#!/usr/bin/env python3
"""The hero's phone: each app's frame 1 on an Android phone that is a device of the desktop capture
realm (docs/how-to/store-screenshots.md §10.3), so the landing hero's laptop and phone show the same
content (store-screenshot-stories.md §5, rule 6).

    ANDROID_SERIAL=emulator-5556 python3 scripts/lib/android_hero.py [--lang en|de] [--appearance light|dark]

Nothing is installed, seeded or signed in here: the phone must already be paired into the realm and
synced (§10.3). Writes store/<app>/screenshots/android/phonehero/<lang>/[dark/]01-<screen>.png —
a device of its own, so it never replaces the support or landing sets.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import android_shots as a  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PKG = {app: f"app.tenshu.{app}.android" for app in ("drive", "photos", "calendar", "contacts", "fit")}


def open_app(app):
    a.adb("shell", "am", "force-stop", PKG[app], check=False)
    a.adb("shell", "monkey", "-p", PKG[app], "-c", "android.intent.category.LAUNCHER", "1", check=False)
    time.sleep(6)


def tap_any(*labels, timeout=60, desc=False, startswith=False):
    """Tap the first of LABELS on screen — one per language, since --lang picks the app's locale."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for label in labels:
            if a.find(label, desc=desc, startswith=startswith):
                return a.tap(label, desc=desc, startswith=startswith)
        time.sleep(1)
    sys.exit(f"android_hero: none of {labels} appeared")


def drive():
    open_app("drive")
    tap_any("Files", "Dateien")
    time.sleep(2)
    for label in ("Switch to list view", "Zur Listenansicht wechseln"):
        if a.find(label, desc=True):
            a.tap(label, desc=True)
    time.sleep(3)
    return "01-files"


def photos():
    open_app("photos")
    # Grouped by month, as on the desktop (store-screenshots.md §10).
    for _ in range(4):
        if a.find("Grouped by month.", desc=True, startswith=True) or a.find("Nach Monat gruppiert.", desc=True, startswith=True):
            break
        tap_any("Grouped by", "Nach ", desc=True, startswith=True)
        time.sleep(1.5)
    time.sleep(8)  # the first screen's thumbnails decode
    return "01-library"


def calendar():
    open_app("calendar")
    tap_any("Month", "Monat")
    tap_any("Today", "Heute")
    time.sleep(3)
    return "01-month"


def contacts():
    open_app("contacts")
    # A locale switch restarts Control too: the list can spin for a while after launch.
    a.wait("Aiko Tanaka", timeout=90)  # the fixture's first contact (store-screenshots.md §10.1)
    time.sleep(4)  # portraits decode
    return "01-contacts"


def fit():
    open_app("fit")
    # ⚠ With Health Connect granted, Fit imports on every launch and Home draws empty until it is
    # done: wait for the day's figures, not a fixed beat.
    a.wait("kcal", timeout=90)  # the same in every language
    time.sleep(3)
    return "01-home"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--lang", default="en", choices=["en", "de"])
    p.add_argument("--appearance", default="light", choices=["light", "dark"])
    p.add_argument("apps", nargs="*", default=["drive", "photos", "calendar", "contacts", "fit"])
    args = p.parse_args()
    if not a.SERIAL:
        sys.exit("android_hero: set ANDROID_SERIAL to the paired phone")
    a.adb("shell", "cmd", "uimode", "night", "yes" if args.appearance == "dark" else "no")
    for pkg in PKG.values():
        a.adb("shell", "cmd", "locale", "set-app-locales", pkg, "--locales", "de-DE" if args.lang == "de" else "en-US")
    for app in args.apps:
        name = globals()[app]()
        out = os.path.join(ROOT, "store", app, "screenshots", "android", "phonehero", args.lang)
        if args.appearance == "dark":
            out = os.path.join(out, "dark")
        os.makedirs(out, exist_ok=True)
        a.OUT = out
        a.shot(name)
        print(f"  {app}: {os.path.join(out, name)}.png", flush=True)


if __name__ == "__main__":
    main()
