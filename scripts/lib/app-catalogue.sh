# shellcheck shell=bash
#
# The one table of Tenshu first-party apps, for scripts that must act on ALL of
# them: reset-local-device-state.sh (wipe) and reinstall-all-apps.sh (build +
# install). Source it, don't execute it.
#
# WHY THIS EXISTS. Every "for each app" list in this repo has drifted at least
# once, and each drift is silent by construction — a missing app is not an error,
# it is an app the script never mentions. reset-local-device-state.sh knew two of
# the nine packages of the day for months, so a "full reset" left seven sandboxes
# standing and looked like it worked. calendar-ios shipped through eleven phases while
# ios-smoke.sh compiled the other eight (see that script's header).
#
# So: one row per app, and the row carries every platform's coordinates. Adding a
# first-party app means adding one line here, and both scripts pick it up.
#
# ⚠ ios-smoke.sh keeps its own `spec_for` table on purpose — it is the iOS gate,
# it predates this file, and rewiring a gate to prove a point about lists is how
# you break the gate. Its rows must agree with the iOS columns here;
# `app_catalogue_selftest` (below) asserts exactly that, so the two cannot drift
# without a script failing.
#
# ⚠ Bash 3.2 is what macOS ships, so this is a `case` statement and not an
# associative array.

# Every app key, in install order: Control first because it is the proxy every
# other app talks to.
TENSHU_APP_KEYS="control drive maps home calendar timeline mia photos fit contacts"

# key → android-package | gradle-module | desktop-module | desktop-task |
#       desktop-bundle | expected-nested-helpers | ios-dir | ios-scheme | ios-bundle
#
# ⚠ `expected-nested-helpers` is load-bearing, not trivia: a desktop bundle signed
# with 0 nested binaries when it should have 3 is the "tray is gone, every client
# hangs on a spinner" failure, and the count is the ONLY tell (see
# gradle/desktop-app-signing.gradle.kts and docs/tech-debt.md "A locally rebuilt
# desktop bundle is ad-hoc signed").
#
# ⚠ `desktop-task` is packageControlApp for Control alone — it adds the tray agent
# on top of packageDesktopApp. NEVER createDistributable: that produces an ad-hoc
# bundle with no Contents/Helpers/, which the desktop IPC gate silently refuses.
app_spec() {
  case "$1" in
    # ⚠ Every app's nestedHelpers counts `appgroup-claim`, embedded in all ten, and the two
    # `:macos-helpers` jar helpers `embedJarHelpers` copies into every bundle (`image-decode-helper`,
    # `map-snapshot-helper`; the notary rejected the unsigned jar copies, 2026-11-06). Hence 3 for
    # an app with no helpers of its own.
    # ⚠ Control: 6 = tray-agent, qr-scan-helper, image-decode-helper, map-snapshot-helper,
    # applock-helper (RA-5) and appgroup-claim. This count is asserted after install, so a
    # helper added without updating it here reports a FAIL on a correct build — which is how
    # a gate stops being read.
    control)  echo "app.tenshu.control.android|:control|:control-desktop|packageControlApp|TenshuControl.app|6|control-ios|ControlIos|app.tenshu.control.ios" ;;
    # ⚠ Drive: 5 = TenshuFileProvider.appex, qr-scan-helper, image-decode-helper,
    # map-snapshot-helper and appgroup-claim.
    drive)    echo "app.tenshu.drive.android|:drive-android|:drive-desktop|packageDesktopApp|TenshuDrive.app|5|drive-ios|DriveIos|app.tenshu.drive.ios" ;;
    maps)     echo "app.tenshu.maps.android|:maps-android|:maps-desktop|packageDesktopApp|TenshuMaps.app|3|maps-ios|MapsIos|app.tenshu.maps.ios" ;;
    home)     echo "app.tenshu.home.android|:home-android|:home-desktop|packageDesktopApp|TenshuHome.app|3|home-ios|HomeIos|app.tenshu.home.ios" ;;
    calendar) echo "app.tenshu.calendar.android|:calendar-android|:calendar-desktop|packageDesktopApp|TenshuCalendar.app|3|calendar-ios|CalendarIos|app.tenshu.calendar.ios" ;;
    timeline) echo "app.tenshu.timeline.android|:timeline-android|:timeline-desktop|packageDesktopApp|TenshuTimeline.app|3|timeline-ios|TimelineIos|app.tenshu.timeline.ios" ;;
    mia)      echo "app.tenshu.mia.android|:mia-android|:mia-desktop|packageDesktopApp|TenshuMia.app|3|mia-ios|MiaIos|app.tenshu.mia.ios" ;;
    # ⚠ A row whose iOS module exists names it rather than carrying `-`: the selftest fails once
    # ios-smoke.sh knows the app (`photos-ios.md`).
    photos)   echo "app.tenshu.photos.android|:photos-android|:photos-desktop|packageDesktopApp|TenshuPhotos.app|3|photos-ios|PhotosIos|app.tenshu.photos.ios" ;;
    fit)      echo "app.tenshu.fit.android|:fit-android|:fit-desktop|packageDesktopApp|TenshuFit.app|3|fit-ios|FitIos|app.tenshu.fit.ios" ;;
    contacts) echo "app.tenshu.contacts.android|:contacts-android|:contacts-desktop|packageDesktopApp|TenshuContacts.app|3|contacts-ios|ContactsIos|app.tenshu.contacts.ios" ;;
    *) echo "app-catalogue: unknown app '$1' (expected one of: ${TENSHU_APP_KEYS})" >&2; return 2 ;;
  esac
}

# Field accessor. `app_field drive androidPkg` → app.tenshu.drive.android
app_field() {
  local spec index
  spec="$(app_spec "$1")" || return 2
  case "$2" in
    androidPkg)     index=1 ;;
    androidModule)  index=2 ;;
    desktopModule)  index=3 ;;
    desktopTask)    index=4 ;;
    desktopBundle)  index=5 ;;
    nestedHelpers)  index=6 ;;
    iosDir)         index=7 ;;
    iosScheme)      index=8 ;;
    iosBundle)      index=9 ;;
    *) echo "app-catalogue: unknown field '$2'" >&2; return 2 ;;
  esac
  printf '%s\n' "$spec" | cut -d'|' -f"$index"
}

# Every Android package, space separated — the list reset/reinstall iterate.
android_packages() {
  local app
  for app in ${TENSHU_APP_KEYS}; do app_field "$app" androidPkg; done
}

# Does this app have an iOS target at all? Rows carry `-` in the three iOS columns
# until the module exists.
#
# ⚠⚠ Callers MUST ask before using iosBundle/iosScheme. `-` is not an inert value:
# `grep -qF -` matches every line of a devicectl listing, so an unguarded
# "is it already on the phone?" test reports YES for an app that has no iOS build,
# and `rm -rf build/ios-device/-` deletes a path nobody meant.
app_has_ios() {
  [ "$(app_field "$1" iosDir)" != "-" ]
}

# Every iOS bundle id — only for apps that HAVE an iOS target.
ios_bundles() {
  local app
  for app in ${TENSHU_APP_KEYS}; do
    app_has_ios "$app" || continue
    app_field "$app" iosBundle
  done
}

# Resolve the connected iPhone's devicectl identifier — the same extraction
# ios-smoke.sh uses, so all three scripts agree on which device they mean.
#
# ⚠⚠ Take the identifier by REGEX, never by awk column. `devicectl list devices`
# puts a human name in the first column ("Benedikt's iPhone"), so the field count
# shifts per device and `$(NF-2)` lands somewhere in the model name. It does not
# error — it returns a plausible-looking word, every lookup then finds no device,
# and a caller that reads "no device" as "nothing installed" goes on to do the
# wrong work: the iOS path would announce "nothing installed yet" about a phone holding three apps.
tenshu_ios_device() {
  if [ -n "${TENSHU_IOS_DEVICE:-}" ]; then printf '%s\n' "${TENSHU_IOS_DEVICE}"; return 0; fi
  # ⚠⚠ TWO UDID shapes, and the 8-4-4-4-12 one alone matches no real iPhone. A physical device since
  # the XS reports 8-16 (`00008150-0011085C1E85401C`) — measured against a live `devicectl list
  # devices`; only *simulators* use the 5-group UUID. With just that pattern the state filter passes,
  # the extraction yields nothing, and the caller reports "no connected iPhone" for a phone sitting
  # right there, which silently skips the iOS half of a full device reset.
  # ⚠⚠ Simulators are excluded by the `physical` column, NOT by the state filter: a BOOTED simulator
  # lists as `connected` (with the phone `unavailable`, a reinstall would build, install and launch
  # onto the simulator). Only the Reality column tells them apart.
  # ⚠ No bare 40-hex alternative for pre-XS devices: unanchored it would also match the first 40
  # characters of any longer hex token on the line and hand back a truncated id. `devicectl` does
  # not list those devices at all, so it buys nothing and can only mis-fire. Use TENSHU_IOS_DEVICE.
  # ⚠ `ios-smoke.sh` carries the same pattern deliberately (see its note) — change both together.
  xcrun devicectl list devices 2>/dev/null \
    | grep -E ' (connected|available)' | grep -v unavailable | grep -w physical \
    | grep -oE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}' \
    | head -1 || true
}

# How many devices the pattern above matches. ⚠⚠ `tenshu_ios_device` takes `head -1`, which is fine
# for a build/smoke target and NOT fine for a destructive one: with a phone and an iPad both
# connected, a wipe silently lands on whichever `devicectl` happens to list first — line order, not
# any preference of ours. Before the UDID fix this could not bite (the old pattern matched no
# physical device at all); now it can, so destructive callers must ask first. Honours
# TENSHU_IOS_DEVICE, which is the explicit way to choose.
tenshu_ios_device_count() {
  if [ -n "${TENSHU_IOS_DEVICE:-}" ]; then printf '1\n'; return 0; fi
  # ⚠ `-cE`, never `-coE`: `-c` combined with `-o` is implementation-defined — BSD grep counts matching
  # LINES, ugrep (which is first on PATH on this machine) counts MATCHES. A device row carrying a
  # second UDID-shaped token would then count as two, fire the multi-device refusal for a single
  # connected phone, and — since that refusal only warns — skip the iOS half while exiting 0.
  # "How many devices" means lines.
  xcrun devicectl list devices 2>/dev/null \
    | grep -E ' (connected|available)' | grep -v unavailable | grep -w physical \
    | grep -cE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}' \
    || true
}

# Assert this table agrees with ios-smoke.sh's own, and that every row is
# complete. Cheap enough to call at startup, which is the point: a table that is
# only checked by a test nobody runs is a table that drifts.
app_catalogue_selftest() {
  local repo_root smoke app fields dir scheme bundle rc=0
  repo_root="${1:?app_catalogue_selftest needs the repo root}"
  smoke="${repo_root}/scripts/ios-smoke.sh"

  for app in ${TENSHU_APP_KEYS}; do
    fields="$(app_spec "$app")" || return 2
    if [ "$(printf '%s\n' "$fields" | awk -F'|' '{print NF}')" -ne 9 ]; then
      echo "app-catalogue: row '$app' does not have 9 fields" >&2
      rc=1
      continue
    fi
    [ -n "$(app_field "$app" androidPkg)" ] || { echo "app-catalogue: '$app' has no android package" >&2; rc=1; }

    # The iOS columns must match ios-smoke.sh, or one of the two scripts is
    # acting on an app the other cannot see. Both directions are checked: a row
    # with coordinates must appear in `spec_for`, and a row marked `-` must NOT —
    # otherwise the sentinel would outlive the missing module in silence.
    if [ -f "$smoke" ]; then
      dir="$(app_field "$app" iosDir)"
      scheme="$(app_field "$app" iosScheme)"
      bundle="$(app_field "$app" iosBundle)"
      if app_has_ios "$app"; then
        if [ "$scheme" = "-" ] || [ "$bundle" = "-" ]; then
          echo "app-catalogue: '$app' has an iOS dir but a '-' scheme or bundle — fill the row in or mark all three '-'" >&2
          rc=1
        elif ! grep -qF "echo \"$dir $scheme $bundle\"" "$smoke"; then
          echo "app-catalogue: iOS row for '$app' ($dir $scheme $bundle) does not match ios-smoke.sh's spec_for" >&2
          rc=1
        fi
      else
        if [ "$scheme" != "-" ] || [ "$bundle" != "-" ]; then
          echo "app-catalogue: '$app' is marked as having no iOS target, so all three iOS columns must be '-'" >&2
          rc=1
        elif grep -qE "^[[:space:]]*${app}\)[[:space:]]*echo \"" "$smoke"; then
          echo "app-catalogue: '$app' is marked '-' here but ios-smoke.sh now has a spec_for row for it — fill the iOS columns in" >&2
          rc=1
        fi
      fi
    fi
  done
  return $rc
}
