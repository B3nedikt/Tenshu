#!/usr/bin/env bash
# Build per-region transit-line overlays (.tlne) from OSM.
# L2 of the overlay pipeline (docs/proposals/timeline-movement-capture.md §33, §17.6).
#
#   ./scripts/transit-overlay-build.sh                        # every region in regions.json
#   ./scripts/transit-overlay-build.sh de-bayern              # one region
#   OVERPASS_URL=https://overpass.private/api/interpreter ./scripts/transit-overlay-build.sh
#
# What it produces, per region:
#   build/transit/<buildDate>/<id>.tsv    the reduced OSM rows  (ref \t latE7 \t lngE7)
#   build/transit/<buildDate>/<id>.tlne   the artefact the client reads
#
# ⚠⚠ This script does NOT encode the artefact. `TransitLineIndex.encode` already exists in Kotlin,
# and a second encoder here would be two implementations of one format, free to drift — the failure
# `PlaceProximity`'s "one rule, in the module both sides depend on" exists to prevent. So the split
# is: jq does the JSON, and Gradle's `:vector-tile:buildTransitOverlay` owns the transformation and
# the bytes. Everything interesting (merging a station's platform nodes) is unit-tested there.
#
# Tools: curl, jq, and the repo's Gradle wrapper.
#
# ⚠ Overpass is a shared public service. This asks for one region at a time and sleeps between
# regions; for a full planet build the right source is an osmium pass over Geofabrik extracts, which
# produces the same TSV — see "SCALING" at the foot of this file.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TILES_HOME="${TILES_HOME:-$REPO_ROOT/deploy/tiles}"
REGIONS_JSON="${REGIONS_JSON:-$TILES_HOME/regions.json}"
OVERPASS_URL="${OVERPASS_URL:-https://overpass-api.de/api/interpreter}"
SLEEP_BETWEEN="${SLEEP_BETWEEN:-5}"

# The route types that carry a line a user would recognise. ⚠ `bus` is deliberately absent: a bus
# route's `ref` is a number the timeline cannot corroborate (§13's "buses read as drive" — nothing
# upstream ever produces a bus leg to label), and it would multiply the artefact for no answer.
ROUTE_TYPES="${ROUTE_TYPES:-subway|light_rail|tram|train}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: 'jq' not found on PATH." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "ERROR: 'curl' not found on PATH." >&2; exit 1; }
[ -f "$REGIONS_JSON" ] || { echo "ERROR: no region file at $REGIONS_JSON" >&2; exit 1; }

BUILD_DATE="$(jq -r '.buildDate' "$REGIONS_JSON")"
OUT_DIR="${BUILD_DIR:-$TILES_HOME/build}/transit/$BUILD_DATE"
mkdir -p "$OUT_DIR"

WANTED="${1:-}"

# ---- one region ------------------------------------------------------------
build_region() {
  local id="$1" bbox="$2"
  # Overpass wants south,west,north,east; regions.json stores west,south,east,north.
  local w s e n
  w="$(echo "$bbox" | jq -r '.[0]')"; s="$(echo "$bbox" | jq -r '.[1]')"
  e="$(echo "$bbox" | jq -r '.[2]')"; n="$(echo "$bbox" | jq -r '.[3]')"

  echo "==> $id  bbox=$s,$w,$n,$e"

  # `>>` recurses the relations down to their member NODES, so each stop arrives with a coordinate.
  # ⚠ We ask for the relations' members rather than for stations directly: the line identity lives on
  # the relation (`ref`), and only its membership says which stops it actually serves.
  local query
  query="[out:json][timeout:180];
relation[\"type\"=\"route\"][\"route\"~\"^($ROUTE_TYPES)$\"]($s,$w,$n,$e);
out body;
>;
out skel qt;"

  local raw="$OUT_DIR/$id.overpass.json"
  curl -sS --fail-with-body --data-urlencode "data=$query" "$OVERPASS_URL" -o "$raw"

  # Reduce to `ref \t latE7 \t lngE7 \t service`, one row per (line, stop node).
  # ⚠ The `service` tag rides along but is NOT judged here: deciding what counts as a line rather
  # than one train is a product decision (§17.6), so it lives in tested Kotlin (`isLine`) and jq
  # stays a dumb reducer — the same split that keeps the encoder out of this script.
  # ⚠ `ref`, never `name`: measured, one relation carries ref=U5 alongside
  # name="U-Bahn U5: Laimer Platz => Neuperlach Süd" — a direction, unreadable on a feed row (§17.6).
  # ⚠ Only members OSM marks as a stop or platform; a route relation also lists the track ways.
  jq -r '
    ( [ .elements[] | select(.type=="node") | {key: (.id|tostring), value: .} ] | from_entries ) as $nodes
    | .elements[]
    | select(.type=="relation")
    | . as $rel
    | (.tags.ref // empty) as $ref
    | select($ref != "")
    | .members[]
    | select(.type=="node")
    | select((.role // "") | test("^(stop|platform)"))
    | $nodes[(.ref|tostring)]
    | select(. != null)
    | [ $ref, ((.lat*10000000)|round), ((.lon*10000000)|round), ($rel.tags.service // "") ]
    | @tsv
  ' "$raw" | sort -u > "$OUT_DIR/$id.tsv"

  local rows
  rows="$(wc -l < "$OUT_DIR/$id.tsv" | tr -d ' ')"
  if [ "$rows" = "0" ]; then
    # ⚠ Not fatal, and not silent either: a region with no rail is legitimate (and an empty overlay is
    # a valid artefact), but a region that USED to have rows and now has none is a broken query.
    echo "    WARNING: no transit rows for $id — check the bbox and ROUTE_TYPES" >&2
  fi
  echo "    rows: $rows"

  "$REPO_ROOT/gradlew" -q --console=plain :vector-tile:buildTransitOverlay \
    --args="$OUT_DIR/$id.tsv $OUT_DIR/$id.tlne"
}

# ---- every region ----------------------------------------------------------
count="$(jq '.regions | length' "$REGIONS_JSON")"
for i in $(seq 0 $((count - 1))); do
  id="$(jq -r ".regions[$i].id" "$REGIONS_JSON")"
  [ -n "$WANTED" ] && [ "$WANTED" != "$id" ] && continue
  # ⚠ The `global` region is skipped: a planet-wide Overpass query is not a request to make of a
  # shared public service, and the overlay is per-region by design.
  [ "$id" = "global" ] && { echo "==> global — skipped (see the ⚠ in this script)"; continue; }
  bbox="$(jq -c ".regions[$i].bbox" "$REGIONS_JSON")"
  build_region "$id" "$bbox"
  sleep "$SLEEP_BETWEEN"
done

echo
echo "Artefacts in $OUT_DIR"
echo "⚠ Not yet delivered — L3 adds the catalogue entry + signature beside the tile bundles."
#
# SCALING
# -------
# Overpass is right for one region and wrong for a planet. The same TSV comes out of an osmium pass
# over a Geofabrik extract, which is what a production build should use:
#
#   osmium tags-filter region.osm.pbf r/type=route,route=subway,light_rail,tram,train -o routes.osm.pbf
#   osmium export routes.osm.pbf -f json | jq -r '<the same reduction as above>' | sort -u > region.tsv
#
# The Kotlin half is unchanged — it only ever sees the TSV.
