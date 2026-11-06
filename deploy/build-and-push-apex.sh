#!/usr/bin/env bash
# Build the apex web-frontend image locally and push it to GHCR — the no-CI path.
# Produces the same image as .github/workflows/apex-image.yml, just from your
# machine. Run from anywhere; build context is forced to the repo root.
#
#   ./deploy/build-and-push-apex.sh                              # tags :latest and :<short-sha>
#   ./deploy/build-and-push-apex.sh v1.2.0                       # also tags :v1.2.0
#   RELAY_URL=https://relay.example.com ./deploy/build-and-push-apex.sh   # non-default relay
#   CONSTRUCTION_CODE=424242 ./deploy/build-and-push-apex.sh              # bake the pre-launch gate code
#   PLATFORM=linux/arm64 ./deploy/build-and-push-apex.sh                  # override target arch
#
# Targets linux/amd64 by DEFAULT because the DigitalOcean droplets are amd64. A
# bare `docker build` tags the image for the HOST arch — on an Apple-Silicon
# machine that's arm64, which then won't run on the amd64 droplet
# ("platform ... does not match host"). So we build through buildx with an
# explicit --platform. On an amd64 host / CI runner this is native; on an arm
# host the Dockerfile's RUN steps (Gradle + pnpm) run under QEMU emulation —
# correct but slow (use the CI workflow for the fast path).
#
# Prereqs (one-time):
#   - docker buildx (Docker Desktop bundles it; Colima/standalone need:
#       brew install docker-buildx
#       mkdir -p ~/.docker/cli-plugins && \
#       ln -sfn "$(brew --prefix)/opt/docker-buildx/bin/docker-buildx" ~/.docker/cli-plugins/docker-buildx)
#   - docker logged in to GHCR:
#       echo "$GHCR_PAT" | docker login ghcr.io -u b3nedikt --password-stdin
#     where GHCR_PAT is a PAT with `write:packages` (classic) scope.
#
# The relay URL is compiled INTO the SPA at build time (Vite inlines it), so it's
# fixed when the image is built — not a runtime knob. Defaults to the prod relay.

set -euo pipefail

IMAGE="ghcr.io/b3nedikt/tenshu-apex"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXTRA_TAG="${1:-}"
RELAY_URL="${RELAY_URL:-https://relay.tenshu.app}"
# Pre-launch construction-gate code, baked in at build time (web/apex Dockerfile ARG
# VITE_CONSTRUCTION_CODE). Empty (default) => no curtain. Set it to hide the site before launch.
CONSTRUCTION_CODE="${CONSTRUCTION_CODE:-}"
PLATFORM="${PLATFORM:-linux/amd64}"
BUILDER="tenshu-builder"

SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"

if ! docker buildx version >/dev/null 2>&1; then
  echo "ERROR: docker buildx is required (cross-platform build+push for the amd64 droplets)." >&2
  echo "  brew install docker-buildx && mkdir -p ~/.docker/cli-plugins && \\" >&2
  echo "  ln -sfn \"\$(brew --prefix)/opt/docker-buildx/bin/docker-buildx\" ~/.docker/cli-plugins/docker-buildx" >&2
  exit 1
fi

# A container-driver builder can build+push for a non-native platform (the plain
# docker driver can't). Created once, reused thereafter.
docker buildx inspect "$BUILDER" >/dev/null 2>&1 \
  || docker buildx create --name "$BUILDER" --driver docker-container --bootstrap >/dev/null

TAGS=(--tag "$IMAGE:latest" --tag "$IMAGE:$SHA")
[ -n "$EXTRA_TAG" ] && TAGS+=(--tag "$IMAGE:$EXTRA_TAG")

echo "Building+pushing $IMAGE for $PLATFORM  (relay: $RELAY_URL; gate: ${CONSTRUCTION_CODE:-off}; tags: latest, $SHA${EXTRA_TAG:+, $EXTRA_TAG})"
docker buildx build --builder "$BUILDER" --platform "$PLATFORM" \
  --file "$REPO_ROOT/web/apex/Dockerfile" \
  --build-arg "VITE_TENSHU_RELAY_URL=$RELAY_URL" \
  --build-arg "VITE_CONSTRUCTION_CODE=$CONSTRUCTION_CODE" \
  "${TAGS[@]}" --push "$REPO_ROOT"

echo "Done. Deploy it with:  ./deploy/redeploy-apex.sh <apex_ip> ${EXTRA_TAG:-$SHA}"
