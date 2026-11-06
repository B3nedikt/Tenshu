#!/usr/bin/env bash
#
# Build Play-uploadable release bundles, signed with the key held in the macOS Keychain — or, with
# --direct, APKs for outside Play (the download on tenshu.app), signed with the side-channel key.
#
#   scripts/android-release.sh --init-key                 # once: generate the key into the Keychain
#   scripts/android-release.sh --version-code 2 control   # one app
#   scripts/android-release.sh --version-code 2           # all ten
#   scripts/android-release.sh --version-code 3 --upload internal control   # build, check, upload
#
#   scripts/android-release.sh --init-key --direct        # once, by the maintainer: the side-channel key
#   scripts/android-release.sh --direct --version-code 6  # all ten APKs, Stripe rail
#
# --direct output: build/direct/<app>-<versionCode>.apk, SHA256SUMS, release.json (versions, digests,
# sizes, the signing certificate) and signing-cert-sha256.txt — written only if every APK passed. It
# builds `assembleRelease` with -Ptenshu.android.billingRail=stripe (a Play build never offers Stripe,
# a direct one never Play Billing: docs/proposals/commerce-store-link-out.md D3), and refuses:
#   - a signer other than the side-channel key pinned in FirstPartySigning.kt — an unpinned key would
#     be refused by every Play-installed app. --allow-unpinned skips this for a dry run before the key
#     exists, and writes to build/direct-unpinned/ instead, which is never to be published;
#   - a build that baked anything but the production relay, commerce and issuer;
#   - the four AAB checks below that apply to an APK (1, 2, 3, 5), plus the APK's zip alignment.
#
# Play output: build/play/<app>-<versionCode>.aab — and nothing is written there unless it passed every
# check below. With --upload <track>, each bundle that passed is then sent to that Play track as
# its only release (scripts/lib/play_publish.py; service account in the Keychain item
# `play-service-account`). ⚠ Nothing is uploaded unless every requested bundle passed.
# ⚠ Play refuses an app's first upload — create the app and make its signing choice in the Console.
#
# The key follows deploy/scaleway-dev/tf.sh's convention (account `tenshu`, one item per value):
#
#   android-upload-keystore            the PKCS12 keystore, base64
#   android-upload-keystore-password   its password (store and key share it)
#   android-sidechannel-keystore       --direct: the side-channel key, alias `sidechannel`
#   android-sidechannel-keystore-password
#
# It is materialised into a temporary directory for the one Gradle run and removed on exit, so no
# keystore file and no password sits in the checkout, in `local.properties`, or in shell history.
# ⚠ Hygiene, not a boundary (docs/proposals/ops-secret-custody.md D2): a process running as you can
# read the item.
#
# ⚠ An exported TENSHU_RELEASE_STORE_FILE wins over the Keychain, with its three siblings
# (`_STORE_PASSWORD`, `_KEY_ALIAS`, `_KEY_PASSWORD`) — that is the route for CI, and for signing
# with a key that deliberately does not live in the Keychain.
#
# ⚠⚠ Things Gradle reports as success and Play or the app then refuses, so this script checks them:
#   1. a bundle signed with the DEBUG key — the build's fallback whenever no key is configured;
#   2. a native library that is not 16 KB page-aligned — it arrives through a dependency's AAR, so
#      nothing in this repository changes when one appears;
#   3. a class native code reaches by name that R8 removed — the app aborts the first time that
#      feature runs (scripts/lib/check_jni_classes.py);
#   4. no native debug symbols in the bundle — no NDK on this machine, or a build that predates it;
#   5. a client app (anything but Control) whose merged manifest holds INTERNET — a dependency
#      merges it in silently (scripts/lib/check_client_no_internet.py).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ACCOUNT=tenshu
KEYSTORE_ITEM=android-upload-keystore
PASSWORD_ITEM=android-upload-keystore-password
ALIAS=upload
# The side-channel key (--direct): a permanent identity users' devices pin, never the upload key.
DIRECT_KEYSTORE_ITEM=android-sidechannel-keystore
DIRECT_PASSWORD_ITEM=android-sidechannel-keystore-password
DIRECT_ALIAS=sidechannel
ALL_APPS="control drive maps timeline calendar contacts fit photos home mia"

die() { echo "android-release.sh: $*" >&2; exit 1; }
keychain() { security find-generic-password -a "$ACCOUNT" -s "$1" -w 2>/dev/null || true; }
module_of() { if [[ "$1" == control ]]; then echo control; else echo "$1-android"; fi; }
# SHA-256 of the signing certificate, as keytool prints it.
fingerprint() { grep -m1 'SHA256:' | sed -E 's/.*SHA256: *//'; }

init_key() {
  [[ -z "$(keychain "$KEYSTORE_ITEM")" ]] ||
    die "'$KEYSTORE_ITEM' already exists in the Keychain (account $ACCOUNT). Delete it deliberately first — a new key is a different app identity."
  local tmp password
  # ⚠ Expand $tmp now: the EXIT trap fires after this function returns, when the `local` is gone
  # (under `set -u` that aborted the cleanup and left the private key behind in $TMPDIR).
  tmp="$(mktemp -d)"; trap "rm -rf '$tmp'" EXIT
  password="$(openssl rand -base64 30)"
  # ⚠ The password goes through the environment, never argv, so it is not visible in `ps`.
  STOREPASS="$password" keytool -genkeypair -keystore "$tmp/key.p12" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=Tenshu, O=Tenshu" -storepass:env STOREPASS >/dev/null
  security add-generic-password -U -a "$ACCOUNT" -s "$PASSWORD_ITEM" -w "$password"
  security add-generic-password -U -a "$ACCOUNT" -s "$KEYSTORE_ITEM" -w "$(base64 < "$tmp/key.p12" | tr -d '\n')"
  echo "Key generated into the Keychain (account $ACCOUNT: $KEYSTORE_ITEM, $PASSWORD_ITEM)."
  echo "Certificate SHA-256: $(STOREPASS="$password" keytool -list -v -keystore "$tmp/key.p12" -storepass:env STOREPASS | fingerprint)"
  if [[ "$ALIAS" == "$DIRECT_ALIAS" ]]; then
    echo "⚠⚠ This is the side-channel key: a permanent identity. Back it up offline before the first"
    echo "   APK ships, then pin the digest above in FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256"
    echo "   and in docs/how-to/android-release.md, and ship that pin through Play first."
  else
    echo "⚠ The Keychain holds the only copy. Back it up before the first upload."
  fi
}

VERSION_CODE=""; VERSION_NAME=""; APPS=""; TRACK=""; DIRECT=0; ALLOW_UNPINNED=0; INIT_KEY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --init-key) INIT_KEY=1; shift ;;
    --direct) DIRECT=1; shift ;;
    --allow-unpinned) ALLOW_UNPINNED=1; shift ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --upload) TRACK="$2"; shift 2 ;;
    -h|--help) sed -n '2,54p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [[ " $ALL_APPS " == *" $1 "* ]] || die "unknown app '$1' (one of: $ALL_APPS)"; APPS="$APPS $1"; shift ;;
  esac
done
if [[ $DIRECT -eq 1 ]]; then
  KEYSTORE_ITEM="$DIRECT_KEYSTORE_ITEM"; PASSWORD_ITEM="$DIRECT_PASSWORD_ITEM"; ALIAS="$DIRECT_ALIAS"
  [[ -z "$TRACK" ]] || die "--upload is for Play; a --direct APK is published on tenshu.app"
else
  [[ $ALLOW_UNPINNED -eq 0 ]] || die "--allow-unpinned only applies to --direct"
fi
if [[ $INIT_KEY -eq 1 ]]; then init_key; exit 0; fi
[[ -n "$APPS" ]] || APPS="$ALL_APPS"
[[ -n "$VERSION_CODE" ]] || die "--version-code N is required: Play refuses a code it has already seen for an app, and Android refuses to update an installed APK to a lower one."
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "--version-code must be a whole number"
# Fail on missing credentials or app access before a build of several minutes, not after it.
if [[ -n "$TRACK" ]]; then
  for app in $APPS; do python3 -I scripts/lib/play_publish.py check "app.tenshu.$app.android" >/dev/null; done
fi

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
if [[ -z "${TENSHU_RELEASE_STORE_FILE:-}" ]]; then
  encoded="$(keychain "$KEYSTORE_ITEM")"; password="$(keychain "$PASSWORD_ITEM")"
  [[ -n "$encoded" && -n "$password" ]] ||
    die "no '$KEYSTORE_ITEM' / '$PASSWORD_ITEM' in the Keychain (account $ACCOUNT) and TENSHU_RELEASE_STORE_FILE is unset. Run --init-key once."
  printf '%s' "$encoded" | base64 -d > "$TMP/key.p12"
  export TENSHU_RELEASE_STORE_FILE="$TMP/key.p12"
  export TENSHU_RELEASE_STORE_PASSWORD="$password" TENSHU_RELEASE_KEY_PASSWORD="$password"
  export TENSHU_RELEASE_KEY_ALIAS="$ALIAS"
fi
for var in TENSHU_RELEASE_STORE_PASSWORD TENSHU_RELEASE_KEY_ALIAS TENSHU_RELEASE_KEY_PASSWORD; do
  [[ -n "${!var:-}" ]] ||
    die "TENSHU_RELEASE_STORE_FILE is set but $var is not: set all four, or none to use the Keychain."
done
EXPECTED="$(STOREPASS="$TENSHU_RELEASE_STORE_PASSWORD" keytool -list -v -keystore "$TENSHU_RELEASE_STORE_FILE" \
  -alias "$TENSHU_RELEASE_KEY_ALIAS" -storepass:env STOREPASS | fingerprint)"
[[ -n "$EXPECTED" ]] || die "could not read the signing certificate from the keystore"

if [[ $DIRECT -eq 1 ]]; then
  # ⚠⚠ Refuse BEFORE the build: an APK whose signer no Play-installed app has pinned cannot talk to
  # one, and a key once published can never be withdrawn from users' devices.
  PINNED="$(python3 -I scripts/lib/direct_release.py pinned-digest)"
  OUT=build/direct
  if [[ "$PINNED" == "$EXPECTED" ]]; then
    :
  elif [[ $ALLOW_UNPINNED -eq 1 ]]; then
    OUT=build/direct-unpinned
    echo "⚠ --allow-unpinned: signer $EXPECTED is not the pinned side-channel key (${PINNED:-none pinned})." >&2
    echo "  Output goes to $OUT/ and must not be published." >&2
  elif [[ -z "$PINNED" ]]; then
    die "no side-channel key is pinned in FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256 — pin $EXPECTED there (and in docs/how-to/android-release.md), ship it through Play, then build. --allow-unpinned for a dry run."
  else
    die "the keystore's certificate $EXPECTED is not the pinned side-channel key $PINNED"
  fi

  # The generators write the relay / commerce / issuer / rail config the build is checked against,
  # so they run in the same invocation whichever apps are asked for.
  read -r -a TASKS <<< "$(python3 -I scripts/lib/direct_release.py generator-tasks)"
  for app in $APPS; do TASKS+=(":$(module_of "$app"):assembleRelease"); done
  PROPS=("-Ptenshu.versionCode=$VERSION_CODE" "-Ptenshu.android.billingRail=stripe")
  [[ -z "$VERSION_NAME" ]] || PROPS+=("-Ptenshu.versionName=$VERSION_NAME")
  ./gradlew "${TASKS[@]}" "${PROPS[@]}" --no-configuration-cache

  failed=0
  python3 -I scripts/lib/direct_release.py endpoints || failed=1
  # ⚠ Into a staging directory, moved into place only when every APK passed: a failed build of N+1
  # must not delete the verified release N.
  STAGE="$OUT.staging"
  rm -rf "$STAGE"; mkdir -p "$STAGE"
  PAIRS=()
  for app in $APPS; do
    module="$(module_of "$app")"
    apk="$module/build/outputs/apk/release/$module-release.apk"
    [[ -f "$apk" ]] || { echo "✗ $app: no APK at $apk" >&2; failed=1; continue; }
    if ! python3 -I scripts/lib/direct_release.py badging "$apk" "app.tenshu.$app.android" "$VERSION_CODE"; then failed=1; continue; fi
    actual="$(python3 -I scripts/lib/direct_release.py signer "$apk")" || { failed=1; continue; }
    if [[ "$actual" != "$EXPECTED" ]]; then
      echo "✗ $app: signed by $actual, not by the side-channel key" >&2; failed=1; continue
    fi
    if ! python3 -I scripts/lib/direct_release.py abi "$apk"; then failed=1; continue; fi
    if ! python3 -I scripts/lib/check_16kb_alignment.py "$apk"; then failed=1; continue; fi
    if ! python3 -I scripts/lib/check_jni_classes.py "$apk"; then failed=1; continue; fi
    if ! python3 -I scripts/lib/check_client_no_internet.py --variant release "$app"; then failed=1; continue; fi
    cp "$apk" "$STAGE/$app-$VERSION_CODE.apk"
    PAIRS+=("$app=$app-$VERSION_CODE.apk")
    echo "✓ $app → $OUT/$app-$VERSION_CODE.apk ($(( $(stat -f%z "$apk") / 1024 / 1024 )) MB)"
  done
  if [[ $failed -ne 0 ]]; then rm -rf "$STAGE"; die "at least one APK is not publishable — see above (the previous $OUT/ is untouched)."; fi
  python3 -I scripts/lib/direct_release.py manifest "$STAGE" "$VERSION_CODE" "${VERSION_NAME:-1.0}" "$EXPECTED" "${PAIRS[@]}"
  echo "$EXPECTED" > "$STAGE/signing-cert-sha256.txt"
  rm -rf "$OUT"; mv "$STAGE" "$OUT"
  echo "Signed by: $EXPECTED"
  echo "Checksums: $OUT/SHA256SUMS, manifest: $OUT/release.json"
  exit 0
fi

TASKS=()
for app in $APPS; do TASKS+=(":$(module_of "$app"):bundleRelease"); done
# ⚠ The rail explicitly, so a `tenshu.android.billingRail` line in a gradle.properties cannot put
# Stripe into a Play bundle (commerce-store-link-out.md D3).
PROPS=("-Ptenshu.versionCode=$VERSION_CODE" "-Ptenshu.android.billingRail=play")
[[ -z "$VERSION_NAME" ]] || PROPS+=("-Ptenshu.versionName=$VERSION_NAME")

# ⚠ No configuration cache: an entry would persist the signing passwords under .gradle/.
./gradlew "${TASKS[@]}" "${PROPS[@]}" --no-configuration-cache

mkdir -p build/play
failed=0
for app in $APPS; do
  module="$(module_of "$app")"
  aab="$module/build/outputs/bundle/release/$module-release.aab"
  [[ -f "$aab" ]] || { echo "✗ $app: no bundle at $aab" >&2; failed=1; continue; }
  actual="$(keytool -printcert -jarfile "$aab" | fingerprint)"
  if [[ "$actual" != "$EXPECTED" ]]; then
    echo "✗ $app: signed by $actual, not by the release key" >&2; failed=1; continue
  fi
  if ! python3 -I scripts/lib/check_16kb_alignment.py "$aab"; then failed=1; continue; fi
  if ! python3 -I scripts/lib/check_jni_classes.py "$aab"; then failed=1; continue; fi
  # ⚠ A dependency can merge INTERNET into a client (ML Kit did, into Drive); only Control may hold it.
  if ! python3 -I scripts/lib/check_client_no_internet.py --variant release "$app"; then failed=1; continue; fi
  # ⚠ Not `grep -q`: it stops reading at the first match, unzip dies of SIGPIPE, and under
  # `pipefail` the pipeline then fails on a bundle that HAS symbols.
  if ! unzip -Z1 "$aab" | grep "BUNDLE-METADATA/com.android.tools.build.debugsymbols/" >/dev/null; then
    echo "✗ $app: no native debug symbols — install the NDK pinned as ndkVersion in build.gradle.kts;" \
      "if it was just installed, delete $module/build/intermediates/stripped_native_libs and rerun" >&2
    failed=1; continue
  fi
  cp "$aab" "build/play/$app-$VERSION_CODE.aab"
  echo "✓ $app → build/play/$app-$VERSION_CODE.aab"
done
[[ $failed -eq 0 ]] || die "at least one bundle is not uploadable — see above."
echo "Signed by: $EXPECTED"

[[ -n "$TRACK" ]] || exit 0
for app in $APPS; do
  python3 -I scripts/lib/play_publish.py upload --track "$TRACK" \
    "app.tenshu.$app.android" "build/play/$app-$VERSION_CODE.aab"
done
