#!/usr/bin/env bash
#
# Rebuild every Tenshu app from the current checkout and install it on every
# target: attached Android devices, /Applications, and the connected iPhone.
# The other half of scripts/reset-local-device-state.sh — that one wipes, this
# one puts the apps back, both reading scripts/lib/app-catalogue.sh so neither
# can quietly cover a subset.
#
#   ./scripts/reinstall-all-apps.sh                       # all platforms, all apps
#   ./scripts/reinstall-all-apps.sh --android --desktop   # skip iOS
#   ./scripts/reinstall-all-apps.sh --apps "control drive" # a subset
#   ./scripts/reinstall-all-apps.sh --serial emulator-5554 # one adb device
#   ./scripts/reinstall-all-apps.sh --ios --wipe-ios-identity   # after a relay wipe
#   ./scripts/reinstall-all-apps.sh --list                 # print the catalogue and exit
#
# Options:
#   --android|--desktop|--ios  pick platforms (default: all three)
#   --apps "<keys>"        restrict to these app keys (default: every app; iOS
#                          takes the first three, see the cap below)
#   --serial <s>           target one adb device (default: EVERY attached device —
#                          leaving one stale is how schema lockstep breaks)
#   --skip-build           install what is already built (no Gradle, no xcodebuild)
#   --wipe-ios-identity    after installing Control-iOS, run -wipeDeviceState to
#                          purge the Keychain identity that survives an uninstall,
#                          then relaunch clean. Needed after a relay wipe: Control
#                          otherwise polls with an old device key and gets 401.
#                          Off by default — it un-pairs a working device.
#   --no-launch-check      install only; skip "did it survive launch"
#   RESET_YES=1            skip the confirmation prompt
#
# WHAT IT KNOWS THAT YOU HAVE TO REMEMBER OTHERWISE:
#   * Android: assembling every app in ONE Gradle invocation OOMs the dexer
#     (D8 OutOfMemoryError at mergeExtDexDebug), so builds go out in batches.
#     Control installs first — it is the proxy every other app talks to.
#   * Desktop: packageDesktopApp / packageControlApp, NEVER createDistributable,
#     which yields an ad-hoc bundle the desktop IPC gate refuses — presenting as
#     a missing tray and an endless spinner. Verified here per bundle: a real
#     TeamIdentifier AND the expected nested-helper count.
#   * iOS: ios-smoke.sh --build deliberately does NOT assemble the XCFramework,
#     so going through it alone compiles against the PREVIOUS build's Kotlin.
#     This script assembles it first and clears the stale derived data that
#     otherwise keeps failing with "no XCFramework found" while it sits on disk.
#   * iOS free personal team fits at most THREE apps on a device at once.
#
# ⚠ A fresh install needs no pairing or admission: appRegistrationTrigger resolves
# clientId → appId through the compiled-in allowlist and registers the app itself.
# Device pairing and app registration are different things; don't warn about the
# former after doing the latter.

set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
# shellcheck source=scripts/lib/app-catalogue.sh
. scripts/lib/app-catalogue.sh

DO_ANDROID=0
DO_DESKTOP=0
DO_IOS=0
APPS=""
APPS_EXPLICIT=0
SERIAL=""
SKIP_BUILD=0
WIPE_IOS_IDENTITY=0
LAUNCH_CHECK=1
IOS_APP_CAP=3
GRADLE_BATCH=3
FAILURES=""

usage() { sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
fail()  { FAILURES="${FAILURES}
  - $1"; echo "   FAIL: $1" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --android) DO_ANDROID=1; shift ;;
    --desktop) DO_DESKTOP=1; shift ;;
    --ios)     DO_IOS=1; shift ;;
    --apps)    APPS="${2:?--apps needs a list}"; APPS_EXPLICIT=1; shift 2 ;;
    --serial)  SERIAL="${2:?--serial needs a device}"; shift 2 ;;
    --skip-build)        SKIP_BUILD=1; shift ;;
    --wipe-ios-identity) WIPE_IOS_IDENTITY=1; shift ;;
    --no-launch-check)   LAUNCH_CHECK=0; shift ;;
    --list)
      printf '%-9s %-30s %-20s %-22s %s\n' APP ANDROID-PACKAGE DESKTOP-TASK DESKTOP-BUNDLE IOS-SCHEME
      for a in ${TENSHU_APP_KEYS}; do
        printf '%-9s %-30s %-20s %-22s %s\n' "$a" \
          "$(app_field "$a" androidPkg)" "$(app_field "$a" desktopTask)" \
          "$(app_field "$a" desktopBundle)" "$(app_field "$a" iosScheme)"
      done
      exit 0 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

if [[ $DO_ANDROID -eq 0 && $DO_DESKTOP -eq 0 && $DO_IOS -eq 0 ]]; then
  DO_ANDROID=1; DO_DESKTOP=1; DO_IOS=1
fi
[[ -z "$APPS" ]] && APPS="${TENSHU_APP_KEYS}"

# Validate the app keys up front — a typo must not surface three minutes into a
# Gradle build.
app_catalogue_selftest "$PWD" || { echo "reinstall: app catalogue is inconsistent — fix it first" >&2; exit 2; }
for a in ${APPS}; do app_spec "$a" >/dev/null || exit 2; done

adb_devices() {
  if [[ -n "$SERIAL" ]]; then printf '%s\n' "$SERIAL"; return; fi
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}'
}

# Run one Gradle task per app, in batches — see the dexer note in the header.
gradle_batched() { # $1 = task name resolver fn, rest = app keys
  local resolve="$1"; shift
  local batch=() app rc=0
  for app in "$@"; do
    batch+=("$("$resolve" "$app")")
    if [[ ${#batch[@]} -ge $GRADLE_BATCH ]]; then
      echo "   ./gradlew ${batch[*]}"
      ./gradlew "${batch[@]}" >/dev/null || rc=1
      batch=()
    fi
  done
  if [[ ${#batch[@]} -gt 0 ]]; then
    echo "   ./gradlew ${batch[*]}"
    ./gradlew "${batch[@]}" >/dev/null || rc=1
  fi
  return $rc
}

android_task() { echo "$(app_field "$1" androidModule):assembleDebug"; }
desktop_task() { echo "$(app_field "$1" desktopModule):$(app_field "$1" desktopTask)"; }

echo "About to REBUILD and REINSTALL Tenshu from $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD)):"
[[ $DO_ANDROID -eq 1 ]] && echo "  android → $(echo "${APPS}" | wc -w | tr -d ' ') apps onto: $(adb_devices | tr '\n' ' ')"
[[ $DO_DESKTOP -eq 1 ]] && echo "  desktop → /Applications (the apps being replaced are quit, and reopened if they were running)"
[[ $DO_IOS     -eq 1 ]] && echo "  ios     → $([[ $APPS_EXPLICIT -eq 1 ]] && echo "${APPS}" || echo "whichever of ${IOS_APP_CAP} apps this device already carries")$([[ $WIPE_IOS_IDENTITY -eq 1 ]] && echo ' + identity wipe')"
if [[ "${RESET_YES:-0}" != "1" ]]; then
  read -r -p "Proceed? [y/N] " typed
  [[ "$typed" == "y" || "$typed" == "Y" ]] || { echo "Aborted." >&2; exit 1; }
fi

# ── Android ───────────────────────────────────────────────────────────────────
if [[ $DO_ANDROID -eq 1 ]]; then
  echo
  echo "==> Android …"
  if ! command -v adb >/dev/null 2>&1; then
    fail "adb not on PATH"
  elif [[ -z "$(adb_devices)" ]]; then
    fail "no adb device attached"
  else
    if [[ $SKIP_BUILD -eq 0 ]]; then
      # shellcheck disable=SC2086
      gradle_batched android_task ${APPS} || fail "android build failed"
    fi
    for serial in $(adb_devices); do
      echo "   --- $serial ---"
      for app in ${APPS}; do
        pkg="$(app_field "$app" androidPkg)"
        dir="$(app_field "$app" androidModule | tr -d ':')"
        apk="$(ls -1 "${dir}/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
        if [[ -z "$apk" ]]; then fail "$app: no APK built (${dir}/build/outputs/apk/debug/)"; continue; fi
        if adb -s "$serial" install -r "$apk" >/dev/null 2>&1; then
          echo "       installed $pkg"
        else
          fail "$app: adb install failed on $serial"
        fi
      done
      if [[ $LAUNCH_CHECK -eq 1 ]]; then
        # `Success` from adb install says nothing about startup: DI-wiring and
        # manifest regressions only show on launch.
        for app in ${APPS}; do
          pkg="$(app_field "$app" androidPkg)"
          adb -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
        done
        sleep 8
        for app in ${APPS}; do
          pkg="$(app_field "$app" androidPkg)"
          if [[ -n "$(adb -s "$serial" shell pidof "$pkg" 2>/dev/null | tr -d '\r')" ]]; then
            echo "       alive     $pkg"
          else
            fail "$app: not running after launch on $serial"
          fi
        done
      fi
    done
  fi
fi

# ── Desktop ───────────────────────────────────────────────────────────────────
if [[ $DO_DESKTOP -eq 1 ]]; then
  echo
  echo "==> Desktop …"
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "   not macOS — skipping."
  else
    if [[ $SKIP_BUILD -eq 0 ]]; then
      # shellcheck disable=SC2086
      gradle_batched desktop_task ${APPS} || fail "desktop packaging failed"
    fi
    # Quit first: copying over a running bundle leaves a process on deleted code,
    # and the tray agent keeps a stale binary alive.
    # ⚠⚠ ONLY the bundles being replaced, and what was running is put back below. This used
    # to be `pkill -f /Applications/Tenshu` whatever the subset, with Control reopened only
    # when it was in the set — so `--apps photos` took down Control, its tray and Drive and
    # brought none of them back (2026-10-13: "Control is no longer installed").
    was_running=""
    for app in ${APPS}; do
      bundle="$(app_field "$app" desktopBundle)"
      if pgrep -f "/Applications/${bundle}/Contents/MacOS/" >/dev/null 2>&1; then
        was_running="${was_running} ${app}"
      fi
      pkill -f "/Applications/${bundle}/" 2>/dev/null || true
    done
    sleep 2
    for app in ${APPS}; do
      bundle="$(app_field "$app" desktopBundle)"
      module="$(app_field "$app" desktopModule | tr -d ':')"
      src="${module}/build/compose/binaries/main/app/${bundle}"
      if [[ ! -d "$src" ]]; then fail "$app: no bundle at $src"; continue; fi
      rm -rf "/Applications/${bundle}"
      if ! cp -R "$src" /Applications/; then fail "$app: copy to /Applications failed"; continue; fi
      # LaunchServices can notice the bundle mid-copy, record it with no Info.plist, and never
      # look again: the app is on disk yet unknown by bundle id, so `open -b` and the tray's
      # launcher both miss it (seen on TenshuPhotos, 2026-10-11). Re-register once it is whole.
      /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
        -f "/Applications/${bundle}" || fail "$app: LaunchServices re-registration failed"

      team="$(codesign -dv "/Applications/${bundle}" 2>&1 | sed -n 's/^TeamIdentifier=//p')"
      nested=0
      for d in "/Applications/${bundle}/Contents/Helpers" "/Applications/${bundle}/Contents/PlugIns"; do
        [[ -d "$d" ]] && nested=$(( nested + $(ls -1 "$d" 2>/dev/null | wc -l | tr -d ' ') ))
      done
      want="$(app_field "$app" nestedHelpers)"
      if [[ -z "$team" || "$team" == "not set" ]]; then
        fail "$app: ${bundle} is ad-hoc signed (TeamIdentifier '${team:-absent}') — the IPC gate will refuse it"
      elif [[ "$nested" -ne "$want" ]]; then
        fail "$app: ${bundle} has ${nested} nested binaries, expected ${want} (helpers did not make it in)"
      else
        printf '       installed %-22s team=%s · %s nested\n' "$bundle" "$team" "$nested"
      fi
    done
    # Control first — it is the proxy the others talk to. Reopened when it was replaced and
    # either was running or is about to be checked; left alone when it was not in the set.
    if echo " ${APPS} " | grep -q " control " &&
       { [[ $LAUNCH_CHECK -eq 1 ]] || echo " ${was_running} " | grep -q " control "; }; then
      control_bundle="$(app_field control desktopBundle)"
      open -a "/Applications/${control_bundle}" 2>/dev/null || true
      if [[ $LAUNCH_CHECK -eq 1 ]]; then
        # ⚠ Polled, not one look after a fixed sleep: the tray agent has been seen to follow
        # Control by two minutes, and a single 12 s look reported a healthy install as failed.
        tray_alive=0
        for _ in $(seq 1 75); do
          if pgrep -f "/Applications/${control_bundle}/Contents/Helpers/tray-agent" >/dev/null 2>&1; then
            tray_alive=1
            break
          fi
          sleep 2
        done
        if [[ $tray_alive -eq 1 ]]; then
          echo "       alive     Control + tray-agent (IPC signature gate accepted the bundle)"
        else
          fail "control: desktop Control or its tray-agent is not running — check the signature gate"
        fi
      fi
    fi
    # Then every other app this run quit. An install that silently closes what the user had
    # open is the half of the old behaviour nobody asked for.
    for app in ${was_running}; do
      [[ "$app" == "control" ]] && continue
      bundle="$(app_field "$app" desktopBundle)"
      [[ -d "/Applications/${bundle}" ]] || continue
      if open -a "/Applications/${bundle}" 2>/dev/null; then
        echo "       reopened  ${bundle}"
      else
        fail "$app: was running before the install and could not be reopened"
      fi
    done
  fi
fi

# ── iOS ───────────────────────────────────────────────────────────────────────
if [[ $DO_IOS -eq 1 ]]; then
  echo
  echo "==> iOS …"
  ios_device="$(tenshu_ios_device)"
  ios_apps=""

  # ⚠ The cap makes "all apps" meaningless on iOS, and picking the first three of
  # the catalogue is actively wrong: it reinstalled control/drive/maps onto a phone
  # carrying control/drive/FIT, then failed launching an app that was never there.
  # So when the caller did not name a trio, take the apps the DEVICE already has —
  # "reinstall what is on this phone" is what the flag-less form means.
  if [[ $APPS_EXPLICIT -eq 0 ]]; then
    if [[ -z "$ios_device" ]]; then
      fail "iOS: no connected iPhone — plug in, unlock, trust (ios-signing.md §3), or name apps with --apps"
    elif ! installed="$(xcrun devicectl device info apps --device "$ios_device" 2>/dev/null)"; then
      # ⚠ A failed enumeration is NOT an empty device. Guessing a trio here is how
      # you install onto a phone the apps it never had.
      fail "iOS: could not enumerate installed apps on ${ios_device} — not choosing a trio by guesswork"
    else
      for app in ${APPS}; do
        # ⚠ Ask the catalogue first. An app with no iOS module carries `-` as its
        # bundle id, and `grep -qF -` matches every line of the listing — so an
        # unguarded test here would "find" Photos on the phone and then try to
        # build a scheme that does not exist.
        app_has_ios "$app" || continue
        if printf '%s\n' "$installed" | grep -qF "$(app_field "$app" iosBundle)"; then
          ios_apps="${ios_apps} ${app}"
        fi
      done
      if [[ -n "$ios_apps" ]]; then
        echo "   already on the device, reinstalling those:${ios_apps}"
      else
        count=0
        for app in ${APPS}; do
          app_has_ios "$app" || continue
          count=$(( count + 1 ))
          [[ $count -le $IOS_APP_CAP ]] && ios_apps="${ios_apps} ${app}"
        done
        echo "   device carries no Tenshu app yet — the free personal team fits ${IOS_APP_CAP}, taking:${ios_apps}"
      fi
    fi
  fi

  # An explicit --apps list is taken as given: the caller chose the trio. Apps with
  # no iOS module are dropped OUT LOUD — passing one to ios-smoke.sh is a hard exit
  # ("unknown app"), which would take the whole iOS pass down with it.
  if [[ $APPS_EXPLICIT -eq 1 ]]; then
    ios_apps=""
    for app in ${APPS}; do
      if app_has_ios "$app"; then
        ios_apps="${ios_apps} ${app}"
      else
        echo "   skipping ${app} on iOS: no iOS module (catalogue row says '-')"
      fi
    done
  fi

  # Over-cap is a hard stop, not a truncation: silently dropping apps from an
  # explicit list is how you believe you reinstalled something you did not.
  if [[ "$(echo "${ios_apps}" | wc -w | tr -d ' ')" -gt $IOS_APP_CAP ]]; then
    fail "iOS: ${IOS_APP_CAP} apps fit on a free-team device, but ${ios_apps} were requested — name a trio with --apps"
    ios_apps=""
  fi

  if [[ -z "$ios_apps" ]]; then
    echo "   nothing to do on iOS."
  else

  if [[ $SKIP_BUILD -eq 0 ]]; then
    # Nothing to assemble first: each app's Xcode prebuild links its own Kotlin framework
    # (`scripts/ios-build-sdk.sh <app>`, ios-per-app-frameworks.md). Fresh derived data still,
    # so no app relinks against a stale framework (P2's "stale derived data" note).
    for app in ${ios_apps}; do rm -rf "build/ios-device/$(app_field "$app" iosScheme)"; done
    echo "   cleared stale derived data for:${ios_apps}"
  fi

  if [[ -z "${TENSHU_TEAM_ID:-}" && -f local.properties ]]; then
    TENSHU_TEAM_ID="$(sed -n 's/^tenshu\.teamId=//p' local.properties | head -1)"
    export TENSHU_TEAM_ID
  fi
  # shellcheck disable=SC2086
  if [[ $SKIP_BUILD -eq 0 ]]; then
    scripts/ios-smoke.sh --build ${ios_apps} || fail "ios-smoke.sh reported a failure (see its output; a launch refusal right after uninstalling every app of this developer is the DEVICE TRUST record, re-Trust in Settings → General → VPN & Device Management)"
  else
    scripts/ios-smoke.sh ${ios_apps} || fail "ios-smoke.sh reported a failure"
  fi

  if [[ $WIPE_IOS_IDENTITY -eq 1 ]] && echo " ${ios_apps} " | grep -q " control "; then
    device="$ios_device"
    bundle="$(app_field control iosBundle)"
    if [[ -z "$device" ]]; then
      fail "identity wipe: no connected device found"
    else
      echo "   -wipeDeviceState on ${bundle} …"
      # ⚠ devicectl parses a bare -flag as its OWN option; the `--` is required.
      xcrun devicectl device process launch --terminate-existing --device "$device" "$bundle" -- -wipeDeviceState >/dev/null 2>&1 \
        || fail "identity wipe: launch with -wipeDeviceState failed"
      sleep 8
      # That launch parks on "Device state wiped" for the process lifetime (the
      # flag rides argv), so it must be relaunched WITHOUT the flag.
      xcrun devicectl device process launch --terminate-existing --device "$device" "$bundle" >/dev/null 2>&1 \
        || fail "identity wipe: clean relaunch failed"
      echo "   identity wiped and Control relaunched clean."
    fi
  fi

  fi   # ios_apps non-empty
fi

echo
if [[ -n "$FAILURES" ]]; then
  echo "Reinstall finished WITH FAILURES:${FAILURES}"
  exit 1
fi
echo "Reinstall complete — every requested app built, installed and verified."
