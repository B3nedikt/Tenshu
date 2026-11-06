#!/usr/bin/env bash
#
# Push an app's store listing — the copy and screenshots under store/ — to App Store Connect with
# fastlane deliver (store-presence-and-support-pages.md §2.2, D7; runbook docs/how-to/store-listings.md).
#
#   scripts/store-push.sh <app> [--ios] --dry-run     check, assemble, fetch screenshots, render deliver's
#                                                     HTML preview. Offline apart from the public screenshot
#                                                     URLs; no credentials; writes NOTHING to App Store Connect.
#   scripts/store-push.sh <app> [--ios]               the push. Refuses a dirty tree. `force` only for exactly
#                                                     the files the last --dry-run previewed; otherwise deliver
#                                                     shows its own preview and asks before it writes.
#   … --no-screenshots                                text only: App Store Connect's screenshots stay as they are
#   … --review-info                                   also set App Review Information (contact + notes). Off by
#                                                     default: deliver then forces "Sign-in required" OFF, wiping the
#                                                     demo account entered by hand. Once per app, and after a notes edit.
#
#   <app>      control | drive | fit | calendar | contacts | photos | timeline | mia — PUSHED_APPS in scripts/store-listing.py.
#              maps is named but refused: its listing is copy only. Photos' landing
#              captures are never used as App Store screenshots; its listing.json names its App Store set.
#   --ios      the App Store (the default, and today the only store). --android is S5 (`supply`), not built.
#
# Never uploads a binary (scripts/ios-archive.sh does), never submits for review, never releases.
#
# Reads    store/shared/, store/<app>/ (scripts/store-listing.py has the layout), store/<app>/screenshots.manifest.json
#          ios-release/release.yml (MARKETING_VERSION — the App Store version the listing belongs to)
# Writes   build/store/deliver/<app>/ios/ (gitignored): metadata/, screenshots/<locale>/, Preview.html,
#          preview.stamp — and, without --dry-run, App Store Connect.
# Needs    Ruby ≥ 3.2 + `bundle install` (Gemfile); python3. For the push: ASC_KEY_ID, ASC_ISSUER_ID,
#          ASC_KEY_PATH — the same App Store Connect API key as ios-archive.sh (ios-testflight.md §2.1).

set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

APP=""
STORE_KIND=ios
DRY_RUN=0
SCREENSHOTS=true
REVIEW_INFO=false
while [ $# -gt 0 ]; do
  case "$1" in
    control|drive|fit|calendar|contacts|photos|mia|maps) [ -z "$APP" ] || { echo "store-push: one app per run" >&2; exit 2; }; APP="$1" ;;
    --ios) STORE_KIND=ios ;;
    --android) STORE_KIND=android ;;
    --dry-run) DRY_RUN=1 ;;
    --no-screenshots) SCREENSHOTS=false ;;
    --review-info) REVIEW_INFO=true ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "store-push: unknown argument '$1'" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$APP" ] || { echo "store-push: which app? (control | drive | fit | calendar | contacts | photos | timeline | mia)" >&2; exit 2; }

# fastlane needs a UTF-8 locale: under C/US-ASCII a non-ASCII value (an umlaut in the review contact)
# fails with "invalid byte sequence in US-ASCII".
export LC_ALL="${LC_ALL:-en_US.UTF-8}" LANG="${LANG:-en_US.UTF-8}"
case "$LC_ALL" in *UTF-8*|*utf8*) ;; *) export LC_ALL=en_US.UTF-8 ;; esac

log() { echo "store-push: $*"; }
die() { echo "store-push: $*" >&2; exit 1; }

# The structure is ready for Play: a second folder under build/store/deliver/<app>/, a `supply` lane,
# and store/<app>/<lang>/short_description.txt (design §2.1). Until S5 it refuses rather than half-works.
[ "$STORE_KIND" = ios ] || die "--android is phase S5 (fastlane supply, a Play service account) — not built yet"

# A listing that is not complete is refused before anything else; the script says what it lacks.
python3 scripts/store-listing.py pushable "$APP" || exit 1

if [ "$DRY_RUN" = 0 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  die "refusing to push from a tree with uncommitted changes — the listing must be what git has"
fi

command -v bundle >/dev/null 2>&1 || die "bundler is not on PATH — Ruby ≥ 3.2 and \`bundle install\` (docs/how-to/store-listings.md §1)"

# The copy, then the binaries guard: both fail before anything is assembled.
python3 scripts/check-store-assets.py >/dev/null || python3 scripts/check-store-assets.py
python3 scripts/store-listing.py check "$APP"

OUT="build/store/deliver/$APP/ios"
mkdir -p "$OUT"
python3 scripts/store-listing.py assemble "$APP" "$OUT"
if [ "$SCREENSHOTS" = true ]; then
  python3 scripts/store-listing.py screenshots "$APP" "$OUT"
else
  rm -rf "$OUT/screenshots"
  log "--no-screenshots: App Store Connect keeps the screenshots it has"
fi

DIGEST="$(python3 scripts/store-listing.py digest "$OUT")"

if [ "$DRY_RUN" = 1 ]; then
  rm -f "$OUT/Preview.html"
  bundle exec fastlane ios preview dir:"$OUT" screenshots:"$SCREENSHOTS"
  [ -f "$OUT/Preview.html" ] || die "fastlane wrote no Preview.html"
  # The stamp says "a person can have looked at exactly these files". A real push passes force only
  # while it matches; any change to the copy or a screenshot in between voids it.
  echo "$DIGEST" > "$OUT/preview.stamp"
  log "dry run — nothing was sent to App Store Connect. Open the preview:"
  echo "  open $OUT/Preview.html"
  log "then push exactly this: scripts/store-push.sh $APP$([ "$SCREENSHOTS" = true ] || echo ' --no-screenshots')"
  exit 0
fi

for name in ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_PATH; do
  [ -n "${!name:-}" ] || die "$name is not set — the App Store Connect API key (docs/how-to/ios-testflight.md §2.1)"
done
[ -f "$ASC_KEY_PATH" ] || die "ASC_KEY_PATH=$ASC_KEY_PATH does not exist"

if [ "$REVIEW_INFO" = true ]; then
  # The App Review contact (Apple-only, never on the product page). Personal data, so never in this
  # repo: ASC_REVIEW_* from the environment, else the macOS Keychain (account `tenshu`, services
  # asc-review-first-name / -last-name / -email / -phone). App Store Connect refuses review notes
  # without it, so all four are required. The demo account stays manual (store-listings.md §5).
  for field in first-name last-name email phone; do
    var="ASC_REVIEW_$(printf '%s' "$field" | tr '[:lower:]-' '[:upper:]_')"
    if [ -z "${!var:-}" ] && command -v security >/dev/null; then
      # ⚠ Not `-w`: it prints a value with any non-ASCII byte as bare hex ("Löbbecke" →
      # 4cc3b6626265636b65), which was pushed to App Store Connect verbatim. `-g` marks hex with `0x`.
      raw="$(security find-generic-password -a tenshu -s "asc-review-$field" -g 2>&1 >/dev/null \
        | sed -n 's/^password: //p' || true)"
      case "$raw" in
        0x*) value="$(printf '%s' "${raw#0x}" | cut -d' ' -f1 | xxd -r -p)" ;;
        \"*) value="${raw#\"}"; value="${value%\"}" ;;
        *) value="" ;;
      esac
      [ -n "$value" ] && export "$var=$value"
    fi
    [ -n "${!var:-}" ] || die "$var is not set and the Keychain has no asc-review-$field (docs/how-to/store-listings.md §5)"
  done
  log "--review-info: setting App Review Information — ⚠ this turns 'Sign-in required' OFF; re-enter the demo account by hand after"
fi

VERSION="$(sed -n 's/^ *MARKETING_VERSION: *"\{0,1\}\([0-9.]*\)"\{0,1\} *$/\1/p' ios-release/release.yml | head -1)"
[ -n "$VERSION" ] || die "no MARKETING_VERSION in ios-release/release.yml"

FORCE=false
if [ -f "$OUT/preview.stamp" ] && [ "$(cat "$OUT/preview.stamp")" = "$DIGEST" ]; then
  FORCE=true
  log "the files match the last preview ($OUT/Preview.html) — deliver will not ask again"
else
  log "no preview of exactly these files — deliver shows its own and asks before it writes (or run --dry-run first)"
fi

log "pushing $APP $VERSION to App Store Connect: metadata$([ "$SCREENSHOTS" = true ] && echo ' + screenshots'), no binary, no submission"
bundle exec fastlane ios push dir:"$OUT" version:"$VERSION" force:"$FORCE" screenshots:"$SCREENSHOTS" review_info:"$REVIEW_INFO"
log "done — App Store Connect has the listing of $(git rev-parse --short HEAD). The privacy label, pricing and availability are manual (docs/how-to/store-listings.md §5)."
