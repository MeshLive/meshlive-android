"""Compile retained capabilities into bounded NOT RUN suites and symbolic obligations; no device I/O."""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path
import acceptance_profiles as acceptance
from benchmark_layout import resolve as locate, output_path

from run_device_benchmark import validate_case
from run_private_call_benchmark import validate_call_case

ROOT = Path(__file__).resolve().parents[1]
TIERS = ("low", "high")
CALL_TIERS = (("low", "low"), ("high", "high"), ("low", "high"), ("high", "low"))

# Wi-Fi carrier dimension (Design WF). Absent carrier = the earlier Bluetooth-only request (identity unchanged).
CARRIERS = ("wifi_aware", "wifi_aware_wider", "wifi_direct", "wifi_aware_native")
NATIVE_PROFILE_TRANSPORT = "gatt"  # a Wi-Fi-native hop has no Bluetooth transport; the lowest cap (no rate is raised, D8) is its reference
SWEEP_FAMILIES = acceptance.SWEEP_FAMILIES[acceptance.V3]  # v3 default; scope acceptance_profile selects another

# Fractions scale only the declared control schedule, never media settings.
LIFECYCLE_TEMPLATES = {
    "broadcast_controls": [(0.10, "source", "pause"), (0.17, "source", "resume"),
        (0.24, "source", "background"), (0.30, "source", "foreground"),
        (0.37, "leaf", "background"), (0.44, "leaf", "foreground"), (0.90, "source", "end")],
    "receiver_rejoin": [(0.20, "leaf", "kill"), (0.34, "leaf", "restart_receiver"),
        (0.90, "source", "end")],
    "chain_controls": [(0.10, "source", "pause"), (0.17, "source", "resume"),
        (0.24, "source", "background"), (0.30, "source", "foreground"),
        (0.37, "middle", "background"), (0.44, "middle", "foreground"),
        (0.51, "leaf", "background"), (0.58, "leaf", "foreground"), (0.90, "source", "end")],
    "chain_leaf_rejoin": [(0.20, "leaf", "kill"), (0.34, "leaf", "restart_receiver"),
        (0.90, "source", "end")],
    "chain_middle_rejoin": [(0.20, "middle", "kill"), (0.34, "middle", "restart_receiver"),
        (0.90, "source", "end")],
    "call_controls": [(0.10 + i * 0.05, role, action)
        for offset, role in ((0, "caller"), (7, "callee"))
        for i, action in enumerate(("mute", "unmute", "video_off", "video_on", "flip", "background", "foreground"), offset)]
        + [(0.90, "caller", "end")],
    "call_decline": [],
    "call_cancel": [],
}


def read(path):
    return json.loads(resolve(path).read_text(encoding="utf-8-sig"))


def resolve(path):
    return locate(path, root=ROOT)


def request_id(request, prefix="request"):
    # Provenance, selection order and unrelated phones never enter request identity.
    encoded = json.dumps(request, sort_keys=True, separators=(",", ":"))
    return prefix + "-" + hashlib.sha256(encoded.encode()).hexdigest()[:24]


def profile(capability, encoder, allocation, transport, tier, playback):
    # Same low/high extreme rule as the retained standard acceptance generator.
    modes = [m for m in encoder.get("modes", []) if m.get("fps") and
             m["bitrate_kbps"] <= capability.get("transport_max_kbps", {}).get(transport, 0)]
    if not modes:
        return None
    order = lambda m: (m["bitrate_kbps"], m["width"] * m["height"], m["width"], m["height"])
    mode = (min if tier == "low" else max)(modes, key=order)
    return {"codec": encoder["name"], "width": mode["width"], "height": mode["height"],
            "fps": (min if tier == "low" else max)(mode["fps"]),
            "bitrate_kbps": mode["bitrate_kbps"], "rate_control": allocation,
            "playback": playback, "thermal_reduction": False}


def settings_profiles(capability, encoder, transport, buffers):
    """Cover offered individual values and feasible boundaries, not their Cartesian product."""
    maximum = min(capability.get("transport_max_kbps", {}).get(transport, 0),
                  {"classic": 1500, "ble": 1200, "gatt": 1100}[transport])
    exported = {(m["width"], m["height"], f, m["bitrate_kbps"])
                for m in encoder.get("modes", []) for f in m.get("fps", [])}
    modes = sorted(m for m in exported if all(isinstance(v, int) and not isinstance(v, bool) and v > 0 for v in m)
                   and m[3] <= maximum)
    allocations = sorted(set(encoder.get("rate_controls", [])) & {"automatic", "cbr", "vbr", "cbr_fd"})
    playback = sorted(set(buffers) & {"low", "balanced", "stable", "auto"})
    proof = {"candidate_mode_count": len(modes), "excluded_mode_choices": len(exported) - len(modes),
             "transport_maximum_kbps": maximum,
             "unsupported_allocations": sorted(set(encoder.get("rate_controls", [])) - set(allocations)),
             "unsupported_buffers": sorted(set(buffers) - set(playback)),
             "profile_product_count": len(modes) * len(allocations) * len(playback),
             "single_value_coverage_complete": False, "remaining_product_state": "UNMEASURED",
             "boundaries": {}, "selected_modes": []}
    if not modes or not allocations or not playback:
        proof["omitted_profile_products"] = proof["profile_product_count"]
        return [], proof
    area = lambda m: m[0] * m[1]
    boundaries = {
        "low_fps_high_rate": max(modes, key=lambda m: (-m[2], m[3], area(m), m)),
        "large_geometry_low_rate": max(modes, key=lambda m: (area(m), -m[3], m[2], m)),
        "maximum_pixel_rate": max(modes, key=lambda m: (area(m) * m[2], m[3], m))}
    features = lambda m: {("geometry", m[:2]), ("fps", m[2]), ("rate", m[3])}
    candidates = {}
    for m in modes:
        for feature in features(m):
            candidates.setdefault(feature, []).append(m)
    expected = set(candidates)
    chosen = set(boundaries.values())
    uncovered = expected - set().union(*(features(m) for m in chosen))
    while uncovered:
        feature = min(uncovered, key=lambda f: (len(candidates[f]), f))
        selected = max(candidates[feature], key=lambda m: (len(features(m) & uncovered), area(m) * m[2], m[3], m))
        chosen.add(selected)
        uncovered -= features(selected)
    anchor = boundaries["maximum_pixel_rate"]
    allocation = "cbr" if "cbr" in allocations else allocations[0]
    buffer = "balanced" if "balanced" in playback else playback[0]
    # Every selected mode uses one declared baseline; allocations and buffers vary linearly at the anchor.
    settings = {(m, allocation, buffer) for m in chosen} | {(anchor, a, buffer) for a in allocations} | {
        (anchor, allocation, b) for b in playback}
    profiles = [{"codec": encoder["name"], "width": m[0], "height": m[1], "fps": m[2],
                 "bitrate_kbps": m[3], "rate_control": a, "playback": b, "thermal_reduction": False}
                for m, a, b in sorted(settings)]
    proof.update(single_value_coverage_complete=True, boundaries={name: list(m) for name, m in boundaries.items()},
                 selected_modes=[list(m) for m in sorted(chosen)],
                 offered={"geometries": [list(g) for g in sorted({m[:2] for m in modes})],
                          "fps": sorted({m[2] for m in modes}), "bitrate_kbps": sorted({m[3] for m in modes}),
                          "allocations": allocations, "buffers": playback},
                 omitted_profile_products=proof["profile_product_count"] - len(profiles))
    return profiles, proof


def offered(capability, peers, transport, blocked, phone):
    choices = []
    for encoder in sorted(capability.get("encoders", []), key=lambda e: e["name"]):
        reason = None
        if any(encoder["codec"] not in peer.get("decoders", []) for peer in peers):
            reason = "A receiving peer does not offer the matching decoder codec"
        elif not any(m.get("fps") and m["bitrate_kbps"] <=
                     capability.get("transport_max_kbps", {}).get(transport, 0)
                     for m in encoder.get("modes", [])):
            reason = "No exported geometry/FPS/rate mode for this transport"
        for allocation in sorted(encoder.get("rate_controls", [])):
            if reason:
                blocked.append({"phone": phone, "encoder": encoder["name"], "allocation": allocation,
                                "tiers": list(TIERS), "buffers": capability.get("playback", []),
                                "state": "NOT RUN", "eligibility": "BLOCKED", "reason": reason})
            else:
                choices.append((encoder, allocation))
    return choices


def topology_space(phones, transports):
    n, t = len(phones), len(transports)
    def item(kind, length, factor, formula, executable=False):
        return {"kind": kind, "role_slots": length, "phones": phones, "transports": transports,
                "assignment_count": math.perm(n, length) * factor if n >= length else 0,
                "formula": formula, "state": "NOT RUN",
                "eligibility": "UNMEASURED" if executable else "BLOCKED",
                "reason": "Only the bounded selected subset is compiled; execution unmeasured" if executable else
                          "Symbolic obligation; this compiler supplies no executable adapter",
                "role_order": "Distinct ordered role slots; branch leaves and concurrent lanes are labelled"}
    result = [item("direct", 2, t, "P(n,2)*t", True),
              item("direct_call", 2, t, "P(n,2)*t", True),
              item("homogeneous_chain", 3, t, "P(n,3)*t", True),
              item("source_fanout", 3, t, "P(n,3)*t"),
              item("viewer_branch", 4, t, "P(n,4)*t"),
              item("diamond", 4, t, "P(n,4)*t"),
              item("opaque_call_relay", 3, t, "P(n,3)*t"),
              item("two_disjoint_calls", 4, t * t, "P(n,4)*t^2")]
    for length in range(3, n + 1):
        result.append(item("mixed_chain", length, t ** (length - 1) - t,
                           f"P(n,{length})*(t^{length - 1}-t)"))
        if length >= 4:
            result.append(item("homogeneous_chain", length, t, f"P(n,{length})*t"))
            result.append(item("multi_relay_call", length, t, f"P(n,{length})*t"))
    return result


def setting_space(capabilities, transports):
    # Count exported mode/FPS choices; never expand mode*allocation*buffer*heat products.
    return {phone: {"state": "NOT RUN", "measurement": "UNMEASURED",
        "buffers": caps.get("playback", []), "thermal": [False, True],
        "camera_lenses_exported": [caps.get("camera_lens", "unknown")], "encoders": [
        {"name": e["name"], "codec": e["codec"], "allocations": e.get("rate_controls", []),
         "buffers": caps.get("playback", []), "thermal": [False, True],
         "mode_rows": len(e.get("modes", [])),
         "geometry_count": len({(m["width"], m["height"]) for m in e.get("modes", [])}),
         "fps_values": sorted({f for m in e.get("modes", []) for f in m.get("fps", [])}),
         "bitrate_values_kbps": sorted({m["bitrate_kbps"] for m in e.get("modes", [])}),
         "profile_products_by_transport": {t: sum(len(m.get("fps", [])) for m in e.get("modes", [])
             if m["bitrate_kbps"] <= caps.get("transport_max_kbps", {}).get(t, 0)) *
             len(e.get("rate_controls", [])) * len(caps.get("playback", [])) * 2 for t in transports}}
        for e in caps.get("encoders", [])]} for phone, caps in capabilities.items()}


def lifecycle_profile(capability, choices, transport, buffers):
    if not choices or not buffers:
        return None
    encoder, allocation = min(choices, key=lambda c: (c[1] != "cbr", c[0]["name"], c[1]))
    playback = "balanced" if "balanced" in buffers else sorted(buffers)[0]
    return profile(capability, encoder, allocation, transport, "low", playback)


def lifecycle_actions(template, phones, duration):
    roles = {"source": phones[0], "caller": phones[0], "callee": phones[1], "leaf": phones[-1]}
    if len(phones) == 3:
        roles["middle"] = phones[1]
    return [{"at_s": round(fraction * duration, 3), "phone": roles[role], "action": action}
            for fraction, role, action in LIFECYCLE_TEMPLATES[template]]


def input_requirements(cases):
    """Native external derivatives are optional new requests, not fabricated fixtures."""
    dimensions = {}
    for kind, requests in cases.items():
        for case in requests:
            profiles = case.get("profiles", {case.get("source"): case.get("profile")})
            for phone, selected in profiles.items():
                key = (selected["width"], selected["height"], selected["fps"])
                row = dimensions.setdefault(key, {"width": key[0], "height": key[1], "fps": key[2],
                    "timebase": {"numerator": 1, "denominator": key[2]},
                    "frame_bytes": key[0] * key[1] * 3 // 2, "profile_uses": 0, "source_phones": set()})
                row["profile_uses"] += 1
                row["source_phones"].add(phone)
    for row in dimensions.values():
        row["source_phones"] = sorted(row["source_phones"])
        row["eligibility"] = "SUPPORTED_LAYOUT" if not row["width"] % 2 and not row["height"] % 2 else "BLOCKED"
        row["reason"] = "Requested native geometry/FPS; content and live viability unmeasured" if row["eligibility"] == "SUPPORTED_LAYOUT" else "Raw I420 requires even geometry; no silent resizing"
    return {"schema": "benchmark-input-requirements-v1", "state": "NOT RUN",
        "scope": "Optional external synthetic/natural raw inputs for the exact selected geometries; generated suites use live Camera2 until new fixture requests are declared",
        "raw_contract": {"format": "I420", "bit_depth": 8, "chroma": "4:2:0",
            "plane_order": ["Y", "U", "V"], "layout": "Tightly packed planar, no row padding",
            "fps": "Integer native FPS with 1/FPS timestamps; no interpolation or speed change",
            "size_rule": "width*height*3/2*frame_count", "loops": "Declare seams, iteration and unique source IDs",
            "other_formats": "Explicit adapter or declared conversion required; never treated as supported automatically"},
        "dimensions": [dimensions[key] for key in sorted(dimensions)],
        "required_sidecars": ["Lossless native reference and SHA256/frame count for every file",
            "Stable pattern ID, generator/version, seed, full parameters and license provenance",
            "Per-frame ID, rational source timestamp and expected event schedule",
            "Color range, primaries, matrix, transfer, chroma location and conversion history",
            "Visible marker bounds/checksum and a declared content-scoring ROI/mask",
            "Audio reference sample rate/channels/hash and event sample indices, when present"],
        "integration": {"broadcast": "raw_fixture_file", "call": "raw_fixture_files keyed by endpoint alias",
            "identity": "A supplied fixture, input type or event sequence creates a new exact request; prior Camera2 results are not inherited",
            "status": "UNMEASURED", "reason": "No media generated/imported or marker/audio/ROI adapter executed"},
        "separate_scopes": ["Natural motion with descriptive SI/TI and measured selected-encoder load", "Live Camera2 controls",
            "Network impairments", "Optical latency", "Acoustic/A-V synchronization", "Endurance"]}


def retired_requirements(scope):
    """The scope's inline ``retired_requirements`` plus every ``retired_requirement_files`` entry.

    A retirement file is ``{"reason": "...", "ids": ["request-id", ...]}``: one reason for many request IDs
    that no longer exist (for example after a capability refresh changed the sampled profiles), so the scope
    stays small. Each ID becomes an ordinary ``{"id", "reason"}`` row in the inventory.
    """
    rows = list(scope.get("retired_requirements", []))
    for name in scope.get("retired_requirement_files", []):
        group = read(name)
        reason, ids = group.get("reason"), group.get("ids")
        if (not isinstance(reason, str) or not reason.strip() or not isinstance(ids, list) or
                any(not isinstance(item, str) or not item.strip() for item in ids)):
            raise ValueError(f"Retirement file {name} needs one explicit reason and a list of request identities")
        rows.extend({"id": item, "reason": reason} for item in ids)
    return rows


def compile_inventory(scope, capabilities, selected=None, provenance=None, problems=None):
    required = list(scope["required_phones"])
    # Extension phones (scope `extension_phones`, for example a registered phone that is not on the bench today) are
    # compiled like required ones, so their exact case IDs persist, but a run never needs them: `required_phones`
    # names only the fixed phones. Without the key the compiled set is the required set, as before.
    extension = list(scope.get("extension_phones", []))
    universe = required + extension
    selected = list(universe if selected is None else selected)
    if (len(set(universe)) != len(universe) or len(set(selected)) != len(selected) or
            not set(selected) <= set(universe)):
        raise ValueError("Required/extension/selected aliases must be unique; selected phones must belong to scope")
    acceptance.families_for(scope.get("acceptance_profile"))  # Unknown profile versions raise.
    transports = scope.get("transports", ["classic", "ble", "gatt"])
    if not transports or len(set(transports)) != len(transports) or set(transports) - {"classic", "ble", "gatt"}:
        raise ValueError("Transports must be unique supported runner names")
    carriers = scope.get("carriers", [])
    if not isinstance(carriers, list) or len(set(carriers)) != len(carriers) or set(carriers) - set(CARRIERS):
        raise ValueError("Carriers must be unique names from " + ", ".join(CARRIERS))
    carrier_eligibility = scope.get("carrier_eligibility", {})
    if (not isinstance(carrier_eligibility, dict) or set(carrier_eligibility) - set(CARRIERS) or
            any(not isinstance(v, list) or not set(v) <= set(universe) for v in carrier_eligibility.values())):
        raise ValueError("Carrier eligibility must list required or extension phone aliases per carrier")
    retired = retired_requirements(scope)
    if any(not isinstance(r.get("id"), str) or not r["id"].strip() or
           not isinstance(r.get("reason"), str) or not r["reason"].strip() for r in retired):
        raise ValueError("Every retired requirement needs its prior identity and an explicit reason")
    retired_ids = {r["id"] for r in retired}
    if len(retired_ids) != len(retired):
        raise ValueError("Retired requirement identities must be unique")
    sweep_scope = scope.get("settings_sweep")
    if sweep_scope is not None:
        duration = sweep_scope.get("duration_s", 45)
        sweep_pairs = sweep_scope.get("pairs", [])
        if (isinstance(duration, bool) or not isinstance(duration, (int, float)) or
                not math.isfinite(duration) or duration <= 0 or duration >= scope.get("duration_s", 90)):
            raise ValueError("Settings sweep duration must be positive and shorter than the acceptance request")
        if (not isinstance(sweep_pairs, list) or any(not isinstance(p, list) or len(p) != 2 or p[0] == p[1] or
                not set(p) <= set(universe) for p in sweep_pairs) or len({tuple(p) for p in sweep_pairs}) != len(sweep_pairs)):
            raise ValueError("Settings sweep pairs must be explicit unique directed pairs from required or extension phones")
    lifecycle_duration = scope.get("lifecycle_duration_s", 120)
    if isinstance(lifecycle_duration, bool) or not isinstance(lifecycle_duration, (int, float)) or not math.isfinite(lifecycle_duration) or lifecycle_duration < 120:
        raise ValueError("Lifecycle duration must be at least 120 seconds for the declared control/recovery schedules")
    provenance, problems = provenance or {}, problems or {}
    suites = {"broadcast": {}, "call": {}, "chain": {}}
    rows = []
    blocked_requests = set()
    # Synthetic registry only validates structure; real identity/live checks remain runner-owned.
    registry = {p: {"serial": "offline-" + p, "node_prefix": f"{i + 1:012x}"} for i, p in enumerate(universe)}

    def add(kind, request, row):
        case_id = request_id(request, kind)
        if case_id in retired_ids:
            row["retired_case_ids"].append(case_id)
            return None
        try:
            (validate_call_case if kind == "call" else validate_case)(request, registry)
        except ValueError as error:
            if case_id not in blocked_requests:
                row["blocked_profiles"].append({"case_id": case_id, "request": request, "state": "NOT RUN",
                    "eligibility": "BLOCKED", "reason": "Runner cannot execute this exact request: " + str(error)})
                blocked_requests.add(case_id)
            return None
        suites[kind][case_id] = {"id": case_id, **request, "state": "NOT RUN", "family": kind,
                               "eligibility": "Retained offered modes only; live readiness, load and quality unmeasured"}
        row["case_ids"].append(case_id)
        return case_id

    def add_lifecycle(kind, request, row, phones, templates):
        for template in templates:
            updated = {**request, "duration_s": lifecycle_duration}
            if template in {"call_decline", "call_cancel"}:
                updated["setup_action"] = template.removeprefix("call_")
            else:
                updated["actions"] = lifecycle_actions(template, phones, lifecycle_duration)
            case_id = add(kind, updated, row)
            if case_id:
                row["lifecycle_case_ids"][template] = case_id

    for kind, length in (("broadcast", 2), ("call", 2), ("chain", 3)):
        for phones in itertools.permutations(universe, length):
            for transport, carrier in itertools.product(transports, (None, *carriers)):
                if carrier == "wifi_aware_native" and transport != NATIVE_PROFILE_TRANSPORT:
                    continue
                extra = {"carrier": carrier} if carrier else {}
                assignment = {"kind": kind, "phones": list(phones), "transport": transport, **extra}
                row = {"id": request_id(assignment, "obligation"), **assignment, "state": "NOT RUN",
                       "eligibility": "UNMEASURED", "case_ids": [], "blocked_profiles": [],
                       "lifecycle_case_ids": {}, "retired_case_ids": []}
                rows.append(row)
                if carrier == "wifi_aware_native":
                    row.update(eligibility="BLOCKED", reason="wifi_aware_native needs Bluetooth turned off on both phones, which the runner cannot do; run it by hand (package A8 phone steps)")
                    continue
                missing = [p for p in phones if p not in capabilities or p in problems]
                if missing:
                    row.update(eligibility="BLOCKED", reason="; ".join(
                        f"{p}: {problems.get(p, 'missing capability export')}" for p in missing))
                    continue
                if not set(phones) <= set(selected):
                    row["reason"] = "Participants not all selected; physical availability is unmeasured"
                    continue
                if carrier:
                    unlisted = [p for p in phones if p not in carrier_eligibility.get(carrier, [])]
                    if unlisted:
                        row.update(eligibility="BLOCKED", reason=f"{carrier} is not declared for {', '.join(unlisted)} in the scope; census pending or not supported")
                        continue
                source = phones[0]
                caps = capabilities[source]
                choices = offered(caps, [capabilities[p] for p in phones[1:]], transport,
                                  row["blocked_profiles"], source)
                if kind == "call":
                    peer = phones[1]
                    other = offered(capabilities[peer], [caps], transport, row["blocked_profiles"], peer)
                    left = [(e, a, b) for e, a in choices for b in sorted(caps.get("playback", []))]
                    right = [(e, a, b) for e, a in other for b in sorted(capabilities[peer].get("playback", []))]
                    # Linear endpoint coverage, not the enormous independent profile cross-product.
                    pairs = [(c, right[0]) for c in left] + [(left[0], c) for c in right] if left and right else []
                    for first, second in pairs:
                        for tiers in CALL_TIERS:
                            profiles = {p: profile(capabilities[p], e, a, transport, tier, b)
                                        for p, (e, a, b), tier in zip(phones, (first, second), tiers)}
                            request = {"caller": source, "callee": peer, "transport": transport,
                                       "profiles": profiles, "setup_action": "accept",
                                       "duration_s": scope.get("duration_s", 90), "media_trace": True, **extra}
                            add(kind, request, row)
                    selected_profiles = {
                        source: lifecycle_profile(caps, choices, transport, caps.get("playback", [])),
                        peer: lifecycle_profile(capabilities[peer], other, transport, capabilities[peer].get("playback", []))}
                    if all(selected_profiles.values()):
                        add_lifecycle(kind, {"caller": source, "callee": peer, "transport": transport,
                            "profiles": selected_profiles, "setup_action": "accept", "media_trace": True, **extra},
                            row, phones, ("call_controls", "call_decline", "call_cancel"))
                else:
                    buffers = sorted(set(caps.get("playback", [])).intersection(
                        *(set(capabilities[p].get("playback", [])) for p in phones[1:])))
                    for buffer in sorted(set(caps.get("playback", [])) - set(buffers)):
                        row["blocked_profiles"].append({"phone": source, "buffer": buffer,
                            "tiers": list(TIERS), "state": "NOT RUN", "eligibility": "BLOCKED",
                            "reason": "Shared broadcast buffer is not offered by every receiving peer"})
                    selected_profile = lifecycle_profile(caps, choices, transport, buffers)
                    if kind == "chain":
                        choices = choices[:1]
                        buffers = ["balanced"] if "balanced" in buffers else buffers[:1]
                    for encoder, allocation in choices:
                        for tier, playback in itertools.product(TIERS, buffers):
                            request = {"source": source, "transport": transport,
                                       "profile": profile(caps, encoder, allocation, transport, tier, playback),
                                       "receivers": [{"phone": child, "parent": parent}
                                                     for parent, child in zip(phones, phones[1:])],
                                       "duration_s": scope.get("duration_s", 90), "media_trace": True, **extra}
                            add(kind, request, row)
                    if selected_profile:
                        add_lifecycle(kind, {"source": source, "transport": transport,
                            "profile": selected_profile, "receivers": [{"phone": child, "parent": parent}
                                for parent, child in zip(phones, phones[1:])], "media_trace": True, **extra}, row, phones,
                            ("chain_controls", "chain_leaf_rejoin", "chain_middle_rejoin") if kind == "chain" else
                            ("broadcast_controls", "receiver_rejoin"))
                row["case_ids"] = sorted(set(row["case_ids"]))
                row["retired_case_ids"] = sorted(set(row["retired_case_ids"]))
                row.update(eligibility="ELIGIBLE" if row["case_ids"] else "BLOCKED",
                           reason="Selected retained-compatible requests; live readiness/quality unmeasured" if row["case_ids"] else
                                  "All compatible requests explicitly retired" if row["retired_case_ids"] else
                                  "No compatible offered encoder/allocation/buffer profile; no substitution")
    sweep = None
    if sweep_scope is not None:
        sweep = {"schema": "benchmark-settings-sweep-v1", "duration_s": duration, "pairs": sweep_pairs,
                 "state": "NOT RUN", "required_families": list(acceptance.sweep_families_for(scope.get("acceptance_profile"))), "case_ids": [], "coverage": [],
                 "purpose": "Short diagnostic of individual settings and feasible boundary interactions; not full acceptance or endurance",
                 "selection": "Fixed directed pairs. Cover least frequent uncovered native-mode values, preferring tuples that cover other missing values, plus low-FPS/high-rate, large-geometry/low-rate and maximum-pixel-rate boundaries. Allocation and shared buffer choices vary linearly at the maximum-pixel-rate anchor; no full product claim."}
        assignments = {(tuple(r["phones"]), r["transport"]): r for r in rows if r["kind"] == "broadcast" and "carrier" not in r}
        for source, receiver in sorted(sweep_pairs):
            for transport in sorted(transports):
                row = assignments[((source, receiver), transport)]
                base = {"phones": [source, receiver], "transport": transport, "state": "NOT RUN"}
                if source not in capabilities or receiver not in capabilities or source in problems or receiver in problems:
                    sweep["coverage"].append({**base, "eligibility": "BLOCKED", "reason": row["reason"]})
                    continue
                if source not in selected or receiver not in selected:
                    sweep["coverage"].append({**base, "eligibility": "UNMEASURED", "reason": row["reason"]})
                    continue
                caps, peer = capabilities[source], capabilities[receiver]
                buffers = sorted(set(caps.get("playback", [])) & set(peer.get("playback", [])))
                if not caps.get("encoders"):
                    sweep["coverage"].append({**base, "eligibility": "BLOCKED", "reason": "Source offers no encoder; no substitution"})
                for encoder in sorted(caps.get("encoders", []), key=lambda e: e["name"]):
                    profiles, proof = settings_profiles(caps, encoder, transport, buffers)
                    coverage = {**base, "encoder": encoder["name"], "codec": encoder["codec"], **proof,
                                "candidate_case_ids": [], "case_ids": []}
                    sweep["coverage"].append(coverage)
                    if encoder["codec"] not in peer.get("decoders", []):
                        coverage.update(eligibility="BLOCKED", single_value_coverage_complete=False,
                                        omitted_profile_products=proof["profile_product_count"],
                                        reason="Receiving peer does not offer the matching decoder codec")
                        continue
                    for selected_profile in profiles:
                        request = {"source": source, "transport": transport, "profile": selected_profile,
                                   "receivers": [{"phone": receiver, "parent": source}],
                                   "duration_s": duration, "media_trace": True,
                                   "diagnostic_scope": "settings_sweep"}
                        coverage["candidate_case_ids"].append(request_id(request, "broadcast"))
                        case_id = add("broadcast", request, row)
                        if case_id:
                            coverage["case_ids"].append(case_id)
                    coverage["candidate_case_ids"].sort()
                    coverage["case_ids"].sort()
                    coverage.update(eligibility="ELIGIBLE" if coverage["case_ids"] else "BLOCKED",
                                    reason="Exact offered short diagnostics; remaining products/endurance unmeasured" if coverage["case_ids"] else
                                           "No executable offered mode/allocation/shared buffer; no substitution",
                                    single_value_coverage_complete=proof["single_value_coverage_complete"] and
                                        len(coverage["case_ids"]) == len(profiles),
                                    omitted_profile_products=proof["profile_product_count"] - len(coverage["case_ids"]))
                    sweep["case_ids"].extend(coverage["case_ids"])
                row["case_ids"] = sorted(set(row["case_ids"]))
                row["retired_case_ids"] = sorted(set(row["retired_case_ids"]))
        sweep["case_ids"] = sorted(set(sweep["case_ids"]))
    inventory = {"schema": "benchmark-inventory-v1", "state": "NOT RUN", "required_phones": required,
        **({"extension_phones": extension} if extension else {}),
        "selected_phones": selected, "availability": {p: {"selected": p in selected, "status": "UNMEASURED",
            "reason": "Offline selection is not live connectivity or ownership proof"} for p in universe},
        "capability_provenance": provenance, "assignments": rows,
        "retired_requirements": retired,
        **({"carriers": carriers, "carrier_eligibility": carrier_eligibility} if carriers else {}),
        **({"acceptance_profile": scope["acceptance_profile"]} if scope.get("acceptance_profile", acceptance.V3) != acceptance.V3 else {}),
        "lifecycle_templates": {name: {"state": "NOT RUN", "source_kind": "live_camera_bidirectional" if name.startswith("call_") else "live_camera",
            "duration_s": lifecycle_duration, "schedule": [{"fraction": fraction, "role": role, "action": action}
                for fraction, role, action in events],
            "profile_selection": "Low offered mode, prefer CBR then encoder name; Balanced if shared/offered, else first offered buffer. Exact selected profile is in each case",
            "prerequisites": ["Current live identity/capability/permission checks; generated actions are not acknowledgements"] +
                (["Front-camera capability and flip preservation remain unmeasured from back-camera exports"] if name == "call_controls" else [])}
            for name, events in LIFECYCLE_TEMPLATES.items()},
        "required_topology_space": topology_space(universe, transports),
        "selected_topology_space": topology_space(selected, transports),
        "full_setting_space": setting_space(capabilities, transports),
        "combination_rules": {
            "direct": "Sum each compatible source encoder's profile_products_by_transport over ordered peers; receiver-only buffer products need independent-viewer adapter",
            "calls": "For each ordered pair/transport, multiply independent compatible outgoing endpoint profile counts; finite cases cover each endpoint choice linearly at low/low, high/high, low/high and high/low",
            "chains": "P(n,k) ordered role assignments times homogeneous t or mixed t^(k-1)-t; setting products remain symbolic",
            "other_dimensions": ["Front camera exports", "Independent receiver playback", "Audio modes/routing",
                                 "Input kinds/patterns", "Lifecycle/fault sequences", "Environment/endurance"],
            "coverage": "Counted obligations are not executed coverage; uncompiled dimensions remain UNMEASURED"},
        "selection": "Direct: every offered encoder/allocation/buffer at low/high per ordered pair. Calls: linear independent endpoint coverage at all four low/high tier pairs; full Cartesian products symbolic. Chains: every ordered triple/transport at low/high using first compatible choice and one declared buffer. Separate bounded lifecycle/control/rejoin templates select an explicit low compatible profile per assignment; no setting-by-fault Cartesian claim.",
        "remaining": ["Other geometry/FPS/rate products and thermal-ON execution unmeasured",
                      "Independent call setting products, front cameras, other lifecycle/fault variants and external 40-pattern inputs not compiled",
                      "Longer/branch/mixed/relayed-call obligations symbolic only; no execution or acceptance claim",
                      "Camera/acoustic/optical/endurance/network-impairment adapters remain separate"]}
    cases = {kind: sorted(values.values(), key=lambda c: c["id"]) for kind, values in suites.items()}
    summary = {"state": "NOT RUN", "required_phones": required, **({"extension_phones": extension} if extension else {}),
               "selected_phones": selected,
               "cases": {k: len(v) for k, v in cases.items()},
               "lifecycle_cases": {kind: sum(len(r["lifecycle_case_ids"]) for r in rows if r["kind"] == kind)
                                   for kind in cases},
               "assignment_eligibility": {s: sum(r["eligibility"] == s for r in rows)
                                          for s in ("ELIGIBLE", "BLOCKED", "UNMEASURED")},
               "measured_executions": 0, "passes": 0}
    if sweep is not None:
        inventory["settings_sweep"] = sweep
        summary["settings_sweep_cases"] = len(sweep["case_ids"])
    return cases, inventory, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", type=Path, required=True)
    parser.add_argument("--phones", help="Comma-separated scope aliases; selection is not live availability")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    scope_path, output = resolve(args.scope), output_path(args.output, "catalog", root=ROOT)
    scope = read(scope_path)
    caps, provenance, problems = {}, {}, {}
    registry = read(resolve(scope["registry"]))["devices"]
    for phone in [*scope["required_phones"], *scope.get("extension_phones", [])]:
        try:
            path = resolve(scope["capabilities"][phone])
            raw = path.read_bytes()
            caps[phone] = read(path)
            provenance[phone] = {"path": str(path), "sha256": hashlib.sha256(raw).hexdigest(),
                                 "schema_version": caps[phone].get("schema_version"),
                                 "fingerprint": caps[phone].get("fingerprint"), "node_prefix": caps[phone].get("node_prefix")}
            if caps[phone].get("node_prefix") != registry.get(phone, {}).get("node_prefix"):
                problems[phone] = "Capability identity differs from the registry; refresh before execution"
        except (OSError, KeyError, ValueError) as error:
            problems[phone] = str(error)
    selected = [p.strip() for p in args.phones.split(",") if p.strip()] if args.phones is not None else None
    cases, inventory, summary = compile_inventory(scope, caps, selected, provenance, problems)
    inventory["scope_provenance"] = {"path": str(scope_path), "sha256": hashlib.sha256(scope_path.read_bytes()).hexdigest()}
    output.mkdir(parents=True, exist_ok=False)
    def write(name, data):
        (output / name).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    groups = []
    sweep_ids = set(inventory.get("settings_sweep", {}).get("case_ids", []))
    sweep_group = None
    profile = scope.get("acceptance_profile", acceptance.V3)
    user = ({"acceptance_profile": profile, "required_families": list(acceptance.families_for(profile))}
            if profile != acceptance.V3 else {})
    for kind, name in (("broadcast", "broadcast-suite.json"), ("call", "call-suite.json"), ("chain", "chain-suite.json")):
        write(name, {"schema_version": 1, "capability_bases": provenance, "cases": cases[kind]})
        if cases[kind]:
            group = {"path": str(output / name), "execution": "concurrent", **user}
            if kind == "broadcast" and sweep_ids:
                group["case_ids"] = [c["id"] for c in cases[kind] if c["id"] not in sweep_ids]
                sweep_group = {"path": str(output / name), "case_ids": sorted(sweep_ids),
                               "execution": "concurrent", "required_families": list(acceptance.sweep_families_for(profile)),
                               **({"acceptance_profile": profile} if user else {})}
            if group.get("case_ids") != []:
                groups.append(group)
    if sweep_group:
        groups.append(sweep_group)
    if "settings_sweep" in inventory:
        write("settings-sweep-bundle.json", {"schema_version": 1, "suites": [sweep_group] if sweep_group else [],
                                             "purpose": inventory["settings_sweep"]["purpose"]})
    write("benchmark-bundle.json", {"schema_version": 1, "suites": groups})
    write("inventory.json", inventory)
    write("input_requirements.json", input_requirements(cases))
    write("summary.json", summary)
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
