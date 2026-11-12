"""Compile the release-gate selection: tagged user-launch calls on phone-disjoint lanes; no device I/O.

Owner rule (DECISIONS "First-principles audit (2026-11-10)", "Required phones and the gate", R3; the Realme left the
required phones on 2026-11-12): the release gate is a tagged selection of user-launch calls on the five required phones
(S24, S22, M31, ASUS, A15s) in under two hours; join time ships against the recorded interim line (90% of calls within
6 s, none over 15 s) measured from an idle callee (tools/release_join.py).

Five phones cannot make three disjoint pairs, nor two disjoint three-phone relay chains, so the selection has two windows
that follow each other; lanes inside a window share no phone, but a phone may sit out a window (every release phone is
in at least one window, and no other phone is):

* window "direct": two disjoint phone pairs run at the same time (lanes lane1, lane2). Every call is a direct user
  launch from an idle callee (`callee_idle_s: 12`), both call directions, every transport both phones offer, a share of
  Wi-Fi Direct calls on pairs the scope lists for Wi-Fi Direct, and Wi-Fi Aware calls only on the pair the scope lists
  for Aware (`carrier_eligibility` in tools/benchmark_scope.json, with its provenance). These are the calls the join
  gate counts;
* window "relay": one three-phone chain (lane relay1) runs on-demand relayed calls (`relay_shape: on_demand`,
  `user_settings.relayed_calls`) over the LE transports, both directions, while the pair lane3 (the M31 and the S24,
  who are not in the chain) runs ordinary direct user-launch calls from an idle callee next to it. The runner refuses
  `callee_idle_s` on a relayed call (the relay, callee and caller launch in their own order), so the relayed calls are
  not counted by the join gate; they are the selection's relayed-call evidence, and lane3's calls are counted.
  A lane entry may carry its own `quotas`, merged over QUOTAS for that lane.

Reproducibility. The selection id is a hash of every input that shapes a request (layout, quotas, duration, profiles,
encoders, capability file hashes, seed); a case id is the selection id, a descriptive request name and a replicate
number, so the same inputs give the same ids and a different request never reuses one. The run order of each lane is a
deterministic shuffle seeded by `seed:lane`; the suite file lists the cases in that order and every bundle selects its
cases in the same order (the runner executes a suite in file order; plan or run without `--state`, whose queue
re-prioritises by phone use, to keep the randomised order).

Every endpoint takes the app's own video choice (`profile_source: app_default`, tools/user_launch.py): the runner sends
no resolution, rate, encoder, allocation or buffer, so the call runs what a person's launch resolves from the stored
Settings and Automatic encoder choice, and each manifest records what the app chose. A phone whose stored video settings
differ from the shipped defaults (the shipped 426x240, or the phone's camera-mapped equivalent in
tools/video_default_equivalents.json, whose hash is part of the selection id) blocks its case before the dial. Benchmark-launch control arms are not part of this
selection: every case is `user_launch: true`.
"""
import argparse
import hashlib
import json
import random
from pathlib import Path

import acceptance_profiles as acceptance
import user_launch
from benchmark_layout import output_path, resolve as locate
from run_private_call_benchmark import validate_call_case

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "release-gate-selection-v1"
RELEASE_PHONES = ("s24", "s22", "m31", "asus", "a15s")  # owner 2026-11-12 (Realme retired); tools/benchmark_devices.json
CALLEE_IDLE_S = 12  # release_join.RELEASE_CALLEE_IDLE_S, just beyond the app's 10 s discovery fast window
LE_TRANSPORTS = ("gatt", "ble")
ALL_TRANSPORTS = ("gatt", "ble", "classic")  # fixed order: remainders go to the earlier ones (the shipped LE paths)

# Lane layout. Within a window the lanes share no phone; a phone may sit out a window, but every release phone is in at
# least one. A window may mix chain lanes and pair lanes. A lane's `quotas` override QUOTAS for that lane only: lane3 is
# the M31's only lane, so it keeps all three plain transports (6 calls) plus 4 Wi-Fi Direct calls.
LAYOUT = (
    {"window": "direct", "lanes": (
        {"lane": "lane1", "phones": ("s24", "s22"), "quotas": {"direct_lane_calls": 32}},
        {"lane": "lane2", "phones": ("asus", "a15s"), "quotas": {"direct_lane_calls": 32}})},
    {"window": "relay", "lanes": (
        {"lane": "relay1", "chain": ("s22", "asus", "a15s"), "quotas": {"relay_lane_calls": 6}},
        {"lane": "lane3", "phones": ("s24", "m31"),
         "quotas": {"direct_lane_calls": 10, "wifi_direct_calls": 4}})},
)
QUOTAS = {"direct_lane_calls": 36, "aware_calls": 6, "wifi_direct_calls_with_aware": 6, "wifi_direct_calls": 8,
          "relay_lane_calls": 6}
DURATION_S = 45  # media seconds per call; the gate decides on the join, the other families score the same call
CONNECTION_TIMEOUT_S = 35
# Every endpoint is a person's launch: the app's own video profile (the shipped Settings defaults, an Automatic encoder
# choice). The request names none of it; the runner records what the app chose.
APP_DEFAULT = {user_launch.PROFILE_SOURCE: user_launch.APP_DEFAULT}
# The shipped default video request (VideoSettings.DEFAULT) a transport must be able to carry for the call to be offered.
# It is part of the selection id, so a changed shipped default is a new selection.
APP_DEFAULT_VIDEO = {name: item[2] for name, item in user_launch.VIDEO_SETTINGS.items() if name.startswith("video_")}
APP_DEFAULT_KBPS = APP_DEFAULT_VIDEO["video_bitrate_kbps"]
USER_TUNABLES = {"reset_learned_state": True, "peer_labels": True, "names_audience": "everyone",
                 "peer_label_name": "Test", "user_launch": True}
ENVIRONMENT = ("Live Camera2 at a lit, moving scene (a monitor playing video, a window or a fan in front of every back "
               "camera: a dark or flat scene leaves the join's first real picture unmeasured); radio environment "
               "UNMEASURED; concurrent execution")
# Seconds a call occupies its lane beyond its media duration (setup, idle callee wait, cleanup, scoring gap), from the
# gaps between consecutive saved manifests of one run (benchmarks/runs, mtime) at 60 s or 90 s media:
TIMING_BASIS = {
    "direct": {"overhead_s": 76, "run": "jt-20261110r", "note": "24 user-launch GATT calls, 60 s, callee idle 12 s: median 136 s"},
    "carrier": {"overhead_s": 121, "run": "ul3-20261110n",
                "note": "6 user-launch Wi-Fi Direct calls, 90 s, no idle wait: median 199 s, plus the 12 s idle wait; "
                        "the Aware calls use the same figure (no user-launch Aware run exists)"},
    "relay_gatt": {"overhead_s": 90, "run": "ul2q27gatt-20261110j", "note": "3 relayed GATT calls, 60 s: median 149 s"},
    "relay_ble": {"overhead_s": 163, "run": "up2q27ble-20261110p", "note": "3 relayed BLE calls, 60 s: median 223 s"},
}
WINDOW_SETUP_S = 300  # preflight, ownership, volume-down and the first launch before the first call of a window
BUDGET_S = 6600  # 110 minutes: the two-hour gate with a ten-minute margin for a retry
# Two parallel pairs in the direct window instead of three: about 78 calls fit under two hours (the longest lane of each
# window sets its time); about 115 calls would need about 2.9 h. The lanes carry 32 + 32 + 10 + 6 calls.
TARGET_CALLS = 80


def read(path):
    return json.loads(locate(path, root=ROOT).read_text(encoding="utf-8-sig"))


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def spread(total, names):
    """{name: count} that adds to `total`, remainders to the earlier names."""
    base, extra = divmod(total, len(names))
    return {name: base + (index < extra) for index, name in enumerate(names)}


def check_codecs(capabilities, caller, callee):
    """The app picks the encoder, so only prove the pair can interoperate: each phone has an encoder its peer decodes."""
    for phone, peer in ((caller, callee), (callee, caller)):
        codecs = {encoder["codec"] for encoder in capabilities[phone].get("encoders", [])}
        if not codecs & set(capabilities[peer].get("decoders", [])):
            raise ValueError(f"{phone}: none of its encoders ({', '.join(sorted(codecs))}) is decoded by {peer}")


def offered_transports(capabilities, phones, candidates):
    """The candidate transports every phone of the route can carry at the app's default video rate."""
    return [t for t in candidates if all(
        capabilities[p].get("transport_max_kbps", {}).get(t, 0) >= APP_DEFAULT_KBPS for p in phones)]


def carrier_phones(scope, carrier):
    """The phones the scope declares for a carrier (carrier_eligibility, with its provenance in the scope)."""
    return set(scope.get("carrier_eligibility", {}).get(carrier, ()))


def request(capabilities, caller, callee, transport, duration_s, relay=None, carrier=None):
    """One exact call request (no id, no tag)."""
    check_codecs(capabilities, caller, callee)
    profiles = {phone: dict(APP_DEFAULT) for phone in (caller, callee)}
    case = {"caller": caller, "callee": callee, "profiles": profiles, "transport": transport, "duration_s": duration_s,
            "connection_timeout_s": CONNECTION_TIMEOUT_S, "media_trace": relay is None, "call_pipeline_timing": True,
            "actions": [], "tunables": dict(USER_TUNABLES)}
    if relay:
        case.update(relay=relay, relay_shape="on_demand", user_settings={"relayed_calls": True})
    else:
        case["callee_idle_s"] = CALLEE_IDLE_S
        if carrier:
            case.update(carrier=carrier, user_settings={"wifi": True})
    return case


def kind_of(case):
    if case.get("relay"):
        return "relay_" + case["transport"]
    return "carrier" if case.get("carrier") else "direct"


def estimate_s(case):
    return case["duration_s"] + TIMING_BASIS[kind_of(case)]["overhead_s"]


def lane_requests(scope, capabilities, lane, window, duration_s, quotas):
    """The ordered-by-construction list of (label, request) one lane runs; both directions get equal counts."""
    quotas = {**quotas, **lane.get("quotas", {})}
    requests = []
    if "chain" in lane:
        first, middle, last = lane["chain"]
        transports = offered_transports(capabilities, lane["chain"], LE_TRANSPORTS)
        per_direction = quotas["relay_lane_calls"] // 2
        for caller, callee in ((first, last), (last, first)):
            for transport, count in spread(per_direction, transports).items():
                requests += [(f"{caller}-via-{middle}-to-{callee}-{transport}",
                              request(capabilities, caller, callee, transport, duration_s, relay=middle))] * count
        return requests
    a, b = lane["phones"]
    aware = {a, b} <= carrier_phones(scope, "wifi_aware") and "wifi_aware" in scope.get("carriers", ())
    wifi = {a, b} <= carrier_phones(scope, "wifi_direct") and "wifi_direct" in scope.get("carriers", ())
    n_aware = quotas["aware_calls"] if aware else 0
    n_wifi = (quotas["wifi_direct_calls_with_aware"] if aware else quotas["wifi_direct_calls"]) if wifi else 0
    n_direct = quotas["direct_lane_calls"] - n_aware - n_wifi
    if n_direct < 0 or n_direct % 2 or n_aware % 2 or n_wifi % 2:
        raise ValueError("Per-lane call counts must be even and fit the lane: both directions get equal counts")
    for caller, callee in ((a, b), (b, a)):
        for transport, count in spread(n_direct // 2, offered_transports(capabilities, (a, b), ALL_TRANSPORTS)).items():
            requests += [(f"{caller}-calls-{callee}-{transport}",
                          request(capabilities, caller, callee, transport, duration_s))] * count
        for carrier, total in (("wifi_aware", n_aware), ("wifi_direct", n_wifi)):
            for transport, count in spread(total // 2, offered_transports(capabilities, (a, b), LE_TRANSPORTS)).items():
                requests += [(f"{caller}-calls-{callee}-{carrier.replace('_', '-')}-{transport}",
                              request(capabilities, caller, callee, transport, duration_s, carrier=carrier))] * count
    return requests


def build(scope, capabilities, provenance, registry, *, date, seed, duration_s=DURATION_S, quotas=None,
          budget_s=BUDGET_S, phones=RELEASE_PHONES, equivalents_path=None):
    """The selection: (suite dict, {lane: bundle dict}, selection manifest). Raises when a rule cannot hold."""
    if set(scope["required_phones"]) != set(phones):
        raise ValueError("The scope's required_phones differ from the release phones: refresh tools/benchmark_scope.json "
                         f"(scope {sorted(scope['required_phones'])}, release phones {sorted(phones)})")
    quotas = {**QUOTAS, **(quotas or {})}
    # The camera-mapped sizes the runner accepts as the shipped default (tools/video_default_equivalents.json): a different
    # table is a different request, so its hash is part of the selection id.
    equivalents = user_launch.equivalents_sha256(equivalents_path)
    lanes = [(item["window"], lane) for item in LAYOUT for lane in item["lanes"]]
    placed = set()
    for item in LAYOUT:
        seen = [p for lane in item["lanes"] for p in lane.get("phones", lane.get("chain"))]
        if len(seen) != len(set(seen)):
            raise ValueError(f"Lanes of window {item['window']} share a phone")
        placed |= set(seen)
    if placed != set(phones):
        raise ValueError("Every release phone must be in at least one window, and no other phone "
                         f"(placed {sorted(placed)}, release phones {sorted(phones)})")
    identity = {"schema": SCHEMA, "layout": LAYOUT, "quotas": quotas, "duration_s": duration_s, "seed": seed,
                "profile_source": APP_DEFAULT, "app_default_video": APP_DEFAULT_VIDEO, "callee_idle_s": CALLEE_IDLE_S,
                "video_default_equivalents": equivalents,
                "timing": {k: v["overhead_s"] for k, v in TIMING_BASIS.items()},
                "capabilities": {p: provenance[p]["sha256"] for p in sorted(phones)},
                "carriers": {c: sorted(carrier_phones(scope, c)) for c in ("wifi_aware", "wifi_direct")}}
    selection_id = f"rg-{date.replace('-', '')}-" + hashlib.sha256(canonical(identity).encode()).hexdigest()[:8]
    cases, bundle_lanes, counts = [], {}, {}
    for window, lane in lanes:
        label = lane["lane"]
        planned = lane_requests(scope, capabilities, lane, window, duration_s, quotas)
        replicate, entries = {}, []
        for name, body in planned:
            key = canonical(body)
            replicate[key] = replicate.get(key, 0) + 1
            case = {"id": f"{selection_id}-{name}-r{replicate[key]}", **body, "state": "NOT RUN",
                    "environment": ENVIRONMENT,
                    "eligibility": f"Release-gate selection {selection_id}: user-launch call, lane {label} ({window} window)",
                    "release_gate": {"selection": selection_id, "lane": label, "window": window}}
            entries.append(case)
        random.Random(f"{seed}:{label}").shuffle(entries)
        cases += entries
        route = list(lane.get("phones", lane.get("chain")))
        bundle_lanes[label] = {"window": window, "phones": route, "case_ids": [c["id"] for c in entries]}
        counts[label] = entries
    ids = [c["id"] for c in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("Case ids must be unique")
    for case in cases:
        validate_call_case(case, registry)
        route = [case["caller"], case["callee"], *([case["relay"]] if case.get("relay") else [])]
        problems = user_launch.blockers(case, route)
        if problems:
            raise ValueError(f"{case['id']}: {problems}")
    estimates = {}
    for label, entries in counts.items():
        estimates[label] = sum(estimate_s(c) for c in entries)
    window_times = {}
    for item in LAYOUT:
        longest = max(estimates[lane["lane"]] for lane in item["lanes"])
        window_times[item["window"]] = {"setup_s": WINDOW_SETUP_S, "longest_lane_s": longest,
                                        "total_s": WINDOW_SETUP_S + longest}
    total_s = sum(w["total_s"] for w in window_times.values())
    if total_s > budget_s:
        raise ValueError(f"Estimated {total_s} s exceeds the {budget_s} s budget")
    if not 0.9 * TARGET_CALLS <= len(cases) <= 1.1 * TARGET_CALLS:
        raise ValueError(f"{len(cases)} calls is not about {TARGET_CALLS}")
    profile = scope.get("acceptance_profile", acceptance.V3)
    group_extra = ({"acceptance_profile": profile, "required_families": list(acceptance.families_for(profile))}
                   if profile != acceptance.V3 else {})
    suite_rel = f"benchmarks/catalog/release-gate-{date.replace('-', '')}/release-gate-suite.json"
    suite = {"schema_version": 1, "release_gate_selection": selection_id,
             "capability_bases": {p: provenance[p] for p in sorted(phones)}, "cases": cases}
    bundles = {}
    for label, info in bundle_lanes.items():
        bundles[label] = {"schema_version": 1, "release_gate_selection": selection_id, "lane": label,
                          "window": info["window"], "phones": info["phones"],
                          "estimated_minutes": round(estimates[label] / 60, 1),
                          "suites": [{"path": suite_rel, "execution": "concurrent", **group_extra,
                                      "case_ids": info["case_ids"]}]}

    def composition(entries):
        table = {}
        for case in entries:
            route = case.get("relay") and f"{case['caller']}>{case['relay']}>{case['callee']}" or f"{case['caller']}>{case['callee']}"
            key = (case.get("carrier") or ("relayed" if case.get("relay") else "bluetooth"), case["transport"], route)
            table[key] = table.get(key, 0) + 1
        return [{"route": r, "carrier": c, "transport": t, "calls": n} for (c, t, r), n in sorted(table.items())]
    selection = {
        "schema": SCHEMA, "id": selection_id, "date": date, "seed": seed, "state": "NOT RUN",
        "owner_rule": "DECISIONS 'First-principles audit (2026-11-10)', Required phones and the gate (R3)",
        "tag": {"field": "release_gate", "value": {"selection": selection_id}},
        "profile_source": user_launch.APP_DEFAULT, "app_default_video": APP_DEFAULT_VIDEO,
        "video_default_equivalents": {"path": "tools/video_default_equivalents.json", "sha256": equivalents,
                                      "rule": "a phone's stored size passes as the shipped default when it is 426x240 or the "
                                              "camera-mapped pair listed here for that phone; nothing else"},
        "carrier_eligibility": {c: sorted(carrier_phones(scope, c)) for c in ("wifi_aware", "wifi_direct")},
        "calls": len(cases), "counted_by_join_gate": sum(1 for c in cases if "callee_idle_s" in c),
        "duration_s": duration_s, "callee_idle_s": CALLEE_IDLE_S,
        "windows": [{"window": item["window"], "lanes": [lane["lane"] for lane in item["lanes"]],
                     **window_times[item["window"]]} for item in LAYOUT],
        "estimated_total_s": total_s, "estimated_total_minutes": round(total_s / 60, 1), "budget_s": budget_s,
        "timing_basis": TIMING_BASIS, "window_setup_s": WINDOW_SETUP_S,
        "lanes": {label: {**{k: v for k, v in info.items() if k != "case_ids"}, "calls": len(info["case_ids"]),
                         "estimated_s": estimates[label], "estimated_minutes": round(estimates[label] / 60, 1),
                         "composition": composition(counts[label])} for label, info in bundle_lanes.items()},
        "order": f"deterministic shuffle per lane seeded by '{seed}:<lane>'; the suite lists cases in run order",
        "files": {"suite": suite_rel.rsplit("/", 1)[1], "bundles": {l: f"release-gate-{l}-bundle.json" for l in bundles}},
        "preconditions": ["Frozen user-launch build; APK SHA256 verified on every phone of the window",
                          "Back cameras see a lit, moving scene (a dark scene leaves the join UNMEASURED)",
                          "Every phone's stored video settings are the shipped defaults (no manual video choice, no Advanced "
                          "settings; the stored size may be the phone's camera-mapped equivalent of 426x240, as the app's own "
                          "mode picker stores it): the runner reads them back and blocks a case on any other value "
                          "(video_setting_not_default)",
                          "No phone has Battery Saver on: the app withholds Wi-Fi then, so a Wi-Fi case is blocked in the "
                          "preflight (battery_saver_on); Battery Saver is the owner's setting",
                          "Phones on 5 GHz Wi-Fi or Wi-Fi off; S24 is the owner's phone and may be unplugged"],
        "limits": ["Relayed calls cannot use callee_idle_s and are not counted by the join gate",
                   "A user-launch relayed call takes the app's shortest route: it counts only when the callee is out of "
                   "the caller's direct range (confirm_on_demand_route fails a direct sighting)",
                   "The encoder, size, FPS and rate are the app's own choice and are not requested: each manifest records "
                   "them (app_chosen_profiles), and the scorer matches no request for them. The timing basis below was "
                   "measured on explicit 320x240 calls"],
    }
    return suite, bundles, selection


def commands(selection, devices="tools/benchmark_devices.json"):
    """The plan and run commands per lane (no --state: its queue re-prioritises and would undo the random order)."""
    result = {}
    for label, info in selection["lanes"].items():
        base = (f"--suite benchmarks/catalog/release-gate-{selection['date'].replace('-', '')}/"
                f"release-gate-{label}-bundle.json --devices {devices} --phones {','.join(info['phones'])}")
        result[label] = {
            "plan": f"python -B tools/run_benchmark_suite.py plan {base} --apk-sha256 APK_SHA256 --parallel 1 "
                    f"--output benchmarks/catalog/release-gate-{selection['date'].replace('-', '')}/plan-{label}",
            "run": f"python -B tools/run_benchmark_suite.py run {base} --owner OWNER --apk-sha256 APK_SHA256 "
                   f"--adb PATH_TO_ADB --parallel 1 --output benchmarks/runs/release-gate-{selection['date'].replace('-', '')}-{label}"}
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scope", type=Path, default=ROOT / "tools/benchmark_scope.json")
    parser.add_argument("--date", required=True, help="YYYY-MM-DD; part of the selection id and the output name")
    parser.add_argument("--seed", type=int, help="Order seed; default is the date as YYYYMMDD")
    parser.add_argument("--duration-s", type=int, default=DURATION_S)
    parser.add_argument("--output", type=Path, help="New catalog directory; default benchmarks/catalog/release-gate-DATE")
    args = parser.parse_args(argv)
    scope = read(args.scope)
    registry = read(scope["registry"])["devices"]
    capabilities, provenance = {}, {}
    for phone in RELEASE_PHONES:
        path = locate(scope["capabilities"][phone], root=ROOT)
        capabilities[phone] = json.loads(path.read_text(encoding="utf-8-sig"))
        provenance[phone] = {"path": scope["capabilities"][phone], "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                             "fingerprint": capabilities[phone].get("fingerprint"),
                             "node_prefix": capabilities[phone].get("node_prefix")}
        if capabilities[phone].get("node_prefix") != registry[phone]["node_prefix"]:
            raise ValueError(f"{phone}: capability identity differs from the registry; refresh before execution")
    seed = args.seed if args.seed is not None else int(args.date.replace("-", ""))
    suite, bundles, selection = build(scope, capabilities, provenance, registry, date=args.date, seed=seed,
                                      duration_s=args.duration_s)
    selection["commands"] = commands(selection)
    output = output_path(args.output or Path(f"benchmarks/catalog/release-gate-{args.date.replace('-', '')}"), "catalog", root=ROOT)
    output.mkdir(parents=True, exist_ok=False)

    def write(name, data):
        (output / name).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    write("release-gate-suite.json", suite)
    for label, bundle in bundles.items():
        write(f"release-gate-{label}-bundle.json", bundle)
    write("selection.json", selection)
    print(json.dumps({"id": selection["id"], "calls": selection["calls"], "counted": selection["counted_by_join_gate"],
                      "estimated_minutes": selection["estimated_total_minutes"], "output": str(output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
