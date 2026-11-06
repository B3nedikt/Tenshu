#!/usr/bin/env bash
# Publish a signed model catalog + its artifacts to the R2 bucket (IN-2 / IN-D10).
#
#   terraform -chdir=deploy/cloudflare-models output publish_env_hint   # the whole invocation
#   DRY_RUN=1 SPACES_BUCKET=… SPACES_ENDPOINT=… ./scripts/models-publish.sh   # print every remote command
#
# Layout, mirroring the tiles pipeline:
#   build/artifacts/<path>   ->  s3://$SPACES_BUCKET/<path>          (immutable, long cache)
#   build/catalog.json       ->  s3://$SPACES_BUCKET/catalog.json    (mutable pointer, short cache)
#   build/catalog.json.sig   ->  s3://$SPACES_BUCKET/catalog.json.sig
#
# ⚠⚠ ARTIFACTS FIRST, CATALOG LAST. The catalog is the pointer; publishing it before the bytes it
# names gives every device a window in which each install 404s. The reverse order is harmless —
# artifacts nothing points at yet cost only storage.
#
# ⚠ The catalog and the weights are public (IN-D10) — on R2 through the bucket's custom domain, since R2
# has no object ACLs. Integrity comes from the signature and the per-artifact sha256, never from the
# transport.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$REPO_ROOT/deploy/models/build}"
ARTIFACT_DIR="${ARTIFACT_DIR:-$BUILD_DIR/artifacts}"
CATALOG="$BUILD_DIR/catalog.json"
CATALOG_SIG="$BUILD_DIR/catalog.json.sig"

SPACES_BUCKET="${SPACES_BUCKET:?set SPACES_BUCKET (terraform -chdir=deploy/cloudflare-models output -raw bucket_name)}"
DRY_RUN="${DRY_RUN:-0}"

# ⚠⚠ No default endpoint. It used to default to DigitalOcean Spaces, which never held a model bucket;
# a default there fails at best, and against another S3-compatible store publishes where no client reads.
if [ -z "${SPACES_ENDPOINT:-}" ]; then
  echo "ERROR: set SPACES_ENDPOINT (terraform -chdir=deploy/cloudflare-models output -raw s3_endpoint)." >&2
  echo "       'terraform -chdir=deploy/cloudflare-models output publish_env_hint' prints the whole invocation." >&2
  exit 1
fi

# ⚠⚠ R2 differs from other S3 stores in two ways that each FAIL an upload, so both are derived from the
# endpoint rather than left to an operator to remember (the tiles-publish.sh rule, for the same reasons):
#   - R2 does not implement `x-amz-acl`: a PutObject carrying `public-read` is refused with
#     NotImplemented. Public read is the bucket's custom domain.
#   - R2's region is `auto`. ⚠ FORCED, not defaulted: a shell set up for another store exports a region
#     R2 does not have, and honouring it signs every request for it.
case "$SPACES_ENDPOINT" in
  *.r2.cloudflarestorage.com | *.r2.cloudflarestorage.com/*)
    if [ -n "${SPACES_REGION:-}" ] && [ "$SPACES_REGION" != "auto" ]; then
      echo "NOTE: ignoring SPACES_REGION=$SPACES_REGION for an R2 endpoint — R2's region is auto." >&2
    fi
    SPACES_REGION=auto
    ACL_ARGS=()
    ;;
  *)
    SPACES_REGION="${SPACES_REGION:?set SPACES_REGION for a non-R2 endpoint}"
    ACL_ARGS=(--acl public-read)
    ;;
esac

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-${SPACES_ACCESS_KEY_ID:-}}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-${SPACES_SECRET_ACCESS_KEY:-}}"

command -v aws >/dev/null 2>&1 || {
  echo "ERROR: the 'aws' CLI is not on PATH. See docs/how-to/models-pipeline.md." >&2
  exit 1
}

# ⚠ A configured CLI profile is credentials too — `aws` reads AWS_PROFILE itself, so an operator
# using one needs no key in their shell history.
if [ "$DRY_RUN" != "1" ] && [ -z "${AWS_PROFILE:-}" ]; then
  if [ -z "$AWS_ACCESS_KEY_ID" ] || [ -z "$AWS_SECRET_ACCESS_KEY" ]; then
    echo "ERROR: set AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY (or SPACES_*, or AWS_PROFILE)." >&2
    exit 1
  fi
fi

[ -f "$CATALOG" ] || { echo "ERROR: $CATALOG not found — run the sign step first (see the how-to)." >&2; exit 1; }
[ -f "$CATALOG_SIG" ] || { echo "ERROR: $CATALOG_SIG not found — the catalog was not signed." >&2; exit 1; }

# ⚠⚠ Refuse to publish a catalog naming a file we do not have. The client's integrity gate would
# refuse the download anyway, but only AFTER a user spent the bytes; failing here costs nothing.
missing=0
while IFS= read -r path; do
  [ -z "$path" ] && continue
  if [ ! -f "$ARTIFACT_DIR/$path" ]; then
    echo "ERROR: catalog names '$path' but $ARTIFACT_DIR/$path does not exist" >&2
    missing=1
  fi
done < <(grep -o '"path"[[:space:]]*:[[:space:]]*"[^"]*"' "$CATALOG" | sed 's/.*"\([^"]*\)"$/\1/')
[ "$missing" = "0" ] || { echo "Nothing was published." >&2; exit 1; }

run() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "DRY_RUN: $*"
  else
    "$@"
  fi
}

echo "==> Publishing to s3://$SPACES_BUCKET ($SPACES_ENDPOINT)"
echo "    artifacts: $ARTIFACT_DIR"
[ "$DRY_RUN" = "1" ] && echo "    (dry run — nothing will be uploaded)"

AWS=(aws --endpoint-url "$SPACES_ENDPOINT" --region "$SPACES_REGION")

# 1. Artifacts first. Immutable: a model version never changes its bytes, so cache them hard.
while IFS= read -r path; do
  [ -z "$path" ] && continue
  run "${AWS[@]}" s3 cp "$ARTIFACT_DIR/$path" "s3://$SPACES_BUCKET/$path" \
    ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
    --content-type "application/octet-stream" \
    --cache-control "public, max-age=31536000, immutable"
done < <(grep -o '"path"[[:space:]]*:[[:space:]]*"[^"]*"' "$CATALOG" | sed 's/.*"\([^"]*\)"$/\1/' | sort -u)

# 2. Then the pointer. Short cache: this is what a device re-reads to notice a new model.
run "${AWS[@]}" s3 cp "$CATALOG" "s3://$SPACES_BUCKET/catalog.json" \
  ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
  --content-type "application/json" --cache-control "public, max-age=300"
run "${AWS[@]}" s3 cp "$CATALOG_SIG" "s3://$SPACES_BUCKET/catalog.json.sig" \
  ${ACL_ARGS[@]+"${ACL_ARGS[@]}"} \
  --content-type "application/octet-stream" --cache-control "public, max-age=300"

echo "==> Done."
echo "    Verify: curl -fsS \"\$BASE/catalog.json\" | head -c 200 ; curl -fsSI \"\$BASE/catalog.json.sig\""
