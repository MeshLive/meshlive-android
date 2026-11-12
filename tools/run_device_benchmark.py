"""Repeatable physical-phone scenarios; no optical-quality claims from log counters."""
import argparse
import concurrent.futures
import ctypes
import datetime as dt
import hashlib
import os
import json
import math
import re
import struct
import subprocess
import sys
import time
import uuid
import wave
from pathlib import Path

from benchmark_layout import output_path, resolve
import app_crash_events as crash_events
import applied_arms
import switch_inert
import user_launch
from stage_trace_lines import stage_records
from device_log_capture import LogCapture, line_time, merge_dump

PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
COMPONENT = f"{PACKAGE}/com.example.bluetoothtest.MainActivity"
# DEBUG clean shutdown (app CleanShutdown.ACTION): a broadcast reaches only a running app.
CLEAN_SHUTDOWN_ACTION = "com.example.bluetoothtest.debug.CLEAN_SHUTDOWN"


def clean_shutdown_broadcast_args(nonce):
    """adb arguments of the clean-shutdown broadcast (drivers: adb -s SERIAL <these>)."""
    return ("shell", "am", "broadcast", "-a", CLEAN_SHUTDOWN_ACTION, "-p", PACKAGE, "--es", "nonce", nonce)


def clean_shutdown_done(logcat_text, nonce):
    """True when the app logged its clean-shutdown done line for this nonce."""
    return f"clean_shutdown: done | nonce={nonce} |" in (logcat_text or "")


# adb.exe failing before it runs (Windows loader failure under load, 0xC0000142 STATUS_DLL_INIT_FAILED) and a
# transport that is briefly offline are transient. Delays between the bounded retries, in seconds.
ADB_RETRY_DELAYS_S = (0.5, 1.5, 3.0)
ADB_SPAWN_FAILURE_CODES = {0xC0000142, 0xC0000142 - (1 << 32)}  # unsigned and signed views of the same status
ADB_TRANSIENT_OFFLINE = re.compile(r"device offline|device still authorizing", re.I)
# Safe to run twice: reads, overwrites with identical content, and stops/clean-ups that end in the same state.
ADB_IDEMPOTENT = (("get-state",), ("push",), ("pull",), ("logcat", "-d"), ("logcat", "-g"), ("logcat", "-G"),
                  ("shell", "pidof"), ("shell", "sha256sum"), ("shell", "ls"), ("shell", "getprop"),
                  ("shell", "dumpsys"), ("shell", "pm", "path"), ("shell", "am", "force-stop"),
                  ("shell", "wm", "dismiss-keyguard"), ("shell", "rm", "-f"),
                  ("shell", "input", "keyevent", "KEYCODE_WAKEUP"))


def adb_is_idempotent(args):
    parts = tuple(str(item) for item in args)
    if len(parts) == 2 and parts[0] == "shell" and parts[1].startswith("date "):
        return True
    return any(parts[:len(prefix)] == prefix for prefix in ADB_IDEMPOTENT)


def adb_retry_delay(returncode, output, args, attempt):
    """Seconds to wait before retrying a failed adb invocation, or None when it must not be retried.

    A spawn failure means adb never started, so any command is safe to repeat. A transport that is
    offline is retried only for idempotent commands (reads, force-stop, identical overwrites): a
    command already delivered must not run twice. A timeout is never retried."""
    if attempt >= len(ADB_RETRY_DELAYS_S) or not returncode:
        return None
    if returncode in ADB_SPAWN_FAILURE_CODES:
        return ADB_RETRY_DELAYS_S[attempt]
    if ADB_TRANSIENT_OFFLINE.search(output or "") and adb_is_idempotent(args):
        return ADB_RETRY_DELAYS_S[attempt]
    return None


def replay_fixture_metadata(case):
    if "replay_sha256" in case and (not case.get("replay_file") or not isinstance(
            case["replay_sha256"], str) or not re.fullmatch(r"[0-9a-fA-F]{64}", case["replay_sha256"])):
        raise ValueError("Replay SHA256 needs a matching input file and a64-digit digest")
    if not case.get("replay_file"):
        return None
    path = resolve(case["replay_file"])
    checksum = hashlib.sha256()
    with path.open("rb") as fixture:
        header = fixture.read(32)
        checksum.update(header)
        if len(header) != 32:
            raise ValueError("Replay needs a complete IVF header")
        magic, version, header_size, codec, width, height, rate, scale, declared, _ = struct.unpack("<4sHH4sHHIIII", header)
        if magic != b"DKIF" or version != 0 or header_size < 32 or codec not in (b"VP90", b"AV01"):
            raise ValueError("Replay needs a supported native IVF header")
        extra = fixture.read(header_size - 32)
        checksum.update(extra)
        if len(extra) != header_size - 32 or not rate or not scale or not declared or rate % scale:
            raise ValueError("Replay must declare complete frames and an integer native FPS/timebase")
        profile = case["profile"]
        if (width, height, rate // scale) != (profile["width"], profile["height"], profile["fps"]):
            raise ValueError("Replay native geometry/FPS differs from the requested profile; no conversion")
        frames, first, last = 0, None, None
        while frame_header := fixture.read(12):
            checksum.update(frame_header)
            if len(frame_header) != 12:
                raise ValueError("Replay contains a truncated IVF frame header")
            size, timestamp = struct.unpack("<IQ", frame_header)
            if not 0 < size <= 4 * 1024 * 1024:
                raise ValueError("Replay IVF frame exceeds the native payload bounds")
            payload = fixture.read(size)
            checksum.update(payload)
            if len(payload) != size:
                raise ValueError("Replay contains a truncated IVF frame payload")
            if timestamp >= 2 ** 63 or last is not None and timestamp < last:
                raise ValueError("Replay IVF timestamps must be nonnegative and ordered")
            if timestamp * 1_000_000 // (rate // scale) > (2 ** 63 - 1) // 1000 or rate // scale > 2 ** 31 - 1:
                raise ValueError("Replay IVF format/timestamp exceeds native integer/nanosecond timing")
            first = timestamp if first is None else first
            last = timestamp
            frames += 1
        size = fixture.tell()
    digest = checksum.hexdigest()
    if case.get("replay_sha256") and digest != case["replay_sha256"].lower():
        raise ValueError("Replay input bytes differ from the frozen case SHA256")
    if frames != declared:
        raise ValueError("Replay declared IVF frame count differs from its complete payloads")
    required_s = case.get("duration_s", 60) + len(case["receivers"]) * case.get("connection_timeout_s", 30)
    if frames * scale / rate < required_s or last * scale / rate < required_s:
        raise ValueError("Replay fixture is shorter than measurement plus connection allowance; app replay does not loop")
    return {"path": str(path), "sha256": digest, "format": "IVF", "codec": codec.decode("ascii"),
            "width": width, "height": height, "fps": rate // scale, "rate": rate, "scale": scale,
            "frames": frames, "bytes": size, "first_timestamp": first, "last_timestamp": last, "loops": False,
            "scope": "Finite preencoded video; transport/decoder isolation; live encoding, camera and audio unmeasured"}


def raw_fixture_metadata(case):
    if not case.get("raw_fixture_file"):
        return None
    fixture = resolve(case["raw_fixture_file"])
    with fixture.open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    if case.get("raw_fixture_sha256") and digest != case["raw_fixture_sha256"].lower():
        raise ValueError("Raw input bytes differ from the frozen case SHA256")
    width, height = case["profile"]["width"], case["profile"]["height"]
    size = fixture.stat().st_size
    return {"path": str(fixture), "sha256": digest, "format": "I420", "width": width, "height": height,
            "bytes": size, "frames": size // (width * height * 3 // 2), "loops": True,
            "declared_metadata": case.get("raw_fixture_metadata"),
            "scope": "Selected encoder plus real media path; optical camera capture/controls unmeasured"}


def reference_audio_metadata(case):
    entry = case.get("reference_audio")
    if entry is None:
        return None
    if not isinstance(entry, dict) or set(entry) != {"path", "sha256"} or not isinstance(entry["path"], str) or not entry["path"]:
        raise ValueError("Reference audio requires exactly path and sha256")
    if not case.get("raw_fixture_file") or case.get("audio_enabled") is False or not case.get("media_trace"):
        raise ValueError("Reference audio requires matching raw video, enabled audio and media tracing")
    path = resolve(entry["path"])
    with path.open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    if not isinstance(entry["sha256"], str) or digest != entry["sha256"].lower():
        raise ValueError("Reference audio hash differs from requested bytes")
    with wave.open(str(path), "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 48000, "NONE"):
            raise ValueError("Reference audio must be PCM16 mono48000 Hz; no conversion")
        samples = wav.getnframes()
        # Verify the entire declared PCM, not just the WAV header.
        remaining = samples * 2
        while remaining:
            chunk = wav.readframes(min(48000, remaining // 2))
            if not chunk:
                raise ValueError("Reference audio WAV payload is truncated")
            remaining -= len(chunk)
    raw = raw_fixture_metadata(case)
    numerator = raw["frames"] * 48000
    fps = case["profile"]["fps"]
    if numerator % fps or samples != numerator // fps:
        raise ValueError("Reference audio must match the exact whole-sample raw-video loop")
    return {"path": str(path), "sha256": digest, "sample_count": samples, "sample_rate": 48000,
            "channels": 1, "format": "PCM16", "scope": "Reference injected before actual Opus encoding; acoustics UNMEASURED"}


def media_impairment_metadata(case):
    entry = case.get("media_impairment")
    if entry is None:
        return None
    if not isinstance(entry, dict) or set(entry) != {"path", "sha256"} or not case.get("media_trace"):
        raise ValueError("Media impairment requires hashed config and media tracing")
    path = resolve(entry["path"])
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if not isinstance(entry["sha256"], str) or digest != entry["sha256"].lower():
        raise ValueError("Media impairment config hash differs from requested bytes")
    from score_impairment_trace import validate_config
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    validate_config(config)
    return {"path": str(path), "sha256": digest, "config": config,
            "scope": "Outgoing whole media before encryption; bounded producer delay; not radio impairment"}


def validate_case(case, devices):
    required_hash = case.get("expected_apk_sha256")
    if required_hash is not None and (not isinstance(required_hash, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", required_hash)):
        raise ValueError("Expected APK SHA256 must be a64-digit digest")
    source = case["source"]
    parents = {item["phone"]: item["parent"] for item in case["receivers"]}
    if len(parents) != len(case["receivers"]) or source in parents:
        raise ValueError("A phone cannot have two roles or two active parents")
    participants = {source, *parents}
    if not participants <= devices.keys():
        raise ValueError("Scenario contains an unknown phone")
    if "phone_tunables" in case:
        per_phone = case["phone_tunables"]
        if not isinstance(per_phone, dict) or not all(isinstance(value, dict) for value in per_phone.values()):
            raise ValueError("Per-phone tunables must map a phone to an object")
        if not set(per_phone) <= participants:
            raise ValueError("Per-phone tunables must name phones of this case")
        for value in per_phone.values():
            tunable_extras(value)  # Unknown names or wrong types never silently test the default.
    serials, prefixes = [], []
    for phone in participants:
        device = devices[phone]
        if not device.get("serial") or not re.fullmatch(r"[0-9a-f]{12}", device.get("node_prefix", "")):
            raise ValueError("Every participant needs a serial and a 12-digit signed-node prefix")
        serials.append(device["serial"])
        prefixes.append(device["node_prefix"])
    if len(set(serials)) != len(serials) or len(set(prefixes)) != len(prefixes):
        raise ValueError("Aliases must not assign one physical phone to multiple roles")
    profile = case["profile"]
    app_default = user_launch.is_app_default(profile)
    if app_default:
        # A call endpoint that takes the app's own video choice (user_launch.validate_app_default checked it in
        # validate_call_case): no profile value is requested, so none is validated here.
        if not case.get("call_endpoint"):
            raise ValueError("profile_source app_default is a private-call endpoint choice, not a broadcast profile")
        for value in (case.get("duration_s", 60), case.get("connection_timeout_s", 30)):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                raise ValueError("Duration and timeout must be positive")
    else:
        for value in [case.get("duration_s", 60), case.get("connection_timeout_s", 30),
                      *(case.get("profile", {}).get(key) for key in ("width", "height", "fps", "bitrate_kbps"))]:
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                raise ValueError("Duration, timeout, dimensions, FPS, and bitrate must be positive")
        if any(not isinstance(profile[key], int) for key in ("width", "height", "fps", "bitrate_kbps")):
            raise ValueError("Android profile extras require integer dimensions, FPS, and bitrate")
        transport_maximum = {"classic": 1500, "gatt": 1100, "ble": 1200}.get(case["transport"])
        # A Wi-Fi carrier case with wifi_exact_kbps asks the app for exactly that rate, unclamped by the Bluetooth maximum
        # (SWITCHES "Exact call bitrate on Wi-Fi"); it must cover the requested bitrate, so nothing is clamped silently.
        exact = (case.get("tunables") or {}).get("wifi_exact_kbps")
        wifi_exact = bool(case.get("carrier")) and isinstance(exact, int) and not isinstance(exact, bool) and exact >= profile["bitrate_kbps"]
        if exact and profile.get("rate_control", "cbr") == "automatic":
            # MainActivity applies test_wifi_exact_kbps only with a manual allocation; under Automatic it is silently ignored.
            raise ValueError("wifi_exact_kbps needs rate_control cbr, vbr or cbr_fd (the app ignores it under automatic)")
        if transport_maximum and profile["bitrate_kbps"] > transport_maximum and not wifi_exact:
            raise ValueError("Requested bitrate exceeds the Settings limit; do not silently clamp tests")
        if profile.get("playback", "balanced") not in {"low", "balanced", "stable", "auto"}:
            raise ValueError("Unknown playback choice would silently become Balanced")
        if profile.get("rate_control", "cbr") not in {"automatic", "cbr", "vbr", "cbr_fd"}:
            raise ValueError("Unknown bitrate allocation would silently become Automatic")
    if "stream_flush" in case and case["stream_flush"] not in {"frame", "5ms", "10ms", "16k", "32k"}:
        raise ValueError("Unknown flush policy would silently become 5ms")
    if "audio_enabled" in case and not isinstance(case["audio_enabled"], bool):
        raise ValueError("Audio override must be a boolean")
    if "media_trace" in case and not isinstance(case["media_trace"], bool):
        raise ValueError("Media trace override must be a boolean")
    if "video_presentation_timing" in case and not isinstance(case["video_presentation_timing"], bool):
        raise ValueError("Video presentation timing override must be a boolean")
    if "release_video_at_playout" in case and (
            not isinstance(case["release_video_at_playout"], bool) or
            (case["release_video_at_playout"] and not case.get("video_presentation_timing"))):
        raise ValueError("Output release pacing requires an explicit boolean and presentation tracing")
    if "gatt_write_timing" in case and (
            not isinstance(case["gatt_write_timing"], bool) or case["transport"] != "gatt" or len(parents) != 1):
        raise ValueError("GATT write timing is a boolean diagnostic for one direct GATT receiver")
    if "direct_gatt_heartbeat" in case:
        if (not isinstance(case["direct_gatt_heartbeat"], bool) or case["transport"] != "gatt" or
                len(parents) != 1 or any(node.get("forwarding_enabled", False) for node in case["receivers"])):
            raise ValueError("Direct GATT heartbeat is a boolean diagnostic for one ordinary leaf only")
    if "viewer_scan_mode" in case:
        if case["viewer_scan_mode"] not in {"balanced", "low_latency", "off_when_full"}:
            raise ValueError("Unknown viewer scan diagnostic would silently use production behavior")
        if case["transport"] != "classic" and not (
                case["transport"] == "gatt" and case["viewer_scan_mode"] == "off_when_full"):
            raise ValueError("Viewer scan diagnostics require Classic, or explicit GATT off_when_full")
    for node in case["receivers"]:
        if "forwarding_enabled" in node:
            if not isinstance(node["forwarding_enabled"], bool) or case["transport"] != "gatt":
                raise ValueError("Forwarding override is a boolean GATT-only debug diagnostic")
            if not node["forwarding_enabled"] and node["phone"] in parents.values():
                raise ValueError("An actual middle must forward to its requested child")
        if "forwarder_gatt_priority" in node:
            if node["forwarder_gatt_priority"] not in {"high", "dck", "balanced"}:
                raise ValueError("Forwarder GATT priority diagnostic must be high or dck")
            if case["transport"] != "gatt" or node["phone"] not in parents.values():
                raise ValueError("Forwarder GATT priority applies only to a GATT middle with a child")
    for phone in parents:
        visited = set()
        current = phone
        while current != source:
            if current in visited or current not in parents:
                raise ValueError("Receiver graph must be an acyclic tree rooted at the source")
            visited.add(current)
            current = parents[current]
    for event in case.get("actions", []):
        if event["phone"] not in participants or not 0 <= event["at_s"] < case.get("duration_s", 60):
            raise ValueError("Action must target a participant within the measurement duration")
        if event["action"] not in {"kill", "restart_receiver", "background", "foreground", "pause", "resume", "flip", "end"}:
            raise ValueError("Unknown benchmark action")
        if event["action"] == "restart_receiver" and event["phone"] not in parents:
            raise ValueError("Only a receiver may be restarted in a recovery scenario")
        if event["action"] in {"pause", "resume", "flip", "end"} and event["phone"] != source:
            raise ValueError("Broadcast controls must target the source")
    if case["transport"] not in {"classic", "gatt", "ble"}:
        raise ValueError("Transport must be classic, gatt, or ble (CoC)")
    carrier = case.get("carrier")
    if carrier is not None:
        if carrier == "wifi_aware_native":
            raise ValueError("A Wi-Fi-native hop needs Bluetooth turned off on both phones, which this runner cannot do; "
                             "run it by hand (package A8 phone steps)")
        if carrier not in WIFI_CARRIER_ARMS:
            raise ValueError("Unknown carrier would silently run Bluetooth only")
        if WIFI_CARRIER_OWNED_TUNABLES & set(case.get("tunables", {})):
            raise ValueError("A carrier case sets the Wi-Fi switches itself; remove them from tunables")
        if case.get("tunables", {}).get("bmh3") is False or \
                WIFI_CARRIER_ARMS[carrier].get("test_bmh3") is not True:
            raise ValueError("A carrier case needs bmh3: the Wi-Fi opt-in rides on it")
    if "raw_fixture_sha256" in case and (not case.get("raw_fixture_file") or not isinstance(
            case["raw_fixture_sha256"], str) or not re.fullmatch(r"[0-9a-fA-F]{64}", case["raw_fixture_sha256"])):
        raise ValueError("Raw fixture SHA256 needs a matching input file and a64-digit digest")
    if case.get("raw_fixture_file") and app_default:
        raise ValueError("A raw fixture declares its geometry: it cannot use profile_source app_default")
    if case.get("raw_fixture_file"):
        if case.get("replay_file"):
            raise ValueError("Raw live-encoder fixture and preencoded replay are mutually exclusive")
        fixture = resolve(case["raw_fixture_file"])
        if not fixture.is_file():
            raise ValueError("Raw I420 fixture does not exist")
        if profile["width"] % 2 or profile["height"] % 2:
            raise ValueError("Raw I420 fixture requires even width and height")
        frame_bytes = profile["width"] * profile["height"] * 3 // 2
        if not fixture.stat().st_size or fixture.stat().st_size % frame_bytes:
            raise ValueError("Raw fixture must contain whole exact-size I420 frames for the requested dimensions")
    if case.get("replay_file") and not resolve(case["replay_file"]).is_file():
        raise ValueError("Replay fixture does not exist")
    replay_fixture_metadata(case)
    reference_audio_metadata(case)
    media_impairment_metadata(case)
    refuse_inert_arms(case)
    return participants


def refuse_inert_arms(case):
    """A requested arm that a parent switch masks measures nothing (switch audit F2, tools/switch_inert.py): refuse the
    case unless it sets `allow_inert_arms` (a deliberate regression control). A `user_launch` case runs the app's user
    defaults, which this benchmark-launch table does not describe, so it is not checked."""
    if case.get("allow_inert_arms") is True:
        return
    per_phone = case.get("phone_tunables")
    inert = []
    # The shared request, then each phone's own launch (a per-phone entry can switch a parent off for one phone).
    for phone in (None, *(per_phone if isinstance(per_phone, dict) else ())):
        if user_launch.is_user_launch(phone_tunables(case, phone)):
            continue
        for found in switch_inert.inert_arms(case_extras(case, phone)):
            if found not in inert:
                inert.append(found)
    if inert:
        raise ValueError("Requested arm is inert: " + "; ".join(f"{name} because {parent}" for name, parent in inert) +
                         " (set allow_inert_arms for a deliberate regression control)")


LOG_BUFFER_BYTES = 8 << 20
# Tags of the app capture; the end-of-case dump merge asks logd for the same set.
APP_LOG_FILTERS = ("BluetoothVideo:I", "GattVideoSetup:I", "AndroidRuntime:E", "*:S")



# Exact tunables a sweep case may set; each maps to one debug launch extra on every participant.
# Unknown names are rejected so a typo can never silently test the default.
TUNABLES = {
    "stream_buffer_kib": ("test_stream_buffer_kib", int),
    # Comma-separated DeviceFamilyPolicy entry ids switched off for this launch (or "all"); RM-01 policy-off arm.
    "device_policy_off": ("test_device_policy_off", str),
    # Experimental relayed private calls (RelayedCalls.kt; off by default): enable, consent, help, limits, require, no-direct mask.
    "relayed_calls": ("test_relayed_calls", bool),
    "relayed_calls_consent": ("test_relayed_calls_consent", bool),
    # Relayed calls: the idle call endpoint restart guard (default on; false is the control arm, rc-ctl-20261106).
    "relayed_restart_guard": ("test_relayed_restart_guard", bool),
    "relayed_calls_help": ("test_relayed_calls_help", bool),
    "relayed_calls_max_relays": ("test_relayed_calls_max_relays", int),
    "relayed_calls_require": ("test_relayed_calls_require", int),
    "relayed_calls_no_direct": ("test_relayed_calls_no_direct", bool),
    # DEBUG route pins (AP8, benchmark control): the caller's first helper and a helper's next hop (12-hex node prefix).
    "relayed_calls_helper": ("test_relayed_calls_helper", str),
    "relayed_calls_next_hop": ("test_relayed_calls_next_hop", str),
    "stream_flush": ("test_stream_flush", str),
    "playback_delay_ms": ("test_playback_delay_ms", int),
    "decoder_low_latency": ("test_decoder_low_latency", bool),
    "release_video_at_playout": ("test_release_video_at_playout", bool),
    "source_gatt_priority": ("test_source_gatt_priority", str),
    "forwarder_gatt_priority": ("test_forwarder_gatt_priority", str),
    # DEBUG experiment (off): relay CoC child-link priority high|balanced|low|dck (relaylink 2026-10-09).
    "forwarder_coc_priority": ("test_forwarder_coc_priority", str),
    "forwarder_child_interval_units": ("test_forwarder_child_interval_units", int),
    "decoder": ("test_decoder", str),
    "encoder_rate_trim": ("test_encoder_rate_trim", bool),
    # Default-on WebRTC-style bitrate adjuster for MediaCodec encoders; false is the control arm.
    "encoder_bitrate_adjuster": ("test_encoder_bitrate_adjuster", bool),
    "encoder_framerate_adjuster": ("test_encoder_framerate_adjuster", bool),
    # Experimental temporal layering (app default off; may drop enhancement frames under congestion).
    "temporal_layers": ("test_temporal_layers", bool),
    "link_pacing": ("test_link_pacing", bool),
    # Default-on BLE scan-failure recovery (2026-10-10); false restores stop-and-report on any failure.
    "scan_recovery": ("test_scan_recovery", bool),
    # Experimental (app default off, was the legacy-Qualcomm default until 2026-11-10): a call relay's next GATT hop with
    # acknowledged writes paced at 24 KB/s (DeviceFamilyPolicy qcom-legacy-call-relay-gatt-acknowledged-writes).
    "relay_gatt_acknowledged_writes": ("test_relay_gatt_acknowledged_writes", bool),
    # Experimental diagnostic (app default off, B3): on a hop-record tag failure on GATT, NACK and compare the resent values, then end as before.
    "hop_integrity_resend_diff": ("test_hop_integrity_resend_diff", bool),
    # Experimental (app default off, audit MEDIA-06): a stalled call video decoder is replaced at the next keyframe instead of
    # ending the call, the receive wait drops from 5 s to 1.5 s, and video stops after 3 stalls while voice continues.
    "call_video_restart": ("test_call_video_restart", bool),
    # Decoder stall recovery in place (app default ON since 2026-11-10; false is the DEBUG control arm): rebuild the same decoder at the next key frame, then exclude the component (docs/dev/switches.md).
    "decoder_stall_recovery": ("test_decoder_stall_recovery", bool),
    # Experimental (app default off, JT1): a ringing video call prepares its encoder (and, at the peer's call header, its
    # decoder component); camera and microphone still start only after Accept and the block check (docs/dev/switches.md).
    "call_prewarm": ("test_call_prewarm", bool),
    # Default-on Bluetooth ON recovery (2026-10-17): scanners started during a toggle, the live stream offer and the idle call
    # listener are rebuilt when Bluetooth is back; false is the control arm (only the paused scanners resume).
    "bluetooth_on_recovery": ("test_bluetooth_on_recovery", bool),
    # Q29 (2026-10-14): incoming-handshake deadline on every call endpoint; false restores dual-endpoint-only.
    "call_handshake_deadline_all": ("test_call_handshake_deadline_all", bool),
    # Experimental (app default off), Design PP package A (2026-11-10): incoming-only phase timers. The hop timer closes a BLE
    # GATT/CoC connection that has not verified its hop plus opening 10 s after the accept; the pre-INVITE timer closes an
    # authenticated ordinary incoming call that sends no INVITE within 10 s of its reader starting (docs/dev/switches.md).
    "call_preauth_deadline_short": ("test_call_preauth_deadline_short", bool),
    "call_pre_invite_deadline": ("test_call_pre_invite_deadline", bool),
    # Experimental (default off, JT2-P2): a call endpoint's idle GATT receiver set keeps its 100 ms / high-power advert
    # instead of stepping to 250 ms / medium after 10 s (docs/dev/switches.md). DEBUG `adv_hold` line every 60 s.
    "call_adv_idle_fast": ("test_call_adv_idle_fast", bool),
    "calls_indicator_settles": ("test_calls_indicator_settles", bool),
    # Lock-screen notification privacy (2026-10-10): true shows the caller's name (user opt-in).
    "lock_screen_peer_names": ("test_lock_screen_peer_names", bool),
    # Always reachable for calls (2026-10-10): app default on for normal launches, off for every
    # benchmark launch unless true, so cases keep the advertising role they request.
    "always_reachable": ("test_always_reachable", bool),
    # Call resolution ladder (2026-10-10): app default on for user calls, off for every benchmark
    # launch unless true, so cases keep their exact requested geometry.
    "call_resolution_ladder": ("test_call_resolution_ladder", bool),
    # F16 experiments (off, 2026-10-20): reverse-direction link sharing and camera-rate follow for Automatic calls.
    "call_link_share": ("test_call_link_share", bool),
    "call_fps_follow": ("test_call_fps_follow", bool),
    "viewer_admission_bounds": ("test_viewer_admission_bounds", bool),
    # Default-on since 2026-11-10 (owner, CALL-04): a viewer's search for a lost stream ends after 60 s; false is the DEBUG control arm.
    "stream_search_bound": ("test_stream_search_bound", bool),
    # Default-on since 2026-11-10 (owner, CALL-06): a throwaway callee sends its name to a stranger caller only after the caller's
    # reveal passed the block check; false is the DEBUG control arm. Release builds always have it on.
    "callee_name_after_reveal": ("test_callee_name_after_reveal", bool),
    "source_media_prewarm": ("test_source_media_prewarm", bool),
    "drop_leading_blank_frames": ("test_drop_leading_blank_frames", bool),
    # DEBUG only: blank the first N camera images before the leading-blank gate (0..60), to exercise it on any phone.
    "inject_blank_frames": ("test_inject_blank_frames", int),
    # Default on: a GATT chunk gap held 1 s or behind 128 chunks ends the stream with its reason; false is the control arm.
    "gatt_gap_fast_fail": ("test_gatt_gap_fast_fail", bool),
    # Experimental, default off: GR1 lost-chunk recovery on GATT links (Design GR option C).
    "gatt_chunk_recovery": ("test_gatt_chunk_recovery", bool),
    # DEBUG only: drop the first copy of every received GATT data chunk with sequence N-1 mod N (0 = off).
    "gatt_drop_received_every": ("test_gatt_drop_received_every", int),
    # Default on: a user dial follows its person across a rotating-ID epoch (same contact or same address); false = exact key only.
    "dial_follows_rotation": ("test_dial_follows_rotation", bool),
    # Default on: the Nearby list says "Bluetooth needs a restart" while its scanner cannot register; false is the control arm.
    "scan_restart_hint": ("test_scan_restart_hint", bool),
    # Default on: a copied ID needs two addresses whose sightings interleave (a handover is not a copy); false is the control arm.
    "copied_id_overlap": ("test_copied_id_overlap", bool),
    # Default on: write a scrubbed crash report and offer it at the next plain launch; false turns writing off for the run.
    "crash_reports": ("test_crash_reports", bool),
    # Default-on fixes kept switchable for A/B: pacer seeded at join, early fan-out release at the rate floor.
    "link_pacer_seed": ("test_link_pacer_seed", bool),
    "fanout_early_release": ("test_fanout_early_release", bool),
    # Experimental (app default off): seed a child link pacer from that child's own last report.
    "link_pacer_peer_seed": ("test_link_pacer_peer_seed", bool),
    # LE link experiments and control arms (2026-10-09): CoC control PHY order (default
    # sequenced), GATT request MTU 23-517 (app default 517; ignored by Android 14+ clients), GATT
    # values sized to whole 251-octet LE PDUs (default off), LE PHY "1m"/"coded-s2"/"coded-s8"
    # (app default 2M).
    "coc_sequenced_phy": ("test_coc_sequenced_phy", bool),
    # Experimental (app default off): the CoC central requests the PHY only after the socket connected
    # and hop authentication finished, then waits for onPhyUpdate (readPhy fallback after 1 s).
    "coc_phy_after_connect": ("test_coc_phy_after_connect", bool),
    # Experimental (app default off): the higher node-ID phone of a direct call pair holds its outgoing
    # call 1.5 s and yields to the peer's call (one CoC initiator per pair).
    "coc_single_initiator": ("test_coc_single_initiator", bool),
    # Experimental (app default off): peers tracked by node prefix; stale/replaced addresses rescanned first.
    "peer_address_book": ("test_peer_address_book", bool),
    # Experimental (app default off): Android <= 11 CoC SDUs coalesced up to 8,000 bytes (replaces single-PDU there).
    "coc_coalesce_sdus": ("test_coc_coalesce_sdus", bool),
    "le_priority_retry": ("test_le_priority_retry", bool),
    # Default-on (2026-10-09) kept switchable: adaptive 2M -> 1M on a backlogged one-PDU-per-event link;
    # CoC SDUs sized to one 251-octet PDU.
    "adaptive_phy": ("test_adaptive_phy", bool),
    "adaptive_phy_memory": ("test_adaptive_phy_memory", bool),
    # Experimental (default off): where the adaptive PHY would move a GATT link 2M -> 1M, move only the peripheral -> central
    # direction to 1M first (setPreferredPhy tx2M/rx1M); the _dir tunable picks the mirror tx1m-rx2m (docs/dev/switches.md).
    "adaptive_phy_split": ("test_adaptive_phy_split", bool),
    "adaptive_phy_split_dir": ("test_adaptive_phy_split_dir", str),
    # Default-on (2026-10-10) call 2M setup-stall guard: a first 2M call link without hop authentication
    # 3 s after it opened is closed and the 1M retry rescans a direct callee; false = control arm.
    "phy_setup_stall": ("test_phy_setup_stall", bool),
    # Default-on (2026-10-21) fixes of the 1M retry after a call 2M setup stall, false = control arm each: wait for the
    # stalled link's ACL to drop before dialing; redial fast 0x3E failures (4 dials, 1.5 s apart, inside the 15 s window);
    # key the bad-2M memory of a rotating offer by its recognised contact's node.
    "setup_retry_acl_wait": ("test_setup_retry_acl_wait", bool),
    "stall_retry_dials": ("test_stall_retry_dials", bool),
    "phy_memory_contact_key": ("test_phy_memory_contact_key", bool),
    # Default-on (2026-10-13) message-exchange dial 2M protections (stall guard, 1M retry, bad-2M memory); false = control arm.
    "message_adaptive_phy": ("test_message_adaptive_phy", bool),
    # Default-on (2026-10-13) progress-aware total limit of a plain Messaging v2 exchange; false = the fixed 120 s.
    "mv2_progress_total": ("test_mv2_progress_total", bool),
    "relay_phy_guard": ("test_relay_phy_guard", bool),
    "ql_consent_ui": ("test_ql_consent_ui", bool),
    "relay_one_pdu_move": ("test_relay_one_pdu_move", bool),
    "relay_reanchor": ("test_relay_reanchor", bool),
    "coc_single_pdu_sdus": ("test_coc_single_pdu_sdus", bool),
    "gatt_request_mtu": ("test_gatt_request_mtu", int),
    "gatt_aligned_writes": ("test_gatt_aligned_writes", bool),
    "ble_phy": ("test_ble_phy", str),
    # Experimental call FPS ladder from 0.4 x request (app default off: geometry floor).
    "call_ladder_from_request": ("test_call_ladder_from_request", bool),
    # Experimental bounded media age (app default off: no frame loss); sheds stale video codec-safely.
    "bounded_media_age": ("test_bounded_media_age", bool),
    "relay_child_media_age": ("test_relay_child_media_age", bool),
    "relay_child_reanchor": ("test_relay_child_reanchor", bool),
    # Default-on relay cold start (one child on the carried incoming link); false is the control arm.
    "relay_cold_start": ("test_relay_cold_start", bool),
    # DEBUG only (SEC-B2, default off; test_require_relay implies it on the source): the manual stream relay (test_role=relay)
    # passes the end-to-end handshake through, so the source and the viewer of that chain skip the stream-hop endpoint ==
    # link equality for a v2 hop (docs/dev/protocol/README.md Phase 1b item 4). Set it on the source and the viewer of a relay-role case.
    "stream_relay_passthrough": ("test_stream_relay_passthrough", bool),
    # Default-on relay parent fallback after repeated link-establishment failures; false is the control arm.
    "relay_establish_fallback": ("test_relay_establish_fallback", bool),
    # Default-on fan-out growth in planned-link units; false is the control arm.
    "fanout_proven_links": ("test_fanout_proven_links", bool),
    "relay_parent_trickle": ("test_relay_parent_trickle", bool),
    # Default-on relay child pacing exempts only an audio record from the pacer (false: the earlier
    # any-audio-queued test, which left a backlog unpaced); the lead cap is an experiment (app default off).
    "relay_pace_head": ("test_relay_pace_head", bool),
    "relay_pace_lead_cap": ("test_relay_pace_lead_cap", bool),
    # Experimental time-based sizing (Q31, app default off): relay child queue shed target in ms of media from the
    # child's drain rate and jitter; viewer playout grown to the measured arrival jitter; shared latency budget
    # (300-5000 ms) and relay queue floor (100-1000 ms).
    "relay_time_queue": ("test_relay_time_queue", bool),
    # Experimental (app default off): the relay's own sustained delivery to its child feeds the drain rate when
    # the child's arrival trains report none (slow GATT hops); logged as drainSource in relay_time_queue.
    "relay_drain_sustained": ("test_relay_drain_sustained", bool),
    "playout_time_target": ("test_playout_time_target", bool),
    "time_queue_budget_ms": ("test_time_queue_budget_ms", int),
    "time_queue_floor_ms": ("test_time_queue_floor_ms", int),
    # Default-on relay child stall guard (false = control arm: wait for the 5 s supervision timeout); the
    # upstream DCK priority for a relay-capable child is an experiment (app default off, Android 14+ centrals).
    "relay_child_stall_guard": ("test_relay_child_stall_guard", bool),
    "relay_upstream_dck": ("test_relay_upstream_dck", bool),
    # Default-on viewer decoder size-change handling (false = control arm: one decoder at the header size,
    # a size change only logged, recovery re-queues the stream-start csd).
    "decoder_size_rebuild": ("test_decoder_size_rebuild", bool),
    # Experimental relay establishment budget (app default off): retry fast 0x3E/133 child connects in place for 12 s.
    "relay_establish_budget": ("test_relay_establish_budget", bool),
    # Experimental calls (app default off): authenticated 2 KB video fragments with audio between them,
    # and speech-led call playout (late video may grow the shared call delay by at most 150 ms).
    "call_video_fragments": ("test_call_video_fragments", bool),
    # Experimental call arm D (app default off, needs call_video_fragments): size each fragment by link
    # airtime, rate x this many ms (0 = off = fixed 2 KB). The A/B arm uses 40.
    "call_fragment_airtime_ms": ("test_call_fragment_airtime_ms", int),
    # Experimental in-call link renewal (Design SR, app default off, both endpoints) and its DEBUG stall hook: this
    # phone stops sending on the call's first link N s after ACTIVE (give it to ONE endpoint, via phone_tunables).
    "call_link_renewal": ("test_call_link_renewal", bool),
    "call_tx_stall_after_s": ("test_call_tx_stall_after_s", int),
    "gatt_mtu_probe": ("test_gatt_mtu_probe", bool),
    "receiver_ui_stability": ("test_receiver_ui_stability", bool),
    "call_speech_led_playout": ("test_call_speech_led_playout", bool),
    # Default-on call fixes kept switchable for A/B: audio headroom in the call send queue, and the
    # legacy VP9-only decode restriction widened when the peer encodes no VP9.
    "call_audio_headroom": ("test_call_audio_headroom", bool),
    "call_codec_fallback": ("test_call_codec_fallback", bool),
    # Experimental calls (app default off): legacy route low delay (40 ms audio pairs, normal call
    # playout), legacy Qualcomm call protections lifted on this phone, call screen over the lock
    # screen with an incoming-call alert, first call frame shown on arrival.
    "call_legacy_low_delay": ("test_call_legacy_low_delay", bool),
    "call_legacy_relief": ("test_call_legacy_relief", bool),
    "call_lock_screen": ("test_call_lock_screen", bool),
    "call_first_frame_now": ("test_call_first_frame_now", bool),
    # Join-latency A/Bs (2026-11-07 stage breakdown): quiet PSM-read link, call audio set-up off the UI thread.
    "psm_read_link_quiet": ("test_psm_read_link_quiet", bool),
    "audio_off_main": ("test_audio_off_main", bool),
    # Experimental (default off, join-latency P1): a fresh-ACL CoC client returns the socket at once; the MTU prime and the
    # control reattach run in the background and bulk writes wait for them (at most 1.5 s) (docs/dev/switches.md).
    "coc_prime_async": ("test_coc_prime_async", bool),
    # Experimental (default off, join-latency P4b): one same-PSM CoC connect retry after the ACL-down wait (docs/dev/switches.md).
    "coc_psm_reuse_retry": ("test_coc_psm_reuse_retry", bool),
    # Experimental (default off, join-latency P4a): the first redial after a fast 133/0x3E waits 200 ms, not 500.
    "fast_first_redial": ("test_fast_first_redial", bool),
    # Experimental (default off): first call frame at once, then a bounded slow-down into the playout target (no freeze).
    "call_first_frame_ramp": ("test_call_first_frame_ramp", bool),
    # Experimental (app default off, JT2): after Accept and the block check the call starts its camera before its audio
    # route (route open and microphone queued behind it), opens the camera together with its preview surface, and plays
    # remote audio only after the route open (docs/dev/switches.md).
    "call_camera_first": ("test_call_camera_first", bool),
    # App default on (2026-11-10, UI-01): a video call answered or placed with only the camera refused goes on as
    # voice; false is the control arm (the refusal is only said).
    "call_voice_when_camera_refused": ("test_call_voice_when_camera_refused", bool),
    # Experimental (app default off, CALL-02): an incoming link that failed before it rang changes no screen, says nothing.
    "call_quiet_pre_ring_failure": ("test_call_quiet_pre_ring_failure", bool),
    # Experimental (app default off, AP7 F2): a hop authentication that finished after this many ms on a first 2M call
    # attempt is a setup stall and takes the existing 1M retry (0 = off; docs/dev/switches.md).
    "stall_slow_auth_ms": ("test_stall_slow_auth_ms", int),
    # Experimental (app default off, AP7 F3): the DEBUG static private-call relay's child hop gets the on-demand 3 s
    # guard and one 1M retry (test_private_call_relay shape only; production uses startOnDemand; docs/dev/switches.md).
    "relay_start_hop_guard": ("test_relay_start_hop_guard", bool),
    # Experimental (app default off, AP7 F1): a delivered GR1 hello/high counts as the stage-2 first-write watch's write.
    "gatt_first_write_counts_control": ("test_gatt_first_write_counts_control", bool),
    # Call UX (2026-10-10). Benchmark launches keep the former behaviour unless a tunable opts in: audio
    # focus with hold on transient loss and the silent/vibrate/DND ringer policy (both on for user
    # sessions), and the experiments ringback, CallStyle notifications, camera pause at the ear and
    # audio starvation concealment with silence-gated catch-up (all app default off).
    "call_audio_focus": ("test_call_audio_focus", bool),
    "call_ringer_policy": ("test_call_ringer_policy", bool),
    "call_ringback": ("test_call_ringback", bool),
    "call_style_notification": ("test_call_style_notification", bool),
    "call_proximity_camera_pause": ("test_call_proximity_camera_pause", bool),
    "call_audio_conceal": ("test_call_audio_conceal", bool),
    # Experimental call encoder fit (app default off; live camera only): smaller call geometry after
    # this phone's native encoder overloaded at its fastest settings in an earlier call.
    "call_encoder_fit": ("test_call_encoder_fit", bool),
    # Experimental native libopus call audio (app default off): VOIP mode, in-band FEC with a loss hint,
    # decoder PLC/FEC recovery; sub-switches FEC (default on), loss hint % (default 10), DTX (default off).
    "call_native_opus": ("test_call_native_opus", bool),
    "call_native_opus_fec": ("test_call_native_opus_fec", bool),
    "call_native_opus_loss_pct": ("test_call_native_opus_loss_pct", int),
    "call_native_opus_dtx": ("test_call_native_opus_dtx", bool),
    # Control arm for private-call relay chains (app default on): false = one relay, its child the callee.
    "call_relay_chain": ("test_call_relay_chain", bool),
    # Experimental relayed call cap from this phone's measured link (app default off: 15 FPS / 110 kbps).
    "relayed_call_measured_cap": ("test_relayed_call_measured_cap", bool),
    # Experimental delivery-based probing (app default off): probe ceiling, probe undo, queue overuse.
    "delivery_rate_control": ("test_delivery_rate_control", bool),
    # Control arm for link report version 3 (app default on); false = pre-2026-10-09 behaviour exactly.
    "delivery_reports": ("test_delivery_reports", bool),
    # Experimental stream delivery reports (app default off; every phone in the stream on this build).
    "stream_delivery_reports": ("test_stream_delivery_reports", bool),
    "on_time_reports": ("test_on_time_reports", bool),
    # On-time seed raise (app default on since 2026-10-16); false is the control arm.
    "on_time_seed_raise": ("test_on_time_seed_raise", bool),
    # Experimental relay child-detach guard against the node's own pre-child p95 (app default off).
    "receiver_node_baseline_p95": ("test_receiver_node_baseline_p95", bool),
    # Experimental join probe seed (app default off, 2026-10-17): seed a fresh link's pacer from a short measured
    # burst at join instead of the cautious prior. Set on every phone in the stream (the child times the burst).
    "join_probe_seed": ("test_join_probe_seed", bool),
    # Experimental seed-raise drain guard (app default off, 2026-10-17): no raise while the queue is not draining
    # and writes are blocking (link-limited).
    "seed_raise_drain_guard": ("test_seed_raise_drain_guard", bool),
    # Experimental relay establishment keeps 2M (app default off): a child setup that failed before any connection
    # existed no longer pins that child to 1M for the session.
    "relay_establish_keep_2m": ("test_relay_establish_keep_2m", bool),
    # Experimental relay child thinning (app default off): source temporal layers + per-child full/base/audio.
    "relay_child_thinning": ("test_relay_child_thinning", bool),
    # A/B arms: clear the 24 h learned start rates at launch (the launch log records them either way).
    "reset_learned_rates": ("test_reset_learned_rates", bool),
    # DEBUG: clear learned relay/fan-out ceilings, sustained rates and bad-2M marks before the launch.
    "reset_learned_state": ("test_reset_learned_state", bool),
    # Experimental learned-limit recovery (app default off): ceiling probe after a cool-down, 24 h bad-2M.
    "ceiling_probe_recovery": ("test_ceiling_probe_recovery", bool),
    # Control arm for the learned call start (app default on): false = build-keyed learned rates and
    # unmeasured calls starting at the request, as before 2026-10-09.
    "start_rate_guard": ("test_start_rate_guard", bool),
    "hw_qp_min": ("test_hw_qp_min", int),
    "hw_qp_max": ("test_hw_qp_max", int),
    "keyframe_shaping": ("test_keyframe_shaping", float),
    "bitrate_mode": ("test_bitrate_mode", int),
    # Workstream C burst experiments (app default off; see the app's DebugTunables):
    # scene-cut shaping 0 off / 1 sync frame + lowered target / 2 lowered target only.
    "scene_cut_shaping": ("test_scene_cut_shaping", int),
    # VP9 quantizer headroom: lower the target while parsed base_q_idx stays below this floor.
    "vp9_q_floor": ("test_vp9_q_floor", int),
    # Encoder ranking by measured burst size / encode headroom (default choice only).
    "encoder_ranking": ("test_encoder_ranking", bool),
    # Child lateness after a large frame on a link that keeps up is one burst, not congestion.
    "burst_drain": ("test_burst_drain", bool),
    # Receiver playout grows once by this multiple of a frame's lateness (lane-b P2), 1.0-4.0.
    "playout_burst_growth": ("test_playout_burst_growth", float),
    # Hardware intra refresh period in frames (MediaFormat intra-refresh-period).
    "intra_refresh_frames": ("test_intra_refresh_frames", int),
    # Experimental intercom features (app default off): voice calls, push-to-talk button, PTT mode (starts muted).
    "voice_calls": ("test_voice_calls", bool),
    "push_to_talk": ("test_push_to_talk", bool),
    "ptt_mode": ("test_ptt_mode", bool),
    # Call audio (2026-10-09): sender-gap realignment is a default-on fix kept switchable for A/B;
    # low-delay voice playout and a separate call audio clock are experimental (app default off).
    "audio_gap_align": ("test_audio_gap_align", bool),
    "voice_call_low_delay": ("test_voice_call_low_delay", bool),
    "call_audio_own_clock": ("test_call_audio_own_clock", bool),
    # SW1 (Experimental, app default off, 2026-11-10): a sealed record keeps its place in the Bluetooth FIFO across a switch to Wi-Fi
    # (no stale drop), and the Bluetooth reader stamps liveness before the record lock (docs/dev/switches.md).
    "cutover_keep_fifo_slot": ("test_cutover_keep_fifo_slot", bool),
    "liveness_before_record_lock": ("test_liveness_before_record_lock", bool),
    # Experimental bond-free Classic (app default off): insecure RFCOMM + BMH2 on every role, learned
    # peer addresses, inquiry first contact; set it on every participant. Visibility prompt is opt-in.
    "classic_bond_free": ("test_classic_bond_free", bool),
    # Experimental bounded Classic connect path alone (bond-free implies it; app default off).
    "classic_bounded_connect": ("test_classic_bounded_connect", bool),
    "classic_request_visible": ("test_classic_request_visible", bool),
    # DEBUG Classic address overrides read over adb: "nodeprefix=AA:BB:CC:DD:EE:FF,...".
    "classic_addresses": ("test_classic_addresses", str),
    # Experimental call-link pacing on Classic/CoC calls (app default off).
    "call_link_pacing": ("test_call_link_pacing", bool),
    # DEBUG harness for the rotating-ID presence-lease day roll (P3): moves the discovery wall clock forward by
    # this many whole days (0-28) at cold start; give both phones of a pair the same value. The one-shot contact
    # verify/forget extras need a fresh token per run and are produced by tools/debug_verify_contacts.py.
    "presence_day_offset": ("test_presence_day_offset", int),
    # DEBUG harness for the rotating-ID phone tests: shortens the discovery epoch (60-900 s, default 900) at cold
    # start so three boundaries take minutes; give EVERY phone of the run the same value (unequal lengths fail the
    # two-epoch check by design) and pass the same value to rotid_tracker.py --epoch-s. Presence-lease days stay real.
    "rotid_epoch_s": ("test_rotid_epoch_s", int),
    # DEBUG benchmark launches read only this extra for rotating discovery IDs (RotatingDiscovery.EXTRA): set it on
    # BOTH phones of a call so the callee is a stranger (pseudonymous), which exercises the REVEAL-gated caller name.
    "rotating_ids": ("test_rotating_ids", bool),
    # Experimental old caller-name timing (name before the stranger answers; owner 2026-11-06 default off).
    "names_before_answer": ("test_names_before_answer", bool),
    # BMH3 handshake and its Experimental pseudonymous stranger identities (a callee answers a stranger with a throwaway
    # key and reveals after Accept). Set both, plus rotating_ids, on BOTH phones for a true stranger call.
    "bmh3": ("test_bmh3", bool),
    "bmh3_pseudonyms": ("test_bmh3_pseudonyms", bool),
    # Default-on since 2026-11-10 (owner, BT-07 Q4): the Bluetooth-may-be-stuck card on MediaTek ColorOS/Realme UI Android 16+
    # (AP2); false is the DEBUG control arm. Release builds always have it on.
    "gatt_native_wedge_guard": ("test_gatt_native_wedge_guard", bool),
    # Experimental (default off, Q14 2026-11-06, AP4): the adaptive split names the failing direction and remembers the
    # working split per peer; set it on every participant. Masks adaptive_phy_split (docs/dev/switches.md).
    "phy_direction_split": ("test_phy_direction_split", bool),
    # Experimental (default off, AP4): after a call audio stall of 2 s or more, audio older than the playout budget is
    # dropped and logged (call_audio_stale); off logs the same line with mode=observe.
    "call_stale_audio_drop": ("test_call_stale_audio_drop", bool),
    # Experimental (default off, 2026-11-11): after one isolated late burst the receiver leaves out quiet audio in pauses
    # (never speech) and the call clock follows, so the added delay returns within seconds (call_fast_recovery lines).
    "call_fast_recovery": ("test_call_fast_recovery", bool),
    # DEBUG controlled impairment (B15, default none): "AFTER_MS:DURATION_MS[,...]" - the call writer holds all media for
    # DURATION_MS starting AFTER_MS after its first media record (heartbeats flow); call_inject lines. Same in both arms.
    "call_inject_send_pause": ("test_call_inject_send_pause", str),
    # AP5 / codec control arms (Experimental, app defaults as in docs/dev/switches.md).
    # Experimental (app default off, design decision 2026-11-06): with no fresh relay ceiling the relay budget is at least
    # 2 x the per-link load whatever was proven before (relay_admission adds floor=); set it on every participant.
    "relay_no_ceiling_floor": ("test_relay_no_ceiling_floor", bool),
    # Experimental (app default off, AP5 2026-11-07): a software encoder measured short of the frame rate while its input
    # kept up is remembered; the next call or stream start prefers hardware H.264 (codec choice only, never the request).
    "sw_shortfall_avc": ("test_sw_shortfall_avc", bool),
    # Design HC/VX codec masters and the power-aware start: a benchmark launch keeps them off unless passed; an
    # Automatic-codec case passes them to run the user's codec order (docs/dev/switches.md).
    "h264_codec": ("test_h264_codec", bool),
    "hevc_codec": ("test_hevc_codec", bool),
    "prefer_libvpx": ("test_prefer_libvpx", bool),
    "codec_power_aware": ("test_codec_power_aware", bool),
    # Experimental measured Auto quality ladder (app default off, DEBUG only; docs/dev/switches.md): set it on
    # every participant of the run; the class override is strong|standard|weak (control matrix only).
    "auto_ladder": ("test_auto_ladder", bool),
    "auto_ladder_class": ("test_auto_ladder_class", str),
    # Call retry release window (on by default since 2026-11-05; false is the control arm).
    "rediscover_release_window": ("test_rediscover_release_window", bool),
    # Experimental (off): callee re-enables its advertising set when a failed caller's ACL drops.
    "adv_reenable_on_acl_down": ("test_adv_reenable_on_acl_down", bool),
    # Experimental (off): retry dial bounded by the release window; one rescan/redial.
    "retry_dial_release_window": ("test_retry_dial_release_window", bool),
    # Experimental (off): move a trickling 2M call link to 1M in place.
    "post_phy_liveness": ("test_post_phy_liveness", bool),
    # Experimental (off): release a partly opened call listener.
    "listen_partial_cleanup": ("test_listen_partial_cleanup", bool),
    # Experimental (off): Android 10 CoC listen budget kept across app processes.
    "coc_ledger_persist": ("test_coc_ledger_persist", bool),
    # Experimental (off): fail a stalled LE CoC listen after this many ms.
    "call_listen_watchdog_ms": ("test_call_listen_watchdog_ms", int),
    # Experimental (off): status 'Calls unavailable' when availability fails.
    "calls_unavailable_status": ("test_calls_unavailable_status", bool),
    # DEBUG fault injection for the call listener: hang|control_fail.
    "call_listen_inject": ("test_call_listen_inject", str),
    # CoC -> GATT fallback (shipped default off; DEBUG arm).
    "coc_auto_gatt": ("test_coc_auto_gatt", bool),
    # Names and titles (on by default for users; a benchmark launch reads only extras) and the caller's own name.
    "peer_labels": ("test_peer_labels", bool),
    "peer_label_name": ("test_peer_label_name", str),
    # Who sees my name: everyone|verified|nobody (NamingSettings).
    "names_audience": ("test_names_audience", str),
    # Experimental (off): relay relaxes its parent link and redials once after a fast 0x3E child dial.
    "relay_setup_parent_relief": ("test_relay_setup_parent_relief", bool),
    # Experimental (off): dual-role relay keeps its parent link relieved while a child link forms.
    "dual_role_parent_relief": ("test_dual_role_parent_relief", bool),
    "caller_answer_deadline": ("test_caller_answer_deadline", bool),
    "coc_attach_single": ("test_coc_attach_single", bool),
    "fast_fail_redial_all": ("test_fast_fail_redial_all", bool),
    "stall_retry_own_budget": ("test_stall_retry_own_budget", bool),
    "setup_stall_ms": ("test_setup_stall_ms", int),
    "relayed_budget_from_hop_auth": ("test_relayed_budget_from_hop_auth", bool),
    # Simplification pass A (2026-10-27): the single connect-retry rule (UnifiedConnectRule); default off.
    "unified_connect_policy": ("test_unified_connect_policy", bool),
    # Setup-latency A/B (2026-10-26, faster-setup): dial a row heard under 1.5 s ago at once (Experimental, off);
    # microphone start off the UI thread (Experimental, off); idle list scans yield to incoming connections (off).
    "dial_recent_sighting": ("test_dial_recent_sighting", bool),
    # Source honours a relay child's keyframe request after a 2 s calm gap at once (Experimental, off).
    "relay_join_key_frame": ("test_relay_join_key_frame", bool),
    # A call's first GATT dial redials a fast 0x3E (missed CONNECT_IND) up to 4 times with jitter (Experimental, off).
    "fast_0x3e_dials": ("test_fast_0x3e_dials", bool),
    "reachable_scan_yield": ("test_reachable_scan_yield", bool),
    # Default-on (2026-10-27): the setup PHY waits for the in-window priority update (PhyAfterPriority); false = control arm.
    "phy_after_priority": ("test_phy_after_priority", bool),
    # Experimental (app default off): forwarding CoC writers (relays, stream forwarders) on Android <= 12 use coalesced
    # SDUs up to 8,000 bytes (registry proposal pre-android-13-coc-congested-write-loss).
    "relay_coc_coalesce": ("test_relay_coc_coalesce", bool),
    # Default-on (2026-10-27): a direct call ignores an idle helper's call-relay bit for bad-2M memory; false = control arm.
    "direct_call_hop_memory": ("test_direct_call_hop_memory", bool),
    # Child side of relay recovery, split out of relay_child_reanchor (switch audit B2); absent, they follow it.
    "relay_parent_watch": ("test_relay_parent_watch", bool),
    "relay_prefer_parent": ("test_relay_prefer_parent", bool),
    "relay_hold_in_place": ("test_relay_hold_in_place", bool),
    # Default-on (2026-10-28) viewer link silence watch on links without the relay parent watch; false = control arm.
    "viewer_link_silence": ("test_viewer_link_silence", bool),
    # Viewer playout budget alone (switch audit B12); absent, time_queue_budget_ms sets both budgets.
    "playout_time_budget_ms": ("test_playout_time_budget_ms", int),
    # Experimental overhead trims (app default off, no wire change): queued 20 ms call audio units sent as one
    # pair/batch record (no added delay), and 40 ms pairs on every call route that sends single units.
    "call_audio_coalesce": ("test_call_audio_coalesce", bool),
    "call_audio_pairs": ("test_call_audio_pairs", bool),
    # Experimental CPU trim (app default off, byte-identical records): one AES-GCM cipher per record stream.
    "aead_cipher_reuse": ("test_aead_cipher_reuse", bool),
    # Experimental (app default 2 = 40 ms pairs): stream audio units per record, 3-10 = existing BTA2 batches.
    "stream_audio_batch_frames": ("test_stream_audio_batch_frames", int),
    # Relayed calls (relay-showcase 2026-10-26): control arms of the first-hop 2M protections and the helper's one
    # fast connect retry (app default on), and the Experimental 1M next hop of a helper (app default off).
    "relayed_first_hop_direct": ("test_relayed_first_hop_direct", bool),
    "relayed_hop_retry": ("test_relayed_hop_retry", bool),
    "relayed_hop_1m": ("test_relayed_hop_1m", bool),
    # Control arm of relayed-call candidate iteration (app default on): false = the attempt ends at the first failed route.
    "relayed_candidates": ("test_relayed_candidates", bool),
    # Control arm of the helper's per-link 2M -> 1M next-hop retry and its bad-2M record (app default on).
    "relayed_hop_phy_retry": ("test_relayed_hop_phy_retry", bool),
    # Amendment CP (app defaults: pseudonymous relay edges on, stranger relayed calls on, contact-helper arm off).
    "relayed_pseudonymous_edges": ("test_relayed_pseudonymous_edges", bool),
    "relayed_stranger_calls": ("test_relayed_stranger_calls", bool),
    "relayed_contact_helper": ("test_relayed_contact_helper", bool),
    # Automatic prefers a hardware AV1 encoder (design decision 2026-10-27, default on); false = control arm.
    "prefer_hw_av1": ("test_prefer_hw_av1", bool),
    # Register names the runner used to reject (switch audit 2026-10-27, D/F7): each is the switch's own DEBUG launch extra
    # (docs/dev/switches.md row of the same name).
    "call_bluetooth_output": ("test_call_bluetooth_output", bool),
    "check_for_updates": ("test_check_for_updates", bool),
    "copied_id_pc_pair": ("test_copied_id_pc_pair", bool),
    "gatt_input_failure_ends_link": ("test_gatt_input_failure_ends_link", bool),
    "gatt_phy_split": ("test_gatt_phy_split", str),
    "gatt_reregister_keep_address": ("test_gatt_reregister_keep_address", bool),
    "inbound_probe": ("test_inbound_probe", str),
    "live_media_state": ("test_live_media_state", bool),
    "newer_versions_nearby": ("test_newer_versions_nearby", bool),
    # Experimental (app default off, JT2-P3): a direct outgoing GATT call opens on LE 1M with no PHY request, asks for 2M after
    # its first picture only above the 1M budget or at a link limit, falls back to 1M in place on an early loss, no setup retry.
    "call_1m_first": ("test_call_1m_first", bool),
    # Experimental (app default off, B15 feat/call-1m-first-coc): the same 1M-first start for a direct outgoing CoC call
    # (the control link asks for 1M; 2M only after the first picture when needed). Independent of call_1m_first (GATT).
    "call_1m_first_coc": ("test_call_1m_first_coc", bool),
    # Experimental control arm (B16): a first-1M call also asks for 2M at the sender's link limit (the first version);
    # default holds it on 1M (link_phy reason=link_limit_held).
    "call_1m_first_link_limit": ("test_call_1m_first_link_limit", bool),
    # Experimental (app default off, B11 fix/a15s-own-2m): after two symmetric 2M call setup stalls in a row as caller, peers
    # with no memory key (unrecognised rotating offers) start on 1M while the local own-controller mark is fresh.
    "own_2m_memory": ("test_own_2m_memory", bool),
    # Experimental (app default off): a stalled or crawling first symmetric 2M call link to an unrecognised direct peer asks the
    # same link for 1M before today's close and 1M retry; after a fallback close the rescan waits out the callee's 20 s deadline.
    "slow_2m_in_place": ("test_slow_2m_in_place", bool),
    "phy_after_auth": ("test_phy_after_auth", bool),
    "phy_move_flood_guard": ("test_phy_move_flood_guard", bool),
    "phy_move_skip_backlog": ("test_phy_move_skip_backlog", bool),
    "reachable_while_browsing": ("test_reachable_while_browsing", bool),
    "relay_recovery_grace": ("test_relay_recovery_grace", bool),
    "relay_refusal_escalation": ("test_relay_refusal_escalation", bool),
    "relay_stall_holdoff": ("test_relay_stall_holdoff", bool),
    "relay_stall_separate": ("test_relay_stall_separate", bool),
    "stall_rediscover_ms": ("test_stall_rediscover_ms", int),
    "stall_remembers_2m": ("test_stall_remembers_2m", bool),
    "stalled_2m_move": ("test_stalled_2m_move", bool),
    "stream_adaptive_phy": ("test_stream_adaptive_phy", bool),
    "stream_row_grouping": ("test_stream_row_grouping", bool),
    # Shipped-default features (B4/F7): a benchmark launch runs every release-ON feature OFF unless its extra is passed.
    # Pass these to measure them, or `user_launch: true` to run the app exactly as a person's launch does (user
    # defaults and stored choices; DEBUG test_user_launch, the other tunables still apply where the app reads them).
    "user_launch": ("test_user_launch", bool),
    "call_head_of_line": ("test_call_head_of_line", str),  # off | gatt_default (what users run) | on
    "bmh3_only": ("test_bmh3_only", bool),
    "bmh3_contacts_only": ("test_bmh3_contacts_only", bool),
    "iphone_discovery": ("test_ios_compact_discovery", bool),
    "generated_names": ("test_generated_names", bool),
    "bvmid2": ("test_bvmid2", bool),
    "title_pages": ("test_title_pages", bool),
    "visible_by_name": ("test_visible_by_name", bool),
    "find_pcs": ("test_find_pcs", bool),
    "messaging_v2": ("test_messaging_v2", bool),
    "mv2_chat_session": ("test_mv2_chat_session", bool),
    "audio_live": ("test_audio_live", bool),
    "store_forward": ("test_store_forward", bool),
    # Experimental (app default off), Design WF. wifi_carrier / wifi_wider_reach / wifi_direct / wifi_force_carrier are set by a
    # case's "carrier" key, not as tunables; these are the further arms. wifi_ble_standby_low_power is reserved with no
    # consumer (R-4) and deliberately has no entry, so a case naming it is refused.
    "wifi_icm": ("test_wifi_icm", bool),
    "wifi_allow_24ghz": ("test_wifi_allow_24ghz", bool),
    "wifi_share_attach": ("test_wifi_share_attach", bool),
    "wifi_probe": ("test_wifi_probe", bool),
    # Aware arms (aware-port-band). wifi_aware_derived_port is the rule since 2026-11-10 (W5, PROTOCOL `port`; app default
    # on, also in a benchmark launch that does not name it): false is the control arm (host random port, a guest whose
    # platform reports no port ends no_port). wifi_aware_require_channel (Experimental, off): a required, not hinted, 5 GHz channel.
    "wifi_aware_derived_port": ("test_wifi_aware_derived_port", bool),
    "wifi_aware_require_channel": ("test_wifi_aware_require_channel", bool),
    # AP9 DEBUG control arm: the old all-channels-5GHz Aware band rule (the default judges by the 5 GHz data channel).
    "aware_band_strict": ("test_aware_band_strict", bool),
    # AP10 (Experimental, app default off): a 2.4 GHz-only Aware path is accepted on a phone with no other Bluetooth media link.
    "aware_24ghz_alone": ("test_aware_24ghz_alone", bool),
    # AP10 (Experimental, app default off = log-only): true lets the Aware goodput guard return a low hop to Bluetooth.
    "aware_goodput_guard": ("test_aware_goodput_guard", bool),
    # A5's exact-rate arm (M2 on a Wi-Fi carrier; DEBUG, needs test_rate_control): requested video kbps exactly, no clamp to the transport maximum.
    "wifi_exact_kbps": ("test_wifi_exact_kbps", int),
}


def phone_tunables(case, phone):
    """Launch tunables of one phone: the case's shared tunables with that phone's own `phone_tunables` over them.

    A per-phone entry (for example names_before_answer on one endpoint, or a stall hook on one side) reaches only
    that phone's launch; every other phone keeps the shared tunables."""
    merged = dict(case.get("tunables") or {})
    per_phone = case.get("phone_tunables")
    if isinstance(per_phone, dict) and isinstance(per_phone.get(phone), dict):
        merged.update(per_phone[phone])
    return merged


# "carrier" request key (Design WF; absent = Bluetooth only). Launch extras each carrier sets on EVERY participant.
# Each arm also pins BMH3: the Wi-Fi opt-in (WIFI marker) rides on it, so a phone whose stored choice is off would
# otherwise run the whole case on Bluetooth.
WIFI_CARRIER_ARMS = {
    "wifi_aware": {"test_wifi_carrier": True, "test_wifi_force_carrier": "aware", "test_bmh3": True},
    "wifi_aware_wider": {"test_wifi_carrier": True, "test_wifi_wider_reach": True, "test_wifi_force_carrier": "aware",
                         "test_bmh3": True},
    "wifi_direct": {"test_wifi_carrier": True, "test_wifi_direct": True, "test_wifi_force_carrier": "direct",
                    "test_bmh3": True},
}
WIFI_CARRIER_OWNED_TUNABLES = {"wifi_carrier", "wifi_wider_reach", "wifi_direct", "wifi_force_carrier"}


def case_extras(case, phone=None):
    """The launch extras a case's own request sets for [phone] (every phone's shared set when None): the shared
    tunables with that phone's own `phone_tunables` over them, then the carrier's arms. The carrier owns only
    WIFI_CARRIER_OWNED_TUNABLES (a case naming one is refused), so an explicit tunable such as
    `wifi_aware_derived_port: false` (the control arm) reaches the launch unchanged."""
    return {**tunable_extras(phone_tunables(case, phone)), **WIFI_CARRIER_ARMS.get(case.get("carrier"), {})}


def tunable_extras(tunables):
    extras = {}
    for name, value in tunables.items():
        if name not in TUNABLES:
            raise ValueError(f"Unknown tunable {name!r}")
        extra, kind = TUNABLES[name]
        if kind is int and (isinstance(value, bool) or not isinstance(value, int)) or \
                kind is not int and not isinstance(value, kind):
            raise ValueError(f"Tunable {name!r} must be {kind.__name__}")
        extras[extra] = value
    return extras


def stage_sequence_gaps(lines):
    """Count app stage records the device log lost: each (scope, role) stream numbers its records
    1, 2, 3...; a jump means logd never kept the lines between (ASUS mesh4-smoke-b9029e48: the app's
    and the Bluetooth process's logs both went silent for 10-14 s while frames kept arriving).
    Returns (missing records, streams checked). Lines without a parsable record are ignored."""
    last = {}
    missing = 0
    for line in lines:
        try:
            records = stage_records(line)
        except ValueError:
            continue
        for record in records:
            try:
                key = (record.get("scope"), record.get("role"))
                sequence = int(record["sequence"])
            except (KeyError, TypeError, ValueError):
                continue
            previous = last.get(key)
            if previous is not None and sequence > previous + 1:
                missing += sequence - previous - 1
            if previous is None or sequence > previous:
                last[key] = sequence
    return missing, len(last)


# liblog writes to logd over a non-blocking datagram socket (AOSP android11-release
# system/core/liblog/logd_writer.cpp). On EAGAIN it drops the line and counts it; the process's next
# successful write first logs that count to the events buffer as event 1005, tag `liblog`, with the
# timestamp of the write it precedes. The ASUS (Android 11) lost `call phase ACTIVE` this way at camera
# open (release-window-pres, 7 of 25 ASUS-caller calls), so a missing one-time line is not proof.
LOG_DROP_FILTERS = ("-b", "events", "-s", "liblog")
LOG_DROP_REASON = "log lines dropped by logd"
LIBLOG_DROP = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+[VDIWEF]\s+liblog\s*:\s*\[?\s*(\d+)\s*\]?\s*$")


def parse_log_drops(text, pid=None):
    """liblog drop reports in `logcat -b events -v threadtime` text, as [{"time", "pid", "dropped"}]
    (one PID's when given). Each exact report line counts once."""
    result, seen = [], set()
    for line in (text or "").splitlines():
        match = LIBLOG_DROP.match(line.strip())
        if not match or line.strip() in seen or (pid is not None and int(match[2]) != int(pid)):
            continue
        seen.add(line.strip())
        result.append({"time": match[1], "pid": int(match[2]), "dropped": int(match[3])})
    return result


def drops_in_window(drops, pid, after=None, until=None):
    """Drop reports of `pid` that can hide a line expected later than `after` (logcat time; None = any)
    and earlier than a stored line at `until` (None = open). A report covers the lines lost since the
    process's previous stored write and carries the time of its next stored write, so a lost line later
    than `after` is reported later than `after`, and one lost before the stored line at `until` is
    reported no later than `until`."""
    lower = line_time(after + " ") if after else None
    upper = line_time(until + " ") if until else None
    found = []
    for item in drops or []:
        stamp = line_time(str(item.get("time", "")) + " ")
        if stamp is None or pid is None or int(item.get("pid", -1)) != int(pid) or int(item.get("dropped", 0)) <= 0:
            continue
        if (lower is None or stamp > lower) and (upper is None or stamp <= upper):
            found.append(item)
    return found


def manifest_log_drops(manifest, base_path, device, pid):
    """(drop reports, capture entry) of one participant PID from the manifest's hash-bound liblog capture;
    ([], None) when none was recorded. The file is re-read and must still match its recorded hash: a
    changed or unreadable capture confirms no drop (its entry is returned marked unverified)."""
    entry = next((item for item in manifest.get("log_drops", []) if item.get("device") == device and
                  pid is not None and item.get("pid") == int(pid) and item.get("path") and item.get("sha256")), None)
    if entry is None:
        return [], None
    try:
        path = resolve(entry["path"], base_path)
        with path.open("rb") as handle:
            if hashlib.file_digest(handle, "sha256").hexdigest() != entry["sha256"]:
                return [], {**entry, "verified": False}
        return parse_log_drops(path.read_text(encoding="utf-8", errors="replace"), pid), entry
    except (OSError, ValueError):
        return [], {**entry, "verified": False}


def confirmed_log_loss(manifest, base_path):
    """The runner stopped on a missing one-time app line (`runner_error_log_loss`) and the participant's
    hash-bound liblog capture holds a drop report of that PID inside the line's window: the stop is then
    UNMEASURED log loss, never a FAIL. None when the capture does not confirm it."""
    loss = manifest.get("runner_error_log_loss")
    if not manifest.get("runner_error") or not isinstance(loss, dict):
        return None
    drops, _ = manifest_log_drops(manifest, base_path, loss.get("device"), loss.get("pid"))
    found = drops_in_window(drops, loss.get("pid"), loss.get("after"))
    return {**loss, "reports": found} if found else None


def apply_log_loss(score, manifest, base_path):
    """Turn a broadcast score's runner_error gate UNMEASURED when its stop was confirmed log loss."""
    loss = confirmed_log_loss(manifest, base_path)
    gates = score.get("execution_gates", {})
    if loss and gates.get("runner_error", {}).get("status") == "FAIL":
        from score_device_run import aggregate_status
        gates["runner_error"] = {"status": "UNMEASURED", "value": manifest["runner_error"],
                                 "reason": LOG_DROP_REASON, "log_loss": loss}
        score["overall_status"] = aggregate_status([*gates.values(), *[{"status": item["overall_status"]}
                                                                      for item in score.get("devices", [])]])
    return score


class LogLinesDropped(RuntimeError):
    """A one-time app line never arrived and logd reported dropping this PID's lines where it was due."""

    def __init__(self, message, record):
        super().__init__(message)
        self.record = record


class Runner:
    def __init__(self, adb, devices, case, output):
        self.adb_path, self.devices, self.case, self.output = adb, devices, case, output
        self.logs, self.captures, self.events, self.intervals = [], [], [], []
        # Bluetooth-process logs are diagnostics only; kept apart because captures align with logs.
        self.stack_logs, self.stack_captures = [], []
        # liblog drop reports per app PID (events buffer): proof of lines logd never stored.
        self.drop_logs, self.drop_captures = [], []
        # One bounded dump of the crash and system buffers per phone, taken when the case ends (crashes and ANRs).
        self.crash_logs = []
        # Launch extras as sent per phone (after any rewrite) and what the app's echo and effect lines showed of them.
        self.launch_sent, self.arm_checks = {}, []
        # A user launch's stored choices (tools/user_launch.py): what the case asks for and what was written per phone.
        user_launch.resolve(case)  # a malformed user_settings is refused before any phone is touched
        self.settings_case = case  # the case that states user_settings (self.case becomes each launch's own shape)
        self.user_setting_records = []
        # The preferences file as each phone had it before the first write (exact bytes), and the restore outcomes.
        self.prefs_snapshots, self.user_settings_restored = {}, []
        self.log_buffers = {}
        self.active_intervals = {}
        self.expected_stopped = set()
        self.source_end_applied = False
        self.pids = {}
        self.observed_lines = {}
        self.health_events = []
        self.topology_changes = []
        self.pending_planned_losses = {}
        self.participants = validate_case(case, devices)
        self.run_id = (re.sub(r"[^A-Za-z0-9_.-]", "_", output.name)[:40] or "run") + "-" + uuid.uuid4().hex
        self.trace_session = self.run_id if case.get("media_trace") else None
        self.trace_artifacts = []
        self.raw_fixture = raw_fixture_metadata(case)
        self.replay_fixture = replay_fixture_metadata(case)
        self.reference_audio = reference_audio_metadata(case)
        self.media_impairment = media_impairment_metadata(case)

    def check_cancelled(self):
        event = getattr(self, "cancel_event", None)
        if event is not None and event.is_set():
            raise InterruptedError("Suite stopped by user")

    def adb(self, phone, *args, timeout=20):
        if args[:3] == ("shell", "am", "start") and "-W" in args and "test_control" not in args:
            self.launch_sent[phone] = applied_arms.parse_sent_extras(args)
        command = [self.adb_path, "-s", self.devices[phone]["serial"], *map(str, args)]
        attempt = 0
        while True:
            # Device text is UTF-8. The host locale (cp1252 on Windows) turned a non-ASCII app line into
            # mojibake, so a logcat dump never equalled the byte-exact live capture of the same line.
            result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)
            delay = adb_retry_delay(result.returncode, result.stderr or result.stdout, args, attempt)
            if delay is None:
                break
            attempt += 1
            self.events.append({"device": phone, "action": "adb_retry", "attempt": attempt,
                                "returncode": result.returncode, "command": " ".join(map(str, args))[:120],
                                "delay_s": delay, "host_monotonic_ns": time.monotonic_ns()})
            time.sleep(delay)
        if result.returncode:
            raise RuntimeError(f"{phone}: ADB {args!r} exited {result.returncode}: {result.stderr.strip() or result.stdout.strip()}")
        return result.stdout.strip()

    def phone_time(self, phone):
        value = self.adb(phone, "shell", "date '+%m-%d %H:%M:%S.%3N'")
        if not re.fullmatch(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}", value):
            raise RuntimeError(f"{phone}: device clock format unavailable: {value!r}")
        return value

    def inventory(self, phone):
        props = dict(re.findall(r"\[([^]]+)\]: \[([^]]*)\]", self.adb(phone, "shell", "getprop")))
        keys = ["ro.product.manufacturer", "ro.product.model", "ro.build.version.sdk",
                "ro.build.fingerprint", "ro.board.platform", "ro.hardware", "ro.soc.model", "ro.soc.manufacturer"]
        apk = self.adb(phone, "shell", "pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
        digest = self.adb(phone, "shell", "sha256sum", apk).split()[0]
        return {"serial": self.devices[phone]["serial"], "node_prefix": self.devices[phone]["node_prefix"], "apk_sha256": digest,
                "properties": {key: props.get(key) or None for key in keys},
                "battery_at_start": self.adb(phone, "shell", "dumpsys", "battery"),
                "thermal_at_start": self.adb(phone, "shell", "dumpsys", "thermalservice"),
                "bluetooth_controller": "UNMEASURED - SoC is not proof of controller identity"}

    def prepare_app_files_dir(self, phone):
        """Create the app's external files folder as the app before pushing inputs into it.

        On a fresh install the folder does not exist and the first `adb push` creates it owned by
        `shell`; on Android 16 (A55, 2026-10-16) the app then read a full pushed raw fixture as 0 bytes
        and refused setup. `run-as` (debug builds) makes the folder with the app's uid; an existing
        shell-owned folder is reported for its owner to remove rather than deleted here. Where run-as cannot
        write /sdcard (HyperOS), one app launch creates the folder (DEBUG builds call getExternalFilesDir at startup)."""
        directory = f"/sdcard/Android/data/{PACKAGE}/files"
        try:
            owner = self.adb(phone, "shell", f"run-as {PACKAGE} mkdir -p {directory}; stat -c %U {directory}")
        except RuntimeError:
            # HyperOS (Xiaomi, Android 15) denies run-as on /sdcard; a DEBUG build creates its own folder at startup.
            self.adb(phone, "shell", "am", "start", "-W", "-n", COMPONENT)
            self.adb(phone, "shell", "am", "force-stop", PACKAGE)
            owner = self.adb(phone, "shell", f"stat -c %U {directory}")
            self.events.append({"device": phone, "action": "app_files_dir_created_by_launch"})
        owner = owner.splitlines()[-1].strip() if owner else ""
        if owner == "shell":
            raise RuntimeError(f"{phone}: {directory} is owned by shell (created by adb before the app), so the "
                               "app cannot read pushed inputs; remove the folder and rerun")
        self.events.append({"device": phone, "action": "app_files_dir_ready", "owner": owner or None})

    def stage_raw_fixture(self, phone):
        digest = self.raw_fixture["sha256"]
        remote = f"/sdcard/Android/data/{PACKAGE}/files/benchmark-raw-{digest}.yuv"
        existing = self.adb(phone, "shell", f"if [ -f {remote} ]; then sha256sum {remote}; fi")
        reused = bool(existing and existing.split()[0].lower() == digest)
        if not reused:
            self.adb(phone, "push", self.raw_fixture["path"], remote, timeout=180)
            verified = self.adb(phone, "shell", "sha256sum", remote)
            if not verified or verified.split()[0].lower() != digest:
                raise RuntimeError(f"{phone}: uploaded raw fixture hash mismatch")
        self.events.append({"device": phone, "action": "raw_fixture_reused" if reused else "raw_fixture_uploaded",
                            "time": self.phone_time(phone), "sha256": digest, "remote": remote})
        return remote

    def verify_replay_unchanged(self):
        if replay_fixture_metadata(self.case)["sha256"] != self.replay_fixture["sha256"]:
            raise RuntimeError("Replay input changed after the execution was frozen")

    def stage_replay_fixture(self, phone):
        entry = self.replay_fixture
        self.verify_replay_unchanged()
        remote = f"/sdcard/Android/data/{PACKAGE}/files/benchmark-replay-{entry['sha256']}.ivf"
        entry["staging"] = {"device": phone, "remote": remote, "status": "PENDING"}
        self.adb(phone, "push", entry["path"], remote, timeout=180)
        verified = self.adb(phone, "shell", "sha256sum", remote).split()
        if not verified or verified[0].lower() != entry["sha256"]:
            raise RuntimeError(f"{phone}: uploaded replay fixture hash mismatch")
        entry["staging"].update(status="VERIFIED", sha256=verified[0].lower())
        return remote

    def collect_media_traces(self, phone, role):
        remote_directory = f"/sdcard/Android/data/{PACKAGE}/files/media-traces"
        prefix = f"{self.trace_session}-{role}"
        audio_prefix = f"{self.trace_session}-audio-{role}"
        phase_pattern = re.compile("(?:" + re.escape(prefix) + "|" + re.escape(audio_prefix) + r")(?:-\d+)?")
        owned_pids = {log["pid"] for log in self.logs if log.get("device") == phone and type(log.get("pid")) is int}
        local_directory = self.output / phone / "media-traces"
        try:
            names = self.adb(phone, "shell", "ls", remote_directory).splitlines()
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            self.trace_artifacts.append({"device": phone, "role": role, "state": "UNMEASURED", "reason": str(error)})
            return
        phases = sorted({match[1] for name in names if
                         (match := re.fullmatch(r"(.+?)(?:-summary\.json|\.csv|\.ivf|\.wav)", name)) and
                         phase_pattern.fullmatch(match[1])})
        deadline = time.monotonic() + 3
        while any(phase + "-summary.json" not in names for phase in phases) and time.monotonic() < deadline:
            time.sleep(0.1)
            try:
                names = self.adb(phone, "shell", "ls", remote_directory).splitlines()
            except (RuntimeError, subprocess.TimeoutExpired):
                break
        if not phases:
            self.trace_artifacts.append({"device": phone, "role": role, "state": "UNMEASURED",
                                         "reason": "No exact owned session/role trace phase found"})
        for phase in phases:
            artifact = {"device": phone, "role": role, "phase": phase, "state": "UNMEASURED", "files": []}
            audio = phase.startswith(audio_prefix + "-")
            artifact["kind"] = "audio_pcm" if audio else "video_access_units"
            self.trace_artifacts.append(artifact)
            summary_name = phase + "-summary.json"
            finalized = summary_name in names
            failure = None
            if summary_name not in names:
                artifact["reason"] = "Trace phase was not finalized; summary absent; remote files retained"
            try:
                local_directory.mkdir(parents=True, exist_ok=True)
                verified_names = []
                extension = ".wav" if audio else ".ivf"
                for name in (summary_name, phase + ".csv", phase + extension):
                    if name not in names:
                        finalized = False
                        continue
                    remote = f"{remote_directory}/{name}"
                    remote_hash = self.adb(phone, "shell", "sha256sum", remote).split()[0].lower()
                    if not re.fullmatch(r"[0-9a-f]{64}", remote_hash):
                        raise RuntimeError("Media trace remote hash is unavailable")
                    local_name = hashlib.sha256(phase.encode("utf-8")).hexdigest()[:16] + name.removeprefix(phase)
                    local = local_directory / local_name
                    self.adb(phone, "pull", remote, str(local), timeout=120)
                    with local.open("rb") as handle:
                        local_hash = hashlib.file_digest(handle, "sha256").hexdigest()
                    if local_hash != remote_hash:
                        raise RuntimeError(f"Media trace pull hash mismatch: {name}")
                    if self.adb(phone, "shell", "sha256sum", remote).split()[0].lower() != remote_hash:
                        raise RuntimeError(f"Media trace changed during collection: {name}; local interrupted snapshot retained")
                    if name == summary_name:
                        summary = json.loads(local.read_text(encoding="utf-8-sig"))
                        if (summary.get("session") != self.trace_session or summary.get("role") != role or
                                summary.get("csv") != phase + ".csv" or summary.get("wav" if audio else "ivf") != phase + extension or
                                summary.get("phase", phase) != phase):
                            raise RuntimeError("Media trace summary does not match exact owned phase")
                        if type(summary.get("pid")) is not int or summary["pid"] not in owned_pids:
                            raise RuntimeError("Media trace summary PID does not belong to this phone execution; remote evidence retained")
                        artifact["summary"] = summary
                        if summary.get("finalized") is False or audio and summary.get("finalized") is not True:
                            finalized = False
                            failure = "Media trace finalization failed; remote files retained"
                    artifact["files"].append({"path": local.relative_to(self.output).as_posix(),
                                              "remote_name": name,
                                              "sha256": local_hash, "bytes": local.stat().st_size})
                    verified_names.append(name)
                if not finalized:
                    artifact.update(state="FAIL" if failure else "UNMEASURED", reason=failure or artifact.get("reason", "Incomplete trace files; remote evidence retained"))
                    continue
                artifact["state"] = "MEASURED"
                artifact["remote_cleanup"] = "complete"
                for name in verified_names:
                    self.adb(phone, "shell", "rm", "-f", f"{remote_directory}/{name}")
            except (RuntimeError, subprocess.TimeoutExpired, OSError, ValueError, IndexError) as error:
                if artifact["state"] == "MEASURED":
                    artifact["remote_cleanup"] = str(error)
                else:
                    artifact.update(state="FAIL" if finalized else "UNMEASURED", reason=str(error))

    def graceful_stop(self, phone, wait_s=3.0):
        """Ask the app to close its Bluetooth sockets, then force-stop. Killing a process with an
        LE CoC listener open leaked PSMs on older stacks (A15s l2cap_listen failures), and Android
        10-11 keep scanner/GATT registrations of a process killed while it scans (ASUS uncleanExits=99
        refilled its 32-slot table within minutes of a Bluetooth restart).

        DEBUG builds since the clean-shutdown control answer a broadcast (it never relaunches an exited
        app) with `BENCHMARK clean_shutdown: done | nonce=<n>` once nothing of the app is registered;
        older builds get the former am start control."""
        try:
            # Only a running app gets the control: am start would relaunch an exited app.
            if self.adb(phone, "shell", "pidof", PACKAGE, timeout=5).strip():
                done = self.clean_shutdown(phone, wait_s)
                if not done:
                    self.adb(phone, "shell", "am", "start", "-n", COMPONENT, "-W", "--es", "test_control", "shutdown", timeout=10)
                    for _ in range(int(wait_s / 0.25)):
                        if not self.adb(phone, "shell", "pidof", PACKAGE, timeout=5).strip():
                            break
                        time.sleep(0.25)
        except Exception:
            pass
        # The asynchronous shutdown intent can start a fresh process after the app already exited
        # (bmaraw-ab-call-9c121b30-run1: a new ASUS process 19 s after cleanup quarantined the phone),
        # so force-stop until no process remains.
        # A late relaunch can still land after the first empty pidof (mesh4-b9726176-loop3-1: ASUS
        # PID 21638 alive at the suite's cleanup check), so require it to stay empty for 3 s.
        try:
            self.adb(phone, "shell", "am", "force-stop", PACKAGE, timeout=5)
        except Exception:
            pass
        quiet = 0
        for _ in range(16):
            try:
                running = self.adb(phone, "shell", "pidof", PACKAGE, timeout=5).strip()
            except Exception:
                running = ""
            if running:
                quiet = 0
                try:
                    self.adb(phone, "shell", "am", "force-stop", PACKAGE, timeout=5)
                except Exception:
                    pass
            else:
                quiet += 1
                # A cancelled run must not keep sleeping; the suite still verifies pidof afterwards.
                cancelled = getattr(getattr(self, "cancel_event", None), "is_set", lambda: False)()
                if quiet >= 6 or cancelled is True:
                    break
            time.sleep(0.5)

    def clean_shutdown(self, phone, wait_s=3.0):
        """Send the DEBUG clean-shutdown broadcast and wait (at most wait_s) for its done line. True when it came."""
        nonce = f"r{int(time.time() * 1000) % 1_000_000_000}"
        done = False
        try:
            self.adb(phone, *clean_shutdown_broadcast_args(nonce), timeout=10)
            for _ in range(max(1, int(wait_s / 0.25))):
                dump = self.adb(phone, "shell", "logcat", "-d", "-s", "BluetoothVideo:I", "-t", "400", timeout=10)
                if clean_shutdown_done(dump, nonce):
                    done = True
                    break
                time.sleep(0.25)
        except Exception:
            done = False
        try:
            self.events.append({"device": phone, "action": "clean_shutdown", "done": done, "nonce": nonce})
        except Exception:
            pass
        return done

    def launch(self, phone, source=False):
        self.check_cancelled()
        if source and self.replay_fixture:
            self.verify_replay_unchanged()
        if source and (self.replay_fixture or self.case.get("raw_fixture_file") or self.reference_audio
                       or self.media_impairment):
            self.prepare_app_files_dir(phone)
        replay_remote = self.stage_replay_fixture(phone) if source and self.replay_fixture else None
        self.adb(phone, "shell", "am", "force-stop", PACKAGE)
        self.apply_user_settings(phone)
        try:
            self.adb(phone, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            self.adb(phone, "shell", "wm", "dismiss-keyguard")
        except RuntimeError as error:
            # Some OEMs restrict shell input even on an already unlocked phone.
            # Confirm that state instead of requiring a security-setting override.
            if "INJECT_EVENTS" not in str(error):
                raise
            policy = self.adb(phone, "shell", "dumpsys", "window", "policy")
            if not (re.search(r"^\s*showing=false\s*$", policy, re.M) and
                    "screenState=SCREEN_STATE_ON" in policy):
                raise RuntimeError(f"{phone}: unlock the phone; OEM policy blocks automatic wake") from error
            self.events.append({"device": phone, "action": "already_unlocked",
                                "time": self.phone_time(phone), "reason": "OEM restricts INJECT_EVENTS"})
        started = self.phone_time(phone)
        args = ["shell", "am", "start", "-W", "-n", COMPONENT]
        profile = self.case["profile"]
        app_default = user_launch.is_app_default(profile)
        # A user launch's app_default endpoint sends no resolution, rate, encoder, allocation, buffer or thermal extra:
        # the app resolves them from its stored video settings and its Automatic encoder choice, as a person's launch
        # does. The transport stays the case's own dimension.
        extras = {"test_transport": self.case["transport"]} if app_default else {
            "test_transport": self.case["transport"], "test_codec": profile.get("codec", "vp9"),
            "test_playback_delay": profile.get("playback", "balanced"),
            "test_thermal_reduction": profile.get("thermal_reduction", False)}
        if self.trace_session:
            extras["test_media_trace"] = self.trace_session
        if "direct_gatt_heartbeat" in self.case:
            extras["test_direct_gatt_heartbeat"] = self.case["direct_gatt_heartbeat"]
        if source:
            if "gatt_write_timing" in self.case:
                extras["test_gatt_write_timing"] = self.case["gatt_write_timing"]
            extras.update(test_auto_broadcast=True, test_role="send")
            if not app_default:
                extras.update(test_width=profile["width"], test_height=profile["height"], test_fps=profile["fps"],
                              test_bitrate_kbps=profile["bitrate_kbps"],
                              test_rate_control=profile.get("rate_control", "cbr"))
            if self.case.get("viewer_scan_mode"):
                extras["test_viewer_scan_mode"] = self.case["viewer_scan_mode"]
            if "audio_enabled" in self.case:
                extras["test_audio"] = self.case["audio_enabled"]
            if "stream_flush" in self.case:
                extras["test_stream_flush"] = self.case["stream_flush"]
            if replay_remote:
                extras["test_replay_ivf"] = replay_remote
            if self.case.get("raw_fixture_file"):
                extras["test_raw_yuv"] = self.stage_raw_fixture(phone)
                # The fixture's declared geometry (validated against the file size above): the app reads
                # frames at this size only and refuses an encoder that runs at another.
                extras["test_raw_width"] = profile["width"]
                extras["test_raw_height"] = profile["height"]
            if self.reference_audio:
                entry = self.reference_audio
                remote = f"/sdcard/Android/data/{PACKAGE}/files/benchmark-audio-{entry['sha256']}.wav"
                self.adb(phone, "push", entry["path"], remote, timeout=180)
                if self.adb(phone, "shell", "sha256sum", remote).split()[0].lower() != entry["sha256"]:
                    raise RuntimeError(f"{phone}: uploaded reference audio hash mismatch")
                extras["test_reference_audio"] = remote
            if self.media_impairment:
                entry = self.media_impairment
                remote = f"/sdcard/Android/data/{PACKAGE}/files/benchmark-impairment-{entry['sha256']}.json"
                self.adb(phone, "push", entry["path"], remote)
                if self.adb(phone, "shell", "sha256sum", remote).split()[0].lower() != entry["sha256"]:
                    raise RuntimeError(f"{phone}: uploaded impairment config hash mismatch")
                extras["test_media_impairment"] = remote
        else:
            if self.case.get("video_presentation_timing"):
                extras["test_video_presentation_timing"] = True
            if self.case.get("release_video_at_playout"):
                extras["test_release_video_at_playout"] = True
            node = next(item for item in self.case["receivers"] if item["phone"] == phone)
            extras.update(test_auto_select_stream=True, test_role="receive",
                          test_forward_received_streams=node.get("forwarding_enabled",
                              any(item["parent"] == phone for item in self.case["receivers"])),
                          test_stream_node=self.devices[node["parent"]]["node_prefix"])
            if "forwarder_gatt_priority" in node:
                extras["test_forwarder_gatt_priority"] = node["forwarder_gatt_priority"]
        extras.update(case_extras(self.case, phone))
        # Quiet speakers for automated runs (generated reference audio on four phones at once);
        # measured PCM is captured before this output gain. BENCHMARK_PLAYBACK_GAIN=1 restores full volume.
        extras["test_playback_gain"] = float(os.environ.get("BENCHMARK_PLAYBACK_GAIN", "0.01"))  # owner: minimum volume
        for key, value in extras.items():
            flag = "--ez" if isinstance(value, bool) else "--ei" if isinstance(value, int) else                 "--ef" if isinstance(value, float) else "--es"
            args.extend([flag, key, str(value).lower() if isinstance(value, bool) else str(value)])
        self.adb(phone, *args)
        pid = self.adb(phone, "shell", "pidof", PACKAGE)
        if not pid.isdigit():
            raise RuntimeError(f"{phone}: application did not start")
        self.pids[phone] = pid
        log_path = self.output / f"{phone}-{pid}.log"
        capture = LogCapture(self.adb_path, self.devices[phone]["serial"], pid, started, log_path, APP_LOG_FILTERS)
        self.captures.append((capture, capture))
        self.logs.append({"device": phone, "role": "sender" if source else "receiver", "pid": int(pid),
                          "start": started, "path": log_path.name})
        self.enlarge_log_buffer(phone)
        self.start_drop_log(phone, pid, started)
        self.start_stack_log(phone, started)
        self.expected_stopped.discard(phone)
        self.events.append({"device": phone, "action": "launch", "time": started,
                            "host_monotonic_ns": time.monotonic_ns()})
        self.check_applied_arms(phone)

    def stored_prefs(self, phone):
        """The app's stored Settings choices as text (run-as; a debug build only). No file yet reads as empty."""
        try:
            return self.adb(phone, "shell", user_launch.read_command(PACKAGE)).replace(chr(13) + chr(10), chr(10))
        except RuntimeError as error:
            if "No such file" in str(error):
                return ""
            raise

    def device_prefs_state(self, phone):
        """(present, sha256 of the file on the device) through the app's own user; the hash is None for no file."""
        present = self.adb(phone, "shell", user_launch.present_command(PACKAGE)).strip() == "present"
        if not present:
            return False, None
        digest = self.adb(phone, "shell", user_launch.hash_command(PACKAGE)).split()
        if not digest or not re.fullmatch(r"[0-9a-f]{64}", digest[0]):
            raise RuntimeError(f"{phone}: the device gave no SHA-256 of the stored preferences")
        return True, digest[0]

    def snapshot_stored_prefs(self, phone):
        """Keep the preferences file's exact bytes (or its absence) before the first write of this case.

        `adb exec-out` carries raw bytes: `adb shell` adds a carriage return per line on Windows hosts. The copy is
        accepted only when its SHA-256 equals the device's own."""
        if phone in self.prefs_snapshots:
            return
        present, device_hash = self.device_prefs_state(phone)
        data = None
        if present:
            command = [self.adb_path, "-s", self.devices[phone]["serial"], "exec-out", "run-as", PACKAGE, "cat",
                       user_launch.PACKAGE_PREFS_FILE]
            result = subprocess.run(command, capture_output=True, timeout=20)
            data = result.stdout
            if result.returncode or hashlib.sha256(data).hexdigest() != device_hash:
                raise RuntimeError(f"{phone}: the snapshot of the stored preferences differs from the device file")
        self.prefs_snapshots[phone] = {"existed": present, "data": data, "sha256": device_hash}

    def restore_user_settings(self):
        """Give every phone its preferences file back byte for byte (or delete the file it did not have).

        Runs in the case's cleanup after the app was force-stopped, whatever ended the case: a blocked case, a failure or
        an interruption after the write included. The restored file is verified by the device's own SHA-256; an
        unverified restore is recorded as UNVERIFIED and the suite quarantines that phone. The restore also drops any
        preference the app itself stored during the case: the file is returned exactly as it was found."""
        for phone, snapshot in self.prefs_snapshots.items():
            record = {"device": phone, "existed": snapshot["existed"], "sha256_before": snapshot["sha256"],
                      "status": "UNVERIFIED"}
            try:
                self.adb(phone, "shell", "am", "force-stop", PACKAGE, timeout=10)
                try:
                    running = self.adb(phone, "shell", "pidof", PACKAGE, timeout=5).strip()
                except RuntimeError:
                    running = ""  # pidof exits 1 when no process is left
                if running:
                    raise RuntimeError("the app is still running, so the file cannot be restored")
                for command in user_launch.restore_commands(PACKAGE, snapshot["data"]):
                    self.adb(phone, "shell", command)
                present, digest = self.device_prefs_state(phone)
                record["sha256_after"] = digest
                if present == snapshot["existed"] and digest == snapshot["sha256"]:
                    record["status"] = "RESTORED"
                else:
                    record["error"] = "the restored file differs from the snapshot" if present else "the file is gone"
            except Exception as error:
                record["error"] = str(error)[:300]
            self.user_settings_restored.append(record)
            self.events.append({"device": phone, "action": "user_settings_restored", "status": record["status"],
                                "host_monotonic_ns": time.monotonic_ns()})

    def settings_for(self, phone):
        """The resolved user settings one phone gets (the shared ones with its `user_settings.per_phone` over them)."""
        settings = user_launch.resolve(self.settings_case, phone)
        # A call launch reuses the stream shape with each endpoint as its "source"; only a stream's source goes live.
        stream = "caller" not in self.settings_case and not self.case.get("call_endpoint")
        goes_live = stream and phone == self.settings_case.get("source")
        return user_launch.with_live_disclosure(settings, goes_live)

    def video_choices_for(self, phone):
        """The stored video choices one phone gets (a call case states them for its app_default endpoints)."""
        return user_launch.video_settings(self.settings_case, phone)

    def apply_user_settings(self, phone):
        """Put a user-launch phone in the state a person's own Settings choice leaves it in, before the app starts.

        The app is stopped here (it rewrites its preferences from memory), the stored values the Settings screen writes
        are merged into its preferences file, and the file is read back. Anything but the exact stored values blocks the
        case before the dial. Other stored values and other apps are never touched.

        A call endpoint that takes the app's own video choice (`profile_source: app_default`) also has its stored video
        settings read back and recorded; one that is not the shipped default (or the case's stated choice) blocks the
        case with `video_setting_not_default`, since the call would then not be what a default person gets."""
        if not user_launch.is_user_launch(phone_tunables(self.case, phone)):
            return
        video = bool(self.case.get("call_endpoint")) and user_launch.is_app_default(self.case.get("profile"))
        settings = self.settings_for(phone)
        choices = self.video_choices_for(phone) if video else {}
        if not settings and not video:
            return
        writes = user_launch.pref_writes(settings)
        if video:
            writes.update(user_launch.video_writes(choices))
        record = {"device": phone, "settings": dict(settings), "stored": writes}
        try:
            if writes:
                self.snapshot_stored_prefs(phone)
                before = self.stored_prefs(phone)
                for command in user_launch.write_commands(PACKAGE, user_launch.merge_prefs(before, writes)):
                    self.adb(phone, "shell", command)
            stored = user_launch.read_values(self.stored_prefs(phone))
        except (RuntimeError, ValueError, user_launch.ET.ParseError) as error:
            record["error"] = str(error)[:300]
            self.user_setting_records.append(record)
            raise applied_arms.ArmBlocked(phone, "user_setting_not_stored", record["error"]) from error
        record["read_back"] = {key: stored.get(key) for key in writes}
        state = user_launch.video_state(choices, stored, user_launch.default_equivalent(phone)) if video else None
        if state:
            record["video"] = state
        self.user_setting_records.append(record)
        if writes:
            self.events.append({"device": phone, "action": "user_settings_stored", "stored": writes,
                                "host_monotonic_ns": time.monotonic_ns()})
        wrong = [f"{key} is {stored.get(key)}, wanted {value}" for key, value in writes.items()
                 if type(stored.get(key)) is not type(value) or stored.get(key) != value]
        if wrong:
            raise applied_arms.ArmBlocked(phone, "user_setting_not_stored", "read back: " + "; ".join(wrong))
        if state and state["differs"]:
            raise applied_arms.ArmBlocked(phone, "video_setting_not_default",
                                          "a stored video choice is not the shipped default, so the app would not "
                                          "resolve what a default person gets (state it in user_settings or reset "
                                          "the phone's Settings): " + "; ".join(state["differs"]))

    def check_applied_arms(self, phone, wait_s=10.0):
        """Read the app's launch_extras echo and effect lines for the extras just sent. An arm the app did not receive,
        or shows with another value, blocks the case before it is dialed; a missing echo is unknown, never a pass
        and never a block (an older build, or logd dropped it). A user launch's stored choices are checked the same
        way against the line the app logs about each (applied_arms.assess_settings)."""
        sent = {key: value for key, value in self.launch_sent.get(phone, {}).items()
                if key.startswith("test_") and key not in applied_arms.NOT_AN_ARM}
        settings = self.settings_for(phone) if user_launch.is_user_launch(phone_tunables(self.case, phone)) else {}
        if not sent and not settings:
            return
        log = self.logs[-1]
        path = self.output / log["path"]

        def read():
            return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""

        def settled(text):
            echo = applied_arms.parse_launch_echo(text)
            return echo is not None and all(
                applied_arms.effect_value(text, extra) is not None for extra in sent if extra in applied_arms.EFFECT_LINES
            ) and all(row["applied"] is not None for row in applied_arms.assess_settings(settings, text))
        deadline = time.monotonic() + wait_s
        while not settled(read()) and time.monotonic() < deadline:
            self.check_cancelled()
            time.sleep(0.25)
        text = read()
        if not settled(text):
            # The live stream can lag behind the app (A15s: 4-21 s): one bounded dump of the app's own buffer.
            text += chr(10) + chr(10).join(self.dump_log_lines(phone)[0])
        rows = applied_arms.assess(sent, text, bool(self.log_drops_now(phone)) if applied_arms.parse_launch_echo(text) is None else False)
        rows += applied_arms.assess_settings(settings, text)
        verdict = applied_arms.blocking(rows)
        self.arm_checks.append({"device": phone, "pid": log.get("pid"), "arms": rows,
                                **({"blocked": {"reason": verdict[0], "detail": verdict[1]}} if verdict else {})})
        if verdict:
            raise applied_arms.ArmBlocked(phone, *verdict)

    def recover_log_lines(self, phone):
        """Lines of the device log buffer that the live capture lacks (see dump_log_lines)."""
        return self.dump_log_lines(phone)[1]

    def dump_log_lines(self, phone):
        """Re-read the app's whole device log buffer for this execution's PID (`logcat -d --pid`).
        Returns (all dump lines, lines the live capture lacks); both empty when the buffer cannot be read.

        The live `adb logcat` stream is not a clock: on the Oppo A15s (Android 10/ColorOS) it ran 4-21 s
        behind the app's own timestamps (the S24 stream: 0.2 s; voice-speech-s24a15s-51cc8028), so a
        short evidence wait can expire before an on-time line reaches the host file. The dump reads
        logd directly and is the authority for what the app logged. Lines missing from the live capture
        go to a separate recovered file named in an event; the captured log is never rewritten."""
        pid = self.pids.get(phone)
        log = next((item for item in reversed(self.logs) if item["device"] == phone and item.get("pid") == int(pid or 0)), None)
        if not pid or log is None:
            return [], []
        try:
            dump = self.adb(phone, "logcat", "-d", f"--pid={pid}", "-v", "threadtime", *APP_LOG_FILTERS, timeout=30)
            captured = set((self.output / log["path"]).read_text(encoding="utf-8", errors="replace").splitlines())
        except Exception:
            return [], []
        every = [line for line in dump.splitlines() if line.strip() and not line.startswith("---------")]
        missing = [line for line in every if line not in captured]
        path = self.output / f"{phone}-{pid}-recovered.log"
        if path.exists():  # periodic polls re-dump: keep one copy of each recovered line
            captured |= set(path.read_text(encoding="utf-8", errors="replace").splitlines())
            missing = [line for line in missing if line not in captured]
        if missing:
            with path.open("a", encoding="utf-8") as handle:
                handle.write(chr(10).join(missing) + chr(10))
            self.events.append({"device": phone, "action": "log_lines_recovered", "path": path.name,
                                "lines": len(missing), "pid": int(pid),
                                "reason": "Device log buffer held lines the live capture lacked",
                                "host_monotonic_ns": time.monotonic_ns()})
        return every, missing

    def supervise_captures(self):
        """Restart a live logcat reader that died or went silent while its app is alive (once a second at
        most). The restart resumes from the reader's last timestamp, de-duplicated, and is an event."""
        now = time.perf_counter()
        if now - getattr(self, "last_supervised", float("-inf")) < 1.0:
            return
        self.last_supervised = now
        for log, (capture, _) in zip(self.logs, self.captures):
            phone = log["device"]
            if not isinstance(capture, LogCapture) or phone in self.expected_stopped:
                continue

            def alive(phone=phone, pid=str(log["pid"])):
                try:
                    return self.adb(phone, "shell", "pidof", PACKAGE, timeout=5) == pid
                except Exception:
                    return False

            record = capture.supervise(alive)
            if record:
                self.events.append({"device": phone, "action": "log_capture_restarted", "pid": log["pid"],
                                    "path": log["path"], "host_monotonic_ns": time.monotonic_ns(), **record})

    def merge_device_logs(self):
        """After the live captures stop, merge each PID's complete `logcat -d` into its physical log: only
        lines of that PID inside the capture window the live stream lacked, in timestamp order, under a
        header that states how many came from the dump. The scorer then sees what the app logged even when
        the live stream stalled or died. Failures leave the live log as captured and are recorded."""
        ends = {log["device"]: log.get("end") for log in self.logs}
        for entry, filters in [*((log, APP_LOG_FILTERS) for log in self.logs),
                               *((log, ()) for log in self.stack_logs if log.get("pid"))]:
            try:
                dump = self.adb(entry["device"], "logcat", "-d", f"--pid={entry['pid']}", "-v", "threadtime",
                                *filters, timeout=60)
                path = self.output / entry["path"]
                live = path.read_text(encoding="utf-8", errors="replace").splitlines()
                end = entry.get("end") or ends.get(entry["device"])
                merged, added = merge_dump(live, dump.splitlines(), entry["pid"], entry.get("start"), end)
                entry["live_lines"] = sum(1 for line in live if line and not line.startswith("---------"))
                entry["dump_merged_lines"] = len(added)
                if added:
                    header = (f"--------- merged {len(added)} line(s) from logcat -d --pid={entry['pid']}; "
                              f"the live capture held {entry['live_lines']}")
                    with path.open("w", encoding="utf-8", newline="\n") as handle:
                        handle.write("\n".join([header, *merged]) + "\n")
                    self.events.append({"device": entry["device"], "action": "log_dump_merged", "path": entry["path"],
                                        "pid": entry["pid"], "lines": len(added), "live_lines": entry["live_lines"],
                                        "host_monotonic_ns": time.monotonic_ns()})
            except Exception as error:
                self.events.append({"device": entry["device"], "action": "log_dump_merge_failed",
                                    "path": entry.get("path"), "reason": str(error)[:200],
                                    "host_monotonic_ns": time.monotonic_ns()})
        for log, (capture, _) in zip(self.logs, self.captures):
            if isinstance(capture, LogCapture):
                log["capture_restarts"] = len(capture.restarts)

    def enlarge_log_buffer(self, phone):
        """A 1 MiB main buffer lost the app's End acknowledgement on ASUS (logd prunes the chattiest
        app first). Raise it for the run and restore the recorded size during cleanup."""
        if phone in self.log_buffers:
            return
        try:
            report = self.adb(phone, "logcat", "-g", "-b", "main", timeout=5)
            match = re.search(r"ring buffer is (\d+)\s*(KiB|MiB|Kb|Mb|K|M)?", report)
            if not match:
                self.log_buffers[phone] = {"status": "UNKNOWN", "report": report[:200]}
                return
            size = int(match[1]) * {"MiB": 1 << 20, "Mb": 1 << 20, "M": 1 << 20}.get(match[2], 1 << 10)
            self.log_buffers[phone] = {"status": "UNCHANGED", "original_bytes": size}
            if size < LOG_BUFFER_BYTES:
                self.adb(phone, "logcat", "-G", f"{LOG_BUFFER_BYTES >> 20}M", timeout=5)
                self.log_buffers[phone]["status"] = "ENLARGED"
        except Exception as error:
            self.log_buffers[phone] = {"status": "UNKNOWN", "error": str(error)}

    def restore_log_buffers(self):
        for phone, item in self.log_buffers.items():
            if item.get("status") == "ENLARGED":
                try:
                    self.adb(phone, "logcat", "-G", f"{item['original_bytes'] >> 10}K", timeout=5)
                    item["status"] = "RESTORED"
                except Exception as error:
                    item.update(status="RESTORE_FAILED", error=str(error))

    def drop_dump_args(self, pid):
        return ("logcat", "-d", f"--pid={pid}", "-v", "threadtime", *LOG_DROP_FILTERS)

    def start_drop_log(self, phone, pid, started):
        """Capture this PID's liblog drop reports from the events buffer beside its app log. The app log
        cannot show a line logd never stored. Failure to start is recorded, never fatal."""
        path = self.output / f"{phone}-{pid}-liblog.log"
        entry = {"device": phone, "pid": int(pid), "start": started, "path": path.name,
                 "source": "logcat -b events -s liblog --pid",
                 "purpose": "Lines of this PID that logd dropped (a missing one-time line is then UNMEASURED)"}
        try:
            capture = LogCapture(self.adb_path, self.devices[phone]["serial"], pid, started, path, LOG_DROP_FILTERS)
        except Exception as error:
            self.drop_logs.append({**entry, "status": "UNMEASURED", "reason": f"capture did not start: {error}"[:200]})
            return
        self.drop_captures.append((capture, capture))
        self.drop_logs.append(entry)

    def log_drops_now(self, phone):
        """Drop reports of the phone's current app PID so far: its live liblog capture plus one bounded
        events-buffer dump (the live stream can lag seconds behind the app)."""
        pid = getattr(self, "pids", {}).get(phone)
        entry = next((item for item in reversed(getattr(self, "drop_logs", [])) if item["device"] == phone and
                      item["pid"] == int(pid or 0) and not item.get("status")), None)
        if not pid or entry is None:
            return []
        text = ""
        try:
            text = (self.output / entry["path"]).read_text(encoding="utf-8", errors="replace")
        except OSError:
            pass
        try:
            text += "\n" + self.adb(phone, *self.drop_dump_args(pid), timeout=15)
        except Exception:
            pass
        return parse_log_drops(text, pid)

    def log_loss_error(self, phone, message, expression, after=None):
        """LogLinesDropped when logd reported dropping this PID's lines after `after` (default: launch),
        where the missing line was due; None otherwise (the missing line stays a failure)."""
        pid = int(getattr(self, "pids", {}).get(phone) or 0)
        entry = next((item for item in reversed(getattr(self, "drop_logs", [])) if item["device"] == phone and
                      item["pid"] == pid), None)
        after = after or (entry or {}).get("start")
        found = drops_in_window(self.log_drops_now(phone), pid, after)
        if not found:
            return None
        record = {"device": phone, "pid": pid, "pattern": expression, "after": after, "reason": LOG_DROP_REASON,
                  "reports": found}
        self.events.append({"action": "log_lines_dropped", **record, "host_monotonic_ns": time.monotonic_ns()})
        lost = sum(item["dropped"] for item in found)
        return LogLinesDropped(f"{message}; {LOG_DROP_REASON}: {lost} line(s) in {len(found)} liblog report(s) "
                               f"after {after}, so the missing line is UNMEASURED", record)

    def finalize_drop_logs(self):
        """After every reader stopped: merge each PID's events-buffer dump into its liblog capture (the live
        stream can lag or attach late), then hash-bind the file and record its drop reports."""
        for entry in self.drop_logs:
            if entry.get("status"):
                continue
            phone, pid = entry["device"], entry["pid"]
            entry.setdefault("end", next((log.get("end") for log in self.logs
                                          if log["device"] == phone and log.get("pid") == pid), None))
            path = self.output / entry["path"]
            try:
                live = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
                try:
                    dump = self.adb(phone, *self.drop_dump_args(pid), timeout=30).splitlines()
                    entry["dump"] = "read"
                except Exception as error:
                    dump, entry["dump"] = [], f"failed: {error}"[:200]
                merged, added = merge_dump(live, dump, pid, entry.get("start"), entry.get("end"))
                if added or not path.exists():
                    path.write_text("".join(line + "\n" for line in merged), encoding="utf-8", newline="\n")
                entry["dump_merged_lines"] = len(added)
                with path.open("rb") as handle:
                    entry["sha256"] = hashlib.file_digest(handle, "sha256").hexdigest()
                entry["bytes"] = path.stat().st_size
                drops = parse_log_drops(path.read_text(encoding="utf-8", errors="replace"), pid)
                entry.update(drops=[{"time": item["time"], "dropped": item["dropped"]} for item in drops],
                             total_dropped=sum(item["dropped"] for item in drops),
                             status="MEASURED" if entry["dump"] == "read" else "LIVE_ONLY")
            except OSError as error:
                entry.update(status="UNMEASURED", reason=str(error)[:200])

    def collect_crash_logs(self):
        """Dump each participant's crash and system log buffers for the case window and keep them as evidence.

        A Java crash, an ANR (ActivityManager, system process) or a native crash (debuggerd) never reaches the
        per-PID app capture. A phone that cannot be read is recorded UNMEASURED, never as clean."""
        for phone in sorted(self.participants):
            logs = [log for log in self.logs if log["device"] == phone]
            starts = [log["start"] for log in logs if log.get("start")]
            ends = [log["end"] for log in logs if log.get("end")]
            entry = {"device": phone, "start": min(starts, default=None), "end": max(ends, default=None),
                     "path": f"{phone}-crash-events.log", "package": crash_events.PACKAGE,
                     "source": "logcat -b crash / -b system ActivityManager:E, dumped when the case ended"}
            self.crash_logs.append(entry)
            if not entry["start"]:
                entry.update(status="UNMEASURED", reason="no app log was started on this phone")
                continue
            text, failed = "", []
            for buffer, extra in crash_events.DUMPS:
                try:
                    dump = self.adb(phone, *crash_events.dump_args(buffer, extra, entry["start"]), timeout=20)
                    text += f"### logcat -b {buffer}\n{dump}\n"
                except Exception as error:
                    failed.append(f"-b {buffer}: {error}"[:200])
            if failed:
                entry.update(status="UNMEASURED", reason="; ".join(failed))
                continue
            path = self.output / entry["path"]
            path.write_text(text, encoding="utf-8", newline="\n")
            entry.update(status="MEASURED", bytes=path.stat().st_size, sha256=crash_events.sha256(path.read_bytes()),
                         events=crash_events.parse_crash_events(text, crash_events.PACKAGE, entry["start"], entry["end"]))

    def start_stack_log(self, phone, started):
        """Capture the Bluetooth process log: page/SDP, LL collision and scan-throttle evidence
        is logged there, not by the app. Failure to find it is recorded, never fatal."""
        for package in ("com.android.bluetooth", "com.google.android.bluetooth"):
            try:
                pid = self.adb(phone, "shell", "pidof", package, timeout=5)
            except Exception:
                continue
            if pid.isdigit():
                path = self.output / f"{phone}-bluetooth-{pid}.log"
                capture = LogCapture(self.adb_path, self.devices[phone]["serial"], pid, started, path)
                self.stack_captures.append((capture, capture))
                self.stack_logs.append({"device": phone, "process": package, "pid": int(pid),
                                        "start": started, "path": path.name,
                                        "purpose": "Diagnose Bluetooth stack setup/scan failures"})
                return
        self.stack_logs.append({"device": phone, "process": None, "start": started,
                                "reason": "Bluetooth process not found"})

    def wait_first_frame(self, phone):
        deadline = time.monotonic() + self.case.get("connection_timeout_s", 30)
        path = self.output / next(log["path"] for log in reversed(self.logs) if log["device"] == phone)
        while time.monotonic() < deadline:
            self.check_cancelled()
            text = path.read_text(encoding="utf-8")
            if "selection-to-first-surface" in text:
                return
            if "FATAL EXCEPTION" in text:
                raise RuntimeError(f"{phone}: application crashed during connection")
            self.supervise_captures()
            time.sleep(1)
        # A lagging live stream (dump_log_lines) may have delivered the line while the dump ran.
        dumped = self.dump_log_lines(phone)[0]
        live = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
        if "selection-to-first-surface" in live or any("selection-to-first-surface" in line for line in dumped):
            return
        reason = f"{phone}: no first surface within {self.case.get('connection_timeout_s', 30)}s connection timeout"
        lost, streams = stage_sequence_gaps(live.splitlines() + dumped)
        if lost:
            # The first-surface line may simply be among the lost lines: device logging, not the
            # app, is then unproven. Keep the timeout visible, but name the log loss.
            reason += (f"; device log lost {lost} app stage records across {streams} stream(s), "
                       "so the missing first surface is UNMEASURED log loss, not proof of no picture")
            self.events.append({"device": phone, "action": "log_loss", "time": self.phone_time(phone),
                                "missing_stage_records": lost, "streams": streams,
                                "reason": "Stage record sequence gaps in the device log"})
        dropped = self.log_loss_error(phone, reason, "selection-to-first-surface")
        self.events.append({"device": phone, "action": "connection_timeout", "time": self.phone_time(phone),
                            "reason": str(dropped or reason)})
        raise dropped or RuntimeError(reason)

    def wait_paused_connection(self, phone):
        deadline = time.monotonic() + self.case.get("connection_timeout_s", 30)
        parent = next(node["parent"] for node in self.case["receivers"] if node["phone"] == phone)
        identity = (f"original={self.devices[self.case['source']]['node_prefix']} | "
                    f"parent={self.devices[parent]['node_prefix']} |")
        path = self.output / next(log["path"] for log in reversed(self.logs) if log["device"] == phone)
        while time.monotonic() < deadline:
            self.check_cancelled()
            text = path.read_text(encoding="utf-8")
            current_authentication = text.rpartition("Authenticated stream identity: ")[2]
            if current_authentication.startswith(identity) and "Stream paused by broadcaster." in current_authentication:
                self.events.append({"device": phone, "action": "authenticated_paused_join",
                                    "time": self.phone_time(phone), "parent": parent})
                return
            if "FATAL EXCEPTION" in text:
                raise RuntimeError(f"{phone}: application crashed during paused connection")
            time.sleep(1)
        reason = (f"{phone}: no authenticated paused state via {parent} within "
                  f"{self.case.get('connection_timeout_s', 30)}s connection timeout")
        self.events.append({"device": phone, "action": "paused_connection_timeout", "time": self.phone_time(phone), "reason": reason})
        raise RuntimeError(reason)

    @staticmethod
    def beep():
        if sys.platform == "win32":
            for frequency in (1400, 900, 1400):
                ctypes.windll.kernel32.Beep(frequency, 150)

    def action(self, event):
        phone, action = event["phone"], event["action"]
        when = self.phone_time(phone)
        self.events.append({"device": phone, "action": action, "time": when,
                            "host_monotonic_ns": time.monotonic_ns()})
        print(f"{phone}: planned {action}", flush=True)
        if action in {"kill", "background", "pause"}:
            self.active_intervals.setdefault((phone, action), {"device": phone, "start": when, "action": action})
        if action == "kill":
            parents = {item["phone"]: item["parent"] for item in self.case["receivers"]}
            for child in sorted(parents):
                ancestor = parents[child]
                while ancestor is not None:
                    if ancestor == phone:
                        change = {"device": child, "action": "planned_parent_change",
                                  "time": self.phone_time(child), "removed_ancestor": phone}
                        self.topology_changes.append(change)
                        self.pending_planned_losses[child] = change
                        break
                    ancestor = parents.get(ancestor)
            self.adb(phone, "shell", "am", "force-stop", PACKAGE)
            self.expected_stopped.add(phone)
            next(log for log in reversed(self.logs) if log["device"] == phone)["end"] = self.phone_time(phone)
        elif action == "restart_receiver":
            self.launch(phone)
            if (self.case["source"], "pause") in self.active_intervals:
                self.wait_paused_connection(phone)
            else:
                self.wait_first_frame(phone)
        elif action == "background":
            self.adb(phone, "shell", "input", "keyevent", "KEYCODE_HOME")
        elif action == "foreground":
            self.adb(phone, "shell", "am", "start", "-n", COMPONENT)
        else:
            result = self.adb(phone, "shell", "am", "start", "-n", COMPONENT, "--es", "test_control", action)
            # The application marker, not successful am-start, proves the action ran.
            deadline = time.monotonic() + 5
            acknowledged = False
            while time.monotonic() < deadline:
                self.check_cancelled()
                marker = self.adb(phone, "logcat", "-d", f"--pid={self.pids[phone]}", "-T", when, "-s", "BluetoothVideo:I")
                if f"BENCHMARK control {action} applied" in marker:
                    acknowledged = True
                    break
                if f"BENCHMARK control {action} rejected" in marker:
                    raise RuntimeError(f"{phone}: application explicitly rejected control {action}")
                time.sleep(0.1)
            if not acknowledged:
                raise RuntimeError(f"{phone}: control {action} was not acknowledged: {result}")
            if action == "end":
                self.source_end_applied = True
        closes = {"restart_receiver": "kill", "foreground": "background", "resume": "pause"}
        key = (phone, closes.get(action))
        if key in self.active_intervals:
            interval = self.active_intervals.pop(key)
            interval["end"] = self.phone_time(phone)
            self.intervals.append(interval)

    def health_check(self, phone):
        if self.adb(phone, "get-state", timeout=15) != "device":
            raise RuntimeError(f"{phone}: wireless debugging disconnected")
        if phone in self.expected_stopped:
            return
        if self.adb(phone, "shell", "pidof", PACKAGE, timeout=5) != self.pids[phone]:
            raise RuntimeError(f"{phone}: application exited or restarted unexpectedly")
        log = next(log for log in reversed(self.logs) if log["device"] == phone)
        index = self.logs.index(log)
        self.supervise_captures()
        if self.captures[index][0].poll() is not None:
            raise RuntimeError(f"{phone}: log capture stopped unexpectedly")
        lines = (self.output / log["path"]).read_text(encoding="utf-8", errors="replace").splitlines()
        for line in lines[self.observed_lines.get(log["path"], 0):]:
            planned_loss = self.pending_planned_losses.get(phone)
            match = re.match(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
            since_loss = None
            if planned_loss and match:
                since_loss = (dt.datetime.strptime("2000-" + match[1], "%Y-%m-%d %H:%M:%S.%f") -
                              dt.datetime.strptime("2000-" + planned_loss["time"], "%Y-%m-%d %H:%M:%S.%f")).total_seconds()
                if since_loss >= 0 and "Receiver startup: selection-to-first-surface " in line:
                    self.pending_planned_losses.pop(phone)
                    planned_loss = None
            if phone == self.case["source"] and "Deterministic IVF replay finished" in line:
                raise RuntimeError(f"{phone}: replay exhausted before the measurement ended; not a Bluetooth freeze")
            if re.search(r"Connection lost|Detached downstream child|parent withdrew|FATAL EXCEPTION|Fatal signal|Incoming GATT stream stopped:|BAD_DECRYPT", line):
                planned = bool(planned_loss and since_loss is not None and
                               0 <= since_loss <= self.case.get("connection_timeout_s", 30) and
                               re.search(r"Connection lost|parent withdrew", line) and
                               not re.search(r"FATAL EXCEPTION|Fatal signal", line))
                event = {"device": phone, "message": line, "planned": planned}
                if planned:
                    event["removed_ancestor"] = planned_loss["removed_ancestor"]
                    self.pending_planned_losses.pop(phone)
                else:
                    self.beep()
                self.health_events.append(event)
                print(f"{'EXPECTED' if planned else 'ALERT'}: {phone}: {line}", flush=True)
        self.observed_lines[log["path"]] = len(lines)

    def close_intervals(self, phone, when):
        for key in [key for key in self.active_intervals if key[0] == phone]:
            interval = self.active_intervals.pop(key)
            interval["end"] = when
            self.intervals.append(interval)

    def run(self):
        self.output.mkdir(parents=True, exist_ok=False)
        manifest = {"schema_version": 1, "run_id": self.run_id, "scenario": self.case,
                    "media_trace_session": self.trace_session,
                    "expected_fps": self.case["profile"]["fps"],
                    "expected_bitrate_kbps": self.case["profile"]["bitrate_kbps"],
                    "expected_width": self.case["profile"]["width"], "expected_height": self.case["profile"]["height"],
                    "optical_results": [], "source_kind": "raw_fixture_live_encoder" if self.case.get("raw_fixture_file") else
                    "preencoded_video_only" if self.case.get("replay_file") else "live_camera"}
        if self.raw_fixture:
            manifest["source_fixture"] = self.raw_fixture
        if self.replay_fixture:
            manifest["replay_fixture"] = self.replay_fixture
        if self.reference_audio:
            manifest["reference_audio"] = self.reference_audio
        if self.media_impairment:
            manifest["media_impairment"] = self.media_impairment
        try:
            self.check_cancelled()
            with concurrent.futures.ThreadPoolExecutor() as executor:
                manifest["devices"] = dict(zip(sorted(self.participants), executor.map(self.inventory, sorted(self.participants))))
            if self.case.get("expected_apk_sha256") and any(
                item["apk_sha256"].lower() != self.case["expected_apk_sha256"].lower() for item in manifest["devices"].values()
            ):
                raise RuntimeError("A participant does not have the required frozen APK hash")
            self.launch(self.case["source"], source=True)
            launched = {self.case["source"]}
            pending = list(self.case["receivers"])
            while pending:
                self.check_cancelled()
                node = next(item for item in pending if item["parent"] in launched)
                self.launch(node["phone"])
                self.wait_first_frame(node["phone"])
                launched.add(node["phone"])
                pending.remove(node)
            start = time.monotonic()
            events = iter(sorted(self.case.get("actions", []), key=lambda item: item["at_s"]))
            next_event = next(events, None)
            deadline = start + self.case.get("duration_s", 60)
            last_health = start
            while time.monotonic() < deadline:
                self.check_cancelled()
                if next_event and time.monotonic() - start >= next_event["at_s"]:
                    self.action(next_event)
                    next_event = next(events, None)
                if time.monotonic() - last_health >= 3:
                    for phone in sorted(self.participants):
                        try:
                            self.health_check(phone)
                        except (RuntimeError, subprocess.TimeoutExpired) as error:
                            self.beep()
                            raise RuntimeError(f"{phone}: health check failed: {error}") from error
                    last_health = time.monotonic()
                time.sleep(0.25)
            if next_event is not None:
                raise RuntimeError(f"Scenario ended before action executed: {next_event}")
            if self.case["source"] not in self.expected_stopped and not any(
                event["action"] == "end" for event in self.case.get("actions", [])
            ):
                self.action({"phone": self.case["source"], "action": "end"})
                time.sleep(2)
        except Exception as error:
            manifest["runner_error"] = str(error) or type(error).__name__
            if isinstance(error, LogLinesDropped):
                manifest["runner_error_log_loss"] = error.record
            if isinstance(error, applied_arms.ArmBlocked):
                manifest["runner_blocked"] = error.record
            self.beep()
            print(f"FAIL: {manifest['runner_error']}", flush=True)
        finally:
            source = self.case["source"]
            if (manifest.get("runner_error") and source in self.pids and
                    source not in self.expected_stopped and not self.source_end_applied):
                try:
                    if self.adb(source, "shell", "pidof", PACKAGE, timeout=5) != self.pids[source]:
                        raise RuntimeError(f"{source}: owned source PID changed or missing; graceful End skipped")
                    self.action({"phone": source, "action": "end"})
                    time.sleep(2)
                except Exception as error:
                    manifest["trace_finalization_error"] = str(error)
            if self.trace_session:
                for phone in sorted(self.participants):
                    self.collect_media_traces(phone, "sender" if phone == self.case["source"] else "receiver")
            for phone in sorted(self.participants):
                try:
                    when = self.phone_time(phone)
                    self.close_intervals(phone, when)
                    for log in self.logs:
                        if log["device"] == phone:
                            log.setdefault("end", when)
                except Exception:
                    # An absent clock must stay absent, not be guessed from a different phone.
                    pass
            for process, handle in [*self.captures, *self.stack_captures, *self.drop_captures]:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                handle.close()
            self.restore_log_buffers()
            self.collect_crash_logs()
            for phone in sorted(self.participants):
                self.graceful_stop(phone)
            self.restore_user_settings()
            self.merge_device_logs()
            self.finalize_drop_logs()
            manifest.update(logs=self.logs, planned_intervals=self.intervals, selection_events=self.events,
                            health_events=self.health_events, topology_changes=self.topology_changes,
                            media_traces=self.trace_artifacts, bluetooth_stack_logs=self.stack_logs,
                            log_buffers=self.log_buffers, log_drops=self.drop_logs, crash_logs=self.crash_logs,
                            applied_arms=self.arm_checks,
                            **({"user_settings": self.user_setting_records} if self.user_setting_records else {}),
                            **({"user_settings_restored": self.user_settings_restored} if self.user_settings_restored else {}))
            (self.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        from score_device_run import score_manifest
        score = apply_log_loss(score_manifest(manifest, self.output), manifest, self.output)
        (self.output / "score.json").write_text(json.dumps(score, indent=2) + "\n", encoding="utf-8")
        print(f"Objective score: {score['overall_status']}", flush=True)
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devices", type=Path, required=True)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--case", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk-sha256", help="Require this frozen APK on every participant before launch")
    parser.add_argument("--output", type=Path, help="Explicit run-owned output directory; required for execution")
    parser.add_argument("--plan", action="store_true", help="Validate without touching phones")
    args = parser.parse_args()
    if not args.plan and args.output is None:
        parser.error("Execution requires --output with a run-owned directory")
    if args.output is not None:
        args.output = output_path(args.output, "runs")
    args.devices, args.suite = resolve(args.devices), resolve(args.suite)
    devices = json.loads(args.devices.read_text(encoding="utf-8"))["devices"]
    cases = json.loads(args.suite.read_text(encoding="utf-8"))["cases"]
    case = next((item for item in cases if item["id"] == args.case), None)
    if case is None:
        parser.error(f"Unknown scenario: {args.case}")
    if args.apk_sha256:
        case = {**case, "expected_apk_sha256": args.apk_sha256}
    validate_case(case, devices)
    if args.plan:
        print(json.dumps(case, indent=2))
        return
    manifest = Runner(args.adb, devices, case, args.output).run()
    print(f"Evidence: {args.output / 'manifest.json'}", flush=True)
    if manifest.get("runner_error"):
        sys.exit(1)


if __name__ == "__main__":
    main()
