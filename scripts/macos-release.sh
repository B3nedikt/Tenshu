#!/usr/bin/env bash
# Build notarized Developer ID releases of the desktop apps: one stapled .dmg per app.
#
# The off-store channel (Homebrew cask and the tenshu.app download, desktop-macos-distribution.md
# §2.12) installs exactly these files. Each app is built with -Ptenshu.developerIdRelease=true
# (hardened runtime, secure timestamps, allow-jit, native libraries pre-extracted and signed:
# gradle/desktop-app-signing.gradle.kts), checked Mach-O by Mach-O, notarized and stapled, then put
# in a .dmg that is signed, notarized and stapled too.
#
# Usage:
#   scripts/macos-release.sh                      # all ten, notarized → build/macos-release/
#   scripts/macos-release.sh photos maps          # a subset
#   scripts/macos-release.sh --dry-run            # resolve identity + credentials, print the plan, build nothing
#   scripts/macos-release.sh --pipeline-test fit  # everything except notarization → build/macos-release-test/
#
# Options:
#   --dry-run          Check identity, credentials and tools; print what would run. No build.
#   --pipeline-test    Exercise the pipeline without notarizing. Uses a Developer ID Application
#                      identity if there is one, else the team's Apple Development identity. Output
#                      goes to build/macos-release-test/, never build/macos-release/, because those
#                      files are not installable for anyone but a developer.
#   --identity <sha1>  Sign with this identity (SHA-1 from `security find-identity -v -p codesigning`).
#   --allow-dirty      Release from a tree with uncommitted changes (release mode refuses by default).
#
# Required for a release (not for --pipeline-test):
#   a "Developer ID Application: … (<team>)" identity in the keychain
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH   the App Store Connect API key the iOS/MAS scripts use
# Optional:
#   TENSHU_TEAM_ID     default 752LWAZMKZ
#   TENSHU_BUILD_NUMBER  CFBundleVersion for every app; default the UTC minute (as ios-archive.sh)
#
# Runbook: docs/how-to/macos-release.md.
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
  echo "macos-release: must run on macOS" >&2
  exit 2
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"
# shellcheck source=lib/app-catalogue.sh
source "$REPO/scripts/lib/app-catalogue.sh"

TEAM="${TENSHU_TEAM_ID:-752LWAZMKZ}"
DRY_RUN=0
PIPELINE_TEST=0
ALLOW_DIRTY=0
IDENTITY=""
APPS=""

while (( $# > 0 )); do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --pipeline-test) PIPELINE_TEST=1; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --identity)
      (( $# >= 2 )) || { echo "macos-release: --identity needs a SHA-1" >&2; exit 2; }
      IDENTITY="$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')"; shift 2 ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    -*) echo "macos-release: unknown option '$1'" >&2; exit 2 ;;
    *) app_spec "$1" >/dev/null || exit 2; APPS="${APPS} $1"; shift ;;
  esac
done
APPS="${APPS:-$TENSHU_APP_KEYS}"
APPS="$(echo $APPS)"  # trim

die() { echo "macos-release: $*" >&2; exit 1; }
say() { echo "macos-release: $*"; }

# ── Identity ──────────────────────────────────────────────────────────────────
# `security find-identity` prints:   1) <SHA1> "Developer ID Application: Name (TEAM)"
identities() { security find-identity -v -p codesigning 2>/dev/null | sed -n 's/^ *[0-9][0-9]*) \([0-9A-F]\{40\}\) "\(.*\)"$/\1|\2/p' | sort -u; }
# ⚠ The team is the certificate's subject OU, not the "(…)" at the end of its name: for an Apple
# Development certificate that is the person's id, and one of those on this Mac is for the old
# personal team. Read it off the certificate itself.
cert_team() {  # $1 sha1, $2 name
  security find-certificate -a -Z -c "$2" -p 2>/dev/null |
    awk -v sha="$1" '/^SHA-1 hash:/ { keep = ($3 == sha); next } /^SHA-256 hash:/ { next } keep { print }' |
    openssl x509 -noout -subject 2>/dev/null | sed -n 's/.*OU *= *\([A-Z0-9]\{10\}\).*/\1/p'
}
pick_identity() {  # $1 = certificate kind, e.g. "Developer ID Application"
  local sha name
  identities | while IFS='|' read -r sha name; do
    [[ "$name" == "$1: "* ]] || continue
    if [[ "$(cert_team "$sha" "$name")" == "$TEAM" ]]; then printf '%s|%s\n' "$sha" "$name"; fi
  done
  return 0  # ⚠ under set -e a non-matching last identity must not become the function's status
}

if [[ -n "$IDENTITY" ]]; then
  IDENTITY_NAME="$(identities | awk -F'|' -v sha="$IDENTITY" '$1 == sha { print $2 }')"
  [[ -n "$IDENTITY_NAME" ]] || die "no valid code-signing identity with SHA-1 $IDENTITY in the keychain"
else
  devid="$(pick_identity "Developer ID Application")"
  if [[ "$(printf '%s' "$devid" | grep -c . || true)" -gt 1 ]]; then
    die "more than one Developer ID Application identity for $TEAM; pick one with --identity <sha1>:
$(printf '%s\n' "$devid" | sed 's/^/  /')"
  fi
  if [[ -z "$devid" && $PIPELINE_TEST -eq 1 ]]; then
    devid="$(pick_identity "Apple Development" | sed -n 1p)"
    [[ -n "$devid" ]] || die "no Developer ID Application or Apple Development identity for team $TEAM in the keychain"
  fi
  if [[ -z "$devid" ]]; then
    cat >&2 <<EOF
macos-release: no "Developer ID Application" identity for team $TEAM in the keychain.

  A notarized release can only be signed with one. Only the Account Holder can create it:
  developer.apple.com → Certificates, IDs & Profiles → Certificates → + → Developer ID Application.
  Import the certificate and its private key into the login keychain, keep an offline backup of
  the .p12 (a lost key cannot be recovered), then check it is listed:

    security find-identity -v -p codesigning

  To exercise everything except notarization without it:  scripts/macos-release.sh --pipeline-test
EOF
    exit 1
  fi
  IDENTITY="${devid%%|*}"
  IDENTITY_NAME="${devid#*|}"
fi
case "$IDENTITY_NAME" in
  "Developer ID Application: "*) IS_DEVELOPER_ID=1 ;;
  *) IS_DEVELOPER_ID=0 ;;
esac
IDENTITY_TEAM="$(cert_team "$IDENTITY" "$IDENTITY_NAME")"
[[ "$IDENTITY_TEAM" == "$TEAM" ]] || die "identity '$IDENTITY_NAME' is for team '${IDENTITY_TEAM:-unknown}', not $TEAM"

if (( ! PIPELINE_TEST && ! IS_DEVELOPER_ID )); then
  die "'$IDENTITY_NAME' is not a Developer ID Application identity; a release cannot be notarized with it (use --pipeline-test to exercise the pipeline)"
fi

# ── Credentials, tools, tree ───────────────────────────────────────────────────
for tool in codesign hdiutil ditto plutil shasum lipo python3 openssl /usr/libexec/PlistBuddy; do
  command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool"
done
if (( ! PIPELINE_TEST )); then
  : "${ASC_KEY_ID:?ASC_KEY_ID is required to notarize (the App Store Connect API key)}"
  : "${ASC_ISSUER_ID:?ASC_ISSUER_ID is required to notarize}"
  : "${ASC_KEY_PATH:?ASC_KEY_PATH is required to notarize}"
  [[ -f "$ASC_KEY_PATH" ]] || die "ASC_KEY_PATH does not exist: $ASC_KEY_PATH"
  xcrun --find notarytool >/dev/null 2>&1 || die "xcrun notarytool not found (Xcode 13 or later)"
  xcrun --find stapler >/dev/null 2>&1 || die "xcrun stapler not found"
  if (( ! ALLOW_DIRTY )) && [[ -n "$(git status --porcelain)" ]]; then
    die "the tree has uncommitted changes; a release is built from a commit (--allow-dirty to override)"
  fi
fi

BUILD_NUMBER="${TENSHU_BUILD_NUMBER:-$(date -u +%Y%m%d%H%M)}"
[[ "$BUILD_NUMBER" =~ ^[0-9]+(\.[0-9]+)*$ ]] || die "TENSHU_BUILD_NUMBER must be numeric, got '$BUILD_NUMBER'"
MIN_MACOS="$(sed -n 's/^tenshu\.macosDeploymentTarget=//p' "$REPO/gradle.properties")"
[[ "$MIN_MACOS" =~ ^[0-9]+\.[0-9]+$ ]] || die "no tenshu.macosDeploymentTarget in gradle.properties"
if (( PIPELINE_TEST )); then OUT="$REPO/build/macos-release-test"; else OUT="$REPO/build/macos-release"; fi
# ⚠ Everything is written to $STAGE and moved to $OUT only after every app passed, so a failed run
# never leaves part of a release where a publish step would look for one.
STAGE="$OUT.staging"
LOGS="$STAGE/logs"

gradle_tasks=()
for app in $APPS; do
  gradle_tasks+=("$(app_field "$app" desktopModule):$(app_field "$app" desktopTask)")
done

say "identity  $IDENTITY_NAME ($IDENTITY)"
say "team      $TEAM · build $BUILD_NUMBER · apps: $APPS"
say "mode      $( (( PIPELINE_TEST )) && echo "PIPELINE TEST — not notarized, not installable outside this team's Macs" || echo "release — notarize + staple")"
say "output    $OUT"
if (( DRY_RUN )); then
  say "would run: ./gradlew ${gradle_tasks[*]} -Ptenshu.developerIdRelease=true -Ptenshu.teamId=$TEAM -Ptenshu.signingIdentity=$IDENTITY -Ptenshu.buildNumber=$BUILD_NUMBER"
  for app in $APPS; do
    say "would ship $(app_field "$app" desktopBundle) ($(app_field "$app" iosBundle)) → $OUT/$(basename "$(app_field "$app" desktopBundle)" .app)-<version>.dmg"
  done
  say "dry run: nothing built"
  exit 0
fi

# ── Build ──────────────────────────────────────────────────────────────────────
# A fresh distributable for every app: the signing task edits the bundle in place, and a bundle
# left by a development or MAS build must not be what gets notarized.
for app in $APPS; do
  rm -rf "$(app_field "$app" desktopModule | tr -d ':')/build/compose/binaries/main/app"
done
say "building ${#gradle_tasks[@]} app(s)"
./gradlew "${gradle_tasks[@]}" \
  -Ptenshu.developerIdRelease=true \
  -Ptenshu.teamId="$TEAM" \
  -Ptenshu.signingIdentity="$IDENTITY" \
  -Ptenshu.buildNumber="$BUILD_NUMBER"

rm -rf "$STAGE"
mkdir -p "$LOGS"

# ── Checks ─────────────────────────────────────────────────────────────────────
plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1/Contents/Info.plist" 2>/dev/null; }
is_macho() { file --brief "$1" | grep -q 'Mach-O'; }

# Every zip archive in the bundle (found by its signature, any name), recursing into archives
# inside archives: prints each entry that is a Mach-O (thin or fat, by magic bytes) or carries a
# native-library extension. Nothing printed = clean.
archive_native_scan() {
  python3 -I - "$1" <<'PY'
import io, os, struct, sys, zipfile
NATIVE_EXT = ('.dylib', '.jnilib', '.so', '.dll')
def macho(b):
    if len(b) < 8: return False
    if b[:4] in (b'\xcf\xfa\xed\xfe', b'\xce\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xfe\xed\xfa\xce'): return True
    # A fat header shares 0xCAFEBABE with a .class file; the next word is the slice count there and
    # the major version (>= 45) in a class file.
    return b[:4] in (b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf') and struct.unpack('>I', b[4:8])[0] < 45
def scan(zf, label):
    for info in zf.infolist():
        if info.is_dir(): continue
        name = label + '/' + info.filename
        with zf.open(info) as f: head = f.read(8)
        if macho(head) or info.filename.lower().endswith(NATIVE_EXT):
            print(name)
        elif head[:4] == b'PK\x03\x04':
            try: scan(zipfile.ZipFile(io.BytesIO(zf.read(info))), name)
            except zipfile.BadZipFile: pass
root = sys.argv[1]
for d, _, files in os.walk(root):
    for fn in files:
        path = os.path.join(d, fn)
        if os.path.islink(path): continue
        with open(path, 'rb') as f:
            if f.read(4) != b'PK\x03\x04': continue
        try: scan(zipfile.ZipFile(path), os.path.relpath(path, root))
        except zipfile.BadZipFile: pass
PY
}

check_bundle() {  # $1 app key, $2 bundle path
  local app="$1" bundle="$2" want_id got_id version count=0 bad=0 file info nested want_nested ents native_in_archives
  want_id="$(app_field "$app" iosBundle)"
  got_id="$(plist "$bundle" CFBundleIdentifier)"
  # C4: every macOS bundle id is the app's iOS one, in every channel.
  [[ "$got_id" == "$want_id" ]] || die "$app: CFBundleIdentifier '$got_id', want '$want_id' (desktop-macos-distribution.md C4)"
  [[ "$(plist "$bundle" CFBundleVersion)" == "$BUILD_NUMBER" ]] || die "$app: CFBundleVersion is '$(plist "$bundle" CFBundleVersion)', want $BUILD_NUMBER"
  version="$(plist "$bundle" CFBundleShortVersionString)"
  [[ -n "$version" ]] || die "$app: no CFBundleShortVersionString"
  # D10. ⚠ A module that does not set minimumSystemVersion gets jpackage's 10.13, and an arm64-only
  # app then offers to open on macOS 11–13 (eight of ten did, in the first notarized 1.0.1).
  [[ "$(plist "$bundle" LSMinimumSystemVersion)" == "$MIN_MACOS" ]] \
    || die "$app: LSMinimumSystemVersion is '$(plist "$bundle" LSMinimumSystemVersion)', want $MIN_MACOS (gradle.properties tenshu.macosDeploymentTarget; set minimumSystemVersion in the module's macOS block)"

  nested=0
  for d in "$bundle/Contents/Helpers" "$bundle/Contents/PlugIns"; do
    [[ -d "$d" ]] && nested=$(( nested + $(ls -1 "$d" | wc -l | tr -d ' ') ))
  done
  want_nested="$(app_field "$app" nestedHelpers)"
  [[ "$nested" -eq "$want_nested" ]] || die "$app: $nested nested helpers, expected $want_nested (scripts/lib/app-catalogue.sh)"

  # Every Mach-O: our team, hardened runtime, secure timestamp, arm64 only, the run's identity.
  while IFS= read -r -d '' file; do
    is_macho "$file" || continue
    count=$(( count + 1 ))
    info="$(codesign -dvvv "$file" 2>&1 || true)"
    if ! grep -q "^TeamIdentifier=$TEAM$" <<<"$info"; then
      echo "  ✗ team: ${file#"$bundle"/} ($(grep '^TeamIdentifier=' <<<"$info" || echo unsigned))" >&2; bad=1
    fi
    if ! grep -Eq '^CodeDirectory .*flags=0x[0-9a-f]+\([^)]*runtime' <<<"$info"; then
      echo "  ✗ no hardened runtime: ${file#"$bundle"/}" >&2; bad=1
    fi
    if ! grep -q '^Timestamp=' <<<"$info"; then
      echo "  ✗ no secure timestamp: ${file#"$bundle"/}" >&2; bad=1
    fi
    if [[ "$(grep -m1 '^Authority=' <<<"$info")" != "Authority=$IDENTITY_NAME" ]]; then
      echo "  ✗ signer: ${file#"$bundle"/} ($(grep -m1 '^Authority=' <<<"$info" || echo none))" >&2; bad=1
    fi
    if [[ "$(lipo -archs "$file" 2>/dev/null)" != "arm64" ]]; then
      echo "  ✗ not arm64-only: ${file#"$bundle"/} ($(lipo -archs "$file" 2>/dev/null))" >&2; bad=1
    fi
  done < <(find "$bundle" -type f -print0)
  (( count > 0 )) || die "$app: no Mach-O files found in $bundle"

  # The notary service opens archives (jars included); an unsigned Mach-O inside one rejects the
  # whole submission. ⚠⚠ By magic bytes, not by extension: the first real submission (2026-11-06)
  # was rejected for three extension-less helper executables in jars that a name check passed.
  native_in_archives="$(archive_native_scan "$bundle")"
  if [[ -n "$native_in_archives" ]]; then
    printf '%s\n' "$native_in_archives" | sed 's/^/  ✗ native code inside an archive: /' >&2; bad=1
  fi

  # Entitlements of the host and every nested bundle/helper.
  for file in "$bundle" "$bundle"/Contents/Helpers/* "$bundle"/Contents/PlugIns/* "$bundle"/Contents/Library/LoginItems/*; do
    [[ -e "$file" ]] || continue
    ents="$(codesign -d --entitlements - --xml "$file" 2>/dev/null || true)"
    if grep -q 'com.apple.security.get-task-allow' <<<"$ents"; then
      echo "  ✗ get-task-allow: ${file#"$bundle"/}" >&2; bad=1
    fi
    if grep -q 'com.apple.security.cs.disable-library-validation' <<<"$ents"; then
      echo "  ✗ disable-library-validation: ${file#"$bundle"/}" >&2; bad=1
    fi
  done
  grep -q 'com.apple.security.cs.allow-jit' <<<"$(codesign -d --entitlements - --xml "$bundle" 2>/dev/null)" ||
    { echo "  ✗ the host lacks cs.allow-jit (the JVM traps without it under the hardened runtime)" >&2; bad=1; }

  codesign --verify --strict --deep --verbose=2 "$bundle" >"$LOGS/$app-codesign-verify.log" 2>&1 ||
    { echo "  ✗ codesign --verify --strict --deep failed: $LOGS/$app-codesign-verify.log" >&2; bad=1; }

  (( bad == 0 )) || die "$app: $bundle failed the release checks above"
  say "$app: $count Mach-O files checked · $nested nested · $got_id $version ($BUILD_NUMBER)"
  CHECKED_VERSION="$version"
}

notarize() {  # $1 label, $2 file to submit
  local label="$1" file="$2" json status id
  json="$LOGS/$label-notary.json"
  say "$label: notarizing $(basename "$file") (waits for Apple)"
  xcrun notarytool submit "$file" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" \
    --wait --output-format json >"$json" 2>"$LOGS/$label-notary.stderr" || true
  status="$(plutil -extract status raw -o - "$json" 2>/dev/null || echo unknown)"
  id="$(plutil -extract id raw -o - "$json" 2>/dev/null || true)"
  if [[ "$status" != "Accepted" ]]; then
    if [[ -n "$id" ]]; then
      xcrun notarytool log "$id" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" \
        "$LOGS/$label-notary-log.json" >/dev/null 2>&1 || true
    fi
    die "$label: notarization status '$status' (submission ${id:-none}); see $LOGS/$label-notary*.json and .stderr"
  fi
  say "$label: notarized (submission $id)"
}

bundle_of() { echo "$REPO/$(app_field "$1" desktopModule | tr -d ':')/build/compose/binaries/main/app/$(app_field "$1" desktopBundle)"; }

# Phase 1: check every bundle before anything is sent to Apple.
VERSIONS=""
for app in $APPS; do
  bundle="$(bundle_of "$app")"
  [[ -d "$bundle" ]] || die "$app: no bundle at $bundle"
  check_bundle "$app" "$bundle"
  VERSIONS="$VERSIONS $CHECKED_VERSION"
done
# D9: one version for every app in a release.
if [[ "$(printf '%s\n' $VERSIONS | sort -u | wc -l | tr -d ' ')" != 1 ]]; then
  die "the apps disagree on CFBundleShortVersionString ($VERSIONS); a release carries one version (D9)"
fi
version="$(echo $VERSIONS | cut -d' ' -f1)"

# Phase 2: notarize, staple, package.
for app in $APPS; do
  bundle="$(bundle_of "$app")"
  bundle_name="$(basename "$bundle")"
  stem="$(basename "$bundle_name" .app)"

  if (( ! PIPELINE_TEST )); then
    ditto -c -k --keepParent "$bundle" "$LOGS/$stem.zip"
    notarize "$app-app" "$LOGS/$stem.zip"
    rm -f "$LOGS/$stem.zip"
    xcrun stapler staple "$bundle" >"$LOGS/$app-staple-app.log" 2>&1 || die "$app: stapling the app failed ($LOGS/$app-staple-app.log)"
  fi

  # The .dmg: the app plus an /Applications link, compressed, signed with the same identity.
  staging="$(mktemp -d "${TMPDIR:-/tmp}/tenshu-dmg.XXXXXX")"
  ditto "$bundle" "$staging/$bundle_name"
  ln -s /Applications "$staging/Applications"
  dmg="$STAGE/$stem-$version.dmg"
  hdiutil create -volname "${stem/Tenshu/Tenshu }" -srcfolder "$staging" -fs HFS+ -format UDZO -ov "$dmg" \
    >"$LOGS/$app-hdiutil.log" 2>&1 || die "$app: hdiutil create failed ($LOGS/$app-hdiutil.log)"
  rm -rf "$staging"
  codesign --force --timestamp --sign "$IDENTITY" "$dmg" >"$LOGS/$app-codesign-dmg.log" 2>&1 ||
    die "$app: signing the dmg failed ($LOGS/$app-codesign-dmg.log)"

  if (( ! PIPELINE_TEST )); then
    notarize "$app-dmg" "$dmg"
    xcrun stapler staple "$dmg" >"$LOGS/$app-staple-dmg.log" 2>&1 || die "$app: stapling the dmg failed ($LOGS/$app-staple-dmg.log)"
    # Gatekeeper's verdict, as a downloading user's Mac will reach it.
    spctl -a -vv -t exec "$bundle" >"$LOGS/$app-spctl-app.log" 2>&1 || die "$app: Gatekeeper rejects the app ($LOGS/$app-spctl-app.log)"
    grep -q 'source=Notarized Developer ID' "$LOGS/$app-spctl-app.log" || die "$app: Gatekeeper accepts the app, but not as Notarized Developer ID ($LOGS/$app-spctl-app.log)"
    spctl -a -vv -t open --context context:primary-signature "$dmg" >"$LOGS/$app-spctl-dmg.log" 2>&1 ||
      die "$app: Gatekeeper rejects the dmg ($LOGS/$app-spctl-dmg.log)"
    xcrun stapler validate "$bundle" >/dev/null 2>&1 || die "$app: stapler validate fails on the app"
    xcrun stapler validate "$dmg" >/dev/null 2>&1 || die "$app: stapler validate fails on the dmg"
  else
    # Informational only: an unnotarized build is expected to be rejected here.
    spctl -a -vv -t exec "$bundle" >"$LOGS/$app-spctl-app.log" 2>&1 || true
    say "$app: (pipeline test) Gatekeeper on the app: $(head -1 "$LOGS/$app-spctl-app.log")"
  fi
  say "$app: → $OUT/$(basename "$dmg")"
done

( cd "$STAGE" && shasum -a 256 ./*.dmg | sed 's| \./| |' >SHA256SUMS )
say "SHA256SUMS:"
rm -rf "$OUT"
mv "$STAGE" "$OUT"
sed 's/^/  /' "$OUT/SHA256SUMS"
if (( PIPELINE_TEST )); then
  say "PIPELINE TEST complete — NOT notarized. Do not distribute anything in $OUT."
else
  say "release complete: $OUT (not published; see docs/how-to/macos-release.md)"
fi
