#!/usr/bin/env bash
# Pull a prebuilt relay image onto the droplet and restart the stack.
# This is the fast deploy path — no on-box build, seconds not minutes. The image
# must already be in GHCR (push it via CI or deploy/build-and-push.sh first).
#
#   ./deploy/redeploy.sh <relay_ip>            # deploy :latest
#   ./deploy/redeploy.sh <relay_ip> 1a2b3c4    # deploy a pinned tag (recommended)
#
# Pinning a tag is safer than :latest — you know exactly what's running and can
# roll back by re-running with the previous tag.
#
# Note: if the GHCR package is private, the droplet needs to be logged in once:
#   ssh root@<ip> 'echo "$GHCR_READ_PAT" | docker login ghcr.io -u b3nedikt --password-stdin'
# (a PAT with read:packages). Make the package public in GitHub → Packages and
# no login is needed.

set -euo pipefail

HOST="${1:?usage: redeploy.sh <relay_ip> [image_tag]}"
TAG="${2:-latest}"

COMPOSE="docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env"

echo "Deploying ghcr.io/b3nedikt/tenshu-relay:$TAG to $HOST …"
# shellcheck disable=SC2087
ssh "root@${HOST}" "set -euo pipefail
  cd /opt/tenshu/repo
  git pull --ff-only || true          # pick up compose/Caddyfile changes, if any
  export RELAY_IMAGE_TAG='${TAG}'
  ${COMPOSE} pull relay
  ${COMPOSE} up -d
  ${COMPOSE} ps"

echo "Verifying…"
ssh "root@${HOST}" "curl -fsS http://localhost/ && echo" \
  && echo "Relay is up." \
  || echo "Relay did not answer yet — check: ssh root@${HOST} '${COMPOSE} logs --tail=100 relay'"
