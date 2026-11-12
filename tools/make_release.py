#!/usr/bin/env python3
"""Build, verify and package the signed per-ABI release APKs, plus a SOURCE-MANIFEST.

Full run (local, needs the signing key outside the repo):
    python tools/make_release.py [--no-universal] [--force]

Writes release/<versionName>-<versionCode>/ with MeshLive-<versionName>-<abi>.apk for arm64-v8a and
armeabi-v7a (plus -universal unless --no-universal), SHA256SUMS, signing-cert.sha256, apksigner-certs.txt,
RELEASE-NOTES.md (a template to finish by hand) and SOURCE-MANIFEST.txt. Nothing is uploaded or pushed.

Manifest only (used by CI; no build, no signing material):
    python tools/make_release.py --manifest-only --output SOURCE-MANIFEST.txt

The full run refuses a dirty working tree and any signing certificate other than
EXPECTED_CERT_SHA256. It never reads the keystore or its properties; Gradle does.
The manifest hashes the bytes committed at HEAD (not the working tree), so it is
identical on every OS and checkout setting and anyone can recompute it.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_apk_16kb  # noqa: E402
import fetch_native_sources  # noqa: E402  (pinned native inputs recorded in the manifest)

EXPECTED_CERT_SHA256 = ("2E:EF:C0:5A:44:45:22:52:9D:9F:75:D7:EB:CE:FD:08:"
                        "86:47:07:F2:1A:79:04:1D:B5:1E:BF:F8:36:49:E2:13")
MANIFEST_NAME = "SOURCE-MANIFEST.txt"
# RENAME POINT 2 of 2 (the other is `appId` in app/build.gradle.kts): the release file prefix.
RELEASE_BASENAME = "MeshLive"
LICENSE_ID = "GPL-3.0-or-later"
# Per-ABI versionCode = versionCode * 10 + digit; must equal app/build.gradle.kts `abiVersionDigits` (checked
# against Gradle's output-metadata.json on every release). The universal APK is digit 0.
ABI_DIGITS = {"armeabi-v7a": 1, "arm64-v8a": 2}
UNIVERSAL = "universal"
SPLIT_GRADLE_ARGS = ["-PsplitAbis=true", "-PenableArmeabiV7a=true"]
# The release page ships the github flavour (opt-in updater, REQUEST_INSTALL_PACKAGES); F-Droid builds the fdroid
# flavour with its own recipe (distribution/fdroid). app/build.gradle.kts `productFlavors` defines both.
RELEASE_FLAVOUR = "github"
FASTLANE_CHANGELOGS = Path("fastlane/metadata/android/en-US/changelogs")
ROOT = Path(__file__).resolve().parents[1]


class ReleaseError(Exception):
    pass


def run(args, cwd, input_bytes=None):
    """Run a command and return stdout bytes; raise ReleaseError on failure."""
    try:
        done = subprocess.run(args, cwd=str(cwd), input=input_bytes,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as exc:
        raise ReleaseError(f"cannot run {args[0]}: {exc}")
    if done.returncode != 0:
        tail = done.stderr.decode("utf-8", "replace").strip()[-800:]
        raise ReleaseError(f"{' '.join(map(str, args[:3]))} failed ({done.returncode}): {tail}")
    return done.stdout


# --- source manifest ---------------------------------------------------------

def parse_ls_tree(raw):
    """Parse `git ls-tree -r -z HEAD` into [(mode, sha1, path)], skipping submodules."""
    entries = []
    for record in raw.split(b"\0"):
        if not record:
            continue
        meta, path = record.split(b"\t", 1)
        mode, kind, sha = meta.decode().split()
        if kind == "blob":
            entries.append((mode, sha, path.decode("utf-8")))
    return entries


def blob_contents(root, shas, runner=run):
    """Return {sha1: bytes} through one `git cat-file --batch` call."""
    unique = sorted(set(shas))
    out = runner(["git", "cat-file", "--batch"], root,
                 input_bytes=("\n".join(unique) + "\n").encode() if unique else b"")
    blobs, pos = {}, 0
    for sha in unique:
        eol = out.index(b"\n", pos)
        header = out[pos:eol].decode().split()
        if len(header) != 3 or header[0] != sha or header[1] != "blob":
            raise ReleaseError(f"unexpected cat-file header for {sha}: {header}")
        size = int(header[2])
        blobs[sha] = out[eol + 1:eol + 1 + size]
        pos = eol + 1 + size + 1
    return blobs


def build_manifest(root, version, runner=run):
    """Deterministic manifest text for the commit at HEAD."""
    commit = runner(["git", "rev-parse", "HEAD"], root).decode().strip()
    tree = runner(["git", "rev-parse", "HEAD^{tree}"], root).decode().strip()
    entries = parse_ls_tree(runner(["git", "ls-tree", "-r", "-z", "HEAD"], root))
    blobs = blob_contents(root, [sha for _, sha, _ in entries], runner)
    rows = sorted((path, hashlib.sha256(blobs[sha]).hexdigest()) for _, sha, path in entries)
    lines = ["SOURCE-MANIFEST v1",
             f"version: {version[0]} ({version[1]})",
             f"git-commit: {commit}",
             f"git-tree: {tree}",
             f"files: {len(rows)}",
             *fetch_native_sources.native_provenance(),
             "format: <sha256>  <path>, hashes of the bytes committed at that commit",
             ""]
    lines += [f"{digest}  {path}" for path, digest in rows]
    return "\n".join(lines) + "\n"


def check_clean(status_output):
    if status_output.strip():
        shown = status_output.strip().splitlines()
        raise ReleaseError("working tree is dirty; commit or stash first:\n  "
                           + "\n  ".join(shown[:10]) + ("\n  ..." if len(shown) > 10 else ""))


# --- version, apksigner, certificate -----------------------------------------

def read_version(root):
    text = (Path(root) / "app/build.gradle.kts").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        raise ReleaseError("versionName/versionCode not found in app/build.gradle.kts")
    return name.group(1), int(code.group(1))


def _version_key(name):
    return [int(p) if p.isdigit() else -1 for p in re.split(r"[.\-]", name)]


def find_apksigner(env=None, exists=os.path.exists, listdir=os.listdir, windows=None):
    """Highest-version apksigner under ANDROID_HOME/ANDROID_SDK_ROOT/%LOCALAPPDATA%\\Android\\Sdk."""
    env = os.environ if env is None else env
    windows = (os.name == "nt") if windows is None else windows
    sdks = [env[k] for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if env.get(k)]
    if env.get("LOCALAPPDATA"):
        sdks.append(os.path.join(env["LOCALAPPDATA"], "Android", "Sdk"))
    names = ["apksigner.bat"] if windows else ["apksigner"]
    searched = []
    for sdk in sdks:
        tools = os.path.join(sdk, "build-tools")
        searched.append(tools)
        if not exists(tools):
            continue
        for version in sorted(listdir(tools), key=_version_key, reverse=True):
            for name in names:
                candidate = os.path.join(tools, version, name)
                if exists(candidate):
                    return candidate
    raise ReleaseError("apksigner not found; set ANDROID_HOME to an SDK with build-tools. Searched: "
                       + (", ".join(searched) or "(no ANDROID_HOME, ANDROID_SDK_ROOT or LOCALAPPDATA)"))


def normalize_fingerprint(value):
    hex_digits = re.sub(r"[^0-9A-Fa-f]", "", value).upper()
    if len(hex_digits) != 64:
        raise ReleaseError(f"not a SHA-256 fingerprint: {value!r}")
    return ":".join(hex_digits[i:i + 2] for i in range(0, 64, 2))


def parse_cert_sha256(print_certs_output):
    """All signer certificate SHA-256 digests from `apksigner verify --print-certs`."""
    # apksigner <= 36 prints "Signer #1 certificate ..."; 37 prints "V2 Signer: certificate ..." (one line
    # per signature scheme that carries a signer, so the same digest can repeat for V2 and V3).
    found = re.findall(r"(?:Signer #\d+|V\d+ Signer(?: #\d+)?):? certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)",
                       print_certs_output)
    if not found:
        raise ReleaseError("no signer certificate SHA-256 in apksigner output (unsigned APK?)")
    return [normalize_fingerprint(item) for item in found]


def require_expected_cert(print_certs_output, expected=EXPECTED_CERT_SHA256):
    digests = parse_cert_sha256(print_certs_output)
    want = normalize_fingerprint(expected)
    bad = [d for d in digests if d != want]
    if bad:
        raise ReleaseError(f"signing certificate mismatch: got {bad[0]}, expected {want}")
    return want


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_app_id(root):
    text = (Path(root) / "app/build.gradle.kts").read_text(encoding="utf-8")
    found = re.search(r'val appId\s*=.*?\?:\s*"([^"]+)"', text, re.S)
    if not found:
        raise ReleaseError('`val appId = ... ?: "<id>"` not found in app/build.gradle.kts')
    return found.group(1)


def abi_version_code(base, abi):
    return base * 10 + (0 if abi == UNIVERSAL else ABI_DIGITS[abi])


def find_release_apks(root, base_code, universal=True):
    """The release APKs from Gradle's output-metadata.json as [{abi, versionCode, path}], ABI-sorted.

    Fails if an APK is unsigned, the set differs from the requested ABIs, or a versionCode is not
    base * 10 + ABI digit (so Gradle and this script cannot drift apart).
    """
    folder = Path(root) / "app/build/outputs/apk" / RELEASE_FLAVOUR / "release"
    meta = folder / "output-metadata.json"
    if not meta.is_file():
        raise ReleaseError(f"no {meta}: assemble{RELEASE_FLAVOUR.capitalize()}Release produced no APKs")
    found = {}
    for element in json.loads(meta.read_text(encoding="utf-8")).get("elements", []):
        name = element["outputFile"]
        if "unsigned" in name:
            raise ReleaseError(f"{name} is unsigned: the release signing config is missing "
                               "(~/.bluetoothtest-release/keystore.properties absent or incomplete)")
        abis = [f["value"] for f in element.get("filters", []) if f.get("filterType") == "ABI"]
        abi = abis[0] if abis else UNIVERSAL
        found[abi] = {"abi": abi, "versionCode": int(element["versionCode"]), "path": folder / name}
    want = sorted(ABI_DIGITS) + ([UNIVERSAL] if universal else [])
    if sorted(found) != sorted(want):
        raise ReleaseError(f"expected APKs {sorted(want)}, Gradle produced {sorted(found)}")
    for abi, item in found.items():
        expect = abi_version_code(base_code, abi)
        if item["versionCode"] != expect:
            raise ReleaseError(f"{abi} versionCode is {item['versionCode']}, expected {expect} "
                               f"({base_code} * 10 + ABI digit); check abiVersionDigits in app/build.gradle.kts")
        if not item["path"].is_file():
            raise ReleaseError(f"missing APK file {item['path']}")
    return [found[abi] for abi in sorted(found)]


def check_changelogs(root, apks):
    """F-Droid reads fastlane changelogs/<versionCode>.txt for each APK it publishes (not the universal one)."""
    missing = [str(FASTLANE_CHANGELOGS / f"{a['versionCode']}.txt") for a in apks
               if a["abi"] != UNIVERSAL
               and not (Path(root) / FASTLANE_CHANGELOGS / f"{a['versionCode']}.txt").is_file()]
    if missing:
        raise ReleaseError("missing fastlane changelog(s): " + ", ".join(missing))


def check_native_libs(apk, abi, inspector=check_apk_16kb.check_apk):
    """Libraries must match the APK's ABI; 64-bit libraries must pass the 16 KB page-size check.

    32-bit libraries are not gated: 16 KB page-size devices are 64-bit.
    Returns the list of per-library result dicts.
    """
    with zipfile.ZipFile(apk) as archive:
        libs = sorted(n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so"))
    want = sorted(ABI_DIGITS) if abi == UNIVERSAL else [abi]
    got = sorted({n.split("/")[1] for n in libs})
    if got != want:
        raise ReleaseError(f"{Path(apk).name}: native ABIs {got}, expected {want}")
    results = inspector(str(apk))
    for result in results:
        if "/arm64-v8a/" in result["library"] and not result["ok"]:
            raise ReleaseError(f"{Path(apk).name}: {result['library']} fails the 16 KB page-size check: {result}")
    return results


# --- release files -----------------------------------------------------------

def apk_file_name(version_name, apk):
    return f"{RELEASE_BASENAME}-{version_name}-{apk['abi']}.apk"


STABLE_APK_NAME = "MeshLive.apk"


def sha256sums_text(entries):
    """sha256sum -c compatible text for [(name, digest)]."""
    return "".join(f"{digest}  {name}\n" for name, digest in sorted(entries))


CHOOSE = {
    "arm64-v8a": "Almost every phone sold since about 2016 (64-bit ARM). Pick this one if unsure.",
    "armeabi-v7a": "Older or 32-bit-only ARM phones (some Android Go models). A 64-bit phone can also run it, "
                   "but it is slower and Android will not move it to the 64-bit build by itself.",
    UNIVERSAL: "Both libraries in one larger file, for sideloading when you do not know the ABI. "
               "Not published to F-Droid.",
}


def release_notes(version, app_id, commit, cert, rows, changelog):
    """Markdown template for the GitHub release. `rows` is [(file, abi, versionCode, sha256)]."""
    table = "\n".join(f"| `{f}` | {a} | {c} | `{d}` |" for f, a, c, d in rows)
    choose = "\n".join(f"- **{a}**: {CHOOSE[a]}" for _f, a, _c, _d in rows)
    first = rows[0][0]
    return f"""# {RELEASE_BASENAME} {version[0]} (unofficial build)

Open-source build signed with the project's release key; Google Play delivers the same app with the same key.
Application id: `{app_id}`. Source commit: `{commit}`.
Licence: {LICENSE_ID}; the full text is in `LICENSE` and third-party licences are in `THIRD_PARTY_NOTICES.md` of the source.

## What changed

{changelog or "TODO: write the changes for this release (user-visible first)."}

## Which file to download

| File | ABI | versionCode | SHA-256 |
| --- | --- | --- | --- |
{table}

{choose}

If `adb shell getprop ro.product.cpu.abilist` lists `arm64-v8a`, use the arm64-v8a file.

## Verify before installing

1. Hash: `sha256sum -c SHA256SUMS --ignore-missing` (PowerShell: `Get-FileHash <file> -Algorithm SHA256`).
2. Signer: `apksigner verify --print-certs {first}` must print exactly one signer whose
   `certificate SHA-256 digest` is (colon form):

```
{cert}
```

   A different fingerprint means a different publisher: do not install it.
3. Source: `SOURCE-MANIFEST.txt` lists the commit, the SHA-256 of every tracked file and the pinned native
   sources (libaom and libvpx commits and tree SHA-256s, libopus tarball SHA-256; `python tools/fetch_native_sources.py`
   fetches and verifies them). `python tools/make_release.py --manifest-only` reproduces it from a checkout.

## Measured on

TODO: benchmark build ids, phones and cases this release was measured on (docs/dev/testing.md "Release checklist").
Untested combinations are not claimed to work.

## Known limits

TODO: list the known issues users will notice (open GitHub issues labelled for this release).
"""


# --- orchestration -----------------------------------------------------------

def verify_native_sources(root):
    """Refuse a release unless reference-repos/ holds exactly the pinned libaom, libvpx and libopus sources."""
    problems = [reason for name in sorted(fetch_native_sources.NATIVE_SOURCES)
                if (reason := fetch_native_sources.verify(name, Path(root))) is not None]
    if problems:
        raise ReleaseError("native sources do not match their pins (run python tools/fetch_native_sources.py):\n  "
                           + "\n  ".join(problems))


def make_release(root, out_base=None, expected=EXPECTED_CERT_SHA256, runner=run,
                 apksigner=None, force=False, universal=True, inspector=check_apk_16kb.check_apk,
                 log=print):
    root = Path(root)
    check_clean(runner(["git", "status", "--porcelain"], root).decode())
    verify_native_sources(root)
    version = read_version(root)
    app_id = read_app_id(root)
    out_dir = Path(out_base or root / "release") / f"{version[0]}-{version[1]}"
    if out_dir.exists() and not force:
        raise ReleaseError(f"{out_dir} exists; versionCode was not bumped (use --force to overwrite)")
    signer = apksigner or find_apksigner()
    head = runner(["git", "rev-parse", "HEAD"], root).decode().strip()

    gradle = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")),
              "--no-daemon", f":app:assemble{RELEASE_FLAVOUR.capitalize()}Release", *SPLIT_GRADLE_ARGS,
              f"-PuniversalApk={'true' if universal else 'false'}"]
    log("building: " + " ".join(gradle))
    runner(gradle, root)

    # A build must not change tracked files or move HEAD.
    check_clean(runner(["git", "status", "--porcelain"], root).decode())
    if runner(["git", "rev-parse", "HEAD"], root).decode().strip() != head:
        raise ReleaseError("HEAD moved during the build")

    apks = find_release_apks(root, version[1], universal)
    check_changelogs(root, apks)
    cert_sections = []
    cert = normalize_fingerprint(expected)
    for apk in apks:
        certs = runner([signer, "verify", "--print-certs", str(apk["path"])], root).decode("utf-8", "replace")
        cert = require_expected_cert(certs, expected)
        if len(set(parse_cert_sha256(certs))) != 1:
            raise ReleaseError(f"{apk['path'].name}: expected exactly one signer")
        check_native_libs(apk["path"], apk["abi"], inspector)
        cert_sections.append(f"== {apk_file_name(version[0], apk)}\n{certs}")
    manifest = build_manifest(root, version, runner)

    out_dir.mkdir(parents=True, exist_ok=True)
    entries, rows = [], []
    for apk in apks:
        name = apk_file_name(version[0], apk)
        (out_dir / name).write_bytes(apk["path"].read_bytes())
        digest = sha256_file(out_dir / name)
        entries.append((name, digest))
        rows.append((name, apk["abi"], apk["versionCode"], digest))
    # Stable alias of the arm64-v8a APK: README's download button links releases/latest/download/MeshLive.apk.
    arm64_name = apk_file_name(version[0], next(a for a in apks if a["abi"] == "arm64-v8a"))
    (out_dir / STABLE_APK_NAME).write_bytes((out_dir / arm64_name).read_bytes())
    entries.append((STABLE_APK_NAME, sha256_file(out_dir / STABLE_APK_NAME)))
    (out_dir / MANIFEST_NAME).write_text(manifest, encoding="utf-8", newline="\n")
    entries.append((MANIFEST_NAME, sha256_file(out_dir / MANIFEST_NAME)))
    (out_dir / "SHA256SUMS").write_text(sha256sums_text(entries), encoding="utf-8", newline="\n")
    (out_dir / "apksigner-certs.txt").write_text("\n".join(cert_sections), encoding="utf-8")
    (out_dir / "signing-cert.sha256").write_text(cert + "\n", encoding="utf-8")
    arm64 = next(a for a in apks if a["abi"] == "arm64-v8a")
    changelog_file = root / FASTLANE_CHANGELOGS / f"{arm64['versionCode']}.txt"
    changelog = changelog_file.read_text(encoding="utf-8").strip() if changelog_file.is_file() else None
    (out_dir / "RELEASE-NOTES.md").write_text(
        release_notes(version, app_id, head, cert, rows, changelog), encoding="utf-8", newline="\n")
    log(f"release folder: {out_dir}\ncertificate sha256: {cert}\n"
        + "\n".join(f"{name}  {digest}" for name, _a, _c, digest in rows))
    return out_dir


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--manifest-only", action="store_true",
                        help="write only the source manifest for HEAD (CI mode; no build, no signing)")
    parser.add_argument("--output", help="manifest path for --manifest-only (default: stdout)")
    parser.add_argument("--force", action="store_true", help="overwrite an existing release folder")
    parser.add_argument("--no-universal", action="store_true", help="do not build the universal APK")
    args = parser.parse_args(argv)
    try:
        if args.manifest_only:
            text = build_manifest(ROOT, read_version(ROOT))
            if args.output:
                Path(args.output).write_text(text, encoding="utf-8", newline="\n")
            else:
                sys.stdout.write(text)
        else:
            make_release(ROOT, force=args.force, universal=not args.no_universal)
    except ReleaseError as exc:
        print(f"make_release: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
