#!/usr/bin/env bash
#
# Cloud-environment setup helper: pre-install the exact Gradle version pinned by
# this repo's wrapper, so sessions can build modules that require a newer Gradle
# than the pre-installed one (e.g. AGP 9.2.1 needs Gradle >= 9.4.1) without every
# session re-downloading the distribution.
#
# It seeds Gradle's *wrapper cache* (not just a PATH install), so the repo's
# ./gradlew invocations resolve the distribution offline afterwards.
#
# Paste this into the cloud environment's "Setup script" field, or call it from
# there. It is intentionally best-effort: a download failure logs loudly but does
# NOT exit non-zero, so it can never brick session startup.
#
# Source of the bytes: services.gradle.org -> (307) github release assets.
# github.com + objects.githubusercontent.com + release-assets.githubusercontent.com
# are on the default "Trusted" network allowlist, so this works during normal
# environment provisioning.

set -uo pipefail

log() { echo "[install-gradle] $*"; }

# Locate the repo's wrapper properties. Works whether this script is run from a
# checkout (BASH_SOURCE), from the repo root as CWD (setup-script default), from
# $CLAUDE_PROJECT_DIR (SessionStart hook), or pasted into the environment UI
# (fall back to a bounded search under common clone roots).
find_props() {
  local c
  for c in \
    "${CLAUDE_PROJECT_DIR:-}/gradle/wrapper/gradle-wrapper.properties" \
    "$(pwd)/gradle/wrapper/gradle-wrapper.properties" \
    "$(cd "$(dirname "${BASH_SOURCE[0]:-.}")/.." 2>/dev/null && pwd)/gradle/wrapper/gradle-wrapper.properties"
  do
    [ -n "$c" ] && [ -f "$c" ] && { echo "$c"; return 0; }
  done
  find "${HOME}" /root /home /workspace -maxdepth 5 \
    -path '*/gradle/wrapper/gradle-wrapper.properties' 2>/dev/null | head -1
}

PROPS="$(find_props)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

if [ -z "$PROPS" ] || [ ! -f "$PROPS" ]; then
  log "no gradle-wrapper.properties found — nothing to do"; exit 0
fi
log "using wrapper properties: $PROPS"

# distributionUrl reads e.g.
#   distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
# Strip the backslash-escaping the properties format uses on the colon.
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | tr -d '\r' | sed 's/\\:/:/g')
DIST_FILE=$(printf '%s' "$DIST_URL" | grep -oE 'gradle-[0-9][0-9.]*-(bin|all)\.zip')
VER=$(printf '%s' "$DIST_FILE" | sed -E 's/gradle-([0-9.]+)-(bin|all)\.zip/\1/')
KIND=$(printf '%s' "$DIST_FILE" | sed -E 's/gradle-([0-9.]+)-(bin|all)\.zip/\2/')

if [ -z "$VER" ] || [ -z "$DIST_URL" ]; then
  log "could not parse version/URL from '$DIST_URL' — leaving wrapper to self-download"; exit 0
fi
log "repo wrapper pins Gradle $VER ($KIND) from $DIST_URL"

# Gradle's wrapper cache dir is:
#   $GRADLE_USER_HOME/wrapper/dists/gradle-<ver>-<kind>/<hash>/
# where <hash> = base36( md5( distributionUrl-string ) ), matching
# org.gradle.wrapper.PathAssembler.
HASH=$(python3 - "$DIST_URL" <<'PY'
import hashlib, sys
digest = hashlib.md5(sys.argv[1].encode()).digest()
n = int.from_bytes(digest, "big")
if n == 0:
    print("0"); sys.exit()
alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
out = []
while n:
    n, r = divmod(n, 36)
    out.append(alphabet[r])
print("".join(reversed(out)))
PY
)
[ -n "$HASH" ] || { log "hash computation failed"; exit 0; }

DIST_NAME="gradle-$VER-$KIND"
CACHE_DIR="$GRADLE_USER_HOME/wrapper/dists/$DIST_NAME/$HASH"
ZIP="$CACHE_DIR/$DIST_NAME.zip"
OK_MARKER="$ZIP.ok"

if [ -f "$OK_MARKER" ] && [ -x "$CACHE_DIR/gradle-$VER/bin/gradle" ]; then
  log "already seeded at $CACHE_DIR — nothing to do"; exit 0
fi

mkdir -p "$CACHE_DIR"
log "downloading into wrapper cache: $CACHE_DIR"
if ! curl -fSL --retry 4 --retry-delay 2 -o "$ZIP" "$DIST_URL"; then
  log "DOWNLOAD FAILED for $DIST_URL"
  log "  If this environment blocks github, the wrapper cannot be seeded; the"
  log "  bytes only exist on github release assets. Give the session normal"
  log "  (non single-repo-scoped) GitHub access, or set network access to allow"
  log "  github.com + *.githubusercontent.com."
  rm -f "$ZIP"
  exit 0   # best-effort: never brick session startup
fi

log "extracting"
if command -v unzip >/dev/null 2>&1; then
  unzip -q -o "$ZIP" -d "$CACHE_DIR"
else
  python3 -c "import zipfile,sys; zipfile.ZipFile('$ZIP').extractall('$CACHE_DIR')"
fi
touch "$OK_MARKER"

# Convenience: also expose gradle on PATH so `gradle <task>` works too.
if [ -x "$CACHE_DIR/gradle-$VER/bin/gradle" ]; then
  ln -sf "$CACHE_DIR/gradle-$VER/bin/gradle" /usr/local/bin/gradle 2>/dev/null || true
  log "seeded + linked. Version check:"
  "$CACHE_DIR/gradle-$VER/bin/gradle" --version 2>/dev/null | sed 's/^/[install-gradle]   /' || true
else
  log "extraction did not yield gradle-$VER/bin/gradle — check the archive layout"
fi

log "done"
