#!/usr/bin/env bash
#
# Publish the signed Windows MSIX of Control and Drive, and the public certificate they are signed
# with, to the release bucket and record them in the repo (desktop-windows-port.md §5.1 D6; runbook:
# docs/how-to/windows-release.md §3). The macOS twin is scripts/macos-direct-publish.sh, which this
# follows step for step.
#
#   scripts/windows-direct-publish.sh --dry-run    # check + show the manifest diff; no network, no writes
#   scripts/windows-direct-publish.sh              # upload what is missing, then rewrite the manifest
#   scripts/windows-direct-publish.sh --verify     # download every object the manifest names, check it
#
# Reads   build/windows-release/ as scripts/windows/windows-release.ps1 leaves it ON WINDOWS — copied to this
#         Mac's checkout at the same commit: Tenshu<App>-<version>.msix × 2, <Cert>.cer, release.json, SHA256SUMS.
# Uploads s3://$RELEASES_BUCKET/sha256/<hex>.msix|.cer → https://releases.tenshu.app/sha256/<hex>.<ext>, with
#           Content-Type: application/msix | application/pkix-cert
#           Cache-Control: public, max-age=31536000, immutable
#           Content-Disposition: attachment; filename="<file>"
# Writes  store/windows-release.json (committed; the tenshu.app Windows download pages read it).
#
# ⚠⚠ REFUSES, before any network (scripts/lib/windows_publish.py): a dirty or foreign-commit build, a
# SHA256SUMS that is not exactly the packages + certificate, bytes that differ from it, a package that is
# unsigned, signed without the published certificate, of another identity/publisher/version/architecture,
# a certificate whose subject is not the publisher (or the local test one), a downgrade, a published version
# with other bytes, and a publisher switch.
#
# ⚠⚠ OBJECTS FIRST, MANIFEST LAST, as in scripts/macos-direct-publish.sh: a failed upload leaves the old
# manifest untouched.
#
# Environment (all optional) — the same bucket, endpoint and key as the Android and macOS direct releases:
#   RELEASES_ENDPOINT   R2 S3 endpoint. Default: `terraform -chdir=deploy/cloudflare-releases output
#                       -raw s3_endpoint` (works in the checkout that holds the module's state).
#   RELEASES_BUCKET     default tenshu-releases     RELEASES_BASE_URL  default https://releases.tenshu.app/sha256/
#   RELEASES_R2_ACCESS_KEY_ID / RELEASES_R2_SECRET_ACCESS_KEY — else the Keychain:
#                       r2-releases-access-key-id / r2-releases-secret-access-key (account tenshu). Never printed.

set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

MODE=publish
DIR=build/windows-release
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run|--verify)
      [ "$MODE" = publish ] || { echo "windows-direct-publish: --dry-run and --verify do not combine" >&2; exit 2; }
      MODE="${1#--}" ;;
    -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
    -*) echo "windows-direct-publish: unknown option '$1'" >&2; exit 2 ;;
    *) DIR="$1" ;;
  esac
  shift
done

RELEASES_BUCKET="${RELEASES_BUCKET:-tenshu-releases}"
RELEASES_BASE_URL="${RELEASES_BASE_URL:-https://releases.tenshu.app/sha256/}"
CACHE_CONTROL="public, max-age=31536000, immutable"
MANIFEST=store/windows-release.json
HELPER=scripts/lib/windows_publish.py

log() { echo "windows-direct-publish: $*"; }
die() { echo "windows-direct-publish: $*" >&2; exit 1; }

# ── --verify ─────────────────────────────────────────────────────────────────
# Every object the committed manifest names must download from the public URL with the recorded bytes,
# hash and three headers. ⚠ The whole file, not a HEAD: R2 does not check that a key matches its bytes.
if [ "$MODE" = verify ]; then
  command -v curl >/dev/null 2>&1 || die "curl is not on PATH"
  urls="$(python3 -I "$HELPER" urls)" || die "$MANIFEST does not check"
  TMPV="$(mktemp -d "${TMPDIR:-/tmp}/windows-verify.XXXXXX")"
  trap 'rm -rf "$TMPV"' EXIT
  header() { awk -F': *' -v h="$1" 'tolower($1)==h {sub(/\r$/, "", $2); v=$2} END {print v}' "$TMPV/headers"; }
  failed=0 checked=0
  while IFS=$'\t' read -r what url sha bytes name ctype; do
    [ -n "$what" ] || continue
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
      [ -n "$problem" ] || [ "$(header content-type)" = "$ctype" ] || problem="Content-Type '$(header content-type)'"
      [ -n "$problem" ] || case "$(header cache-control)" in *immutable*) ;; *) problem="Cache-Control '$(header cache-control)'" ;; esac
      [ -n "$problem" ] || [ "$(header content-disposition)" = "attachment; filename=\"$name\"" ] \
        || problem="Content-Disposition '$(header content-disposition)'"
    fi
    if [ -n "$problem" ]; then
      echo "  FAIL $what — $url — $problem"
      failed=$((failed + 1))
    else
      echo "  ok   $what $name"
    fi
  done <<< "$urls"
  [ "$failed" = 0 ] || die "$failed of $checked objects are not served correctly — do not point a page at this manifest"
  log "verified $checked objects"
  exit 0
fi

# ── check + plan (no network) ────────────────────────────────────────────────
[ -d "$DIR" ] || die "no such directory: $DIR (build on Windows with scripts/windows/windows-release.ps1 and copy it here)"
command -v openssl >/dev/null 2>&1 || die "openssl is not on PATH (it reads the certificate and the package signatures)"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/windows-publish.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
log "checking $DIR (each package's manifest and signature, the certificate, the build's commit)"
python3 -I "$HELPER" plan "$DIR" "$RELEASES_BASE_URL" "$STAGE" \
  || die "nothing was uploaded and $MANIFEST is untouched"

show_diff() {
  if [ -f "$MANIFEST" ]; then
    diff -u "$MANIFEST" "$STAGE/windows-release.json" || true
  else
    echo "--- (new) $MANIFEST"
    cat "$STAGE/windows-release.json"
  fi
}

if [ "$MODE" = dry-run ]; then
  log "dry run — would upload to s3://$RELEASES_BUCKET/ (those already there are skipped); no network, nothing written"
  while IFS=$'\t' read -r _sha file key name _bytes ctype; do echo "  $key  ← ${file#"$ROOT"/}  ($name, $ctype)"; done < "$STAGE/objects.tsv"
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
unset AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
export AWS_ACCESS_KEY_ID="$id" AWS_SECRET_ACCESS_KEY="$secret"
# ⚠ R2's region is `auto`, and R2 implements no ACLs — no --acl anywhere below.
AWS=(aws --endpoint-url "$RELEASES_ENDPOINT" --region auto)

"${AWS[@]}" s3api head-bucket --bucket "$RELEASES_BUCKET" >/dev/null 2>&1 \
  || die "bucket '$RELEASES_BUCKET' does not exist (or this key cannot see it). Nothing was uploaded; $MANIFEST is untouched."

# ── objects first ────────────────────────────────────────────────────────────
# An object already there is kept only if its size AND all three headers are right. ⚠ A REPAIR fixes
# the bucket, not a copy already cached with `immutable`: purge that URL by hand in the dashboard.
count="$(wc -l < "$STAGE/objects.tsv" | tr -d ' ')"
want() { printf '%s\t%s\t%s\t%s' "$1" "$3" "$CACHE_CONTROL" "attachment; filename=\"$2\""; }
head_of() {
  "${AWS[@]}" s3api head-object --bucket "$RELEASES_BUCKET" --key "$1" \
    --query '[ContentLength, ContentType, CacheControl, ContentDisposition]' --output text 2>/dev/null || true
}
log "uploading $count object(s) to s3://$RELEASES_BUCKET ($RELEASES_ENDPOINT)"
while IFS=$'\t' read -r _sha file key name bytes ctype; do
  have="$(head_of "$key")"
  if [ "$have" = "$(want "$bytes" "$name" "$ctype")" ]; then
    echo "  have   $key ($name)"
    continue
  fi
  [ -z "$have" ] || echo "  REPAIR $key (was: $have)"
  "${AWS[@]}" s3api put-object --bucket "$RELEASES_BUCKET" --key "$key" --body "$file" \
    --content-type "$ctype" --cache-control "$CACHE_CONTROL" \
    --content-disposition "attachment; filename=\"$name\"" >/dev/null \
    || die "upload of $key failed — $MANIFEST is untouched"
  [ "$(head_of "$key")" = "$(want "$bytes" "$name" "$ctype")" ] \
    || die "$key reads back as '$(head_of "$key")' after the upload — $MANIFEST is untouched"
  echo "  put    $key ($name)"
done < "$STAGE/objects.tsv"

# ── every object hashed back ─────────────────────────────────────────────────
# ⚠⚠ R2 does not check that a key matches its bytes, so size + headers cannot tell a same-size
# replacement. Each object is downloaded and hashed before the manifest may point at it.
log "hashing $count object(s) back from the bucket"
while IFS=$'\t' read -r sha _file key name _bytes _ctype; do
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
cp "$STAGE/windows-release.json" "$MANIFEST"
python3 -I "$HELPER" check
log "done — commit $MANIFEST, check the public side with --verify, then deploy apex (the download pages read it)"
