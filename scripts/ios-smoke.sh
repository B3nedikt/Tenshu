#!/usr/bin/env bash
#
# iOS on-device PR smoke gate (physical iPhone).
#
# Finds the connected, developer-provisioned iPhone, optionally rebuilds +
# reinstalls the requested apps (free-tier signed, docs/how-to/ios-signing.md
# §§2–3), then launches each one and FAILS if its process is not still alive a
# few seconds later. The iOS analogue of scripts/android-smoke.sh: it catches
# DI-wiring / startup / SKIE-bridge regressions that neither the Kotlin unit
# tests nor a simulator xcodebuild can — the device runs the *signed* bundle
# with the real Keychain, entitlements, and provisioning profile.
#
# It is a *smoke* gate, not a functional one: it proves the apps build, sign,
# install, and survive launch. Taps and flows need an XCUITest bundle (whose
# runner app also counts against the free-tier 3-apps-per-device cap), and
# genuinely device-dependent behaviour (HealthKit grants, camera pairing)
# still needs a person holding the phone.
#
# Usage:   scripts/ios-smoke.sh [--build|--build-only] [app ...]
#            app ∈ control drive fit timeline maps contacts home mia calendar photos
#                                              (default: control drive fit)
#            --build       rebuild + reinstall each app before launching
#            --build-only  compile each app and stop — no device, no install,
#                          no launch (see below for why this exists)
#          ⚠ Free personal team: at most 3 of the 10 apps fit on the device at
#            once — pick your trio (or swap with `devicectl device uninstall`).
#
# ⚠⚠ **Add a new iOS app here the day its Xcode project exists.** The list below
# is not documentation of what exists, it is what gets compiled — and an app
# missing from it is invisible to every gate in the repo: nothing notices unless
# someone counts the PASS lines against the xcodegen output.
#
# ⚠⚠ `--build-only` catches an app shell whose Swift does not compile: naming
# Kotlin members by their plain Obj-C export spelling (`TrustState.verified`,
# `MergeReason.sharedphone`) while SKIE bridges both differently, calling a
# `toNSData()` that is private to Kotlin, or using an iOS-17 navigation API in an
# iOS-16 app. Every Kotlin gate stays green over all of these — none of them
# compiles Swift — and the
# device path above cannot be the backstop, because it needs a plugged-in iPhone
# and only three of the six apps fit on one at a time. A build needs neither, so
# it is the check that can actually run on every iOS-touching change.
# Env:     TENSHU_IOS_DEVICE   devicectl device identifier (default: the first
#                              connected/available iPhone in `devicectl list`)
# Exit:    0 = passed, non-zero = a check failed (see output).
#
# First-time device setup (cable + pair + Developer Mode + trust) is manual —
# see ios-signing.md §3. Free 7-day profiles: when launches start failing with
# a trust/integrity error a week after signing, rerun with --build to re-mint.

set -euo pipefail

# Queue behind any other gate or smoke run on this machine — see the file for why. Only when this
# run BUILDS: a launch-only pass costs the Mac nothing and should not wait out someone's gate.
# shellcheck source=scripts/lib/gate-lock.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/gate-lock.sh"
case " $* " in *" --build"*) tenshu_gate_lock "$0" "$@" ;; esac

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

ALIVE_AFTER_SECONDS=6

# app key → "dir scheme bundle-id"
spec_for() {
  case "$1" in
    control)  echo "control-ios ControlIos app.tenshu.control.ios" ;;
    drive)    echo "drive-ios DriveIos app.tenshu.drive.ios" ;;
    fit)      echo "fit-ios FitIos app.tenshu.fit.ios" ;;
    timeline) echo "timeline-ios TimelineIos app.tenshu.timeline.ios" ;;
    maps)     echo "maps-ios MapsIos app.tenshu.maps.ios" ;;
    contacts) echo "contacts-ios ContactsIos app.tenshu.contacts.ios" ;;
    home)     echo "home-ios HomeIos app.tenshu.home.ios" ;;
    mia)      echo "mia-ios MiaIos app.tenshu.mia.ios" ;;
    calendar) echo "calendar-ios CalendarIos app.tenshu.calendar.ios" ;;
    photos) echo "photos-ios PhotosIos app.tenshu.photos.ios" ;;
    *) echo "unknown app '$1' (expected control|drive|fit|timeline|maps|contacts|home|mia|calendar|photos)" >&2; exit 2 ;;
  esac
}

BUILD=0
BUILD_ONLY=0
APPS=()
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --build-only) BUILD=1; BUILD_ONLY=1 ;;
    *) APPS+=("$arg") ;;
  esac
done
# ⚠ The default trio is the *launch* default and stays three, because that is the
# free-tier cap. `--build-only` has no such limit, so it defaults to every app —
# an app left out of a compile gate is exactly how contacts-ios went dark.
if [ ${#APPS[@]} -eq 0 ]; then
  if [ "$BUILD_ONLY" = 1 ]; then
    APPS=(control drive fit timeline maps contacts home mia calendar photos)
  else
    APPS=(control drive fit)
  fi
fi

# ── Device ────────────────────────────────────────────────────────────────────
# The State column must say connected/available; "unavailable" means locked,
# unpaired, or a charge-only cable (`ioreg -p IOUSB` shows nothing at all).
# ⚠ Skipped entirely under `--build-only`: requiring a device to *compile* is what
# would keep the compile gate off CI and off any machine without a phone plugged in.
DEVICE=""
if [ "$BUILD_ONLY" = 0 ]; then
  DEVICE="${TENSHU_IOS_DEVICE:-}"
  if [ -z "$DEVICE" ]; then
    # ⚠⚠ Two UDID shapes. The 8-4-4-4-12 form is a *simulator* UUID; a physical device since the XS
    # reports 8-16 (`00008150-0011085C1E85401C`), which is what `devicectl` prints in the Identifier
    # column for a connected phone. Matching only the first form makes this resolve nothing and the
    # caller report "no device" for a phone on the desk — the bug this pattern shares with
    # `app-catalogue.sh:tenshu_ios_device`, which the comment there says it agrees with. Keep them
    # identical or the two scripts silently pick different devices. ⚠⚠ `physical`: a BOOTED simulator
    # also lists as `connected`, and would be picked over a locked phone.
    DEVICE="$(xcrun devicectl list devices 2>/dev/null \
      | grep -E ' (connected|available)' | grep -v unavailable | grep -w physical \
      | grep -oE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}' \
      | head -1 || true)"
  fi
  if [ -z "$DEVICE" ]; then
    echo "ios-smoke: no connected iPhone." >&2
    echo "  Plug in with a DATA cable, unlock, trust, enable Developer Mode (ios-signing.md §3)." >&2
    echo "  (To check only that the apps still compile, use --build-only — no device needed.)" >&2
    exit 1
  fi
  echo "ios-smoke: device $DEVICE"
fi

processes() {
  xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null
}

pid_of() { # $1 = scheme (binary name)
  processes | grep -F "/$1.app/$1" | awk '{print $1}' | head -1
}

# --- store-stamp guard -------------------------------------------------------
#
# ⚠⚠ A store WIPE is invisible to "the process is still alive". Installing a build
# that is behind on any ONE service version makes `StoreSchemaMigrator` treat the
# store as "written by newer code" and call `wipeAllRows()` — every service's rows,
# not just the drifting one's — then re-stamp the store down to this build's
# numbers — e.g. a branch that predates a merged sync bump. Without this guard the
# gate passes it green.
#
# The stamp is two tiny files next to the store, so reading it before and after
# costs a pair of copies per app. A version that DECREASED across the install is
# the signal: only the wipe path writes a lower number.

# Read the app's store stamp into a file as `key=version` lines (core as
# `tenshu/core`). Returns non-zero when there is nothing to compare — a fresh
# install, an unreadable container — which callers treat as "skip", never as a
# failure.
read_store_stamp() { # $1 = bundle id, $2 = output file
  local bundle="$1" out="$2" tmp
  tmp="$(mktemp -d)" || return 1
  : > "$out"
  if xcrun devicectl device copy from --device "$DEVICE" \
      --domain-type appDataContainer --domain-identifier "$bundle" \
      --source Documents/objects/.formatversion --destination "${tmp}/fv" >/dev/null 2>&1 \
      && [ -s "${tmp}/fv" ]; then
    echo "tenshu/core=$(tr -dc '0-9' < "${tmp}/fv")" >> "$out"
  fi
  if xcrun devicectl device copy from --device "$DEVICE" \
      --domain-type appDataContainer --domain-identifier "$bundle" \
      --source Documents/objects/.serviceversions --destination "${tmp}/sv" >/dev/null 2>&1 \
      && [ -s "${tmp}/sv" ]; then
    grep -E '^[^=]+=[0-9]+$' "${tmp}/sv" >> "$out" || true
  fi
  rm -rf "$tmp"
  [ -s "$out" ]
}

# Print `service before->after` for every entry whose version went DOWN.
stamp_regressions() { # $1 = before file, $2 = after file
  awk -F= '
    NR == FNR { before[$1] = $2; next }
    ($1 in before) && ($2 + 0) < (before[$1] + 0) {
      printf "%s %s->%s\n", $1, before[$1], $2
    }
  ' "$1" "$2"
}

# ⚠⚠ Nothing is assembled up front: each app's Xcode prebuild links that app's own Kotlin framework
# fresh, every build (`scripts/ios-build-sdk.sh <app>`, `docs/proposals/ios-per-app-frameworks.md`),
# so a Swift/Kotlin API mismatch is always compiled against today's Kotlin, never yesterday's
# framework. (Until P5 a shared umbrella XCFramework was assembled here first for apps still on it.)

# ⚠⚠ A `TenshuSdk.framework` in Xcode's products dir (a pre-P5 leftover) SHADOWS the fresh one:
# that dir is first on `-F`. Nothing writes one there any more, so it is always stale, and the
# symptom doesn't look like staleness — "has no member" for a member the fresh framework has.
stale_products_framework() { # $1 = scheme; prints any shadowing framework
  ls -d build/ios-sim/"$1"/Build/Products/*/TenshuSdk.framework \
        build/ios-device/"$1"/Build/Products/*/TenshuSdk.framework 2>/dev/null || true
}

FAILED=0
for app in "${APPS[@]}"; do
  read -r dir scheme bundle <<<"$(spec_for "$app")"

  if [ "$BUILD" = 1 ]; then
    stale="$(stale_products_framework "$scheme")"
    if [ -n "$stale" ]; then
      echo "ios-smoke: FAIL $app — a stale TenshuSdk.framework shadows the fresh one (remove it):"
      echo "$stale" | sed 's/^/  rm -rf /'
      FAILED=1
      continue
    fi
    echo "ios-smoke: building ${scheme}…"
    if [ "$BUILD_ONLY" = 1 ]; then
      # ⚠ Simulator SDK, not `generic/platform=iOS`: a device build needs a
      # signing identity, and requiring one to answer "does the Swift still
      # compile" would put the gate out of reach of CI and of a fresh clone.
      # ⚠⚠ Resolve TENSHU_TEAM_ID from local.properties first, exactly as the
      # githook does. A bare `xcodegen generate` regenerates with the variable
      # UNSET, so `DEVELOPMENT_TEAM[sdk=iphoneos*]` lands as the literal
      # `${TENSHU_TEAM_ID}` — harmless for this simulator build, but it leaves the
      # project unsignable, and the NEXT `--build` dies with "Signing for
      # 'DriveIos' requires a development team". A compile gate must not be able
      # to break the device path it shares a script with.
      if [ -z "${TENSHU_TEAM_ID:-}" ] && [ -f local.properties ]; then
        TENSHU_TEAM_ID="$(sed -n 's/^tenshu\.teamId=//p' local.properties | head -1)"
      fi
      (cd "$dir" && TENSHU_TEAM_ID="${TENSHU_TEAM_ID:-}" xcodegen generate >/dev/null 2>&1) || true
      xcodebuild -project "$dir/$scheme.xcodeproj" -scheme "$scheme" \
        -configuration Debug -sdk iphonesimulator \
        -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath "build/ios-sim/$scheme" build >/dev/null 2>&1 \
        && echo "ios-smoke: PASS $app (compiles)" \
        || { echo "ios-smoke: FAIL $app — xcodebuild failed (rerun without >/dev/null for details)"; FAILED=1; }
      continue
    fi
    # generic destination is enough once the device is registered in the
    # profile (first registration: build once with the concrete device, §3).
    xcodebuild -project "$dir/$scheme.xcodeproj" -scheme "$scheme" \
      -configuration Debug -destination 'generic/platform=iOS' \
      -derivedDataPath "build/ios-device/$scheme" \
      -allowProvisioningUpdates build >/dev/null 2>&1 \
      || { echo "ios-smoke: FAIL $app — xcodebuild failed (rerun without >/dev/null for details)"; FAILED=1; continue; }
    # Record the store stamp BEFORE the new binary can open (and possibly wipe)
    # the store. Absent on a first install — then there is nothing to lose.
    stamp_before="$(mktemp)"
    read_store_stamp "$bundle" "$stamp_before" || { rm -f "$stamp_before"; stamp_before=""; }

    xcrun devicectl device install app --device "$DEVICE" \
      "build/ios-device/$scheme/Build/Products/Debug-iphoneos/$scheme.app" >/dev/null \
      || { echo "ios-smoke: FAIL $app — install failed (free-tier 3-app cap? locked?)"; FAILED=1; continue; }
  fi

  # Fresh launch: terminate a leftover instance so we test cold start.
  old_pid="$(pid_of "$scheme" || true)"
  [ -n "$old_pid" ] && xcrun devicectl device process terminate --device "$DEVICE" --pid "$old_pid" >/dev/null 2>&1 || true

  echo "ios-smoke: launching ${bundle}…"
  out="$(xcrun devicectl device process launch --device "$DEVICE" "$bundle" 2>&1)" || {
    case "$out" in
      *Locked*)   echo "ios-smoke: FAIL $app — the iPhone is locked; unlock it and rerun" ;;
      *trusted*|*integrity*) echo "ios-smoke: FAIL $app — developer profile not trusted / expired (Settings → VPN & Device Management; --build re-mints)" ;;
      *)          echo "ios-smoke: FAIL $app — launch failed:"; echo "$out" | tail -3 ;;
    esac
    # The store is never opened when the launch fails, so there is nothing to
    # compare — just don't leak the pre-install stamp on the way out.
    rm -f "${stamp_before:-}" 2>/dev/null || true
    stamp_before=""
    FAILED=1; continue
  }

  sleep "$ALIVE_AFTER_SECONDS"
  pid="$(pid_of "$scheme" || true)"
  if [ -n "$pid" ]; then
    echo "ios-smoke: PASS $app (pid $pid alive after ${ALIVE_AFTER_SECONDS}s)"
    xcrun devicectl device process terminate --device "$DEVICE" --pid "$pid" >/dev/null 2>&1 || true

    # The store opens during launch, so the wipe — if this build is behind on any
    # slot — has already happened by now. Compare, and fail loudly: a green gate
    # over a discarded store is the failure mode this exists to end.
    if [ -n "${stamp_before:-}" ]; then
      stamp_after="$(mktemp)"
      if ! read_store_stamp "$bundle" "$stamp_after"; then
        # ⚠⚠ Not the fresh-install skip: there WAS a stamp before the install, so
        # the store existed and something has to account for it. Silently passing
        # here would reproduce the very hole this guard closes — a green gate over
        # a store nobody checked.
        echo "ios-smoke: FAIL $app — the store stamp was readable before the install and is not now"
        echo "  (device locked mid-run, or the container is unreadable). The install may have"
        echo "  wiped the store; re-read it before trusting this build:"
        echo "    xcrun devicectl device copy from --device ${DEVICE} --domain-type appDataContainer \\"
        echo "      --domain-identifier ${bundle} --source Documents/objects/.serviceversions --destination ."
        FAILED=1
      else
        regressions="$(stamp_regressions "$stamp_before" "$stamp_after")"
        # ⚠⚠ Say so when the check RAN and found nothing. A guard that is silent on
        # success cannot be told apart from one that skipped — which is the whole
        # reason the wipe this guard exists for went unnoticed behind a green PASS.
        [ -z "$regressions" ] && echo "ios-smoke: store stamp verified for $app ($(wc -l < "$stamp_after" | tr -d ' ') entries, none lower)"
        if [ -n "$regressions" ]; then
          echo "ios-smoke: FAIL $app — the store was RE-STAMPED DOWN, which means it was WIPED:"
          echo "$regressions" | sed 's/^/  /'
          echo "  This build is behind the installed store on the version(s) above, so"
          echo "  StoreSchemaMigrator took the 'written by newer code' path and discarded"
          echo "  EVERY service's rows (docs/proposals/core-version-compatibility.md §1.6)."
          echo "  Recover: install a build that is behind on no slot — usually 'merge master'"
          echo "  — and let the realm backfill restore the data."
          FAILED=1
        fi
      fi
      rm -f "$stamp_after"
    fi
    rm -f "${stamp_before:-}" 2>/dev/null || true
    stamp_before=""
  else
    echo "ios-smoke: FAIL $app — process died within ${ALIVE_AFTER_SECONDS}s of launch (crash on startup)"
    FAILED=1
  fi
done

if [ "$FAILED" = 1 ]; then
  echo "ios-smoke: FAILED"
  exit 1
fi
echo "ios-smoke: all passed"
