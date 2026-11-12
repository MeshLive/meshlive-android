"""Bluetooth stack health for LE CoC call runs: read-only log analysis, no phone access.

Two faults look alike from the runner (coc-listener-exhaustion report, 2026-11-05):

- A wedged Bluetooth main thread (seen on the S22, Android 16): during a CoC call the stack thread that logs
  `BTA_JvL2capRead` stops while other stack threads keep logging; the app then ends the call with "Call peer stopped
  responding" (wrongly blaming the peer), and the next listen hangs inside the stack (`btsock_listen: Attempting
  listen ...` with no `Allocated l2cap socket structure`), so the app never logs `call available`. Every later run in
  the same Bluetooth process hangs the same way until Bluetooth restarts.
- Android 10 LE CoC exhaustion (the A15s): the stack never stops a closed LE CoC server, so each app process leaks one
  registration per role until about 12 processes use the stack up; the listen then throws `IOException: Error: -1`.

Remediation (restarting Bluetooth) belongs to the phone's owner; this module only detects and explains.
"""
import collections
import os
import re
from datetime import datetime
from pathlib import Path

LOCAL_STACK_WEDGED = "local_bt_stack_wedged"
LISTEN_STALLED = "coc_listen_stalled_in_stack"
LISTENER_EXHAUSTED = "coc_listener_exhausted"
A10_BUDGET = "a15s_coc_budget"

WEDGE_SILENCE_S = 5.0
LISTEN_GRACE_S = 3.0
# A healthy Android 10 stack took 12 app processes after a Bluetooth enable and failed the 13th (rw-P1b*); block one
# process early so a run never spends its deadline on a stack that is about to refuse it.
A10_BUDGET_BLOCK_AT = 11

STACK_LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$")
PEER_STOPPED = "peer stopped responding"
SOCKET_CHANNEL = re.compile(r"createSocketChannel: type=4\b.*from uid/pid=\d+/(\d+)")
ATTEMPT_LISTEN = re.compile(r"btsock_listen: Attempting listen for socket connections.*\btype: 4\b")
ALLOCATED = "Allocated l2cap socket structure"
STALLED_LINE = re.compile(r"BENCHMARK call_availability: state=failed \| stage=coc_listen \| reason=stalled")
AVAILABILITY_FAILED = "Private call availability failed"
EXHAUSTED_ERROR = re.compile(r"IOException: Error: -1\b")
COC_LISTEN_LINE = re.compile(r"LE CoC (?:listener lease: .*\bpsm=\d+|registration: kind=listen\b)")


def at(value):
    """Seconds of a threadtime stamp (the year is not in the log, as in score_device_run.timestamp)."""
    return datetime.strptime("2000-" + value, "%Y-%m-%d %H:%M:%S.%f").timestamp()


def rows(text):
    result = []
    for number, line in enumerate(text.splitlines(), 1):
        match = STACK_LINE.match(line)
        if match:
            stamp, pid, tid, level, tag, message = match.groups()
            result.append({"time": stamp, "at": at(stamp), "pid": int(pid), "tid": int(tid), "level": level,
                           "tag": tag.strip(), "message": message, "line": number})
    return result


def detect_wedge(stack_text, app_text, silence_s=WEDGE_SILENCE_S):
    """The first "Call peer stopped responding" end that a silent stack main thread explains, or None.

    The main thread is the one that logs `BTA_JvL2capRead`. It is wedged when it logged nothing for `silence_s`
    or more before the app's end while other threads of the same stack process kept logging (3 of 3 S22 wedges,
    0 false positives in 96 S22 CoC calls with stack logs)."""
    stack = rows(stack_text)
    reads = [row for row in stack if "BTA_JvL2capRead" in row["message"]]
    if not reads:
        return None
    main = collections.Counter(row["tid"] for row in reads).most_common(1)[0][0]
    ends = [row for row in rows(app_text) if row["message"].startswith("Private call ended:")
            and PEER_STOPPED in row["message"].lower()]
    for end in ends:
        before = [row for row in stack if row["tid"] == main and row["at"] <= end["at"]]
        if not before:
            continue
        last = before[-1]
        others = sum(1 for row in stack if row["tid"] != main and last["at"] < row["at"] <= end["at"])
        silent = end["at"] - last["at"]
        if silent >= silence_s and others:
            return {"reason": LOCAL_STACK_WEDGED, "stack_pid": last["pid"], "main_tid": main,
                    "main_last_line": last["time"], "main_last_message": last["message"][:200],
                    "app_end": end["time"], "app_end_message": end["message"][:200],
                    "main_silent_s": round(silent, 3), "other_thread_lines": others}
    return None


def pending_listen(stack_text, app_pid=None, since=None, grace_s=LISTEN_GRACE_S):
    """The first LE CoC listen the stack began but never allocated, or None.

    Only stacks that log both `btsock_listen: Attempting listen` and `Allocated l2cap socket structure` (current
    AOSP, both at info) can match: Android 10 logs neither line in this form. A healthy listen allocates on the
    same binder thread within about 1 ms. Returns the attempt and whether the log itself has run `grace_s` past it."""
    stack = rows(stack_text)
    latest = stack[-1]["at"] if stack else None
    channel_pid = {}
    for index, row in enumerate(stack):
        if since is not None and row["at"] < since:
            continue
        channel = SOCKET_CHANNEL.search(row["message"])
        if channel:
            channel_pid[row["tid"]] = int(channel.group(1))
            continue
        if not ATTEMPT_LISTEN.search(row["message"]):
            continue
        caller = channel_pid.pop(row["tid"], None)
        if app_pid is not None and caller is not None and caller != app_pid:
            continue
        allocated = any(later["tid"] == row["tid"] and ALLOCATED in later["message"]
                        and later["at"] - row["at"] <= grace_s for later in stack[index + 1:])
        if not allocated:
            return {"time": row["time"], "at": row["at"], "tid": row["tid"], "stack_pid": row["pid"],
                    "app_pid": caller, "log_past_grace": latest is not None and latest - row["at"] >= grace_s}
    return None


def app_availability_block(app_text, since=None, pid=None):
    """(reason, evidence line) when the app itself shows why it never became available, else None."""
    app = [row for row in rows(app_text) if (since is None or row["at"] >= since) and (pid is None or row["pid"] == pid)]
    for index, row in enumerate(app):
        if STALLED_LINE.search(row["message"]):
            return LISTEN_STALLED, row["message"]
        if row["message"].startswith(AVAILABILITY_FAILED):
            # Log.w with a throwable: the exception follows on the next lines of the same process and instant.
            for later in app[index + 1:index + 4]:
                if later["pid"] == row["pid"] and later["at"] - row["at"] <= 1.0 and EXHAUSTED_ERROR.search(later["message"]):
                    return LISTENER_EXHAUSTED, f"{row['message']}: {later['message']}"
    return None


def coc_listen_pids(app_text):
    """App processes in this log that registered an LE CoC listener (shared lease or listen)."""
    return {row["pid"] for row in rows(app_text) if COC_LISTEN_LINE.search(row["message"])}


def recent_manifests(runs_root, since_epoch):
    """Execution manifests under suite roots changed since `since_epoch` (a suite root's mtime moves with its files)."""
    found = []
    try:
        roots = [entry for entry in os.scandir(runs_root) if entry.is_dir() and entry.stat().st_mtime >= since_epoch]
    except OSError:
        return found
    for root in roots:
        for path in Path(root.path).glob("*/physical/manifest.json"):
            try:
                if path.stat().st_mtime >= since_epoch:
                    found.append(path)
            except OSError:
                continue
    return found


def stack_history(manifests, phone, stack_pid, read_json):
    """What earlier executions recorded about this phone's current Bluetooth process: wedges and CoC app processes."""
    wedges, pids, used = [], set(), []
    for path in manifests:
        try:
            manifest = read_json(path)
        except Exception:
            continue
        stacks = [item for item in manifest.get("bluetooth_stack_logs", [])
                  if item.get("device") == phone and item.get("pid") == stack_pid]
        if not stacks:
            continue
        used.append(str(path))
        wedges += [{**item, "manifest": str(path)} for item in manifest.get("bluetooth_stack_wedges", [])
                   if item.get("device") == phone and item.get("stack_pid") == stack_pid]
        for log in manifest.get("logs", []):
            if log.get("device") != phone:
                continue
            try:
                text = (path.parent / log["path"]).read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            pids |= coc_listen_pids(text)
    return {"manifests": used, "wedges": wedges, "coc_app_processes": sorted(pids)}


def bluetooth_manager_state(dump):
    """The adapter state in `dumpsys bluetooth_manager` ("ON", "TURNING_OFF", ...), or None when not stated."""
    match = re.search(r"^\s*state:\s*([A-Z_]+)\s*$", dump or "", re.MULTILINE)
    return match.group(1) if match else None


def process_age_s(proc_stat, uptime, ticks_per_s=100):
    """Seconds since a process started, from /proc/<pid>/stat field 22 and /proc/uptime; None if unreadable."""
    try:
        fields = proc_stat.rsplit(")", 1)[1].split()
        return float(uptime.split()[0]) - int(fields[19]) / ticks_per_s
    except (IndexError, ValueError):
        return None


def stack_preflight(phone, api, stack_pid, state, history):
    """The read-only CoC preflight verdict: None, or the blocking reason. Remediation stays with the phone's owner."""
    if not stack_pid:
        return f"{phone}: Bluetooth process not found; Bluetooth state unknown"
    if history["wedges"]:
        wedge = history["wedges"][0]
        return (f"{phone}: {LOCAL_STACK_WEDGED}: Bluetooth process {stack_pid} stopped responding in "
                f"{wedge.get('manifest')}; restart Bluetooth (owner)")
    if state is not None and state != "ON":
        return f"{phone}: Bluetooth manager state is {state}, not ON; restart Bluetooth (owner)"
    if api == 29 and len(history["coc_app_processes"]) >= A10_BUDGET_BLOCK_AT:
        return (f"{phone}: {A10_BUDGET}: {len(history['coc_app_processes'])} app processes already listened on "
                f"Bluetooth process {stack_pid} (Android 10 frees none); restart Bluetooth (owner)")
    return None

