#!/usr/bin/env bash
#
# Store screenshots for the iOS apps (docs/how-to/store-screenshots.md).
#
#   scripts/store-screenshots.sh [control] [drive] [fit] [calendar] [contacts] [timeline] [--lang en|de] [--device iphone69|ipad13]
#                                [--appearance light|dark|both] [--skip-build] [--keep-sims] [--offline]
#   scripts/store-screenshots.sh photos [fit] [calendar] [contacts] [--lang en|de] [--device iphone69|ipad13]   (§3d; not with control/drive)
#   scripts/store-screenshots.sh --set landing [drive] [photos] [--device iphone69] [--appearance …]
#   scripts/store-screenshots.sh mia [--lang en|de] [--device iphone69|ipad13]                         (§3g; on its own)
#
# Builds each app's `<App>-Screenshots` scheme once, then per device: erases a DEDICATED simulator,
# boots it, pins the status bar, runs Control's onboarding capture (which is also the realm's
# genesis), Drive's capture over the seeded fixture, and Control's main-screen capture — and copies
# each XCTest attachment to
#
#   store/<app>/screenshots/ios/<device>/<lang>/NN-<screen>.png        (gitignored)
#   store/<app>/screenshots/ios/<device>/<lang>/dark/NN-<screen>.png   (--appearance dark|both)
#
# `--appearance` (default light, as every run before it) sets the simulator's system appearance with
# `xcrun simctl ui <udid> appearance` right after the boot. `both` runs each device twice — a fresh
# erase and onboarding per appearance, since the onboarding is the realm's genesis. The dark folder
# is what `store-publish.sh` turns into the manifest's `…/<lang>/dark/NN-screen` keys, and what the
# support pages show in dark mode (falling back to light for a screen with no dark capture).
#
# ⚠ Never committed: `scripts/store-publish.sh` puts them on R2 and records them in
# store/<app>/screenshots.manifest.json, which is what the support pages read. The Drive fixture is
# not in git either — store/fixtures/generate.py fetches (sha256-pinned) or draws every file.
#
# ⚠⚠ The screens must be what the shipping app renders. Both schemes test the `Debug`
# configuration (Drive's harness tabs live in `DebugHarness`, which is never used here), every
# screen is reached by taps, and the one dev hook is a `-seedFixture` launch (Drive's, Fit's, Calendar's, Contacts'),
# which writes DATA through a shipping path and is never captured. See the doc before adding a hook.
#
# `fit` (store-screenshots.md §3a): Fit's fixture is GENERATED for the run's day
# (store/fixtures/fit/generate_fit.py) — Apple Health figures plus GPX workouts routed along the
# bundled Florence basemap's streets — imported through Fit's file import and, by taps,
# Profile → "Import from Apple Health". Light only. The iPad cannot seed Apple Health, so it recovers
# the iPhone's realm from its phrase and walks the synced data — fit then runs alone (§3e).
#
# `calendar` (store-screenshots.md §3b): Calendar's fixture is GENERATED for the run's day too
# (store/fixtures/calendar/generate_calendar.py) — one .ics file per calendar, a person's month of
# work, family, fitness and a trip — and imported through Calendar's own .ics import by its seed
# launch. iPhone and iPad, light only so far.
#
# `contacts` (store-screenshots.md §3c): an invented address book (store/fixtures/contacts/
# manifest.json → generate_contacts.py → one .vcf), imported through the shared repository's
# `importVcards` on a `-seedFixture` launch; then the list, a contact, a search, the duplicates,
# the merge question and You, by taps. iPhone and iPad, light only so far.
#
# `timeline` (store-screenshots.md §3f): two weeks of an invented person's days at home in Milan and
# three in Florence that end today (store/fixtures/timeline/generate_timeline.py, generated for the
# run's day as a Google Timeline export), imported through the shared Google Timeline importer on a
# `-seedFixture` launch; then today, the trip suggestion, the trip made by accepting it, a walk on the
# map, Places and one place, by taps. iPhone and iPad, light only so far.
#
# `photos` (store-screenshots.md §3d): Photos' support set walks a realm filled with the landing
# set's stock photos re-dated six to a day (store/fixtures/photos/), seeded through Drive's `-seedFixture` launch
# (DriveLandingScreenshots/testSeedLandingRealm, nothing of Drive's exported) — Photos reads every
# image in the realm. So it cannot share a run with control or drive, whose captures show the
# synthetic Drive fixture. iPhone and iPad, light, dev lab only.
#
# `mia` (store-screenshots.md §3g): Mia answers from the realm the other apps keep, so its walk first
# fills it through THEIR seed launches — Drive's fixture, Calendar's .ics files and (iPhone only, §3e)
# Fit's workouts — capturing nothing of theirs, then asks Mia questions the grammar routes to those
# rows, and History. Never Settings (all "Coming soon"). On its own, light only, dev lab only.
#
# ⚠ Drive needs a realm, so a Drive-only run still runs Control's onboarding test (for the
# genesis) and simply does not export its pictures.
#
# The realm is a REAL one on the dev lab (tenshu.xyz — never production): the apps get the dev
# lab's origins through their Debug-only `TENSHU_*` seams, and Control's onboarding signs a fresh
# throwaway address up for a Free plan, reading the OTP from the dev lab's mailbox (token from the
# Keychain, never printed). So the realm syncs and Drive's uploads are real. `--offline` keeps the
# old local-only realm (no account, "Not syncing" banner) for when the dev lab is down.
#
# ⚠ The simulators are this script's own (`tenshu-shots-*`) and are ERASED at the start of every
# run: other sessions' booted simulators are never touched. `--keep-sims` leaves them booted
# afterwards (to look around); otherwise they are shut down.
#
# `--set landing` is the landing page's content set (store-screenshots.md §9), the twin of the
# Android script's: Drive seeds the stock-photo recipe store/fixtures/landing/ (same `-seedFixture`
# hook), captures DriveLandingScreenshots, then Photos walks the same realm (PhotosLandingScreenshots).
# Written to store/<app>/screenshots/ios/<device>landing/<lang>/ — a device of its own
# (`iphone69landing`), so a landing capture never replaces a support-page one. Control is onboarded
# (the realm needs it) but not exported. Dev lab only, iPhone only.

set -euo pipefail

# One heavy build at a time on this machine (scripts/lib/gate-lock.sh).
# shellcheck source=scripts/lib/gate-lock.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/gate-lock.sh"
tenshu_gate_lock "$0" "$@"

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

APPS=()
LANG_CODE=en
DEVICES=()
APPEARANCE_LIST=(light)
SKIP_BUILD=0
SET=support
KEEP_SIMS=0
OFFLINE=0
while [ $# -gt 0 ]; do
  case "$1" in
    control|drive|photos|fit|calendar|contacts|timeline|mia) APPS+=("$1") ;;
    --set)
      case "${2:-}" in
        support|landing) SET="$2" ;;
        *) echo "store-screenshots: --set takes support or landing" >&2; exit 2 ;;
      esac
      shift ;;
    --lang) LANG_CODE="$2"; shift ;;
    --device) DEVICES+=("$2"); shift ;;
    --appearance)
      case "${2:-}" in
        light|dark) APPEARANCE_LIST=("$2") ;;
        both) APPEARANCE_LIST=(light dark) ;;
        *) echo "store-screenshots: --appearance takes light, dark or both" >&2; exit 2 ;;
      esac
      shift ;;
    --skip-build) SKIP_BUILD=1 ;;
    --keep-sims) KEEP_SIMS=1 ;;
    --offline) OFFLINE=1 ;;
    -h|--help) sed -n '2,85p' "$0"; exit 0 ;;
    *) echo "store-screenshots: unknown argument '$1'" >&2; exit 2 ;;
  esac
  shift
done
if [ "$SET" = landing ]; then
  [ ${#APPS[@]} -gt 0 ] || APPS=(drive photos)
  for a in "${APPS[@]}"; do
    case "$a" in drive|photos) ;; *) echo "store-screenshots: the landing set has drive and photos, not $a" >&2; exit 2 ;; esac
  done
  [ "$OFFLINE" = 0 ] || { echo "store-screenshots: the landing set needs the dev lab (a funded realm takes the uploads)" >&2; exit 2; }
  [ ${#DEVICES[@]} -gt 0 ] || DEVICES=(iphone69)
  # Photos reads the realm Drive's landing walk fills, so a Photos run walks Drive too.
  case " ${APPS[*]} " in *" drive "*) ;; *) APPS=(drive "${APPS[@]}") ;; esac
else
  [ ${#APPS[@]} -gt 0 ] || APPS=(control drive)
  # Photos' realm is the landing photos (§3d), Control's and Drive's captures show the Drive fixture.
  case " ${APPS[*]} " in
    *" photos "*)
      case " ${APPS[*]} " in *" control "*|*" drive "*)
        echo "store-screenshots: photos walks a realm of its own (the landing photos, §3d) — run it apart from control and drive" >&2; exit 2 ;;
      esac
      [ "$OFFLINE" = 0 ] || { echo "store-screenshots: photos needs the dev lab (a funded realm takes Drive's uploads)" >&2; exit 2; } ;;
  esac
  [ ${#DEVICES[@]} -gt 0 ] || DEVICES=(iphone69 ipad13)
fi

# The region that goes with each listing language. ⚠ Without it the simulator inherits this Mac's
# region (here `en_US@rg=dezzzz`), and an English listing shows German dates ("6. Oct 2026").
case "$LANG_CODE" in
  en) REGION=US ;;
  de) REGION=DE ;;
  *) REGION="$(echo "$LANG_CODE" | tr '[:lower:]' '[:upper:]')" ;;
esac

wants() { local a; for a in "${APPS[@]}"; do [ "$a" = "$1" ] && return 0; done; return 1; }
# ⚠ Fit is light only: no dark set has been checked (store-screenshots.md §3a). $1 = device key,
# $2 = appearance.
fit_runs_on() { [ "$2" = light ]; }
# ⚠⚠ On the iPad, Fit cannot seed: Apple Health's consent there ends in "iCloud Health Data Sync
# is Off", after which the seed's write authorization is never granted (§3e). So the iPad does no
# genesis of its own — it RECOVERS the iPhone's realm from the phrase the iPhone's onboarding saved,
# and Fit's walk shows the iPhone's import, synced. That is why such a run has Fit alone (the other
# apps' iPad captures need a genesis), and the iPhone first.
FIT_JOINS_ON_IPAD=0
# Calendar and Contacts: light only (no dark set checked), on both devices.
light_runs_on() { [ "$2" = light ]; }
if wants fit; then
  fit_any=0
  for d in "${DEVICES[@]}"; do for a in "${APPEARANCE_LIST[@]}"; do fit_runs_on "$d" "$a" && fit_any=1; done; done
  [ "$fit_any" = 1 ] || { echo "store-screenshots: fit runs in light only so far — drop --appearance dark" >&2; exit 2; }
  case " ${DEVICES[*]} " in *" ipad13 "*)
    [ "${APPS[*]}" = fit ] \
      || { echo "store-screenshots: on the iPad, fit restores the iPhone's realm (§3e) — run it on its own: store-screenshots.sh fit" >&2; exit 2; }
    [ "${DEVICES[0]}" = iphone69 ] \
      || { echo "store-screenshots: fit's iPad walk shows the iPhone's data — pass --device iphone69 before --device ipad13" >&2; exit 2; }
    [ "$OFFLINE" = 0 ] \
      || { echo "store-screenshots: fit's iPad walk needs the dev lab (it syncs the iPhone's realm)" >&2; exit 2; }
    FIT_JOINS_ON_IPAD=1 ;;
  esac
fi
# ⚠ Calendar too: no dark set has been checked (store-screenshots.md §3b).
calendar_runs_on() { light_runs_on "$@"; }
if wants calendar; then
  calendar_any=0
  for d in "${DEVICES[@]}"; do for a in "${APPEARANCE_LIST[@]}"; do calendar_runs_on "$d" "$a" && calendar_any=1; done; done
  [ "$calendar_any" = 1 ] || { echo "store-screenshots: calendar runs in light only so far — drop --appearance dark" >&2; exit 2; }
fi
# ⚠ Contacts too: no dark set has been checked (§3c).
contacts_runs_on() { light_runs_on "$@"; }
# ⚠ Timeline too: no dark set has been checked (§3f).
timeline_runs_on() { light_runs_on "$@"; }
if wants timeline; then
  timeline_any=0
  for d in "${DEVICES[@]}"; do for a in "${APPEARANCE_LIST[@]}"; do timeline_runs_on "$d" "$a" && timeline_any=1; done; done
  [ "$timeline_any" = 1 ] || { echo "store-screenshots: timeline runs in light only so far — drop --appearance dark" >&2; exit 2; }
fi
# ⚠ Photos' support set: iPhone and iPad, light only — no dark set has been checked (§3d). The landing
# set has its own rules.
photos_runs_on() { light_runs_on "$@"; }
if wants photos && [ "$SET" = support ]; then
  photos_any=0
  for d in "${DEVICES[@]}"; do for a in "${APPEARANCE_LIST[@]}"; do photos_runs_on "$d" "$a" && photos_any=1; done; done
  [ "$photos_any" = 1 ] || { echo "store-screenshots: photos runs in light only so far — drop --appearance dark" >&2; exit 2; }
fi
if wants contacts; then
  contacts_any=0
  for d in "${DEVICES[@]}"; do for a in "${APPEARANCE_LIST[@]}"; do contacts_runs_on "$d" "$a" && contacts_any=1; done; done
  [ "$contacts_any" = 1 ] || { echo "store-screenshots: contacts runs in light only so far — drop --appearance dark" >&2; exit 2; }
fi

# ⚠ Mia: its walk seeds Drive, Calendar and Fit itself (§3g), so it runs alone — beside their own
# walks it would answer from their captured realms, and they would capture each other's data. Light
# only (no dark set checked) and dev lab only (an unfunded realm draws "Not syncing" over Mia).
mia_runs_on() { light_runs_on "$@"; }
if wants mia; then
  [ "${APPS[*]}" = mia ] \
    || { echo "store-screenshots: mia seeds the other apps' data itself (§3g) — run it on its own: store-screenshots.sh mia" >&2; exit 2; }
  [ "$SET" = support ] || { echo "store-screenshots: the landing set has no mia" >&2; exit 2; }
  [ "$OFFLINE" = 0 ] || { echo "store-screenshots: mia needs the dev lab (a funded realm; §3g)" >&2; exit 2; }
  mia_any=0
  for d in "${DEVICES[@]}"; do for a in "${APPEARANCE_LIST[@]}"; do mia_runs_on "$d" "$a" && mia_any=1; done; done
  [ "$mia_any" = 1 ] || { echo "store-screenshots: mia runs in light only so far — drop --appearance dark" >&2; exit 2; }
fi

# The App Store's two required sizes. ⚠ The device type decides the pixel size of every capture;
# the export step below checks it against the sizes App Store Connect accepts.
device_type() {
  case "$1" in
    iphone69) echo "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max" ;;   # 6.9", 1320x2868
    ipad13) echo "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-12GB" ;; # 13", 2064x2752
    *) echo "store-screenshots: unknown device '$1' (iphone69|ipad13)" >&2; exit 2 ;;
  esac
}
# The name the realm records for this device (Control's devices screen shows it). The simulator
# carries it only while a run is on it; it is `tenshu-shots-<device>` the rest of the time.
display_name() {
  case "$1" in
    iphone69) echo "iPhone" ;;
    ipad13) echo "iPad" ;;
  esac
}
accepted_sizes() {
  case "$1" in
    iphone69) echo "1320x2868 1290x2796" ;;
    ipad13) echo "2064x2752 2048x2732" ;;
  esac
}

# The newest installed iOS runtime.
RUNTIME="$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
rs = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]]
rs.sort(key=lambda r: [int(x) for x in r["version"].split(".")])
print(rs[-1]["identifier"] if rs else "")')"
[ -n "$RUNTIME" ] || { echo "store-screenshots: no iOS simulator runtime installed" >&2; exit 1; }

WORK="$ROOT/build/store-shots"
FIXTURE_NAME=drive
[ "$SET" = support ] || FIXTURE_NAME=landing
# Photos' support set is seeded with the landing photos, six to a day (store/fixtures/photos/, §3d);
# it never shares a run with Drive's.
[ "$SET" != support ] || case " ${APPS[*]} " in *" photos "*) FIXTURE_NAME=photos ;; esac
FIXTURE="$ROOT/build/store/fixture/$FIXTURE_NAME"
mkdir -p "$WORK"

log() { echo "store-screenshots: $*"; }

# ── The dev lab ──────────────────────────────────────────────────────────────
# The four settings a client needs against the dev plane (deploy-dev-lab-scaleway.md §10). The
# first three are read at RUNTIME by the Debug-only seams (`DevRelayOverride`, `iosCommerceUrl`,
# `iosBillingMock`) and reach the apps as `TEST_RUNNER_TENSHU_*` → the tests' launch environment.
# The issuer is compiled into the debug framework (`TENSHU_EXTRA_ISSUER`, identity-service) and
# trusted only by a debug binary. It is a PUBLIC key; override any of them from the environment.
DEV_RELAY="${TENSHU_SHOTS_RELAY:-https://relay.tenshu.xyz}"
DEV_COMMERCE="${TENSHU_SHOTS_COMMERCE:-https://commerce.tenshu.xyz}"
DEV_MAIL="${TENSHU_SHOTS_MAIL:-https://mail.tenshu.xyz}"
DEV_ISSUER="${TENSHU_SHOTS_ISSUER:-tenshu.commerce.dev:N3wP7kIUUeU3nZFhF0Pi77g0eFKPuUIB6FWVPzp4aQI=}"

# The mailbox token, straight from the Keychain into curl's stdin config — never an argument, a
# variable of this shell, or a line of output.
mail_get() { # $1 = path under the mail host → body on stdout
  { printf 'header = "Authorization: Bearer %s"\n' \
      "$(security find-generic-password -a tenshu -s dev-mailpit-token -w)"; } \
    | curl -sf --max-time 15 -K - "$DEV_MAIL$1"
}

if [ "$OFFLINE" = 0 ]; then
  for url in "$DEV_RELAY/health" "$DEV_COMMERCE/health"; do
    curl -sf --max-time 15 -o /dev/null "$url" \
      || { log "the dev lab is not answering ($url) — try again later, or pass --offline"; exit 1; }
  done
  # Before the build: a relay that refuses this checkout's protocol fails every walk 40 minutes in.
  # shellcheck source=scripts/lib/devlab-relay-protocol.sh
  . "$ROOT/scripts/lib/devlab-relay-protocol.sh"
  devlab_relay_protocol_ok "$ROOT" "$DEV_RELAY" || { log "FAIL — the dev lab's relay is behind (above)"; exit 1; }
  security find-generic-password -a tenshu -s dev-mailpit-token >/dev/null 2>&1 \
    || { log "no dev-mailpit-token in the Keychain (deploy-dev-lab-scaleway.md §10) — or pass --offline"; exit 1; }
  mail_get "/api/v1/messages?limit=1" >/dev/null \
    || { log "the dev lab's mailbox ($DEV_MAIL) refuses the Keychain token — or pass --offline"; exit 1; }
fi

# Polls the mailbox for the OTP sent to $1 and writes the code (only) to $2. Runs in the
# background while Control's onboarding test waits for that file.
otp_poller() { # $1 = address, $2 = output file
  local query deadline=$(( $(date +%s) + 300 )) code
  query="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote("to:\"%s\"" % sys.argv[1]))' "$1")"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    code="$(mail_get "/api/v1/search?query=$query&limit=1" | python3 -c '
import json, re, sys
try:
    msgs = json.load(sys.stdin).get("messages") or []
except ValueError:
    msgs = []
m = msgs and re.search(r"\b(\d{6})\b", msgs[0].get("Snippet", ""))
print(m.group(1) if m else "")' 2>/dev/null || true)"
    if [ -n "$code" ]; then
      (umask 077; echo "$code" > "$2.tmp" && mv "$2.tmp" "$2")
      return 0
    fi
    sleep 2
  done
  return 1
}

# ── Build ────────────────────────────────────────────────────────────────────
# Both apps always build: Drive's capture needs Control's genesis.
if [ "$SKIP_BUILD" = 0 ]; then
  # Each app's Xcode prebuild links its own Kotlin framework (scripts/ios-build-sdk.sh <app>);
  # there is nothing to assemble first.
  # ⚠ The issuer goes in on every run, --offline too: it is inert without dev-lab records, and
  # flipping it would recompile identity-service and everything above it each time.
  # ⚠⚠ Exported, not prefixed: the prebuild runs gradle inside xcodebuild, and without the
  # variable it would build the framework WITHOUT the issuer — the record then fails as
  # "unknown publisher" and the realm never syncs.
  export TENSHU_EXTRA_ISSUER="$DEV_ISSUER"
  SCHEMES=(ControlIos DriveIos)
  wants photos && SCHEMES+=(PhotosIos)
  wants fit && SCHEMES+=(FitIos)
  wants calendar && SCHEMES+=(CalendarIos)
  wants contacts && SCHEMES+=(ContactsIos)
  wants timeline && SCHEMES+=(TimelineIos)
  wants mia && SCHEMES+=(CalendarIos FitIos MiaIos)
  for scheme in "${SCHEMES[@]}"; do
    (cd "$(echo "$scheme" | sed 's/Ios$//' | tr '[:upper:]' '[:lower:]')-ios" && xcodegen generate --quiet)
  done
  for scheme in "${SCHEMES[@]}"; do
    dir="$(echo "$scheme" | sed 's/Ios$//' | tr '[:upper:]' '[:lower:]')-ios"
    log "building $scheme-Screenshots…"
    xcodebuild build-for-testing \
      -project "$dir/$scheme.xcodeproj" -scheme "$scheme-Screenshots" \
      -destination 'generic/platform=iOS Simulator' \
      -derivedDataPath "$WORK/dd-$scheme" \
      > "$WORK/build-$scheme.log" 2>&1 \
      || { tail -40 "$WORK/build-$scheme.log"; log "FAIL — $scheme build (log: $WORK/build-$scheme.log)"; exit 1; }
  done
fi

if wants drive || wants photos || wants mia; then
  # Fetches each `fetch` entry into build/store/fixture-cache (sha256-verified, fails loudly on a
  # mismatch) and draws each `generate` one — before any simulator is touched.
  rm -rf "$FIXTURE"
  python3 store/fixtures/generate.py "$FIXTURE_NAME" "$FIXTURE"
fi
FIT_FIXTURE="$ROOT/build/store/fixture/fit"
# Mia's iPhone walk asks about Fit's workouts (§3g); the iPad's cannot seed them (§3e).
if wants fit || { wants mia && case " ${DEVICES[*]} " in *" iphone69 "*) true ;; *) false ;; esac; }; then
  # Generated, not fetched: dated relative to today, so a run always shows a current week.
  rm -rf "$FIT_FIXTURE"
  python3 store/fixtures/fit/generate_fit.py "$FIT_FIXTURE"
fi
CALENDAR_FIXTURE="$ROOT/build/store/fixture/calendar"
if wants calendar || wants mia; then
  # Generated, dated relative to today: the current week is always the full one.
  rm -rf "$CALENDAR_FIXTURE"
  python3 store/fixtures/calendar/generate_calendar.py "$CALENDAR_FIXTURE"
fi
CONTACTS_FIXTURE="$ROOT/build/store/fixture/contacts"
if wants contacts; then
  # One .vcf of invented people; the generator refuses a real-looking number or address.
  rm -rf "$CONTACTS_FIXTURE"
  python3 store/fixtures/contacts/generate_contacts.py "$CONTACTS_FIXTURE"
fi
TIMELINE_FIXTURE="$ROOT/build/store/fixture/timeline"
if wants timeline; then
  # Generated, dated relative to today: the trip ends today, so the run must be after its morning.
  rm -rf "$TIMELINE_FIXTURE"
  python3 store/fixtures/timeline/generate_timeline.py "$TIMELINE_FIXTURE"
fi

# ── Simulators ───────────────────────────────────────────────────────────────
sim_udid() { # $1 = device key → UDID of tenshu-shots-<key>, created on first use
  local name="tenshu-shots-$1" udid
  # By name, or by the UDID a run interrupted mid-rename left behind.
  udid="$(xcrun simctl list devices -j | python3 -c '
import json, os, sys
name, remembered = sys.argv[1], sys.argv[2]
known = open(remembered).read().strip() if os.path.exists(remembered) else ""
for devs in json.load(sys.stdin)["devices"].values():
    for d in devs:
        if d["isAvailable"] and (d["name"] == name or d["udid"] == known):
            print(d["udid"]); sys.exit(0)' "$name" "$WORK/sim-$1.udid")"
  if [ -z "$udid" ]; then
    udid="$(xcrun simctl create "$name" "$(device_type "$1")" "$RUNTIME")"
  fi
  echo "$udid" > "$WORK/sim-$1.udid"
  echo "$udid"
}

# Give every simulator this run renamed its own name back, however the run ends.
RENAMED=()
restore_names() {
  local entry
  for entry in "${RENAMED[@]+"${RENAMED[@]}"}"; do
    xcrun simctl rename "${entry%%=*}" "tenshu-shots-${entry#*=}" >/dev/null 2>&1 || true
  done
}
# The phrase of the iPhone's realm, for Fit's iPad recovery (§3e). A throwaway dev-lab realm's,
# still never left behind.
PHRASE_FILE="$WORK/fit-realm-phrase"
FIT_IPHONE_OK=0
rm -f "$PHRASE_FILE"
trap 'restore_names; rm -f "$PHRASE_FILE"' EXIT

# The simulator's data directory (its `/var/mobile`), from simctl rather than a guessed path.
sim_data_path() { # $1 = UDID
  xcrun simctl list devices -j | python3 -c '
import json, sys
for devs in json.load(sys.stdin)["devices"].values():
    for d in devs:
        if d["udid"] == sys.argv[1]:
            print(d["dataPath"]); sys.exit(0)
sys.exit("no simulator " + sys.argv[1])' "$1"
}

prepare_sim() { # $1 = UDID, $2 = device key — a clean device with the store status bar
  xcrun simctl shutdown "$1" >/dev/null 2>&1 || true
  xcrun simctl erase "$1"
  # The device's own language and region, matching the run's. `-testLanguage/-testRegion` reach
  # the app only; SpringBoard draws the iPad status bar's date ("Wed 14. Oct") in the DEVICE's
  # locale, which an erased simulator inherits from this Mac.
  local global
  global="$(sim_data_path "$1")/Library/Preferences/.GlobalPreferences.plist"
  mkdir -p "$(dirname "$global")"
  [ -f "$global" ] || plutil -create xml1 "$global"
  plutil -replace AppleLocale -string "${LANG_CODE}_$REGION" "$global"
  plutil -replace AppleLanguages -json "[\"$LANG_CODE\"]" "$global"
  if [ "$2" = ipad13 ]; then
    # ⚠ A fresh iPadOS 26+ simulator is in Multitasking & Gestures → "Windowed Apps": every app is
    # a resizable window and SpringBoard draws its resize grabber at the bottom-right corner, into
    # every capture. `SBMedusaMultitaskingEnabled = NO` (private SpringBoard state, one of the keys
    # that Settings pane manages) runs apps full screen, with no grabber. Written into the erased
    # device's preferences BEFORE the boot, so SpringBoard reads it at start. The app is untouched:
    # its iPad windowing support is the same as in the store build. Doc: store-screenshots.md §2.
    local prefs
    prefs="$(sim_data_path "$1")/Library/Preferences"
    mkdir -p "$prefs"
    plutil -create xml1 "$prefs/com.apple.springboard.plist"
    plutil -insert SBMedusaMultitaskingEnabled -bool NO "$prefs/com.apple.springboard.plist"
  fi
  xcrun simctl boot "$1"
  xcrun simctl bootstatus "$1" -b >/dev/null
  xcrun simctl status_bar "$1" override \
    --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
    --cellularMode active --cellularBars 4 --operatorName "" \
    --batteryState discharging --batteryLevel 100
  # Face ID enrolled, so Control's app lock (on by default) prompts for it on a relaunch and
  # `face_id_matches` can answer the prompt the way a user's face does.
  xcrun simctl spawn "$1" notifyutil -s com.apple.BiometricKit.enrollmentChanged 1
  xcrun simctl spawn "$1" notifyutil -p com.apple.BiometricKit.enrollmentChanged
}

# Answers every Face ID prompt with a match until killed. A match with no prompt up does nothing.
face_id_matches() { # $1 = UDID
  while true; do
    xcrun simctl spawn "$1" notifyutil -p com.apple.BiometricKit_Sim.pearl.match >/dev/null 2>&1 || true
    sleep 3
  done
}

# The runner's environment: the fixture, and — unless --offline — the dev lab's origins, which the
# tests forward to the apps. Extra `KEY=value` words (the sign-up's) follow the four arguments.
run_tests() { # $1 = UDID, $2 = scheme, $3 = only-testing, $4 = result bundle, [$5… = KEY=value]
  local dir env=(TEST_RUNNER_STORE_FIXTURE_DIR="$FIXTURE")
  dir="$(echo "$2" | sed 's/Ios$//' | tr '[:upper:]' '[:lower:]')-ios"
  if [ "$OFFLINE" = 0 ]; then
    env+=(TEST_RUNNER_TENSHU_DEV_RELAY="$DEV_RELAY" TEST_RUNNER_TENSHU_COMMERCE_URL="$DEV_COMMERCE"
          TEST_RUNNER_TENSHU_BILLING_MOCK=false)
  fi
  # Another language: the walk's English labels → this app's own (§4, ShotStrings in
  # ios-shell/UITestSupport). An English run passes no table and looks up the English labels alone.
  if [ "$LANG_CODE" != en ]; then
    # ⚠ `run_tests` runs under `if`/`&&`, where `set -e` is off: without the `||` a failed table
    # would leave the runner on English labels only, failing much later at an unrelated wait.
    python3 "$ROOT/scripts/lib/ios_shot_strings.py" "$dir" "$LANG_CODE" "$WORK/strings-$dir-$LANG_CODE.json" \
      || { log "FAIL — the $LANG_CODE label table for $dir"; return 1; }
    env+=(TEST_RUNNER_SHOTS_STRINGS="$WORK/strings-$dir-$LANG_CODE.json")
  fi
  env+=("${@:5}")
  rm -rf "$4"
  env "${env[@]}" xcodebuild test-without-building \
    -project "$dir/$2.xcodeproj" -scheme "$2-Screenshots" \
    -destination "id=$1" -derivedDataPath "$WORK/dd-$2" \
    -testLanguage "$LANG_CODE" -testRegion "$REGION" \
    -only-testing "$3" -resultBundlePath "$4" \
    > "$4.log" 2>&1 \
    || { grep -E 'error:|failed|XCTAssert' "$4.log" | tail -20; log "FAIL — $3 (log: $4.log, result: $4)"; return 1; }
}

# Copy a result bundle's attachments to the store layout and check their pixel size. Each exported
# screen name is appended to $4, for check_screens.
export_shots() { # $1 = result bundle, $2 = app, $3 = device key, $4 = list of exported names
  # The landing set is a device of its own (`iphone69landing`), sized as the device it ran on.
  local out="$ROOT/store/$2/screenshots/ios/$3$OUT_SUFFIX/$LANG_CODE" raw="$1.attachments"
  # Dark captures go to their own folder: store-publish.sh maps it to the `…/dark/NN-screen` keys.
  [ "$APPEARANCE" = light ] || out="$out/$APPEARANCE"
  rm -rf "$raw"
  xcrun xcresulttool export attachments --path "$1" --output-path "$raw" >/dev/null
  mkdir -p "$out"
  python3 - "$raw" "$out" "$(accepted_sizes "$3")" "$4" <<'PY'
import json, os, re, shutil, subprocess, sys
raw, out, accepted, exported = sys.argv[1], sys.argv[2], sys.argv[3].split(), sys.argv[4]
bad = []
for test in json.load(open(os.path.join(raw, "manifest.json"))):
    for a in test["attachments"]:
        # The suggested name is "<attachment name>_<n>_<uuid>.png"; the name is ours (NN-screen).
        name = re.sub(r"_\d+_[0-9A-Fa-f-]{36}(\.\w+)$", r"\1", a["suggestedHumanReadableName"])
        if not re.match(r"^\d\d-[a-z0-9-]+\.png$", name):
            continue
        dst = os.path.join(out, name)
        shutil.copyfile(os.path.join(raw, a["exportedFileName"]), dst)
        dims = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", dst],
                              capture_output=True, text=True).stdout.split()
        size = f"{dims[-3]}x{dims[-1]}"
        print(f"  {os.path.relpath(dst)}  {size}")
        with open(exported, "a") as f:
            f.write(name[:-4] + "\n")
        if size not in accepted:
            bad.append(f"{name} is {size}, App Store Connect takes {' or '.join(accepted)}")
if bad:
    sys.exit("store-screenshots: wrong size:\n  " + "\n  ".join(bad))
PY
}

# Warns when a run captured a screen store/<app>/screens.json does not list, or missed one it lists
# (`devLabOnly` ones are not expected on an --offline run, nor one whose `devices` leave this device out). The list is what the support pages'
# shot: slots are checked against (web/apex/support-pages/screens.test.ts), so a capture missing
# from it can never be shown, and a listed one that stopped being captured leaves a placeholder.
# A warning, not a failure: the captures themselves are fine either way.
check_screens() { # $1 = app, $2 = device key, $3 = list of exported names
  python3 - "$ROOT/store/$1/screens.json" "$3" "$OFFLINE" "$1" "$2" "$SET" <<'PY'
import json, os, sys
listed_path, exported_path, offline, app, device, set_ = sys.argv[1:7]
screens = json.load(open(listed_path))
# The landing set's iOS list: landing.ios.captured (store-screenshots.md §9).
listed = screens["landing"]["ios"]["captured"] if set_ == "landing" else screens["captured"]
expected = {n for n, e in listed.items()
            if not (offline == "1" and e.get("devLabOnly")) and device in e.get("devices", [device])}
got = set(open(exported_path).read().split()) if os.path.exists(exported_path) else set()
for name in sorted(got - set(listed)):
    print(f"store-screenshots: WARNING — {app}/{device}: captured {name}, which store/{app}/screens.json does not list (add it there)")
for name in sorted(expected - got):
    print(f"store-screenshots: WARNING — {app}/{device}: store/{app}/screens.json lists {name}, but this run did not capture it")
PY
}

OUT_SUFFIX=""
[ "$SET" = support ] || OUT_SUFFIX=landing
FAILED=0
for device in "${DEVICES[@]}"; do
for APPEARANCE in "${APPEARANCE_LIST[@]}"; do # --appearance both: each device twice, fresh each time
  udid="$(sim_udid "$device")"
  log "$device ($APPEARANCE) — simulator $udid ($RUNTIME), erasing…"
  xcrun simctl rename "$udid" "$(display_name "$device")"
  RENAMED+=("$udid=$device")
  prepare_sim "$udid" "$device"
  # After the boot (simctl ui needs a booted device), before any app launches.
  xcrun simctl ui "$udid" appearance "$APPEARANCE"
  results="$WORK/$device$OUT_SUFFIX-$LANG_CODE"
  [ "$APPEARANCE" = light ] || results="$results-$APPEARANCE"
  mkdir -p "$results"
  rm -f "$results"/*.exported

  # A fresh throwaway account per device and run. Commerce takes any address with an "@"; the dev
  # lab's mail is a sink, so example.com (reserved, never delivered) keeps the dev domain off the
  # Storage screen, which shows the address.
  # Fit's iPad walk joins the iPhone's realm instead (§3e): no account, no genesis — a recovery.
  joining=0
  [ "$FIT_JOINS_ON_IPAD" = 1 ] && [ "$device" = ipad13 ] && joining=1
  if [ "$joining" = 1 ]; then
    if [ "$FIT_IPHONE_OK" != 1 ] || [ ! -s "$PHRASE_FILE" ]; then
      FAILED=1
      log "$device — skipping fit: the iPhone's walk did not finish, so there is no realm to restore"
      continue
    fi
    if run_tests "$udid" ControlIos ControlIosScreenshots/ControlRecoverScreenshots \
        "$results/control-recover.xcresult" TEST_RUNNER_SHOTS_PHRASE_FILE="$PHRASE_FILE"; then
      if run_tests "$udid" FitIos FitIosScreenshots/FitScreenshots/testStoreScreenshotsFromSyncedRealm \
          "$results/fit.xcresult"; then
        export_shots "$results/fit.xcresult" fit "$device" "$results/fit.exported" || FAILED=1
      else
        FAILED=1
      fi
    else
      FAILED=1
      log "$device — Control could not recover the iPhone's realm; no Fit walk on this device"
    fi
    check_screens fit "$device" "$results/fit.exported"
    [ "$KEEP_SIMS" = 1 ] || xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    continue
  fi

  signup=()
  poller=""
  # The iPhone's onboarding saves the realm's phrase for the iPad's recovery (above).
  [ "$FIT_JOINS_ON_IPAD" = 1 ] && signup+=(TEST_RUNNER_SHOTS_PHRASE_FILE="$PHRASE_FILE")
  if [ "$OFFLINE" = 0 ]; then
    email="alex.$(python3 -c 'import time; n=int(time.time()*10); s=""
while n: n, r = divmod(n, 36); s = "0123456789abcdefghijklmnopqrstuvwxyz"[r] + s
print(s)')@example.com"
    otp_file="$results/otp"
    rm -f "$otp_file"
    log "$device — signing up $email on the dev lab"
    otp_poller "$email" "$otp_file" &
    poller=$!
    signup+=(TEST_RUNNER_SHOTS_ACCOUNT_EMAIL="$email" TEST_RUNNER_SHOTS_OTP_FILE="$otp_file")
  fi
  onboarded=0
  run_tests "$udid" ControlIos ControlIosScreenshots/ControlOnboardingScreenshots \
    "$results/control-onboarding.xcresult" "${signup[@]+"${signup[@]}"}" && onboarded=1
  if [ -n "$poller" ]; then
    kill "$poller" 2>/dev/null || true
    wait "$poller" 2>/dev/null || true
    rm -f "$otp_file"
  fi
  if [ "$onboarded" = 1 ]; then
    if wants control && [ "$SET" = support ]; then export_shots "$results/control-onboarding.xcresult" control "$device" "$results/control.exported" || FAILED=1; fi
  else
    FAILED=1
    log "$device ($APPEARANCE) — Control's onboarding failed; nothing else can run on this device"
    continue
  fi

  drive_ok=0
  if wants drive; then
    # ⚠ The method, not the class: the class also holds Mia's seed-only test (§3g).
    drive_test=DriveIosScreenshots/DriveScreenshots/testStoreScreenshots
    # ⚠ Likewise: the landing class also holds Photos' seed-only test.
    [ "$SET" = support ] || drive_test=DriveIosScreenshots/DriveLandingScreenshots/testLandingScreenshots
    if run_tests "$udid" DriveIos "$drive_test" "$results/drive.xcresult"; then
      drive_ok=1
      export_shots "$results/drive.xcresult" drive "$device" "$results/drive.exported" || FAILED=1
    else
      FAILED=1
    fi
  fi

  # Photos' support set: Drive seeds the landing photos and captures nothing (never with `drive`).
  photos_here=1
  if wants photos && [ "$SET" = support ] && ! photos_runs_on "$device" "$APPEARANCE"; then
    photos_here=0
    log "$device ($APPEARANCE) — skipping photos (light only so far)"
  fi
  if wants photos && [ "$SET" = support ] && [ "$photos_here" = 1 ]; then
    if run_tests "$udid" DriveIos DriveIosScreenshots/DriveLandingScreenshots/testSeedLandingRealm "$results/drive-seed.xcresult"; then
      drive_ok=1
    else
      FAILED=1
    fi
  fi

  # Photos reads the realm the landing Drive walk (or seed) filled, so it runs after it, and only if it ran.
  if wants photos && [ "$drive_ok" = 1 ] && [ "$photos_here" = 1 ]; then
    photos_test=PhotosIosScreenshots/PhotosScreenshots
    [ "$SET" = support ] || photos_test=PhotosIosScreenshots/PhotosLandingScreenshots
    if run_tests "$udid" PhotosIos "$photos_test" "$results/photos.xcresult"; then
      export_shots "$results/photos.xcresult" photos "$device" "$results/photos.exported" || FAILED=1
    else
      FAILED=1
    fi
  fi

  if wants control && [ "$SET" = support ]; then
    face_id_matches "$udid" &
    matcher=$!
    if run_tests "$udid" ControlIos ControlIosScreenshots/ControlMainScreenshots "$results/control-main.xcresult"; then
      export_shots "$results/control-main.xcresult" control "$device" "$results/control.exported" || FAILED=1
    else
      FAILED=1
    fi
    kill "$matcher" 2>/dev/null || true
    wait "$matcher" 2>/dev/null || true
  fi

  # Fit needs only the realm Control created; its fixture goes in through its own seed launch.
  # ⚠ After Control's main captures, so Fit's imports never change the realm those show.
  # ⚠ The method, not the class: the class also holds the iPad's synced walk.
  if wants fit && fit_runs_on "$device" "$APPEARANCE"; then
    if run_tests "$udid" FitIos FitIosScreenshots/FitScreenshots/testStoreScreenshots "$results/fit.xcresult" \
        TEST_RUNNER_STORE_FIXTURE_DIR="$FIT_FIXTURE"; then
      [ "$device" != iphone69 ] || FIT_IPHONE_OK=1
      export_shots "$results/fit.xcresult" fit "$device" "$results/fit.exported" || FAILED=1
    else
      FAILED=1
    fi
  elif wants fit; then
    log "$device ($APPEARANCE) — skipping fit (light only so far)"
  fi

  # Calendar, like Fit, needs only Control's realm; its .ics files go in through its own seed launch.
  if wants calendar && calendar_runs_on "$device" "$APPEARANCE"; then
    if run_tests "$udid" CalendarIos CalendarIosScreenshots/CalendarScreenshots/testStoreScreenshots "$results/calendar.xcresult" \
        TEST_RUNNER_STORE_FIXTURE_DIR="$CALENDAR_FIXTURE"; then
      export_shots "$results/calendar.xcresult" calendar "$device" "$results/calendar.exported" || FAILED=1
    else
      FAILED=1
    fi
  elif wants calendar; then
    log "$device ($APPEARANCE) — skipping calendar (light only so far)"
  fi

  # Contacts, like Fit, needs only Control's realm; its vCards go in through its own seed launch.
  if wants contacts && contacts_runs_on "$device" "$APPEARANCE"; then
    if run_tests "$udid" ContactsIos ContactsIosScreenshots/ContactsScreenshots "$results/contacts.xcresult" \
        TEST_RUNNER_STORE_FIXTURE_DIR="$CONTACTS_FIXTURE"; then
      export_shots "$results/contacts.xcresult" contacts "$device" "$results/contacts.exported" || FAILED=1
    else
      FAILED=1
    fi
  elif wants contacts; then
    log "$device ($APPEARANCE) — skipping contacts (light only so far)"
  fi

  # Timeline, like Fit, needs only Control's realm; its Google Timeline export goes in through its own seed launch.
  if wants timeline && timeline_runs_on "$device" "$APPEARANCE"; then
    if run_tests "$udid" TimelineIos TimelineIosScreenshots/TimelineScreenshots "$results/timeline.xcresult" \
        TEST_RUNNER_STORE_FIXTURE_DIR="$TIMELINE_FIXTURE"; then
      export_shots "$results/timeline.xcresult" timeline "$device" "$results/timeline.exported" || FAILED=1
    else
      FAILED=1
    fi
  elif wants timeline; then
    log "$device ($APPEARANCE) — skipping timeline (light only so far)"
  fi

  # Mia (§3g): the other apps' seed launches fill the realm — nothing of theirs is exported — then
  # Mia's walk asks about it. ⚠ The methods, not the classes: each class also holds the app's own walk.
  if wants mia && mia_runs_on "$device" "$APPEARANCE"; then
    seeded=1 mia_env=()
    run_tests "$udid" DriveIos DriveIosScreenshots/DriveScreenshots/testSeedRealm "$results/mia-seed-drive.xcresult" \
      || seeded=0
    [ "$seeded" = 0 ] || run_tests "$udid" CalendarIos CalendarIosScreenshots/CalendarScreenshots/testSeedRealm \
      "$results/mia-seed-calendar.xcresult" TEST_RUNNER_STORE_FIXTURE_DIR="$CALENDAR_FIXTURE" || seeded=0
    # ⚠ Fit's seed writes to Apple Health, which the iPad simulator refuses (§3e): the workouts are
    # the iPhone's alone, and the walk asks about them only where they were seeded.
    if [ "$seeded" = 1 ] && [ "$device" = iphone69 ]; then
      run_tests "$udid" FitIos FitIosScreenshots/FitScreenshots/testSeedRealm "$results/mia-seed-fit.xcresult" \
        TEST_RUNNER_STORE_FIXTURE_DIR="$FIT_FIXTURE" || seeded=0
      mia_env+=(TEST_RUNNER_MIA_WORKOUTS=1)
    fi
    if [ "$seeded" = 1 ] && run_tests "$udid" MiaIos MiaIosScreenshots/MiaScreenshots "$results/mia.xcresult" \
        "${mia_env[@]+"${mia_env[@]}"}"; then
      export_shots "$results/mia.xcresult" mia "$device" "$results/mia.exported" || FAILED=1
    else
      FAILED=1
      [ "$seeded" = 1 ] || log "$device — a seed launch failed (above); Mia was not walked"
    fi
  fi

  for app in "${APPS[@]}"; do
    [ "$app" != timeline ] || timeline_runs_on "$device" "$APPEARANCE" || continue
    [ "$app" != fit ] || fit_runs_on "$device" "$APPEARANCE" || continue
    [ "$app" != calendar ] || calendar_runs_on "$device" "$APPEARANCE" || continue
    [ "$app" != contacts ] || contacts_runs_on "$device" "$APPEARANCE" || continue
    [ "$app" != photos ] || [ "$SET" != support ] || photos_runs_on "$device" "$APPEARANCE" || continue
    check_screens "$app" "$device" "$results/$app.exported"
  done

  [ "$KEEP_SIMS" = 1 ] || xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
done
done

# The captures are gitignored; this catches one that was force-added (or any binary under store/).
python3 scripts/check-store-assets.py || FAILED=1

if [ "$FAILED" = 0 ]; then
  log "done (${APPEARANCE_LIST[*]}) — store/<app>/screenshots/ios/<device>$OUT_SUFFIX/$LANG_CODE/ (publish: scripts/store-publish.sh --dry-run, then without)"
else
  log "FAILED — see the logs above"
  exit 1
fi
