#!/usr/bin/env python3
"""The Android screenshot walk (docs/how-to/store-screenshots.md §8), driven over adb.

    android_shots.py control --out DIR --email ADDR --otp-file FILE [--offline]
    android_shots.py drive   --out DIR --fixture DIR [--offline]
    android_shots.py drive   --out DIR --fixture DIR --set landing     # the landing content set (§9)
    android_shots.py photos  --out DIR --set landing                   # after the landing Drive walk
    … --lang de                                                         # every app set to German (§8)

Run by scripts/store-screenshots-android.sh, never by hand: it expects a FRESH install of Control
and Drive on the emulator named by $ANDROID_SERIAL, with the status bar already in demo mode.

Every screen is reached by taps on what the shipping app draws, found by its text in a
`uiautomator dump` — no deep link, no test hook, no debug screen. Each capture is
`adb exec-out screencap -p`, written to DIR/NN-screen.png, and its name printed on stdout (the
shell script collects the list for its screens.json check). Every `shot("NN-name")` call below
is a screen name `web/apex/support-pages/screens.test.ts` checks against
`store/<app>/screens.json` (`android.captured`); every `landing_shot("NN-name")` one it checks
against that file's `landing.captured` — the landing page's own content set (§9), a separate realm
seeded from store/fixtures/landing/, published under the device `phonelanding` so it never replaces
a support-page capture.

⚠ The walk stays in ONE foreground session of Control: its app lock is on by default, re-arms
when the app goes to the background, and cannot be passed on an emulator without a screen lock.
Control's part therefore runs completely before Drive is ever launched.

Every label below is the ENGLISH one. With `--lang de` (the shell script has set each Tenshu app's
locale to de-DE) find() also accepts that label's German: the table is read from the apps' own
string resources — `values/strings.xml` against `values-de/strings.xml`, by resource name, Android
`res/` and Compose `composeResources/` alike — so a walk step needs no German of its own. A format
string is matched as a pattern (its arguments carried over), an all-caps label as the caps of its
resource, and the English always matches too: system UI (DocumentsUI, Health Connect) follows the
SYSTEM locale, which stays English, and a string the app has no German for is shown in English.
TRANSLATION_OVERRIDES holds the few labels that come from no app resource.
"""
import argparse
import os
import re
import subprocess
import json
import sys
import time

SERIAL = os.environ.get("ANDROID_SERIAL", "")
CONTROL = "app.tenshu.control.android"
DRIVE = "app.tenshu.drive.android"
PHOTOS = "app.tenshu.photos.android"


def adb(*args, check=True, capture=True):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=capture)
    if check and r.returncode != 0:
        sys.exit(f"android-shots: adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout


NODE = re.compile(r"<node [^>]*>")
# ⚠ uiautomator writes a value that contains a double quote in SINGLE quotes
# (text='"Priya R." and "Priya Raman"'), so both forms are read.
ATTR = re.compile(r"""([a-z-]+)=(?:"([^"]*)"|'([^']*)')""")


def attrs(node):
    return {k: dq or sq for k, dq, sq in ATTR.findall(node)}
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def unescape(s):
    return s.replace("&quot;", '"').replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")


def nodes():
    """The current window as (text, content-desc, (cx, cy)) triples."""
    for _ in range(5):
        xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
        if "<hierarchy" in xml:
            break
        time.sleep(1)  # "could not get idle state" while an animation runs
    out = []
    for m in NODE.finditer(xml):
        a = attrs(m.group(0))
        b = BOUNDS.match(a.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        out.append((unescape(a.get("text", "")), unescape(a.get("content-desc", "")), ((x1 + x2) // 2, (y1 + y2) // 2)))
    return out

# ── The language (store-screenshots.md §8) ───────────────────────────────────────────────────────
LANG = "en"
APP = ""
# Which modules' strings an app draws (its own first). A label missing from them is looked up in
# every module's: a string can reach an app from a library the list does not name.
APP_MODULES = {
    "control": ["control", "control-shared", "control-shared-proxy", "sync-service-proxy", "ui-core"],
    "drive": ["drive-android", "drive-shared-ui", "sync-service-proxy", "ui-core"],
    "photos": ["photos-android", "photos-shared-ui", "drive-shared-ui", "ui-core"],
    "fit": ["fit-android", "fit-shared-ui", "ui-map", "ui-core"],
    "calendar": ["calendar-android", "calendar-shared-ui", "ui-core"],
    "contacts": ["contacts-android", "contacts-shared-ui", "ui-core"],
    "timeline": ["timeline-android", "timeline-shared-ui", "ui-map", "ui-core"],
    "maps": ["maps-android", "maps-shared-ui", "ui-map", "ui-core"],
    "mia": ["mia-android", "mia-shared-ui", "ui-core"],
    "home": ["home-android", "home-shared-ui", "ui-core"],
}
# Labels no Tenshu resource carries: the framework's own strings, in their German.
TRANSLATION_OVERRIDES = {
    "de": {
        "Close sheet": ["Tabelle schließen"],  # androidx.compose.ui `close_sheet` (sic, AOSP's German)
        # Fragments the walk tests with `in` / endswith (wait_text), from the resources named.
        " and ": [" und "],                    # contacts_merge_pair
        "last night": ["letzte Nacht"],        # fit_teaser_sleep
        "items ·": ["Einträge ·", "Eintrag ·"],  # fit_source_status_imported_at(_one)
    },
}
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXeEgGc]")
_TABLES = None


def _resource_strings(path):
    """name → text of a strings.xml, each plural quantity as `name#quantity`."""
    import xml.etree.ElementTree as ET
    out = {}
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return out

    # ⚠ Only Android `res/` strips a pair of enclosing quotes; Compose resources keep them
    # (contacts' `"%1$s" and "%2$s"` renders with its quotes).
    android_res = "/composeResources/" not in path

    def text(el):
        s = "".join(el.itertext()).strip()
        if android_res and len(s) >= 2 and s[0] == s[-1] == '"':
            s = s[1:-1]
        return (s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
                .replace("\\@", "@").replace("\\?", "?"))

    for el in root:
        if el.tag == "string" and el.get("name"):
            out[el.get("name")] = text(el)
        elif el.tag == "plurals" and el.get("name"):
            for item in el:
                out[f"{el.get('name')}#{item.get('quantity')}"] = text(item)
    return out


def _tables():
    """module → [(english, german)], read once from the repository's string resources."""
    global _TABLES
    if _TABLES is None:
        import glob
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        _TABLES = {}
        for en in sorted(glob.glob(f"{root}/*/src/*/composeResources/values/strings.xml")
                         + glob.glob(f"{root}/*/src/*/res/values/strings.xml")):
            de = en.replace("/values/strings.xml", f"/values-{LANG}/strings.xml")
            if not os.path.exists(de):
                continue
            module = os.path.relpath(en, root).split(os.sep)[0]
            e, d = _resource_strings(en), _resource_strings(de)
            _TABLES.setdefault(module, []).extend((e[k], d[k]) for k in e if k in d and e[k] and d[k])
    return _TABLES


def _pattern(template, args=None):
    """A format string as a regex: each placeholder becomes its argument (an alternation of the
    strings in ARGS[index]) or, without one, a wildcard."""
    out, pos, seq, seen = [], 0, 0, set()
    for m in PLACEHOLDER.finditer(template):
        out.append(re.escape(template[pos:m.start()]))
        seq += 1
        index = int(m.group(1)) if m.group(1) else seq
        alts = (args or {}).get(index)
        if alts:
            out.append("(?:" + "|".join(re.escape(a) for a in alts) + ")")
        elif index in seen:
            out.append(f"(?P=a{index})")  # the same argument twice
        else:
            out.append(f"(?P<a{index}>.+?)")
            seen.add(index)
        pos = m.end()
    out.append(re.escape(template[pos:]))
    return "".join(out)


def _translations(label, startswith):
    """Regexes (to be matched at the start of a node's text) for LABEL's German, or [] if none."""
    tables = _tables()
    scoped = [p for m in APP_MODULES.get(APP, []) for p in tables.get(m, [])]
    everything = [p for pairs in tables.values() for p in pairs]
    for pairs in (scoped, everything):
        found = []
        plain = {}
        for en, de in pairs:
            plain.setdefault(en, set()).add(de)
        for en, de in pairs:
            if PLACEHOLDER.search(en):
                if not re.search(r"[^\W\d_]", PLACEHOLDER.sub("", en)):
                    continue  # "%1$s %2$s" and the like: no words of its own, so nothing to translate
                m = re.fullmatch(_pattern(en), label, re.S)
                if m:  # a rendered format string: its arguments, each with its own German too
                    args = {int(k[1:]): {v} | plain.get(v, set()) for k, v in m.groupdict().items()}
                    found.append(_pattern(de, args))
                elif startswith and PLACEHOLDER.split(en)[0].startswith(label) and PLACEHOLDER.split(en)[0]:
                    found.append(_pattern(de))
            elif en == label or (startswith and en.startswith(label)):
                found.append(re.escape(de))
            elif label.isupper() and en.upper() == label:  # a title the app draws in capitals
                found.append(re.escape(de.upper()))
        if found:
            return found
    return []


_MATCHERS = {}


def matcher(label, startswith=False):
    """A predicate on a node's text (or description): LABEL, or in German mode its German."""
    key = (label, startswith)
    if key not in _MATCHERS:
        if LANG == "en":
            _MATCHERS[key] = (lambda v: v.startswith(label)) if startswith else (lambda v: v == label)
        else:
            alts = [re.escape(label)] + _translations(label, startswith)
            alts += [re.escape(t) for t in TRANSLATION_OVERRIDES.get(LANG, {}).get(label, [])]
            # ⚠ One regex per alternative: two templates in one alternation would both define the
            # named group `a1` (_pattern), which `re` refuses.
            rxs = [re.compile(a, re.S) for a in dict.fromkeys(alts)]
            _MATCHERS[key] = ((lambda v: any(r.match(v) for r in rxs)) if startswith
                              else (lambda v: any(r.fullmatch(v) for r in rxs)))
    return _MATCHERS[key]


def contains(fragment):
    """A predicate: the node's text contains FRAGMENT or, in German mode, one of the overrides'
    German for it (wait_text's free-form checks, which no resource lookup can answer)."""
    alts = [fragment] + TRANSLATION_OVERRIDES.get(LANG, {}).get(fragment, [])
    return lambda t: any(a in t for a in alts)


# System dialogs that can cover the screen mid-walk (both seen over Control's sign-in):
# Gboard asks for contacts access the first time an email field takes focus. Refused, walked on.
INTERRUPTIONS = (
    ("Allow Gboard to access contacts", "Don’t allow"),
    # Gboard's translate key, when a tap meant for a button under the keyboard hits it.
    ("Using Google Translate", "Cancel"),
)


def dismiss_interruption(current):
    for prompt, refuse in INTERRUPTIONS:
        if any(t.startswith(prompt) for t, _, _ in current):
            for t, _, (x, y) in current:
                if t == refuse:
                    adb("shell", "input", "tap", str(x), str(y))
                    time.sleep(1)
                    return True
    return False


def find(label, desc=False, startswith=False):
    ok = matcher(label, startswith)
    current = nodes()
    if dismiss_interruption(current):
        current = nodes()
    for text, cd, center in current:
        if ok(cd if desc else text):
            return center
    return None


def wait(label, timeout=60, desc=False, startswith=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        c = find(label, desc=desc, startswith=startswith)
        if c:
            return c
        time.sleep(1)
    visible = [t or d for t, d, _ in nodes() if t or d]
    sys.exit(f"android-shots: '{label}' never appeared (on screen: {visible[:15]})")


def tap(label, timeout=60, desc=False, startswith=False):
    x, y = wait(label, timeout, desc=desc, startswith=startswith)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def tap_card(label, timeout=60):
    """Tap the centre of the smallest clickable node around LABEL — a card whose caption is the
    label. A tap on the caption itself, at the card's edge, was seen not to open it."""
    x, y = card_center(label, timeout)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def card_center(label, timeout=60):
    """The centre of the smallest clickable node around LABEL (tap_card's target)."""
    lx, ly = wait(label, timeout)
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
    best = None
    for m in NODE.finditer(xml):
        a = attrs(m.group(0))
        b = BOUNDS.match(a.get("bounds", ""))
        if a.get("clickable") != "true" or not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x1 <= lx <= x2 and y1 <= ly <= y2 and (best is None or (x2 - x1) * (y2 - y1) < best[0]):
            best = ((x2 - x1) * (y2 - y1), (x1 + x2) // 2, (y1 + y2) // 2)
    return (best[1], best[2]) if best else (lx, ly)


def tap_lowest(label, timeout=60):
    """Tap the lowest node with this text: a bottom-bar tab rather than a row of the same name
    (the fixture has a folder called Home, and Drive has a Home tab)."""
    wait(label, timeout)
    ok = matcher(label)
    x, y = max((c for t, _, c in nodes() if ok(t)), key=lambda c: c[1])
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def keyboard_showing():
    out = adb("shell", "dumpsys", "window", check=False).decode(errors="replace")
    return "mImeShowing=true" in out


def hide_keyboard():
    """Close the soft keyboard, and make sure it is closed: a tap aimed at a button it covers lands
    on the keyboard instead — "…example.comy" in a German sign-up, Gboard's translate
    key hit by "Email me a code".

    ESCAPE first — it closes the keyboard without navigating. ⚠ Gboard does not always honour it
    (bilingual DE · EN, or still coming up on the Android 17 image). BACK then closes it, sent only
    while the window manager has reported it shown on two reads half a second apart: BACK with no
    keyboard up would leave the screen, and a keyboard ESCAPE just closed can still read as shown."""
    adb("shell", "input", "keyevent", "111")  # ESCAPE
    for _ in range(5):
        time.sleep(1)
        if not keyboard_showing():
            return
        time.sleep(0.5)
        if keyboard_showing():
            adb("shell", "input", "keyevent", "4")  # BACK: closes the keyboard while it is shown
    if keyboard_showing():
        sys.exit("android-shots: the soft keyboard would not close")


def type_text(text, close_keyboard=True):
    adb("shell", "input", "text", text)
    if close_keyboard:
        hide_keyboard()  # it is not in a capture, and it covers buttons
    else:
        time.sleep(1)


def quiet_notifications():
    """Snooze whatever the system posted (e.g. Safety Center's "no screen lock"), so the status
    bar shows no notification icon. Demo mode's `notifications visible false` no longer hides them
    on Android 17. A notification that refuses the snooze (an ongoing one) stays and is visible in
    the capture — look at the pictures."""
    dump = adb("shell", "dumpsys", "notification", "--noredact", check=False).decode(errors="replace")
    for key in set(re.findall(r"NotificationRecord\(\S+: pkg=\S+ .*? key=(\S+?): Notification", dump)):
        adb("shell", "cmd", "notification", "snooze", "--for", "86400000", f"'{key}'", check=False)


DEMO = [
    ["exit"],
    ["enter"],
    ["clock", "-e", "hhmm", "0941"],
    ["battery", "-e", "level", "100", "-e", "plugged", "false", "-e", "powersave", "false"],
    ["network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"],
    ["network", "-e", "mobile", "hide"],
    ["network", "-e", "nosim", "hide"],
    ["network", "-e", "airplane", "hide"],
    ["status", "-e", "volume", "hide", "-e", "bluetooth", "hide", "-e", "location", "hide", "-e", "alarm", "hide",
     "-e", "mute", "hide", "-e", "speakerphone", "hide", "-e", "zen", "hide", "-e", "sync", "hide", "-e", "tty", "hide",
     "-e", "eri", "hide", "-e", "cast", "hide", "-e", "hotspot", "hide", "-e", "managed_profile", "hide"],
]


def demo_status_bar():
    """Re-assert the demo status bar before every capture, from scratch. ⚠ Set once at boot, it
    drifts: a capture taken minutes later can show a second Wi-Fi glyph in the mobile slot (SystemUI's
    connectivity pipeline is still settling when the boot-time commands go out). ⚠⚠ Re-sending
    the commands does NOT clear that glyph (seen on the Pixel 8 profile: every dark capture of a run
    had it); leaving demo mode and entering it again does, so the list starts with `exit`."""
    for cmd in DEMO:
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *cmd, check=False)
        if cmd == ["exit"]:
            time.sleep(1)


OUT = ""


def shot(name, settle=2.0):
    time.sleep(settle)  # let ripples, the keyboard and transitions finish
    demo_status_bar()
    quiet_notifications()
    time.sleep(1)
    time.sleep(0.5)
    png = adb("exec-out", "screencap", "-p")
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(png)
    print(name, flush=True)


def landing_shot(name, settle=2.0):
    """A capture of the landing content set (store/<app>/screens.json `landing.captured`). The same
    capture as shot(); its own name so the support-page lists and the landing lists stay apart."""
    shot(name, settle)


def promo_shot(name, settle=2.0):
    """A still of the promo set (store-screenshots.md §9, "The promo set"). The same capture as
    shot(); its own name because the promo stills are listed nowhere, and the landing and support
    lists are each checked against the calls that carry their name (`screens.test.ts`)."""
    shot(name, settle)


def launch(package):
    adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1", check=False)


def read_otp(path, timeout=300):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.exists(path):
            code = open(path).read().strip()
            if code:
                return code
        time.sleep(2)
    sys.exit("android-shots: no OTP arrived (the mailbox poller found nothing in 5 minutes)")


def control(args):
    launch(CONTROL)
    wait("Welcome to Tenshu", timeout=120)
    shot("01-welcome")
    tap("Start fresh on this device")
    wait("Protected by your device")
    shot("02-protected")
    tap("Continue")
    # "Registering your realm" → "You're set up". Continue, as the Android page's steps do.
    wait("You're set up", timeout=120)
    tap("Continue")
    wait("Home")

    if not args.offline:
        # Services → Sign in or create → "Pick a plan" (the Free plan; Android sells no in-app plan).
        tap("Services")
        tap("Sign in or create an account")
        wait("To sync your data, pick a plan")
        shot("10-services")
        tap("Continue")  # the Free card's
        wait("Sign in or create your free Tenshu account")
        shot("08-sign-in")
        tap("Email address")
        type_text(args.email)
        tap("Email me a code")
        wait("Enter your code", timeout=60)
        tap("6-digit code")
        type_text(read_otp(args.otp_file))
        tap("Verify")
        wait("Plan: Free", timeout=120)
        shot("07-storage")

    tap("Settings")
    wait("Synchronization & Devices")
    shot("06-settings")

    # Settings → Set up account recovery → Show. A throwaway realm's phrase (the session's disk
    # overlay is discarded when the emulator exits).
    tap("Set up account recovery")
    wait("Recovery Phrase")
    tap("Show", desc=True)
    wait("I have written this down")
    shot("03-recovery-phrase")
    adb("shell", "input", "keyevent", "4")  # BACK

    if not args.offline:
        # Settings → Synchronization & Devices ("Realm devices") → "+" → Add a device → Show a code.
        # The code needs a bound account, hence dev-lab runs only.
        tap("Synchronization & Devices")
        wait("Realm devices")
        tap("Scan Device", desc=True)
        wait("Add a device")
        tap("Show a code")
        wait_host_code()
        wait("Manual Code:")
        shot("09-add-device")
        tap("Stop")
        if args.hub_uri_file:
            pair_hub(args.hub_uri_file)


def wait_host_code(timeout=60):
    """Wait for the Show tab's HOST code ("Show this on your new device").

    ⚠ The tab turns itself into the NEW-device offer ("Scan with your other device", a
    `tenshu://offer` code) when the relay refuses this device: the host poll 401s, the re-register
    repair fails, and `PairingWizardViewModel` switches role. On a device that just signed up that
    is never the product's choice — it means the dev-lab relay does not accept this build, typically
    a build OLDER than the relay (a checkout behind master, against a relay built from newer
    sources). Say so instead of "never appeared"."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find("Show this on your new device"):
            return
        if find("Scan with your other device"):
            sys.exit(
                "android-shots: Show a code offered a NEW-device code — the dev-lab relay refused this "
                "device (look for 'relay-unauthorized-401' in logcat). Usually this build is older "
                "than the relay: rebuild from the commit the dev lab runs (or current master)."
            )
        time.sleep(1)
    visible = [t or d for t, d, _ in nodes() if t or d]
    sys.exit(f"android-shots: the host code never appeared (on screen: {visible[:15]})")


# Where the walk puts the fixture on the emulator, for the system picker to offer.
DEVICE_FIXTURE = "/sdcard/Documents"


def push_fixture(fixture):
    """Copy the fixture tree (store/fixtures/generate.py, built by the shell script) into the
    emulator's Documents folder. `adb push` keeps each file's mtime — the manifest's `modified` —
    and the picker hands it to Drive as the document's last-modified time, which Drive shows as the
    file's date. The emulator's overlay is discarded at exit, so nothing outlives the run."""
    adb("shell", "mkdir", "-p", DEVICE_FIXTURE)
    adb("push", os.path.join(fixture, "."), DEVICE_FIXTURE + "/")


def pick_in_documents(name):
    """In the system picker, open Documents and tap NAME. The picker opens where it was last
    left: on a fresh install the storage root (Documents is a folder there), later a folder inside
    Documents (Documents is then the breadcrumb, which comes first in the dump — before the
    picker's own "Documents" filter chip)."""
    tap("Documents")
    tap(name)


def upload_menu(entry):
    """Drive's "+" sheet → an Upload entry. "File" is under both Create and Upload; the Upload one
    is the lower of the two."""
    tap("Add", desc=True)
    wait("Folder upload")
    if entry == "File":
        tap_lowest("File")
    else:
        tap(entry)


def wait_upload_finished(timeout=180):
    """A folder upload shows "Uploading… <file>" and then "Uploaded N" for a few seconds."""
    wait("Uploaded", timeout=timeout, startswith=True)
    deadline = time.time() + 30
    while find("Uploaded", startswith=True) and time.time() < deadline:
        time.sleep(1)


def seed(fixture):
    """Fill Drive the way a user would: every top-level folder of the fixture through
    + → Upload → Folder upload, every loose file through + → Upload → File, each picked in the
    system picker. The same uploadFolder / uploadFile path as the app's own buttons — no hook."""
    push_fixture(fixture)
    entries = sorted(os.listdir(fixture))
    folders = [e for e in entries if os.path.isdir(os.path.join(fixture, e))]
    files = [e for e in entries if os.path.isfile(os.path.join(fixture, e))]
    for folder in folders:
        upload_menu("Folder upload")
        pick_in_documents(folder)
        tap("USE THIS FOLDER")
        tap("ALLOW")
        wait_upload_finished()
    for name in files:
        upload_menu("File")
        pick_in_documents(name)
        wait("Add", desc=True)
    for name in entries:
        wait(name, timeout=120)


def show_grid():
    """The folder's list/grid toggle (top bar) to grid. Its description names the target view."""
    if find("Switch to grid view", desc=True):
        tap("Switch to grid view", desc=True)
    wait("Switch to list view", desc=True)


def show_list():
    if find("Switch to list view", desc=True):
        tap("Switch to list view", desc=True)
    wait("Switch to grid view", desc=True)


def drive_until_seeded(args):
    """Drive from launch to the seeded root listing: the empty Files tab, the Not syncing banner
    gone, the fixture uploaded through + → Upload."""
    launch(DRIVE)
    wait("Files", timeout=120)
    tap("Files")
    wait("No files here yet", timeout=60)
    deadline = time.time() + 120
    while find("Not syncing", startswith=True) and time.time() < deadline:
        time.sleep(2)
    seed(args.fixture)
    time.sleep(8)  # the photo thumbnails are minted after the upload


# The landing set's photo folder and the photo its preview opens (store/fixtures/landing).
LANDING_FOLDER = "Dolomites 2026"
LANDING_PHOTO = "IMG_5102.jpg"


def drive_landing(args):
    """The landing page's Drive (§9): the root of a realistic Drive, a photo folder as a grid of
    real thumbnails, and one photo previewed."""
    drive_until_seeded(args)
    landing_shot("01-files")
    tap(LANDING_FOLDER)
    wait(LANDING_PHOTO)
    show_grid()
    time.sleep(8)
    landing_shot("02-photos")
    tap(LANDING_PHOTO)
    wait("More actions", desc=True)
    time.sleep(4)
    landing_shot("03-preview")


def grid_tiles():
    """The Photos feed's tiles: clickable nodes with no text, larger than 150 px, below the app
    bar and above the bottom bar, in reading order. Tiles carry no content description."""
    xml = ""
    for _ in range(5):
        xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
        if "<hierarchy" in xml:
            break
        time.sleep(1)
    tiles = []
    for m in NODE.finditer(xml):
        a = attrs(m.group(0))
        b = BOUNDS.match(a.get("bounds", ""))
        if not b or a.get("text") or a.get("content-desc") or a.get("clickable") != "true":
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 - x1 > 150 and y2 - y1 > 150 and y1 > 300 and y2 < 2150:
            tiles.append((y1, x1, (x1 + x2) // 2, (y1 + y2) // 2))
    return [(cx, cy) for _, _, cx, cy in sorted(set(tiles))]


def wait_tiles(count, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        t = grid_tiles()
        if len(t) >= count:
            return t
        time.sleep(2)
    sys.exit(f"android-shots: the Photos feed never showed {count} tiles")


LANDING_ALBUM = "Autumn walks"
LANDING_ALBUM_SIZE = 12


def photos_landing(args):
    """The landing page's Photos (§9), over the realm the landing Drive walk filled: the library
    (the default mosaic), an album made through Collections → New album and filled from the
    viewer's More → Add to album, and one photo full screen. Photos reads the realm's files
    (every image in it), so it needs no seeding of its own."""
    launch(PHOTOS)
    wait("Collections", timeout=120)
    wait_tiles(6)
    time.sleep(10)  # thumbnails arrive per tile, with a retry back-off (photos-app.md §41.2)
    landing_shot("01-library")

    # The album: the newest photos are the Autumn walks folder's twelve (store/fixtures/landing).
    tap_lowest("Collections")
    time.sleep(3)  # the tab refreshes its sections when it is selected
    tap_card("New album")
    tap("Name")
    # ⚠ Not ESCAPE after typing: in a dialog it dismisses the dialog, not just the keyboard.
    type_text(LANDING_ALBUM.replace(" ", "%s"), close_keyboard=False)
    tap("Create")
    tap_lowest("Photos")
    x, y = wait_tiles(1)[0]
    adb("shell", "input", "tap", str(x), str(y))
    for i in range(LANDING_ALBUM_SIZE):
        tap("More", desc=True)
        tap("Add to album")
        tap(LANDING_ALBUM)
        # From the viewer the add is silent; a multi-select add confirms with a "Done" dialog.
        time.sleep(2)
        if find("OK"):
            tap("OK")
        if i < LANDING_ALBUM_SIZE - 1:
            adb("shell", "input", "swipe", "900", "1200", "150", "1200", "250")
            time.sleep(2)
    adb("shell", "input", "keyevent", "4")  # BACK to the feed
    tap_lowest("Collections")
    time.sleep(3)
    tap_card(LANDING_ALBUM)
    wait("Back", desc=True)
    time.sleep(10)  # an album's tiles are not clickable nodes, so there is nothing to count
    landing_shot("02-album")
    adb("shell", "input", "keyevent", "4")

    # One photo full screen, with the viewer's own chrome.
    tap_lowest("Photos")
    tiles = wait_tiles(6)
    x, y = tiles[min(LANDING_VIEWER_TILE, len(tiles) - 1)]
    adb("shell", "input", "tap", str(x), str(y))
    wait("More", desc=True)
    time.sleep(5)
    landing_shot("03-viewer")


# Which feed tile the viewer capture opens (reading order).
LANDING_VIEWER_TILE = 0


# ── The promo set (docs/how-to/store-screenshots.md §9, "The promo set") ────
# The promo video's own realm: the maintainer's photos (staged by store/fixtures/promo/stage_photos.py,
# never in git), seeded like the landing set, plus the screen recordings the video shows. One photo
# is held back from the seed (--upload) so the recording can upload it for real.
PROMO_PICTURES = "/sdcard/Pictures/Promo"


class ScreenRecord:
    """`adb shell screenrecord` around a block, pulled to OUT/<name>.mp4. ⚠ screenrecord stops at
    180 s on its own; a promo clip is a few seconds."""

    def __init__(self, name):
        self.name = name
        self.remote = f"/sdcard/{name}.mp4"

    def __enter__(self):
        adb("shell", "rm", "-f", self.remote, check=False)
        # Android's own touch dots, so a viewer sees what is tapped. The overlay is read-only:
        # gone with the emulator.
        adb("shell", "settings", "put", "system", "show_touches", "1")
        self.proc = subprocess.Popen(["adb", "-s", SERIAL, "shell", "screenrecord", "--bit-rate", "16000000", self.remote])
        time.sleep(1.5)  # screenrecord's first frame comes late; the clip is trimmed in the video
        return self

    def __exit__(self, exc_type, exc, tb):
        """Stops the recording; pulls it only if the block succeeded — a clip of a step that failed
        (a stalled backup, a missing button) must not pass for a good one. Nothing here may raise
        over the block's own error."""
        time.sleep(1.5 if exc_type is None else 0)
        adb("shell", "pkill", "-INT", "screenrecord", check=False)
        adb("shell", "settings", "put", "system", "show_touches", "0", check=False)
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        if exc_type is None:
            time.sleep(2)  # the file is finalised after the signal
            adb("pull", self.remote, os.path.join(OUT, f"{self.name}.mp4"))
        adb("shell", "rm", "-f", self.remote, check=False)
        return False


def upload_photo(upload_dir):
    """(album, path) of the photo uploaded on camera: --upload holds <Album>/<name>.jpg
    (stage_photos.py), and the walk uploads it into that album's folder."""
    album = sorted(d for d in os.listdir(upload_dir) if os.path.isdir(os.path.join(upload_dir, d)))[0]
    name = sorted(f for f in os.listdir(os.path.join(upload_dir, album)) if f.lower().endswith(".jpg"))[0]
    return album, os.path.join(upload_dir, album, name)


def push_for_picker(photo):
    """The held-back photo into Pictures, indexed by the media store, so the system photo picker
    lists it — and nothing else: the seed's copies in Documents go first, so the picker's first
    tile is the photo the video shows arriving."""
    name = os.path.basename(photo)
    # The seed's copies, and what an earlier appearance or rerun left (the backup's Chat folder):
    # the emulator keeps its files across appearances, and the picker would list them all.
    adb("shell", "rm", "-rf", DEVICE_FIXTURE + "/*", PROMO_PICTURES, CHAT_FOLDER)
    adb("shell", "mkdir", "-p", PROMO_PICTURES)
    adb("push", photo, f"{PROMO_PICTURES}/{name}")
    adb("shell", "content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary", check=False)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", f"file://{PROMO_PICTURES}/{name}", check=False)
    time.sleep(3)


PICKED = "Photo taken on"   # the start of a photo tile's description in the system photo picker


def warm_up_picker():
    """Open the photo picker once off camera: its first open shows one-time banners (a Google
    Photos account notice, then "Drive will only have access to the photos you select"), each
    gone for good once dismissed. Select a photo to raise the second, then leave empty-handed."""
    upload_menu("Images")
    wait(PICKED, desc=True, startswith=True, timeout=30)
    if find("Dismiss"):
        tap("Dismiss")
    tap(PICKED, desc=True, startswith=True)
    if find("Dismiss"):
        tap("Dismiss")
    tap("Deselect all", desc=True)
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)


def listed_jpgs():
    return {t for t, _, _ in nodes() if t.lower().endswith(".jpg")}


def wait_new_file(before, timeout=120):
    """Until a .jpg not in BEFORE is listed. ⚠ Not by name: the photo picker hands Drive a
    media-store id as the display name (`32.jpg`), never the file's own."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        new = listed_jpgs() - before
        if new:
            return new.pop()
        time.sleep(1)
    sys.exit("android-shots: the picked photo never appeared in the folder")


def drive_promo(args):
    """The promo realm's Drive: seeded through the app's own Folder upload, a folder as a grid, and
    two recordings — one photo uploaded into it through + → Upload → Images, then a share link
    made for that photo through its preview's More → Link → Generate link. ⚠ The link names the
    production viewer host and the realm is the dev lab's, so it opens nothing (§2 of
    store-screenshots.md): the video shows it being made, never opened."""
    drive_until_seeded(args)
    promo_shot("01-files")
    folder, photo = upload_photo(args.upload)
    tap(folder)
    show_grid()
    time.sleep(8)
    promo_shot("02-photos")
    push_for_picker(photo)
    warm_up_picker()
    before = listed_jpgs()
    with ScreenRecord("android-upload"):
        upload_menu("Images")
        tap(PICKED, desc=True, startswith=True, timeout=30)
        tap("Done")
        uploaded = wait_new_file(before)
        time.sleep(8)  # the new tile's thumbnail is minted after the upload
    promo_shot("03-uploaded")
    tap(uploaded)
    wait("More actions", desc=True)
    time.sleep(3)
    with ScreenRecord("android-share"):
        tap("More actions", desc=True)
        tap("Link")
        tap("Generate link")
        wait("https://", timeout=60, startswith=True)
        time.sleep(3)
    record_recovery()
    if args.backup:
        # Last, and not fatal: a backup that stalls must not cost the run its other recordings.
        try:
            record_backup(args.backup)
        except SystemExit as e:
            print(f"android-shots: no backup recording — {e}", file=sys.stderr)


CHAT_FOLDER = "/sdcard/Pictures/Chat"
BACKUP_MASTER_X = 966  # the master switch's x on the 1080-wide profile; y is read from its row


def record_backup(backup_dir):
    """Synced Folders on camera: a "Chat" folder on the phone holding one photo the realm has never
    seen (⚠ a copy of a realm photo is skipped as already held — that is not a failed backup), then
    Drive → Settings → Synced folders → the master switch on, until the screen says it backed up.
    The folders the earlier walks used (Pictures/Promo, the seed in Documents) are removed first,
    so Chat is the only folder listed."""
    photo = sorted(f for f in os.listdir(backup_dir) if f.lower().endswith(".jpg"))[0]
    for perm in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "ACCESS_MEDIA_LOCATION", "POST_NOTIFICATIONS"):
        adb("shell", "pm", "grant", DRIVE, f"android.permission.{perm}", check=False)
    adb("shell", "rm", "-rf", PROMO_PICTURES, DEVICE_FIXTURE + "/*")
    adb("shell", "mkdir", "-p", CHAT_FOLDER)
    adb("push", os.path.join(backup_dir, photo), f"{CHAT_FOLDER}/{photo}")
    adb("shell", "content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary", check=False)
    time.sleep(3)
    adb("shell", "am", "force-stop", DRIVE)
    launch(DRIVE)
    tap_lowest("Files", timeout=90)
    tap("Settings", desc=True)
    tap("Synced folders")
    _, y = wait("Automatically back up folders")
    time.sleep(2)
    with ScreenRecord("android-backup"):
        adb("shell", "input", "tap", str(BACKUP_MASTER_X), str(y + 30))
        time.sleep(2.5)
        adb("shell", "input", "swipe", "540", "1900", "540", "700", "900")
        deadline = time.time() + 90
        while not find("Backed up", startswith=True):
            if time.time() > deadline:
                sys.exit("Synced Folders never reported a finished backup")
            time.sleep(1)
        time.sleep(3)


def record_recovery():
    """Control → Settings → Set up account recovery → Show: the throwaway realm's recovery phrase
    (gone with the emulator's overlay), recorded for the promo video's recovery scene."""
    adb("shell", "am", "force-stop", CONTROL)
    launch(CONTROL)
    wait("Settings", timeout=60)
    time.sleep(2)
    with ScreenRecord("android-recovery"):
        tap("Settings")
        tap("Set up account recovery")
        wait("Recovery Phrase")
        time.sleep(1)
        tap("Show", desc=True)
        wait("I have written this down")
        time.sleep(5)  # long enough to read in the video
    adb("shell", "input", "keyevent", "4")


# The promo album comes from --promo-config, a JSON file kept with the video (not in this public
# tree, since it names the maintainer's own photos): {"album": "<name>", "albumPhotos": ["<file>.jpg", …]}.
# The album is made off camera from those photos, then opened on camera and one made a favourite.


def promo_config(path):
    if not path:
        return None
    with open(path) as f:
        cfg = json.load(f)
    if not cfg.get("album") or len(cfg.get("albumPhotos") or []) < 3:
        # record_album taps the album's third tile (ALBUM_TILE).
        sys.exit(f"android-shots: {path} needs \"album\" and at least 3 \"albumPhotos\"")
    return cfg


def viewer_name():
    """The file name the Photos viewer shows at its top: the first node, by text or description,
    that names a .jpg."""
    for t, d, _ in nodes():
        for label in (t, d):
            if label.lower().endswith(".jpg"):
                return label
    return None


def make_promo_album(album, photos):
    """Collections → New album ALBUM, then the viewer swiped through the library, each of PHOTOS
    (file names) added through More → Add to album. Off camera."""
    photos = set(photos)
    tap_lowest("Collections")
    time.sleep(3)
    if not find(album):  # a rerun on a kept emulator finds it made
        tap_card("New album")
        tap("Name")
        type_text(album.replace(" ", "%s"), close_keyboard=False)
        tap("Create")
    tap_lowest("Photos")
    x, y = wait_tiles(1)[0]
    adb("shell", "input", "tap", str(x), str(y))
    wait("More", desc=True)
    seen, last = set(), None
    while True:
        name = viewer_name()
        if name == last:
            break  # the swipe went nowhere: the end of the library
        last = name
        seen.add(name)
        if name in photos:
            tap("More", desc=True)
            tap("Add to album")
            tap(album)
            time.sleep(2)
            if find("OK"):
                tap("OK")
        if photos <= seen:
            break
        adb("shell", "input", "swipe", "900", "1200", "150", "1200", "250")
        time.sleep(2)
    missing = photos - seen
    if missing:
        sys.exit(f"android-shots: the library never showed {sorted(missing)}")
    adb("shell", "input", "keyevent", "4")


# Positions on the 1080 × 2400 Pixel 8 profile, measured once, tapped blind on camera: every wait()
# reads the screen through uiautomator (1–2 s each), which a recording shows as dead air. Album
# tiles are not clickable nodes anyway (photos_landing). The third tile is the one made a favourite.
ALBUM_TILE = (900, 430)
VIEWER_FAVOURITE = (880, 206)
COLLECTIONS_TAB = (907, 2274)


def record_album(album):
    """Collections → ALBUM → a photo → Add to favourites → back, and Collections re-selected (it
    refreshes its sections then), where Favourites now counts the photo."""
    tap_lowest("Collections")
    time.sleep(4)  # the tab refreshes its sections when it is selected
    card = card_center(album)
    tap_at = lambda xy: adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
    with ScreenRecord("android-album"):
        tap_at(card)
        time.sleep(2.5)
        tap_at(ALBUM_TILE)
        time.sleep(3.5)  # long enough to see the empty star before it fills
        tap_at(VIEWER_FAVOURITE)
        time.sleep(1.8)
        adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
        tap_at(COLLECTIONS_TAB)
        time.sleep(3.5)
    if not find("Favourites"):
        sys.exit("android-shots: the album recording did not end on Collections")


def photos_promo(args):
    """The promo realm's Photos: the library, a recording of a slow scroll through it, and — with
    --promo-config — an album made off camera and a recording of one of its photos made a favourite."""
    launch(PHOTOS)
    wait("Collections", timeout=120)
    wait_tiles(6)
    time.sleep(10)  # thumbnails arrive per tile, with a retry back-off (photos-app.md §41.2)
    promo_shot("01-library")
    with ScreenRecord("android-photos-scroll"):
        for _ in range(2):
            adb("shell", "input", "swipe", "540", "1700", "540", "900", "1800")
            time.sleep(2.5)
    tiles = wait_tiles(1)
    adb("shell", "input", "tap", str(tiles[0][0]), str(tiles[0][1]))
    wait("More", desc=True)
    time.sleep(5)
    promo_shot("02-viewer")
    adb("shell", "input", "keyevent", "4")  # the viewer, back to the feed
    time.sleep(2)
    cfg = promo_config(args.promo_config)
    if cfg:
        make_promo_album(cfg["album"], cfg["albumPhotos"])
        record_album(cfg["album"])


def settle_uploads(timeout=360, quiet=3):
    """Until neither the deferred banner nor the upload bar has been up for QUIET seconds: the one
    banner slot can swap between them as the emulator's route flips, so one check of each is not
    enough. Fails rather than let a capture show either."""
    deadline, clear_since = time.time() + timeout, None
    while time.time() < deadline:
        if find("Waiting for Wi-Fi", startswith=True):
            clear_since = None
            tap("Upload over cellular")
        elif find("Uploading", startswith=True):
            clear_since = None
        elif clear_since is None:
            clear_since = time.time()
        elif time.time() - clear_since >= quiet:
            return
        time.sleep(1)
    sys.exit("android-shots: Drive's uploads did not settle in time — not capturing the banner")


def drive(args):
    launch(DRIVE)
    wait("Files", timeout=120)
    tap("Files")
    wait("No files here yet", timeout=60)
    if not args.offline:
        # The Not syncing banner goes once the entitlement reaches Drive's runtime.
        deadline = time.time() + 120
        while find("Not syncing", startswith=True) and time.time() < deadline:
            time.sleep(2)
    shot("07-empty")
    tap("Add", desc=True)
    wait("Folder upload")
    shot("05-add")
    adb("shell", "input", "keyevent", "4")
    if args.offline:
        # ⚠ A realm with no plan refuses a folder upload at its capacity pre-flight, so the filled
        # screens are dev-lab only.
        return
    wait("No files here yet")

    seed(args.fixture)
    time.sleep(6)  # the photo thumbnails are minted after the upload
    # ⚠ "Waiting for Wi-Fi. N files still to upload": seen in a German run's 01-files.
    # The emulator's Wi-Fi network fails its DNS validation now and then, the default
    # route moves to the (metered) cellular one, and Control's Wi-Fi-only policy defers the files.
    # (Mobile data off is no cure: Wi-Fi alone then resolves nothing.) The walk answers as a user
    # would, with the banner's own "Upload over cellular", and refuses the capture while it stays.
    settle_uploads()
    shot("01-files")

    # The Holiday folder as a grid of its photos' thumbnails — the iOS "as icons" picture.
    tap("Holiday 2026")
    wait("IMG_4102.jpg")
    show_grid()
    time.sleep(6)
    shot("02-photos")
    show_list()  # back to the default, so the rest of the walk finds its rows as before

    tap("IMG_4102.jpg")
    wait("More actions", desc=True)
    time.sleep(2)
    shot("03-preview")

    tap("More actions", desc=True)
    wait("Details & Activity")
    shot("04-file-actions")

    # Link → Generate link: a real share on the dev relay (the link names the production viewer
    # host, so it opens nothing — store-screenshots.md §2). Then the Shared tab lists it.
    tap("Link")
    tap("Generate link")
    wait("https://", timeout=60, startswith=True)
    shot("06-share-link")
    tap("Done")
    tap_lowest("Shared")
    tap("My shares")
    wait("IMG_4102.jpg")
    shot("09-shared")



# ── Fit, Calendar and Contacts (store-screenshots.md §8) ─────────────────────────────────────────
# Each joins the realm Control's walk made (they reach Control over Binder) and takes its fixture
# through its own shipping import: the files go into the emulator's Download folder and are picked
# in the system picker by taps, exactly as a user picks a file they were sent.

DOWNLOAD = "/sdcard/Download"
FIT = "app.tenshu.fit.android"
CALENDAR = "app.tenshu.calendar.android"
CONTACTS = "app.tenshu.contacts.android"


def push_files(paths):
    """`adb push` each file into the emulator's Download folder (the overlay is discarded at exit),
    then ask the media scanner about it so the picker lists it at once."""
    adb("shell", "mkdir", "-p", DOWNLOAD)
    for path in paths:
        adb("push", path, DOWNLOAD + "/")
        name = os.path.basename(path)
        adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", f"file://{DOWNLOAD}/{name}", check=False)


def pick_in_downloads(name):
    """In the system picker (DocumentsUI), open Downloads and tap NAME. The picker opens on Recent
    (empty for a file nobody opened) or on the folder it was last left in, so the walk goes through
    the drawer's Downloads root every time."""
    wait("Show roots", desc=True, timeout=30)
    if not find(name):
        tap("Show roots", desc=True)
        tap("Downloads")
    tap(name, timeout=30)


def dismiss_keyboard():
    shown = lambda: "mInputShown=true" in adb("shell", "dumpsys", "input_method", check=False).decode(errors="replace")
    # Two reads half a second apart: BACK with no keyboard up would leave the screen, and a
    # keyboard ESCAPE just closed can still read as shown for a moment.
    if shown():
        time.sleep(0.5)
        if shown():
            adb("shell", "input", "keyevent", "4")  # BACK closes the keyboard and nothing else
            time.sleep(1)


def wait_gone(label, timeout=60, startswith=False):
    deadline = time.time() + timeout
    while find(label, startswith=startswith) and time.time() < deadline:
        time.sleep(1)


# ── Contacts ─────────────────────────────────────────────────────────────────────────────────────
CONTACTS_DETAIL = "Amara Okafor"     # the same contact, query and name as the iOS walk (§3c)
CONTACTS_QUERY = "Lumen"
CONTACTS_OWN_NAME = "Alex Rivera"


def contacts(args):
    """The address book comes in through + → Import contacts → Import a .vcf file and the system
    picker — the shipping import, no hook."""
    push_files([os.path.join(args.fixture, "contacts.vcf")])
    launch(CONTACTS)
    wait("No contacts yet", timeout=120)
    tap("Add a contact", desc=True)
    tap("Import contacts")
    tap("Import a .vcf file")
    pick_in_downloads("contacts.vcf")
    wait("Imported 28 contacts.", timeout=120)
    tap("Done")
    adb("shell", "input", "keyevent", "4")  # close the sheet
    wait(CONTACTS_DETAIL, timeout=60)
    wait("2 possible duplicates", timeout=60)
    shot("01-contacts")

    tap(CONTACTS_DETAIL)
    wait("Contact info")
    shot("02-contact")
    adb("shell", "input", "keyevent", "4")

    tap("Search your contacts")
    type_text(CONTACTS_QUERY, close_keyboard=False)
    wait("Grace Liu")
    dismiss_keyboard()
    shot("03-search")
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    if find("Clear search", desc=True):
        adb("shell", "input", "keyevent", "4")

    tap("2 possible duplicates")
    wait("Possible duplicates")
    # The pair as the app words it, in whichever order it puts the two.
    conj = contains(" and ")
    pair = wait_text(lambda t: "Priya Raman" in t and "Priya R." in t and conj(t), "the Priya pair")
    shot("04-duplicates")
    tap(pair)
    wait("Combine")
    shot("05-merge")
    tap("Cancel")  # never merges
    adb("shell", "input", "keyevent", "4")

    tap_lowest("You")
    tap("Edit name")
    tap("Your name")
    type_text(CONTACTS_OWN_NAME.replace(" ", "%s"), close_keyboard=False)
    dismiss_keyboard()
    tap("Save")
    wait(CONTACTS_OWN_NAME)
    shot("06-you")

    # You → Invite someone: a real one-time invitation on the dev relay, made by the app alone (no
    # second device). Revoked straight after the capture, as a user who changed their mind does.
    tap("Invite someone")
    wait("Create a one-time invitation", startswith=True)
    tap_lowest("Invite someone")
    wait("Invitation QR code", desc=True, timeout=60)
    wait("Waiting for a reply", timeout=60)
    shot("07-invite")
    tap("Revoke")
    wait_gone("Waiting for a reply", timeout=30)
    tap("Close sheet", desc=True)


def type_field(text):
    """`input text` of TEXT as one quoted shell word: spaces as %s, and & , ' kept literal."""
    escaped = text.replace("'", "'\\''").replace(" ", "%s")
    adb("shell", f"input text '{escaped}'")
    time.sleep(1)


def tap_visible(label, timeout=30):
    """Tap LABEL, scrolling the form up first while it is below the fold (under the navigation bar)."""
    for _ in range(4):
        c = find(label)
        if c and c[1] < 2150:
            adb("shell", "input", "tap", str(c[0]), str(c[1]))
            time.sleep(1)
            return
        adb("shell", "input", "swipe", "540", "1700", "540", "1100", "400")
        time.sleep(1)
    tap(label, timeout)


def unlabelled_clickables(below_y, above_y=2400, min_px=60, max_px=160):
    """Clickable nodes with no text and no description between two heights, in reading order —
    the colour swatches of Calendar's sheet, which carry neither."""
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
    out = []
    for m in NODE.finditer(xml):
        a = attrs(m.group(0))
        b = BOUNDS.match(a.get("bounds", ""))
        if not b or a.get("text") or a.get("content-desc") or a.get("clickable") != "true":
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if min_px <= x2 - x1 <= max_px and min_px <= y2 - y1 <= max_px and below_y < y1 and y2 < above_y:
            out.append((y1 // 20, x1, (x1 + x2) // 2, (y1 + y2) // 2))
    return [(cx, cy) for _, _, cx, cy in sorted(out)]


# ── Calendar ─────────────────────────────────────────────────────────────────────────────────────
# The palette's order (calendar-shared-logic CalendarPalette.COLORS): the sheet draws a swatch per
# entry, in this order, with no label — so the walk picks a colour by its index.
CALENDAR_PALETTE = ["4285F4", "0F9D58", "DB4437", "F4B400", "9C27B0", "00ACC1", "FF7043", "757575"]
CALENDAR_EVENT = "Dentist — Dr. Okafor"            # the same event, new event and calendar as iOS (§3b)
CALENDAR_NEW_TITLE = "Coffee with Elena"
CALENDAR_NEW_LOCATION = "Bean & Leaf, Market Square"
CALENDAR_EDITED = "Work"


def new_calendar(name, colour):
    tap("New calendar", desc=True)
    wait("New calendar")
    tap("Name")
    type_field(name)
    dismiss_keyboard()
    _, label_y = wait("Colour")
    swatches = unlabelled_clickables(label_y)
    if len(swatches) < len(CALENDAR_PALETTE):
        sys.exit(f"android-shots: the calendar sheet shows {len(swatches)} colour swatches, expected {len(CALENDAR_PALETTE)}")
    x, y = swatches[CALENDAR_PALETTE.index(colour.upper())]
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    tap("Save")
    wait_gone("New calendar", timeout=30)
    wait(name, timeout=30)


def import_ics(calendar_name, file_name, events):
    tap(f"More for {calendar_name}", desc=True)
    tap("Import or export…")
    tap("Import from a file")
    pick_in_downloads(file_name)
    wait(f"Imported {events} event", timeout=120, startswith=True)
    tap("Done")
    wait_gone("Done", timeout=15)


def calendar(args):
    """Five calendars, as on iOS: Personal is the app's own (seeded on first launch), the other
    four made through Calendars → New calendar, and every one filled from its .ics file through
    its row's More → Import or export… → Import from a file and the system picker."""
    import json
    spec = json.load(open(os.path.join(args.fixture, "calendars.json")))
    push_files([os.path.join(args.fixture, c["file"]) for c in spec["calendars"]])
    launch(CALENDAR)
    wait("Month", timeout=120)
    tap("Calendars", desc=True)
    wait("Personal", timeout=60)  # ensureDefaultCalendar's, once the realm answers
    for c in spec["calendars"]:
        if not c["default"]:
            new_calendar(c["name"], c["colorArgb"][2:])
    for c in spec["calendars"]:
        import_ics(c["name"], c["file"], c["events"])
    adb("shell", "input", "keyevent", "4")  # back to the month

    wait("Month")
    tap("Today")
    time.sleep(3)
    shot("01-month")

    tap("Week")
    time.sleep(3)
    shot("07-week")
    tap("Day")
    time.sleep(3)
    shot("08-day")

    tap("Agenda")
    wait(CALENDAR_EVENT, timeout=30)
    shot("02-agenda")
    tap(CALENDAR_EVENT)
    wait("Edit event")
    dismiss_keyboard()
    shot("03-event")
    tap("Cancel", desc=True)
    wait("Agenda")

    tap("Month")
    tap("Today")
    tap("New event", desc=True)
    wait("New event")
    tap("Title")
    type_field(CALENDAR_NEW_TITLE)
    # ⚠ Close the keyboard before Location: the field sits under it, and a tap on its label lands
    # on the keyboard (a comma key, once — the location went into the title).
    dismiss_keyboard()
    tap_visible("Location")
    type_field(CALENDAR_NEW_LOCATION)
    dismiss_keyboard()
    adb("shell", "input", "swipe", "540", "700", "540", "1900", "400")  # back to the top of the form
    time.sleep(1)
    shot("04-new-event")
    tap("Save")
    # The new row's text is its title and location run together ("Coffee with Elena,Bean & …").
    wait(CALENDAR_NEW_TITLE, timeout=30, startswith=True)

    tap("Calendars", desc=True)
    wait("Travel")
    shot("05-calendars")
    tap(CALENDAR_EDITED)
    wait("Edit calendar")
    shot("06-edit-calendar")
    tap("Cancel")


# ── Fit ──────────────────────────────────────────────────────────────────────────────────────────
FIT_WORKOUTS_ZIP = "Fit workouts.zip"
FIT_WORKOUT = "Morning run along the Arno"   # today's run, as on iOS (§3a)
FIT_SEED = f"{FIT}/.StoreFixtureSeedActivity"


def fit_seed_health(fixture):
    """The daily figures into Health Connect: the debug build's seed (StoreFixtureSeedActivity,
    fit-android/src/debug) writes health.json there and renders one line, never captured. Fit
    reads them back through its shipping import on the next launch."""
    # Into the app's own files directory with run-as (a debug build is debuggable): a folder that
    # `adb push` makes under Android/data is the shell's, and the app cannot read it.
    with open(os.path.join(fixture, "health.json"), "rb") as f:
        r = subprocess.run(["adb", "-s", SERIAL, "exec-in", "run-as", FIT, "sh", "-c",
                            "mkdir -p files/store-fixture && cat > files/store-fixture/health.json"], stdin=f)
    if r.returncode != 0:
        sys.exit("android-shots: could not write health.json into Fit's files (run-as)")
    adb("shell", "am", "start", "-W", "-n", FIT_SEED)
    # ⚠⚠ Write access through Health Connect's own sheet, never `pm grant`: only the sheet puts the
    # writer on Health Connect's data-source priority list, and its aggregates (Fit's daily steps and
    # distance) count only the apps on that list — after a `pm grant` they read 0.
    fit_allow_health_connect()
    deadline = time.time() + 180
    while time.time() < deadline:
        texts = [t for t, _, _ in nodes()]
        ok = [t for t in texts if t.startswith("seed: ")]
        if ok:
            if not ok[0].startswith("seed: OK"):
                sys.exit(f"android-shots: the Fit seed failed: {ok[0]}")
            break
        time.sleep(2)
    else:
        sys.exit("android-shots: the Fit seed never finished")
    adb("shell", "am", "force-stop", FIT)


def fit_workouts_zip(fixture, out_dir):
    """The GPX workouts as one zip — the shape a Takeout export or a watch's bulk export has — for
    Profile → Google Takeout → Import again, which takes a zip or a single activity file."""
    import zipfile
    path = os.path.join(out_dir, FIT_WORKOUTS_ZIP)
    wdir = os.path.join(fixture, "workouts")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(os.listdir(wdir)):
            if name.endswith(".gpx"):
                z.write(os.path.join(wdir, name), f"workouts/{name}")
    return path


def fit(args):
    """Fit's data as a user's arrives: the daily figures from Health Connect (put there by the debug
    seed, read back through Fit's own import after its consent sheet, then Profile → Health Connect
    → Import your full history so all six weeks come in), the workouts as GPX files through
    Profile → Google Takeout → Import again and the system picker."""
    fit_seed_health(args.fixture)
    push_files([fit_workouts_zip(args.fixture, args.out + ".work")])
    launch(FIT)
    fit_allow_health_connect()  # the read sheet Fit opens on launch
    tap_lowest("Profile")
    # Health Connect shares 30 days unless a past-data grant says otherwise; the fixture is six weeks
    # (and its one height sample is the oldest record). ⚠ After this import (~3,800 rows) one of Fit's
    # list streams is ~1.35 MB — over one Binder transaction — and arrives only because Control sends
    # it out of band (core-client-resubscription.md §4.8); this step is that path's standing check.
    tap("Health Connect")
    tap("Import your full history")
    tap("Allow", timeout=60)
    wait("Health Connect is sharing your older data too", timeout=120)
    wait_count_settles("items ·")
    adb("shell", "input", "keyevent", "4")
    tap("Google Takeout")
    # ⚠ "Import again" is disabled while ANY import runs — the full-history one above may still be.
    wait("Import again")
    wait_gone("Importing", timeout=300, startswith=True)
    tap("Import again")
    pick_in_downloads(FIT_WORKOUTS_ZIP)
    # Done when the screen's import history has a row: its "No imports yet" line goes, and nothing
    # is importing any more. ⚠ Not "Importing… gone" alone — that holds before the import starts.
    wait_gone("No imports yet", timeout=300, startswith=True)
    if find("No imports yet", startswith=True):
        visible = [t for t, _, _ in nodes() if t]
        sys.exit(f"android-shots: the workouts zip never imported (on screen: {visible[:15]})")
    wait_gone("Importing", timeout=300, startswith=True)
    adb("shell", "input", "keyevent", "4")

    # Home at its top: the ring, the day's figures and the first week cards. Home is one scrolling
    # page with four week cards, so the morning's run sits far below them — History shows it.
    tap_lowest("Home")
    wait("Steps", timeout=120)
    time.sleep(5)  # the ring and the week bars animate in
    shot("01-home")
    tap_lowest("History")
    wait(FIT_WORKOUT)
    shot("02-history")
    tap(FIT_WORKOUT)
    wait("ROUTE", timeout=60)
    time.sleep(8)  # the basemap's tiles and the route
    shot("03-workout")
    adb("shell", "input", "keyevent", "4")
    tap_lowest("Browse")
    wait_text(lambda t: any(t.endswith(e) for e in ["last night"] + TRANSLATION_OVERRIDES.get(LANG, {}).get("last night", [])),
              "the Sleep card's last night", timeout=60)
    shot("05-browse")
    tap_card("Sleep")
    wait("per night", timeout=60)
    shot("06-sleep")
    adb("shell", "input", "keyevent", "4")
    # Profile scrolled so Data sources and Batch import both show, as the support page describes it.
    tap_lowest("Profile")
    wait("PERSONAL DATA")
    adb("shell", "input", "swipe", "540", "1600", "540", "1000", "600")
    wait("Google Takeout")
    shot("07-profile")


# ── Timeline (store-screenshots.md §8.2) ─────────────────────────────────────────────────────────
TIMELINE = "app.tenshu.timeline.android"
TIMELINE_ZIP = "Timeline Takeout.zip"
TIMELINE_WALK_FROM = "Piazza Santo Spirito"     # today's 09:25 walk across the river, as on iOS (§3f)
TIMELINE_WALK_TO = "Galleria dell'Accademia"
TIMELINE_PLACE = "Piazza Santo Spirito"


def timeline_zip(fixture, out_dir):
    """The generated months as a Takeout archive: the shape a user downloads, and what Timeline's
    *Choose file…* takes (the router reads each entry by its content, not its path)."""
    import zipfile
    src = os.path.join(fixture, "Semantic Location History")
    path = os.path.join(out_dir, TIMELINE_ZIP)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(os.listdir(src)):
            year = name.split("_")[0]
            z.write(os.path.join(src, name),
                    f"Takeout/Location History (Timeline)/Semantic Location History/{year}/{name}")
    return path


def scroll_to(label, max_swipes=12, startswith=False, up=False):
    """Swipe the list until LABEL is on screen; its centre."""
    for _ in range(max_swipes):
        c = find(label, startswith=startswith)
        if c and 300 < c[1] < 2150:
            return c
        if up:
            adb("shell", "input", "swipe", "540", "800", "540", "1700", "500")
        else:
            adb("shell", "input", "swipe", "540", "1700", "540", "800", "500")
        time.sleep(1.5)
    visible = [t for t, _, _ in nodes() if t]
    sys.exit(f"android-shots: '{label}' never scrolled into view (on screen: {visible[:15]})")


def timeline(args):
    """Two weeks in Milan and three days in Florence, through Settings → Import from Google Takeout →
    Choose file… and the system picker — the shipping import, no hook (the iOS run needs a seed only
    because the iOS app has no import)."""
    push_files([timeline_zip(args.fixture, args.out + ".work")])
    launch(TIMELINE)
    wait("Your timeline starts here", timeout=120)
    tap_lowest("Settings")
    tap("Choose file…")
    pick_in_downloads(TIMELINE_ZIP)
    # The result is a line under the button, then Done. Place-name lookups stay off (every fixture
    # place is named): a result dialog offering them is answered Not now.
    wait("Imported ", startswith=True, timeout=300)
    tap("Done")
    time.sleep(2)
    if find("Not now"):
        tap("Not now")
    tap_lowest("Home")
    walk = f"{TIMELINE_WALK_FROM} → {TIMELINE_WALK_TO}"
    wait(walk, timeout=120)
    time.sleep(3)
    shot("01-today")

    # Today's walk across the river, opened from the top of the feed (the same walk recurs on other
    # days, so it is never looked for further down).
    tap(walk)
    wait("DISTANCE", timeout=60)
    time.sleep(8)  # the basemap's tiles and the route
    shot("04-walk")
    adb("shell", "input", "keyevent", "4")

    scroll_to("Read as a trip")
    shot("02-suggestion")
    tap("Read as a trip")
    time.sleep(3)
    # The accepted trip is a chapter card after the last day ("BOUND CHAPTER").
    for _ in range(60):
        if find("Open chapter →"):
            break
        adb("shell", "input", "swipe", "540", "2000", "540", "300", "150")
        time.sleep(0.7)
    tap("Open chapter →")
    wait("TRIP · ", startswith=True, timeout=60)
    time.sleep(8)  # the route over the basemap
    shot("03-trip")
    adb("shell", "input", "keyevent", "4")

    tap_lowest("Places")
    wait("Home", timeout=60)
    # Oldest last visit first: the Milan places, then Florence's at the end — the list scrolled there.
    for _ in range(2):
        adb("shell", "input", "swipe", "540", "2000", "540", "300", "300")
        time.sleep(1)
    wait(TIMELINE_PLACE, timeout=60)
    time.sleep(2)
    shot("05-places")
    tap(TIMELINE_PLACE)
    time.sleep(8)  # the basemap's tiles
    shot("06-place")
    adb("shell", "input", "keyevent", "4")


# ── Maps ─────────────────────────────────────────────────────────────────────────────────────────
MAPS = "app.tenshu.maps.android"
MAPS_PLACE = "Piazza Santo Spirito"   # a Florence place: the bundled basemap draws it


def maps(args):
    """Maps has no way of its own to make a place (store-screenshots.md §8.2): it shows the places
    Timeline's import made, which the run walked first."""
    launch(MAPS)
    wait("Your places", timeout=120)
    wait("Home", timeout=120)  # the import's first place: the list has arrived
    time.sleep(2)
    shot("01-places")
    scroll_to(MAPS_PLACE)
    tap_card(MAPS_PLACE)
    wait("History", timeout=60)
    time.sleep(6)  # the basemap's tiles
    shot("02-place")
    adb("shell", "input", "keyevent", "4")


# ── Home ─────────────────────────────────────────────────────────────────────────────────────────
HOME = "app.tenshu.home.android"
# The names the run's Hub announces its simulated devices with (store-screenshots-android.sh,
# `--simulated-device-name`); "Simulated light" on a store listing reads as a fake.
HOME_LIGHT = "Living room lamp"
HOME_SENSOR = "Bedroom climate"
HOME_AUTOMATION = "Evening lights"
HOME_AUTOMATION_LATE = "Lights out"


def pair_hub(uri_file):
    """Control: Add a device → Enter code instead → the Hub's printed pairing URI → Approve — what a
    user does with the label on a Hub, typed instead of scanned (the emulator has no camera). Runs
    inside Control's one foreground session (its app lock)."""
    deadline = time.time() + 120
    while not os.path.exists(uri_file) and time.time() < deadline:
        time.sleep(2)
    uri = open(uri_file).read().strip()
    # After the host code's Stop, Control is still on Add a device (opened from Realm devices' +).
    if not find("Enter code instead"):
        if not find("Add a device"):
            tap("Scan Device", desc=True)
        tap("Scan a code")
    tap("Enter code instead")
    tap("Pairing Code")
    adb("shell", "input", "text", f"'{uri}'")
    hide_keyboard()
    tap("Continue")
    tap("Approve", timeout=120)
    wait_gone("Approve", timeout=120)
    time.sleep(3)
    if find("Continue"):
        tap("Continue")
    if find("Done"):
        tap("Done")


def tap_fab():
    """The extended floating button above the bottom bar. ⚠ Home's *Add automation* exposes no text
    or description to `uiautomator dump` (its *Add device* twin does), so it is found by place: the
    clickable node right above the bar."""
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
    best = None
    for m in NODE.finditer(xml):
        a = attrs(m.group(0))
        b = BOUNDS.match(a.get("bounds", ""))
        if a.get("clickable") != "true" or not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x1 > 300 and 1700 < y2 < 2127 and (best is None or y2 > best[1]):
            best = ((x1 + x2) // 2, y2, (y1 + y2) // 2)
    if not best:
        sys.exit("android-shots: no floating button above the bottom bar")
    adb("shell", "input", "tap", str(best[0]), str(best[2]))
    time.sleep(1)


def bounds_of(label, desc=False):
    """The (x1, y1, x2, y2) of the first node whose text (or description) is LABEL."""
    ok = matcher(label)
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode(errors="replace")
    for m in NODE.finditer(xml):
        a = attrs(m.group(0))
        b = BOUNDS.match(a.get("bounds", ""))
        if b and ok(unescape(a.get("content-desc" if desc else "text", ""))):
            return tuple(map(int, b.groups()))
    sys.exit(f"android-shots: no node '{label}'")


def replace_field(current, text):
    """Tap the field showing CURRENT, empty it, and type TEXT (an editor field that starts filled)."""
    tap(current)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * (len(current) + 2)))
    type_text(text)


def home_automation(name, trigger, on):
    """Automations → Add automation → a name, a trigger, and one device action on the lamp → the
    editor still open. TRIGGER is ("time", "HH:MM") or ("sunset", minutes)."""
    tap_fab()
    wait("New automation")
    tap("Name")
    type_text(name.replace(" ", "%s"))
    if trigger[0] == "time":
        tap("Time")
        wait("Time (HH:MM)")
        current = next(t for t, _, _ in nodes() if re.fullmatch(r"\d\d:\d\d", t))
        replace_field(current, trigger[1])
    else:
        tap("Sun")
        tap("sunset")
        replace_field("0", str(trigger[1]))
    tap_lowest("Device")  # the action's chip, under the trigger's chip of the same name
    wait(HOME_LIGHT)
    tap(HOME_LIGHT)
    tap("On" if on else "Off")
    tap("Add")
    wait(("Turn on " if on else "Turn off ") + HOME_LIGHT)


def home(args):
    """The Hub the run started (simulated devices, ordinary names) was paired by Control's walk; its
    device rows reach Home through the realm. The lamp is switched by Home's own control (the command
    goes to the Hub and its new state comes back), and both automations are made in Home's editor."""
    launch(HOME)
    wait(HOME_LIGHT, timeout=300)
    wait(HOME_SENSOR, timeout=120)
    tap("Power", desc=True)
    # The slider shows only while the lamp is on: the Hub has run the command and reported back.
    wait("Brightness", desc=True, timeout=120)
    x1, y1, x2, y2 = bounds_of("Brightness", desc=True)
    adb("shell", "input", "tap", str(x1 + (x2 - x1) * 7 // 10), str((y1 + y2) // 2))
    wait_text(lambda t: re.search(r"· 7\d\s?%", t) is not None, "the lamp at ~70 %", timeout=120)
    time.sleep(3)
    shot("01-devices")

    tap_lowest("Automations")
    wait("No automations yet")
    home_automation(HOME_AUTOMATION_LATE, ("time", "23:30"), on=False)
    tap("Save")
    wait(HOME_AUTOMATION_LATE, timeout=60)
    home_automation(HOME_AUTOMATION, ("sunset", -15), on=True)
    shot("03-automation")
    tap("Save")
    wait(HOME_AUTOMATION, timeout=60)
    wait(HOME_AUTOMATION_LATE)
    time.sleep(2)
    shot("02-automations")

# ── Mia ──────────────────────────────────────────────────────────────────────────────────────────
MIA = "app.tenshu.mia.android"
# The iOS walk's questions (§3g), each picked by the QueryPlanner arm it takes.
MIA_FILES = ("Show me my tax files", "Searched your files for names containing", "Tax return 2025.pdf")
MIA_PHOTOS = ("Photos from July", "Searched your files for photos from", "IMG_4102.jpg")
MIA_CALENDAR = ("What's on my calendar next week?", "Looked at your calendar for", None)


def mia_answers(caption):
    ok = matcher(caption, startswith=True)
    return [t for t, _, _ in nodes() if ok(t) and "nothing matched" not in t]


def mia_ask(question, caption, row):
    """Type QUESTION, send it, and wait for an answer that is a citation list (CAPTION…), citing
    ROW when given — then bring the question back into view."""
    before = len(mia_answers(caption))
    tap("Ask Mia…")
    type_text(question.replace(" ", "%s").replace("'", "\\'"), close_keyboard=False)
    # The field must hold the whole question: a dropped key would fail as a wrong answer.
    wait(question, timeout=10)
    dismiss_keyboard()
    tap("Send", desc=True)  # ⚠ ENTER does not send: the field is multi-line
    deadline = time.time() + 90
    while time.time() < deadline and len(mia_answers(caption)) <= before:
        time.sleep(2)
    if len(mia_answers(caption)) <= before:
        visible = [t for t, _, _ in nodes() if t]
        sys.exit(f"android-shots: Mia gave '{question}' no answer with rows (on screen: {visible[-15:]})")
    wait_gone("Mia is thinking…", timeout=60)
    dismiss_keyboard()
    if row:
        wait(row, timeout=20)
    mia_reveal(question)


def mia_reveal(question, top=260):
    """Scroll the thread back just far enough that the question's own bubble shows above its answer
    (a long answer pushes it off the top) — the iOS walk's revealQuestion. Done when it is below the
    status bar; never scrolls past it, so as much of the answer as fits stays on screen."""
    ok = matcher(question)
    last = None
    for _ in range(6):
        ys = [c[1] for t, _, c in nodes() if ok(t.replace("’", "'"))]
        y = max(ys) if ys else None
        # Below the status bar — or as low as it goes (the thread's first message, at its top).
        if y is not None and (y >= top or (y == last and y > 150)):
            return
        last = y
        distance = 600 if y is None else min(600, top - y + 80)
        adb("shell", "input", "swipe", "540", "700", "540", str(700 + distance), "600")
        time.sleep(1.5)
    sys.exit(f"android-shots: could not bring '{question}' back into view")


def mia(args):
    """Mia keeps no data of its own: it answers from the rows Drive's and Calendar's walks put in the
    realm. One launch for the whole walk — History lists only this session's conversation."""
    launch(MIA)
    for _ in range(3):
        if find("Get started"):
            break
        if find("Next"):
            tap("Next")
    if find("Get started"):
        tap("Get started")
    wait("Ask Mia…", timeout=60)
    shot("01-welcome")
    mia_ask(*MIA_FILES)
    shot("02-files")
    mia_ask(*MIA_PHOTOS)
    shot("03-photos")
    mia_ask(*MIA_CALENDAR)
    shot("04-calendar")
    tap_lowest("History")
    wait("Show me my tax files", timeout=30)
    shot("06-history")


def wait_text(match, what, timeout=60):
    """Wait for a node whose text MATCHES; returns that text."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, _, _ in nodes():
            if match(t):
                return t
        time.sleep(1)
    sys.exit(f"android-shots: {what} never appeared")


def wait_count_settles(prefix, timeout=180):
    """Wait until the text starting with PREFIX (an import's running count) stops changing."""
    deadline = time.time() + timeout
    has = contains(prefix)
    last, stable, seen = None, 0, False
    while time.time() < deadline and stable < 3:
        now = next((t for t, _, _ in nodes() if has(t)), None)
        seen = seen or now is not None
        stable = stable + 1 if now == last and now is not None else 0
        last = now
        time.sleep(3)
    # ⚠ Never seen at all is a lookup that cannot match (a missing translation), not a settled count.
    if not seen:
        sys.exit(f"android-shots: no text containing {prefix!r} appeared in {timeout} s")


def fit_allow_health_connect():
    """Health Connect's read-consent sheet, which Fit opens on launch: allow everything, as a user
    who wants their data in does."""
    tap("Allow all", timeout=120)
    tap("Allow")

def main():
    global OUT, LANG, APP
    p = argparse.ArgumentParser()
    p.add_argument("app", choices=["control", "drive", "photos", "fit", "calendar", "contacts", "timeline", "maps", "mia", "home"])
    p.add_argument("--set", choices=["support", "landing", "promo"], default="support")
    p.add_argument("--upload", help="drive --set promo: photos held back from the seed, one uploaded on camera")
    p.add_argument("--backup", help="drive --set promo: a photo new to the realm, backed up by Synced Folders on camera")
    p.add_argument("--promo-config", help="photos --set promo: JSON naming the album to make and film")
    p.add_argument("--out", required=True)
    p.add_argument("--email")
    p.add_argument("--otp-file")
    p.add_argument("--offline", action="store_true")
    p.add_argument("--lang", choices=["en", "de"], default="en",
                   help="the language the apps were set to; labels are matched in it as well as in English")
    p.add_argument("--hub-uri-file", help="control: pair the run's Hub from this printed pairing URI")
    p.add_argument("--fixture", help="drive: the fixture tree (store/fixtures/generate.py drive <dir>)")
    args = p.parse_args()
    if not SERIAL.startswith("emulator-"):
        sys.exit("android-shots: ANDROID_SERIAL must name an emulator")
    if args.app == "control" and not args.offline and not (args.email and args.otp_file):
        sys.exit("android-shots: control needs --email and --otp-file (or --offline)")
    if args.app == "drive" and not args.offline and not (args.fixture and os.path.isdir(args.fixture)):
        sys.exit("android-shots: drive needs --fixture <dir> (store/fixtures/generate.py drive <dir>)")
    OUT = args.out
    LANG, APP = args.lang, args.app
    os.makedirs(OUT, exist_ok=True)
    if args.app == "photos" and args.set == "support":
        sys.exit("android-shots: photos has only the landing and promo sets")
    if args.set == "promo" and args.app == "drive":
        if not (args.upload and os.path.isdir(args.upload)):
            sys.exit("android-shots: drive --set promo needs --upload <dir>")
        drive_promo(args)
    elif args.set == "promo" and args.app == "photos":
        photos_promo(args)
    elif args.set == "landing" and args.app == "drive":
        drive_landing(args)
    elif args.app == "photos":
        photos_landing(args)
    elif args.app == "control":
        control(args)
    elif args.app in ("maps", "mia", "home"):
        {"maps": maps, "mia": mia, "home": home}[args.app](args)
    elif args.app in ("fit", "calendar", "contacts", "timeline"):
        if not (args.fixture and os.path.isdir(args.fixture)):
            sys.exit(f"android-shots: {args.app} needs --fixture <dir> (store/fixtures/{args.app}/generate_{args.app}.py <dir>)")
        os.makedirs(args.out + ".work", exist_ok=True)
        {"fit": fit, "calendar": calendar, "contacts": contacts, "timeline": timeline}[args.app](args)
    else:
        drive(args)


if __name__ == "__main__":
    main()
