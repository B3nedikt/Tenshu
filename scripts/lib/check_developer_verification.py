#!/usr/bin/env python3
"""Is each first-party package registered for Android developer verification with the side-channel key?

  python3 scripts/lib/check_developer_verification.py [app ...]

Asks Google's Android Developer ID Status API (read-only) for every app in android-release.sh's list,
or the ones named, with the side-channel digest pinned in FirstPartySigning.kt. Exits 1 unless every
answer is REGISTERED. Why it matters: docs/how-to/android-release.md "The side-channel key" and
docs/proposals/android-direct-apk-distribution.md D5 (a website APK whose key is not registered stops
installing on certified devices once enforcement reaches the user's country).

The API key comes from the Keychain (account `tenshu`, item `android-developer-id-status-api-key`;
Google Cloud project `tenshu`, restricted to that one API) or $ANDROID_DEVELOPER_ID_STATUS_API_KEY.
"""
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import direct_release  # noqa: E402

ENDPOINT = "https://androiddeveloperidstatus.googleapis.com/v1/packages/{}/packageRegistrationStatus:check"
KEYCHAIN_ITEM = "android-developer-id-status-api-key"


def all_apps(root: str) -> list[str]:
    """The ten apps, from android-release.sh, so the two lists cannot drift."""
    with open(os.path.join(root, "scripts", "android-release.sh")) as f:
        m = re.search(r'^ALL_APPS="([^"]+)"', f.read(), re.M)
    if not m:
        raise ValueError("android-release.sh no longer declares ALL_APPS")
    return m.group(1).split()


def query_fingerprint(colon_digest: str) -> str:
    """`AB:CD:…` (keytool, FirstPartySigning) → `abcd…`, the form the API's examples use."""
    if not direct_release.DIGEST.match(colon_digest):
        raise ValueError(f"not a colon-separated SHA-256 digest: {colon_digest!r}")
    return colon_digest.replace(":", "").lower()


def api_key() -> str:
    key = os.environ.get("ANDROID_DEVELOPER_ID_STATUS_API_KEY", "").strip()
    if key:
        return key
    out = subprocess.run(["security", "find-generic-password", "-a", "tenshu", "-s", KEYCHAIN_ITEM, "-w"],
                         capture_output=True, text=True)
    if out.returncode != 0 or not out.stdout.strip():
        sys.exit(f"no API key: set ANDROID_DEVELOPER_ID_STATUS_API_KEY or the Keychain item {KEYCHAIN_ITEM!r}")
    return out.stdout.strip()


def check(key: str, package: str, digest: str) -> str:
    url = ENDPOINT.format(package) + "?" + urllib.parse.urlencode({"certificateFingerprint": query_fingerprint(digest)})
    req = urllib.request.Request(url, headers={"X-Goog-Api-Key": key, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r).get("state", "no state in the answer")
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read())["error"]
            return f"HTTP {e.code} {err.get('status')}: {err.get('message')}"
        except (ValueError, KeyError):
            return f"HTTP {e.code}"


def main(argv: list[str]) -> int:
    with open(os.path.join(direct_release.ROOT, direct_release.FIRST_PARTY_SIGNING)) as f:
        digest = direct_release.pinned_digest(f.read())
    if digest is None:
        sys.exit("no side-channel key is pinned in FirstPartySigning.kt: nothing to check")
    apps = argv or all_apps(direct_release.ROOT)
    key = api_key()
    failed = 0
    for app in apps:
        package = f"app.tenshu.{app}.android"
        state = check(key, package, digest)
        mark = "✓" if state == "REGISTERED" else "✗"
        failed += state != "REGISTERED"
        print(f"{mark} {package}: {state}")
    print(f"side-channel key {digest}: {len(apps) - failed}/{len(apps)} registered")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
