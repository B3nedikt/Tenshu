#!/usr/bin/env bash
#
# Publish the store screenshots to R2 and record them in the repo's manifests
# (docs/how-to/store-screenshots.md §7, store-presence-and-support-pages.md §3.3).
#
#   scripts/store-publish.sh [control] [drive] [photos] [fit] [calendar] [contacts] [timeline] [mia] [maps] [home] --dry-run   # hash + diff the manifests; no network, no writes
#   scripts/store-publish.sh [control] [drive] [photos] [fit] [calendar] [contacts] [timeline] [mia] [maps] [home]  # upload what is missing, rewrite the manifests
#   scripts/store-publish.sh [control] [drive] [photos] [fit] [calendar] [contacts] [timeline] [mia] [maps] [home] --verify    # HEAD every URL the manifests name
#   scripts/store-publish.sh --fixture <file>              # put one licensed fixture photo in the PRIVATE
#                                                          # bucket; prints the manifest `fetch` to paste
#
# Reads   store/<app>/screenshots/<platform>/<device>/<lang>/NN-<screen>.png        light (gitignored, generated)
#         store/<app>/screenshots/<platform>/<device>/<lang>/dark/NN-<screen>.png   dark  (store-screenshots.sh --appearance)
# Uploads s3://$ASSETS_BUCKET/sha256/<hex>.png   → https://assets.tenshu.app/sha256/<hex>.png
# Writes  store/<app>/screenshots.manifest.json  (committed; the support pages read it), keyed like the
#         path: `<platform>/<device>/<lang>/NN-screen` (light — the form every manifest had before dark
#         existed, so those keys stay valid) and `<platform>/<device>/<lang>/dark/NN-screen` (dark).
# And     the website's WebP derivatives of EVERY PNG the manifests name (scripts/lib/store_webp.py):
#         s3://$ASSETS_BUCKET/sha256/<hex>.webp, recorded in store/<app>/screenshots.webp.json (committed)
#         by the source PNG's hash. Missing ones are made on every run — from the local capture, else
#         from the PNG downloaded back from R2 — so a run with no local captures at all only backfills
#         derivatives. Needs `cwebp` (brew install webp) for the real run; --dry-run lists them only.
#
# ⚠⚠ OBJECTS FIRST, MANIFEST LAST. The manifest is the pointer: a support page built from it links
# every URL it names, so it is rewritten only after every object it will name is in the bucket. A
# failed upload leaves the old manifest untouched.
#
# ⚠ Content-addressed: the key is the SHA-256 of the bytes, so an object that exists is never
# re-uploaded, and `immutable` is true. Only directories that exist locally are rewritten in the
# manifest — publishing the iPhone run leaves the iPad entries as they were, and publishing a light
# folder leaves its dark/ entries (and the other way round): each is its own group.
#
# Environment (all optional):
#   ASSETS_ENDPOINT   R2 S3 endpoint. Default: `terraform -chdir=deploy/cloudflare-assets output -raw
#                     s3_endpoint` (works in the checkout that holds the module's state).
#   ASSETS_BUCKET     default tenshu-assets          ASSETS_BASE_URL  default https://assets.tenshu.app/sha256/
#   FIXTURES_BUCKET   default tenshu-store-fixtures
#   R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY — else the Keychain: r2-assets-access-key-id /
#                     r2-assets-secret-access-key (account tenshu). Never printed.

set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

APPS=()
MODE=publish
FIXTURE=""
while [ $# -gt 0 ]; do
  case "$1" in
    control|drive|photos|fit|calendar|contacts|timeline|mia|maps|home) APPS+=("$1") ;;
    --dry-run) MODE=dry-run ;;
    --verify) MODE=verify ;;
    --fixture) MODE=fixture; FIXTURE="${2:?--fixture needs a file}"; shift ;;
    -h|--help) sed -n '2,35p' "$0"; exit 0 ;;
    *) echo "store-publish: unknown argument '$1'" >&2; exit 2 ;;
  esac
  shift
done
[ ${#APPS[@]} -gt 0 ] || APPS=(control drive photos fit calendar contacts timeline mia maps home)

ASSETS_BUCKET="${ASSETS_BUCKET:-tenshu-assets}"
ASSETS_BASE_URL="${ASSETS_BASE_URL:-https://assets.tenshu.app/sha256/}"
FIXTURES_BUCKET="${FIXTURES_BUCKET:-tenshu-store-fixtures}"
CACHE_CONTROL="public, max-age=31536000, immutable"

log() { echo "store-publish: $*"; }
die() { echo "store-publish: $*" >&2; exit 1; }

# ── R2 access (publish and --fixture only) ───────────────────────────────────
keychain() { security find-generic-password -a tenshu -s "$1" -w 2>/dev/null || true; }

r2_setup() {
  command -v aws >/dev/null 2>&1 || die "the 'aws' CLI is not on PATH (brew install awscli)"
  if [ -z "${ASSETS_ENDPOINT:-}" ]; then
    ASSETS_ENDPOINT="$(terraform -chdir=deploy/cloudflare-assets output -raw s3_endpoint 2>/dev/null || true)"
  fi
  case "${ASSETS_ENDPOINT:-}" in
    https://*.r2.cloudflarestorage.com) ;;
    "") die "no R2 endpoint: set ASSETS_ENDPOINT, or run from the checkout where deploy/cloudflare-assets was applied" ;;
    *) die "ASSETS_ENDPOINT=$ASSETS_ENDPOINT is not an R2 endpoint (https://<account>.r2.cloudflarestorage.com)" ;;
  esac
  local id secret
  id="${R2_ACCESS_KEY_ID:-$(keychain r2-assets-access-key-id)}"
  secret="${R2_SECRET_ACCESS_KEY:-$(keychain r2-assets-secret-access-key)}"
  [ -n "$id" ] && [ -n "$secret" ] \
    || die "no R2 key pair — export R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY or store r2-assets-access-key-id / r2-assets-secret-access-key in the Keychain (deploy/cloudflare-assets/README.md)"
  # Exported into THIS process only (and the aws children), never the caller's shell. A session token
  # or profile from another store would be sent along and break R2's signature check.
  unset AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
  export AWS_ACCESS_KEY_ID="$id" AWS_SECRET_ACCESS_KEY="$secret"
  # ⚠ R2's region is `auto`, and R2 implements no ACLs — no --acl anywhere below.
  AWS=(aws --endpoint-url "$ASSETS_ENDPOINT" --region auto)
}

# ⚠⚠ Refuses on a bucket that does not exist yet: the Terraform apply is the maintainer's call, and a
# PUT against a missing bucket would otherwise read as a credentials problem.
require_bucket() { # $1 = bucket
  "${AWS[@]}" s3api head-bucket --bucket "$1" >/dev/null 2>&1 \
    || die "bucket '$1' does not exist (or this key cannot see it) — it is created by deploy/cloudflare-assets (./tf.sh apply), which waits for the maintainer's OK. Nothing was uploaded."
}

# Uploads $2 to s3://$1/$3 unless an object of the same size is already there. Same key ⇒ same
# bytes (content addressing), so the size is a cheap guard against a truncated earlier upload.
put_once() { # $1 = bucket, $2 = file, $3 = key, $4 = content type
  local have want
  want="$(wc -c < "$2" | tr -d ' ')"
  have="$("${AWS[@]}" s3api head-object --bucket "$1" --key "$3" --query ContentLength --output text 2>/dev/null || true)"
  if [ "$have" = "$want" ]; then
    echo "  have   $3"
    return 0
  fi
  [ -z "$have" ] || echo "  REPAIR $3 (size $have, expected $want)"
  "${AWS[@]}" s3api put-object --bucket "$1" --key "$3" --body "$2" \
    --content-type "$4" --cache-control "$CACHE_CONTROL" >/dev/null
  echo "  put    $3"
}

# ── --fixture ────────────────────────────────────────────────────────────────
if [ "$MODE" = fixture ]; then
  [ -f "$FIXTURE" ] || die "no such file: $FIXTURE"
  sha="$(shasum -a 256 "$FIXTURE" | cut -d' ' -f1)"
  ext="$(echo "${FIXTURE##*.}" | tr '[:upper:]' '[:lower:]')"
  case "$ext" in
    jpg|jpeg) type=image/jpeg; ext=jpg ;;
    png) type=image/png ;;
    heic) type=image/heic ;;
    pdf) type=application/pdf ;;
    mp4) type=video/mp4 ;;
    mov) type=video/quicktime ;;
    *) die "unexpected fixture type .$ext" ;;
  esac
  r2_setup
  require_bucket "$FIXTURES_BUCKET"
  put_once "$FIXTURES_BUCKET" "$FIXTURE" "fixtures/$sha.$ext" "$type"
  log "add to the entry in store/fixtures/<app>/manifest.json (with its real source/license/author/url):"
  echo "  \"fetch\": {\"url\": \"s3://$FIXTURES_BUCKET/fixtures/$sha.$ext\", \"sha256\": \"$sha\"}"
  exit 0
fi

# ── --verify ─────────────────────────────────────────────────────────────────
# Every URL a committed manifest names must answer 200, as a PNG of the recorded size, with the
# immutable header — a support page must never link an image R2 does not have. Public, no credentials.
if [ "$MODE" = verify ]; then
  failed=0 checked=0
  for app in "${APPS[@]}"; do
    manifest="store/$app/screenshots.manifest.json"
    [ -f "$manifest" ] || { log "$app — no manifest, nothing published"; continue; }
    # ⚠ Into a variable first, not `< <(…)`: a process substitution's exit status is lost, and a
    # manifest that does not parse would then "verify" as zero URLs.
    urls="$(python3 - "$manifest" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
for key, e in sorted(m["screens"].items()):
    print(f'{key}\t{m["base"]}{e["sha256"]}.png\t{e["bytes"]}')
PY
)" || die "$manifest does not parse"
    while IFS=$'\t' read -r key url bytes; do
      [ -n "$key" ] || continue
      checked=$((checked + 1))
      headers="$(curl -sSI --max-time 20 "$url" 2>&1 || true)"
      status="$(printf '%s\n' "$headers" | awk 'toupper($1) ~ /^HTTP/ {s=$2} END {print s}')"
      ctype="$(printf '%s\n' "$headers" | awk -F': *' 'tolower($1)=="content-type" {print $2}' | tr -d '\r')"
      length="$(printf '%s\n' "$headers" | awk -F': *' 'tolower($1)=="content-length" {print $2}' | tr -d '\r')"
      cache="$(printf '%s\n' "$headers" | awk -F': *' 'tolower($1)=="cache-control" {print $2}' | tr -d '\r')"
      problem=""
      [ "$status" = 200 ] || problem="HTTP ${status:-no answer}"
      [ -n "$problem" ] || [ "$ctype" = image/png ] || problem="content-type '$ctype'"
      [ -n "$problem" ] || [ -z "$length" ] || [ "$length" = "$bytes" ] || problem="$length bytes, manifest says $bytes"
      [ -n "$problem" ] || case "$cache" in *immutable*) ;; *) problem="cache-control '$cache'" ;; esac
      if [ -n "$problem" ]; then
        echo "  FAIL $app $key — $url — $problem"
        failed=$((failed + 1))
      else
        echo "  ok   $app $key"
      fi
    done <<< "$urls"
  done
    # The WebP derivatives (store_webp.py): each must answer 200 as image/webp of the recorded size.
  for app in "${APPS[@]}"; do
    urls="$(python3 scripts/lib/store_webp.py urls "$ROOT" "$app")" || die "store/$app/screenshots.webp.json does not parse"
    while IFS=$'\t' read -r key url bytes; do
      [ -n "$key" ] || continue
      checked=$((checked + 1))
      headers="$(curl -sSI --max-time 20 "$url" 2>&1 || true)"
      status="$(printf '%s\n' "$headers" | awk 'toupper($1) ~ /^HTTP/ {s=$2} END {print s}')"
      ctype="$(printf '%s\n' "$headers" | awk -F': *' 'tolower($1)=="content-type" {print $2}' | tr -d '\r')"
      length="$(printf '%s\n' "$headers" | awk -F': *' 'tolower($1)=="content-length" {print $2}' | tr -d '\r')"
      cache="$(printf '%s\n' "$headers" | awk -F': *' 'tolower($1)=="cache-control" {print $2}' | tr -d '\r')"
      problem=""
      [ "$status" = 200 ] || problem="HTTP ${status:-no answer}"
      [ -n "$problem" ] || [ "$ctype" = image/webp ] || problem="content-type '$ctype'"
      [ -n "$problem" ] || [ -z "$length" ] || [ "$length" = "$bytes" ] || problem="$length bytes, sidecar says $bytes"
      [ -n "$problem" ] || case "$cache" in *immutable*) ;; *) problem="cache-control '$cache'" ;; esac
      if [ -n "$problem" ]; then
        echo "  FAIL $app $key — $url — $problem"
        failed=$((failed + 1))
      else
        echo "  ok   $app $key"
      fi
    done <<< "$urls"
  done
  [ "$failed" = 0 ] || die "$failed of $checked manifest URLs are not served correctly — do not ship pages built from these manifests"
  log "verified $checked URLs"
  exit 0
fi

# ── scan ─────────────────────────────────────────────────────────────────────
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/store-publish.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

# Hashes every capture, then writes each app's NEW manifest into $STAGE (nothing in the repo yet) and
# the list of objects to upload to $STAGE/objects.tsv.
python3 - "$ROOT" "$STAGE" "$ASSETS_BASE_URL" "${APPS[@]}" <<'PY'
import hashlib, json, os, re, struct, sys

root, stage, base, apps = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
NAME = re.compile(r"^\d\d-[a-z0-9-]+\.png$")
# A capture folder: <platform>/<device>/<lang> (light) or <platform>/<device>/<lang>/dark. The key is
# the folder plus the screen name, so the key form follows the path — and has to match the generator's
# MANIFEST_KEY_RE (web/apex/support-pages/generate.ts), which check-store-assets.py also enforces.
APPEARANCE_DIRS = {"dark"}
objects = {}

def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        sys.exit(f"store-publish: {path} is not a PNG")
    return struct.unpack(">II", head[16:24])

for app in apps:
    shots = os.path.join(root, "store", app, "screenshots")
    path = os.path.join(root, "store", app, "screenshots.manifest.json")
    old = json.load(open(path)) if os.path.exists(path) else {"screens": {}}
    if old.get("base", base) != base:
        sys.exit(f"store-publish: {path} names base {old['base']}, this run {base} — move the manifest deliberately")
    scanned, groups = {}, set()
    if os.path.isdir(shots):
        for dirpath, _dirs, files in os.walk(shots):
            rel = os.path.relpath(dirpath, shots).split(os.sep)
            pngs = sorted(f for f in files if NAME.match(f))
            if not pngs:
                continue
            if not (len(rel) == 3 or (len(rel) == 4 and rel[3] in APPEARANCE_DIRS)):
                sys.exit(f"store-publish: {dirpath} is not <platform>/<device>/<lang>[/dark] — move the captures, nothing was published")
            groups.add("/".join(rel))
            for f in pngs:
                full = os.path.join(dirpath, f)
                data = open(full, "rb").read()
                sha = hashlib.sha256(data).hexdigest()
                w, h = png_size(full)
                scanned["/".join(rel + [f[:-4]])] = {"sha256": sha, "width": w, "height": h, "bytes": len(data)}
                objects[sha] = full
    # Replace whole <platform>/<device>/<lang> groups that were captured; keep every other entry.
    screens = {k: v for k, v in old["screens"].items() if k.rsplit("/", 1)[0] not in groups}
    screens.update(scanned)
    print(f"store-publish: {app} — {len(scanned)} captures in {len(groups)} folder(s), {len(screens)} screens in the manifest")
    if not screens:
        continue
    lines = [
        "{",
        '  "about": "Generated by scripts/store-publish.sh — do not edit. Each screen of the store screenshots, as published to R2: its URL is base + sha256 + .png. Keys are <platform>/<device>/<lang>/NN-screen (light) and <platform>/<device>/<lang>/dark/NN-screen (dark). The images themselves are not in git (store-presence-and-support-pages.md §3.3).",',
        '  "version": 1,',
        f'  "base": {json.dumps(base)},',
        '  "screens": {',
    ]
    items = sorted(screens.items())
    for i, (k, v) in enumerate(items):
        entry = json.dumps({"sha256": v["sha256"], "width": v["width"], "height": v["height"], "bytes": v["bytes"]})
        lines.append(f"    {json.dumps(k)}: {entry}{',' if i < len(items) - 1 else ''}")
    lines += ["  }", "}", ""]
    os.makedirs(os.path.join(stage, app), exist_ok=True)
    open(os.path.join(stage, app, "screenshots.manifest.json"), "w").write("\n".join(lines))

with open(os.path.join(stage, "objects.tsv"), "w") as f:
    for sha, full in sorted(objects.items()):
        f.write(f"{sha}\t{full}\n")
PY

count="$(wc -l < "$STAGE/objects.tsv" | tr -d ' ')"
# The WebP derivatives every manifest PNG still lacks (no network here; `build` below makes them).
python3 scripts/lib/store_webp.py plan "$ROOT" "$STAGE" "$ASSETS_BASE_URL" "${APPS[@]}"
jobs="$(cat "$STAGE/webp-jobs")"
[ "$count" -gt 0 ] || [ "$jobs" -gt 0 ] \
  || die "no captures under store/<app>/screenshots/ and no WebP derivatives missing — run scripts/store-screenshots.sh first"

show_diffs() {
  local app
  for app in "${APPS[@]}"; do
    [ -f "$STAGE/$app/screenshots.manifest.json" ] || continue
    if [ -f "store/$app/screenshots.manifest.json" ]; then
      diff -u "store/$app/screenshots.manifest.json" "$STAGE/$app/screenshots.manifest.json" || true
    else
      echo "--- (new) store/$app/screenshots.manifest.json"
      cat "$STAGE/$app/screenshots.manifest.json"
    fi
  done
}

if [ "$MODE" = dry-run ]; then
  log "dry run — $count distinct image(s) and $jobs WebP derivative(s) would go to s3://$ASSETS_BUCKET/sha256/ (those already there are skipped); no network, nothing written"
  while IFS=$'\t' read -r sha file; do echo "  sha256/$sha.png  ← ${file#"$ROOT"/}"; done < "$STAGE/objects.tsv"
  show_diffs
  exit 0
fi

# ── publish ──────────────────────────────────────────────────────────────────
r2_setup
require_bucket "$ASSETS_BUCKET"
log "uploading $count distinct image(s) to s3://$ASSETS_BUCKET ($ASSETS_ENDPOINT)"
while IFS=$'\t' read -r sha file; do
  put_once "$ASSETS_BUCKET" "$file" "sha256/$sha.png" image/png
done < "$STAGE/objects.tsv"

# The WebP derivatives: encoded into $STAGE (downloading a PNG back from R2 where it is not captured
# here), uploaded, and only then pointed at — the sidecars are copied in with the manifests below.
python3 scripts/lib/store_webp.py build "$ROOT" "$STAGE" "$ASSETS_BASE_URL" "${APPS[@]}"
webp_count="$(wc -l < "$STAGE/webp-objects.tsv" | tr -d ' ')"
log "uploading $webp_count WebP derivative(s)"
while IFS=$'\t' read -r sha file; do
  put_once "$ASSETS_BUCKET" "$file" "sha256/$sha.webp" image/webp
done < "$STAGE/webp-objects.tsv"

show_diffs
for app in "${APPS[@]}"; do
  [ -f "$STAGE/$app/screenshots.manifest.json" ] || continue
  cp "$STAGE/$app/screenshots.manifest.json" "store/$app/screenshots.manifest.json"
done
for app in "${APPS[@]}"; do
  [ -f "$STAGE/$app/screenshots.webp.json" ] || continue
  diff -u "store/$app/screenshots.webp.json" "$STAGE/$app/screenshots.webp.json" >/dev/null 2>&1 || log "$app — screenshots.webp.json updated"
  cp "$STAGE/$app/screenshots.webp.json" "store/$app/screenshots.webp.json"
done
python3 scripts/check-store-assets.py >/dev/null || python3 scripts/check-store-assets.py
log "done — commit store/*/screenshots.manifest.json and store/*/screenshots.webp.json. Check the public side with: scripts/store-publish.sh --verify"
