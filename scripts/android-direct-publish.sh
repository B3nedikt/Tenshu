#!/usr/bin/env bash
#
# Publish a direct (outside-Play) Android release to the release bucket and record it in the repo
# (android-direct-apk-distribution.md Phase 1 item 4; runbook: docs/how-to/android-release.md
# "Publishing a direct release").
#
#   scripts/android-direct-publish.sh [<dir>] --dry-run   # check + show the manifest diff; no network, no writes
#   scripts/android-direct-publish.sh [<dir>]             # upload what is missing, then rewrite the manifest
#   scripts/android-direct-publish.sh --verify            # download every APK the manifest names, check it
#
# Reads   <dir> (default build/direct/), as scripts/android-release.sh --direct leaves it:
#         <app>-<versionCode>.apk, release.json, SHA256SUMS, signing-cert-sha256.txt
# Uploads s3://$RELEASES_BUCKET/sha256/<hex>.apk → https://releases.tenshu.app/sha256/<hex>.apk, with
#           Content-Type: application/vnd.android.package-archive
#           Cache-Control: public, max-age=31536000, immutable
#           Content-Disposition: attachment; filename="tenshu-<app>-<versionCode>.apk"
# Writes  store/android-release.json (committed; the download pages read it at build time). Apps not
#         in this release keep their entries.
#
# ⚠⚠ REFUSES, before any network: build/direct-unpinned/ (an --allow-unpinned build), an empty
# side-channel pin, a directory whose signer is not FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256
# (read back from every APK with apksigner, not only from release.json), an APK that no longer matches
# release.json / SHA256SUMS, a versionCode lower than the one published, and a change of signing key.
#
# ⚠⚠ OBJECTS FIRST, MANIFEST LAST. The manifest is the pointer: a download page built from it links
# every URL it names, so it is rewritten only after every object it will name is in the bucket with
# the right headers. A failed upload leaves the old manifest untouched.
#
# Environment (all optional):
#   RELEASES_ENDPOINT   R2 S3 endpoint. Default: `terraform -chdir=deploy/cloudflare-releases output
#                       -raw s3_endpoint` (works in the checkout that holds the module's state).
#   RELEASES_BUCKET     default tenshu-releases     RELEASES_BASE_URL  default https://releases.tenshu.app/sha256/
#   RELEASES_R2_ACCESS_KEY_ID / RELEASES_R2_SECRET_ACCESS_KEY — else the Keychain:
#                       r2-releases-access-key-id / r2-releases-secret-access-key (account tenshu).
#                       ⚠ Deliberately not R2_ACCESS_KEY_ID, the assets pair's name (D3). Never printed.
#   ANDROID_HOME        for apksigner (default ~/Library/Android/sdk)

set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

MODE=publish
DIR=build/direct
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) MODE=dry-run ;;
    --verify) MODE=verify ;;
    -h|--help) sed -n '2,37p' "$0"; exit 0 ;;
    -*) echo "android-direct-publish: unknown option '$1'" >&2; exit 2 ;;
    *) DIR="$1" ;;
  esac
  shift
done

RELEASES_BUCKET="${RELEASES_BUCKET:-tenshu-releases}"
RELEASES_BASE_URL="${RELEASES_BASE_URL:-https://releases.tenshu.app/sha256/}"
APK_TYPE="application/vnd.android.package-archive"
CACHE_CONTROL="public, max-age=31536000, immutable"
MANIFEST=store/android-release.json

log() { echo "android-direct-publish: $*"; }
die() { echo "android-direct-publish: $*" >&2; exit 1; }

# ── --verify ─────────────────────────────────────────────────────────────────
# Every APK the committed manifest names must download from the public URL with the recorded bytes
# and hash and the three headers. ⚠ The whole file, not a HEAD: R2 does not check that a key matches
# its hash, so only the bytes prove the object was not replaced (threat model T-DA-01). Public, no
# credentials.
if [ "$MODE" = verify ]; then
  command -v curl >/dev/null 2>&1 || die "curl is not on PATH"
  # ⚠ Into a variable first, not `< <(…)`: a process substitution's exit status is lost, and a
  # manifest that does not parse would then "verify" as zero APKs.
  urls="$(python3 -I scripts/lib/direct_publish.py urls)" || die "$MANIFEST does not check"
  TMPV="$(mktemp -d "${TMPDIR:-/tmp}/direct-verify.XXXXXX")"
  trap 'rm -rf "$TMPV"' EXIT
  failed=0 checked=0
  while IFS=$'\t' read -r app url sha bytes name; do
    [ -n "$app" ] || continue
    checked=$((checked + 1))
    problem=""
    status="$(curl -sS --max-time 600 -D "$TMPV/headers" -o "$TMPV/body" -w '%{http_code}' "$url" 2>&1 || true)"
    header() { awk -F': *' -v h="$1" 'tolower($1)==h {sub(/\r$/, "", $2); v=$2} END {print v}' "$TMPV/headers"; }
    if [ "$status" != 200 ]; then
      problem="HTTP ${status:-no answer}"
    else
      got_sha="$(shasum -a 256 "$TMPV/body" | cut -d' ' -f1)"
      got_bytes="$(wc -c < "$TMPV/body" | tr -d ' ')"
      [ "$got_sha" = "$sha" ] || problem="sha256 $got_sha, manifest says $sha"
      [ -n "$problem" ] || [ "$got_bytes" = "$bytes" ] || problem="$got_bytes bytes, manifest says $bytes"
      [ -n "$problem" ] || [ "$(header content-type)" = "$APK_TYPE" ] || problem="content-type '$(header content-type)'"
      [ -n "$problem" ] || case "$(header cache-control)" in *immutable*) ;; *) problem="cache-control '$(header cache-control)'" ;; esac
      [ -n "$problem" ] || [ "$(header content-disposition)" = "attachment; filename=\"$name\"" ] \
        || problem="content-disposition '$(header content-disposition)'"
    fi
    if [ -n "$problem" ]; then
      echo "  FAIL $app — $url — $problem"
      failed=$((failed + 1))
    else
      echo "  ok   $app $name"
    fi
  done <<< "$urls"
  [ "$failed" = 0 ] || die "$failed of $checked APKs are not served correctly — do not ship pages built from this manifest"
  log "verified $checked APKs"
  exit 0
fi

# ── check + plan (no network) ────────────────────────────────────────────────
[ -d "$DIR" ] || die "no such directory: $DIR (run scripts/android-release.sh --direct first)"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/direct-publish.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
python3 -I scripts/lib/direct_publish.py plan "$DIR" "$RELEASES_BASE_URL" "$STAGE" \
  || die "nothing was uploaded and $MANIFEST is untouched"

show_diff() {
  if [ -f "$MANIFEST" ]; then
    diff -u "$MANIFEST" "$STAGE/android-release.json" || true
  else
    echo "--- (new) $MANIFEST"
    cat "$STAGE/android-release.json"
  fi
}

if [ "$MODE" = dry-run ]; then
  log "dry run — would upload to s3://$RELEASES_BUCKET/ (those already there are skipped); no network, nothing written"
  while IFS=$'\t' read -r _sha file key name _bytes; do echo "  $key  ← ${file#"$ROOT"/}  ($name)"; done < "$STAGE/objects.tsv"
  show_diff
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
# Exported into THIS process only (and the aws children), never the caller's shell. A session token or
# profile from another store would be sent along and break R2's signature check.
unset AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
export AWS_ACCESS_KEY_ID="$id" AWS_SECRET_ACCESS_KEY="$secret"
# ⚠ R2's region is `auto`, and R2 implements no ACLs — no --acl anywhere below.
AWS=(aws --endpoint-url "$RELEASES_ENDPOINT" --region auto)

# ⚠⚠ Refuses on a bucket that does not exist yet: the Terraform apply is the maintainer's call, and a
# PUT against a missing bucket would otherwise read as a credentials problem.
"${AWS[@]}" s3api head-bucket --bucket "$RELEASES_BUCKET" >/dev/null 2>&1 \
  || die "bucket '$RELEASES_BUCKET' does not exist (or this key cannot see it) — it is created by deploy/cloudflare-releases (./tf.sh apply), which waits for the maintainer's OK. Nothing was uploaded; $MANIFEST is untouched."

# ── objects first ────────────────────────────────────────────────────────────
# An object already there is kept only if its size AND all three headers are right: the headers are
# the object's own, so one uploaded without Content-Disposition would be served under its hash.
# ⚠ A REPAIR fixes the bucket, not a copy already cached with `immutable` (Cloudflare's edge, a
# browser): purge that URL by hand in the Cloudflare dashboard (Caching → Purge → Custom URL).
# The bytes are not trusted from size alone either: every object is hashed back below, before the
# manifest may name it.
count="$(wc -l < "$STAGE/objects.tsv" | tr -d ' ')"
log "uploading $count APK(s) to s3://$RELEASES_BUCKET ($RELEASES_ENDPOINT)"
while IFS=$'\t' read -r _sha file key name bytes; do
  disposition="attachment; filename=\"$name\""
  have="$("${AWS[@]}" s3api head-object --bucket "$RELEASES_BUCKET" --key "$key" \
    --query '[ContentLength, ContentType, CacheControl, ContentDisposition]' --output text 2>/dev/null || true)"
  if [ "$have" = "$(printf '%s\t%s\t%s\t%s' "$bytes" "$APK_TYPE" "$CACHE_CONTROL" "$disposition")" ]; then
    echo "  have   $key ($name)"
    continue
  fi
  [ -z "$have" ] || echo "  REPAIR $key (was: $have)"
  "${AWS[@]}" s3api put-object --bucket "$RELEASES_BUCKET" --key "$key" --body "$file" \
    --content-type "$APK_TYPE" --cache-control "$CACHE_CONTROL" \
    --content-disposition "$disposition" >/dev/null \
    || die "upload of $key failed — $MANIFEST is untouched"
  # Read back: a PUT that "succeeded" against the wrong bucket or with a mangled header must not be
  # pointed at.
  have="$("${AWS[@]}" s3api head-object --bucket "$RELEASES_BUCKET" --key "$key" \
    --query '[ContentLength, ContentType, CacheControl, ContentDisposition]' --output text 2>/dev/null || true)"
  [ "$have" = "$(printf '%s\t%s\t%s\t%s' "$bytes" "$APK_TYPE" "$CACHE_CONTROL" "$disposition")" ] \
    || die "$key reads back as '$have' after the upload — $MANIFEST is untouched"
  echo "  put    $key ($name)"
done < "$STAGE/objects.tsv"

# ── every object hashed back ─────────────────────────────────────────────────
# ⚠⚠ R2 does not check that a key matches its bytes, so "have" (size + headers) cannot tell a
# same-size replacement (threat model T-DA-01), and a file could change between the check and the
# PUT. Each object this release names is downloaded through the S3 API and hashed before the
# manifest is allowed to point at it.
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
cp "$STAGE/android-release.json" "$MANIFEST"
python3 -I scripts/lib/direct_publish.py check
log "done — commit $MANIFEST. Check the public side with: scripts/android-direct-publish.sh --verify"
