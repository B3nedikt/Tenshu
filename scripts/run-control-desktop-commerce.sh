#!/usr/bin/env bash
# Launch Control desktop wired to a REAL commerce issuer, so you can redeem a code
# and mint a real (commerce-signed) entitlement instead of the default mock billing.
# This is the client-side "mint" step you do BEFORE flipping ENTITLEMENT_ENFORCE=true
# on the relay (the staged rollout: mint on a client, confirm it holds an entitlement,
# then enforce).
#
#   ./scripts/run-control-desktop-commerce.sh
#   TENSHU_COMMERCE_URL=https://commerce.service.tenshu.app ./scripts/run-control-desktop-commerce.sh
#   TENSHU_RELAY_URL=https://relay.tenshu.app ./scripts/run-control-desktop-commerce.sh
#
# What it sets (read by control-shared-proxy/.../PlatformProxyModule.kt via System.getenv):
#   TENSHU_BILLING_MOCK=false  -> use the real CommercePublisherClient, not the dev-seed
#                                 self-signer. (Default is true = mock, no service.)
#   TENSHU_COMMERCE_URL        -> the deployed issuer (default: commerce.service.tenshu.app, below).
#   TENSHU_RELAY_URL           -> optional; only if you want a non-default relay.
#
# After it launches:
#   1. Onboard as a new user (bootstraps a genesis realm against the relay).
#   2. Open Settings -> Services and enter the redemption code (e.g. FOUNDER-1).
#      The client calls POST /commerce/entitlement/redeem -> a commerce-signed
#      entitlement -> it syncs and the device now holds it.
#   3. Tell the deploy operator the client holds an entitlement; then flip
#      ENTITLEMENT_ENFORCE=true and writes will present X-Entitlement and pass.
#
# NOTE: this launches a GUI; it must be run interactively (not headless).

set -euo pipefail

export TENSHU_BILLING_MOCK=false
export TENSHU_COMMERCE_URL="${TENSHU_COMMERCE_URL:-https://commerce.service.tenshu.app}"
[ -n "${TENSHU_RELAY_URL:-}" ] && export TENSHU_RELAY_URL

echo "Launching Control desktop:"
echo "  billing mock : OFF (real commerce client)"
echo "  commerce URL : ${TENSHU_COMMERCE_URL}"
echo "  relay URL    : ${TENSHU_RELAY_URL:-<app default>}"
echo "  -> after launch: onboard, then Settings > Services > enter a redemption code (e.g. FOUNDER-1)"
echo

cd "$(dirname "$0")/.."
exec ./gradlew :control-desktop:run
