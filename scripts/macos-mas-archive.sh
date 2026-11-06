#!/bin/zsh
# Build and optionally validate/upload the signed Mac App Store packages.
#
# This deliberately builds Control and Drive separately: they are separate App Store
# Connect records even though each macOS target reuses its iOS bundle ID for Universal
# Purchase. The package is the upload unit for a Mac App Store app.
#
# Required:
#   TENSHU_TEAM_ID             Apple team identifier
#   MAS_APP_IDENTITY           Apple Distribution identity (prefer its SHA-1)
#   MAS_INSTALLER_IDENTITY     Mac Installer Distribution identity (prefer its SHA-1)
#   MAS_CONTROL_PROFILE        Mac App Store profile for app.tenshu.control.ios
#   MAS_DRIVE_PROFILE          Mac App Store profile for app.tenshu.drive.ios
#   MAS_CONTROL_LOGIN_PROFILE  Mac App Store profile for app.tenshu.control.loginitem
#
# Required for --validate/--upload:
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH
#
# Optional:
#   MAS_JAVA_HOME              JDK used by Gradle; defaults to the current environment
#
# Examples:
#   scripts/macos-mas-archive.sh
#   scripts/macos-mas-archive.sh --app drive --validate
#   scripts/macos-mas-archive.sh --app all --upload
#
set -euo pipefail

if [[ $(uname -s) != Darwin ]]; then
  print -u2 "macos-mas-archive: must run on macOS"
  exit 2
fi

repo=${0:A:h:h}
cd "$repo"

selection=all
validate=0
upload=0
while (( $# > 0 )); do
  case "$1" in
    --app)
      (( $# >= 2 )) || { print -u2 "macos-mas-archive: --app needs control, drive, or all"; exit 2; }
      selection="$2"
      shift 2
      ;;
    --validate)
      validate=1
      shift
      ;;
    --upload)
      upload=1
      validate=1
      shift
      ;;
    -h|--help)
      sed -n '1,34p' "$0"
      exit 0
      ;;
    *)
      print -u2 "macos-mas-archive: unknown argument '$1'"
      exit 2
      ;;
  esac
done

case "$selection" in
  control) apps=(control) ;;
  drive) apps=(drive) ;;
  all) apps=(control drive) ;;
  *)
    print -u2 "macos-mas-archive: --app must be control, drive, or all"
    exit 2
    ;;
esac

: "${TENSHU_TEAM_ID:?TENSHU_TEAM_ID is required}"
: "${MAS_APP_IDENTITY:?MAS_APP_IDENTITY is required}"
: "${MAS_INSTALLER_IDENTITY:?MAS_INSTALLER_IDENTITY is required}"

control_profile=${MAS_CONTROL_PROFILE:-$repo/build/macos-profiles/TenshuControl-MacAppStore.provisionprofile}
drive_profile=${MAS_DRIVE_PROFILE:-$repo/build/macos-profiles/TenshuDrive-MacAppStore.provisionprofile}
fileprovider_profile=${MAS_DRIVE_FILE_PROVIDER_PROFILE:-$repo/build/macos-profiles/TenshuDrive-FileProvider-MacAppStore.provisionprofile}
login_profile=${MAS_CONTROL_LOGIN_PROFILE:-$repo/build/macos-profiles/TenshuControl-LoginItem-MacAppStore.provisionprofile}
build_number=${MAS_BUILD_NUMBER:-$(date -u +%Y%m%d%H%M)}

require_file() {
  [[ -f "$1" ]] || { print -u2 "macos-mas-archive: missing $2: $1"; exit 2; }
}

require_bundle() {
  [[ -d "$1" ]] || { print -u2 "macos-mas-archive: missing app bundle $2: $1"; exit 2; }
}

profile_app_id() {
  local decoded
  decoded=$(mktemp "${TMPDIR:-/tmp}/tenshu-mas-profile.XXXXXX")
  security cms -D -i "$1" -o "$decoded"
  /usr/libexec/PlistBuddy -c 'Print :Entitlements:com.apple.application-identifier' "$decoded"
  rm -f "$decoded"
}

check_mas_profile() {
  local profile="$1"
  local expected="$2"
  local actual
  require_file "$profile" "provisioning profile"
  actual=$(profile_app_id "$profile")
  [[ "$actual" == "${TENSHU_TEAM_ID}.${expected}" ]] || {
    print -u2 "macos-mas-archive: profile is for '$actual', want '${TENSHU_TEAM_ID}.${expected}'"
    exit 2
  }
  if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' <(security cms -D -i "$profile") >/dev/null 2>&1; then
    print -u2 "macos-mas-archive: $profile is a development profile, not a Mac App Store profile"
    exit 2
  fi
}

if (( ${apps[(I)control]} )); then
  check_mas_profile "$control_profile" "app.tenshu.control.ios"
  check_mas_profile "$login_profile" "app.tenshu.control.loginitem"
fi
if (( ${apps[(I)drive]} )); then
  check_mas_profile "$drive_profile" "app.tenshu.drive.ios"
  check_mas_profile "$fileprovider_profile" "app.tenshu.drive.ios.fileprovider"
fi

if (( validate || upload )); then
  : "${ASC_KEY_ID:?ASC_KEY_ID is required for --validate/--upload}"
  : "${ASC_ISSUER_ID:?ASC_ISSUER_ID is required for --validate/--upload}"
  : "${ASC_KEY_PATH:?ASC_KEY_PATH is required for --validate/--upload}"
  require_file "$ASC_KEY_PATH" "App Store Connect API key"
  [[ $(basename "$ASC_KEY_PATH") == "AuthKey_${ASC_KEY_ID}.p8" ]] || {
    print -u2 "macos-mas-archive: ASC_KEY_PATH must be named AuthKey_${ASC_KEY_ID}.p8"
    exit 2
  }
  export API_PRIVATE_KEYS_DIR=${ASC_KEY_PATH:A:h}
fi

if [[ -n "${MAS_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$MAS_JAVA_HOME"
fi

archive_root="$repo/build/macos-mas"
mkdir -p "$archive_root"

build_one() {
  local app="$1"
  local module profile nested_profile bundle app_name gradle_args app_path version pkg
  nested_profile=""
  case "$app" in
    control)
      module=control-desktop
      profile="$control_profile"
      bundle=app.tenshu.control.ios
      app_name=TenshuControl
      gradle_args=(
        ":control-desktop:packageDesktopApp"
        "-Ptenshu.loginItemProvisioningProfile=$login_profile"
      )
      app_path="$repo/control-desktop/build/compose/binaries/main/app/TenshuControl.app"
      ;;
    drive)
      module=drive-desktop
      profile="$drive_profile"
      nested_profile="$fileprovider_profile"
      bundle=app.tenshu.drive.ios
      app_name=TenshuDrive
      gradle_args=(":drive-desktop:packageDesktopApp")
      app_path="$repo/drive-desktop/build/compose/binaries/main/app/TenshuDrive.app"
      ;;
  esac

  print "macos-mas-archive: building $app_name"
  ./gradlew "${gradle_args[@]}" \
    -Ptenshu.masSandbox=true \
    -Ptenshu.teamId="$TENSHU_TEAM_ID" \
    -Ptenshu.buildNumber="$build_number" \
    -Ptenshu.signingIdentity="$MAS_APP_IDENTITY" \
    -Ptenshu.provisioningProfile="$profile" \
    ${nested_profile:+"-Ptenshu.fileProviderProvisioningProfile=$nested_profile"}

  require_bundle "$app_path" "$app_name.app"
  version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_path/Contents/Info.plist")
  pkg="$archive_root/${app_name}-${version}.pkg"
  rm -f "$pkg"
  productbuild --component "$app_path" /Applications \
    --sign "$MAS_INSTALLER_IDENTITY" "$pkg"
  pkgutil --check-signature "$pkg" >/dev/null
  print "macos-mas-archive: built $pkg ($bundle)"

  if (( validate )); then
    print "macos-mas-archive: validating $pkg"
    xcrun altool --validate-app -f "$pkg" -t macos \
      --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
  fi
  if (( upload )); then
    print "macos-mas-archive: uploading $pkg"
    xcrun altool --upload-app -f "$pkg" -t macos \
      --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
  fi
}

for app in "${apps[@]}"; do
  build_one "$app"
done

print ""
print "macos-mas-archive: complete"
print "Packages: $archive_root"
