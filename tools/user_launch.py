"""A user launch as a person's phone resolves it (package UL1).

`user_launch: true` in a case's tunables starts the debug build with `test_user_launch=true`: the app then ignores the
benchmark extras of every promoted or opt-in feature and reads what a person's launch reads - the stored choice of the
Settings screen, else the shipped default. Rotating discovery IDs, hidden identities and pseudonyms are shipped ON; the
opt-in switches (Experimental relayed calls, the Wi-Fi switches) are shipped OFF, so a case that needs one must put the
phone in the state a person's own choice leaves it in. This module owns that for the runners:

* `user_settings` (case field, a user-launch case only) names the choices, for example
  `{"relayed_calls": true}` or `{"wifi": true}`. It is expanded here (`resolve`) into exactly the stored booleans the
  Settings switch writes (`SETTINGS`, read from MainActivity / RelayedCalls.kt / WifiFeatures.kt at integration a64f24b0);
* `merge_prefs` edits the app's `shared_prefs/bluetooth_video_settings.xml` text and keeps every other stored value;
  the runner writes it with `run-as` while the app is stopped, reads it back and records both in the manifest;
* `assess` compares each setting with the line the app logs about it after launch (`BENCHMARK relayed_calls: enabled=`,
  `BENCHMARK wifi_switches: carrier=`); `applied_arms` blocks the case before the dial when a line shows another value;
* `app_default` (call endpoint profile, `{"profile_source": "app_default"}`) is a person's own video choice: the runner
  sends no resolution, frame rate, bitrate, encoder, allocation, buffer or thermal extra, so the app resolves them from
  its stored video settings and its Automatic encoder choice (MainActivity.applyDebugLaunchExtras reads each
  `test_width`/`test_height`/`test_fps`/`test_bitrate_kbps` with the stored value as its fallback, a launch without
  `test_codec` keeps the picker on Automatic, one without `test_rate_control` and `test_playback_delay` keeps the mode's
  values). `VIDEO_SETTINGS` names the stored keys the runner reads back; a value that is not the shipped default blocks
  the case unless the case states it in `user_settings` (`video_state`);
* `blockers` is the precheck that needs no phone: a case whose request cannot work as a user launch (a needed opt-in
  switch not set, a privacy default turned off, a static relay chain under rotating IDs) is BLOCKED before the dial
  instead of failing after its 90 s deadline.
"""
import base64
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

PACKAGE_PREFS_FILE = "shared_prefs/bluetooth_video_settings.xml"  # CallFeatureDefaults.APP_SETTINGS_PREFERENCES
CHUNK_CHARS = 4000  # base64 characters per `run-as` write (a multiple of 4), far below the device argument limit

RELAYED_LINE = "BENCHMARK relayed_calls:"
WIFI_LINE = "BENCHMARK wifi_switches:"


def _relayed(on):
    # The Settings switch writes the key; the first switch-on dialog (both choices preselected) also writes the three
    # others when the person confirms it (MainActivity.showRelayedCallsPrompt). A switch-off writes the key alone.
    writes = {"experimental_relayed_calls": on}
    if on:
        writes.update(relayed_calls_prompted=True, relayed_calls_help=True, relayed_calls_consent=True)
    return writes


# name -> (stored writes for a value, line the app logs, field of that line, how the app renders a boolean)
SETTINGS = {
    "relayed_calls": (_relayed, RELAYED_LINE, "enabled", "true"),
    "relayed_calls_help": (lambda on: {"relayed_calls_help": on}, RELAYED_LINE, "help", "true"),
    "relayed_calls_consent": (lambda on: {"relayed_calls_consent": on}, RELAYED_LINE, "consent", "true"),
    "wifi_carrier": (lambda on: {"wifi_carrier": on}, WIFI_LINE, "carrier", "on"),
    "wifi_wider_reach": (lambda on: {"wifi_wider_reach": on}, WIFI_LINE, "wider", "on"),
    "wifi_direct": (lambda on: {"wifi_direct": on}, WIFI_LINE, "direct", "on"),
    # The one-time "Before you go live" notice (MainActivity.armDiscoverableStream, LiveDisclosure.PREF_VIDEO): a person
    # who goes live has confirmed it once; until then Go live waits on the dialog. The app logs no line for it, so the
    # read-back of the stored value is the evidence.
    "live_disclosed": (lambda on: {"video_live_disclosed": on}, None, None, None),
}
# `wifi: true` is the person turning on "Use Wi-Fi for faster connections" and the sub-switch the case's carrier needs.
WIFI_SUB_SWITCH = {"wifi_aware": None, "wifi_aware_wider": "wifi_wider_reach", "wifi_direct": "wifi_direct"}
# The wider-reach and Direct switches count only while the carrier switch is on (FeatureSpec.dependsOn).
NEEDS_CARRIER = {"wifi_wider_reach", "wifi_direct"}
# Defaults that a user launch always uses (constants without a Settings row); a tunable turning one off is ignored.
USER_LAUNCH_DEFAULTS_ON = ("rotating_ids", "bmh3", "bmh3_pseudonyms")


APP_DEFAULT = "app_default"
PROFILE_SOURCE = "profile_source"

# name -> (stored key, type, shipped default, scale from the case value to the stored value). Sources at integration
# 0983c1f2: MainActivity.restoreVideoSettings (video_width/height/fps/bitrate; VideoSettings.DEFAULT is 426x240, 15 fps,
# 200_000 bps), PREF_ADVANCED_SETTINGS (default false), PREF_AUTOMATIC_THERMAL_REDUCTION (default true),
# PREF_MANUAL_RATE_CONTROL (default CBR) and PREF_RECEIVER_PLAYBACK_DELAY (absent = AUTO), the last two read only while
# Advanced (manual) video settings are on (applySettingsModeValues).
VIDEO_SETTINGS = {
    "video_width": ("video_width", int, 426, 1),
    "video_height": ("video_height", int, 240, 1),
    "video_fps": ("video_fps", int, 15, 1),
    "video_bitrate_kbps": ("video_bitrate", int, 200, 1000),
    "advanced_settings": ("advanced_settings", bool, False, 1),
    "automatic_thermal_reduction": ("automatic_thermal_reduction", bool, True, 1),
    "manual_rate_control": ("manual_rate_control", str, "CBR", 1),
    "receiver_playback_delay": ("receiver_playback_delay", str, "AUTO", 1),
}
# Read only while Advanced settings are on: a stored value the app does not use cannot change what a launch gets.
VIDEO_NEEDS_ADVANCED = ("manual_rate_control", "receiver_playback_delay")


def is_app_default(profile):
    return isinstance(profile, dict) and profile.get(PROFILE_SOURCE) == APP_DEFAULT


def app_default_phones(case):
    """The call endpoints whose profile is the app's own choice (a call case's `profiles`)."""
    return [phone for phone, profile in (case.get("profiles") or {}).items() if is_app_default(profile)]


def validate_app_default(case):
    """ValueError when an app_default endpoint is not a plain user-launch endpoint (no phone is touched).

    An app_default endpoint states no setting at all (a stated value would be a pinned request, the thing the choice
    exists to avoid), needs `user_launch` (a benchmark launch keeps its extras and the case's `profiles` would be
    meaningless), and has no raw input (a raw fixture declares the geometry the encoder must run at)."""
    for phone in app_default_phones(case):
        extra = sorted(set(case["profiles"][phone]) - {PROFILE_SOURCE})
        if extra:
            raise ValueError(f"{phone}: profile_source app_default states no setting; remove {', '.join(extra)} "
                             "(a pinned value is an explicit profile; use user_settings for a stored choice)")
        if not is_user_launch(phone_tunables(case, phone)):
            raise ValueError(f"{phone}: profile_source app_default needs the tunable user_launch: a benchmark launch "
                             "reads only its extras, so nothing would resolve the profile as a person's launch does")
        for field in ("raw_fixture_files", "reference_audios", "media_impairments"):
            if phone in (case.get(field) or {}):
                raise ValueError(f"{phone}: profile_source app_default cannot carry {field}: recorded or impaired input "
                                 "declares the geometry and rate the encoder must run at")
    video_settings(case)  # a malformed stated video choice is refused with the rest


def is_user_launch(tunables):
    return (tunables or {}).get("user_launch") is True


def phone_tunables(case, phone):
    merged = dict(case.get("tunables") or {})
    per_phone = case.get("phone_tunables")
    if isinstance(per_phone, dict) and isinstance(per_phone.get(phone), dict):
        merged.update(per_phone[phone])
    return merged


def user_launch_phones(case, phones):
    return [phone for phone in phones if is_user_launch(phone_tunables(case, phone))]


PER_PHONE = "per_phone"


def split_user_settings(case):
    """(shared, {alias: settings}) of a case's `user_settings`; ValueError for a malformed one.

    `user_settings` is either one map that applies to every user-launch phone of the case, or the same map plus the key
    `per_phone`: {alias: map} whose values override the shared ones for that phone alone (validated, written, read back
    and restored per phone). A case without `per_phone` is read exactly as before."""
    raw = case.get("user_settings")
    if raw is None:
        return {}, {}
    if not isinstance(raw, dict) or not raw:
        raise ValueError("user_settings must be an object of setting names to true/false")
    shared = {name: value for name, value in raw.items() if name != PER_PHONE}
    per_phone = raw.get(PER_PHONE, {})
    if not isinstance(per_phone, dict) or (PER_PHONE in raw and not per_phone):
        raise ValueError("user_settings.per_phone must be an object of phone aliases to setting maps")
    for alias, item in per_phone.items():
        if not isinstance(item, dict) or not item:
            raise ValueError(f"user_settings.per_phone.{alias} must be a nonempty object of setting names to values")
    return shared, per_phone


def phone_user_settings(case, phone):
    """The raw `user_settings` map one phone gets: the shared values with that phone's own values over them."""
    shared, per_phone = split_user_settings(case)
    return {**shared, **per_phone.get(phone, {})}


def validate_per_phone(case, phones):
    """ValueError when `user_settings.per_phone` names a phone that is not in the call (endpoints and helpers)."""
    unknown = sorted(set(split_user_settings(case)[1]) - set(phones))
    if unknown:
        raise ValueError(f"user_settings.per_phone names {', '.join(unknown)}, which is not a phone of this call "
                         f"({', '.join(sorted(phones))})")


def _resolve_map(case, raw):
    settings = {}

    def put(name, value):
        if name in settings and settings[name] is not value:
            raise ValueError(f"User settings contradict each other on {name!r}")
        settings[name] = value

    for name, value in raw.items():
        if name in VIDEO_SETTINGS:
            continue  # a stored video choice, resolved by video_settings
        if name != "wifi" and name not in SETTINGS:
            raise ValueError(f"Unknown user setting {name!r}; known: wifi, {', '.join(SETTINGS)}, "
                             f"{', '.join(VIDEO_SETTINGS)}")
        if not isinstance(value, bool):
            raise ValueError(f"User setting {name!r} must be true or false")
        if name == "wifi":
            carrier = case.get("carrier")
            if carrier is not None and carrier not in WIFI_SUB_SWITCH:
                raise ValueError(f"User setting 'wifi' does not know the carrier {carrier!r}")
            put("wifi_carrier", value)
            if WIFI_SUB_SWITCH.get(carrier):
                put(WIFI_SUB_SWITCH[carrier], value)
        else:
            put(name, value)
    for name, value in settings.items():
        if value and name in NEEDS_CARRIER and settings.get("wifi_carrier") is not True:
            raise ValueError(f"User setting {name!r} counts only while wifi_carrier is on")
    return settings


def resolve(case, phone=None):
    """{setting name: bool} the case asks for, `wifi` expanded; ValueError for anything the harness cannot write.

    With `phone`, the settings that phone gets (shared values with its own `per_phone` values over them). Without, the
    shared settings; every per-phone map is validated with the shared values under it.

    `user_settings` belongs to a user launch: a benchmark launch reads its extras only, so a stored choice there would
    be a setting that never takes effect."""
    shared, per_phone = split_user_settings(case)
    if case.get("user_settings") is None:
        return {}
    launch = is_user_launch(case.get("tunables"))
    tunables = case.get("phone_tunables")
    if not launch and not (isinstance(tunables, dict) and any(is_user_launch(item) for item in tunables.values()
                                                              if isinstance(item, dict))):
        raise ValueError("user_settings needs the tunable user_launch: a benchmark launch ignores stored choices")
    resolved = _resolve_map(case, shared) if shared or not per_phone else {}
    own = {alias: _resolve_map(case, {**shared, **item}) for alias, item in per_phone.items()}
    return own[phone] if phone in own else resolved


def _typed_video(raw):
    chosen = {}
    for name, value in raw.items():
        if name not in VIDEO_SETTINGS:
            continue
        _, kind, _, _ = VIDEO_SETTINGS[name]
        if kind is bool and not isinstance(value, bool):
            raise ValueError(f"User setting {name!r} must be true or false")
        if kind is int and (isinstance(value, bool) or not isinstance(value, int) or value <= 0):
            raise ValueError(f"User setting {name!r} must be a positive whole number")
        if kind is str and (not isinstance(value, str) or not value.strip()):
            raise ValueError(f"User setting {name!r} must be a nonempty name")
        chosen[name] = value
    return chosen


def video_settings(case, phone=None):
    """{name: value} of the stored video choices a case states in `user_settings` (a person's Settings choice).

    With `phone`, that phone's choices (shared values with its own `per_phone` values over them). Only an app_default
    endpoint reads them: an explicit profile sends its own extras, which beat a stored value."""
    shared, per_phone = split_user_settings(case)
    chosen = _typed_video(shared)
    if chosen and not app_default_phones(case):
        raise ValueError("A stored video choice in user_settings applies to a profile_source app_default endpoint: "
                         "an explicit profile sends its own extras")
    for alias, item in per_phone.items():
        if _typed_video(item) and alias not in app_default_phones(case):
            raise ValueError(f"A stored video choice for {alias} in user_settings.per_phone applies to a "
                             "profile_source app_default endpoint: an explicit profile sends its own extras")
    return _typed_video(phone_user_settings(case, phone)) if phone is not None else chosen


EQUIVALENTS_PATH = Path(__file__).with_name("video_default_equivalents.json")


def default_equivalent(phone, path=None):
    """The camera-mapped size a phone stores for the shipped default size, or None (then the check stays strict).

    tools/video_default_equivalents.json: the app's mode pickers replace the stored size with the camera's nearest mode
    (`BENCHMARK mode_mapping: requested=426x240 effective=WxH`) and the next settings save persists it, so a person's
    phone that has opened the app stores that size. Each entry cites the app's own line, run and log."""
    try:
        table = json.loads(Path(path or EQUIVALENTS_PATH).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    entry = (table.get("phones") or {}).get(phone)
    if not isinstance(entry, dict) or not all(isinstance(entry.get(key), int) for key in ("video_width", "video_height")):
        return None
    return {"video_width": entry["video_width"], "video_height": entry["video_height"], "source": entry.get("source")}


def equivalents_sha256(path=None):
    """SHA-256 of the equivalents table's content (a release-gate selection binds it), or None when there is none.

    The parsed JSON is hashed in a canonical form, so a checkout's line endings cannot change a selection id."""
    try:
        table = json.loads(Path(path or EQUIVALENTS_PATH).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return hashlib.sha256(json.dumps(table, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def video_state(stated, stored, equivalent=None):
    """How the stored video keys compare with the shipped defaults and with what the case states.

    `stated` is `video_settings(case, phone)`; `stored` is `read_values` of the preferences file (an absent key means
    the shipped default); `equivalent` is `default_equivalent(phone)`. Returns
    {"stated": {name: value}, "stored": {name: value or None}, "shipped_default": {name: value}, "differs": [...],
     "default_equivalent": bool, "default_equivalent_size": {...} (when the table has one for the phone),
     "default_equivalent_source": {...}}
    where `differs` lists `name: stored X, shipped default Y` for every ACTIVE key whose stored value is neither the
    stated value nor, when nothing is stated, the shipped default (the runner blocks on it before the dial).

    The stored size is the shipped default when it is the shipped 426x240 or, when the case states neither dimension,
    the phone's camera-mapped equivalent of it as a pair (`default_equivalent` is then true): the app's own mode picker
    stores that size for any person who opened the app. Any other size, or one half of the pair, still differs."""
    shown, defaults, differs = {}, {}, []
    advanced = stated.get("advanced_settings", stored.get("advanced_settings", False))
    for name, (key, kind, default, scale) in VIDEO_SETTINGS.items():
        value = stored.get(key)
        shown[name] = None if value is None else (value // scale if kind is int else value)
        defaults[name] = default
    size = ("video_width", "video_height")
    mapped = tuple((equivalent or {}).get(name) for name in size) if equivalent else None
    actual_size = tuple(defaults[name] if shown[name] is None else shown[name] for name in size)
    used = bool(mapped) and not any(name in stated for name in size) and actual_size == mapped and actual_size != tuple(
        defaults[name] for name in size)
    for name, (key, kind, default, scale) in VIDEO_SETTINGS.items():
        wanted = stated.get(name, default)
        if name in VIDEO_NEEDS_ADVANCED and advanced is not True:
            continue
        if used and name in size:
            continue
        actual = default if shown[name] is None else shown[name]
        if (str(actual).upper() if kind is str else actual) != (str(wanted).upper() if kind is str else wanted):
            differs.append(f"{name}: stored {actual!r}, " + (f"stated {wanted!r}" if name in stated else f"shipped default {default!r}"))
    state = {"stated": stated, "stored": shown, "shipped_default": defaults, "differs": differs,
             "default_equivalent": used}
    if equivalent:
        state["default_equivalent_size"] = {name: equivalent[name] for name in size}
        state["default_equivalent_source"] = equivalent.get("source")
    return state


def video_writes(stated):
    """{stored key: value} the Settings screen would write for the stated video choices."""
    return {VIDEO_SETTINGS[name][0]: value * VIDEO_SETTINGS[name][3] if VIDEO_SETTINGS[name][1] is int else value
            for name, value in stated.items()}


def blockers(case, route):
    """[(reason, detail)] that make this user-launch case impossible, found without touching a phone.

    `route` is every phone of the call that runs the app (endpoints and helpers). Static relay chains scan for the
    callee's fixed prefix, which a rotating callee never advertises, so only the on-demand shape can run."""
    found = []
    phones = user_launch_phones(case, route)
    if not phones:
        return found
    each = {phone: resolve(case, phone) for phone in phones}  # per phone: user_settings.per_phone overrides the shared map
    settings = {}
    for name in ("relayed_calls", "wifi_carrier", *WIFI_SUB_SWITCH.values()):
        if name:
            settings[name] = all(item.get(name) is True for item in each.values()) or None
    for name in USER_LAUNCH_DEFAULTS_ON:
        off = [phone for phone in phones if phone_tunables(case, phone).get(name) is False]
        if off:
            found.append(("user_launch_default_overridden",
                          f"tunable {name}=false on {', '.join(off)}: a user launch ignores it (the shipped default is on); "
                          "run the benchmark launch for that control arm"))
    relayed_route = len(route) > 2 or any(phone_tunables(case, phone).get("relayed_calls") is True for phone in phones)
    if relayed_route and settings.get("relayed_calls") is not True:
        found.append(("user_setting_missing",
                      "relayed calls are off in a person's launch until the person turns on Settings > Advanced "
                      "'Experimental: relayed calls'; the case must say user_settings.relayed_calls: true"))
    if case.get("carrier") is not None and settings.get("wifi_carrier") is not True:
        found.append(("user_setting_missing",
                      "Wi-Fi is off in a person's launch until the person turns on 'Use Wi-Fi for faster connections'; "
                      "the case must say user_settings.wifi: true"))
    sub = WIFI_SUB_SWITCH.get(case.get("carrier"))
    if sub and settings.get(sub) is not True:
        found.append(("user_setting_missing", f"carrier {case['carrier']} needs the stored choice {sub} on; "
                                              "say user_settings.wifi: true"))
    if len(route) > 2 and case.get("relay_shape", "static") != "on_demand":
        found.append(("user_launch_unsupported_route",
                      "a static relay chain finds the callee by its fixed node prefix, which a user launch (rotating "
                      "IDs on) never advertises; use relay_shape on_demand"))
    return found


def with_live_disclosure(settings, goes_live):
    """The settings a user-launch phone gets, with the go-live notice confirmed when it goes live and the case does not
    state the choice: a person who goes live has accepted the one-time notice (the Realme in R1 2026-11-10 waited on it
    with no tap). A stated `live_disclosed: false` is kept, so the first-run notice itself stays testable."""
    if goes_live and "live_disclosed" not in settings:
        return {**settings, "live_disclosed": True}
    return settings


def pref_writes(settings):
    """{stored key: bool} the Settings switches write for these choices, in a stable order."""
    writes = {}
    for name in SETTINGS:
        if name in settings:
            writes.update(SETTINGS[name][0](settings[name]))
    return writes


def read_bools(text):
    """{name: bool} of the boolean elements of a SharedPreferences XML text (empty for none or an empty file)."""
    if not text or not text.strip():
        return {}
    return {element.get("name"): element.get("value") == "true"
            for element in ET.fromstring(text) if element.tag == "boolean"}


def read_values(text):
    """{name: value} of the boolean, whole-number and string elements of a SharedPreferences XML text."""
    if not text or not text.strip():
        return {}
    values = {}
    for element in ET.fromstring(text):
        if element.tag == "boolean":
            values[element.get("name")] = element.get("value") == "true"
        elif element.tag in ("int", "long"):
            values[element.get("name")] = int(element.get("value"))
        elif element.tag == "string":
            values[element.get("name")] = element.text or ""
    return values


def merge_prefs(text, writes):
    """The preferences XML with `writes` set (booleans, whole numbers, strings) and everything else untouched."""
    root = ET.fromstring(text) if text and text.strip() else ET.Element("map")
    for key, value in writes.items():
        tag = "boolean" if isinstance(value, bool) else "int" if isinstance(value, int) else "string"
        element = next((item for item in root if item.get("name") == key), None)
        if element is None:
            element = ET.SubElement(root, tag, name=key)
        elif element.tag != tag:
            wanted = "a boolean the Settings switch writes" if tag == "boolean" else f"the <{tag}> the Settings screen writes"
            raise ValueError(f"Stored value {key!r} is a <{element.tag}>, not {wanted}")
        if tag == "string":
            element.text = value
        else:
            element.set("value", ("true" if value else "false") if tag == "boolean" else str(value))
    if hasattr(ET, "indent"):
        ET.indent(root, space="    ")
    return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + ET.tostring(root, encoding="unicode") + "\n"


def write_commands(package, text):
    """The `adb shell` command strings that replace the preferences file as the app's user (run-as), in order.

    The file is built beside the original and moved over it, so the app never reads a half-written file; a leftover
    backup file would be restored over it by the app's loader, so it is removed with the move."""
    directory, name = PACKAGE_PREFS_FILE.rsplit("/", 1)
    encoded = base64.b64encode(text if isinstance(text, bytes) else text.encode("utf-8")).decode("ascii")
    pieces = [encoded[start:start + CHUNK_CHARS] for start in range(0, len(encoded), CHUNK_CHARS)] or [""]
    temp = f"{directory}/{name}.ul1"
    commands = [f"run-as {package} sh -c 'mkdir -p {directory}; : > {temp}'"]
    commands += [f"run-as {package} sh -c 'echo {piece} | base64 -d >> {temp}'" for piece in pieces]
    commands.append(f"run-as {package} sh -c 'mv -f {temp} {PACKAGE_PREFS_FILE}; rm -f {PACKAGE_PREFS_FILE}.bak'")
    return commands


def read_command(package):
    return f"run-as {package} cat {PACKAGE_PREFS_FILE}"


def present_command(package):
    """Prints `present` or `absent`: whether the preferences file exists (a missing file reads as empty text, so the
    snapshot needs its own answer)."""
    return f"run-as {package} sh -c 'test -e {PACKAGE_PREFS_FILE} && echo present || echo absent'"


def hash_command(package):
    """The device's own SHA-256 of the file: proof that a snapshot or a restore holds the exact bytes."""
    return f"run-as {package} sha256sum {PACKAGE_PREFS_FILE}"


def restore_commands(package, data):
    """The commands that give the file back exactly as it was: the snapshot's bytes, or no file when there was none."""
    if data is None:
        return [f"run-as {package} sh -c 'rm -f {PACKAGE_PREFS_FILE} {PACKAGE_PREFS_FILE}.bak {PACKAGE_PREFS_FILE}.ul1'"]
    return write_commands(package, data)


def _field(text, prefix, field):
    for line in reversed(text.splitlines()):
        if prefix in line:
            match = re.search(rf"(?:^|[ |]){re.escape(field)}=([^ |]+)", line.split(prefix, 1)[1])
            if match:
                return match[1]
    return None


def assess(settings, log_text):
    """One row per setting: requested, what the app logged, applied (True/False/None) and why.

    A missing line is unknown (None), never a pass and never a block: the stored value was already read back from the
    file; the line is the app's own account of what it resolved from it."""
    rows = []
    for name, wanted in settings.items():
        _, prefix, field, on_word = SETTINGS[name]
        if prefix is None:
            rows.append({"setting": name, "requested": wanted, "line": None, "applied": None,
                         "applied_basis": "the app logs no line for this choice; the stored value was read back"})
            continue
        logged = _field(log_text, prefix, field)
        row = {"setting": name, "requested": wanted, "line": f"{prefix} {field}=", "applied": None}
        if logged is None:
            row["applied_basis"] = "the app's line was not logged"
        else:
            word = logged.lower()
            state = True if word in ("true", "on") else False if word in ("false", "off") else None
            row.update(logged=logged, applied=None if state is None else state is wanted,
                       applied_basis=f"{prefix} {field}={logged}")
        rows.append(row)
    return rows


def blocking(rows):
    """(reason, detail) when the app resolved a setting to another value than the person's stored choice."""
    wrong = [f"{row['setting']} stored {str(row['requested']).lower()}, app logged {row['logged']}"
             for row in rows if row["applied"] is False]
    return ("user_setting_not_applied", "; ".join(wrong)) if wrong else None


BATTERY_SAVER_DUMP = re.compile(r"Battery Saver is currently:\s*(ON|OFF)\b", re.IGNORECASE)
# The live state: `dumpsys power` "Battery saver state machine:" then "Enabled=true|false" (BatterySaverStateMachine).
# The "Battery saving stats" line above can be stale: an ASUS (Android 11) printed "currently: ON" with the state machine
# at Enabled=false, Battery Saver off in Settings and the app logging powerSave=false (2026-11-11, sw1a BLOCKED x12).
BATTERY_SAVER_STATE = re.compile(r"Battery saver state machine:\s*\n\s*Enabled=(true|false)\b", re.IGNORECASE)
POWER_SAVE_LOG = re.compile(r"BENCHMARK doze_state: .*\bpowerSave=true\b")


def battery_saver(power_dump):
    """True or False from `dumpsys power`: the state machine's live `Enabled=` when present, else the statistics line
    ("Battery Saver is currently: ON|OFF"); None when the text has neither.

    Android's Battery Saver is the owner's setting; the harness only reads it. While it is on, the app withholds Wi-Fi
    for the whole call (WifiEligibility.batteryOrThermalBlocks), so a Wi-Fi request would silently run on Bluetooth."""
    state = BATTERY_SAVER_STATE.search(power_dump or "")
    if state is not None:
        return state[1].lower() == "true"
    match = BATTERY_SAVER_DUMP.search(power_dump or "")
    return None if match is None else match[1].upper() == "ON"


def wifi_requested(case, phone=None):
    """Whether the case asks for the Wi-Fi carrier or the Wi-Fi upgrade: a `carrier`, or the stored Wi-Fi choice on
    (`user_settings.wifi: true`, shared or for `phone`)."""
    if case.get("carrier"):
        return True
    try:
        return resolve(case, phone).get("wifi_carrier") is True
    except ValueError:
        return False


def power_save_in_log(text):
    """True when the app's own log shows `powerSave=true` (its `BENCHMARK doze_state` line) at any point."""
    return bool(POWER_SAVE_LOG.search(text or ""))
