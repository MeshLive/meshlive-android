"""Did an experiment arm reach the app, and did it take effect? (TEST-14, FLOW-06)

The runner sends launch extras (`--ez test_x true`). An extra the build does not know is ignored without a word,
so an A/B whose arm never took effect looked like a valid comparison. Three independent facts, each recorded:

* in the build: the extra's name is a string literal in the frozen APK's dex (offline, `apk_test_extras`);
* delivered: the app's `BENCHMARK launch_extras:` echo lists the extra with the value the runner sent;
* applied: a line the app logs about the setting shows the requested value (`EFFECT_LINES`; only the settings
  whose line is verified in the source are listed - every other extra reports `applied: unknown`, never true).

A user launch (`user_launch: true`) ignores those extras for the opt-in features and reads the person's stored choices
instead (tools/user_launch.py). `assess_settings` checks each stored choice against the app's own line about it
(`BENCHMARK relayed_calls: enabled=`, `BENCHMARK wifi_switches: carrier=`); a line that shows another value blocks the
case before the dial with reason `user_setting_not_applied`.

A missing echo is unknown (an older build, or logd dropped it); an echo that lacks a sent extra, or an effect
line that shows another value, blocks the case before the dial.
"""
import re
import zipfile

import user_launch

ECHO = re.compile(r"BENCHMARK launch_extras: count=(\d+)(?: \| (.*))?$")
MAX_VALUE_CHARS = 64  # LaunchExtrasEcho.MAX_VALUE_CHARS
EXTRA_TOKEN = re.compile(rb"test_[a-z0-9_]+")

# extra -> (line prefix the app logs, field name in that line). Verified in MainActivity.kt (DEBUG launch lines).
EFFECT_LINES = {
    "test_call_video_fragments": ("BENCHMARK call_policy:", "videoFragments"),
    "test_call_fragment_airtime_ms": ("BENCHMARK call_policy:", "fragmentAirtimeMs"),
    "test_call_speech_led_playout": ("BENCHMARK call_policy:", "speechLedPlayout"),
    "test_call_audio_headroom": ("BENCHMARK call_policy:", "audioHeadroom"),
    "test_call_codec_fallback": ("BENCHMARK call_policy:", "codecFallback"),
    "test_call_legacy_low_delay": ("BENCHMARK call_policy:", "legacyLowDelay"),
    "test_call_legacy_relief": ("BENCHMARK call_policy:", "legacyRelief"),
    "test_call_first_frame_now": ("BENCHMARK call_policy:", "firstFrameNow"),
    "test_call_encoder_fit": ("BENCHMARK call_policy:", "encoderFit"),
    "test_call_relay_chain": ("BENCHMARK call_policy:", "relayChain"),
    "test_relayed_call_measured_cap": ("BENCHMARK call_policy:", "relayedMeasuredCap"),
    "test_call_close_wait_peer": ("BENCHMARK call_policy:", "closeWaitsForPeer"),
    "test_encoder_ranking": ("BENCHMARK burst_experiments:", "encoder_ranking"),
    "test_burst_drain": ("BENCHMARK burst_experiments:", "burst_drain"),
    "test_vp9_q_floor": ("BENCHMARK burst_experiments:", "vp9_q_floor"),
    "test_intra_refresh_frames": ("BENCHMARK burst_experiments:", "intra_refresh_frames"),
    # Verified in saved B2 logs (ul2q27gatt-20261110j): a user launch ignored test_relayed_calls=true and logged enabled=false.
    "test_relayed_calls": ("BENCHMARK relayed_calls:", "enabled"),
    "test_gatt_first_write_counts_control": ("BENCHMARK le_link_policy:", "gattFirstWriteCountsControl"),
    # B3 and later (feat/b3-small): always printed; an older build that omits the field reads as unknown.
    "test_call_first_frame_ramp": ("BENCHMARK call_policy:", "firstFrameRamp"),
    "test_hop_integrity_resend_diff": ("BENCHMARK le_link_policy:", "hopIntegrityResendDiff"),
}
# Sent by the runner for every launch for its own reasons; not an experiment arm.
NOT_AN_ARM = {"test_playback_gain"}


class ArmBlocked(RuntimeError):
    """A requested arm did not reach the app or took another value: the case is BLOCKED, not run."""

    def __init__(self, phone, reason, detail):
        super().__init__(f"{phone}: {reason}: {detail}")
        self.record = {"device": phone, "reason": reason, "detail": detail}


def parse_sent_extras(args):
    """{extra: value text} of the `--e*` triples in an `am start` argument list, as the app's bundle will hold them."""
    sent = {}
    for index, word in enumerate(args):
        if word in ("--ez", "--ei", "--ef", "--es", "--el") and index + 2 < len(args):
            sent[str(args[index + 1])] = str(args[index + 2])
    return sent


def render(value):
    """LaunchExtrasEcho.render: the app cuts at 64 characters and swaps the two separator characters."""
    text = str(value).replace(",", ";").replace("|", "/")
    return text[:MAX_VALUE_CHARS] + "..." if len(text) > MAX_VALUE_CHARS else text


def parse_launch_echo(text):
    """{extra: rendered value} of the last `launch_extras` line in a log, or None when the log has none."""
    found = None
    for line in text.splitlines():
        match = ECHO.search(line.rstrip())
        if match:
            body = match[2] or ""
            found = dict(item.split("=", 1) for item in body.split(",") if "=" in item)
    return found


def same_value(requested, logged):
    return str(requested).strip().lower() == str(logged).strip().lower()


def effect_value(text, extra):
    """The value the app logged for `extra`, or None when its line (or field) is absent."""
    prefix, field = EFFECT_LINES[extra]
    for line in reversed(text.splitlines()):
        if prefix in line:
            match = re.search(rf"(?:^|[ |]){re.escape(field)}=([^ |]+)", line.split(prefix, 1)[1])
            if match:
                return match[1]
    return None


def assess(sent, log_text, drops_reported=False):
    """One row per requested arm: extra, requested, delivered (True/False/None), applied (True/False/None) and why."""
    echo = parse_launch_echo(log_text)
    rows = []
    for extra, value in sorted(sent.items()):
        if extra in NOT_AN_ARM or not extra.startswith("test_"):
            continue
        row = {"extra": extra, "requested": value, "delivered": None, "applied": None}
        if echo is None:
            row["delivered_basis"] = ("launch_extras echo absent and logd dropped lines: not provable" if drops_reported
                                      else "launch_extras echo absent (a build without the echo)")
        elif extra not in echo:
            row.update(delivered=False, delivered_basis="the launch_extras echo does not list this extra")
        elif not same_value(render(value), echo[extra]):
            # adb cannot change a value in transit, so a differing rendering (float formatting) is recorded, not blocked.
            row.update(delivered_basis=f"the echo lists {extra}={echo[extra]}, the runner sent {render(value)}")
        else:
            row.update(delivered=True, delivered_basis="listed in the launch_extras echo with the requested value")
        if extra in EFFECT_LINES:
            logged = effect_value(log_text, extra)
            if logged is None:
                row["applied_basis"] = "the app's effect line was not logged"
            else:
                row.update(applied=same_value(value, logged), logged=logged,
                           applied_basis=f"{EFFECT_LINES[extra][0]} {EFFECT_LINES[extra][1]}={logged}")
        else:
            row["applied_basis"] = "no verified effect line for this extra: delivered, not proven applied"
        rows.append(row)
    return rows


def assess_settings(settings, log_text):
    """One row per stored user-launch choice (`user_launch.assess`): requested, logged, applied (True/False/None)."""
    return user_launch.assess(settings, log_text)


def blocking(rows):
    """(reason, detail) when an arm or a stored choice demonstrably did not take effect, else None. Unknown never blocks."""
    missing = [row["extra"] for row in rows if "extra" in row and row.get("delivered") is False]
    if missing:
        return "arm_not_delivered", "the app's launch_extras echo does not list " + ", ".join(missing)
    wrong = [f"{row['extra']} requested {row['requested']}, app logged {row['logged']}" for row in rows
             if "extra" in row and row["applied"] is False]
    stored = user_launch.blocking([row for row in rows if "setting" in row])
    if stored:
        # In a user launch the stored choice is the cause and the ignored extra only its echo: name both.
        return stored[0], "; ".join([stored[1], *wrong])
    if wrong:
        return "arm_not_applied", "; ".join(wrong)
    return None


def apk_test_extras(apk_path):
    """Every `test_...` token in the dex string pools of an APK: the extras this build can possibly read."""
    found = set()
    with zipfile.ZipFile(apk_path) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                found.update(token.decode("ascii") for token in EXTRA_TOKEN.findall(archive.read(name)))
    return found


def missing_from_build(extras, in_build):
    return sorted(extra for extra in extras if extra not in NOT_AN_ARM and extra not in in_build)


def scan_apk(apk_path):
    """apk_test_extras, or an empty set when the file is not a readable APK (the caller then reports UNMEASURED)."""
    try:
        return apk_test_extras(apk_path)
    except (zipfile.BadZipFile, OSError):
        return set()
