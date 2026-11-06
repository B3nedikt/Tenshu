# shellcheck shell=bash
#
# Does the dev lab's relay serve the relay protocol THIS checkout's clients speak?
# Sourced by scripts/store-screenshots.sh and scripts/store-screenshots-android.sh before they build.
#
# WHY. A relay whose `[RELAY_PROTOCOL_MIN_SUPPORTED_VERSION, RELAY_PROTOCOL_VERSION]` range misses
# the client's version answers every realm registration with 426, and every later call of that
# device (push, usage, content PUTs) with 401: the realm never exists there. A walk then fails 40
# minutes in, at its first wait on sync — Drive's iOS walk sits behind "Uploading…" for 300 s while
# the background session takes ~140 401s a second (store-screenshots.md §3, "the dev relay is
# behind"). The release cut restarted RELAY_PROTOCOL_VERSION at 1, so a dev relay built before it
# (range 4..4) refuses every client built after it.
#
# HOW. `POST /sync/realm` with every required field present but none valid: the relay checks the
# version right after the fields (RealmRouting.kt), so a refused version is a 426 naming the range,
# and an accepted one fails the key decoding with a 400 — nothing is ever written.
#
# ⚠ The relay's own 426 text says "this client must be updated" for any version below its floor,
# which after a version RESET is backwards: it is the relay that is old. This check says so.

# $1 = repo root, $2 = relay origin. Returns 1 (after printing why) unless the relay provably serves it.
devlab_relay_protocol_ok() {
  local root="$1" relay="$2" version answer code
  version="$(sed -n 's/^const val RELAY_PROTOCOL_VERSION: Int = \([0-9][0-9]*\).*/\1/p' \
    "$root/core-common/src/commonMain/kotlin/org/tenshu/core/common/RelayProtocol.kt")"
  [ -n "$version" ] || { echo "devlab-relay-protocol: could not read RELAY_PROTOCOL_VERSION" >&2; return 1; }
  answer="$(curl -s --max-time 15 -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d "{\"ownerId\":\"protocol-probe\",\"masterPublicKey\":\"-\",\"firstDevicePublicKey\":\"-\",\"firstDeviceId\":\"protocol-probe\",\"masterSelfSignature\":\"-\",\"relayProtocolVersion\":\"$version\"}" \
    "$relay/sync/realm" || true)"
  code="${answer##*$'\n'}"
  # 400 is the accepted version failing the key decoding — the one answer that proves the range.
  # Anything else (000 = no connection, 429, 5xx, a proxy page) proves nothing either way.
  [ "$code" = 400 ] && return 0
  if [ "$code" != 426 ]; then
    echo "could not tell whether the dev lab's relay ($relay) serves relay protocol $version: its probe" >&2
    echo "  answered HTTP $code (${answer%$'\n'*}) — expected 400 (served) or 426 (refused)" >&2
    return 1
  fi
  if [ "$code" = 426 ]; then
    echo "the dev lab's relay ($relay) refuses this checkout's relay protocol version $version:" >&2
    echo "  ${answer%$'\n'*}" >&2
    echo "  Every realm this run creates would be refused (426), and every call after it 401 — the walks" >&2
    echo "  would fail at their first sync wait. The relay must be redeployed from a commit that serves" >&2
    echo "  version $version (deploy-dev-lab-scaleway.md; ship-dev-stack.sh --only relay)." >&2
    return 1
  fi
}
