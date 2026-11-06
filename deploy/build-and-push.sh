#!/usr/bin/env bash
# Build the sync-relay image locally and push it to GHCR — the no-CI path.
# Produces the same image as .github/workflows/relay-image.yml, just from your
# machine. Run from anywhere; build context is forced to the repo root.
#
#   ./deploy/build-and-push.sh            # tags :latest and :<short-sha>
#   ./deploy/build-and-push.sh v1.2.0     # also tags :v1.2.0
#   PLATFORM=linux/arm64 ./deploy/build-and-push.sh   # override target arch
#
# Targets linux/amd64 by DEFAULT because the relay box (a Scaleway instance) is amd64. A
# bare `docker build` tags the image for the HOST arch — on an Apple-Silicon
# machine that's arm64, which then crash-loops on the amd64 box
# ("platform ... does not match host"). So we build through buildx with an
# explicit --platform. On an amd64 host / CI runner this is native; on an arm
# host the Dockerfile's RUN steps (Gradle) run under QEMU emulation — correct but
# slower (use the CI workflow for the fast path).
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
# House Rule #1 untouched: this only compiles the relay.

set -euo pipefail

IMAGE="ghcr.io/b3nedikt/tenshu-relay"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXTRA_TAG="${1:-}"
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

echo "Building+pushing $IMAGE for $PLATFORM  (tags: latest, $SHA${EXTRA_TAG:+, $EXTRA_TAG})"
docker buildx build --builder "$BUILDER" --platform "$PLATFORM" \
  --file "$REPO_ROOT/sync-relay-server/Dockerfile" \
  "${TAGS[@]}" --push "$REPO_ROOT"

echo "Done. Deploy it with:  ./deploy/redeploy.sh <relay_ip> ${EXTRA_TAG:-$SHA}"
