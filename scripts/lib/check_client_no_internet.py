#!/usr/bin/env python3
"""Fail when a Tenshu client app's MERGED manifest holds android.permission.INTERNET.

Every first-party app except Control talks only to Control over Binder; Control does all the
networking (House Rule #1, enforced at the OS boundary). Each client's own manifest strips
INTERNET with `tools:node="remove"`, but that is a per-app line a new app can forget, and a
dependency can merge the permission back in at any time — on 2026-10-23 Drive's release build
was found holding INTERNET via ML Kit's datatransport/CCT telemetry, which nothing had flagged
because Drive's source manifest simply never requested it. So the check reads what ships: the
merged manifest Gradle writes, not the source one.

It also fails a client that still registers any datatransport backend (`backend:*` meta-data on
`TransportBackendDiscovery` — CCT is Google's telemetry uploader): without INTERNET it cannot
send, but registered, it keeps retrying.

The app set is derived from settings.gradle.kts — `:control` plus every `:*-android` module — so
an eleventh app is checked the day it is included.

usage: check_client_no_internet.py [--variant release|debug] [app ...]
       (apps by short name — drive, mia, … — default: all of them; run each app's
        :<module>:process<Variant>Manifest first)
       check_client_no_internet.py --list-tasks [--variant release|debug]
       (prints the :<module>:process<Variant>Manifest task of every app, for the smoke gate)
exit 0 = clean, 1 = a client holds INTERNET (or the manifest is missing)
"""
import argparse, os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ANDROID = "{http://schemas.android.com/apk/res/android}"


def all_apps():
    """`control` + every `:<app>-android` module included in settings.gradle.kts."""
    with open(os.path.join(ROOT, "settings.gradle.kts")) as f:
        modules = re.findall(r'^\s*include\(\s*"(:[^"]+)"', f.read(), re.M)
    apps = [m[1:-len("-android")] for m in modules if m.endswith("-android") and m.count(":") == 1]
    if ":control" in modules:
        apps.insert(0, "control")
    return apps


ALL_APPS = all_apps()
# The one app allowed to reach the network. Nothing else, ever: add an app here and you have
# moved a trust boundary, which owes docs/sync/threat-model.md an amendment.
NETWORKED = {"control"}
INTERNET = "android.permission.INTERNET"
DISCOVERY = "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"


def module_of(app):
    return "control" if app == "control" else f"{app}-android"


def merged_manifest(app, variant):
    v = variant[0].upper() + variant[1:]
    return os.path.join(ROOT, module_of(app), "build", "intermediates", "merged_manifests",
                        variant, f"process{v}Manifest", "AndroidManifest.xml")


def problems(path):
    """What in this merged manifest a client may not have."""
    root = ET.parse(path).getroot()
    found = []
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for el in root.iter(tag):
            if el.get(ANDROID + "name") == INTERNET:
                found.append(f"<{tag} {INTERNET}>")
    for svc in root.iter("service"):
        if svc.get(ANDROID + "name") != DISCOVERY:
            continue
        for el in svc.iter("meta-data"):
            name = el.get(ANDROID + "name") or ""
            if name.startswith("backend:"):
                found.append(f"datatransport telemetry backend ({name})")
    return found


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="release")
    ap.add_argument("--list-tasks", action="store_true")
    ap.add_argument("apps", nargs="*")
    args = ap.parse_args(argv)
    v = args.variant[0].upper() + args.variant[1:]
    if args.list_tasks:
        print(" ".join(f":{module_of(a)}:process{v}Manifest" for a in ALL_APPS))
        return 0
    if len(ALL_APPS) < 2 or "control" not in ALL_APPS:
        # ⚠ A parse that finds nothing must not read as "every app is clean".
        print(f"✗ could not derive the app list from settings.gradle.kts (got {ALL_APPS})", file=sys.stderr)
        return 1
    apps = args.apps or ALL_APPS
    failed = False
    for app in apps:
        if app not in ALL_APPS:
            print(f"✗ unknown app '{app}' (one of: {' '.join(ALL_APPS)})", file=sys.stderr)
            return 1
        path = merged_manifest(app, args.variant)
        if not os.path.isfile(path):
            # ⚠ Never a pass: an absent manifest is an unchecked app, not a clean one.
            print(f"✗ {app}: no merged {args.variant} manifest at {os.path.relpath(path, ROOT)} — "
                  f"run :{module_of(app)}:process{v}Manifest first",
                  file=sys.stderr)
            failed = True
            continue
        if app in NETWORKED:
            continue
        found = problems(path)
        if found:
            failed = True
            print(f"✗ {app} ({args.variant}): {', '.join(found)} — a client's egress is Control's. "
                  f"Strip it in {module_of(app)}/src/main/AndroidManifest.xml with "
                  f'tools:node="remove" (see drive-android\'s for both)', file=sys.stderr)
    if failed:
        return 1
    print(f"✓ no client holds INTERNET ({args.variant}: {' '.join(a for a in apps if a not in NETWORKED)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
