#!/usr/bin/env bash
# Cut per-region PMTiles extracts from a planet PMTiles, then hand them to the signer.
# Phase 1 of the offline map-region delivery pipeline
# (docs/proposals/maps-region-delivery.md §3 D2 + §7 phase 1 + §9).
#
#   ./scripts/tiles-build.sh                    # extract every region in regions.json
#   PLANET_PMTILES=/data/planet.pmtiles ./scripts/tiles-build.sh
#   REGIONS_JSON=deploy/tiles/regions.json BUILD_DIR=/tmp/out ./scripts/tiles-build.sh
#
# What it does, per region in the region-definition file (deploy/tiles/regions.json):
#   pmtiles extract $PLANET_PMTILES  build/regions/<buildDate>/<id>.pmtiles  --bbox=w,s,e,n
# keeping the FULL zoom band of the basemap profile (no maxzoom clip — the z12-14 clip that
# made the Firenze demo 1.3 MB was a demo-only trick; production surfaces need the whole band,
# D2). It prints each output's byte size + sha256, then invokes the signer to build+sign the
# catalog (see the SIGN step below).
#
# It does NOT run Planetiler. Producing the planet PMTiles is a separate multi-hour job:
#   * seed from a published Protomaps planet build (fastest — decouples client work from the
#     pipeline, §6), e.g. download the current planet .pmtiles from the Protomaps builds, OR
#   * build it yourself with Planetiler (JVM) against the OSM planet at the Protomaps basemap
#     profile (~100-120 GB out; a few hours on a 64GB+ box).
# Point PLANET_PMTILES at the resulting file. The tile schema MUST be Protomaps v4 (the
# :ui-map runtime style is built against it; a silent v5 build renders blank — D3).
#
# Tools: the go-pmtiles CLI (`pmtiles`) for extract, `jq` to read regions.json. Install
# go-pmtiles: https://github.com/protomaps/go-pmtiles (a single Go binary on PATH).

set -euo pipefail

# ---- Locate the repo + inputs ----------------------------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TILES_HOME="${TILES_HOME:-$REPO_ROOT/deploy/tiles}"
REGIONS_JSON="${REGIONS_JSON:-$TILES_HOME/regions.json}"
BUILD_DIR="${BUILD_DIR:-$TILES_HOME/build}"

# The planet PMTiles the extracts are cut from — REQUIRED (see header for how to produce it).
PLANET_PMTILES="${PLANET_PMTILES:-}"

# Protomaps tile schema this pipeline is pinned to (D3). regions.json.tileSchemaVersion must match.
EXPECTED_TILE_SCHEMA_VERSION=4

# ---- Preconditions ---------------------------------------------------------
command -v pmtiles >/dev/null 2>&1 || {
  echo "ERROR: 'pmtiles' (go-pmtiles CLI) not found on PATH." >&2
  echo "  Install it: https://github.com/protomaps/go-pmtiles/releases  (a single binary)." >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || {
  echo "ERROR: 'jq' not found on PATH (needed to read $REGIONS_JSON)." >&2
  exit 1
}

[ -f "$REGIONS_JSON" ] || {
  echo "ERROR: region-definition file not found: $REGIONS_JSON" >&2
  echo "  Expected {buildDate, schemaVersion, tileSchemaVersion, regions:[{id,name,bbox:[w,s,e,n]}]}." >&2
  exit 1
}
[ -n "$PLANET_PMTILES" ] || {
  echo "ERROR: PLANET_PMTILES is not set — point it at the planet PMTiles to extract from." >&2
  echo "  e.g. PLANET_PMTILES=/data/planet.pmtiles ./scripts/tiles-build.sh   (see header)." >&2
  exit 1
}
# The planet may be a LOCAL FILE or a REMOTE URL. `pmtiles extract` range-fetches over HTTP, pulling
# only the tiles inside each bbox — that is what makes a regional cut runnable on a laptop with no
# 100 GB download (tiles-pipeline.md procedure A). A bare `[ -f ]` check rejected every remote source,
# so the documented flow could not run at all.
case "$PLANET_PMTILES" in
  http://*|https://*|s3://*)
    PLANET_IS_REMOTE=1 ;;
  *)
    PLANET_IS_REMOTE=0
    [ -f "$PLANET_PMTILES" ] || {
      echo "ERROR: planet PMTiles not found: $PLANET_PMTILES" >&2
      echo "  Pass a local path, or an http(s)/s3 URL to range-extract from (e.g." >&2
      echo "  https://build.protomaps.com/<YYYYMMDD>.pmtiles)." >&2
      exit 1
    } ;;
esac

# ---- sha256 / size helpers (portable across linux + macOS) -----------------
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}
size_of() {
  # bytes
  stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}
human() {
  # bytes -> human-ish, no external `numfmt` dependency
  awk -v b="$1" 'BEGIN{
    split("B KiB MiB GiB TiB",u," "); i=1;
    while (b>=1024 && i<5){ b/=1024; i++ }
    printf (i==1 ? "%d %s" : "%.1f %s"), b, u[i]
  }'
}

# ---- Read the region-definition file ---------------------------------------
BUILD_DATE="$(jq -r '.buildDate' "$REGIONS_JSON")"
SCHEMA_VERSION="$(jq -r '.schemaVersion' "$REGIONS_JSON")"
TILE_SCHEMA_VERSION="$(jq -r '.tileSchemaVersion' "$REGIONS_JSON")"
REGION_COUNT="$(jq -r '.regions | length' "$REGIONS_JSON")"

[ "$BUILD_DATE" != "null" ] && [ -n "$BUILD_DATE" ] || {
  echo "ERROR: $REGIONS_JSON has no .buildDate (expected e.g. \"2026-07\")." >&2
  exit 1
}
if [ "$TILE_SCHEMA_VERSION" != "$EXPECTED_TILE_SCHEMA_VERSION" ]; then
  echo "ERROR: regions.json tileSchemaVersion=$TILE_SCHEMA_VERSION but this pipeline is pinned to Protomaps v$EXPECTED_TILE_SCHEMA_VERSION (D3)." >&2
  echo "  A schema-mismatched build renders blank in :ui-map. Fix regions.json or the planet build." >&2
  exit 1
fi
[ "$REGION_COUNT" -gt 0 ] || {
  echo "ERROR: $REGIONS_JSON declares no regions." >&2
  exit 1
}

OUT_DIR="$BUILD_DIR/regions/$BUILD_DATE"
mkdir -p "$OUT_DIR"

# Per-region {byteSize, sha256}, accumulated as TSV in the loop below and assembled into
# $BUILD_DIR/hashes.json afterwards. This is what lets the signing key stay OFF this machine
# (D11): signing needs each bundle's size + hash, not its bytes, so a box that cut a 20 GB planet
# can hand the maintainer a few hundred bytes of public digests instead of receiving the release
# key. The loop runs in a pipeline subshell, so this has to be a file, not a variable.
DIGEST_TSV="$(mktemp)"
trap 'rm -f "$DIGEST_TSV"' EXIT

echo "Region-definition : $REGIONS_JSON"
echo "Planet PMTiles    : $PLANET_PMTILES"
echo "Build date        : $BUILD_DATE   (schemaVersion=$SCHEMA_VERSION, tileSchemaVersion=$TILE_SCHEMA_VERSION)"
echo "Output dir        : $OUT_DIR"
echo "Regions           : $REGION_COUNT"
echo

# ---- Extract each region ---------------------------------------------------
# jq emits one TSV line per region: id \t name \t w \t s \t e \t n
jq -r '.regions[] | [.id, .name, (.bbox[0]|tostring), (.bbox[1]|tostring), (.bbox[2]|tostring), (.bbox[3]|tostring)] | @tsv' \
  "$REGIONS_JSON" | while IFS=$'\t' read -r id name w s e n; do

  [ -n "$id" ] || { echo "ERROR: a region has an empty id in $REGIONS_JSON" >&2; exit 1; }
  out="$OUT_DIR/$id.pmtiles"

  echo "==> $id  ($name)   bbox=[$w,$s,$e,$n]"
  # A **whole-world** bbox ([-180,-90,180,90]) is the *global* region: publish the planet PMTiles
  # itself (streamed on demand, D10) instead of cutting an extract of it — one artifact covers
  # every coordinate, so a user anywhere streams just their covering tiles without a per-region
  # publish (`maps-region-delivery.md`; tiles-pipeline.md "Global coverage"). Link it in (no 100 GB
  # copy; falls back to cp across filesystems); everything downstream — sign, publish — is identical.
  if awk -v w="$w" -v s="$s" -v e="$e" -v n="$n" 'BEGIN{exit !(w<=-180 && s<=-90 && e>=180 && n>=90)}'; then
    # `global` publishes the planet itself, so it must be a real file we can link/copy and upload —
    # there is nothing to "extract". A remote URL cannot satisfy that: materialise it first (that is
    # also where the zoom cap is applied), then point PLANET_PMTILES at the result.
    if [ "$PLANET_IS_REMOTE" = "1" ]; then
      echo "ERROR: the 'global' region needs a LOCAL planet file, but PLANET_PMTILES is a URL:" >&2
      echo "  $PLANET_PMTILES" >&2
      echo "  Materialise it first (this is also where you cap the zoom), then re-run:" >&2
      echo "    pmtiles extract '$PLANET_PMTILES' /data/planet.pmtiles --maxzoom=14" >&2
      echo "    PLANET_PMTILES=/data/planet.pmtiles $0" >&2
      echo "  (Regional bboxes extract straight from a URL — it is only 'global' that cannot.)" >&2
      exit 1
    fi
    echo "    (global: linking the planet as-is — no extract)"
    ln -f "$PLANET_PMTILES" "$out" 2>/dev/null || cp "$PLANET_PMTILES" "$out"
  else
    # go-pmtiles bbox order is minLon,minLat,maxLon,maxLat == w,s,e,n. No --maxzoom: keep the
    # full basemap zoom band within the region (D2).
    pmtiles extract "$PLANET_PMTILES" "$out" --bbox="$w,$s,$e,$n"
  fi

  sz="$(size_of "$out")"
  sha="$(sha256_of "$out")"
  echo "    $out"
  echo "    size   : $sz bytes ($(human "$sz"))"
  echo "    sha256 : $sha"
  echo
  printf '%s\t%s\t%s\n' "$id" "$sz" "$sha" >> "$DIGEST_TSV"
done

echo "Extracted $REGION_COUNT region(s) into $OUT_DIR"
echo

# ---- Emit hashes.json (the signing-key-free half of the split, D11) --------
HASHES_JSON="$BUILD_DIR/hashes.json"
jq -Rn --arg buildDate "$BUILD_DATE" '
  { buildDate: $buildDate,
    bundles: [inputs | split("\t") | { key: .[0], value: { byteSize: (.[1] | tonumber), sha256: .[2] } }] | from_entries }
' < "$DIGEST_TSV" > "$HASHES_JSON"
echo "Wrote $HASHES_JSON — copy this to the machine holding the signing key and pass it as"
echo "  --hashes $HASHES_JSON   (lets you sign a bundle this machine built but that one never sees)"
echo

# ---- SIGN: build + sign the catalog ----------------------------------------
# The signer is the JVM :tiles-signer Gradle module (built in parallel). It reads regions.json
# + the extract dir, computes each bundle's size/sha256, writes build/catalog.json, and emits a
# detached Ed25519 signature build/catalog.json.sig with the Tenshu release-key primitives
# (org.tenshu.core.common.security.Ed25519Backend) so the in-tree client verifier is reused
# (D3, §9 — do NOT introduce a second signature stack).
#
# Exact invocation (TODO: enable once :tiles-signer lands — set TILES_SIGNING_KEY to the
# release signing key file, or run this line by hand):
#
#   ./gradlew :tiles-signer:run --args="build-and-sign \
#     --regions $REGIONS_JSON \
#     --extracts $OUT_DIR \
#     --out $BUILD_DIR/catalog.json \
#     --key <keyfile>"
#
# §33 L3: `--overlays` is passed only when the directory exists, so a build with no transit
# artefacts produces exactly the catalog it does today.
OVERLAY_DIR="${OVERLAY_DIR:-$BUILD_DIR/transit/$BUILD_DATE}"
OVERLAY_FLAG=""
[ -d "$OVERLAY_DIR" ] && OVERLAY_FLAG=" --overlays $OVERLAY_DIR"
SIGN_CMD=( ./gradlew :tiles-signer:run --args="build-and-sign --regions $REGIONS_JSON --extracts $OUT_DIR --out $BUILD_DIR/catalog.json --key ${TILES_SIGNING_KEY:-<keyfile>}$OVERLAY_FLAG" )

if [ -n "${TILES_SIGNING_KEY:-}" ] && [ "${TILES_SKIP_SIGN:-0}" != "1" ]; then
  echo "Signing catalog with :tiles-signer …"
  ( cd "$REPO_ROOT" && "${SIGN_CMD[@]}" )
  echo "Wrote $BUILD_DIR/catalog.json (+ .sig)"
else
  echo "Skipping the signing step (set TILES_SIGNING_KEY to the release key to run it; TILES_SKIP_SIGN=1 forces skip)."
  echo "Run it manually with:"
  echo "  ${SIGN_CMD[*]}"
fi

echo
echo "Next: publish this build to R2 (uploads $OUT_DIR + catalog.json[.sig]):"
echo "  docs/how-to/tiles-pipeline.md A4 -> r2publish DRY_RUN=1, then r2publish"
echo "  (or: terraform -chdir=deploy/cloudflare-tiles output publish_env_hint). SPACES_ENDPOINT is required; there is no default."
