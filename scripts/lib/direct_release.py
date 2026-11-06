#!/usr/bin/env python3
"""The checks and outputs of a direct release: APKs for outside Google Play (android-release.sh --direct).

  direct_release.py generator-tasks
      the Gradle tasks that write the config `endpoints` reads, for the build's own invocation
  direct_release.py pinned-digest
      the side-channel certificate pinned in FirstPartySigning.kt, colon-separated, or nothing
  direct_release.py signer <apk>
      the APK's one signing certificate, SHA-256, colon-separated (apksigner verifies it first)
  direct_release.py badging <apk> <package> <versionCode>
      fail unless the APK is that package at that versionCode (a stale APK from an earlier build)
  direct_release.py abi <apk>
      fail unless every native library in the APK is for ABI (arm64-v8a, decision D2)
  direct_release.py endpoints
      fail unless the build baked the production relay, commerce and issuer, and the Stripe rail
  direct_release.py manifest <out-dir> <versionCode> <versionName> <cert> <app>=<apk> ...
      write <out-dir>/SHA256SUMS and <out-dir>/release.json for the files already in <out-dir>

Why each one exists is in docs/how-to/android-release.md, "Releasing outside Play".
"""
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

FIRST_PARTY_SIGNING = "core-common/src/androidMain/kotlin/org/tenshu/core/common/transport/FirstPartySigning.kt"

# What a build for users must have baked in. Each value is what the generator writes when nothing
# overrides it (`:control-shared-proxy`, `:identity-service`, `:control-shared` build scripts).
RELAY_CONFIG = "control-shared-proxy/build/generated/relayConfig/proxy/app/tenshu/control/proxy/RelayConfig.kt"
ISSUER_CONFIG = "identity-service/build/generated/buildConfiguredIssuer/org/tenshu/identity/BuildConfiguredIssuer.kt"
RAIL_CONFIG = "control-shared/build/generated/billingRailConfig/app/tenshu/control/ui/BillingRailConfig.kt"
EXPECTED = [
    (RELAY_CONFIG, r'RELAY_URL: String = "([^"]*)"', "https://relay.tenshu.app"),
    (RELAY_CONFIG, r'COMMERCE_URL: String = "([^"]*)"', "https://commerce.service.tenshu.app"),
    (ISSUER_CONFIG, r'PUBLISHER_ID: String = "([^"]*)"', ""),
    (RAIL_CONFIG, r"ANDROID_BILLING_RAIL: BillingRail = BillingRail\.(\w+)", "STRIPE"),
]
# The Gradle tasks that write those files, run with the APKs so the files describe this build.
GENERATOR_TASKS = [
    ":control-shared-proxy:generateProxyRelayConfig",
    ":identity-service:generateBuildConfiguredIssuer",
    ":control-shared:generateBillingRailConfig",
]

# The one ABI a direct APK carries (android-direct-apk-distribution.md D2; root build.gradle.kts
# `DIRECT_RELEASE_ABI`). Another ABI later is a second APK with its own versionCode, never a return
# to universal.
ABI = "arm64-v8a"

DIGEST = re.compile(r"^[0-9A-F]{2}(?::[0-9A-F]{2}){31}$")


def colon_hex(hex_digest: str) -> str:
    """`ab12…` (apksigner) → `AB:12:…` (keytool, FirstPartySigning)."""
    h = hex_digest.strip().upper()
    if not re.fullmatch(r"[0-9A-F]{64}", h):
        raise ValueError(f"not a SHA-256 digest: {hex_digest!r}")
    return ":".join(h[i:i + 2] for i in range(0, 64, 2))


def pinned_digest(source: str) -> str | None:
    """The side-channel digest FirstPartySigning pins, or None while the slot is empty."""
    m = re.search(r'val SIDE_CHANNEL_SIGNING_CERT_SHA256: String\? =\s*(null|"([^"]*)")', source)
    if not m:
        raise ValueError("FirstPartySigning.kt no longer declares SIDE_CHANNEL_SIGNING_CERT_SHA256")
    if m.group(1) == "null":
        return None
    if not DIGEST.match(m.group(2)):
        raise ValueError(f"SIDE_CHANNEL_SIGNING_CERT_SHA256 is malformed: {m.group(2)!r}")
    return m.group(2)


def signers(apksigner_output: str) -> list[str]:
    """Every signer's certificate digest in `apksigner verify --print-certs` output."""
    return [colon_hex(d) for d in re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$",
                                             apksigner_output, re.M)]


def endpoint_problems(root: str) -> list[str]:
    problems = []
    for rel, pattern, want in EXPECTED:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            problems.append(f"{rel} is missing: run {' '.join(GENERATOR_TASKS)} with the build")
            continue
        with open(path) as f:
            m = re.search(pattern, f.read())
        if not m:
            problems.append(f"{rel}: no match for {pattern}")
        elif m.group(1) != want:
            problems.append(f"{rel}: baked {m.group(1)!r}, a release for users needs {want!r}")
    return problems


def abi_problems(names: list[str]) -> list[str]:
    """APK entry names → what is wrong with its native libraries: another ABI, or none at all."""
    abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2})
    if not abis:
        return [f"no native libraries at all, expected lib/{ABI}/ (was the ABI filter applied?)"]
    return [f"carries lib/{a}/, a direct APK is {ABI} only" for a in abis if a != ABI]


def badging_problems(badging: str, package: str, version_code: str) -> list[str]:
    m = re.search(r"^package: name='([^']*)' versionCode='([^']*)'", badging, re.M)
    if not m:
        return ["aapt2 printed no package line"]
    problems = []
    if m.group(1) != package:
        problems.append(f"package is {m.group(1)}, expected {package}")
    if m.group(2) != version_code:
        problems.append(f"versionCode is {m.group(2)}, expected {version_code} (a stale APK?)")
    return problems


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def write_manifest(out_dir: str, version_code: int, version_name: str, cert: str,
                   apps: dict[str, str]) -> dict:
    """SHA256SUMS (the `shasum -a 256 -c` format) and release.json, over the APKs in out_dir."""
    if not DIGEST.match(cert):
        raise ValueError(f"not a colon-separated SHA-256 certificate digest: {cert!r}")
    entries = {}
    for app, name in sorted(apps.items()):
        path = os.path.join(out_dir, name)
        entries[app] = {
            "package": f"app.tenshu.{app}.android",
            "file": name,
            "sha256": sha256_file(path),
            "bytes": os.path.getsize(path),
        }
    with open(os.path.join(out_dir, "SHA256SUMS"), "w") as f:
        for e in entries.values():
            f.write(f"{e['sha256']}  {e['file']}\n")
    release = {
        "versionCode": version_code,
        "versionName": version_name,
        "abi": ABI,
        "signingCertSha256": cert,
        "apps": entries,
    }
    with open(os.path.join(out_dir, "release.json"), "w") as f:
        json.dump(release, f, indent=2)
        f.write("\n")
    return release


def build_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name)),
                   key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", p.split("/")[-2])])
    if not tools:
        sys.exit(f"direct_release: no {name} under $ANDROID_HOME/build-tools")
    return tools[-1]


def main(argv: list[str]) -> int:
    if not argv:
        sys.exit(__doc__)
    cmd, args = argv[0], argv[1:]
    if cmd == "generator-tasks":
        print(" ".join(GENERATOR_TASKS))
        return 0
    if cmd == "pinned-digest":
        with open(os.path.join(ROOT, FIRST_PARTY_SIGNING)) as f:
            print(pinned_digest(f.read()) or "")
        return 0
    if cmd == "signer":
        out = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", args[0]],
                             capture_output=True, text=True)
        if out.returncode != 0:
            print(f"✗ {args[0]}: apksigner refuses it:\n{out.stderr or out.stdout}", file=sys.stderr)
            return 1
        found = signers(out.stdout)
        if len(found) != 1:
            print(f"✗ {args[0]}: {len(found)} signers, expected exactly one", file=sys.stderr)
            return 1
        print(found[0])
        return 0
    if cmd == "badging":
        apk, package, version_code = args
        out = subprocess.run([build_tool("aapt2"), "dump", "badging", apk], capture_output=True, text=True)
        problems = badging_problems(out.stdout, package, version_code)
        for p in problems:
            print(f"✗ {os.path.basename(apk)}: {p}", file=sys.stderr)
        return 1 if problems else 0
    if cmd == "abi":

        with zipfile.ZipFile(args[0]) as z:
            problems = abi_problems(z.namelist())
        for p in problems:
            print(f"✗ {os.path.basename(args[0])}: {p}", file=sys.stderr)
        return 1 if problems else 0
    if cmd == "endpoints":
        problems = endpoint_problems(ROOT)
        for p in problems:
            print(f"✗ {p}", file=sys.stderr)
        if problems:
            print("  → a local.properties line, a TENSHU_* variable or a -P flag repointed this build.",
                  file=sys.stderr)
        return 1 if problems else 0
    if cmd == "manifest":
        out_dir, version_code, version_name, cert, *pairs = args
        apps = dict(p.split("=", 1) for p in pairs)
        write_manifest(out_dir, int(version_code), version_name, cert, apps)
        return 0
    sys.exit(f"direct_release: unknown command {cmd!r}\n{__doc__}")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
