#!/usr/bin/env bash
# Launch Control desktop against the LOCAL plane started by `local-commerce-stack.sh`.
#
# Sibling of `run-control-desktop-commerce.sh`, which points at the deployed
# `commerce.service.tenshu.app` issuer. This one points at localhost, so the whole
# end-to-end dry run (`docs/proposals/commerce-end-to-end-dry-run.md` §5) needs no droplet,
# no DNS and no signing.
#
#   ./scripts/local-commerce-stack.sh                     # terminal 1
#   ./scripts/run-control-desktop-local-commerce.sh       # terminal 2
#
# ⚠ TENSHU_BILLING_MOCK=false is the whole point: the default (true, in every non-release
#   build) selects MockPublisherClient, which fakes the OTP and grants checkout locally —
#   i.e. it never touches :commerce-service at all. With it off you get the real
#   CommercePublisherClient, the real mint + token clients, and the real relay credential.
#
# ⚠ The client reaches commerce over THREE independent seams that all read
#   TENSHU_COMMERCE_URL — PublisherClient (account/session), EntitlementMintClient (the
#   synced record) and EntitlementTokenClient (the relay bearer). One env var covers all
#   three; setting only some of them by hand yields a confusing half-real state.
#
# ⚠ GUI: run interactively, not headless.

set -euo pipefail

export TENSHU_BILLING_MOCK=false
export TENSHU_COMMERCE_URL="${TENSHU_COMMERCE_URL:-http://localhost:8081}"
export TENSHU_RELAY_URL="${TENSHU_RELAY_URL:-http://localhost:8080}"

echo "Launching Control desktop against the local plane:"
echo "  billing mock : OFF (real commerce client)"
echo "  commerce URL : ${TENSHU_COMMERCE_URL}"
echo "  relay URL    : ${TENSHU_RELAY_URL}"
echo
echo "  Flow: Services > Sign up > (read the code from the commerce log) > verify"
echo "        then upload in Drive and watch the relay accept POST /sync/push."
echo

cd "$(dirname "$0")/.."
exec ./gradlew :control-desktop:run
