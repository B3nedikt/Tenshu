#!/usr/bin/env bash
#
# Desktop captures of the INSTALLED macOS apps over the maintainer's own capture realm
# (docs/how-to/store-screenshots.md §10). The macOS twin of scripts/store-screenshots-android.sh,
# but nothing is installed, seeded or signed in here: the apps in /Applications, signed in to the
# realm §10 describes, are driven through macOS Accessibility by scripts/lib/desktop_shots.swift and
# their window captured with `screencapture -l`.
#
#   scripts/store-screenshots-desktop.sh [drive] [photos] [calendar] [contacts] [fit] [--appearance light|dark|both] [--lang en|de|both]
#
# writes, per app, language and appearance:
#
#   store/<app>/screenshots/desktop/macoslanding/<lang>/NN-<screen>.png        light
#   store/<app>/screenshots/desktop/macoslanding/<lang>/dark/NN-<screen>.png   dark
#
# at a fixed 1280 × 800 pt window (2560 × 1600 px on a Retina screen).
# ⚠ Never committed: publish with scripts/store-publish.sh, as the phone captures are.
#
# Appearance and language are set PER LAUNCH, never system-wide: each app is quit and reopened with
# `-AppleInterfaceStyle Light|Dark` (an argument-domain default the app's theme reads) and
# `JAVA_TOOL_OPTIONS=-Duser.language=…` (the JVM locale the Compose resources resolve against).
# Your own appearance and language are never touched.
#
# ⚠⚠ The captures show a REAL realm — look at every one before publishing (§10).
# ⚠ Control is never relaunched: its app lock re-arms and wants Touch ID. Only the app captured is.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/build/store-shots/desktop_shots"
W=1280 H=800

apps=() appearances=(light dark) langs=(en de)
while [ $# -gt 0 ]; do
  case "$1" in
    --appearance) shift; [ "$1" = both ] && appearances=(light dark) || appearances=("$1") ;;
    --lang) shift; [ "$1" = both ] && langs=(en de) || langs=("$1") ;;
    drive|photos|calendar|contacts|fit) apps+=("$1") ;;
    *) echo "store-screenshots-desktop: unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
[ ${#apps[@]} -gt 0 ] || apps=(drive photos calendar contacts fit)

[ "$(uname)" = Darwin ] || { echo "store-screenshots-desktop: macOS only" >&2; exit 2; }
mkdir -p "$(dirname "$BIN")"
if [ ! -x "$BIN" ] || [ "$ROOT/scripts/lib/desktop_shots.swift" -nt "$BIN" ]; then
  swiftc -O "$ROOT/scripts/lib/desktop_shots.swift" -o "$BIN"
fi
ds() { "$BIN" "$@"; }

# Quit PROC and reopen it in APPEARANCE and LANG, at the capture size.
relaunch() { # proc appearance lang first-tab
  local proc="$1" style country
  [ "$2" = dark ] && style=Dark || style=Light
  [ "$3" = de ] && country=DE || country=US
  osascript -e "quit app \"$proc\"" >/dev/null
  for _ in $(seq 1 40); do pgrep -x "$proc" >/dev/null || break; sleep 0.25; done
  pgrep -x "$proc" >/dev/null && { echo "store-screenshots-desktop: $proc did not quit" >&2; exit 1; }
  open -a "$proc" --env JAVA_TOOL_OPTIONS="-Duser.language=$3 -Duser.country=$country" \
    --args -AppleInterfaceStyle "$style"
  # The window is up when a label shows — "a|b" accepts either language's.
  local up=0 label
  for _ in $(seq 1 120); do
    IFS='|' read -ra want <<<"$4"
    for label in "${want[@]}"; do ds has "$proc" "$label" 2>/dev/null && { up=1; break; }; done
    [ $up = 1 ] && break
    sleep 0.5
  done
  [ $up = 1 ] || { echo "store-screenshots-desktop: $proc never showed $4" >&2; exit 1; }
  ds frontmost "$proc"
  ds size "$proc" "$W" "$H" # a corner away from the pointer: no hover highlight
}

# Wait until no upload banner shows: a capture never carries transfer progress.
settled() { # proc
  for _ in $(seq 1 120); do
    ds has "$1" "~Uploading" || ds has "$1" "~Hochladen:" || return 0
    sleep 5
  done
  echo "store-screenshots-desktop: $1 still uploading after 10 min" >&2; exit 1
}

shot() { # proc app lang appearance name
  local dir="$ROOT/store/$2/screenshots/desktop/macoslanding/$3"
  [ "$4" = dark ] && dir="$dir/dark"
  mkdir -p "$dir"
  ds frontmost "$1"; sleep 1 # focused traffic lights, no hover state
  local id _
  read -r id _ <<<"$(ds window "$1")"
  screencapture -l "$id" -o -x "$dir/$5.png"
  echo "  $3 ${4}: $dir/$5.png"
}

walk_drive() { # lang appearance
  # Both languages: the nav labels were English literals until they were translated (drive_nav_*).
  relaunch TenshuDrive "$2" "$1" "Home|Start"
  ds press TenshuDrive Files 2>/dev/null || ds press TenshuDrive Dateien
  sleep 2
  # The view toggle is remembered across launches: frame 1 is the list.
  if ds has TenshuDrive "Switch to list view" || ds has TenshuDrive "Zur Listenansicht wechseln"; then
    ds press TenshuDrive "Switch to list view" 2>/dev/null || ds press TenshuDrive "Zur Listenansicht wechseln"
    sleep 2
  fi
  settled TenshuDrive
  sleep 3 # thumbnails and counts finish drawing
  shot TenshuDrive drive "$1" "$2" 01-files
}

walk_photos() { # lang appearance
  relaunch TenshuPhotos "$2" "$1" Photos
  ds press TenshuPhotos Photos
  # Frame 1 groups by MONTH: by day is three photos a row on a desktop window, by year a wall.
  # The control cycles day-larger → day → month → year. Its label is translated (photos_density_*):
  # both languages are matched, so a build from before the translation walks too.
  by_month() { ds has TenshuPhotos "~Grouped by month." || ds has TenshuPhotos "~Nach Monat gruppiert."; }
  for _ in 1 2 3 4; do
    by_month && break
    ds press TenshuPhotos "~Change grouping" 2>/dev/null || ds press TenshuPhotos "~Gruppierung ändern"
    sleep 1
  done
  by_month || { echo "store-screenshots-desktop: Photos never grouped by month" >&2; exit 1; }
  sleep 8 # the first screen's previews decode
  # The year scrubber's thumb shows only while the feed moves (and fades 1.2 s after): scroll two
  # lines and capture inside that beat. Its year pills show only while the thumb is HELD, and held,
  # the feed stops decoding previews — so frame 1 is the thumb, never the pills (§10).
  local dir="$ROOT/store/photos/screenshots/desktop/macoslanding/$1"
  [ "$2" = dark ] && dir="$dir/dark"
  mkdir -p "$dir"
  ds frontmost TenshuPhotos; sleep 1
  ds scroll TenshuPhotos 2 "$dir/01-library.png"
  echo "  $1 ${2}: $dir/01-library.png"
}

walk_calendar() { # lang appearance
  # ⚠ The realm's events were imported on 2026-11-20 from the fixture generated for that day
  # (store/fixtures/calendar/generate_calendar.py): Today shows a full month only around then.
  # A later run re-generates and re-imports (§10.1), or the month comes out nearly empty.
  relaunch TenshuCalendar "$2" "$1" "Manage calendars|Kalender verwalten"
  # Both languages: these were English literals until Calendar's strings were translated.
  ds press TenshuCalendar Month 2>/dev/null || ds press TenshuCalendar Monat
  ds press TenshuCalendar Today 2>/dev/null || ds press TenshuCalendar Heute
  sleep 3
  shot TenshuCalendar calendar "$1" "$2" 01-month
}

walk_contacts() { # lang appearance
  # The realm holds the 28 invented people of §3c, imported once through Add a contact → Import
  # contacts → Import a .vcf file, 18 with a generated portrait (§10.1).
  relaunch TenshuContacts "$2" "$1" "Contacts|Kontakte" # the rail tab
  ds press TenshuContacts Contacts 2>/dev/null || ds press TenshuContacts Kontakte
  # The duplicates row ("2 possible duplicates") stays: the fixture makes exactly two on purpose.
  sleep 4 # portraits decode
  shot TenshuContacts contacts "$1" "$2" 01-contacts
}

walk_fit() { # lang appearance
  # ⚠ Dated like Calendar's: the workouts and the Takeout daily figures were generated for
  # 2026-11-20 and imported through File → Import activity file… (§10.1). Today's ring is full only then.
  relaunch TenshuFit "$2" "$1" "Home|Start"
  ds press TenshuFit Home 2>/dev/null || ds press TenshuFit Start
  sleep 3
  shot TenshuFit fit "$1" "$2" 01-home
}

for app in "${apps[@]}"; do
  echo "$app:"
  for lang in "${langs[@]}"; do
    for appearance in "${appearances[@]}"; do
      "walk_$app" "$lang" "$appearance"
    done
  done
done
