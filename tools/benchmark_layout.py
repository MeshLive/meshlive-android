"""Canonical benchmark storage; migration aliases apply only to evidence reads."""
import json
import os
from pathlib import Path
import tempfile

from debug_image_lifecycle import DebugImages
from evidence_relocation import logical

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "benchmarks"
INPUTS = DATA / "inputs"
BUILDS = DATA / "builds"
CATALOG = DATA / "catalog"
RUNS = DATA / "runs"
LEDGER = CATALOG / "ledger.json"
GROUPS = {"inputs", "builds", "catalog", "runs"}
_CACHE = {}


def _locator(value):
    if not isinstance(value, str) or not value:
        raise ValueError("Storage aliases need nonempty project-relative paths")
    parts = value.replace("\\", "/").split("/")
    if any(part in {"", ".", ".."} or ":" in part for part in parts):
        raise ValueError("Storage alias cannot be absolute or contain traversal")
    return Path(*parts)


def _key(path):
    return os.path.normcase(str(path))


def _target(path, root):
    physical = logical(root / path, root)
    try:
        parts = physical.relative_to(root).parts
    except ValueError:
        raise ValueError("Storage alias target escapes the project") from None
    if len(parts) < 2 or parts[0] != "benchmarks" or parts[1] not in GROUPS:
        raise ValueError("Storage alias target must use a canonical benchmark group")
    return physical


def _aliases(root):
    ledger = root / "benchmarks/catalog/ledger.json"
    try:
        info = ledger.stat()
        stamp = (info.st_mtime_ns, info.st_size)
    except FileNotFoundError:
        stamp = None
    cached = _CACHE.get(root)
    if cached is not None and cached[0] == stamp:
        return cached[1:]
    storage = json.loads(ledger.read_text(encoding="utf-8-sig")).get("storage", {}) if stamp else {}
    if not isinstance(storage, dict):
        raise ValueError("Ledger storage must be an object")
    forward, inverse, targets = {}, {}, set()
    for field in ("paths", "directories"):
        entries = storage.get(field, {})
        if not isinstance(entries, dict):
            raise ValueError(f"Storage {field} must map original paths to canonical paths")
        for source, destination in entries.items():
            source, destination = _locator(source), _locator(destination)
            if field == "paths" and len(destination.parts) < 3:
                raise ValueError("A file alias needs a filename inside its benchmark group")
            _target(destination, root)
            key, target = _key(source), _key(destination)
            if key in forward or target in targets:
                raise ValueError("Storage aliases collide")
            forward[key] = destination
            targets.add(target)
            if field == "paths":
                inverse[target] = source
    if any(_key(target) in forward and forward[_key(target)] != target for target in forward.values()):
        raise ValueError("Storage aliases cannot redirect canonical targets again")
    _CACHE[root] = (stamp, forward, inverse)
    return forward, inverse


def resolve(path, base=None, root=ROOT):
    """Open an exact original locator at its canonical physical location."""
    root, path = Path(root).resolve(), Path(path)
    candidate = logical(path if path.is_absolute() else Path(base if base is not None else root) / path, root)
    try:
        relative = candidate.relative_to(root)
    except ValueError:
        return candidate
    forward, _ = _aliases(root)
    target = forward.get(_key(relative))
    return _target(target, root) if target is not None else candidate


def original(path, root=ROOT):
    """Recover a moved document's original location for its recorded relative references."""
    root, path = Path(root).resolve(), Path(path)
    candidate = logical(path if path.is_absolute() else root / path, root)
    try:
        relative = candidate.relative_to(root)
    except ValueError:
        return candidate
    _, inverse = _aliases(root)
    return root / inverse[_key(relative)] if _key(relative) in inverse else candidate


def output_path(path, group, root=ROOT):
    """Guard an owned physical output; historical aliases never redirect writes."""
    if group not in GROUPS:
        raise ValueError("Unknown benchmark output group")
    root, path = Path(root).resolve(), Path(path)
    candidate = Path(os.path.abspath(path if path.is_absolute() else root / path))
    group_dir = root / "benchmarks" / group
    if candidate.is_relative_to(group_dir) and os.path.isjunction(group_dir) and not os.path.exists(group_dir):
        raise ValueError(f"benchmarks/{group} lives on the external evidence SSD ({os.readlink(group_dir)}); plug it in")
    DebugImages._guard(candidate)
    if any(":" in part for part in candidate.parts[1:]):
        raise ValueError("Benchmark output cannot use an alternate data stream")
    physical = logical(candidate, root)
    if physical.is_relative_to(root):
        expected = root / "benchmarks" / group
        if not physical.is_relative_to(expected) or physical == expected and group != "catalog":
            raise ValueError(f"Project output must be owned by benchmarks/{group}")
    else:
        temporary = Path(tempfile.gettempdir()).resolve()
        if not physical.is_relative_to(temporary) or physical == temporary:
            raise ValueError("Scratch output must use the OS temporary directory")
    return physical


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Validate an owned benchmark output without reading aliases or using devices")
    parser.add_argument("--group", required=True, choices=sorted(GROUPS))
    parser.add_argument("--path", required=True)
    args = parser.parse_args()
    try:
        print(output_path(args.path, args.group))
    except (ValueError, OSError) as error:
        parser.exit(2, f"Benchmark output blocked: {error}\n")
