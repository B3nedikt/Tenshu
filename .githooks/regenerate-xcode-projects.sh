#!/usr/bin/env bash
# Regenerate the (gitignored) iOS Xcode projects from their project.yml via XcodeGen.
#
# Why: XcodeGen resolves `sources: - path: Sources` at *generate time* and lists each
# file individually in the .pbxproj. A branch switch / merge that adds or removes a Swift
# file therefore leaves a stale project that's missing (or still references) a file — the
# classic symptom being "<Type> not found in scope" in a sibling file that *is* compiled.
# Regenerating on those git events keeps the projects in sync automatically.
#
# Invoked by .githooks/post-{checkout,merge}. Install with `./gradlew installGitHooks`
# (or `git config core.hooksPath .githooks`). The matching `./gradlew generateXcodeProjects`
# is the manual / CI entry point.
#
# Optional args: <oldSha> <newSha> — when both are given, regeneration is skipped unless
# something under an iOS app dir actually changed between them (avoids churn on every switch).
set -euo pipefail

# GUI git clients (Xcode, Tower, Fork, …) don't source your shell profile, so xcodegen
# (a Homebrew binary) may be off PATH. Prepend the usual Homebrew locations before lookup.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

# No xcodegen on this machine (a non-iOS dev, or CI without it) → quietly do nothing so a
# checkout is never blocked.
command -v xcodegen >/dev/null 2>&1 || exit 0

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

# project.yml resolves ${TENSHU_TEAM_ID} at generate time — the [sdk=iphoneos*] device
# signing team (ios-signing.md §2). Resolve like the macOS -Ptenshu.teamId flow: explicit
# env wins, else local.properties. Absent everywhere → empty team (simulator builds are
# unaffected; only a real-device build would then need a regenerate with the env set).
if [ -z "${TENSHU_TEAM_ID:-}" ] && [ -f local.properties ]; then
  TENSHU_TEAM_ID="$(sed -n 's/^tenshu\.teamId=//p' local.properties | head -1)"
fi
export TENSHU_TEAM_ID="${TENSHU_TEAM_ID:-}"

# ⚠ Every iOS app dir with a project.yml belongs here. `contacts-ios` was missing — it shipped
# without being added, which is exactly the failure this script exists to prevent (a branch switch
# leaves its project listing files that no longer exist, and the build fails somewhere else).
# `inference-harness-ios` is the IN-3m-iOS measurement harness: a project like the others, never shipped.
apps=(control-ios drive-ios timeline-ios fit-ios home-ios mia-ios maps-ios contacts-ios calendar-ios photos-ios inference-harness-ios)

# Change-detection: with a valid old..new range, skip when no iOS app dir changed. The
# all-zero SHA is git's "no previous commit" sentinel (e.g. initial checkout) → don't skip.
zero="0000000000000000000000000000000000000000"
if [ "$#" -ge 2 ] && [ -n "${1:-}" ] && [ "$1" != "$zero" ]; then
  if git diff --quiet "$1" "$2" -- "${apps[@]}" 2>/dev/null; then
    exit 0
  fi
fi

for app in "${apps[@]}"; do
  if [ -f "$app/project.yml" ]; then
    ( cd "$app" && xcodegen generate >/dev/null ) && echo "xcodegen: regenerated $app"
  fi
done
