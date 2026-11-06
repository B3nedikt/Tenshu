#!/bin/sh
# Build + dev-sign keychain-helper.app for the desktop MasterKeyStore Phase 2
# Touch ID / login-password unlock (docs/proposals/desktop-masterkeystore.md §4).
#
# A free (personal) Apple team is enough — the macOS data-protection keychain
# needs an embedded provisioning profile. A loose `swiftc` binary is killed by
# AMFI (no profile), so this Xcode target is the path for BOTH free-team dev
# signing now and Developer ID later (same project, switch the signing config).
#
# IMPORTANT — mint the profile in the Xcode GUI ONCE first:
#   open KeychainHelper.xcodeproj → target keychain-helper → Signing & Capabilities
#   → set Team → build (⌘B). The CLI cannot mint a free-team profile (xcodebuild
#   -allowProvisioningUpdates errors "No Account for Team"), so this script signs
#   OFFLINE against the GUI-minted profile and deliberately omits that flag.
#
# Usage:
#   TEAM=<10-char Team ID> ./build.sh           # e.g. from: security find-identity -v -p codesigning
#   ./build.sh <TEAM>
#
# Then point the JVM at the inner binary:
#   export TENSHU_KEYCHAIN_HELPER="<printed path>"
#   ./gradlew :control-desktop:run
set -e

TEAM="${1:-${TEAM:-}}"
if [ -z "$TEAM" ]; then
	echo "error: set your 10-char Apple Team ID, e.g. TEAM=ABCDE12345 ./build.sh" >&2
	echo "       it's the OU of your signing cert (NOT the value in the cert-name parens):" >&2
	echo "       security find-certificate -c 'Apple Development' -p | openssl x509 -noout -subject" >&2
	exit 1
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$DIR/build}"
CONFIG="${CONFIG:-Release}"

xcodebuild \
	-project "$DIR/KeychainHelper.xcodeproj" \
	-target keychain-helper \
	-configuration "$CONFIG" \
	DEVELOPMENT_TEAM="$TEAM" \
	CODE_SIGN_STYLE=Automatic \
	SYMROOT="$OUT" \
	build

APP="$OUT/$CONFIG/keychain-helper.app"
echo
echo "Built + signed: $APP"
echo
echo "Point the JVM at the inner binary:"
echo "  export TENSHU_KEYCHAIN_HELPER=\"$APP/Contents/MacOS/keychain-helper\""
echo
echo "Smoke-test (enroll does NOT prompt; unwrap shows the Touch ID sheet):"
echo "  head -c32 /dev/urandom | base64 | \"\$TENSHU_KEYCHAIN_HELPER\" enroll \"Tenshu test\""
echo "  \"\$TENSHU_KEYCHAIN_HELPER\" unwrap \"Tenshu test\""
echo "  \"\$TENSHU_KEYCHAIN_HELPER\" clear"
