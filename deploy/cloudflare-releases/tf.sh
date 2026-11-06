#!/usr/bin/env bash
# terraform, with the Cloudflare API token read from the macOS Keychain for the one command.
#
#   deploy/cloudflare-releases/tf.sh init
#   deploy/cloudflare-releases/tf.sh plan
#   deploy/cloudflare-releases/tf.sh apply      # ⚠ the maintainer's call — it creates the bucket + DNS
#
# The token is the one deploy/cloudflare-tiles and deploy/cloudflare-models are applied with (R2 edit
# + DNS edit on tenshu.app), stored once — `-w` with no value prompts, so nothing lands in history:
#
#   security add-generic-password -U -a tenshu -s cloudflare-api-token -w
#
# ⚠ An exported CLOUDFLARE_API_TOKEN wins over the Keychain, so this also runs off macOS. The token is
# never printed and never written to a file; the state holds no secret (the provider reads it from the
# environment).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]; then
  CLOUDFLARE_API_TOKEN="$(security find-generic-password -a tenshu -s cloudflare-api-token -w 2>/dev/null || true)"
  [[ -n "$CLOUDFLARE_API_TOKEN" ]] || {
    echo "tf.sh: no 'cloudflare-api-token' in the Keychain (account tenshu) and CLOUDFLARE_API_TOKEN is unset" >&2
    exit 1
  }
  export CLOUDFLARE_API_TOKEN
fi

exec terraform "$@"
