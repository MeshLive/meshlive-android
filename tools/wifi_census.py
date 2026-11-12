"""Design WF M0: read-only Wi-Fi capability census of the test phones.

For each requested phone this runs only the census commands from DECISIONS
"Design WF: Wi-Fi Aware / Wi-Fi Direct transport" (getprop, pm has-feature,
``cmd wifi status``, ``dumpsys wifiaware``, ``dumpsys wifip2p`` and
``dumpsys wifi``). The raw output is parsed in memory and never written.

Privacy: every Wi-Fi network name, BSSID and device address becomes
``SHA-256(salt | value)[0:8]`` (hex) with one random 32-byte salt per run.
The salt is never written to a file. ``--probe`` passes it to the test as an
``am instrument`` extra, so it is briefly visible in the host adb command line
and in the phone's instrumentation arguments while that test starts.

Wi-Fi state: when Aware is supported and Wi-Fi is off, the census switches
Wi-Fi on with ``cmd wifi set-wifi-enabled enabled`` so the firmware capability
block has been initialised, records that it did, and restores the original
state in ``finally``.

``--probe`` runs the already installed ``DeviceWifiCapabilitiesTest``
instrumentation (it never installs anything). ``am instrument`` force-stops
MeshLive, so the probe is refused while ``ActiveVideoSessionService`` is
running unless ``--probe-ok-to-stop-app`` is given. The pulled
``wifi-capabilities.json`` is merged into ``census.json`` and deleted from the
phone.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from android_tools import default_adb  # noqa: E402
from benchmark_layout import output_path  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEVICES = ROOT / "tools/benchmark_devices.json"
APP_ID = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
TEST_CLASS = "com.example.bluetoothtest.DeviceWifiCapabilitiesTest"
TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PROBE_FILE = "wifi-capabilities.json"
PROBE_TIMEOUT = "10000"  # milliseconds; also passed to the probe as wifi_census_timeout_ms
SESSION_SERVICE = "ActiveVideoSessionService"
# A phone the scope marks as excluded is never touched by this census.
FORBIDDEN_ALIASES = {"s24"}

MAC = re.compile(r"(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b")
MAC_HYPHEN = re.compile(r"(?i)\b(?:[0-9a-f]{2}-){5}[0-9a-f]{2}\b")
MAC_PLAIN = re.compile(r"(?i)\b[0-9a-f]{12}\b")
IPV6_LINK_LOCAL = re.compile(r"(?i)\bfe[89ab][0-9a-f]?:[0-9a-f:.]*(?:%[0-9a-z_.-]+)?")
ADDRESS = re.compile(
    r"(?i)(?:(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|(?:[0-9a-f]{2}-){5}[0-9a-f]{2}|"
    r"\b[0-9a-f]{12}\b|\bfe[89ab][0-9a-f]?:[0-9a-f:.]*(?:%[0-9a-z_.-]+)?)"
)
HASH_CHARS = 16  # 8 bytes, hex encoded
HASH_STATUSES = {"not_printed", "not_measured", "not_granted", "unavailable", "api_too_low",
                 "unsupported"}

AWARE_NUMBERS = ("maxNdiInterfaces", "maxNdpSessions", "maxPublishes", "maxSubscribes",
                 "supportedCipherSuites")
AWARE_BOOLEANS = {
    "isInstantCommunicationModeSupported": "isInstantCommunicationModeSupported",
    "isInstantCommunicationModeSupport": "isInstantCommunicationModeSupported",
    "isNanPairingSupported": "isNanPairingSupported",
    "isSuspensionSupported": "isSuspensionSupported",
    "is6gSupported": "is6gSupported",
    "is6GSupported": "is6gSupported",
    "isHeSupported": "isHeSupported",
    "isHESupported": "isHeSupported",
}


class CommandResult:
    __slots__ = ("returncode", "stdout", "stderr")

    def __init__(self, returncode: int, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr

    @property
    def ok(self) -> bool:
        return self.returncode == 0


def _run_process(argv: list[str], timeout: int) -> CommandResult:
    try:
        completed = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return CommandResult(124)
    except OSError as error:
        return CommandResult(127, stderr=type(error).__name__)
    return CommandResult(completed.returncode, completed.stdout or "", completed.stderr or "")


class Adb:
    """Thin adb wrapper; a runner can be injected for host tests."""

    def __init__(self, path: str, runner=_run_process):
        self.path = str(path)
        self._runner = runner

    def get_state(self, serial: str) -> CommandResult:
        return self._runner([self.path, "-s", serial, "get-state"], 10)

    def shell(self, serial: str, *args: str, timeout: int = 30) -> CommandResult:
        return self._runner([self.path, "-s", serial, "shell", *args], timeout)

    def exec_out(self, serial: str, *args: str, timeout: int = 30) -> CommandResult:
        return self._runner([self.path, "-s", serial, "exec-out", *args], timeout)


class Anonymizer:
    """One fresh salt per run; it is never written, and only hashes reach an output file."""

    def __init__(self, salt: bytes | None = None):
        self._salt = bytes(salt) if salt is not None else secrets.token_bytes(32)

    @property
    def salt_hex(self) -> str:
        return self._salt.hex()

    def value(self, value) -> str | None:
        if value is None:
            return None
        text = str(value).strip()
        if not text or text.lower() in {"<unknown ssid>", "<unknown>", "unknown", "null", "none"}:
            return None
        if MAC.fullmatch(text) or MAC_HYPHEN.fullmatch(text) or MAC_PLAIN.fullmatch(text):
            text = re.sub(r"[:-]", "", text).lower()
        elif IPV6_LINK_LOCAL.fullmatch(text):
            text = text.lower()
        digest = hashlib.sha256(self._salt + b"|" + text.encode("utf-8")).hexdigest()
        return digest[: HASH_CHARS]


def _is_hash(value) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{16}", value) is not None


def plain_label(value) -> str | None:
    """Model/SoC text only; anything address-shaped is rejected rather than stored."""
    if value is None:
        return None
    text = str(value).strip()
    if not text or len(text) > 64 or ADDRESS.search(text) or ":" in text or '"' in text:
        return None
    return text


def parse_bool(value) -> bool | None:
    if value is None:
        return None
    text = str(value).strip().lower()
    if text == "true":
        return True
    if text == "false":
        return False
    if text in {"1", "yes", "enabled", "supported"}:
        return True
    if text in {"0", "no", "disabled", "unsupported", "not supported", "not_supported"}:
        return False
    return None


def parse_int(value) -> int | None:
    if value is None:
        return None
    try:
        return int(str(value).strip(), 0)
    except (TypeError, ValueError):
        return None


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return value[1:-1]
    return value


def band_from_mhz(frequency: int | None) -> int | None:
    if frequency is None:
        return None
    if 2400 <= frequency <= 2500:
        return 2
    if 4900 <= frequency < 5925:
        return 5
    if 5925 <= frequency <= 7125:
        return 6
    return None


def _wifi_info_line(text: str) -> str | None:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("mWifiInfo ") or stripped.startswith("WifiInfo:"):
            return stripped
    return None


def _wifi_info_completed(info: str) -> bool:
    match = re.search(r"(?i)\bsupplicant state:\s*([A-Z_]+)", info)
    return match is None or match.group(1).upper() == "COMPLETED"


def parse_wifi_status(text: str, anonymizer: Anonymizer) -> dict:
    """Wi-Fi on/off, connected SSID/BSSID (hashed) and the connected band."""
    result = {
        "enabled": None,
        "connected": None,
        "ssid_sha256": None,
        "bssid_sha256": None,
        "band_ghz": None,
    }
    match = re.search(r"(?m)^Wifi is (enabled|disabled)\s*$", text)
    if match:
        result["enabled"] = match.group(1) == "enabled"
    match = re.search(r"(?m)^Wifi is connected to (.+?)\s*$", text)
    if match:
        result["connected"] = True
        result["ssid_sha256"] = anonymizer.value(_unquote(match.group(1).strip()))
    elif re.search(r"(?m)^Wifi is not connected\s*$", text):
        result["connected"] = False

    info = _wifi_info_line(text)
    if result["connected"] is not False and info is not None and _wifi_info_completed(info):
        if result["ssid_sha256"] is None:
            match = re.search(r"(?i)\bSSID:\s*(\"(?:[^\"\\]|\\.)*\"|[^,]+)", info)
            if match:
                ssid = _unquote(match.group(1).strip())
                if ssid and ssid.lower() not in {"<unknown ssid>", "<unknown>"}:
                    result["connected"] = True
                    result["ssid_sha256"] = anonymizer.value(ssid)
        match = re.search(r"(?i)\bBSSID:\s*((?:[0-9a-f]{2}:){5}[0-9a-f]{2})", info)
        if match:
            result["bssid_sha256"] = anonymizer.value(match.group(1))
        match = re.search(r"(?i)\bFrequency:\s*(\d+)\s*MHz", info)
        if match:
            result["band_ghz"] = band_from_mhz(int(match.group(1)))
        if result["connected"] is None:
            result["connected"] = True
    elif result["connected"] and info is None:
        # Older status output has the connected line but no separate WifiInfo line.
        match = re.search(r"(?i)\bBSSID:\s*((?:[0-9a-f]{2}:){5}[0-9a-f]{2})", text)
        if match:
            result["bssid_sha256"] = anonymizer.value(match.group(1))
        match = re.search(r"(?i)\bFrequency:\s*(\d+)\s*MHz", text)
        if match:
            result["band_ghz"] = band_from_mhz(int(match.group(1)))
    return result


def _capability_values(text: str) -> dict[str, str]:
    """key=value (AOSP Capabilities.toString) or key: value (vendor dumps)."""
    values = {}
    for key, value in re.findall(r"\b([A-Za-z0-9_]+)\s*[=:]\s*([^,\]\s]+)", text):
        values.setdefault(key, value.rstrip(",;"))
    return values


def parse_wifiaware(text: str) -> dict:
    """The agreed aware block from the census section, missing values stay None."""
    result = {
        "mUsageEnabled": None,
        "availability": None,
        "attachedClients": None,
        "maxNdiInterfaces": None,
        "maxNdpSessions": None,
        "maxPublishes": None,
        "maxSubscribes": None,
        "supportedCipherSuites": None,
        "isInstantCommunicationModeSupported": None,
        "isNanPairingSupported": None,
        "isSuspensionSupported": None,
        "is6gSupported": None,
        "isHeSupported": None,
    }
    match = re.search(r"(?m)^\s*mUsageEnabled\s*[=:]\s*(true|false)\s*$", text, re.I)
    if match:
        result["mUsageEnabled"] = match.group(1).lower() == "true"

    values = _capability_values(text)
    for name in AWARE_NUMBERS:
        if name in values:
            result[name] = parse_int(values[name])
    for source, target in AWARE_BOOLEANS.items():
        if source in values and result[target] is None:
            result[target] = parse_bool(values[source])

    result["attachedClients"] = _attached_clients(text)
    return result


def _attached_clients(text: str) -> int | None:
    match = re.search(r"(?m)^\s*mClients\s*[=:]\s*(.+)$", text)
    if not match:
        return None
    body = match.group(1).strip()
    if body in {"[]", "{}", "0"}:
        return 0
    integer = parse_int(body)
    if integer is not None:
        return integer
    if body.startswith("{") and body.endswith("}"):
        inner = body[1:-1].strip()
        return 0 if not inner else inner.count("=")
    if body.startswith("[") and body.endswith("]"):
        inner = body[1:-1].strip()
        if not inner or inner == "{}":
            return 0
        return len(re.findall(r"WifiAwareClientState|clientId=", inner)) or None
    return None


def _p2p_groups_section(text: str) -> str | None:
    """The mGroups block exactly as WifiP2pServiceImpl's state-machine dump prints it."""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if re.match(r"^\s*mGroups\b", line):
            start = index
            break
    if start is None:
        return None
    section = [re.sub(r"^\s*mGroups\s?", "", lines[start], count=1)]
    for line in lines[start + 1:]:
        if not line.strip() or re.match(
                r"^(mAutonomousGroup|mJoinExistingGroup|mDiscoveryStarted|mListenStarted|"
                r"mDetailedState|mTemporarilyDisconnectedWifi|mServiceDiscReqId|mDeathDataByBinder|"
                r"mClientInfoList|mActiveClients|mPeerAuthorizingTimestamp|isOwnershipSupported|"
                r"isDualP2pSupported)\b", line):
            break
        section.append(line)
    return "\n".join(section)


def _saved_p2p_group(text: str):
    """True/False for a saved group (networkId >= 0), or 'not_measured'."""
    section = _p2p_groups_section(text)
    if section is None:
        return "not_measured"
    starts = list(re.finditer(r"(?m)^\s*network:\s*", section))
    if not starts:
        return False if not section.strip() else "not_measured"
    ids = []
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(section)
        block = section[match.start():end]
        network_id = re.search(r"(?m)^\s*networkId:\s*(-?\d+)\s*$", block)
        if network_id is None:
            return "not_measured"
        ids.append(int(network_id.group(1)))
    return any(value >= 0 for value in ids)


def parse_wifip2p(text: str) -> dict:
    result = {
        "state": None,
        "persistent_group_saved": None,
        "device_address_randomization": "not_printed",
        "device_address_sha256": "not_printed",
    }
    match = re.search(r"(?m)^\s*mDetailedState\s+([A-Za-z_]+)\s*$", text)
    if match:
        result["state"] = match.group(1).lower()
    result["persistent_group_saved"] = _saved_p2p_group(text)
    match = re.search(
        r"(?i)(?:config_wifi_p2p_mac_randomization_supported|p2p_mac_randomization(?:_supported)?|"
        r"mac_randomization)\s*[=:]\s*(true|false|1|0|enabled|disabled|supported|unsupported)",
        text,
    )
    if match:
        result["device_address_randomization"] = (
            "enabled" if parse_bool(match.group(1)) else "disabled"
        )
    # WifiP2pServiceImpl prints peers and groups, never this device's own address.
    return result


def _bracket_content(text: str, start: int) -> str | None:
    depth = 0
    for index in range(start, len(text)):
        character = text[index]
        if character == "[":
            depth += 1
        elif character == "]":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    return None


def _dump_section(text: str, start: str) -> str:
    index = text.find(start)
    if index < 0:
        return ""
    end = text.find("\nDump of ", index + len(start))
    return text[index:] if end < 0 else text[index:end]


def _chip_text(text: str) -> str:
    """HalDeviceManager plus the chip-info lines some builds print outside that dump."""
    lines = [line for line in text.splitlines() if re.search(
        r"mDebugChipsInfo|WifiChipInfo|wifi_static_chip_info|availableModes|availableCombinations",
        line)]
    return _dump_section(text, "Dump of HalDeviceManager") + "\n" + "\n".join(lines)


def _limit_entries(text: str) -> tuple[list[list[tuple[int, set[int]]]], str | None]:
    """Chip interface combinations and the numbering style used by this dump."""
    combinations = []
    styles = set()
    for match in re.finditer(r"limits\s*=\s*\[", text):
        content = _bracket_content(text, match.end() - 1)
        if content is None:
            continue
        limits = []
        for body in re.findall(r"\{([^{}]*maxIfaces[^{}]*)\}", content):
            count_match = re.search(r"maxIfaces\s*=\s*(\d+)", body)
            types_match = re.search(r"types\s*=\s*\[([0-9,\s]*)\]", body)
            if count_match is None or types_match is None:
                continue
            if re.search(r"\.\s*types\s*=", body):
                styles.add("old")
            elif re.search(r"(?<!\.)\btypes\s*=", body):
                styles.add("new")
            types = {int(item) for item in re.findall(r"\d+", types_match.group(1))}
            limits.append((parse_int(count_match.group(1)) or 1, types))
        if limits:
            combinations.append(limits)
    if not combinations:
        return [], None
    if len(styles) == 1:
        return combinations, styles.pop()
    return combinations, "unknown"


def _pair_supported(limits: list[tuple[int, set[int]]], first: int, second: int) -> bool:
    for count, types in limits:
        if first in types and second in types and count >= 2:
            return True
    for index, (count, types) in enumerate(limits):
        if first not in types:
            continue
        for other, (other_count, other_types) in enumerate(limits):
            if second not in other_types:
                continue
            if other != index or count >= 2:
                return True
    return False


def parse_concurrency(text: str) -> dict:
    """STA + Aware / STA + P2P / P2P + Aware support from the chip and concurrency dumps."""
    result = {"sta_aware": None, "sta_p2p": None, "p2p_aware": None}
    chip = _chip_text(text)
    combinations, style = _limit_entries(chip)
    if combinations:
        present = {value for limits in combinations for _, types in limits for value in types}
        # Type 4 cannot exist in the old numbering, so it identifies the newer one
        # even when a vendor prints it with the older field formatting.
        if 4 in present:
            style = "new"
        if style == "old":
            pairs = (("sta_aware", 0, 3), ("sta_p2p", 0, 2), ("p2p_aware", 2, 3))
        elif style == "new":
            pairs = (("sta_aware", 0, 4), ("sta_p2p", 0, 3), ("p2p_aware", 3, 4))
        else:
            pairs = None
        if pairs is None:
            result = {key: "unknown" for key in result}
        else:
            for key, first, second in pairs:
                if first not in present or second not in present:
                    continue
                result[key] = any(_pair_supported(limits, first, second) for limits in combinations)

    map_support = {"sta_aware": False, "sta_p2p": False, "p2p_aware": False}
    saw_map = False
    for group in re.findall(r"\{([^{}]*\bSTA\s*=[^{}]*)\}", chip, re.I):
        values = {key.lower(): parse_int(value) for key, value in
                  re.findall(r"\b(STA|AP|P2P|NAN|AWARE)\s*=\s*(\d+)", group, re.I)}
        if "sta" not in values or not ({"p2p", "nan", "aware"} & values.keys()):
            continue
        saw_map = True
        sta = values.get("sta", 0) or 0
        p2p = values.get("p2p", 0) or 0
        aware = max(values.get("nan", 0) or 0, values.get("aware", 0) or 0)
        map_support["sta_aware"] |= sta > 0 and aware > 0
        map_support["sta_p2p"] |= sta > 0 and p2p > 0
        map_support["p2p_aware"] |= p2p > 0 and aware > 0
    if saw_map:
        result.update(map_support)

    readable = {
        "sta_aware": r"STA\s*\+\s*(?:Aware|AWARE|NAN)",
        "sta_p2p": r"STA\s*\+\s*P2P",
        "p2p_aware": r"P2P\s*\+\s*(?:Aware|AWARE|NAN)",
    }
    active = _dump_section(text, "Dump of ActiveModeWarden")
    for key, name in readable.items():
        match = re.search(
            rf"(?i){name}\s*(?:concurrency)?\s*(?:supported)?\s*[:\-]?\s*"
            r"(supported|not supported|unsupported|true|false|yes|no)",
            active,
        )
        if match:
            result[key] = parse_bool(match.group(1))
    return result


def _shell_text(adb, serial: str, args: tuple[str, ...], failures: list[str],
                timeout: int = 30) -> str | None:
    result = adb.shell(serial, *args, timeout=timeout)
    if not result.ok:
        failures.append(" ".join(args))
        return None
    return result.stdout


def _wifi_status_text(adb, serial: str) -> str | None:
    result = adb.shell(serial, "cmd", "wifi", "status", timeout=20)
    if result.ok or re.search(r"(?m)^Wifi is (enabled|disabled)\s*$", result.stdout):
        return result.stdout
    return None


def _wifi_enabled_setting(adb, serial: str) -> bool | None:
    result = adb.shell(serial, "settings", "get", "global", "wifi_on", timeout=10)
    value = result.stdout.strip()
    return True if value == "1" else False if value == "0" else None


def _wait_wifi(adb, serial: str, enabled: bool, timeout: float = 20.0) -> bool:
    deadline = time.monotonic() + timeout
    marker = "Wifi is enabled" if enabled else "Wifi is disabled"
    while time.monotonic() < deadline:
        text = _wifi_status_text(adb, serial)
        if text is not None and any(line.strip() == marker for line in text.splitlines()):
            return True
        if text is None and _wifi_enabled_setting(adb, serial) is enabled:
            return True
        time.sleep(0.5)
    return False


def _set_wifi(adb, serial: str, enabled: bool) -> bool:
    value = "enabled" if enabled else "disabled"
    result = adb.shell(serial, "cmd", "wifi", "set-wifi-enabled", value, timeout=20)
    return result.ok


def _test_package_installed(adb, serial: str, package: str) -> bool:
    result = adb.shell(serial, "pm", "path", package, timeout=10)
    return result.ok and "package:" in result.stdout


def _probe_app_state(adb, serial: str) -> str:
    """'active' when the call/stream session service is running, else 'idle'/'unknown'."""
    result = adb.shell(serial, "dumpsys", "activity", "services", APP_ID, timeout=20)
    if not result.ok or "Permission Denial" in result.stdout or "Permission denial" in result.stdout:
        return "unknown"
    return "active" if SESSION_SERVICE in result.stdout else "idle"


def _run_probe(adb, serial: str, anonymizer: Anonymizer, ok_to_stop_app: bool) -> tuple[str, dict | None]:
    """Return (status, parsed capabilities); the device file only ever holds hashes."""
    test_package = f"{APP_ID}.test"
    if not _test_package_installed(adb, serial, test_package):
        return "not_installed", None
    if not ok_to_stop_app:
        app_state = _probe_app_state(adb, serial)
        if app_state != "idle":
            return f"app_{app_state}", None
    path = f"/sdcard/Android/data/{APP_ID}/files/{PROBE_FILE}"
    adb.shell(serial, "rm", "-f", path, timeout=10)
    command = [
        "am", "instrument", "-w", "-r",
        "-e", "class", TEST_CLASS,
        "-e", "wifi_census_salt", anonymizer.salt_hex,
        "-e", "wifi_census_timeout_ms", PROBE_TIMEOUT,
        f"{test_package}/{TEST_RUNNER}",
    ]
    result = adb.shell(serial, *command, timeout=120)
    success = result.ok and "OK (" in result.stdout
    pulled = adb.exec_out(serial, "cat", path, timeout=20)
    try:
        if not pulled.ok or not pulled.stdout.strip():
            return ("missing_output" if success else "test_failed"), None
        try:
            capabilities = json.loads(pulled.stdout)
        except json.JSONDecodeError:
            return "bad_output", None
        if not isinstance(capabilities, dict):
            return "bad_output", None
        if not success:
            return "test_failed", capabilities
        return "ok", capabilities
    finally:
        # The pulled JSON is already in memory; leave no probe file on the phone.
        try:
            adb.shell(serial, "rm", "-f", path, timeout=10)
        except Exception:
            pass


def _base_record(alias: str) -> dict:
    return {
        "schema_version": 1,
        "alias": alias,
        "status": "unavailable",
        "unavailable_reason": None,
        "model": None,
        "sdk": None,
        "soc_model": None,
        "features": {"wifi_aware": None, "wifi_direct": None, "wifi_rtt": None},
        "wifi": {
            "original_enabled": None,
            "enabled_by_census": False,
            "restored": None,
            "enabled": None,
            "connected": None,
            "ssid_sha256": None,
            "bssid_sha256": None,
            "band_ghz": None,
        },
        "aware": {
            "mUsageEnabled": None,
            "availability": None,
            "attachedClients": None,
            "maxNdiInterfaces": None,
            "maxNdpSessions": None,
            "maxPublishes": None,
            "maxSubscribes": None,
            "supportedCipherSuites": None,
            "isInstantCommunicationModeSupported": None,
            "isNanPairingSupported": None,
            "isSuspensionSupported": None,
            "is6gSupported": None,
            "isHeSupported": None,
        },
        "p2p": {
            "state": None,
            "persistent_group_saved": "not_measured",
            "device_address_randomization": "not_printed",
            "device_address_sha256": "not_printed",
        },
        "concurrency": {"sta_aware": None, "sta_p2p": None, "p2p_aware": None},
        "commands_failed": [],
        "probe": {"status": "not_requested"},
    }


def _collect(adb, alias: str, serial: str, anonymizer: Anonymizer, run_probe: bool,
             ok_to_stop_app: bool = False) -> dict:
    record = _base_record(alias)
    state = adb.get_state(serial)
    if not state.ok or state.stdout.strip() != "device":
        if state.returncode in {124, 127}:
            record["unavailable_reason"] = "adb_unavailable"
        else:
            record["unavailable_reason"] = plain_label(state.stdout.strip()) or "not connected"
        if run_probe:
            record["probe"]["status"] = "unavailable"
        return record
    record["status"] = "measured"
    failures = record["commands_failed"]
    restore_due = False
    try:
        model = _shell_text(adb, serial, ("getprop", "ro.product.model"), failures)
        sdk_text = _shell_text(adb, serial, ("getprop", "ro.build.version.sdk"), failures)
        soc_text = _shell_text(adb, serial, ("getprop", "ro.soc.model"), failures)
        record["model"] = plain_label(model)
        record["sdk"] = parse_int(sdk_text)
        record["soc_model"] = plain_label(soc_text)

        for alias_feature, feature in (
            ("wifi_aware", "android.hardware.wifi.aware"),
            ("wifi_direct", "android.hardware.wifi.direct"),
            ("wifi_rtt", "android.hardware.wifi.rtt"),
        ):
            result = adb.shell(serial, "pm", "has-feature", feature, timeout=20)
            value = parse_bool(result.stdout)
            if value is None:
                failures.append(f"pm has-feature {feature}")
            record["features"][alias_feature] = value

        status_text = _wifi_status_text(adb, serial)
        if status_text is None:
            failures.append("cmd wifi status")
        original = parse_wifi_status(status_text or "", anonymizer)
        if original["enabled"] is None:
            original["enabled"] = _wifi_enabled_setting(adb, serial)
        record["wifi"]["original_enabled"] = original["enabled"]

        if original["enabled"] is False and record["features"]["wifi_aware"] is True:
            # Mark the restore before sending: a timeout or interrupt after the
            # command may still have switched Wi-Fi on.
            restore_due = True
            record["wifi"]["enabled_by_census"] = True
            try:
                if _set_wifi(adb, serial, True):
                    _wait_wifi(adb, serial, True)
                else:
                    failures.append("cmd wifi set-wifi-enabled enabled")
            except Exception:
                failures.append("cmd wifi set-wifi-enabled enabled")

        measured = parse_wifi_status(_wifi_status_text(adb, serial) or "", anonymizer)
        if measured["enabled"] is None:
            measured["enabled"] = _wifi_enabled_setting(adb, serial)
        if measured["enabled"] is None:
            measured = original
        record["wifi"].update(measured)

        aware_text = _shell_text(adb, serial, ("dumpsys", "wifiaware"), failures, timeout=60)
        if aware_text is not None:
            record["aware"].update(parse_wifiaware(aware_text))
        record["aware"]["availability"] = _availability(
            record["features"]["wifi_aware"],
            record["wifi"]["enabled"],
            record["aware"]["mUsageEnabled"],
        )

        p2p_text = _shell_text(adb, serial, ("dumpsys", "wifip2p"), failures, timeout=60)
        if p2p_text is not None:
            record["p2p"].update(parse_wifip2p(p2p_text))

        wifi_text = _shell_text(adb, serial, ("dumpsys", "wifi"), failures, timeout=90)
        if wifi_text is not None:
            record["concurrency"].update(parse_concurrency(wifi_text))
            fallback = parse_wifi_status(wifi_text, anonymizer)
            for key in ("connected", "ssid_sha256", "bssid_sha256", "band_ghz"):
                if record["wifi"][key] is None and fallback[key] is not None:
                    record["wifi"][key] = fallback[key]

        if run_probe:
            if ok_to_stop_app:
                print(f"{alias}: --probe will force-stop MeshLive "
                      f"(--probe-ok-to-stop-app given)", file=sys.stderr)
            probe_status, capabilities = _run_probe(adb, serial, anonymizer, ok_to_stop_app)
            record["probe"] = {"status": probe_status}
            if probe_status in {"not_installed", "app_active", "app_unknown", "app_state_unknown"}:
                print(f"{alias}: probe not run ({probe_status})", file=sys.stderr)
            if capabilities is not None:
                record["probe"]["capabilities"] = capabilities
                record["probe"]["file"] = f"{alias}-{PROBE_FILE}"
                self_hash = record["p2p"]["device_address_sha256"]
                probe_hash = _probe_p2p_address(capabilities)
                if _is_hash(self_hash) and probe_hash is not None:
                    record["probe"]["p2p_address_matches"] = self_hash == probe_hash
    except Exception as error:
        record["status"] = "error"
        record["unavailable_reason"] = type(error).__name__
    finally:
        if restore_due:
            try:
                sent = _set_wifi(adb, serial, False)
                restored = bool(sent and _wait_wifi(adb, serial, False))
            except Exception:
                restored = False
            record["wifi"]["restored"] = restored
            if not restored:
                failures.append("cmd wifi set-wifi-enabled disabled")
                # Printed here, not only in main: after Ctrl-C main never sees this record.
                print(f"{alias}: Wi-Fi restore FAILED; switch this phone's Wi-Fi back off manually",
                      file=sys.stderr)
        else:
            record["wifi"]["restored"] = None
    return record


def _availability(feature, wifi_enabled, usage_enabled) -> bool | None:
    if feature is False:
        return False
    if feature is None or wifi_enabled is None or usage_enabled is None:
        return None
    return bool(wifi_enabled and usage_enabled)


def _probe_p2p_address(capabilities: dict) -> str | None:
    direct = capabilities.get("wifi_direct")
    if not isinstance(direct, dict):
        return None
    value = direct.get("device_address_sha256")
    return value if _is_hash(value) else None


def scrub_json(value, anonymizer: Anonymizer):
    """Last line of defence: no address-shaped text can reach a file."""
    if isinstance(value, dict):
        cleaned = {}
        for key, item in value.items():
            scrubbed = scrub_json(item, anonymizer)
            if key.endswith("_sha256") and scrubbed is not None and not (
                    _is_hash(scrubbed) or scrubbed in HASH_STATUSES):
                continue
            cleaned[key] = scrubbed
        return cleaned
    if isinstance(value, list):
        return [scrub_json(item, anonymizer) for item in value]
    if isinstance(value, str):
        scrubbed = ADDRESS.sub(lambda match: anonymizer.value(match.group(0)) or "", value)
        if re.search(r"(?i)\b(?:ssid|bssid|device name)\b", scrubbed):
            return None
        return scrubbed
    return value


def _cell(value, kind: str = "text") -> str:
    if value is None:
        return "n/a"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if kind == "hash":
        return f"`{value}`" if isinstance(value, str) else "n/a"
    text = str(value).replace("|", "\\|")
    return text


def _summary(records: dict[str, dict], run_name: str) -> str:
    lines = [
        f"# Wi-Fi capability census ({run_name})",
        "",
        "Read-only M0 census of Design WF. Raw command output was parsed in memory; every network",
        "name and address below is `SHA-256(run salt | value)[0:8]` with a salt that was never written.",
        "P2P fields marked `not_printed` are not in `dumpsys wifip2p`; the probe's `requestDeviceInfo`",
        "is the source for this device's Wi-Fi Direct address.",
        "",
        "`--probe` runs `am instrument`, which force-stops MeshLive. It is refused while",
        "`ActiveVideoSessionService` is running unless `--probe-ok-to-stop-app` is given; the probe",
        "file is deleted from the phone after it is pulled.",
        "",
        "| Phone | Status | Model | SDK | SoC | Wi-Fi Aware | Wi-Fi Direct | Wi-Fi RTT | "
        "Wi-Fi on (census) | Switched on | Restored | Band | SSID hash | BSSID hash |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for alias, record in records.items():
        wifi = record["wifi"]
        band = f"{wifi['band_ghz']} GHz" if wifi["band_ghz"] else None
        lines.append(
            f"| {alias} | {record['status']} | {_cell(record['model'])} | {_cell(record['sdk'])} | "
            f"{_cell(record['soc_model'])} | {_cell(record['features']['wifi_aware'])} | "
            f"{_cell(record['features']['wifi_direct'])} | {_cell(record['features']['wifi_rtt'])} | "
            f"{_cell(wifi['enabled'])} | {_cell(wifi['enabled_by_census'])} | {_cell(wifi['restored'])} | "
            f"{_cell(band)} | {_cell(wifi['ssid_sha256'], 'hash')} | "
            f"{_cell(wifi['bssid_sha256'], 'hash')} |"
        )
    lines += [
        "",
        "| Phone | Aware mUsageEnabled | Aware availability | Attached clients | max NDI | max NDP | "
        "max publishes | max subscribes | cipher suites | instant mode | NAN pairing | suspension | 6 GHz | HE |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for alias, record in records.items():
        aware = record["aware"]
        lines.append(
            f"| {alias} | {_cell(aware['mUsageEnabled'])} | {_cell(aware['availability'])} | "
            f"{_cell(aware['attachedClients'])} | {_cell(aware['maxNdiInterfaces'])} | "
            f"{_cell(aware['maxNdpSessions'])} | {_cell(aware['maxPublishes'])} | "
            f"{_cell(aware['maxSubscribes'])} | {_cell(aware['supportedCipherSuites'])} | "
            f"{_cell(aware['isInstantCommunicationModeSupported'])} | "
            f"{_cell(aware['isNanPairingSupported'])} | {_cell(aware['isSuspensionSupported'])} | "
            f"{_cell(aware['is6gSupported'])} | {_cell(aware['isHeSupported'])} |"
        )
    lines += [
        "",
        "| Phone | P2P state | Persistent group saved | P2P address randomisation | P2P address hash | "
        "STA + Aware | STA + P2P | P2P + Aware | Probe | Attach | Attach ms | Location | "
        "Probe P2P hash |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for alias, record in records.items():
        p2p = record["p2p"]
        concurrency = record["concurrency"]
        probe = record.get("probe") or {}
        capabilities = probe.get("capabilities") if isinstance(probe.get("capabilities"), dict) else {}
        aware_probe = capabilities.get("aware") if isinstance(capabilities.get("aware"), dict) else {}
        attach = aware_probe.get("attach_status")
        attach_ms = aware_probe.get("attach_elapsed_ms")
        location = aware_probe.get("location_enabled")
        probe_hash = _probe_p2p_address(capabilities)
        lines.append(
            f"| {alias} | {_cell(p2p['state'])} | {_cell(p2p['persistent_group_saved'])} | "
            f"{_cell(p2p['device_address_randomization'])} | {_cell(p2p['device_address_sha256'], 'hash')} | "
            f"{_cell(concurrency['sta_aware'])} | {_cell(concurrency['sta_p2p'])} | "
            f"{_cell(concurrency['p2p_aware'])} | {_cell(probe.get('status'))} | {_cell(attach)} | "
            f"{_cell(attach_ms)} | {_cell(location)} | {_cell(probe_hash, 'hash')} |"
        )
    lines += [
        "",
        "Wi-Fi was switched on only where Aware is supported and Wi-Fi was off; `wifi.enabled_by_census`",
        "and `wifi.restored` in `census.json` record that it was put back. `commands_failed` names any",
        "command that did not return success; values it would have supplied stay missing.",
        "",
    ]
    return "\n".join(lines)


def _write(run_dir: Path, records: dict[str, dict], anonymizer: Anonymizer) -> None:
    run_dir.mkdir(parents=True, exist_ok=False)
    documents = {}
    for alias, record in records.items():
        safe = scrub_json(record, anonymizer)
        probe = safe.get("probe", {})
        if probe.get("file") and isinstance(probe.get("capabilities"), dict):
            (run_dir / probe["file"]).write_text(
                json.dumps(probe["capabilities"], indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        documents[alias] = safe
        (run_dir / f"{alias}.json").write_text(
            json.dumps(safe, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    census = {
        "schema_version": 1,
        "run": run_dir.name,
        "phones": documents,
    }
    (run_dir / "census.json").write_text(
        json.dumps(scrub_json(census, anonymizer), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (run_dir / "summary.md").write_text(
        _summary(scrub_json(documents, anonymizer), run_dir.name), encoding="utf-8"
    )


def _load_devices(path: Path) -> dict[str, str]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    devices = data.get("devices", data)
    if not isinstance(devices, dict):
        raise ValueError("Device registry must map aliases to serials")
    result = {}
    for alias, entry in devices.items():
        serial = entry.get("serial") if isinstance(entry, dict) else entry
        if isinstance(serial, str) and serial:
            result[alias] = serial
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--devices", default=str(DEVICES), help="device registry (tools/benchmark_devices.json)")
    parser.add_argument("--phones", required=True, help="comma-separated aliases from the registry")
    parser.add_argument("--adb", default=default_adb(), help="path to adb")
    parser.add_argument("--output", required=True, help="new run directory under benchmarks/runs")
    parser.add_argument("--probe", action="store_true",
                        help="run the already installed DeviceWifiCapabilitiesTest and merge its "
                             "JSON; am instrument force-stops MeshLive")
    parser.add_argument("--probe-ok-to-stop-app", action="store_true",
                        help="allow --probe while MeshLive is in a call or streaming "
                             "(am instrument force-stops the app)")
    args = parser.parse_args(argv)

    try:
        run_dir = output_path(args.output, "runs")
    except (ValueError, OSError) as error:
        parser.error(f"output path rejected: {error}")
    if run_dir.exists():
        parser.error(f"output directory already exists: {args.output}")

    try:
        registry = _load_devices(Path(args.devices))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(f"cannot read the device registry: {error}")
    aliases = []
    for alias in (part.strip() for part in args.phones.split(",")):
        if not alias:
            continue
        if alias in FORBIDDEN_ALIASES:
            parser.error(f"{alias} is excluded from the census and is not allowed")
        if alias not in registry:
            parser.error(f"unknown phone alias: {alias}")
        if alias not in aliases:
            aliases.append(alias)
    if not aliases:
        parser.error("at least one phone alias is required")

    adb = Adb(args.adb)
    anonymizer = Anonymizer()
    records = {}
    for alias in aliases:
        try:
            records[alias] = _collect(adb, alias, registry[alias], anonymizer, args.probe,
                                      args.probe_ok_to_stop_app)
        except Exception as error:  # restore already ran in _collect's finally
            record = _base_record(alias)
            record["status"] = "error"
            record["unavailable_reason"] = type(error).__name__
            records[alias] = record

    restore_failures = [
        alias for alias, record in records.items()
        if record["wifi"]["enabled_by_census"] and record["wifi"]["restored"] is not True
    ]
    try:
        _write(run_dir, records, anonymizer)
    except FileExistsError:
        parser.error(f"output directory already exists: {args.output}")
    measured = sum(1 for record in records.values() if record["status"] == "measured")
    print(f"Wi-Fi census written to {run_dir} ({measured}/{len(records)} phones measured)")
    return 0 if measured and not restore_failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
