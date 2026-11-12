"""Freeze the built APK under benchmarks/builds/<sha16>/ and install it on phones in parallel.

The build's change-impact.json is still written by its owner; this tool reports when it is missing.
Unavailable phones are reported, not skipped silently.
"""

import os
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_tools import default_adb  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
DEFAULT_APK = ROOT / "app/build/outputs/apk/github/debug/app-github-debug.apk"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def freeze(apk):
    digest = sha256(apk)
    target = ROOT / "benchmarks/builds" / digest[:16] / apk.name
    if target.exists():
        if sha256(target) != digest:
            raise SystemExit(f"{target} exists with different bytes; refusing to overwrite")
    else:
        target.parent.mkdir(parents=True, exist_ok=True)  # change-impact.json may be written first
        shutil.copy2(apk, target)
    return digest, target


def install(adb, phone, serial, apk):
    def run(*args, timeout=180):
        return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    state = run("get-state", timeout=10)
    if state.returncode != 0 or state.stdout.strip() != "device":
        return phone, "UNAVAILABLE"
    # -d: frozen debug builds of an older version (A/B comparators) must install over a newer release candidate.
    result = run("install", "-r", "-d", str(apk))
    if result.returncode != 0 or "Success" not in result.stdout:
        return phone, f"FAILED: {(result.stdout + result.stderr).strip()[-200:]}"
    run("shell", "am", "force-stop", PACKAGE, timeout=20)
    return phone, "INSTALLED"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phones", required=True)
    parser.add_argument("--apk", default=str(DEFAULT_APK))
    parser.add_argument("--devices", default=str(ROOT / "tools/benchmark_devices.json"))
    parser.add_argument("--adb", default=default_adb())
    args = parser.parse_args(argv)
    digest, frozen = freeze(Path(args.apk))
    devices = json.loads(Path(args.devices).read_text(encoding="utf-8"))
    devices = devices.get("devices", devices)
    phones = args.phones.split(",")
    with ThreadPoolExecutor(len(phones)) as pool:
        results = dict(pool.map(lambda phone: install(args.adb, phone, devices[phone]["serial"], frozen), phones))
    impact = frozen.parent / "change-impact.json"
    print(json.dumps({"apk_sha256": digest, "frozen": str(frozen.relative_to(ROOT)), "installs": results,
                      "change_impact": "present" if impact.exists() else "MISSING"}))
    return 0 if all(status == "INSTALLED" for status in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
