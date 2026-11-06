#!/usr/bin/env bash
# Pull a prebuilt commerce-issuer image onto the droplet and restart the stack.
# The fast deploy path — no on-box build, seconds not minutes. The image must
# already be in GHCR (push it via CI: .github/workflows/commerce-image.yml).
#
#   ./deploy/redeploy-commerce.sh <commerce_ip_or_host>            # deploy :latest
#   ./deploy/redeploy-commerce.sh <commerce_ip_or_host> 1a2b3c4    # deploy a pinned tag (recommended)
#
# Pinning a tag is safer than :latest — you know exactly what's running and can
# roll back by re-running with the previous tag. Postgres + Caddy are untouched;
# only the issuer container is replaced.
#
# Note: if the GHCR package is private, the droplet needs to be logged in once:
#   ssh root@<host> 'echo "$GHCR_READ_PAT" | docker login ghcr.io -u b3nedikt --password-stdin'
# (a PAT with read:packages). Make the package public and no login is needed.

set -euo pipefail

HOST="${1:?usage: redeploy-commerce.sh <commerce_ip_or_host> [image_tag]}"
TAG="${2:-latest}"

COMPOSE="docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env"

echo "Deploying ghcr.io/b3nedikt/tenshu-commerce:$TAG to $HOST …"
# shellcheck disable=SC2087
ssh "root@${HOST}" "set -euo pipefail
  cd /opt/tenshu/repo
  git pull --ff-only || true          # pick up compose/Caddyfile changes, if any
  export COMMERCE_IMAGE_TAG='${TAG}'
  ${COMPOSE} pull commerce
  ${COMPOSE} up -d
  ${COMPOSE} ps"

echo "Verifying…"
ssh "root@${HOST}" "curl -fsS http://localhost/ >/dev/null && echo" \
  && echo "Commerce issuer is up." \
  || echo "Issuer did not answer yet — check: ssh root@${HOST} '${COMPOSE} logs --tail=100 commerce'"
