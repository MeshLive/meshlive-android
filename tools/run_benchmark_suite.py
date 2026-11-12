"""Schedule existing phone runners and score their evidence without changing app policy."""
import argparse
import concurrent.futures as futures
import contextlib
import datetime as dt
import hashlib
import html
import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_tools import default_adb  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from run_device_benchmark import PACKAGE, Runner, apply_log_loss, phone_tunables, replay_fixture_metadata, tunable_extras, validate_case
from run_private_call_benchmark import CallRunner, call_relays, relay_chain_limit, score_call_manifest, validate_call_case
import user_launch
import acceptance_profiles as acceptance
import applied_arms
import release_join
import score_intercom_call as intercom
from score_device_run import DEFAULT_THRESHOLDS, aggregate_status, as_diagnostic, gate, limits_for, score_manifest
from score_media_delivery import score_phase_delivery
from score_motion_timing import score_motion
from benchmark_case_state import PRIORITY, request_key, select_remaining, verify_result
from benchmark_state_io import iter_state_rows
from debug_image_lifecycle import DebugImages
from evidence_relocation import logical as project_path
from benchmark_layout import resolve as locate, original, output_path, CATALOG, RUNS
import bt_stack_health as stack_health
import benchmark_measurements
import hci_snoop
from capture_benchmark_observation import ObservationRecorder, RecorderStillRunning, prepare as prepare_observation, validate_directions

FAMILIES = acceptance.FAMILIES_V3  # device-run-v3; device-run-v4-user adds acceptance.USER_FAMILIES
UNKNOWN = {"status": "UNMEASURED", "reason": "No connected measurement adapter"}
DEFERRED = "PENDING_ANALYSIS"  # Phones finished and released; scoring not yet done (not scored, not passed)


def read(path):
    return json.loads(resolve(path).read_text(encoding="utf-8-sig"))


def replace_with_retry(temporary, path, attempts=20, delay_s=0.05):
    """Windows refuses a rename while a scanner/indexer briefly holds the old file open."""
    for attempt in range(attempts):
        try:
            temporary.replace(path)
            return
        except PermissionError:
            if attempt == attempts - 1:
                raise
            time.sleep(delay_s)


def write(path, value):
    path = Path(path)
    DebugImages._guard(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    created = False
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as handle:
            created = True
            handle.write(json.dumps(value, indent=2, allow_nan=False) + "\n")
        DebugImages._guard(path)
        replace_with_retry(temporary, path)
    finally:
        if created and temporary.exists():
            temporary.unlink()


def digest(path):
    with resolve(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def resolve(path, base=None):
    return locate(path, base=base, root=ROOT)


def located(path):
    """Resolved project location; a finished run moved behind a declared junction keeps its project path."""
    return project_path(path, ROOT)


def status(items):
    # DIAGNOSTIC rows are reported with their measured verdict but never decide a family.
    states = [item.get("status") for item in items if item.get("status") != "DIAGNOSTIC"]
    if "FAIL" in states:
        return "FAIL"
    return "PASS" if states and all(state == "PASS" for state in states) else "UNMEASURED"


def mode(case):
    return "private_call" if "caller" in case else "broadcast"


def participants(case, devices):
    return (validate_call_case if mode(case) == "private_call" else validate_case)(case, devices)


def load_jobs(path, devices, selected=()):
    """A bundle references authoritative suites; it does not copy or rewrite their cases."""
    return list(iter_jobs(path, devices, selected))


def iter_jobs(path, devices, selected=()):
    """Yield the jobs one suite group at a time, so a large inventory is never fully resident.

    The bundle-wide checks (duplicate IDs, absent selected cases, an empty selection, participating phones
    sharing a physical identity) run as the generator advances and when it is exhausted.
    """
    path = resolve(path)
    data = read(path)
    groups = data.get("suites", [{"path": str(path)}])
    ids, used = set(), set()
    for group in groups:
        source = resolve(group["path"])
        suite = data if source == path else read(source)
        wanted = set(group["case_ids"]) if "case_ids" in group else None
        cases = suite["cases"]
        available = {case["id"] for case in cases}
        if wanted is not None and wanted - available:
            raise ValueError(f"Unknown cases in {source}: {sorted(wanted - available)}")
        execution = group.get("execution", "isolated")
        if execution not in {"isolated", "concurrent"}:
            raise ValueError("Execution must explicitly be isolated or concurrent")
        profile = group.get("acceptance_profile", acceptance.V3)
        known = acceptance.families_for(profile)
        required = group.get("required_families", list(known))
        if not required or set(required) - set(known):
            raise ValueError("Required measurement families must be known and nonempty")
        suite_sha256 = digest(source)
        observation = prepare_observation(group["observation_capture"], original(path).parent) if "observation_capture" in group else None
        for case in cases:
            if wanted is not None and case["id"] not in wanted or selected and case["id"] not in selected:
                continue
            phones = participants(case, devices)
            if observation:
                validate_directions(observation, case)
            # A voice call carries no video: picture and smoothness have nothing to measure, so they are not
            # applicable (recorded on the job), never silently passed. Every other required family still applies.
            voice = mode(case) == "private_call" and intercom.is_voice(case)
            case_required = [name for name in required if not (voice and name in intercom.VIDEO_ONLY_FAMILIES)]
            if not case_required:
                raise ValueError(f"{case['id']}: a voice call has no applicable required family")
            if case["id"] in ids:
                raise ValueError("Duplicate case IDs must be separated into explicit suite executions")
            ids.add(case["id"])
            used.update(phones)
            yield {"case": case, "phones": sorted(phones), "mode": mode(case),
                         "devices": {phone: {key: devices[phone][key] for key in ("serial", "node_prefix")}
                                     for phone in sorted(phones)},
                         "execution": execution, "required_families": case_required,
                         **({"not_applicable_families": [name for name in required if name not in case_required]}
                            if voice and case_required != required else {}),
                         **({"acceptance_profile": profile} if profile != acceptance.V3 else {}),
                         "suite": str(source), "suite_sha256": suite_sha256,
                         **({"observation_capture": observation, "capture_resources": observation["resources"]} if observation else {})}
        del suite, cases, available
    if set(selected) - ids:
        raise ValueError(f"Selected cases absent: {sorted(set(selected) - ids)}")
    if not ids:
        raise ValueError("No cases selected")
    used = sorted(used)
    for field in ("serial", "node_prefix"):
        identities = [devices[phone][field] for phone in used]
        if len(set(identities)) != len(identities):
            raise ValueError(f"Participating aliases share a physical {field}; correct ownership before scheduling")


def batches(jobs, parallel):
    """Parallelize disjoint concurrent cases; isolated cases are barriers."""
    waiting = list(jobs)
    while waiting:
        first = waiting.pop(0)
        batch, occupied = [first], job_resources(first)
        if first["execution"] == "concurrent":
            index = 0
            while index < len(waiting) and len(batch) < parallel:
                job = waiting[index]
                if job["execution"] == "isolated":
                    break
                if not occupied.intersection(job_resources(job)):
                    occupied.update(job_resources(job))
                    batch.append(waiting.pop(index))
                else:
                    index += 1
        yield batch


def job_resources(job):
    return {"phone:" + phone for phone in job["phones"]} | set(job.get("capture_resources", []))


def job_roles(job):
    case = job["case"]
    if "caller" in case:
        return [(case["caller"], "caller"), *[(relay, "middle") for relay in call_relays(case)], (case["callee"], "callee")]
    parents = {receiver["parent"] for receiver in case["receivers"]}
    return [(case["source"], "sender"), *[(receiver["phone"], "middle" if
        receiver["phone"] in parents or receiver.get("forwarding_enabled") else "receiver")
        for receiver in case["receivers"]]]


def schedule_ready(waiting, active, phone_counts, role_counts, parallel):
    """Choose a free group fairly; an isolated case is a barrier, not a concurrent lane."""
    if len(active) >= parallel or any(job["execution"] == "isolated" for job in active):
        return None
    barrier = next((index for index, job in enumerate(waiting) if job["execution"] == "isolated"), len(waiting))
    if barrier == 0:
        return waiting[0] if not active else None
    occupied = set().union(*(job_resources(job) for job in active))
    eligible = [(index, job) for index, job in enumerate(waiting[:barrier])
                if not occupied.intersection(job_resources(job))]
    def rank(item):
        index, job = item
        counts = [phone_counts.get(phone, 0) for phone in job["phones"]]
        roles = [role_counts.get(role, 0) for role in job_roles(job)]
        return (PRIORITY[job.get("state", "NOT RUN")], max(counts), sum(counts) / len(counts),
                max(roles), sum(roles) / len(roles), index)
    return min(eligible, key=rank)[1] if eligible else None


class PrerequisiteError(RuntimeError):
    pass


class ThermalBlocked(PrerequisiteError):
    """A participant stayed hot (thermal status MODERATE or higher) for the whole cool-down wait: the case is BLOCKED
    with reason `thermal`, kept visible, never silently skipped."""
    reason_code = "thermal"


class BatterySaverBlocked(PrerequisiteError):
    """A participant has Battery Saver on while the case asks for Wi-Fi: the app withholds Wi-Fi then
    (WifiEligibility.batteryOrThermalBlocks), so the call would run on Bluetooth. BLOCKED with reason `battery_saver_on`
    before any phone is touched; Battery Saver is the owner's setting and is never changed here."""
    reason_code = "battery_saver_on"


def check_cancelled(job):
    event = job.get("_cancel_event")
    if event is not None and event.is_set():
        raise InterruptedError("Suite stopped by user")


def validate_capabilities(case, capabilities):
    """Validate the exact offered settings, never substitute a similar profile."""
    if mode(case) == "private_call":
        # A user-launch request that cannot work (tools/user_launch.py) is BLOCKED here, before any phone is touched.
        found = user_launch.blockers(case, [case["caller"], case["callee"], *call_relays(case)])
        if found:
            raise PrerequisiteError(f"case: {found[0][0]}: " + "; ".join(detail for _, detail in found))
    replay = replay_fixture_metadata(case) if case.get("replay_file") else None
    profiles = case["profiles"] if mode(case) == "private_call" else {case["source"]: case["profile"]}
    for sender, profile in profiles.items():
        if user_launch.is_app_default(profile):
            continue  # the app picks its encoder and profile as a person's launch does; the run records what it chose
        caps = capabilities[sender]
        encoder = next((item for item in caps.get("encoders", []) if item["name"] == profile["codec"]), None)
        if encoder is None or profile.get("rate_control", "cbr") not in encoder.get("rate_controls", []):
            raise PrerequisiteError(f"{sender}: requested encoder/allocation is not offered")
        if replay and encoder["codec"] != {"VP90": "vp9", "AV01": "av1"}[replay["codec"]]:
            raise PrerequisiteError(f"{sender}: finite replay codec differs from the requested offered encoder")
        # A Wi-Fi carrier case with wifi_exact_kbps asks the encoder for exactly that rate outside the Settings ladder
        # (SWITCHES "Exact call bitrate on Wi-Fi"): the geometry/FPS must still be an offered mode, the bitrate need not
        # be a Settings step, and the Bluetooth transport maximum does not apply. The app logs the effective rate.
        exact = (case.get("tunables") or {}).get("wifi_exact_kbps")
        wifi_exact = (bool(case.get("carrier")) and isinstance(exact, int) and not isinstance(exact, bool)
                      and exact >= profile["bitrate_kbps"])
        keys = ("width", "height") if wifi_exact else ("width", "height", "bitrate_kbps")
        if not any(all(item.get(key) == profile[key] for key in keys)
                   and profile["fps"] in item.get("fps", []) for item in encoder.get("modes", [])):
            raise PrerequisiteError(f"{sender}: requested geometry/FPS/bitrate is not offered")
        maximum = caps.get("transport_max_kbps", {}).get(case["transport"])
        if maximum is not None and profile["bitrate_kbps"] > maximum and not wifi_exact:
            raise PrerequisiteError(f"{sender}: requested bitrate exceeds the offered transport setting")
        receivers = [case["callee"] if sender == case["caller"] else case["caller"]] if mode(case) == "private_call" else [
            item["phone"] for item in case["receivers"]]
        for receiver in receivers:
            offered = capabilities[receiver]
            if encoder["codec"] not in offered.get("decoders", []):
                raise PrerequisiteError(f"{receiver}: no offered decoder for {sender}'s {encoder['codec']}")
            playback = case["profiles"][receiver].get("playback", "balanced") if mode(case) == "private_call" else profile.get("playback", "balanced")
            if "playback" in offered and playback not in offered["playback"] and not (
                    mode(case) == "private_call" and user_launch.is_app_default(case["profiles"][receiver])):
                raise PrerequisiteError(f"{receiver}: requested playback buffer is not offered")


def process_alive(pid):
    """True while a process with [pid] exists (a reused PID also counts: reclaiming waits rather than guesses)."""
    if os.name == "nt":
        import ctypes
        kernel = ctypes.windll.kernel32
        handle = kernel.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return ctypes.GetLastError() == 5  # access denied: it exists
        try:
            code = ctypes.c_ulong()
            return not kernel.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value == 259  # STILL_ACTIVE
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


class DeviceLease:
    """Cross-process ownership for cooperating suite commands, keyed by physical identity."""
    def __init__(self, directory, devices, phones, isolated, owner, capture_resources=()):
        self.directory, self.owner = Path(directory), owner
        self.token = uuid.uuid4().hex
        self.phone_keys = {phone: {"phone-" + hashlib.sha256(value.encode()).hexdigest()[:24]
                                  for value in (devices[phone]["serial"], devices[phone]["node_prefix"])}
                           for phone in phones}
        self.keys = sorted(set().union(*self.phone_keys.values()) |
                           {"capture-" + hashlib.sha256(value.encode()).hexdigest()[:24] for value in capture_resources})
        self.isolated, self.created, self.quarantined = isolated, [], set()

    @contextlib.contextmanager
    def guard(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        lock = self.directory / "registry.lock"
        deadline = time.monotonic() + 5
        while True:
            try:
                descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                break
            except FileExistsError:
                if time.monotonic() >= deadline:
                    raise PrerequisiteError("Lease registry busy; inspect its owner before clearing any stale lock")
                time.sleep(.05)
        try:
            os.close(descriptor)
            yield
        finally:
            lock.unlink()

    def reclaim_dead(self):
        """Under [guard]: drop every lease set whose owning process has ended, unless any of its files is QUARANTINED
        (a phone or instrument left in an unconfirmed state stays blocked until a person resolves it). A killed runner
        never reaches [release], so its leases would otherwise block every later suite on those phones."""
        sets = {}
        for path in self.directory.glob("*.json"):
            try:
                lease = read(path)
            except (OSError, ValueError):
                continue
            sets.setdefault((lease.get("token"), lease.get("pid")), []).append((path, lease))
        reclaimed = []
        for (token, pid), files in sets.items():
            if not token or not isinstance(pid, int) or pid == os.getpid() or process_alive(pid):
                continue
            if any(lease.get("status") == "QUARANTINED" for _, lease in files):
                continue
            for path, _ in files:
                path.unlink()
                reclaimed.append(path.name)
        if reclaimed:
            print(f"Reclaimed leases of ended processes: {', '.join(sorted(reclaimed))}", file=sys.stderr)
        return reclaimed

    def __enter__(self):
        with self.guard():
            self.reclaim_dead()
            active = list(self.directory.glob("*.json"))
            if any((self.directory / (key + ".json")).exists() for key in self.keys):
                raise PrerequisiteError("Another suite owns a participating physical phone or recording resource")
            if (self.directory / "isolated.json").exists() or self.isolated and active:
                raise PrerequisiteError("Another suite occupies the controlled radio window")
            keys = self.keys + (["isolated"] if self.isolated else ["session-" + self.token])
            try:
                for key in keys:
                    path = self.directory / (key + ".json")
                    self.created.append(path)
                    write(path, {"owner": self.owner, "pid": os.getpid(), "token": self.token})
            except BaseException:
                self.release()
                raise
        return self

    def release(self):
        for path in self.created:
            if path in self.quarantined:
                continue
            if path.exists() and read(path).get("token") == self.token:
                path.unlink()
            temporary = path.with_name(path.name + ".tmp")
            if temporary.exists():
                temporary.unlink()

    def quarantine_capture(self, reason, recording):
        """Keep only affected instrument keys until the named live process is resolved."""
        for path in self.created:
            if path.name.startswith("capture-"):
                self.quarantined.add(path)
                write(path, {**read(path), "status": "QUARANTINED", "reason": reason,
                             "recording_receipt": str(recording.output / "recording.json"),
                             "recorder_pid": recording.result.get("process_pid")})

    def quarantine_phone(self, phone, reason, receipt):
        for path in self.created:
            if path.stem in self.phone_keys[phone] or path.name == "isolated.json":
                self.quarantined.add(path)
                write(path, {**read(path), "status": "QUARANTINED", "reason": reason,
                             "phone": phone, "cleanup_receipt": str(receipt)})

    def __exit__(self, *_):
        with self.guard():
            self.release()


def awake_unlocked(power, policy):
    awake = bool(re.search(r"\bmWakefulness=Awake\b", power) or
                 re.search(r"(?:mAwake=true|mScreenOnFully=true)", policy))
    unlocked = bool(re.search(r"(?:mShowingLockscreen|isStatusBarKeyguard|showing)=false\b", policy))
    locked = bool(re.search(r"(?:mShowingLockscreen|isStatusBarKeyguard|showing|inputRestricted)=true\b", policy))
    return awake and unlocked and not locked


CLEANUP_PROBES = 6  # a process that is still exiting right after the call gets a fresh force-stop and up to ~3 s
CLEANUP_PROBE_DELAY_S = .5


def verify_phone_cleanup(adb, devices, phones, output, lease, sleep=time.sleep):
    receipt = {"status": "STOPPED", "phones": {}}
    manifest_path = output / "physical" / "manifest.json"
    # A user-launch case rewrote the app's stored Settings choices; a phone whose file could not be proven back to the
    # exact bytes it had would start the next (benchmark) launch with the person's choices on: it is quarantined.
    unrestored = {item["device"] for item in (read(manifest_path) if manifest_path.is_file() else {}).get(
        "user_settings_restored", []) if item.get("status") != "RESTORED"}
    # A planned wifi_off leaves Wi-Fi off until the runner's own restore reads it back on; a phone left without proof
    # would join the next case with no Wi-Fi carrier.
    wifi_unrestored = {item["device"] for item in (read(manifest_path) if manifest_path.is_file() else {}).get(
        "wifi_restored", []) if item.get("status") != "RESTORED"}
    for phone in phones:
        item = receipt["phones"][phone] = {}
        serial = devices[phone]["serial"]
        stopped = False
        for probe in range(1, CLEANUP_PROBES + 1):
            try:
                result = subprocess.run([adb, "-s", serial, "shell", "pidof", PACKAGE],
                                        capture_output=True, encoding="utf-8", errors="replace", timeout=5)
                item.update(pidof_returncode=result.returncode, pidof_stdout=result.stdout.strip(),
                            pidof_stderr=result.stderr.strip(), probes=probe)
                item.pop("error", None)
                stopped = result.returncode in (0, 1) and not result.stdout.strip() and not result.stderr.strip()
            except (OSError, subprocess.TimeoutExpired) as error:
                item.update(error=str(error), probes=probe)
                stopped = False
            if stopped or probe == CLEANUP_PROBES:
                break
            # A pid seen right after the call is usually the process still exiting (false quarantines on the A15s and
            # ASUS, 2026-11-04): stop it again and look once more before declaring the phone unconfirmed.
            with contextlib.suppress(OSError, subprocess.TimeoutExpired):
                subprocess.run([adb, "-s", serial, "shell", "am", "force-stop", PACKAGE],
                               capture_output=True, encoding="utf-8", errors="replace", timeout=5)
                item["force_stops"] = item.get("force_stops", 0) + 1
            sleep(CLEANUP_PROBE_DELAY_S)
        item["status"] = "STOPPED" if stopped else "UNCONFIRMED"
        if phone in unrestored:
            item.update(status="UNCONFIRMED", user_settings_restore="UNVERIFIED")
        if phone in wifi_unrestored:
            item.update(status="UNCONFIRMED", wifi_restore="UNVERIFIED")
        if item["status"] != "STOPPED":
            receipt["status"] = "UNCONFIRMED"
            lease.quarantine_phone(phone, "Stored user settings could not be restored" if phone in unrestored
                                   else "Wi-Fi could not be turned back on" if phone in wifi_unrestored
                                   else "Phone cleanup is unconfirmed", output / "cleanup.json")
    write(output / "cleanup.json", receipt)
    if manifest_path.is_file():
        manifest = read(manifest_path)
        manifest["phone_cleanup"] = {"path": "../cleanup.json", "sha256": digest(output / "cleanup.json"),
                                     "status": receipt["status"]}
        write(manifest_path, manifest)
    if receipt["status"] != "STOPPED":
        raise RuntimeError("Phone cleanup is unconfirmed; quarantined phones cannot be reassigned")


BLUETOOTH_ADDRESS = r"[0-9A-Fa-fXx]{2}(?::[0-9A-Fa-fXx]{2}){5}"  # Samsung masks leading octets


def bluetooth_bonds(dump):
    """Local adapter address and bonded peer addresses from `dumpsys bluetooth_manager`."""
    local = re.search(rf"^\s*address:\s*({BLUETOOTH_ADDRESS})\s*$", dump, re.MULTILINE | re.IGNORECASE)
    bonded, section = set(), False
    for line in dump.splitlines():
        if re.match(r"\s*Bonded devices:\s*$", line):
            section = True
            continue
        if section:
            found = re.findall(BLUETOOTH_ADDRESS, line)
            if not found:
                break
            bonded.update(value.upper() for value in found)
    return (local.group(1).upper() if local else None), bonded


def classic_edges(case):
    """Ordered parent/child (or caller/callee) pairs that must open RFCOMM to each other."""
    if case.get("caller") and case.get("callee"):
        route = [case["caller"], *call_relays(case), case["callee"]]
        return list(zip(route, route[1:]))
    return [(node["parent"], node["phone"]) for node in case.get("receivers", []) if node.get("parent")]


def bonded_to(bonded, address):
    """Samsung prints XX:XX:XX:XX:hh:hh; masked forms can only be compared by the visible suffix."""
    if "X" in address:
        return any(item[-5:] == address[-5:] for item in bonded)
    return any(item == address or "X" in item and item[-5:] == address[-5:] for item in bonded)


def classic_bond_free(case):
    """Experimental bond-free Classic (app tunable `classic_bond_free`) uses insecure RFCOMM plus the
    app's own BMH2 authentication on every role, so missing or one-sided bonds are recorded, not blocking.
    A one-sided stale bond can still fail on stacks that refuse a missing key (AOSP key_missing flag)."""
    return (case.get("tunables") or {}).get("classic_bond_free") is True


def missing_classic_bonds(identities, edges):
    """Classic RFCOMM needs a mutual Android bond; a one-sided or absent bond timed out silently."""
    missing = []
    for first, second in edges:
        a, b = identities.get(first, {}), identities.get(second, {})
        if not a.get("address") or not b.get("address"):
            missing.append(f"{first}/{second}: local Bluetooth address unavailable")
        elif not bonded_to(a["bonded"], b["address"]) or not bonded_to(b["bonded"], a["address"]):
            missing.append(f"{first}/{second}: not mutually paired "
                           f"({first} has {second}: {bonded_to(a['bonded'], b['address'])}; "
                           f"{second} has {first}: {bonded_to(b['bonded'], a['address'])})")
    return missing


VERIFIED_INSTALLS = {}


def coc_stack_preflight(adb, serial, phone, api, runs_root=None):
    """Read-only LE CoC stack check: the Bluetooth process must be running, not recorded as wedged by an earlier
    execution, in manager state ON, and on Android 10 below the app-process budget. Remediation (a Bluetooth restart)
    stays with the phone's owner. Returns (evidence, blocking reason or None)."""
    def shell(*args, timeout=10):
        return subprocess.run([adb, "-s", serial, "shell", *args], capture_output=True, encoding="utf-8",
                              errors="replace", timeout=timeout)
    evidence = {}
    pid = None
    for package in ("com.android.bluetooth", "com.google.android.bluetooth"):
        found = shell("pidof", package).stdout.strip()
        if found.isdigit():
            pid, evidence["process"] = int(found), package
            break
    evidence["pid"] = pid
    try:
        # A wedged stack main thread can hang this dump: a timeout is itself a reason to block.
        dump = shell("dumpsys", "bluetooth_manager", timeout=30).stdout
    except subprocess.TimeoutExpired:
        evidence["manager_state"] = "TIMEOUT"
        return evidence, f"{phone}: dumpsys bluetooth_manager timed out (Bluetooth may be wedged); restart Bluetooth (owner)"
    evidence["manager_state"] = stack_health.bluetooth_manager_state(dump)
    since = 0.0
    if pid:
        age = stack_health.process_age_s(shell("cat", f"/proc/{pid}/stat").stdout, shell("cat", "/proc/uptime").stdout)
        evidence["process_age_s"] = None if age is None else round(age, 1)
        # Manifests older than the Bluetooth process cannot describe it (an hour of margin for clock skew).
        since = time.time() - age - 3600 if age is not None else time.time() - 7 * 86400
    manifests = stack_health.recent_manifests(runs_root or RUNS, since) if pid else []
    history = stack_health.stack_history(manifests, phone, pid, read)
    evidence.update(earlier_executions=len(history["manifests"]), wedges=history["wedges"],
                    coc_app_processes=len(history["coc_app_processes"]))
    return evidence, stack_health.stack_preflight(phone, api, pid, evidence["manager_state"], history)


def wifi_client_mhz(status):
    """The Wi-Fi client's frequency from `adb shell cmd wifi status`, or None when not connected or unreadable."""
    match = re.search(r"Frequency:\s*(\d+)", status or "")
    return int(match.group(1)) if match else None


def preflight(adb, devices, job, output, expected_hash):
    """Read-only checks. Permission/identity failures block rather than modify phones."""
    check_cancelled(job)
    evidence = {}
    if job["execution"] == "isolated":
        peers = evidence["quiet_peers"] = {}
        for phone in sorted(set(devices) - set(job["phones"])):
            check_cancelled(job)
            prefix = [adb, "-s", devices[phone]["serial"]]
            item = peers[phone] = {}
            def probe(*operation):
                try:
                    return subprocess.run([*prefix, *operation], capture_output=True, encoding="utf-8",
                                          errors="replace", timeout=5)
                except subprocess.TimeoutExpired as error:
                    item.update(operation=list(operation), error=str(error))
                    write(output / "preflight.json", evidence)
                    raise PrerequisiteError(f"{phone}: quiet-peer probe timed out; quiet state unknown") from error
            state = probe("get-state")
            item.update(adb_state=state.stdout.strip(), error=state.stderr.strip())
            if state.returncode or item["adb_state"] != "device":
                write(output / "preflight.json", evidence)
                raise PrerequisiteError(f"{phone}: isolated quiet state is unknown; use a verified registry scope")
            check_cancelled(job)
            process = probe("shell", "pidof", PACKAGE)
            item.update(pidof_returncode=process.returncode, pidof_stdout=process.stdout.strip(),
                        pidof_stderr=process.stderr.strip())
            if process.stdout.strip() or process.returncode not in (0, 1) or process.stderr.strip():
                write(output / "preflight.json", evidence)
                raise PrerequisiteError(f"{phone}: another app process or unknown quiet state; consult its owner")
    for phone in job["phones"]:
        check_cancelled(job)
        serial = devices[phone]["serial"]
        def command(*args):
            check_cancelled(job)
            try:
                result = subprocess.run([adb, "-s", serial, *args], capture_output=True,
                                        encoding="utf-8", errors="replace", timeout=30)
            except subprocess.TimeoutExpired as error:
                item.update(failed_operation=list(args), error=str(error))
                raise PrerequisiteError(f"{phone}: preflight timed out") from error
            if result.returncode:
                raise PrerequisiteError(f"{phone}: {' '.join(args)}: {result.stderr or result.stdout}")
            return result.stdout.strip()
        item = evidence[phone] = {"serial": serial, "claimed_owner": job["owner"]}
        try:
            item["adb_state"] = command("get-state")
            item["bluetooth_on"] = command("shell", "settings", "get", "global", "bluetooth_on")
            item["window_policy"] = command("shell", "dumpsys", "window", "policy")
            item["power"] = command("shell", "dumpsys", "power")
            item["package"] = command("shell", "dumpsys", "package", PACKAGE)
            item["api"] = int(command("shell", "getprop", "ro.build.version.sdk"))
            needed = ["CAMERA", "RECORD_AUDIO"] + (["BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"]
                      if item["api"] >= 31 else ["ACCESS_FINE_LOCATION"])
            carrier = job["case"].get("carrier")
            if carrier:
                if item["api"] >= 33:
                    needed.append("NEARBY_WIFI_DEVICES")
                elif item["api"] >= 31:
                    needed.append("ACCESS_FINE_LOCATION")  # API 29-30 already list it for Bluetooth scanning above
                item["wifi_on"] = command("shell", "settings", "get", "global", "wifi_on")
                item["location_mode"] = command("shell", "settings", "get", "secure", "location_mode")
                if item["wifi_on"] != "1":
                    raise PrerequisiteError(f"{phone}: Wi-Fi is off; a Wi-Fi carrier case needs it on")
                if carrier != "wifi_aware" and 29 <= item["api"] <= 32 and item["location_mode"] == "0":
                    raise PrerequisiteError(f"{phone}: Location is off; Android 10-12 need it for Wi-Fi discovery and Wi-Fi Direct")
                try:
                    status = command("shell", "cmd", "wifi", "status")  # names the network: never stored
                except PrerequisiteError:
                    status = ""
                mhz = item["wifi_client_mhz"] = wifi_client_mhz(status)
                if mhz is not None and mhz < 4900 and not job["case"].get("tunables", {}).get("wifi_allow_24ghz"):
                    raise PrerequisiteError(f"{phone}: the Wi-Fi client is on 2.4 GHz ({mhz} MHz); a Wi-Fi carrier case needs it off or on 5 GHz")
            denied = [permission for permission in needed if not re.search(
                rf"android\.permission\.{permission}: granted=true", item["package"])]
            if item["adb_state"] != "device" or item["bluetooth_on"] != "1" or denied:
                raise PrerequisiteError(not_ready_reason(phone, item, denied))
            if not awake_unlocked(item["power"], item["window_policy"]):
                raise PrerequisiteError(f"{phone}: awake/unlocked state is not proved")
            item["battery_saver"] = user_launch.battery_saver(item["power"])
            if item["battery_saver"] and user_launch.wifi_requested(job["case"], phone):
                raise BatterySaverBlocked(f"{phone}: battery_saver_on: dumpsys power says Battery Saver is ON, and the app "
                                          "withholds Wi-Fi while it is on (the call would run on Bluetooth); turn it off "
                                          "on the phone (the owner's setting) and rerun")
            remote = command("shell", "pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
            item["apk_sha256"] = command("shell", "sha256sum", remote).split()[0]
            if item["apk_sha256"].lower() != expected_hash.lower():
                raise PrerequisiteError(f"{phone}: installed APK differs from the frozen candidate")
            install_marker = (serial, remote, re.search(r"lastUpdateTime=(.+)", item["package"] or "") and
                              re.search(r"lastUpdateTime=(.+)", item["package"])[1].strip(), expected_hash.lower())
            if install_marker in VERIFIED_INSTALLS:
                # Same install already pulled and hashed by this suite process; repeating a 30 MB pull
                # per case added minutes of serial overhead without new evidence.
                item["independent_apk_verification"] = {**VERIFIED_INSTALLS[install_marker], "reused_in_process": True}
            else:
                pulled = (output / f"verified-{phone}-base.apk").resolve()
                pulled.relative_to(output.resolve())
                item["apk_pull"] = pull_apk(command, remote, pulled)
                item["independent_apk_verification"] = {"sha256": digest(pulled), "bytes": pulled.stat().st_size}
                if item["independent_apk_verification"]["sha256"].lower() != expected_hash.lower():
                    raise PrerequisiteError(f"{phone}: independently pulled APK differs; retained {pulled}")
                # The extras this build can read, from the verified copy (before it is removed).
                BUILD_TEST_EXTRAS[expected_hash.lower()] = applied_arms.scan_apk(pulled)
                pulled.unlink()  # Verified redundant copy; the digest/size receipt is retained.
                VERIFIED_INSTALLS[install_marker] = dict(item["independent_apk_verification"])
            check_tunables_in_build(phone, job["case"], expected_hash, item)
            item["capabilities"] = json.loads(command("shell", "cat",
                f"/sdcard/Android/data/{PACKAGE}/files/benchmark-capabilities.json"))
            caps = item["capabilities"]
            item["fingerprint"] = command("shell", "getprop", "ro.build.fingerprint")
            if caps["fingerprint"] != item["fingerprint"] or caps["node_prefix"] != devices[phone]["node_prefix"]:
                raise PrerequisiteError(f"{phone}: capability fingerprint or current app identity differs")
            if job.get("hci_snoop", "off") != "off":
                # Recorded fact (snoop mode, verbose-logging properties); only --hci-snoop required gates on it.
                check_cancelled(job)
                item["bluetooth_logging"] = hci_snoop.Collector(adb, serial).logging_state()
                if job["hci_snoop"] == "required" and item["bluetooth_logging"]["snoop_enabled"] != "ENABLED":
                    raise PrerequisiteError(f"{phone}: --hci-snoop required but snoop is "
                                            f"{item['bluetooth_logging']['snoop_enabled']}")
            if job["case"].get("transport") == "ble":
                check_cancelled(job)
                try:
                    item["coc_stack"], blocked = coc_stack_preflight(adb, serial, phone, item["api"])
                except subprocess.TimeoutExpired as error:
                    raise PrerequisiteError(f"{phone}: Bluetooth stack preflight timed out") from error
                if blocked:
                    raise PrerequisiteError(blocked)
        except Exception:
            write(output / "preflight.json", evidence)
            raise
    # 2.4 GHz Wi-Fi halved M31 Bluetooth throughput (GATT 0.39-0.46 vs 0.89 Mbps with Wi-Fi off), so
    # every execution records each participant's Wi-Fi band as a radio condition. Read-only.
    evidence["radio_conditions"] = {phone: wifi_condition(adb, devices[phone]["serial"]) for phone in job["phones"]}
    write(output / "preflight.json", evidence)
    thermal_gate(adb, devices, job, evidence, output)
    if job["case"].get("transport") == "classic":
        identities = {}
        for phone in job["phones"]:
            check_cancelled(job)
            try:
                dump = subprocess.run([adb, "-s", devices[phone]["serial"], "shell", "dumpsys", "bluetooth_manager"],
                                      capture_output=True, encoding="utf-8", errors="replace", timeout=30).stdout
            except subprocess.TimeoutExpired as error:
                raise PrerequisiteError(f"{phone}: Bluetooth bond preflight timed out") from error
            address, bonded = bluetooth_bonds(dump)
            identities[phone] = {"address": address, "bonded": bonded}
        missing = missing_classic_bonds(identities, classic_edges(job["case"]))
        bond_free = classic_bond_free(job["case"])
        evidence["classic_bonds"] = {"phones": {phone: {"address_suffix": (item["address"] or "")[-5:],
                                                        "bonded_count": len(item["bonded"])}
                                                for phone, item in identities.items()},
                                     "missing": missing,
                                     "bond_free": bond_free}
        write(output / "preflight.json", evidence)
        if missing and not bond_free:
            raise PrerequisiteError("Classic requires mutual Android pairing; " + "; ".join(missing))
    validate_capabilities(job["case"], {phone: evidence[phone]["capabilities"] for phone in job["phones"]})
    return evidence


def pull_apk(command, remote, destination):
    """`adb pull` of the installed APK. The first pull after a phone woke from Doze timed out at 30 s (S22): wake the
    screen first (the launch path's KEYCODE_WAKEUP; never a sleep key) and retry a timed-out pull once before
    blocking. Any other failure is not retried."""
    record = {"attempts": 0, "woke": False}
    try:
        command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        record["woke"] = True
    except PrerequisiteError:
        pass  # some OEMs refuse shell input; the pull is still tried
    for attempt in (1, 2):
        record["attempts"] = attempt
        try:
            command("pull", remote, str(destination))
            return record
        except PrerequisiteError as error:
            if attempt == 2 or not isinstance(error.__cause__, subprocess.TimeoutExpired):
                raise
            record["first_timeout"] = str(error)
            try:
                command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            except PrerequisiteError:
                pass


BUILD_TEST_EXTRAS = {}  # frozen APK sha256 -> the `test_` extra names in its dex ({} = unscannable)


def check_tunables_in_build(phone, case, expected_hash, item):
    """A tunable whose extra name is not in the frozen build is ignored by the app without a word, so an arm that
    asks for it is not an arm: BLOCK with the reason, never a silent control run (TEST-14)."""
    in_build = BUILD_TEST_EXTRAS.get(expected_hash.lower())
    if not in_build:
        item["tunables_in_build"] = {"status": "UNMEASURED", "reason": "the APK's dex strings could not be scanned"}
        return
    requested = sorted(tunable_extras(phone_tunables(case, phone)))
    missing = applied_arms.missing_from_build(requested, in_build)
    item["tunables_in_build"] = {"status": "FAIL" if missing else "PASS", "requested": requested, "missing": missing}
    if missing:
        raise PrerequisiteError(f"{phone}: tunable not in this build: {', '.join(missing)}")


def not_ready_reason(phone, item, denied):
    """Name every cause of a failed readiness check: the owner must know to switch Bluetooth on, not look at permissions."""
    causes = []
    if item.get("adb_state") != "device":
        causes.append(f"adb state={item.get('adb_state')!r}")
    if item.get("bluetooth_on") != "1":
        causes.append("Bluetooth off")
    if denied:
        causes.append(f"denied permissions={denied}")
    return f"{phone}: not ready; " + "; ".join(causes)


WIFI_FREQUENCY = re.compile(r"(?:Frequency: ?|frequencyMhz: ?|freq=)(\d{4})")


THERMAL_LIMIT = 2        # android.os.PowerManager THERMAL_STATUS_MODERATE: wait while any participant is at or above it
THERMAL_WAIT_S = 600
THERMAL_POLL_S = 30


def thermal_status(dump):
    """`Thermal Status: N` of `dumpsys thermalservice` (0 NONE ... 6 SHUTDOWN), None when the phone does not say."""
    match = re.search(r"Thermal Status:\s*(\d+)", dump or "")
    return int(match[1]) if match else None


def thermal_gate(adb, devices, job, evidence, output, wait_s=THERMAL_WAIT_S, poll_s=THERMAL_POLL_S,
                 clock=None, sleep=None):
    """Cool-down between back-to-back cases: aw1-20261110f went NONE -> SEVERE on both phones over eight calls and the
    last calls never tried Wi-Fi. Reads every participant's thermal status; while any is MODERATE (2) or higher it waits
    (polling every `poll_s`, at most `wait_s`), then blocks the case with reason `thermal`. The status at the start and
    every poll are recorded in the case's preflight.json either way. A phone that does not report a status is recorded
    as such and never held back."""
    clock, sleep = clock or time.monotonic, sleep or time.sleep

    def read(phone):
        try:
            result = subprocess.run([adb, "-s", devices[phone]["serial"], "shell", "dumpsys", "thermalservice"],
                                    capture_output=True, encoding="utf-8", errors="replace", timeout=15)
            return thermal_status(result.stdout)
        except (subprocess.TimeoutExpired, OSError):
            return None
    started = clock()
    now = {phone: read(phone) for phone in job["phones"]}
    record = evidence["thermal_gate"] = {"limit": THERMAL_LIMIT, "wait_limit_s": wait_s, "poll_s": poll_s,
                                         "at_start": dict(now), "polls": [], "waited_s": 0, "verdict": "COOL"}
    while any(status is not None and status >= THERMAL_LIMIT for status in now.values()):
        record["verdict"] = "WAITED"
        if clock() - started >= wait_s:
            record["verdict"] = "BLOCKED"
            write(output / "preflight.json", evidence)
            hot = {phone: status for phone, status in now.items() if status is not None and status >= THERMAL_LIMIT}
            raise ThermalBlocked("thermal: " + ", ".join(f"{phone} status {status}" for phone, status in sorted(hot.items())) +
                                 f" (MODERATE=2 or higher) after waiting {round(clock() - started)} s for it to cool")
        check_cancelled(job)
        sleep(poll_s)
        now = {phone: read(phone) for phone in job["phones"]}
        record["waited_s"] = round(clock() - started)
        record["polls"].append({"after_s": record["waited_s"], "status": dict(now)})
    record["at_end"] = dict(now)
    write(output / "preflight.json", evidence)
    return record


def wifi_condition(adb, serial):
    try:
        def shell(*command):
            return subprocess.run([adb, "-s", serial, "shell", *command], capture_output=True, encoding="utf-8",
                                  errors="replace", timeout=15).stdout
        enabled = shell("settings", "get", "global", "wifi_on").strip() not in ("", "0")
        match = WIFI_FREQUENCY.search(shell("dumpsys", "wifi")) if enabled else None
        frequency = int(match.group(1)) if match else None
        return {"wifi_on": enabled, "frequency_mhz": frequency,
                "shares_bluetooth_band": frequency is not None and 2400 <= frequency <= 2500}
    except Exception as error:
        return {"status": "UNAVAILABLE", "reason": type(error).__name__}


def comparison_key(manifest):
    ignored = {"id", "state", "environment", "planned_environment", "capability_basis", "eligibility",
               "expected_apk_sha256", "raw_fixture_file", "raw_fixture_files", "replay_file", "family", "load_tier"}
    scenario = {key: value for key, value in manifest["scenario"].items() if key not in ignored}
    fixtures = {phone: {key: fixture.get(key) for key in ("sha256", "width", "height", "frames", "format", "loops")}
                for phone, fixture in manifest.get("source_fixtures", {}).items()}
    if manifest.get("source_fixture"):
        fixtures[manifest["scenario"]["source"]] = {key: manifest["source_fixture"].get(key)
            for key in ("sha256", "width", "height", "frames", "format", "loops")}
    identities = {phone: {key: item.get(key) for key in ("serial", "node_prefix")}
                  for phone, item in manifest.get("devices", {}).items()}
    value = {"scenario": scenario, "source_kind": manifest.get("source_kind"), "fixtures": fixtures,
             "physical_endpoints": identities}
    if manifest.get("replay_fixture"):
        value["replay_fixture"] = {key: manifest["replay_fixture"].get(key) for key in
            ("sha256", "format", "codec", "width", "height", "fps", "frames", "rate", "scale", "first_timestamp", "last_timestamp")}
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def family_for(name):
    if name.startswith("media_age"):
        return "media_age"
    if any(word in name for word in ("discovery", "startup", "first_surface", "recovery_ms", "setup_to_active",
                                     "call_phase_active", "release_join")):
        return "connection"
    if name.startswith(("surface_", "visible_fps", "unexpected_freeze", "encoder_fps", "longest_stall",
                        "frozen_time_share", "effective_profile_fps", "requested_profile_shortfall",
                        "degraded_time_share")):
        return "smoothness"
    if "parent_identity" in name or "original_source_identity" in name or name in {"authenticated_peer", "call_id_mismatches"}:
        return "topology"
    return "transport"


def analyze(manifest_path, output, required=FAMILIES, measurement_contract=None, profile=None):
    if resolve(manifest_path).is_relative_to(located(output)):
        raise ValueError("Analysis output cannot contain original input evidence")
    with DebugImages(output):
        return _analyze(manifest_path, output, required, measurement_contract, profile)


def _analyze(manifest_path, output, required, measurement_contract=None, profile=None):
    version = profile or acceptance.V3
    names = acceptance.families_for(version)
    user = version == acceptance.V4
    if set(required) - set(names):
        raise ValueError(f"Required families are not part of acceptance profile {version}")

    def decides(row):  # Fidelity rows are diagnostics under the user-centred profile.
        return as_diagnostic(row) if user else row
    manifest_path, output = resolve(manifest_path), Path(output)
    manifest_base = original(manifest_path, root=ROOT).parent
    manifest = read(manifest_path)
    output.mkdir(parents=True, exist_ok=True)
    if manifest.get("mode") == "private_call":
        score = score_call_manifest(manifest, manifest_base, {"version": version} if user else None)
    else:  # A stop on a one-time line that logd confirmably dropped is UNMEASURED, never FAIL.
        score = apply_log_loss(score_manifest(manifest, manifest_base, {"version": version} if user else None),
                               manifest, manifest_base)
    write(output / "computed-score.json", score)
    families = {name: {**UNKNOWN, "measurements": [],
                       **({"reason": acceptance.FAMILY_REASONS[name]} if user and name in acceptance.FAMILY_REASONS else {})}
                for name in names}
    measurements = {name: [] for name in acceptance.KNOWN_FAMILIES}
    errors = []
    for device in score.get("devices", []):
        media = [("broadcast", device)] if manifest.get("mode") != "private_call" else [
            (direction, device[direction]) for direction in ("outgoing", "incoming") if direction in device]
        sections = [("endpoint", device), *media] if manifest.get("mode") == "private_call" else media
        for direction, section in sections:
            scope = section.get("scope", device.get("scope", {}))
            for name, measured in section.get("gates", {}).items():
                measurements[family_for(name)].append({"device": device["device"], "direction": direction,
                    "scope": scope, "name": name, **measured})
            if (direction == "outgoing" or direction == "broadcast" and device.get("role") == "sender") and not section.get("voice"):
                measurements["transport"].append({"device": device["device"], "direction": direction,
                    "scope": scope, "name": "actual_offered_load", **section.get("offered_load", UNKNOWN)})
            excluded = [sample for segment in section.get("surface_segments", []) for sample in segment
                        if sample.get("cadence_lifecycle_overlap")]
            if excluded:
                measurements["smoothness"].append({"device": device["device"], "direction": direction,
                    "scope": scope, "name": "full_lifecycle_cadence_coverage", "status": "UNMEASURED",
                    "excluded_samples": len(excluded),
                    "reason": "Rolling cadence omits samples after a planned gap; a prefix cannot approve the full lifecycle"})
        for action in device.get("actions", []):
            for name, measured in action.items():
                if isinstance(measured, dict) and "status" in measured:
                    item = {"device": device["device"], "scope": device.get("scope", {}),
                            "action": action.get("action"), "name": name, **measured}
                    measurements["connection"].append(item)
                    if action.get("action") not in {"launch", "connection_timeout"}:
                        measurements["lifecycle"].append(item)
        for item in device.get("lifecycle", []):
            if isinstance(item, dict) and "status" in item:
                measurements["lifecycle"].append({"device": device["device"], **item})
    for item in score.get("lifecycle", []):
        measurements["lifecycle"].append({**item, **item.get("gate", UNKNOWN)})
    for hop in score.get("relays", []):
        # The requested opaque hop: its authenticated adjacent identities decide topology; its forwarding
        # counters (not logged by the app) stay UNMEASURED transport evidence, never an implied pass.
        for family, section in (("topology", "gates"), ("transport", "forwarding")):
            for name, measured in hop.get(section, {}).items():
                measurements[family].append({"device": hop["device"], "direction": "relay", "role": "call_relay",
                                             "name": name, **measured})
    for name, measured in score.get("execution_gates", {}).items():
        measurements["transport"].append({"name": name, **measured})
    if manifest.get("mode") == "private_call":
        families["smoothness"]["motion_detail"] = {**UNKNOWN,
            "reason": "Detailed callback scorer supports broadcasts; independent call gates remain measured"}
        directions = [(manifest["scenario"][a], manifest["scenario"][b]) for a, b in (("caller", "callee"), ("callee", "caller"))]
    else:
        motion = score_motion(manifest, score, manifest_base)
        write(output / "motion-timing.json", motion)
        families["smoothness"]["motion_detail"] = {"status": motion["timing_state"],
            "capture_state": motion["capture_state"], "evidence": str(located(output / "motion-timing.json"))}
        directions = [(manifest["scenario"]["source"], node["phone"]) for node in manifest["scenario"]["receivers"]]
    voice = manifest.get("mode") == "private_call" and intercom.is_voice(manifest["scenario"])
    if voice:
        for name in intercom.VIDEO_ONLY_FAMILIES:
            families[name]["reason"] = "Not applicable: a voice call carries no video (voice scoring measures no video gate)"
        families["smoothness"].pop("motion_detail", None)
        directions = []  # No picture scoring: nothing was sent to decode or compare
    for source, receiver in directions:
        destination = output / "picture" / f"{source}-to-{receiver}"
        try:
            result = score_phase_delivery(manifest, receiver, destination, manifest_base, source)
            quality = result.get("quality", {})
            if result.get("content_regions"):
                families["picture"].setdefault("content_regions", []).append(
                    {"device": receiver, "source": source, **result["content_regions"]})
            measurements["picture"].append(decides({"device": receiver, "source": source, "name": "reference_validity",
                "status": "PASS" if result.get("measurement_status") == "MEASURED" else "UNMEASURED",
                "reason": "; ".join(result.get("reasons", [])) or "Requires every verified finalized active raw-input phase",
                "evidence": str(located(destination / "delivery-result.json"))}))
            for name, limit in (("vmaf", "min_vmaf"), ("ssim", "min_ssim"), ("psnr_db", "min_psnr_db")):
                measured = gate(quality.get(name), DEFAULT_THRESHOLDS[limit], "min")
                if name == "psnr_db" and quality.get("psnr_perfect") is True:
                    measured = {"status": "PASS", "value": None, "perfect": True,
                                "limit": DEFAULT_THRESHOLDS[limit], "reason": "Zero pixel error; PSNR is infinite"}
                measurements["picture"].append(decides({"device": receiver, "source": source, "name": name,
                    **measured,
                    "evidence": str(located(destination / "delivery-result.json"))}))
        except Exception as error:
            errors.append({"stage": "picture", "source": source, "receiver": receiver, "reason": str(error)})
            measurements["picture"].append(decides({"device": receiver, "source": source,
                "name": "scoring_validity", "status": "UNMEASURED", "reason": str(error)}))
    adapters = benchmark_measurements.analyze(measurement_contract, manifest_path, manifest, output / "measurements")
    for row in adapters["measurements"]:
        if row["family"] not in measurements:
            raise ValueError("Measurement adapter reports an unknown family")
        measurements[row["family"]].append(row if row["family"] in families else
                                           {**row, "family": "transport", "family_requested": row["family"],
                                            "reason": f"{row.get('reason', '')} (family inactive in {version})".strip()})
    errors.extend(adapters["errors"])
    for name, values in measurements.items():
        if values and name in families:
            families[name].update(status=status(values), measurements=values)
            families[name].pop("reason", None)
        elif values:
            families["transport"]["measurements"].extend(values)
            families["transport"]["status"] = status(families["transport"]["measurements"])
            families["transport"].pop("reason", None)
    result = {"schema": "benchmark-suite-case-v1", "case_id": manifest["scenario"]["id"],
              "run_id": manifest.get("run_id"), "origin": "RECORDED_EXECUTION_ANALYSIS",
              "manifest": str(manifest_path), "manifest_sha256": digest(manifest_path),
              "comparison_key": comparison_key(manifest), "scenario": manifest["scenario"],
              "devices": manifest.get("devices", {}), "source_kind": manifest.get("source_kind"),
              "source_fixture": manifest.get("source_fixture"), "source_fixtures": manifest.get("source_fixtures", {}),
              "replay_fixture": manifest.get("replay_fixture"),
              "pipeline_status": score["overall_status"], "families": families,
              # A call case that stopped before its invite says so in the case result (never a silent skip).
              **{key: score[key] for key in ("call_dialed", "not_dialed", "release_join") if key in score},
              "thresholds_version": version,
              "measurement_adapters": adapters,
              "required_families": list(required), "measurement_errors": errors,
              "actual_media_overlap": next((item["actual_media_overlap"] for item in adapters["adapters"]
                  if (item.get("actual_media_overlap") or {}).get("status") == "MEASURED"),
                  {"status": "UNMEASURED", "reason": "Job overlap is not actual media overlap"}),
              "full_experience_status": status([families[name] for name in names]),
              "status": status([{"status": score["overall_status"]}, *(families[name] for name in required)])}
    write(output / "analysis.json", result)
    return result


def execute(job, devices, adb, expected_hash, output, leases):
    with DebugImages(output):
        return _execute(job, devices, adb, expected_hash, output, leases)


def _execute(job, devices, adb, expected_hash, output, leases):
    started = time.time_ns()
    cleanup_unconfirmed = False
    try:
        check_cancelled(job)
        if job["mode"] == "private_call" and relay_chain_limit(job["case"]):
            # A chain the app cannot build stays visible as BLOCKED; no phone is leased or touched.
            raise PrerequisiteError(relay_chain_limit(job["case"]))
        with DeviceLease(leases, devices, job["phones"], job["execution"] == "isolated", job["owner"], job.get("capture_resources", ())) as lease:
            evidence = preflight(adb, devices, job, output, expected_hash)
            case = {**job["case"], "expected_apk_sha256": expected_hash}
            runner = CallRunner if job["mode"] == "private_call" else Runner
            instance = runner(adb, devices, case, output / "physical")
            instance.cancel_event = job.get("_cancel_event")
            recorder = ObservationRecorder(job["observation_capture"], output / "observation") if job.get("observation_capture") else None
            try:
                if recorder:
                    recorder.start()
                instance.run()
            finally:
                try:
                    if recorder and recorder.output.exists():
                        try:
                            recorder.stop()
                        except BaseException as error:
                            try:
                                running = recorder.process is not None and recorder.process.poll() is None
                            except Exception:
                                running = True
                            if isinstance(error, RecorderStillRunning) or running:
                                cleanup_unconfirmed = True
                                lease.quarantine_capture(str(error), recorder)
                                raise RecorderStillRunning("Recorder termination unconfirmed: " + str(error)) from error
                            raise
                        manifest_path = output / "physical" / "manifest.json"
                        if manifest_path.is_file():
                            manifest = read(manifest_path)
                            recorder.attach(manifest)
                            write(manifest_path, manifest)
                finally:
                    try:
                        verify_phone_cleanup(adb, devices, job["phones"], output, lease)
                    except BaseException:
                        cleanup_unconfirmed = True
                        raise
                    collect_hci_snoop(adb, devices, job, output, started, evidence)
            if instance.cancel_event is not None and instance.cancel_event.is_set():
                raise InterruptedError("Suite stopped; physical runner cleanup completed")
            manifest_path = output / "physical" / "manifest.json"
            record_battery_saver(manifest_path, evidence)
            physical = read(manifest_path) if manifest_path.is_file() else {}
            blocked = physical.get("runner_blocked")
            if blocked and not physical.get("bluetooth_stack_wedges"):
                # The phone's own Bluetooth stack kept the endpoint from starting (fail-fast, not the 30 s deadline):
                # BLOCKED. A stack that wedged during this run's own call stays that call's FAIL (local_bt_stack_wedged).
                raise PrerequisiteError(f"{blocked['device']}: {blocked['reason']}: {blocked['detail']}")
        return {"manifest": output / "physical" / "manifest.json", "job": job, "output": output,
                "host_start_ns": started, "host_end_ns": time.time_ns()}
    except Exception as error:
        result = {"case_id": job["case"]["id"], "origin": "LIVE_ATTEMPT", "scenario": job["case"],
                  "status": "INTERRUPTED" if isinstance(error, InterruptedError) else
                            "BLOCKED" if isinstance(error, PrerequisiteError) else "FAIL",
                  "reason": str(error), "host_start_ns": started, "host_end_ns": time.time_ns(),
                  **({"blocked_reason": error.reason_code} if hasattr(error, "reason_code") else {}),
                  **({"cleanup_status": "UNCONFIRMED"} if cleanup_unconfirmed else {}),
                  "families": {name: dict(UNKNOWN) for name in acceptance.families_for(job.get("acceptance_profile"))}}
        if (output / "physical/manifest.json").is_file():
            result["manifest"] = str(located(output / "physical/manifest.json"))
        if (output / "hci-snoop.json").is_file():
            result["hci_snoop"] = "hci-snoop.json"
        write(output / "attempt.json", result)
        return {"result": result}


def record_battery_saver(manifest_path, evidence):
    """`battery_saver` (true, false, or null when the preflight's power dump had no such line) on each device of the
    manifest, from the preflight text, for every case."""
    if not manifest_path.is_file():
        return
    manifest = read(manifest_path)
    for phone, record in (manifest.get("devices") or {}).items():
        if isinstance(record, dict) and isinstance(evidence.get(phone), dict):
            record["battery_saver"] = evidence[phone].get("battery_saver")
    write(manifest_path, manifest)


def collect_hci_snoop(adb, devices, job, output, started_ns, evidence):
    """After confirmed cleanup and while the lease is held, pull each participant's HCI snoop for this
    case window into physical/<phone>/hci/. Diagnostic only: retrieval failure is UNMEASURED, never a
    case failure, and a suite stop skips it (physical activity stops)."""
    policy = job.get("hci_snoop", "off")
    if policy == "off":
        return None
    if policy == "on-failure":
        # Bugreport-only phones (Samsung, ColorOS) idle every phone for minutes per case, so only a
        # failed or errored case pays for the packet-level evidence it needs.
        score_path = output / "physical" / "score.json"
        manifest_path = output / "physical" / "manifest.json"
        failed = True
        try:
            status = read(score_path).get("overall_status") if score_path.is_file() else None
            errored = read(manifest_path).get("runner_error") if manifest_path.is_file() else None
            failed = bool(errored) or status not in ("PASS",)
        except Exception:
            failed = True
        if not failed:
            write(output / "hci-snoop.json", {"schema_version": 1, "policy": policy, "status": "SKIPPED",
                                              "reason": "Case passed; on-failure policy"})
            return None
        policy = "auto"
    cancel = job.get("_cancel_event")
    try:
        if cancel is not None and cancel.is_set():
            receipt = {"schema_version": 1, "policy": policy, "phones": {phone: {
                "phone": phone, "status": "UNMEASURED", "reason": "Suite stopped; snoop retrieval skipped"}
                for phone in job["phones"]}}
            write(output / "hci-snoop.json", receipt)
        else:
            states = {phone: (evidence or {}).get(phone, {}).get("bluetooth_logging") for phone in job["phones"]}
            receipt = hci_snoop.collect_case(adb, devices, job["phones"], output, started_ns, time.time_ns(),
                                             policy, states, cancel=cancel)
        manifest_path = output / "physical" / "manifest.json"
        if manifest_path.is_file():
            manifest = read(manifest_path)
            manifest["hci_snoop"] = {"path": "../hci-snoop.json", "sha256": digest(output / "hci-snoop.json"),
                "phones": {phone: {key: row[key] for key in ("status", "method", "sha256", "bytes", "reason", "seconds")
                                   if key in row} | ({"path": row["path"].removeprefix("physical/")} if "path" in row else {})
                           for phone, row in receipt["phones"].items()}}
            write(manifest_path, manifest)
        return receipt
    except Exception as error:  # Never fail a case for diagnostic retrieval.
        try:
            write(output / "hci-snoop.json", {"schema_version": 1, "policy": policy, "status": "UNMEASURED",
                                              "reason": f"{type(error).__name__}: {error}"[:300]})
        except Exception:
            pass
        return None


def frozen_inputs(result):
    scenario = result.get("scenario", {})
    fixtures = result.get("source_fixtures", {})
    if "caller" in scenario:
        expected = {scenario["caller"], scenario["callee"]}
        fixtures = {phone: fixtures.get(phone) for phone in expected}
    elif result.get("source_fixture"):
        fixtures = {scenario.get("source", "source"): result["source_fixture"]}
    elif result.get("source_kind") == "preencoded_video_only" and result.get("replay_fixture"):
        fixtures = {scenario.get("source", "source"): result["replay_fixture"]}
    elif "bidirectional" in (result.get("source_kind") or ""):
        if len(fixtures) != 2:
            return None
    else:
        return None
    if not fixtures or any(not isinstance(item, dict) or not re.fullmatch(
            r"[0-9a-fA-F]{64}", item.get("sha256", "")) for item in fixtures.values()):
        return None
    return {phone: item["sha256"].lower() for phone, item in fixtures.items()}


def artifact_path(output, name):
    """Allow exact files inside this output, with no traversal, links or junctions."""
    if not isinstance(name, str) or not name or Path(name).is_absolute() or any(
            part in {"..", "."} for part in re.split(r"[/\\]", name)) or ":" in name:
        raise ValueError("Artifact review needs an exact relative path inside its output")
    path = output / name
    DebugImages._guard(path)
    located(path).relative_to(output)
    return path


def artifact_inventory(output):
    files = {}
    for directory, folders, names in os.walk(output, followlinks=False):
        for name in folders:
            DebugImages._guard(Path(directory) / name)
        folders[:] = [name for name in folders if name != ".debug-images"]
        for name in names:
            path = Path(directory) / name
            DebugImages._guard(path)
            files[path] = path.relative_to(output).as_posix()
    return files


def validate_artifact_inventory(output, review):
    owned = set(review.get("protected", ())) | set(review["files"]) | {"suite-result.json", "report.html"}
    unregistered = set(artifact_inventory(output).values()) - owned
    if unregistered:
        raise ValueError(f"Unregistered output needs an exact purpose review: {sorted(unregistered)}")


def finalize_artifacts(output):
    """Freeze purpose review in the existing result after all writers have stopped."""
    output = Path(os.path.abspath(output))
    DebugImages._guard(output)
    payload = read(output / "suite-result.json")
    if any(row.get("cleanup_status") == "UNCONFIRMED" for row in payload["results"]):
        raise ValueError("Recorder cleanup is unconfirmed; resolve its quarantined lease before freezing/reviewing output")
    if payload.get("artifact_review"):
        raise ValueError("Artifact ownership is already frozen; use review for existing outputs")
    core = {output / name for name in ("suite-result.json", "report.html", "coverage.json",
                                      "selection.json", "execution.json", "plan.json")}
    for item in payload["results"]:
        if item.get("manifest") and located(item["manifest"]).is_relative_to(output):
            core.add(located(item["manifest"]))
        if item.get("evidence_directory"):
            directory = located(item["evidence_directory"])
            if directory.is_relative_to(output):
                core.update(directory / name for name in ("preflight.json", "attempt.json", "cleanup.json", "physical/score.json"))
        if item.get("analysis_directory"):
            directory = located(item["analysis_directory"])
            if directory.is_relative_to(output):
                core.update(directory / name for name in ("analysis.json", "computed-score.json", "motion-timing.json"))
    files = artifact_inventory(output)
    core.update(path for path in files if path.name == "debug-image-lifecycle.json")
    required = artifact_dependencies(output, files, core)
    rows = {name: {"sha256": digest(path), "bytes": path.stat().st_size,
                  "state": "RETAINED" if path in required else "PENDING",
                  "purpose": "Required by retained measurement/identity evidence" if path in required else "Review remaining use",
                  **({"required_by": sorted(required[path])} if path in required else {})}
            for path, name in sorted(files.items()) if path not in core}
    payload["artifact_review"] = {"files": rows, "protected": sorted(files[path] for path in core & files.keys()),
                                  "protected_hashes": {files[path]: digest(path) for path in core & files.keys()
                                      if path not in {output / "suite-result.json", output / "report.html"}
                                      and path.name != "debug-image-lifecycle.json"}}
    artifact_review_state(output, payload["artifact_review"])
    write(output / "suite-result.json", payload)
    render_report(output, payload)
    return payload["artifact_review"]


def artifact_dependencies(output, files, roots):
    required, waiting, visited = {}, list(roots & files.keys()), set()
    reference_cache = {}
    def references(value, base):
        if isinstance(value, dict):
            for key, item in value.items():
                if key != "artifact_review":
                    yield from references(item, base)
        elif isinstance(value, list):
            for item in value:
                yield from references(item, base)
        elif isinstance(value, str) and value and "\n" not in value:
            key = (base, value)
            if key not in reference_cache:
                reference_cache[key] = None
                try:
                    requested = Path(value)
                    for candidate in (requested,) if requested.is_absolute() else (base / requested, output / requested, ROOT / requested):
                        target = located(candidate)
                        if target in files:
                            reference_cache[key] = target
                            break
                except (OSError, ValueError):
                    pass  # Ordinary strings in measurement records are not file references.
            if reference_cache[key] is not None:
                yield reference_cache[key]
    while waiting:
        path = waiting.pop()
        if path in visited or path.suffix.lower() != ".json":
            continue
        visited.add(path)
        try:
            value = read(path)
        except (ValueError, UnicodeError):
            continue
        for target in references(value, path.parent):
            if target != path and target not in roots:
                required.setdefault(target, set()).add(files[path])
                waiting.append(target)
    return required


def artifact_review_state(output, review):
    pending = []
    for name in set(review["protected"]) | set(review["files"]):
        if Path(name).name == "debug-image-lifecycle.json":
            images = read(artifact_path(output, name))["images"]
            pending.extend(f"{name}: {image}" for image, row in images.items() if row["state"] in {"PENDING", "CONSUMED"})
    review["pending_images"] = pending
    review["status"] = "PENDING" if pending or any(row["state"] in {"PENDING", "CONSUMED"}
                                                   for row in review["files"].values()) else "COMPLETE"


def review_artifacts(output, consume=(), retain=(), purpose=None):
    """Consume reviewed scratch or record a named remaining use; never sweep evidence."""
    output = Path(os.path.abspath(output))
    DebugImages._guard(output / "suite-result.json")
    payload = read(output / "suite-result.json")
    if output.is_relative_to(ROOT):
        from benchmark_results import LEDGER
        if LEDGER.is_file() and any(resolve(row["path"]) == output / "suite-result.json"
                                   for row in read(LEDGER).get("unified_reports", [])):
            raise ValueError("Checkpointed report bytes are frozen; finish purpose review before the ledger checkpoint")
    review = payload.get("artifact_review")
    if payload.get("schema") != "benchmark-suite-v1" or not review or any(
            item["status"] in {"RUNNING", "ANALYZING", DEFERRED} for item in payload["results"]):
        raise ValueError("Artifact review requires a finalized suite's existing ownership inventory")
    if (consume or retain) and (not isinstance(purpose, str) or not purpose.strip()):
        raise ValueError("State the completed review or specific remaining purpose")
    consume = tuple(artifact_path(output, name.replace("\\", "/")).relative_to(output).as_posix() for name in consume)
    retain = tuple(artifact_path(output, name.replace("\\", "/")).relative_to(output).as_posix() for name in retain)
    if len(set((*consume, *retain))) != len((*consume, *retain)):
        raise ValueError("Review each exact artifact only once per batch")
    for name in retain:
        if name not in review["protected"] and name not in review["files"]:
            path = artifact_path(output, name)
            if not path.is_file():
                raise ValueError(f"New retained measurement is missing: {name}")
            review["files"][name] = {"sha256": digest(path), "bytes": path.stat().st_size,
                                     "state": "PENDING", "purpose": "Explicitly registered later measurement"}
    validate_artifact_inventory(output, review)
    files = {artifact_path(output, name): name for name in (*review["protected"], *review["files"])}
    frozen_parents = {**review.get("protected_hashes", {}), **{
        name: row["sha256"] for name, row in review["files"].items()
        if row["state"] == "RETAINED" and Path(name).suffix.lower() == ".json"}}
    for name, sha in frozen_parents.items():
        path = output / name
        if not path.is_file() or digest(path) != sha:
            raise ValueError(f"Retained metadata changed; cannot release its original dependencies: {name}")
    roots = {output / name for name in review["protected"]}
    roots.update(output / name for name, row in review["files"].items() if name not in consume and (
        name in retain or row["state"] == "RETAINED" and (row.get("retention_reason") or not row.get("required_by"))))
    required = artifact_dependencies(output, files, roots)
    for path, name in files.items():
        row = review["files"].get(name)
        if row and path in required:
            if not path.is_file() or digest(path) != row["sha256"] or path.stat().st_size != row["bytes"]:
                raise ValueError(f"Retained record depends on missing or changed evidence: {name}")
            row.update(state="RETAINED", required_by=sorted(required[path]))
        elif row:
            if row.get("required_by") and row["state"] == "RETAINED" and not row.get("retention_reason"):
                row.update(state="PENDING", purpose="Previous consumer released; review remaining use")
            row.pop("required_by", None)
    checked = []
    for name in (*consume, *retain):
        path = artifact_path(output, name)
        row = review["files"].get(path.relative_to(output).as_posix())
        if not row or row["state"] not in {"PENDING", "RETAINED", "CONSUMED"} or name in consume and row.get("required_by"):
            raise ValueError(f"Cannot consume unowned, core or referenced evidence: {name}")
        if not (name in consume and row["state"] == "CONSUMED" and not path.exists()) and (
                not path.is_file() or digest(path) != row["sha256"] or path.stat().st_size != row["bytes"]):
            raise ValueError(f"Artifact changed after finalization; review its bytes again: {name}")
        checked.append((path, row, name in consume))
    for path, row, delete in checked:
        row.update(state="CONSUMED" if delete else "RETAINED", purpose=purpose.strip(),
                   reviewed_at=dt.datetime.now().astimezone().isoformat())
        if not delete:
            row["retention_reason"] = purpose.strip()
    artifact_review_state(output, review)
    write(output / "suite-result.json", payload)  # Interrupted deletion has an exact retryable record.
    for path, row, delete in checked:
        if delete:
            DebugImages._guard(path)
            if path.exists():
                if digest(path) != row["sha256"]:
                    raise ValueError(f"Consumed artifact bytes changed before deletion: {path}")
                path.unlink()
            row["state"] = "DELETED"
            artifact_review_state(output, review)
            write(output / "suite-result.json", payload)
    write(output / "suite-result.json", payload)
    render_report(output, payload)
    return review


def mark_scoring_failure(item, error, origin):
    item.update(status="FAIL", origin=origin, reason=str(error),
                measurement_errors=[{"stage": "runner_or_scoring", "reason": str(error)}])


def recorded_baseline(execution):
    """Baseline named by a deferred run; its bytes must still be the ones the run recorded."""
    recorded = (execution.get("defer_analysis") or {}).get("baseline")
    if not recorded:
        return None
    path = resolve(recorded["path"])
    if not path.is_file() or digest(path) != recorded["sha256"]:
        raise ValueError("Baseline named by the deferred run is missing or changed; analysis would not match a normal run")
    return read(path)


def finish_analysis(output, workers=1):
    """Run the analysis a --defer-analysis suite skipped, then freeze its purpose review.

    Uses the same analyze() and the same result/report/finalize path as a normal run, against the
    manifests the deferred run retained (hash-checked), and rewrites only this run's own files.
    Returns the suite's results.
    """
    output = output_path(output, "runs", root=ROOT)
    DebugImages._guard(output)
    payload = read(output / "suite-result.json")
    if payload.get("artifact_review"):
        raise ValueError("Output is already finalized; nothing is pending analysis")
    results = payload["results"]
    pending = [index for index, item in enumerate(results)
               if item["status"] in {DEFERRED, "ANALYZING"} and "deferred_analysis" in item]
    if not pending:
        raise ValueError("No case is pending analysis in this output")
    baseline = recorded_baseline(read(output / "execution.json"))
    lock = output / ".finish-analysis.lock"
    try:
        os.close(os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY))
    except FileExistsError:
        raise ValueError(f"Another finish-analysis owns this output (or crashed); remove {lock.name} only after verifying no such process") from None
    scoring, offline = {}, futures.ThreadPoolExecutor(max_workers=workers)
    interrupted = False
    try:
        for index in pending:
            item = results[index]
            deferred = item["deferred_analysis"]
            manifest = Path(item["manifest"])
            try:
                if not manifest.is_file() or digest(manifest) != deferred["manifest_sha256"]:
                    raise ValueError("Retained manifest is missing or changed since the deferred run released its phones")
            except (OSError, ValueError) as error:
                item.pop("deferred_analysis")
                mark_scoring_failure(item, error, "LIVE_ATTEMPT")
                continue
            item["status"] = "ANALYZING"
            scoring[offline.submit(analyze, manifest, Path(item["analysis_directory"]),
                                   deferred["required_families"], None, deferred["acceptance_profile"])] = index
        report(output, results, baseline)
        for future in futures.as_completed(scoring):
            index = scoring[future]
            try:
                results[index].update(future.result())
            except Exception as error:
                mark_scoring_failure(results[index], error, "LIVE_ATTEMPT")
            results[index].pop("deferred_analysis", None)  # An interrupt skips this, so the case stays resumable
            report(output, results, baseline)
            print(f"Scored {results[index]['case_id']}: {results[index]['status']}", flush=True)
    except KeyboardInterrupt:
        interrupted = True
        for future in scoring:
            future.cancel()
    finally:
        offline.shutdown(wait=True, cancel_futures=True)
        for item in results:
            if item["status"] == "ANALYZING" and "deferred_analysis" in item:
                item["status"] = DEFERRED  # Not scored; rerun finish-analysis
        report(output, results, baseline)
        lock.unlink()
    if not interrupted:
        finalize_artifacts(output)
    return results


def finalize_stopped(output, reason, resolved_cleanup=()):
    """Finalize an output whose runner stopped before it could (killed, or its lane ended).

    Cases still RUNNING or ANALYZING become INTERRUPTED with the stated reason; their partial evidence stays and
    the retained manifest is attached when the runner got that far. Cases that never started stay NOT RUN and
    terminal results are untouched. Ownership is then frozen exactly as a normal run does (finalize_artifacts);
    nothing is deleted and no case is scored or passed.
    """
    output = output_path(output, "runs", root=ROOT)
    DebugImages._guard(output)
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("State why the runner stopped")
    payload = read(output / "suite-result.json")
    if payload.get("schema") != "benchmark-suite-v1":
        raise ValueError("Unsupported suite-result schema")
    if payload.get("artifact_review"):
        raise ValueError("Output is already finalized; use review for its purpose decisions")
    results = payload["results"]
    if any(item["status"] == DEFERRED for item in results):
        raise ValueError("Cases are pending analysis; run finish-analysis instead")
    stopped = []
    resolved = set(resolved_cleanup)
    unconfirmed = {item["case_id"] for item in results if item.get("cleanup_status") == "UNCONFIRMED"}
    if resolved - unconfirmed:
        raise ValueError(f"Not an unconfirmed-cleanup case in this output: {sorted(resolved - unconfirmed)}")
    for item in results:
        if item["case_id"] in resolved:
            # A person verified the phone's app process stopped (the REASON states how). The unconfirmed receipt
            # file stays as recorded; the case is INTERRUPTED rather than scored, and its earlier verdict is kept.
            item["cleanup_resolution"] = {"previous_status": item["status"], "previous_reason": item.get("reason"),
                "previous_cleanup_status": "UNCONFIRMED", "evidence": reason,
                "resolved_at": dt.datetime.now().astimezone().isoformat()}
            item.update(status="INTERRUPTED", reason=reason, cleanup_status="RESOLVED_BY_EVIDENCE")
            stopped.append(item["case_id"])
        elif item["status"] in {"RUNNING", "ANALYZING"}:
            item.update(status="INTERRUPTED", reason=reason)
            manifest = Path(item.get("evidence_directory", output / "absent")) / "physical/manifest.json"
            if manifest.is_file():
                item.update(manifest=str(located(manifest)), manifest_sha256=digest(manifest))
            stopped.append(item["case_id"])
    if stopped:
        payload["state_counts"] = {state: sum(item["status"] == state for item in results)
                                   for state in sorted({item["status"] for item in results})}
        payload["stopped_before_finalization"] = {"cases": stopped, "reason": reason,
                                                  "recorded_at": dt.datetime.now().astimezone().isoformat()}
        write(output / "suite-result.json", payload)
        render_report(output, payload)
    return finalize_artifacts(output)


def report(output, results, baseline=None):
    comparisons = {}
    for item in (baseline or {}).get("results", []):
        if item.get("comparison_key"):
            comparisons.setdefault(item["comparison_key"], []).append(item)
    for result in results:
        matches = comparisons.get(result.get("comparison_key"), []) if result.get("comparison_key") else []
        inputs = frozen_inputs(result)
        frozen = inputs is not None and len(matches) == 1 and inputs == frozen_inputs(matches[0])
        label = "MATCHED_INPUT_AND_REQUEST" if frozen else "MATCHED_REQUEST_ONLY"
        result["baseline"] = {"status": label if len(matches) == 1 else "UNMATCHED",
            "reason": "Same input/request is not proof of equivalent RF conditions or sole-variable causality"}
        if len(matches) == 1:
            result["baseline"]["previous_status"] = matches[0]["status"]
            result["baseline"]["family_changes"] = {name: {"before": matches[0]["families"].get(name, {}).get("status"),
                "after": result["families"][name]["status"]} for name in result["families"]}
    versions = {item["thresholds_version"] for item in results if item.get("thresholds_version")}
    thresholds = (limits_for({"version": acceptance.V4}) if versions == {acceptance.V4} else
                  {"version": "mixed", "versions": sorted(versions)} if len(versions) > 1 else DEFAULT_THRESHOLDS)
    joins = [item for item in results if isinstance(item.get("release_join"), dict)]
    payload = {"schema": "benchmark-suite-v1", "generated_at": dt.datetime.now().astimezone().isoformat(),
               "thresholds": thresholds, "results": results,
               # The release gate's join metric over this suite's user-launch calls (tools/release_join.py).
               **({"release_join_gate": release_join.suite_gate(joins)} if joins else {}),
               "state_counts": {state: sum(item["status"] == state for item in results)
                                for state in sorted({item["status"] for item in results})},
               "scope": "Automated execution/scoring; missing physical/camera/acoustic/endurance adapters stay unmeasured"}
    write(output / "suite-result.json", payload)
    write(output / "coverage.json", {"schema": "suite-execution-coverage-v1", "cases": [
        {key: item.get(key) for key in ("case_id", "run_id", "status", "origin", "manifest", "manifest_sha256")}
        for item in results], "standard_ledger_updated": False,
        "reason": "Integration record only; shared canonical ledger is owned by the main thread"})
    render_report(output, payload)
    return payload


def render_report(output, payload):
    rows = []
    shown = [name for name in acceptance.KNOWN_FAMILIES if any(name in item["families"] for item in payload["results"])]
    for item in payload["results"]:
        values = [item["case_id"], item["status"], *(item["families"].get(name, {}).get("status", "n/a") for name in shown)]
        cells = "".join(f'<td class="{html.escape(str(value))}">{html.escape(str(value))}</td>' for value in values)
        details = html.escape(json.dumps(item, indent=2, ensure_ascii=False))
        rows.append(f"<tr>{cells}</tr><tr><td colspan='{len(values)}'><details><summary>Evidence and scope</summary><pre>{details}</pre></details></td></tr>")
    headings = "".join(f"<th>{html.escape(name)}</th>" for name in ("Case", "Verdict", *shown))
    join = payload.get("release_join_gate")
    join_note = (f"<p class='{html.escape(join['status'])}'>Release join gate: {html.escape(join['status'])} - "
                 f"{html.escape(join['reason'])}</p>" if join else "")
    page = f"""<!doctype html><html lang="en"><meta charset="utf-8"><title>Benchmark suite report</title>
<style>body{{font:15px system-ui;margin:24px}}table{{border-collapse:collapse}}td,th{{padding:8px;border:1px solid #bbb}}pre{{white-space:pre-wrap}}.FAIL{{background:#ffd6d6}}.PASS{{background:#d2efdc}}.UNMEASURED,.BLOCKED{{background:#fff0c7}}</style>
<h1>Benchmark suite report</h1><p>Each family has its own verdict. Callback timing is a proxy; physical screen delay, camera, acoustic and endurance coverage require their own evidence. Job overlap does not prove media overlap.</p>
<p>{html.escape(json.dumps(payload['state_counts']))}</p>{join_note}<table><thead><tr>{headings}</tr></thead><tbody>{''.join(rows)}</tbody></table></html>"""
    if payload.get("artifact_review"):
        review = html.escape(json.dumps(payload["artifact_review"], indent=2))
        page = page.replace("</html>", f"<details><summary>Output purpose review: {payload['artifact_review']['status']}</summary><pre>{review}</pre></details></html>")
    DebugImages._guard(output / "report.html")
    (output / "report.html").write_text(page, encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "run", "analyze", "review", "finish-analysis", "finalize-stopped"))
    parser.add_argument("--suite", type=Path)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--manifest", type=Path, action="append", default=[])
    parser.add_argument("--devices", type=Path, default=ROOT / "tools/benchmark_devices.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--measurements", type=Path, help="Hash-bound benchmark-measurements-v1 for saved-run analysis")
    parser.add_argument("--profile", choices=(acceptance.V3, acceptance.V4),
                        help="Acceptance profile for saved-run analysis (default device-run-v3); live runs take it from the bundle group")
    parser.add_argument("--state", type=Path, help="Exact-case state.json; prioritize failures/retests and skip verified completion")
    parser.add_argument("--adb", default=default_adb())
    parser.add_argument("--apk-sha256")
    parser.add_argument("--phones", help="Comma-separated exclusively assigned aliases; never inferred from availability")
    parser.add_argument("--owner", help="Named owner of the explicitly assigned device group")
    parser.add_argument("--parallel", type=int, default=1)
    parser.add_argument("--hci-snoop", choices=hci_snoop.POLICIES, default="auto",
                        help="run: after each case's cleanup pull participants' HCI snoop for the case window "
                             "(auto: when not disabled on the phone; required: preflight blocks unless enabled; "
                             "retrieval failure is always UNMEASURED, never a case failure)")
    parser.add_argument("--hci-snoop-scratch", type=Path,
                        help="run: directory for transient bugreport zips (deleted right after the snoop is "
                             "extracted); default HCI_SNOOP_SCRATCH or the case's own hci directory")
    parser.add_argument("--consume", action="append", default=[], help="Exact reviewed scratch path within a finalized output")
    parser.add_argument("--retain", action="append", default=[], help="Exact pending output with a specific remaining use")
    parser.add_argument("--purpose", help="Completed review or named remaining use; recorded in suite-result.json")
    parser.add_argument("--reason", help="finalize-stopped: why the runner stopped before it could finalize its output")
    parser.add_argument("--resolve-cleanup", action="append", default=[], metavar="CASE_ID",
                        help="finalize-stopped: an UNCONFIRMED-cleanup case whose phone process a person verified stopped "
                             "(--reason states the evidence); it becomes INTERRUPTED and keeps its earlier verdict recorded")
    parser.add_argument("--defer-analysis", action="store_true",
                        help="run: finish phone work/cleanup and release leases, then exit with cases marked "
                             f"{DEFERRED}; score later with finish-analysis on the same output")
    args = parser.parse_args(argv)
    if args.defer_analysis and args.action != "run":
        parser.error("--defer-analysis applies only to run")
    if args.action == "finish-analysis":
        if not args.output or any((args.suite, args.case, args.manifest, args.baseline, args.measurements, args.profile,
                                  args.state, args.phones, args.owner, args.apk_sha256, args.consume, args.retain,
                                  args.purpose)):
            parser.error("finish-analysis uses only an existing --output deferred by run (optional --parallel scorers)")
        if args.parallel < 1:
            parser.error("Parallel count must be positive")
        try:
            results = finish_analysis(args.output, args.parallel)
        except (ValueError, KeyError, OSError) as error:
            parser.exit(2, f"Finish analysis blocked: {error}\n")
        print(f"Report: {output_path(args.output, 'runs', root=ROOT) / 'report.html'}")
        return 0 if results and all(item["status"] == "PASS" for item in results) else 2
    if args.action == "finalize-stopped":
        if not args.output or any((args.suite, args.case, args.manifest, args.baseline, args.measurements, args.profile,
                                  args.state, args.phones, args.owner, args.apk_sha256, args.consume, args.retain,
                                  args.purpose)):
            parser.error("finalize-stopped uses only an existing --output and the --reason the runner stopped")
        try:
            review = finalize_stopped(args.output, args.reason, args.resolve_cleanup)
        except (ValueError, KeyError, OSError) as error:
            parser.exit(2, f"Finalize stopped output blocked: {error}\n")
        print(json.dumps({"artifact_review": review["status"], "counts": {
            state: sum(row["state"] == state for row in review["files"].values()) for state in ("PENDING", "RETAINED", "CONSUMED", "DELETED")}}))
        return 0 if review["status"] == "COMPLETE" else 2
    if args.action == "review":
        if not args.output or any((args.suite, args.case, args.manifest, args.baseline, args.measurements,
                                  args.state, args.phones, args.owner, args.apk_sha256)):
            parser.error("Review uses only an existing finalized output and exact consume/retain paths")
        try:
            review = review_artifacts(args.output, args.consume, args.retain, args.purpose)
        except (ValueError, KeyError, OSError) as error:
            parser.exit(2, f"Artifact review blocked: {error}\n")
        print(json.dumps({"artifact_review": review["status"], "counts": {
            state: sum(row["state"] == state for row in review["files"].values()) for state in ("PENDING", "RETAINED", "CONSUMED", "DELETED")}}))
        return 0 if review["status"] == "COMPLETE" else 2
    if args.reason or args.resolve_cleanup:
        parser.error("--reason and --resolve-cleanup apply only to finalize-stopped")
    if args.consume or args.retain or args.purpose:
        parser.error("Artifact decisions belong to review after run/analysis writers have finished")
    if args.profile and args.action != "analyze":
        parser.error("Acceptance profile for plan/run is the bundle group's acceptance_profile; --profile applies to analyze")
    if args.measurements and args.action != "analyze":
        parser.error("Measurement observations attach only to saved-run analysis; they do not authorize capture")
    if args.parallel < 1:
        parser.error("Parallel count must be positive")
    devices = read(resolve(args.devices))["devices"]
    if args.action == "analyze":
        if not args.manifest or args.suite or args.case or args.state or args.phones:
            parser.error("Analyze requires existing manifests and cannot also select a suite")
        jobs = []
    else:
        if not args.suite or args.manifest:
            parser.error("Plan/run require a suite, without recorded manifest inputs")
        jobs = load_jobs(args.suite, devices, args.case)
    inventory = list(jobs)
    selection = {"inventory_cases": len(inventory), "unavailable": [], "completed_skipped": [],
                 "blocked": [], "claimed_phones": args.phones,
                 "reason": "Ownership/selection is not proof of availability; live preflight remains required"}
    if args.action != "analyze":
        assigned = set(filter(None, (name.strip() for name in (args.phones or "").split(","))))
        if assigned - set(devices):
            parser.error(f"Unknown phone aliases: {sorted(assigned - set(devices))}")
        if args.phones is not None:
            selection["unavailable"] = [{"case_id": job["case"]["id"],
                "missing_phones": sorted(set(job["phones"]) - assigned)} for job in jobs if not set(job["phones"]) <= assigned]
            jobs = [job for job in jobs if set(job["phones"]) <= assigned]
        if args.state:
            by_id, rows_read = {}, 0  # streamed: a full-inventory state is hundreds of thousands of rows
            for row in iter_state_rows(resolve(args.state)):
                by_id[row["case_id"]] = row
                rows_read += 1
            if len(by_id) != rows_read:
                parser.error("State contains duplicate case IDs")
            current = []
            for job in jobs:
                row = by_id.get(job["case"]["id"])
                if row is None or row.get("request_key") != request_key(job["case"]):
                    row = {"case_id": job["case"]["id"], "phones": job["phones"], "state": "NOT RUN"}
                else:
                    row = dict(row)
                if row["state"] == "PASS" and not verify_result(row.get("last_result") or {}, job):
                    row["state"] = "UNMEASURED"
                if args.apk_sha256 and row["state"] == "PASS" and not verify_result(
                        row.get("last_result") or {}, job, expected_apk=args.apk_sha256):
                    row["state"] = "NEEDS RETEST"
                if args.case and row["state"] == "BLOCKED":
                    row["state"] = "NOT RUN"  # Explicit retry still has to pass live prerequisites.
                current.append(row)
                job["state"] = row["state"]
            remaining = select_remaining({"cases": current}, assigned if args.phones is not None else set(devices))
            order = {row["case_id"]: index for index, row in enumerate(remaining)}
            selection["completed_skipped"] = [row["case_id"] for row in current if row["state"] == "PASS"]
            selection["blocked"] = [row["case_id"] for row in current if row["state"] == "BLOCKED"]
            jobs = sorted((job for job in jobs if job["case"]["id"] in order), key=lambda job: order[job["case"]["id"]])
        selection["selected_cases"] = len(jobs)
    if args.action == "plan":
        plan = {"state": "NOT RUN", "cases": len(jobs), "jobs": jobs, "selection": selection,
                "duration_budget": {"selected_media_seconds": sum(job["case"]["duration_s"] for job in jobs),
                                    "inventory_media_seconds": sum(job["case"]["duration_s"] for job in inventory),
                                    "basis": "Requested media durations only; excludes setup, cleanup, scoring, resource conflicts and reruns"},
                "batches": [[job["case"]["id"] for job in batch] for batch in batches(jobs, args.parallel)]}
        if args.output:
            output = output_path(args.output, "catalog", root=ROOT)
            output.mkdir(parents=True, exist_ok=True)
            write(output / "plan.json", plan)
        print(json.dumps({"state": plan["state"], "cases": len(jobs), "inventory_cases": len(inventory),
                          "batches": len(plan["batches"]), **plan["duration_budget"]}))
        return 0
    if not args.output:
        parser.error("Execution/analysis requires a new output directory")
    if args.action == "run":
        if args.hci_snoop_scratch:
            hci_snoop.SCRATCH_ROOT = str(args.hci_snoop_scratch)
        owned = assigned
        if not args.owner or not re.fullmatch(r"[0-9a-fA-F]{64}", args.apk_sha256 or "") or any(
                not set(job["phones"]) <= owned for job in jobs):
            parser.error("Run requires a frozen APK SHA256, named owner and explicit ownership of every participant")
    output = output_path(args.output, "runs", root=ROOT)
    if output.is_relative_to(ROOT) and output.parent != ROOT / "benchmarks/runs":
        parser.error("A suite output must be a direct benchmarks/runs/EXECUTION directory")
    if any(resolve(path).is_relative_to(output) for path in args.manifest):
        parser.error("Output cannot contain original input evidence")
    output.mkdir(parents=True, exist_ok=False)
    write(output / "selection.json", selection)
    baseline = read(resolve(args.baseline)) if args.baseline else None
    cases = [job["case"] for job in jobs] if jobs else [read(resolve(path))["scenario"] for path in args.manifest]
    case_families = [acceptance.families_for(job.get("acceptance_profile")) for job in jobs] or \
        [acceptance.families_for(args.profile)] * len(cases)
    results = [{"case_id": case["id"], "scenario": case, "status": "NOT RUN", "origin": "PLANNED",
                "families": {name: dict(UNKNOWN) for name in names}} for case, names in zip(cases, case_families)]
    indices = {job["case"]["id"]: index for index, job in enumerate(jobs)}
    write(output / "execution.json", {"action": args.action, "owner": args.owner, "phones": args.phones,
        "device_registry": str(resolve(args.devices)), "registry_sha256": digest(resolve(args.devices)),
        "frozen_apk_sha256": args.apk_sha256, "parallel": args.parallel,
        **({"hci_snoop": args.hci_snoop} if args.action == "run" else {}),
        **({"defer_analysis": {"baseline": {"path": str(resolve(args.baseline)), "sha256": digest(resolve(args.baseline))}
                                if args.baseline else None}} if args.defer_analysis else {}),
        **({"acceptance_profile": args.profile} if args.profile else {}),
        "manifests": [str(resolve(path)) for path in args.manifest]})
    if jobs:
        write(output / "plan.json", {"state": "NOT RUN", "jobs": jobs})
    report(output, results, baseline)
    cancelled = threading.Event()
    offline = futures.ThreadPoolExecutor(max_workers=1)
    phones = futures.ThreadPoolExecutor(max_workers=args.parallel)
    scoring = {}

    def failed(index, error):
        mark_scoring_failure(results[index], error, "LIVE_ATTEMPT" if args.action == "run" else "RECORDED_EXECUTION_ANALYSIS")

    def collect_score(future):
        index = scoring[future]
        try:
            results[index].update(future.result())
        except Exception as error:
            failed(index, error)
        report(output, results, baseline)
        print(f"Scored {results[index]['case_id']}: {results[index]['status']}", flush=True)

    try:
        if args.action == "analyze":
            for index, path in enumerate(args.manifest):
                results[index].update(status="ANALYZING", manifest=str(resolve(path)),
                                      analysis_directory=str(output / f"analysis-{index + 1:04d}"))
                scoring[offline.submit(analyze, resolve(path), output / f"analysis-{index + 1:04d}",
                                       acceptance.families_for(args.profile),
                                       resolve(args.measurements) if args.measurements else None, args.profile)] = index
            report(output, results, baseline)
        else:
            waiting, running = list(jobs), {}
            phone_counts, role_counts = {}, {}
            while waiting or running:
                while (original := schedule_ready(waiting, [item["job"] for item in running.values()],
                                                   phone_counts, role_counts, args.parallel)) is not None:
                    waiting.remove(original)
                    index = indices[original["case"]["id"]]
                    job = {**original, "owner": args.owner, "_cancel_event": cancelled, "hci_snoop": args.hci_snoop}
                    directory = output / f"{index + 1:04d}-{hashlib.sha256(job['case']['id'].encode()).hexdigest()[:12]}"
                    directory.mkdir()
                    results[index].update(status="RUNNING", origin="LIVE_ATTEMPT", execution=job["execution"],
                                          evidence_directory=str(directory))
                    report(output, results, baseline)
                    running[phones.submit(execute, job, devices, args.adb, args.apk_sha256,
                                          directory, CATALOG / "device-leases")] = {"index": index, "job": original}
                    for phone in original["phones"]:
                        phone_counts[phone] = phone_counts.get(phone, 0) + 1
                    for role in job_roles(original):
                        role_counts[role] = role_counts.get(role, 0) + 1
                completed, _ = futures.wait(running, timeout=1, return_when=futures.FIRST_COMPLETED)
                for future in sorted(completed, key=lambda item: running[item]["index"]):
                    index = running.pop(future)["index"]
                    try:
                        finished = future.result()
                    except Exception as error:
                        failed(index, error)
                    else:
                        if "result" in finished:
                            results[index].update(finished["result"])
                        elif args.defer_analysis:
                            try:
                                manifest_sha256 = digest(finished["manifest"])
                            except OSError as error:
                                failed(index, error)
                            else:
                                results[index].update(status=DEFERRED, manifest=str(finished["manifest"]),
                                    analysis_directory=str(finished["output"] / "analysis"),
                                    host_start_ns=finished["host_start_ns"], host_end_ns=finished["host_end_ns"],
                                    deferred_analysis={"manifest_sha256": manifest_sha256,
                                        "required_families": finished["job"]["required_families"],
                                        "acceptance_profile": finished["job"].get("acceptance_profile")})
                                print(f"Analysis deferred for {results[index]['case_id']}; phones released", flush=True)
                        else:
                            results[index].update(status="ANALYZING", manifest=str(finished["manifest"]),
                                analysis_directory=str(finished["output"] / "analysis"),
                                host_start_ns=finished["host_start_ns"], host_end_ns=finished["host_end_ns"])
                            scoring[offline.submit(analyze, finished["manifest"], finished["output"] / "analysis",
                                                  finished["job"]["required_families"], None,
                                                  finished["job"].get("acceptance_profile"))] = index
                    report(output, results, baseline)
                for future in list(scoring):
                    if future.done():
                        collect_score(future)
                        del scoring[future]
        for future in futures.as_completed(scoring):
            collect_score(future)
    except KeyboardInterrupt:
        cancelled.set()
        for future in scoring:
            future.cancel()
        for item in results:
            if item["status"] in {"RUNNING", "ANALYZING"}:
                item.update(status="INTERRUPTED", reason="User stopped the suite; preserve evidence and rerun explicitly")
        report(output, results, baseline)
        print("Suite stopped; owned phone runners are collecting evidence and stopping their participants.", flush=True)
    finally:
        phones.shutdown(wait=True, cancel_futures=True)
        offline.shutdown(wait=True, cancel_futures=True)
        for item in results:
            manifest = Path(item.get("evidence_directory", output / "absent")) / "physical/manifest.json"
            if item["status"] == "INTERRUPTED" and manifest.is_file():
                item.update(manifest=str(located(manifest)), manifest_sha256=digest(manifest))
        report(output, results, baseline)
        if any(item["status"] == DEFERRED for item in results):
            if any(row.get("cleanup_status") == "UNCONFIRMED" for row in results):
                raise ValueError("Recorder cleanup is unconfirmed; resolve its quarantined lease before freezing/reviewing output")
            # Ownership is frozen by finish-analysis, after analysis creates its files.
        else:
            finalize_artifacts(output)
    print(f"Report: {output / 'report.html'}")
    accepted = {"PASS", DEFERRED} if args.defer_analysis else {"PASS"}
    return 0 if results and all(item["status"] in accepted for item in results) else 2


if __name__ == "__main__":
    raise SystemExit(main())
