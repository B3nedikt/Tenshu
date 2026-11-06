#!/usr/bin/env bash
#
# Publish the notarized macOS .dmg of all ten desktop apps to the release bucket and record them in
# the repo (desktop-macos-distribution.md §2.7 "Hosting"; runbook: docs/how-to/macos-release.md §7).
#
#   scripts/macos-direct-publish.sh --dry-run            # check + show the manifest diff; no network, no writes
#   scripts/macos-direct-publish.sh                      # upload what is missing, then rewrite the manifest
#   scripts/macos-direct-publish.sh --verify             # download every .dmg the manifest names, check it
#   scripts/macos-direct-publish.sh --casks <out-dir>    # the tap's Casks/*.rb from the committed manifest
#   scripts/macos-direct-publish.sh --dry-run --casks <out-dir>   # …from the manifest this run would write
#
# Reads   build/macos-release/ as scripts/macos-release.sh leaves it: <Bundle>-<version>.dmg × 10 and
#         SHA256SUMS. ⚠ Never build/macos-release-test/ (--pipeline-test: not notarized).
# ⚠ "No network" for --dry-run means no R2: `spctl` may ask Apple about the notarization.
# Uploads s3://$RELEASES_BUCKET/sha256/<hex>.dmg → https://releases.tenshu.app/sha256/<hex>.dmg, with
#           Content-Type: application/x-apple-diskimage
#           Cache-Control: public, max-age=31536000, immutable
#           Content-Disposition: attachment; filename="<Bundle>-<version>.dmg"
# Writes  store/macos-release.json (committed; the casks and, later, the download page read it).
#
# ⚠⚠ REFUSES, before any network: a SHA256SUMS that is not exactly the ten apps at one version, a
# .dmg whose bytes differ from it, one that `stapler validate` or Gatekeeper (`spctl … context:
# primary-signature`) does not accept as Notarized Developer ID, an app inside that is not the
# catalogue's bundle id and version or not signed by the team's Developer ID, apps from more than one
# build, a version lower than the published one, and a published version with other bytes.
#
# ⚠⚠ OBJECTS FIRST, MANIFEST LAST, as in scripts/android-direct-publish.sh: a failed upload leaves the
# old manifest untouched.
#
# Environment (all optional) — the same bucket, endpoint and key as the Android direct release:
#   RELEASES_ENDPOINT   R2 S3 endpoint. Default: `terraform -chdir=deploy/cloudflare-releases output
#                       -raw s3_endpoint` (works in the checkout that holds the module's state).
#   RELEASES_BUCKET     default tenshu-releases     RELEASES_BASE_URL  default https://releases.tenshu.app/sha256/
#   RELEASES_R2_ACCESS_KEY_ID / RELEASES_R2_SECRET_ACCESS_KEY — else the Keychain:
#                       r2-releases-access-key-id / r2-releases-secret-access-key (account tenshu).
#                       ⚠ Deliberately not R2_ACCESS_KEY_ID, the assets pair's name. Never printed.

set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

MODE=publish
DIR=build/macos-release
CASKS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run|--verify)
      [ "$MODE" = publish ] || { echo "macos-direct-publish: --dry-run and --verify do not combine" >&2; exit 2; }
      MODE="${1#--}" ;;
    --casks)
      [ $# -ge 2 ] || { echo "macos-direct-publish: --casks needs an output directory" >&2; exit 2; }
      CASKS="$2"; shift ;;
    -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
    -*) echo "macos-direct-publish: unknown option '$1'" >&2; exit 2 ;;
    *) DIR="$1" ;;
  esac
  shift
done

[ "$MODE" != verify ] || [ -z "$CASKS" ] || { echo "macos-direct-publish: --verify does not render casks" >&2; exit 2; }

RELEASES_BUCKET="${RELEASES_BUCKET:-tenshu-releases}"
RELEASES_BASE_URL="${RELEASES_BASE_URL:-https://releases.tenshu.app/sha256/}"
DMG_TYPE="application/x-apple-diskimage"
CACHE_CONTROL="public, max-age=31536000, immutable"
MANIFEST=store/macos-release.json
HELPER=scripts/lib/macos_publish.py

log() { echo "macos-direct-publish: $*"; }
die() { echo "macos-direct-publish: $*" >&2; exit 1; }

# ── --casks alone: from the committed manifest ───────────────────────────────
if [ "$MODE" = publish ] && [ -n "$CASKS" ]; then
  [ -f "$MANIFEST" ] || die "no $MANIFEST yet — publish first, or use --dry-run --casks to render from a plan"
  python3 -I "$HELPER" casks "$MANIFEST" "$CASKS"
  exit 0
fi

# ── --verify ─────────────────────────────────────────────────────────────────
# Every .dmg the committed manifest names must download from the public URL with the recorded bytes
# and hash and the three headers. ⚠ The whole file, not a HEAD: R2 does not check that a key matches
# its bytes, so only the bytes prove the object was not replaced (threat model T-MD-01). Public, no
# credentials.
if [ "$MODE" = verify ]; then
  command -v curl >/dev/null 2>&1 || die "curl is not on PATH"
  urls="$(python3 -I "$HELPER" urls)" || die "$MANIFEST does not check"
  TMPV="$(mktemp -d "${TMPDIR:-/tmp}/macos-verify.XXXXXX")"
  trap 'rm -rf "$TMPV"' EXIT
  header() { awk -F': *' -v h="$1" 'tolower($1)==h {sub(/\r$/, "", $2); v=$2} END {print v}' "$TMPV/headers"; }
  failed=0 checked=0
  while IFS=$'\t' read -r app url sha bytes name; do
    [ -n "$app" ] || continue
    checked=$((checked + 1))
    problem=""
    status="$(curl -sS --max-time 900 -D "$TMPV/headers" -o "$TMPV/body" -w '%{http_code}' "$url" 2>&1 || true)"
    if [ "$status" != 200 ]; then
      problem="HTTP ${status:-no answer}"
    else
      got_sha="$(shasum -a 256 "$TMPV/body" | cut -d' ' -f1)"
      got_bytes="$(wc -c < "$TMPV/body" | tr -d ' ')"
      [ "$got_sha" = "$sha" ] || problem="sha256 $got_sha, manifest says $sha"
      [ -n "$problem" ] || [ "$got_bytes" = "$bytes" ] || problem="$got_bytes bytes, manifest says $bytes"
      [ -n "$problem" ] || [ "$(header content-type)" = "$DMG_TYPE" ] || problem="Content-Type '$(header content-type)'"
      # As android-direct-publish.sh: the edge may rewrite Cache-Control's spelling; `immutable` is the part that matters.
      [ -n "$problem" ] || case "$(header cache-control)" in *immutable*) ;; *) problem="Cache-Control '$(header cache-control)'" ;; esac
      [ -n "$problem" ] || [ "$(header content-disposition)" = "attachment; filename=\"$name\"" ] \
        || problem="Content-Disposition '$(header content-disposition)'"
    fi
    if [ -n "$problem" ]; then
      echo "  FAIL $app — $url — $problem"
      failed=$((failed + 1))
    else
      echo "  ok   $app $name"
    fi
  done <<< "$urls"
  [ "$failed" = 0 ] || die "$failed of $checked .dmg are not served correctly — do not point a cask or page at this manifest"
  log "verified $checked .dmg"
  exit 0
fi

# ── check + plan (no network) ────────────────────────────────────────────────
[ -d "$DIR" ] || die "no such directory: $DIR (run scripts/macos-release.sh first)"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/macos-publish.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
log "checking $DIR (stapler, Gatekeeper, and each app read from a read-only mount)"
python3 -I "$HELPER" plan "$DIR" "$RELEASES_BASE_URL" "$STAGE" \
  || die "nothing was uploaded and $MANIFEST is untouched"

show_diff() {
  if [ -f "$MANIFEST" ]; then
    diff -u "$MANIFEST" "$STAGE/macos-release.json" || true
  else
    echo "--- (new) $MANIFEST"
    cat "$STAGE/macos-release.json"
  fi
}

if [ "$MODE" = dry-run ]; then
  log "dry run — would upload to s3://$RELEASES_BUCKET/ (those already there are skipped); no network, nothing written"
  while IFS=$'\t' read -r _sha file key name _bytes; do echo "  $key  ← ${file#"$ROOT"/}  ($name)"; done < "$STAGE/objects.tsv"
  show_diff
  if [ -n "$CASKS" ]; then
    python3 -I "$HELPER" casks "$STAGE/macos-release.json" "$CASKS"
    log "⚠ these casks name the PLANNED manifest: do not push them to the tap before a real publish"
  fi
  exit 0
fi

# ── R2 access ────────────────────────────────────────────────────────────────
keychain() { security find-generic-password -a tenshu -s "$1" -w 2>/dev/null || true; }
command -v aws >/dev/null 2>&1 || die "the 'aws' CLI is not on PATH (brew install awscli)"
if [ -z "${RELEASES_ENDPOINT:-}" ]; then
  RELEASES_ENDPOINT="$(terraform -chdir=deploy/cloudflare-releases output -raw s3_endpoint 2>/dev/null || true)"
fi
case "${RELEASES_ENDPOINT:-}" in
  https://*.r2.cloudflarestorage.com) ;;
  "") die "no R2 endpoint: set RELEASES_ENDPOINT, or run from the checkout where deploy/cloudflare-releases was applied" ;;
  *) die "RELEASES_ENDPOINT=$RELEASES_ENDPOINT is not an R2 endpoint (https://<account>.r2.cloudflarestorage.com)" ;;
esac
id="${RELEASES_R2_ACCESS_KEY_ID:-$(keychain r2-releases-access-key-id)}"
secret="${RELEASES_R2_SECRET_ACCESS_KEY:-$(keychain r2-releases-secret-access-key)}"
[ -n "$id" ] && [ -n "$secret" ] \
  || die "no R2 key pair — export RELEASES_R2_ACCESS_KEY_ID/RELEASES_R2_SECRET_ACCESS_KEY or store r2-releases-access-key-id / r2-releases-secret-access-key in the Keychain (deploy/cloudflare-releases/README.md)"
unset AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
export AWS_ACCESS_KEY_ID="$id" AWS_SECRET_ACCESS_KEY="$secret"
# ⚠ R2's region is `auto`, and R2 implements no ACLs — no --acl anywhere below.
AWS=(aws --endpoint-url "$RELEASES_ENDPOINT" --region auto)

"${AWS[@]}" s3api head-bucket --bucket "$RELEASES_BUCKET" >/dev/null 2>&1 \
  || die "bucket '$RELEASES_BUCKET' does not exist (or this key cannot see it) — it is created by deploy/cloudflare-releases (./tf.sh apply), which waits for the maintainer's OK. Nothing was uploaded; $MANIFEST is untouched."

# ── objects first ────────────────────────────────────────────────────────────
# An object already there is kept only if its size AND all three headers are right. ⚠ A REPAIR fixes
# the bucket, not a copy already cached with `immutable`: purge that URL by hand in the dashboard.
count="$(wc -l < "$STAGE/objects.tsv" | tr -d ' ')"
want() { printf '%s\t%s\t%s\t%s' "$1" "$DMG_TYPE" "$CACHE_CONTROL" "attachment; filename=\"$2\""; }
head_of() {
  "${AWS[@]}" s3api head-object --bucket "$RELEASES_BUCKET" --key "$1" \
    --query '[ContentLength, ContentType, CacheControl, ContentDisposition]' --output text 2>/dev/null || true
}
log "uploading $count .dmg to s3://$RELEASES_BUCKET ($RELEASES_ENDPOINT)"
while IFS=$'\t' read -r _sha file key name bytes; do
  have="$(head_of "$key")"
  if [ "$have" = "$(want "$bytes" "$name")" ]; then
    echo "  have   $key ($name)"
    continue
  fi
  [ -z "$have" ] || echo "  REPAIR $key (was: $have)"
  "${AWS[@]}" s3api put-object --bucket "$RELEASES_BUCKET" --key "$key" --body "$file" \
    --content-type "$DMG_TYPE" --cache-control "$CACHE_CONTROL" \
    --content-disposition "attachment; filename=\"$name\"" >/dev/null \
    || die "upload of $key failed — $MANIFEST is untouched"
  [ "$(head_of "$key")" = "$(want "$bytes" "$name")" ] \
    || die "$key reads back as '$(head_of "$key")' after the upload — $MANIFEST is untouched"
  echo "  put    $key ($name)"
done < "$STAGE/objects.tsv"

# ── every object hashed back ─────────────────────────────────────────────────
# ⚠⚠ R2 does not check that a key matches its bytes, so size + headers cannot tell a same-size
# replacement (T-MD-01). Each object is downloaded and hashed before the manifest may point at it.
log "hashing $count object(s) back from the bucket"
while IFS=$'\t' read -r sha _file key name _bytes; do
  "${AWS[@]}" s3api get-object --bucket "$RELEASES_BUCKET" --key "$key" "$STAGE/readback" >/dev/null \
    || die "could not read $key back — $MANIFEST is untouched"
  got="$(shasum -a 256 "$STAGE/readback" | cut -d' ' -f1)"
  [ "$got" = "$sha" ] || die "$key in the bucket hashes to $got, not $sha — the object was replaced or truncated. Investigate (a leaked r2-releases key?) before anything else; $MANIFEST is untouched"
  rm -f "$STAGE/readback"
  echo "  ok     $key ($name)"
done < "$STAGE/objects.tsv"

# ── manifest last ────────────────────────────────────────────────────────────
show_diff
mkdir -p "$(dirname "$MANIFEST")"
cp "$STAGE/macos-release.json" "$MANIFEST"
python3 -I "$HELPER" check
log "done — commit $MANIFEST, check the public side with --verify, then regenerate the tap's casks (--casks <tap checkout>)"
