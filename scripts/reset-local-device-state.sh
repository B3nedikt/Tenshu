#!/usr/bin/env bash
# Wipe the LOCAL Tenshu device state so this machine (and any attached Android
# device or connected iPhone) re-onboards from scratch — the client half of a full
# "test against new data" reset. Run AFTER wiping the server
# (deploy/reset-test-data.sh); the device bootstraps a fresh genesis realm against
# the empty relay. Put the apps back with scripts/reinstall-all-apps.sh.
#
#   ./scripts/reset-local-device-state.sh                 # desktop + every attached Android
#   ./scripts/reset-local-device-state.sh --desktop       # this machine only
#   ./scripts/reset-local-device-state.sh --android       # attached devices only
#   ./scripts/reset-local-device-state.sh --android --uninstall  # also remove the apps
#   ./scripts/reset-local-device-state.sh --ios           # the connected iPhone
#   ./scripts/reset-local-device-state.sh --all           # desktop + android + ios
#   ./scripts/reset-local-device-state.sh --hub pi@hub    # the headless hub, over ssh
#
# Options:
#   --desktop / --android / --ios   pick planes (no flag => desktop + android)
#   --all                  all three
#   --uninstall            Android: remove the apps instead of clearing their data
#   --remove-apps          Desktop: also delete /Applications/Tenshu*.app
#   --hub <ssh-target>     Hub: stop tenshu-hub and empty its data dir (objects.db, secrets.enc,
#                          wrap.key, content/) over ssh. Not part of --all — it needs a target.
#   --hub-data-dir <dir>   Hub: the data dir (default /var/lib/tenshu-hub, the unit's).
#   --serial <s>           Android: one device (default: EVERY attached device —
#                          leaving one stale is how schema lockstep breaks)
#   RESET_YES=1            skip the prompt
#   TENSHU_IOS_DEVICE=<udid>  iOS: name the device explicitly. ⚠ REQUIRED when more than one
#                          Apple device is connected — with a phone AND an iPad attached this
#                          refuses to guess which to wipe and skips the iOS plane entirely, so a
#                          run can otherwise look successful having reset nothing on iOS.
#
# DESKTOP (macOS/Linux): quits any running Tenshu app first (copying or deleting
# under a live Control leaves a half-state), then deletes the Control/Drive data
# tree (DesktopAppPaths.root(): $TENSHU_DATA_DIR, else the macOS App Group
# container ~/Library/Group Containers/<TEAMID>.app.tenshu.shared, else
# ~/.tenshu) — objects.db, local-store.properties, secrets.enc. On macOS it first
# removes Drive's Finder (File Provider) domain, whose item list the system keeps
# outside all of those. On macOS it ALSO
# deletes the login-Keychain wrapping key (service
# app.tenshu.control.desktop.master-key-store, account master-wrap-key); the
# encrypted secrets.enc is useless without it, and leaving one behind is the
# classic broken-half-state. Also on macOS: boots out and deletes Control's
# LaunchAgents (~/Library/LaunchAgents/app.tenshu.*.plist — the daemon and the
# tray; launchd would otherwise restart Control mid-wipe, and re-arm it at the
# next login against a store that no longer exists), and asks the bundled
# keychain helper to clear the Touch ID unlock item, which lives in the
# data-protection keychain where `security` cannot reach it.
#
# HUB (--hub): stops the service and empties its data dir, including wrap.key.
# It stays STOPPED and DISABLED: an unpaired hub has nothing to run. Redeploy
# the jar from the reset-era checkout, re-pair it, then `systemctl enable --now
# tenshu-hub` (docs/how-to/hub-pi-deploy.md §1–§4).
#
# ANDROID: `adb shell pm clear` on ALL first-party packages (the catalogue in
# scripts/lib/app-catalogue.sh — this used to be Control and Drive only, so a
# "full reset" left seven sandboxes standing). --uninstall removes them instead.
#
# ⚠⚠ `pm clear` is BLOCKED on ColorOS and some other OEM skins: it returns
# success and clears nothing, so the reset silently does nothing and the next run
# starts from dirty state. This script now VERIFIES the clear through `run-as` and
# tells you to rerun with --uninstall when data survived. Found during Timeline's
# on-device capture pass (timeline-movement-capture.md §11).
#
# IOS: uninstalls the installed first-party apps, which drops each app's own
# container (the shared App-Group replica goes only with the last app of the
# group). ⚠⚠ It is NOT a full wipe on its own — iOS Keychain
# items survive an app delete, so Control comes back bound to the OLD device key
# and gets 401 against a wiped relay. Purge that after reinstalling, with
# `reinstall-all-apps.sh --ios --wipe-ios-identity` (the wipe route needs the app
# installed, so it cannot live here). The guaranteed floor is still Erase All
# Content & Settings. See docs/how-to/reset-and-commerce-test.md §4b and §9.
#
# This is destructive but purely local — it un-pairs this device; nothing on the
# server.

set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
# shellcheck source=scripts/lib/app-catalogue.sh
. scripts/lib/app-catalogue.sh

DO_DESKTOP=0
DO_ANDROID=0
DO_IOS=0
UNINSTALL=0
REMOVE_APPS=0
SERIAL=""
HUB_TARGET=""
HUB_DATA_DIR="/var/lib/tenshu-hub"
WARNED=""

KEYCHAIN_SERVICE="app.tenshu.control.desktop.master-key-store"
KEYCHAIN_ACCOUNT="master-wrap-key"

# ⚠ The header is the comment block from line 2 to the first non-comment line. A hard-coded range
# (2,58) once spilled `set -euo pipefail`, the `cd` and the `.` source line into `--help`.
usage() { awk 'NR==1{next} /^#/{print; next} {exit}' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
warn()  { WARNED="${WARNED}
  - $1"; echo "   ⚠ $1" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --desktop)     DO_DESKTOP=1; shift ;;
    --android)     DO_ANDROID=1; shift ;;
    --ios)         DO_IOS=1; shift ;;
    --all)         DO_DESKTOP=1; DO_ANDROID=1; DO_IOS=1; shift ;;
    --uninstall)   UNINSTALL=1; shift ;;
    --remove-apps) REMOVE_APPS=1; shift ;;
    --serial)      SERIAL="${2:?--serial needs a device}"; shift 2 ;;
    --hub)         HUB_TARGET="${2:?--hub needs an ssh target}"; shift 2 ;;
    --hub-data-dir) HUB_DATA_DIR="${2:?--hub-data-dir needs a path}"; shift 2 ;;
    -h|--help)     usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done
# No plane flag => desktop + android, as before. (`--hub` alone means the hub alone.)
if [[ $DO_DESKTOP -eq 0 && $DO_ANDROID -eq 0 && $DO_IOS -eq 0 && -z "$HUB_TARGET" ]]; then DO_DESKTOP=1; DO_ANDROID=1; fi
case "$HUB_DATA_DIR" in
  /*) ;;
  *) echo "--hub-data-dir must be absolute, got '$HUB_DATA_DIR'" >&2; exit 1 ;;
esac
case "${HUB_DATA_DIR%/}" in
  ""|/|/var|/var/lib|/home|/root|/opt) echo "--hub-data-dir '$HUB_DATA_DIR' is not a hub data dir — refusing." >&2; exit 1 ;;
esac

app_catalogue_selftest "$PWD" || { echo "reset: app catalogue is inconsistent — fix it first" >&2; exit 2; }

adb_devices() {
  if [[ -n "$SERIAL" ]]; then printf '%s\n' "$SERIAL"; return; fi
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}'
}

# Resolve the desktop data roots to delete (there may be more than one candidate).
desktop_roots() {
  local roots=()
  [[ -n "${TENSHU_DATA_DIR:-}" && -e "${TENSHU_DATA_DIR}" ]] && roots+=("${TENSHU_DATA_DIR}")
  if [[ "$(uname -s)" == "Darwin" ]]; then
    while IFS= read -r d; do [[ -n "$d" ]] && roots+=("$d"); done \
      < <(find "$HOME/Library/Group Containers" -maxdepth 1 -type d -name '*.app.tenshu.shared' 2>/dev/null || true)
  fi
  [[ -e "$HOME/.tenshu" ]] && roots+=("$HOME/.tenshu")
  [[ ${#roots[@]} -gt 0 ]] && printf '%s\n' "${roots[@]}"
}

# Portable (bash 3.2) read of desktop_roots into the global ROOTS array; handles
# the space in "Group Containers" paths.
load_roots() {
  ROOTS=()
  while IFS= read -r line; do [[ -n "$line" ]] && ROOTS+=("$line"); done < <(desktop_roots)
}

echo "About to wipe LOCAL device state:"
if [[ $DO_DESKTOP -eq 1 ]]; then
  load_roots
  if [[ ${#ROOTS[@]} -eq 0 ]]; then
    echo "  desktop  → (no data dir found — already clean)"
  else
    for r in "${ROOTS[@]}"; do echo "  desktop  → rm -rf ${r}"; done
  fi
  [[ "$(uname -s)" == "Darwin" ]] && echo "  desktop  → delete Keychain item ${KEYCHAIN_SERVICE}/${KEYCHAIN_ACCOUNT} + the Touch ID unlock item"
  [[ "$(uname -s)" == "Darwin" ]] && echo "  desktop  → boot out + delete ~/Library/LaunchAgents/app.tenshu.*.plist"
  [[ $REMOVE_APPS -eq 1 ]] && echo "  desktop  → rm -rf /Applications/Tenshu*.app"
fi
if [[ $DO_ANDROID -eq 1 ]]; then
  echo "  android  → $([[ $UNINSTALL -eq 1 ]] && echo 'adb uninstall' || echo 'adb shell pm clear') $(android_packages | wc -l | tr -d ' ') packages on: $(adb_devices | tr '\n' ' ')"
fi
if [[ $DO_IOS -eq 1 ]]; then
  echo "  ios      → devicectl uninstall the installed first-party apps"
fi
if [[ -n "$HUB_TARGET" ]]; then
  echo "  hub      → ${HUB_TARGET}: stop tenshu-hub, empty ${HUB_DATA_DIR} (incl. wrap.key); left STOPPED"
fi
echo "This un-pairs this device. It cannot be undone (you'll re-onboard fresh)."

if [[ "${RESET_YES:-0}" != "1" ]]; then
  read -r -p "Type 'reset' to confirm: " typed
  [[ "$typed" == "reset" ]] || { echo "Aborted." >&2; exit 1; }
fi

if [[ $DO_DESKTOP -eq 1 ]]; then
  echo
  echo "==> Desktop …"
  # ⚠ LaunchAgents FIRST: the daemon and tray agents are launchd jobs, so killing the processes below
  # without booting the jobs out lets launchd start Control again in the middle of the wipe.
  if [[ "$(uname -s)" == "Darwin" ]]; then
    for plist in "$HOME"/Library/LaunchAgents/app.tenshu.*.plist; do
      [[ -e "$plist" ]] || continue
      label="$(basename "$plist" .plist)"
      launchctl bootout "gui/$(id -u)/${label}" >/dev/null 2>&1 && echo "   booted out ${label}"
      rm -f "$plist" && echo "   removed ${plist}"
    done
  fi
  if pgrep -f "/Applications/Tenshu" >/dev/null 2>&1; then
    pkill -f "/Applications/Tenshu" 2>/dev/null || true
    sleep 2
    echo "   quit running Tenshu apps."
  fi
  # ⚠⚠ The Finder (File Provider) domain keeps its item list in the system's own database, not
  # under any directory removed below — so without this, Finder goes on listing the files of the
  # realm that was just wiped, and opening one asks the relay for content it never had. Only a
  # process TenshuDrive.app is RESPONSIBLE for may remove the domain (the bundled helper refuses a
  # shell), hence `open` rather than running anything directly.
  # ⚠ A Drive from before the flag ignores it and simply starts, so the wait is bounded and the
  # result is read off the mount, never off the exit status.
  if [[ "$(uname -s)" == "Darwin" ]]; then
    drive_bundle="/Applications/$(app_field drive desktopBundle)"
    if compgen -G "${HOME}/Library/CloudStorage/TenshuDrive-*" >/dev/null && [[ -d "$drive_bundle" ]]; then
      open -W -n -a "$drive_bundle" --args --remove-finder-domain >/dev/null 2>&1 &
      open_pid=$!
      for _ in $(seq 1 45); do kill -0 "$open_pid" 2>/dev/null || break; sleep 1; done
      pkill -f "$drive_bundle" 2>/dev/null || true
      sleep 1
    fi
    if compgen -G "${HOME}/Library/CloudStorage/TenshuDrive-*" >/dev/null; then
      warn "the Finder domain is STILL registered, so Finder keeps showing the old realm's files — it needs a TenshuDrive.app that knows --remove-finder-domain; reinstall, then rerun"
    else
      echo "   Finder domain removed (or none was registered)."
    fi
  fi
  load_roots
  # ⚠⚠ Guard the expansion, do not just guard the message. Under `set -u`, bash 3.2 — which is
  # /bin/bash on macOS — treats "${ROOTS[@]}" on an EMPTY array as an unbound variable and aborts,
  # so an already-clean desktop killed the whole run here before Android and iOS were ever reached.
  # The empty case is the *common* one on a re-run, and it took the two planes that still had state
  # with it. ⚠ Line ~120 already guards the summary this way; this loop was the one that did not.
  if [[ ${#ROOTS[@]} -eq 0 ]]; then
    echo "   no data dir to remove."
  else
    for r in "${ROOTS[@]}"; do
      echo "   rm -rf ${r}"
      rm -rf "${r}"
    done
  fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    if security delete-generic-password -s "${KEYCHAIN_SERVICE}" -a "${KEYCHAIN_ACCOUNT}" >/dev/null 2>&1; then
      echo "   deleted Keychain wrapping key."
    else
      echo "   no Keychain wrapping key found (already clean)."
    fi
    # The Touch ID / login-password unlock item (app.tenshu.control.biometric-unlock) is in the
    # data-protection keychain, which `security` cannot reach — only the entitled helper bundled in
    # Control can. It wraps a key for a store that no longer exists, so leaving it is a stale half.
    helper="$(compgen -G "/Applications/$(app_field control desktopBundle)/Contents/Helpers/keychain-helper.app/Contents/MacOS/*" | head -1 || true)"
    if [[ -n "$helper" && -x "$helper" ]]; then
      # ⚠ `clear` answers "ok" unconditionally (SecItemDelete's status is ignored), so this is a request,
      # not a proof. Anything else means the helper did not even run.
      if [[ "$("$helper" clear 2>/dev/null)" == "ok" ]]; then
        echo "   asked the keychain helper to clear the Touch ID unlock item (it cannot report a failure)."
      else
        warn "the keychain helper did not run — if Touch ID unlock was on, turn it off and on in Control after re-onboarding"
      fi
    else
      echo "   no bundled keychain helper (Touch ID unlock was never available to this build) — nothing to clear."
    fi
  fi
  if [[ $REMOVE_APPS -eq 1 ]]; then
    for app in ${TENSHU_APP_KEYS}; do
      bundle="$(app_field "$app" desktopBundle)"
      [[ -d "/Applications/${bundle}" ]] && rm -rf "/Applications/${bundle}" && echo "   removed /Applications/${bundle}"
    done
  fi
fi

if [[ $DO_ANDROID -eq 1 ]]; then
  echo
  echo "==> Android …"
  if ! command -v adb >/dev/null 2>&1; then
    echo "   adb not found on PATH — skipping. (Install platform-tools or add to PATH.)"
  elif [[ -z "$(adb_devices)" ]]; then
    echo "   no adb device attached — skipping."
  else
    for serial in $(adb_devices); do
      echo "   --- ${serial} ($(adb -s "$serial" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')) ---"
      for pkg in $(android_packages); do
        if [[ $UNINSTALL -eq 1 ]]; then
          adb -s "$serial" uninstall "${pkg}" >/dev/null 2>&1 && echo "       uninstalled ${pkg}" \
            || echo "       ${pkg} not installed."
          continue
        fi
        if ! adb -s "$serial" shell pm clear "${pkg}" >/dev/null 2>&1; then
          echo "       ${pkg} not installed."
          continue
        fi
        # ⚠⚠ Exit status is NOT the discriminator here: an OEM-blocked `pm clear`
        # reports success and clears nothing. Ask the sandbox itself. `run-as`
        # works because these are debug builds; when it cannot answer we say so
        # rather than claim a clean wipe.
        #
        # ⚠ The whole remote command must be ONE quoted argument. Written as
        # `adb shell run-as $pkg sh -c 'ls -A files …'` the local shell eats the
        # quotes, the remote `sh -c` gets `ls` alone with the paths as $0/$1, and it
        # lists the data-dir ROOT instead — which still holds cache/ and files/
        # after a perfectly good clear. That reads as "clear was blocked" on every
        # device, i.e. the guard fires always and therefore means nothing.
        # Verified 2026-08-17: correct form prints objects.db before a clear and
        # NOTHING after one.
        residue="$(adb -s "$serial" shell "run-as ${pkg} sh -c 'ls -A files databases shared_prefs 2>/dev/null | head -5'" 2>&1 | tr -d '\r')"
        trimmed="$(printf '%s' "$residue" | tr -d '[:space:]')"
        case "$residue" in
          *"run-as:"*|*"not debuggable"*|*"is unknown"*)
            echo "       cleared ${pkg} (unverified — run-as declined)"
            warn "could not verify the clear of ${pkg} on ${serial}; if this is ColorOS/OneUI, rerun with --uninstall" ;;
          *)
            if [[ -z "$trimmed" ]]; then
              echo "       cleared ${pkg} (verified empty)"
            else
              warn "pm clear left data behind for ${pkg} on ${serial} — OEM-blocked clear. Rerun with --uninstall."
            fi ;;
        esac
      done
    done
  fi
fi

if [[ $DO_IOS -eq 1 ]]; then
  echo
  echo "==> iOS …"
  if ! xcrun devicectl list devices >/dev/null 2>&1; then
    echo "   devicectl unavailable — skipping."
  else
    device="$(tenshu_ios_device)"
    ios_count="$(tenshu_ios_device_count)"
    if [[ -z "$device" ]]; then
      echo "   no connected iPhone — skipping."
    elif [[ "${ios_count:-0}" -gt 1 ]]; then
      # ⚠⚠ Ambiguous target on a DESTRUCTIVE plane. `tenshu_ios_device` resolves with `head -1`, so
      # with a phone and an iPad both connected this would uninstall every Tenshu app from whichever
      # devicectl listed first — and the line below prints only a UDID, so the operator could not
      # tell which one it had picked. Name them and make the choice explicit.
      warn "iOS: ${ios_count} devices are connected — refusing to guess which one to wipe."
      echo "       Re-run with TENSHU_IOS_DEVICE=<udid>, or disconnect the others. Connected:" >&2
      xcrun devicectl list devices 2>/dev/null \
        | grep -E ' (connected|available)' | grep -v unavailable | sed 's/^/         /' >&2 || true
    else
      echo "   device ${device}"
      # Name it too — a UDID alone does not tell you which phone is about to be wiped.
      xcrun devicectl list devices 2>/dev/null | grep -F "$device" | sed 's/^/   /' || true
      # ⚠⚠ Report what happened per bundle. This loop swallowed every failure — app absent, device
      # locked, trust revoked, devicectl error — printed nothing, and the run still ended "Local
      # reset complete." A locked phone mid-run removed nothing and looked identical to success.
      # ⚠ "not installed" is the ordinary case and not a failure; anything else is, and the run must
      # say so. Android verifies its clear through `run-as`; this plane had no check at all, and it
      # is the one that was dead until the UDID fix above unblocked it.
      # ⚠⚠ `devicectl device uninstall app` prints "App uninstalled." and exits 0 for a bundle that
      # was NEVER INSTALLED — measured against a connected iPhone with a made-up id. So exit status
      # cannot tell removal from a no-op: the `not installed` branch below was unreachable, and since
      # a free personal team fits three apps while `ios_bundles` lists nine, a normal run printed six
      # "uninstalled" lines for apps that were never there. That is the same shape as the bug this
      # block was written to fix — a plane that reports success without checking.
      # So: ask what is installed, act only on those, and CONFIRM by re-listing afterwards.
      ios_before="$(xcrun devicectl device info apps --device "$device" 2>/dev/null || true)"
      if [[ -z "$ios_before" ]]; then
        warn "iOS: could not list installed apps — cannot tell what was removed; treating as NOT reset."
        # ⚠ NOT ios_failed=1: nothing was attempted here, so there is no count. Claiming "1 app(s)
        # were NOT removed" invents a number (the truth is "unknown, up to nine") and contradicts
        # the warning immediately above it.
        ios_unknown=1
      else
        ios_failed=0
        ios_targets=""
        for bundle in $(ios_bundles); do
          # ⚠ Anchored on field boundaries, not a bare substring. The nine ids happen not to prefix
          # one another today, but the SAME match drives the "STILL INSTALLED" proof below, so a
          # bundle id appearing anywhere else in the listing would both add a target and defeat its
          # own verification.
          if printf '%s' "$ios_before" | grep -qE "(^|[[:space:]])$(printf '%s' "$bundle" | sed 's/\./\\./g')([[:space:]]|$)"; then
            ios_targets="${ios_targets} ${bundle}"
          else
            echo "       ${bundle} not installed."
          fi
        done
        for bundle in $ios_targets; do
          out="$(xcrun devicectl device uninstall app --device "$device" "$bundle" 2>&1)" || {
            ios_failed=$((ios_failed + 1))
            warn "iOS: uninstall FAILED for ${bundle}: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-140)"
          }
        done
        # The proof, not the exit status. Android verifies its clear through `run-as`; this is the
        # iOS equivalent, and without it "uninstalled" is just an echo.
        if [[ -n "$ios_targets" ]]; then
          ios_after="$(xcrun devicectl device info apps --device "$device" 2>/dev/null || true)"
          if [[ -z "$ios_after" ]]; then
            warn "iOS: could not re-list apps after uninstalling — removal is UNVERIFIED."
            ios_failed=$((ios_failed + 1))
          else
            for bundle in $ios_targets; do
              if printf '%s' "$ios_after" | grep -qE "(^|[[:space:]])$(printf '%s' "$bundle" | sed 's/\./\\./g')([[:space:]]|$)"; then
                ios_failed=$((ios_failed + 1))
                warn "iOS: ${bundle} is STILL INSTALLED after uninstall."
              else
                echo "       uninstalled ${bundle} (verified gone)"
              fi
            done
          fi
        fi
      fi
      [[ ${ios_failed:-0} -gt 0 ]] && warn "iOS: ${ios_failed} app(s) were NOT removed — this phone is not reset."
      [[ ${ios_unknown:-0} -eq 1 ]] && warn "iOS: nothing was uninstalled and the app list is unknown — this phone is not reset."
      warn "iOS Keychain identity SURVIVES an uninstall — after reinstalling, run: scripts/reinstall-all-apps.sh --ios --wipe-ios-identity (else Control 401s against a wiped relay)"
      warn "uninstalling every app of this developer also drops the device's TRUST record — re-Trust in Settings → General → VPN & Device Management before the apps will launch"
    fi
  fi
fi

if [[ -n "$HUB_TARGET" ]]; then
  echo
  echo "==> Hub (${HUB_TARGET}) …"
  # ⚠ Quoted heredoc + positional args: nothing in the body is expanded locally.
  if ssh "$HUB_TARGET" bash -s -- "$HUB_DATA_DIR" <<'REMOTE'
set -eu
dir="$1"
SUDO=""
if [ "$(id -u)" != 0 ]; then
  SUDO="sudo -n"
  # Ask once, up front: a failing `sudo -n` must not read as "the unit was not running".
  if ! sudo -n true 2>/dev/null; then
    echo "   this needs root: ssh in as root, or give this user passwordless sudo" >&2
    exit 3
  fi
fi
if $SUDO systemctl cat tenshu-hub >/dev/null 2>&1; then
  $SUDO systemctl stop tenshu-hub
  # Disabled too, so a reboot before the re-pair does not start an unpaired hub. The re-pair's
  # `systemctl enable --now tenshu-hub` (hub-pi-deploy.md §4) turns it back on.
  $SUDO systemctl disable tenshu-hub >/dev/null 2>&1 || true
  echo "   tenshu-hub stopped and disabled"
else
  echo "   (no tenshu-hub unit on this host)"
fi
if [ ! -d "$dir" ]; then echo "   no $dir on this host" >&2; exit 2; fi
$SUDO find "$dir" -mindepth 1 -delete
left="$($SUDO find "$dir" -mindepth 1 | wc -l | tr -d ' ')"
if [ "$left" != "0" ]; then echo "   $left entries survived under $dir" >&2; exit 1; fi
echo "   emptied $dir (verified) — tenshu-hub is STOPPED and DISABLED"
REMOTE
  then
    warn "hub: redeploy the jar from THIS checkout and re-pair, then 'systemctl enable --now tenshu-hub' — ./gradlew :hub:distTar, docs/how-to/hub-pi-deploy.md §2–§4 (the hub is a schema peer; an old jar is fenced)"
  else
    warn "hub: the wipe on ${HUB_TARGET} did NOT complete — the hub is not reset"
  fi
fi

echo
if [[ -n "$WARNED" ]]; then
  echo "Local reset finished, with things to know:${WARNED}"
else
  echo "Local reset complete."
fi
echo "Not covered here: web storage. Clear site data (IndexedDB + localStorage) for tenshu.app and"
echo "tenshu.xyz, and their subdomains, in EVERY browser profile that ever opened them."
echo "Re-onboard as a brand-new user against the empty relay — or put the apps back first:"
echo "  ./scripts/reinstall-all-apps.sh"
