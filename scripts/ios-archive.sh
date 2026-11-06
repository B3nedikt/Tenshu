#!/usr/bin/env bash
# Archive an iOS app for App Store Connect and export the .ipa — or upload it to TestFlight.
# Runbook, prerequisites and what each check is for: docs/how-to/ios-testflight.md.
#
#   scripts/ios-archive.sh control drive            # archive + export a signed .ipa, upload nothing
#   scripts/ios-archive.sh --upload control drive   # the same, then upload to App Store Connect
#
# Options:
#   --upload            Upload to App Store Connect instead of writing an .ipa. Refuses a tree
#                       with uncommitted changes: an uploaded build must name a commit.
#   --build-number N    CFBundleVersion for every app in this run. Default: the UTC minute
#                       (YYYYMMDDHHMM), which is distinct per run and always increases.
#
# Environment:
#   TENSHU_TEAM_ID                              else `tenshu.teamId` in local.properties.
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH     an App Store Connect API key. Optional: without
#                                               one, xcodebuild uses the account signed in to Xcode.
#                                               With one, --upload exports the .ipa and uploads it
#                                               with altool (docs/how-to/ios-testflight.md §2.1).
#
# ⚠ macOS bash 3.2: brace every expansion that is followed by a non-ASCII character.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
export PATH="/opt/homebrew/bin:/usr/local/bin:${PATH}"

UPLOAD=0
BUILD_NUMBER=""
APPS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --upload) UPLOAD=1 ;;
    --build-number) BUILD_NUMBER="${2:?--build-number needs a value}"; shift ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    -*) echo "ios-archive: unknown option $1" >&2; exit 2 ;;
    *) APPS+=("$1") ;;
  esac
  shift
done
[ "${#APPS[@]}" -gt 0 ] || { echo "ios-archive: name at least one app (control, drive, photos, maps, timeline, calendar, contacts, fit, home, mia)" >&2; exit 2; }

# <dir> <scheme> <bundle id>. An app belongs here once its project.yml applies the `ReleaseApp` and
# `AppFramework` templates (ios-release/release.yml) — i.e. it links its own `:tenshu-sdk-ios-<app>`
# framework, one spec — and ships a PrivacyInfo.xcprivacy. All ten are.
spec_for() {
  case "$1" in
    control) echo "control-ios ControlIos app.tenshu.control.ios" ;;
    drive)   echo "drive-ios DriveIos app.tenshu.drive.ios" ;;
    photos)   echo "photos-ios PhotosIos app.tenshu.photos.ios" ;;
    maps)     echo "maps-ios MapsIos app.tenshu.maps.ios" ;;
    timeline) echo "timeline-ios TimelineIos app.tenshu.timeline.ios" ;;
    calendar) echo "calendar-ios CalendarIos app.tenshu.calendar.ios" ;;
    contacts) echo "contacts-ios ContactsIos app.tenshu.contacts.ios" ;;
    fit)      echo "fit-ios FitIos app.tenshu.fit.ios" ;;
    home)     echo "home-ios HomeIos app.tenshu.home.ios" ;;
    mia)      echo "mia-ios MiaIos app.tenshu.mia.ios" ;;
    *) return 1 ;;
  esac
}

# ⚠⚠ The privacy-sensitive system frameworks an archived executable may load
# (`docs/proposals/ios-per-app-frameworks.md` D7, §9.3). App Store Connect answers a linked
# HealthKit, location, Bluetooth, motion or Photos framework with ITMS-90683 unless the app carries
# a purpose string for it, so a framework that appears here without being on the app's list fails
# the archive — locally, before an upload — instead of arriving as an email.
# ⚠ An ALLOWLIST, not attribution: `otool -L` cannot tell whether Kotlin's auto-link hints or the
# app's own Swift brought a framework in. It proves only that the app's set did not grow.
SENSITIVE_FRAMEWORKS="HealthKit CoreLocation CoreBluetooth CoreMotion Photos PhotosUI Contacts ContactsUI EventKit EventKitUI HomeKit Speech CoreNFC MediaPlayer AVFoundation LocalAuthentication"
allowed_sensitive_for() {
  case "$1" in
    # Measured on the P2 archive. AVFoundation: the QR scanner's camera (and `file-service-proxy`'s
    # video posters, in every app). LocalAuthentication: the app lock (Face ID), which since P3 is
    # bound in Control's graph alone (§3.3).
    control) echo "AVFoundation LocalAuthentication" ;;
    # Measured on the P3 archive. AVFoundation: the QR scanner's camera, video posters and the
    # streaming player. Photos: the photo backup (PhotoKit in `:tenshu-sdk-ios-drive`, and Drive's
    # Swift). PhotosUI is not listed: `otool -L` showed none (the upload picker is SwiftUI's
    # `PhotosPicker`).
    drive) echo "AVFoundation Photos" ;;
    # The other eight, measured on their archives. AVFoundation in every one:
    # `file-service-proxy`'s video posters (AVAssetImageGenerator), which every in-process graph
    # binds; it reads assets and captures nothing, so it owes no camera string.
    photos)   echo "AVFoundation" ;;   # + CoreML, ImageIO (on-device inference), not sensitive
    # EventKit: "Copy from this iPhone" and "Keep in sync" read the device calendars (CX-2a/2b),
    # behind NSCalendars*UsageDescription in calendar-ios/project.yml.
    calendar) echo "AVFoundation EventKit" ;;
    contacts) echo "AVFoundation" ;;   # no Contacts framework: Contacts keeps its own address book
    home)     echo "AVFoundation" ;;
    mia)      echo "AVFoundation" ;;
    # ⚠ Maps' executable loads no CoreLocation (MapKit is not on the list), but its embedded
    # MapLibre.framework does, which this check does not read — hence Maps' location strings.
    maps)     echo "AVFoundation" ;;
    # CoreLocation: visit / significant-change capture and CLGeocoder (`timeline-shared-data`).
    timeline) echo "AVFoundation CoreLocation" ;;
    # HealthKit: the importer and the workout writer. CoreLocation: the recorded route. CoreBluetooth:
    # the heart-rate strap. CoreMotion: CMPedometer during a workout (`fit-shared-data` iosMain;
    # CoreLocation also from `RecordView.swift`).
    fit)      echo "AVFoundation CoreBluetooth CoreLocation CoreMotion HealthKit" ;;
    *) return 1 ;;
  esac
}
for app in "${APPS[@]}"; do
  spec_for "$app" >/dev/null || { echo "ios-archive: '$app' is not set up for archiving (see ios-release/release.yml)" >&2; exit 2; }
done

if [ -z "${TENSHU_TEAM_ID:-}" ] && [ -f local.properties ]; then
  TENSHU_TEAM_ID="$(sed -n 's/^tenshu\.teamId=//p' local.properties | head -1)"
fi
[ -n "${TENSHU_TEAM_ID:-}" ] || { echo "ios-archive: no team — set TENSHU_TEAM_ID or tenshu.teamId in local.properties" >&2; exit 1; }
export TENSHU_TEAM_ID

if [ "$UPLOAD" = 1 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "ios-archive: refusing to upload from a tree with uncommitted changes" >&2
  exit 1
fi

[ -n "$BUILD_NUMBER" ] || BUILD_NUMBER="$(date -u +%Y%m%d%H%M)"
case "$BUILD_NUMBER" in
  *[!0-9.]*|"") echo "ios-archive: build number must be digits and dots, got '$BUILD_NUMBER'" >&2; exit 2 ;;
esac

AUTH=()
if [ -n "${ASC_KEY_ID:-}" ]; then
  : "${ASC_ISSUER_ID:?ASC_KEY_ID is set, so ASC_ISSUER_ID must be too}"
  : "${ASC_KEY_PATH:?ASC_KEY_ID is set, so ASC_KEY_PATH must be too}"
  AUTH=(-authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID" -authenticationKeyPath "$ASC_KEY_PATH")
  # altool takes no key path: it looks for AuthKey_<id>.p8 in API_PRIVATE_KEYS_DIR.
  [ "$(basename "$ASC_KEY_PATH")" = "AuthKey_${ASC_KEY_ID}.p8" ] \
    || { echo "ios-archive: ASC_KEY_PATH must name a file AuthKey_${ASC_KEY_ID}.p8 (altool finds the key by that name)" >&2; exit 2; }
  export API_PRIVATE_KEYS_DIR
  API_PRIVATE_KEYS_DIR="$(dirname "$ASC_KEY_PATH")"
fi

echo "ios-archive: build ${BUILD_NUMBER} at $(git rev-parse --short HEAD), team ${TENSHU_TEAM_ID}"

mkdir -p build/ios-archive
# One release link per framework, before any archive: sequential, each in the 10 GB daemon
# `ios-build-sdk.sh` asks for (two at once would need ~20 GB, ios-per-app-frameworks.md §8.3). The
# archive's own prebuild then finds it UP-TO-DATE.
for app in "${APPS[@]}"; do
  echo "ios-archive: linking the release framework of ${app}"
  TENSHU_SDK_VARIANT=release PLATFORM_NAME=iphoneos scripts/ios-build-sdk.sh "$app" >"build/ios-archive/sdk-${app}.log" 2>&1 \
    || { echo "ios-archive: FAIL — ${app}'s release framework did not link, see build/ios-archive/sdk-${app}.log" >&2; exit 1; }
done

fail() { echo "ios-archive: FAIL $1" >&2; FAILED=1; }

FAILED=0
for app in "${APPS[@]}"; do
  read -r dir scheme bundle <<<"$(spec_for "$app")"
  out="build/ios-archive/${scheme}"
  archive="${out}/${scheme}.xcarchive"
  # A fresh directory every time: an archive that reuses derived data can relink the previous
  # framework and still succeed.
  rm -rf "$out"
  mkdir -p "$out"

  # One spec, whose Release configuration links the app's release framework.
  project="${dir}/${scheme}.xcodeproj"; release_dir="tenshu-sdk-ios-${app}/build/bin/iosArm64/releaseFramework"
  (cd "$dir" && xcodegen generate >/dev/null) || { fail "$app — xcodegen failed"; continue; }

  echo "ios-archive: archiving ${scheme}"
  if ! xcodebuild archive \
      -project "$project" -scheme "$scheme" -configuration Release \
      -destination 'generic/platform=iOS' \
      -archivePath "$archive" -derivedDataPath "${out}/dd" \
      -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"} \
      CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
      >"${out}/archive.log" 2>&1; then
    fail "$app — archive failed, see ${out}/archive.log"
    continue
  fi

  # What the archive must be, checked on the product rather than on the settings.
  product="${archive}/Products/Applications/${scheme}.app"
  plist="${product}/Info.plist"
  got_bundle="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist")"
  got_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$plist")"
  got_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist")"
  [ "$got_bundle" = "$bundle" ] || { fail "$app — bundle id is ${got_bundle}, expected ${bundle}"; continue; }
  [ "$got_build" = "$BUILD_NUMBER" ] || { fail "$app — build number is ${got_build}, expected ${BUILD_NUMBER}"; continue; }
  [ -f "${product}/PrivacyInfo.xcprivacy" ] || { fail "$app — no PrivacyInfo.xcprivacy in the bundle"; continue; }
  if grep -q 'ProcessXCFramework.*TenshuSdk' "${out}/archive.log"; then
    fail "$app — the archive linked the DEBUG TenshuSdk framework"
    continue
  fi
  grep -q "$release_dir" "${out}/archive.log" \
    || { fail "$app — the archive never named its release TenshuSdk framework (${release_dir})"; continue; }

  # D7: the sensitive frameworks the executable loads must all be on the app's allowlist.
  linked="$(otool -L "${product}/${scheme}" | awk 'NR > 1 { print $1 }' | sed -n 's|.*/\([^/]*\)\.framework/.*|\1|p' | sort -u)"
  allowed=" $(allowed_sensitive_for "$app") "
  unexpected=""
  for fw in $SENSITIVE_FRAMEWORKS; do
    if printf '%s\n' "$linked" | grep -qx "$fw" && [ "${allowed#* $fw }" = "$allowed" ]; then
      unexpected="${unexpected} ${fw}"
    fi
  done
  printf '%s\n' "$linked" >"${out}/linked-frameworks.txt"
  if [ -n "$unexpected" ]; then
    fail "$app — links privacy-sensitive framework(s) not on its allowlist:${unexpected} (otool -L in ${out}/linked-frameworks.txt; ios-per-app-frameworks.md D7)"
    continue
  fi
  echo "ios-archive: ${app} links only allowed sensitive frameworks ($(printf '%s\n' "$linked" | grep -xE "$(echo $SENSITIVE_FRAMEWORKS | tr ' ' '|')" | tr '\n' ' '))"

  # With an API key the upload is altool's, not the export's: xcodebuild's upload first asks the
  # content-delivery service for the store configuration, and with Xcode 27.0 that
  # request fails with 3004 "Cannot verify client's JWT headers" for a key altool uploaded with
  # fine. The local export only logs that failure.
  if [ "$UPLOAD" = 1 ] && [ "${#AUTH[@]}" -eq 0 ]; then destination=upload; else destination=export; fi
  cat >"${out}/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>${destination}</string>
  <key>teamID</key><string>${TENSHU_TEAM_ID}</string>
  <key>signingStyle</key><string>automatic</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
  <key>uploadSymbols</key><true/>
</dict>
</plist>
EOF

  echo "ios-archive: ${destination} ${scheme}"
  if ! xcodebuild -exportArchive \
      -archivePath "$archive" -exportPath "${out}/export" \
      -exportOptionsPlist "${out}/ExportOptions.plist" \
      -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"} \
      >"${out}/export.log" 2>&1; then
    fail "$app — ${destination} failed, see ${out}/export.log"
    continue
  fi

  if [ "$UPLOAD" = 1 ] && [ "$destination" = export ]; then
    echo "ios-archive: upload ${scheme} (altool)"
    if ! xcrun altool --upload-app -f "$(ls "${out}/export"/*.ipa)" --type ios \
        --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" >"${out}/upload.log" 2>&1; then
      fail "$app — upload failed, see ${out}/upload.log"
      continue
    fi
  fi

  if [ "$UPLOAD" = 1 ]; then
    echo "ios-archive: PASS $app — ${bundle} ${got_version} (${BUILD_NUMBER}) uploaded"
  else
    echo "ios-archive: PASS $app — $(ls "${out}/export"/*.ipa)"
  fi
done

exit "$FAILED"
