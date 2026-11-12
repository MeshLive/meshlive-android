#!/usr/bin/env python3
"""Print the app's device-family policy table with sources, for docs or a session handoff.

The source of truth is app/src/main/java/com/example/bluetoothtest/DevicePolicyRegistry.kt. The JVM
test DeviceFamilyPolicyTest exports it, with the benchmark phones' resolution, to
tools/device_policy_registry.json; this tool reads that export. It never touches a phone.

Examples:
  python -B tools/device_policy_report.py                      # Markdown: entries, phones, gaps
  python -B tools/device_policy_report.py --section gaps
  python -B tools/device_policy_report.py --traits hardware=qcom,board=msmnile,sdk=30,manufacturer=asus
  python -B tools/device_policy_report.py --log path/to/logcat.txt   # BENCHMARK device_policy lines
  python -B tools/device_policy_report.py --check               # export consistency (exit 1 on problems)
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REGISTRY = ROOT / "tools" / "device_policy_registry.json"
SOC_FAMILIES = {"QUALCOMM", "EXYNOS", "MEDIATEK", "TENSOR", "UNISOC", "UNKNOWN"}
SOURCE_STATUSES = {"OFFICIAL", "PARTIAL", "EVIDENCE_ONLY"}
CONFIDENCES = {"HIGH", "MEDIUM", "LOW"}
AREAS = {"CODEC", "YUV", "BLUETOOTH_TRANSPORT", "GRAPHICS", "BACKGROUND", "MEMORY_THERMAL", "NATIVE", "CAMERA", "AUDIO", "WIFI"}
VALIDATIONS = {"UPSTREAM_DOCUMENTED", "LOCALLY_REPRODUCED", "EXPERIMENTAL"}
LINE_RE = re.compile(r"BENCHMARK device_policy: (?P<body>.*)$")


# --- Trait derivation: must match DeviceFamilyPolicy.kt (derive* functions). ---------------------

def derive_soc_family(soc_manufacturer: str, hardware: str) -> str:
    maker = (soc_manufacturer or "").strip().lower()
    by_maker = {"qti": "QUALCOMM", "qualcomm": "QUALCOMM", "mediatek": "MEDIATEK", "google": "TENSOR",
                "spreadtrum": "UNISOC", "unisoc": "UNISOC", "samsung": "EXYNOS"}
    if maker in by_maker:
        return by_maker[maker]
    hw = (hardware or "").strip().lower()
    if hw == "qcom":
        return "QUALCOMM"
    if re.fullmatch(r"mt\d{4}[a-z0-9]*", hw):
        return "MEDIATEK"
    if re.fullmatch(r"(exynos|s5e)\d{4}[a-z0-9]*", hw):
        return "EXYNOS"
    return "UNKNOWN"


def derive_skin(manufacturer: str) -> str:
    return {"samsung": "ONE_UI", "oppo": "COLOROS", "realme": "REALME_UI", "oneplus": "OXYGENOS",
            "xiaomi": "MIUI_HYPEROS", "redmi": "MIUI_HYPEROS", "poco": "MIUI_HYPEROS", "asus": "ASUS",
            "google": "PIXEL"}.get((manufacturer or "").strip().lower(), "OTHER")


def derive_stack_lineage(sdk: int) -> str:
    """The umbrella lineage the export carries per device (traits.stackLineage)."""
    return "BLUETOOTH_MODULE" if sdk >= 33 else "PLATFORM_SYSTEM_BT"


def derive_stack_era(sdk: int) -> str:
    """The finer stack era (one of five), as deriveStackLineage in DeviceFamilyPolicy.kt; never an umbrella value."""
    if sdk <= 30:
        return "LEGACY_29_30"
    if sdk <= 32:
        return "LEGACY_GD_OPTIONAL_31_32"
    if sdk == 33:
        return "GD_CORE_33"
    if sdk == 34:
        return "GD_FLAGGED_34"
    return "ACONFIG_35_PLUS"


_UMBRELLA_OF = {"LEGACY_29_30": "PLATFORM_SYSTEM_BT", "LEGACY_GD_OPTIONAL_31_32": "PLATFORM_SYSTEM_BT",
                "GD_CORE_33": "BLUETOOTH_MODULE", "GD_FLAGGED_34": "BLUETOOTH_MODULE", "ACONFIG_35_PLUS": "BLUETOOTH_MODULE"}


def lineage_covers(matcher_value: str, phone_era: str) -> bool:
    """BluetoothStackLineage.covers: an umbrella value matches every era under it, an era only itself."""
    return matcher_value == phone_era or (matcher_value in ("PLATFORM_SYSTEM_BT", "BLUETOOTH_MODULE")
                                          and _UMBRELLA_OF.get(phone_era) == matcher_value)


def complete_traits(traits: dict) -> dict:
    """Fills derived fields the way DeviceTraits does."""
    out = {"hardware": "", "board": "", "sdk": 0, "manufacturer": "", "brand": "", "soc_manufacturer": "",
           "soc_model": "", "codec_components": [], "controller": None}
    out.update({k: v for k, v in traits.items() if v is not None})
    out["sdk"] = int(out["sdk"])
    out["soc_family"] = derive_soc_family(out["soc_manufacturer"], out["hardware"])
    out["skin"] = derive_skin(out["manufacturer"])
    out["stack_lineage"] = derive_stack_lineage(out["sdk"])
    out["stack_era"] = derive_stack_era(out["sdk"])
    return out


# --- Matching: must match DeviceMatcher.matches / CodecComponentMatcher.matches. -----------------

def matcher_matches(matcher: dict, traits: dict) -> bool:
    sdk = traits["sdk"]
    if matcher.get("sdk_min") is not None and sdk < matcher["sdk_min"]:
        return False
    if matcher.get("sdk_max") is not None and sdk > matcher["sdk_max"]:
        return False
    if matcher["hardware"] and traits["hardware"].lower() not in {h.lower() for h in matcher["hardware"]}:
        return False
    if matcher["boards"] or matcher["board_prefixes"]:
        board = traits["board"].lower()
        exact = board in {b.lower() for b in matcher["boards"]}
        prefixed = any(board.startswith(p.lower()) for p in matcher["board_prefixes"])
        if not exact and not prefixed:
            return False
    if matcher["soc_families"] and traits["soc_family"] not in matcher["soc_families"]:
        return False
    if matcher["soc_model_prefixes"] and not any(
            traits["soc_model"].lower().startswith(p.lower()) for p in matcher["soc_model_prefixes"]):
        return False
    if matcher["manufacturers"] and traits["manufacturer"].lower() not in {m.lower() for m in matcher["manufacturers"]}:
        return False
    if matcher["skins"] and traits["skin"] not in matcher["skins"]:
        return False
    if matcher["stack_lineages"] and not any(lineage_covers(m, traits["stack_era"]) for m in matcher["stack_lineages"]):
        return False
    # Security-patch windows and runtime traits (DeviceTraitsProbe) cannot be read offline: an unread fact never satisfies a constraint.
    if any(matcher.get(key) is not None for key in ("patch_min", "patch_max", "runtime")):
        return False
    controller = matcher.get("controller")
    if controller is not None:
        actual = traits.get("controller")
        if actual is None:
            return False
        for key in ("le_2m_phy", "le_coded_phy", "le_extended_advertising"):
            if controller.get(key) is not None and actual.get(key) != controller[key]:
                return False
    if matcher["codec_component_prefixes"] and not any(
            name.lower().startswith(p.lower()) for name in traits["codec_components"]
            for p in matcher["codec_component_prefixes"]):
        return False
    return True


def component_matches(component: dict, name: str) -> bool:
    return any(n.lower() == name.lower() for n in component["names"]) or any(
        name.startswith(p) for p in component["prefixes"])


def resolve(registry: dict, traits: dict, disabled: set[str] | None = None, arms_on: set[str] | None = None) -> dict:
    """Device-level active IDs, component-rule hits and matching proposals for one phone.

    As in Kotlin (DeviceFamilyPolicy.resolve, switch audit A10): a proposal replaced by an implemented entry
    (`superseded_by`) is never a matching proposal, and an entry whose effect waits for a default-off Experimental arm
    (`arm`, the arm's launch extra) is listed under `arm_off`, not `active`, unless that arm is in [arms_on] or the arm
    is on by default (`arm_on_by_default`: a promoted switch whose launch extra now names its control arm's opposite)."""
    disabled = disabled or set()
    arms_on = arms_on or set()
    all_off = "all" in disabled
    active, components, proposals, switched_off, arm_off = [], {}, [], [], []
    for entry in registry["entries"]:
        if not matcher_matches(entry["matcher"], traits):
            continue
        if entry["status"] == "proposed":
            if entry.get("superseded_by"):
                continue
            component = entry["component"]
            if component is None or any(component_matches(component, n) for n in traits["codec_components"]):
                proposals.append(entry["id"])
            continue
        if all_off or entry["id"] in disabled:
            switched_off.append(entry["id"])
            continue
        if entry["component"] is None:
            if entry.get("arm") and entry["arm"] not in arms_on and not entry.get("arm_on_by_default"):
                arm_off.append(entry["id"])
            else:
                active.append(entry["id"])
        else:
            hits = [name for name in traits["codec_components"] if component_matches(entry["component"], name)]
            if hits:
                components[entry["id"]] = hits
    return {"active": active, "component_rules": components, "matching_proposals": proposals,
            "switched_off": switched_off, "arm_off": arm_off}


# --- Checks -------------------------------------------------------------------------------------

def check(registry: dict, root: Path = ROOT) -> list[str]:
    problems: list[str] = []
    ids = [entry["id"] for entry in registry["entries"]]
    if len(ids) != len(set(ids)):
        problems.append("duplicate entry IDs")
    for entry in registry["entries"]:
        eid = entry["id"]
        if entry["source_status"] not in SOURCE_STATUSES:
            problems.append(f"{eid}: unknown source status {entry['source_status']}")
        if entry.get("area", "BLUETOOTH_TRANSPORT") not in AREAS:
            problems.append(f"{eid}: unknown area {entry.get('area')}")
        if entry.get("validation", "EXPERIMENTAL") not in VALIDATIONS:
            problems.append(f"{eid}: unknown validation {entry.get('validation')}")
        if entry.get("validation") == "LOCALLY_REPRODUCED" and not any(
                e.startswith(("benchmarks/", "docs/")) for e in entry["evidence"]):
            problems.append(f"{eid}: locally reproduced without a run or decision reference")
        if entry["confidence"] not in CONFIDENCES:
            problems.append(f"{eid}: unknown confidence {entry['confidence']}")
        if entry["source_status"] != "EVIDENCE_ONLY" and not entry["sources"]:
            problems.append(f"{eid}: {entry['source_status']} without sources")
        if entry["status"] == "proposed" and entry["source_status"] == "EVIDENCE_ONLY":
            problems.append(f"{eid}: a proposal must be sourced")
        if entry["status"] == "implemented" and not entry["effects"]:
            problems.append(f"{eid}: implemented without effects")
        if not entry["evidence"]:
            problems.append(f"{eid}: no evidence entry")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", entry["reviewed"]):
            problems.append(f"{eid}: reviewed date {entry['reviewed']!r}")
        if entry["off_switch"] != f"test_device_policy_off={eid}":
            problems.append(f"{eid}: off-switch {entry['off_switch']!r}")
        for family in entry["matcher"]["soc_families"]:
            if family not in SOC_FAMILIES:
                problems.append(f"{eid}: unknown SoC family {family}")
    for device in registry["known_devices"]:
        alias = device["alias"]
        exported = device["traits"]
        derived = complete_traits({k: v for k, v in exported.items()
                                   if k not in ("soc_family", "skin", "stack_lineage")})
        for key in ("soc_family", "skin", "stack_lineage"):
            if derived[key] != exported[key]:
                problems.append(f"{alias}: Python derives {key}={derived[key]}, Kotlin exported {exported[key]}")
        resolved = resolve(registry, derived)
        for key in ("active", "component_rules", "matching_proposals"):
            if resolved[key] != device[key]:
                problems.append(f"{alias}: Python {key}={resolved[key]} but Kotlin exported {device[key]}")
        capability = root / device["capability_file"]
        if capability.is_file():
            data = json.loads(capability.read_text(encoding="utf-8"))
            if data.get("model") != device["model"] or int(data.get("api", -1)) != exported["sdk"]:
                problems.append(f"{alias}: capability file {device['capability_file']} no longer matches the export")
    return problems


# --- Rendering ----------------------------------------------------------------------------------

def _cell(text: str) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def _sources(entry: dict) -> str:
    listed = "<br>".join(f"{s['kind']}: {s['reference']} ({s['note']})" for s in entry["sources"])
    if entry["source_status"] == "EVIDENCE_ONLY":
        return "**evidence-only, needs source**" + (f"<br>supporting only: {listed}" if listed else "")
    return listed


def render_entries(registry: dict, fmt: str) -> str:
    rows = []
    for entry in registry["entries"]:
        status = entry["status"]
        if entry["source_status"] == "EVIDENCE_ONLY" and status == "implemented":
            status += " (evidence-only, needs source)"
        rows.append([entry["id"], status, entry["matcher_text"], entry["behaviour"], _sources(entry),
                     "<br>".join(entry["evidence"]),
                     entry["confidence"] + (f" ({entry['confidence_note']})" if entry.get("confidence_note") else ""),
                     entry["reviewed"],
                     entry["off_switch"] + "".join(f"<br>{x}" for x in entry.get("legacy_switches", []))])
    header = ["Entry", "Status", "Matcher", "Behaviour", "Sources", "Evidence", "Confidence", "Reviewed", "Off-switch"]
    if fmt == "text":
        return "\n\n".join("\n".join(f"{h}: {v.replace('<br>', '; ')}" for h, v in zip(header, row)) for row in rows)
    lines = ["| " + " | ".join(header) + " |", "| " + " | ".join("---" for _ in header) + " |"]
    lines += ["| " + " | ".join(_cell(v) for v in row) + " |" for row in rows]
    return "\n".join(lines)


def render_devices(registry: dict, fmt: str) -> str:
    rows = []
    for device in registry["known_devices"]:
        t = device["traits"]
        rows.append([device["alias"], device["model"],
                     f"{t['hardware']}/{t['board']} API {t['sdk']} {t['soc_family']} {t['skin']}",
                     ", ".join(device["active"]) or "none",
                     ", ".join(f"{k} ({len(v)})" for k, v in device["component_rules"].items()) or "none",
                     ", ".join(device["matching_proposals"]) or "none"])
    header = ["Phone", "Model", "Traits", "Active device entries", "Component rules (matching codecs)", "Matching proposals"]
    if fmt == "text":
        return "\n".join(" | ".join(row) for row in [header] + rows)
    lines = ["| " + " | ".join(header) + " |", "| " + " | ".join("---" for _ in header) + " |"]
    lines += ["| " + " | ".join(_cell(v) for v in row) + " |" for row in rows]
    return "\n".join(lines)


def render_gaps(registry: dict) -> str:
    lines = []
    for entry in registry["entries"]:
        if entry["source_status"] == "EVIDENCE_ONLY":
            lines.append(f"- {entry['id']}: evidence-only, needs source ({entry['confidence']} confidence).")
        elif entry["source_status"] == "PARTIAL":
            lines.append(f"- {entry['id']}: partial; official sources cover the mechanism, not the scope/values.")
    proposals = [e for e in registry["entries"] if e["status"] == "proposed"]
    for entry in proposals:
        lines.append(f"- {entry['id']}: proposed, not implemented (needs an A/B and maintainer review).")
    return "\n".join(lines) or "- none"


def parse_log_lines(lines) -> list[dict]:
    records = []
    for line in lines:
        match = LINE_RE.search(line.rstrip("\n"))
        if not match:
            continue
        fields = {}
        for part in match.group("body").split(" | "):
            key, _, value = part.partition("=")
            fields[key.strip()] = value.strip()
        for key in ("active", "switchedOff", "matchingProposals"):
            value = fields.get(key, "none")
            fields[key] = [] if value in ("", "none") else value.split(",")
        records.append(fields)
    return records


def parse_traits(text: str) -> dict:
    traits = {}
    for part in text.split(","):
        if not part.strip():
            continue
        key, _, value = part.partition("=")
        key = key.strip()
        if key == "codec_components":
            traits[key] = [c for c in value.split("+") if c]
        else:
            traits[key] = value.strip()
    return complete_traits(traits)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    parser.add_argument("--format", choices=("markdown", "text", "json"), default="markdown")
    parser.add_argument("--section", choices=("all", "entries", "devices", "gaps"), default="all")
    parser.add_argument("--traits", help="evaluate one phone: hardware=,board=,sdk=,manufacturer=,soc_manufacturer=,"
                                         "soc_model=,codec_components=a+b")
    parser.add_argument("--off", default="", help="entry IDs switched off for --traits (comma list or all)")
    parser.add_argument("--log", type=Path, help="print BENCHMARK device_policy lines found in a phone log")
    parser.add_argument("--check", action="store_true", help="validate the export; exit 1 on problems")
    args = parser.parse_args(argv)
    registry = json.loads(args.registry.read_text(encoding="utf-8"))

    if args.check:
        problems = check(registry)
        for problem in problems:
            print(f"PROBLEM {problem}")
        print(f"{len(registry['entries'])} entries, {len(registry['known_devices'])} phones, {len(problems)} problems")
        return 1 if problems else 0
    if args.traits:
        result = resolve(registry, parse_traits(args.traits), {x.strip() for x in args.off.split(",") if x.strip()})
        print(json.dumps(result, indent=2))
        return 0
    if args.log:
        records = parse_log_lines(args.log.read_text(encoding="utf-8", errors="replace").splitlines())
        print(json.dumps(records, indent=2))
        return 0 if records else 1
    if args.format == "json":
        print(json.dumps(registry, indent=2))
        return 0
    parts = []
    if args.section in ("all", "entries"):
        parts.append(("## Device policy entries\n\n" if args.format == "markdown" else "") + render_entries(registry, args.format))
    if args.section in ("all", "devices"):
        parts.append(("## Benchmark phones\n\n" if args.format == "markdown" else "") + render_devices(registry, args.format))
    if args.section in ("all", "gaps"):
        parts.append(("## Gaps\n\n" if args.format == "markdown" else "") + render_gaps(registry))
    print(f"Registry revision {registry['revision']}\n\n" + "\n\n".join(parts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
