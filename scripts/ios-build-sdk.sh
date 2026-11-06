#!/usr/bin/env bash
# Build the Kotlin framework an iOS app build is about to link. Run by each app's Xcode
# prebuild phase (which sets TENSHU_SDK_VARIANT and PLATFORM_NAME) and by scripts/ios-archive.sh.
#
#   scripts/ios-build-sdk.sh control    that app's own framework, `:tenshu-sdk-ios-control`
#   scripts/ios-build-sdk.sh drive      likewise, `:tenshu-sdk-ios-drive`, and so on for all ten apps
#                                       (docs/proposals/ios-per-app-frameworks.md)
#
# ⚠ The app is required. With no argument this used to build the shared umbrella `:tenshu-sdk-ios`,
# which every app has left and P5 removed. It is an error now, not "build all ten": every caller
# (the Xcode prebuilds, ios-archive.sh) names its app, and ten framework links behind a missing
# argument would be a slow way to hide a broken caller. To compile every app, run
# `scripts/ios-smoke.sh --build-only`; to typecheck every app's Kotlin, `./gradlew iosKotlinCheck`.
#
# TENSHU_SDK_VARIANT:
#   (unset) / debug   ONE debug framework, for the platform being built (D5: one mechanism, debug
#                     and release, one slice).
#   release           ONE release framework, for the platform being built.
#
# ⚠⚠ The release link is whole-program optimisation and does not fit the repo's default
# Gradle heap: both slices linked together at -Xmx5g fail with `Java heap space` (measured
# 2026-10-13). So `release` links a single slice and asks for a larger daemon, here and only
# here — raising `org.gradle.jvmargs` in gradle.properties would raise it for every build.
set -euo pipefail

cd "$(dirname "$0")/.."

APPS="control drive calendar contacts home mia maps photos timeline fit"
APP="${1:-}"
case "$APP" in
  control|drive|calendar|contacts|home|mia|maps|photos|timeline|fit) module=":tenshu-sdk-ios-${APP}" ;;
  "") echo "ios-build-sdk: name the app whose framework to build (one of: $APPS)" >&2; exit 2 ;;
  *) echo "ios-build-sdk: '$APP' is not an iOS app with a framework (one of: $APPS)" >&2; exit 2 ;;
esac

case "${PLATFORM_NAME:-iphoneos}" in
  iphonesimulator) target=IosSimulatorArm64 ;;
  *) target=IosArm64 ;;
esac

# The framework's open-source licence notices ride the same Gradle run: the `OssLicences` XcodeGen
# template (`ios-about/about.yml`) copies the JSON into the app bundle (docs/oss-licences.md).
licences="${module}:exportLibraryDefinitions"

if [ "${TENSHU_SDK_VARIANT:-debug}" != "release" ]; then
  exec ./gradlew "${module}:linkDebugFramework${target}" "$licences"
fi

exec ./gradlew "${module}:linkReleaseFramework${target}" "$licences" \
  "-Dorg.gradle.jvmargs=-Xmx10g -Dfile.encoding=UTF-8"
