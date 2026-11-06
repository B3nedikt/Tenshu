#!/usr/bin/env bash
# Publish a dated tiles build (per-region PMTiles + signed catalog) to the tiles bucket: Cloudflare R2
# (deploy/cloudflare-tiles) since 2026-09-15. DigitalOcean Spaces is retired and its bucket deleted.
# Phase 1 of the offline map-region delivery pipeline
# (docs/proposals/maps-region-delivery.md §3 D1/D2 + §7 phase 1 + §9).
#
#   SPACES_BUCKET=tenshu-tiles SPACES_ENDPOINT=<R2 endpoint> ./scripts/tiles-publish.sh   # publish the build in regions.json
#   (the full invocation, credentials included: terraform -chdir=deploy/cloudflare-tiles output publish_env_hint)
#   ⚠ Every line below also needs SPACES_BUCKET, SPACES_ENDPOINT and the R2 key pair in its environment.
#     The script refuses to run without an endpoint. docs/how-to/tiles-pipeline.md A4's `r2publish`
#     supplies all of them, so these become `r2publish DRY_RUN=1`, `r2publish BUILD_DATE=2026-07`, `r2publish GC=1`:
#   DRY_RUN=1 ./scripts/tiles-publish.sh                              # print every remote command, touch nothing
#   BUILD_DATE=2026-07 ./scripts/tiles-publish.sh                     # publish a specific dated build
#   GC=1 ./scripts/tiles-publish.sh                                   # also GC old builds (retain current + one prior, D2)
#
# Uploads, mirroring the redeploy-*.sh env-driven / dry-run-friendly style:
#   build/regions/<buildDate>/*.pmtiles  ->  s3://$SPACES_BUCKET/regions/<buildDate>/   (immutable, long cache;
#                                                                                        skipped when absent)
#   build/transit/<buildDate>/*.tlne     ->  s3://$SPACES_BUCKET/regions/<buildDate>/   (§33 L3, same prefix as the
#                                                                                        bundle: catalog.overlayPath
#                                                                                        points at regions/<date>/<id>.tlne)
#   build/catalog.json                   ->  s3://$SPACES_BUCKET/catalog.json           (mutable pointer, short cache)
#   build/catalog.json.sig               ->  s3://$SPACES_BUCKET/catalog.json.sig        (detached signature, D3)
#
# All objects are public (the catalog + bundles are public, D3); integrity is the signed manifest +
# per-bundle sha256 (R4), NOT the channel. On Spaces that is a per-object `public-read` ACL; on
# Cloudflare R2 it is the bucket's custom domain, and R2 has no object ACLs at all (see ACL_ARGS).
#
# Credentials (S3-compatible Spaces keys — NOT the DO API token):
#   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY   (or SPACES_ACCESS_KEY_ID / SPACES_SECRET_ACCESS_KEY)
# Uses the `aws` CLI against the Spaces endpoint. s3cmd works too (see the how-to); this script
# uses `aws s3` because it ships a native ranged/multipart uploader.

set -euo pipefail

# ---- Config (env-driven; no hardcoded secrets) -----------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TILES_HOME="${TILES_HOME:-$REPO_ROOT/deploy/tiles}"
REGIONS_JSON="${REGIONS_JSON:-$TILES_HOME/regions.json}"
BUILD_DIR="${BUILD_DIR:-$TILES_HOME/build}"

SPACES_BUCKET="${SPACES_BUCKET:?set SPACES_BUCKET (terraform -chdir=deploy/cloudflare-tiles output -raw bucket_name)}"

# ⚠⚠ No default endpoint. It used to be DigitalOcean Spaces, whose tiles bucket was deleted on
# 2026-09-15 when R2 became the only host. A default there fails at best, and against any other
# S3-compatible store it would publish to a target no client reads.
if [ -z "${SPACES_ENDPOINT:-}" ]; then
  echo "ERROR: set SPACES_ENDPOINT to the R2 S3 endpoint (terraform -chdir=deploy/cloudflare-tiles output -raw s3_endpoint)." >&2
  echo "       'terraform -chdir=deploy/cloudflare-tiles output publish_env_hint' prints the whole invocation." >&2
  exit 1
fi

# ⚠⚠ Cloudflare R2 (deploy/cloudflare-tiles) differs from Spaces in two ways that each FAIL an upload,
# so they are derived from the endpoint rather than left to an operator to remember:
#   - R2 does not implement `x-amz-acl`: developers.cloudflare.com/r2/api/s3/api/ lists it ❌ on
#     PutObject and CreateMultipartUpload, and clients sending `public-read` are refused with
#     NotImplemented. Public access is the bucket's custom domain instead.
#   - R2's region is `auto` (an empty value and `us-east-1` alias to it). ⚠ FORCED, not defaulted: a
#     shell set up for DigitalOcean publishing exports SPACES_REGION=fra1, and honouring it would
#     sign every R2 request for a region R2 does not have.
case "${SPACES_ENDPOINT:-}" in
  *.r2.cloudflarestorage.com | *.r2.cloudflarestorage.com/*)
    IS_R2=1
    if [ -n "${SPACES_REGION:-}" ] && [ "$SPACES_REGION" != "auto" ]; then
      echo "NOTE: ignoring SPACES_REGION=$SPACES_REGION for an R2 endpoint — R2's region is auto." >&2
    fi
    SPACES_REGION=auto
    ACL_ARGS=()
    ;;
  *)
    IS_R2=0
    SPACES_REGION="${SPACES_REGION:-fra1}"
    ACL_ARGS=(--acl public-read)
    ;;
esac

# How many dated builds to retain when GC=1 (current + N-1 prior). D2 keeps one prior cycle
# for clients mid-download, then GCs — so 2.
RETAIN_BUILDS="${RETAIN_BUILDS:-2}"

DRY_RUN="${DRY_RUN:-0}"

# Accept either the standard AWS names or the Spaces-specific ones.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-${SPACES_ACCESS_KEY_ID:-}}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-${SPACES_SECRET_ACCESS_KEY:-}}"

# ---- Preconditions ---------------------------------------------------------
command -v aws >/dev/null 2>&1 || {
  echo "ERROR: the 'aws' CLI is not on PATH. Install awscli, or adapt to s3cmd (see docs/how-to/tiles-pipeline.md)." >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || { echo "ERROR: 'jq' not found on PATH (needed to read $REGIONS_JSON)." >&2; exit 1; }
[ -f "$REGIONS_JSON" ] || { echo "ERROR: region-definition file not found: $REGIONS_JSON" >&2; exit 1; }

# ⚠ A configured CLI profile is credentials too. `aws` reads AWS_PROFILE itself, so the only change
# needed is to stop insisting on the env-var form — which otherwise refuses a machine whose keys live
# in ~/.aws, the place they are least likely to end up in a shell history.
if [ "$DRY_RUN" != "1" ] && [ -z "${AWS_PROFILE:-}" ]; then
  [ -n "$AWS_ACCESS_KEY_ID" ] && [ -n "$AWS_SECRET_ACCESS_KEY" ] || {
    echo "ERROR: Spaces credentials missing. Export AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY" >&2
    echo "       (or SPACES_ACCESS_KEY_ID + SPACES_SECRET_ACCESS_KEY, or set AWS_PROFILE)." >&2
    exit 1
  }
fi

BUILD_DATE="${BUILD_DATE:-$(jq -r '.buildDate' "$REGIONS_JSON")}"
[ -n "$BUILD_DATE" ] && [ "$BUILD_DATE" != "null" ] || { echo "ERROR: no build date (set BUILD_DATE or .buildDate in $REGIONS_JSON)." >&2; exit 1; }

SRC_REGIONS="$BUILD_DIR/regions/$BUILD_DATE"
SRC_CATALOG="$BUILD_DIR/catalog.json"
SRC_CATALOG_SIG="$BUILD_DIR/catalog.json.sig"

# ⚠⚠ Local extracts are NOT required. A publish that only ADDS something beside bundles already
# uploaded — a transit-line overlay (§33), a re-signed catalog — has no reason to hold 1.8 GB of
# immutable objects it is not going to touch. Requiring them made an overlay-only publish impossible
# and was hit for real on 2026-08-14.
#
# ⚠ The guard this replaces was worth having, but it checked the wrong thing: that files exist HERE,
# not that the catalog points at objects that exist THERE. `verify_catalog_targets` below does the
# latter, after any upload and before the catalog goes live, which is strictly stronger — it also
# catches a bundle that was never uploaded in a previous run.
[ -f "$SRC_CATALOG" ] || { echo "ERROR: $SRC_CATALOG missing — run the signer (tiles-build.sh SIGN step) first." >&2; exit 1; }
[ -f "$SRC_CATALOG_SIG" ] || { echo "ERROR: $SRC_CATALOG_SIG missing — the catalog must be signed before publishing (D3)." >&2; exit 1; }

# ---- run(): echo every remote command; execute unless DRY_RUN --------------
run() {
  echo "+ $*"
  [ "$DRY_RUN" = "1" ] || "$@"
}

AWS=(aws --endpoint-url "$SPACES_ENDPOINT" --region "$SPACES_REGION")

echo "Publishing build $BUILD_DATE -> s3://$SPACES_BUCKET  (endpoint $SPACES_ENDPOINT)"
[ "$DRY_RUN" = "1" ] && echo "   (DRY RUN — no remote state touched)"
echo

# ---- 1. Upload the region bundles (immutable => long cache) ----------------
# Dated bundles never change (D2/D8), so mark them immutable + cache a year.
# ⚠ Skipped without complaint when the directory is absent — see the note at the precondition.
if [ -d "$SRC_REGIONS" ]; then
  run "${AWS[@]}" s3 cp "$SRC_REGIONS/" "s3://$SPACES_BUCKET/regions/$BUILD_DATE/" \
    --recursive \
    --exclude "*" --include "*.pmtiles" \
    ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
    --content-type "application/octet-stream" \
    --cache-control "public, max-age=31536000, immutable"
else
  echo "No extracts at $SRC_REGIONS — publishing without re-uploading bundles."
  echo "  (the catalog's targets are verified remotely below, so this is checked, not assumed)"
fi

# ---- 1b. Upload the transit-line overlays (§33 L3) -------------------------
# Same prefix and the same immutability rules as the bundles — `catalog.overlayPath` points at
# regions/<buildDate>/<id>.tlne — and the same integrity story: the digest is inside the SIGNED
# catalog, so the artefact needs no signature of its own.
# ⚠ Skipped without complaint when the directory does not exist: a build with no transit artefacts
# is legitimate, and the catalog simply carries no overlay entries.
SRC_OVERLAYS="${SRC_OVERLAYS:-$BUILD_DIR/transit/$BUILD_DATE}"
if [ -d "$SRC_OVERLAYS" ]; then
  run "${AWS[@]}" s3 cp "$SRC_OVERLAYS/" "s3://$SPACES_BUCKET/regions/$BUILD_DATE/" \
    --recursive \
    --exclude "*" --include "*.tlne" \
    ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
    --content-type "application/octet-stream" \
    --cache-control "public, max-age=31536000, immutable"
else
  echo "No overlay directory at $SRC_OVERLAYS — publishing tiles only (§33 L3)."
fi

# ---- 1c. Verify every object the catalog points at actually exists ---------
# ⚠⚠ The catalog is the LIVE POINTER: publishing one that references a missing object breaks map
# delivery for every device, and the failure appears at download time rather than here. So the check
# runs against the bucket, after any upload above and before the pointer moves.
#
# ⚠ Read-only, so it runs in DRY_RUN too — that is the whole point of a dry run.
verify_catalog_targets() {
  local missing=0 key
  # Both the bundle and (since §33) the optional overlay. `// empty` drops regions without one.
  for key in $(jq -r '.regions[] | .path, (.overlayPath // empty | select(. != ""))' "$SRC_CATALOG"); do
    if "${AWS[@]}" s3api head-object --bucket "$SPACES_BUCKET" --key "$key" >/dev/null 2>&1; then
      echo "  ok      $key"
    else
      echo "  MISSING $key" >&2
      missing=$((missing + 1))
    fi
  done
  [ "$missing" -eq 0 ] || {
    echo "ERROR: $missing object(s) named by $SRC_CATALOG are not in s3://$SPACES_BUCKET." >&2
    echo "       Publishing this catalog would point live clients at objects that do not exist." >&2
    echo "       Upload them first (./scripts/tiles-build.sh for bundles), or fix the catalog." >&2
    exit 1
  }
}
echo "Verifying the catalog's targets exist in the bucket…"
verify_catalog_targets
echo

# ---- 2. Upload the signed catalog (mutable pointer => short cache) ----------
# catalog.json is the one object that changes build-to-build; keep it near-uncached so a new
# build is picked up promptly (the client re-verifies the signature every fetch anyway).
run "${AWS[@]}" s3 cp "$SRC_CATALOG" "s3://$SPACES_BUCKET/catalog.json" \
  ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
  --content-type "application/json" \
  --cache-control "public, max-age=60, must-revalidate"

run "${AWS[@]}" s3 cp "$SRC_CATALOG_SIG" "s3://$SPACES_BUCKET/catalog.json.sig" \
  ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
  --content-type "application/octet-stream" \
  --cache-control "public, max-age=60, must-revalidate"

# ---- 3. Flush the CDN cache for the mutable catalog ------------------------
# The bucket TTL is bundle-oriented (long); force the edge to drop the catalog so the new build
# is visible immediately. Optional — needs doctl + the CDN id (`doctl compute cdn list`).
# ⚠ DigitalOcean only. Cloudflare caches by file extension and .json/.sig are outside its default
# list, so on R2 there is no edge copy of the catalog to flush — as long as nobody adds a
# "Cache Everything" rule over it (deploy/cloudflare-tiles/main.tf).
if [ "$IS_R2" = "1" ]; then
  echo "NOTE: R2 endpoint — no CDN flush (the catalog is not held at Cloudflare's edge by default)."
elif [ -n "${CDN_ID:-}" ]; then
  if command -v doctl >/dev/null 2>&1; then
    run doctl compute cdn flush "$CDN_ID" --files "catalog.json,catalog.json.sig"
  else
    echo "NOTE: CDN_ID set but 'doctl' not on PATH — skipping CDN flush. Install doctl or flush in the DO console." >&2
  fi
else
  echo "NOTE: set CDN_ID (doctl compute cdn list) to auto-flush catalog.json from the edge after publish."
fi

# ---- 4. GC old dated builds (retain current + one prior, D2) ---------------
# Off by default (destructive); enable with GC=1. Lists the regions/<date>/ prefixes, keeps the
# newest $RETAIN_BUILDS, removes the rest. Dates sort lexically because they're YYYY-MM.
if [ "${GC:-0}" = "1" ]; then
  echo
  echo "GC: retaining the newest $RETAIN_BUILDS dated build(s) under regions/ …"
  # `aws s3 ls s3://bucket/regions/` prints "PRE 2026-07/" lines for each prefix.
  mapfile -t BUILDS < <("${AWS[@]}" s3 ls "s3://$SPACES_BUCKET/regions/" 2>/dev/null \
    | awk '/PRE/ {gsub("/","",$2); print $2}' | sort)
  count="${#BUILDS[@]}"
  if [ "$count" -le "$RETAIN_BUILDS" ]; then
    echo "   $count build(s) present (<= retain=$RETAIN_BUILDS) — nothing to GC."
  else
    drop=$(( count - RETAIN_BUILDS ))
    for old in "${BUILDS[@]:0:$drop}"; do
      echo "   removing old build regions/$old/"
      run "${AWS[@]}" s3 rm "s3://$SPACES_BUCKET/regions/$old/" --recursive
    done
  fi
else
  echo "NOTE: old-build GC skipped (set GC=1 to prune down to the newest $RETAIN_BUILDS dated builds, D2)."
fi

echo
echo "Done. Verify the public path:"
echo "  curl -fsS https://<your-domain>/catalog.json | head"
echo "  curl -fsSI -r 0-1023 https://<your-domain>/regions/$BUILD_DATE/<id>.pmtiles   # ranged GET (expect 206)"
