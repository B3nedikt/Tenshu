#!/usr/bin/env bash
# Take down ONE share link by its shareId — notice-and-action (docs/policy/acceptable-use-and-limits.md
# §3). Runbook: docs/how-to/take-down-a-share-link.md. Read it before the first use.
#
#   deploy/share-takedown.sh <relay-host> <share-url-or-id> <reason> [note]
#   deploy/share-takedown.sh --local      <share-url-or-id> <reason> [note]   # a dev relay on 127.0.0.1:8080
#
# reason: illegal_content | csam | ip_infringement | malware_or_phishing | terms_violation |
#         authority_order | other
# note:   one line, ≤ 500 chars: a ticket reference. NEVER a description of the content, NEVER the
#         reporter's name or address — the audit row is kept and is not erased with the account.
#
# What it does: calls the relay's POST /ops/share/takedown from INSIDE the relay container's network
# namespace (the route answers only a loopback socket peer holding RELAY_OPERATOR_TOKEN; Caddy refuses
# /ops/* at the edge). The relay revokes the link (recipients get the same 410 as a revoke or an expiry),
# deletes the link's chunk ciphertext and appends one row to the `share_takedown` audit table. The
# owner's durable content is not touched. Prints the relay's JSON answer; `outcome` is taken_down,
# already_taken_down or not_found. Safe to repeat: a repeat retries a byte delete that failed.
#
# The token never leaves the box: it is read from /opt/tenshu/deploy.env over ssh. The URL's `#k=…`
# fragment (the decryption key) is stripped here and never sent anywhere.
set -euo pipefail

usage() { sed -n '2,10p' "$0" >&2; exit 2; }
[[ $# -ge 3 && $# -le 4 ]] || usage
TARGET="$1" INPUT="$2" REASON="$3" NOTE="${4:-}"

# --- the shareId, from a reported URL or the bare id -------------------------------------------
ID="${INPUT%%#*}"            # ⚠ drop the key fragment first, whatever else the input looks like
ID="${ID%%\?*}"
ID="${ID%/}"
case "$ID" in
  */s/*) ID="${ID##*/s/}" ;;
  */share/*) ID="${ID##*/share/}"; ID="${ID%%/*}" ;;
esac
[[ "$ID" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || { echo "not a shareId: '$ID' (expected https://<apex>/s/<shareId> or the id)" >&2; exit 2; }

case "$REASON" in
  illegal_content|csam|ip_infringement|malware_or_phishing|terms_violation|authority_order|other) ;;
  *) echo "unknown reason '$REASON'" >&2; usage ;;
esac
# The relay refuses any control character (a tab too) and counts UTF-16 units; check the same here.
python3 -c 'import sys; n=sys.argv[1]; sys.exit(0 if len(n.encode("utf-16-le"))//2 <= 500 and not any(ord(c) < 32 or 127 <= ord(c) < 160 for c in n) else 1)' "$NOTE" \
  || { echo "note: one line, no control characters, at most 500 characters" >&2; exit 2; }
if [[ "$TARGET" != "--local" && ! "$TARGET" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
  echo "relay-host: a host name or address, got '$TARGET'" >&2; exit 2
fi

# The JSON body, built by a real encoder (the note is free text) and shipped base64 so no shell on
# either side ever re-parses it.
BODY_B64="$(python3 -c 'import json,sys,base64
d={"shareId":sys.argv[1],"reason":sys.argv[2]}
if sys.argv[3]: d["note"]=sys.argv[3]
print(base64.b64encode(json.dumps(d).encode()).decode())' "$ID" "$REASON" "$NOTE")"

echo "Taking down shareId=$ID reason=$REASON on $TARGET …" >&2

if [[ "$TARGET" == "--local" ]]; then
  : "${RELAY_OPERATOR_TOKEN:?set RELAY_OPERATOR_TOKEN to the operator token of the dev relay}"
  printf '%s' "$BODY_B64" | base64 -d | curl -sS --fail-with-body -X POST \
    -H "X-Operator-Token: $RELAY_OPERATOR_TOKEN" -H 'Content-Type: application/json' \
    --data-binary @- "http://127.0.0.1:${RELAY_PORT:-8080}/ops/share/takedown"
  echo
  exit 0
fi

# ⚠ The remote script is a quoted heredoc fed to `bash -s`, and the values travel as positional
# arguments: nothing is interpolated into a hand-quoted ssh string (that pattern has broken before).
ssh -- "root@${TARGET}" bash -s -- "$BODY_B64" <<'REMOTE'
set -euo pipefail
BODY_B64="$1"
ENV=/opt/tenshu/deploy.env
# Strip optional surrounding quotes, as compose does when it reads the same line.
T="$(sed -n 's/^RELAY_OPERATOR_TOKEN=//p' "$ENV" | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")"
if [[ -z "$T" ]]; then
  echo "RELAY_OPERATOR_TOKEN is not set in $ENV, so the operator routes do not exist." >&2
  echo "Enable it first: docs/how-to/take-down-a-share-link.md, 'Enable the operator route'." >&2
  exit 3
fi
if [[ "$(printf '%s\n' "$T" | wc -l)" -ne 1 ]]; then
  echo "RELAY_OPERATOR_TOKEN appears more than once in $ENV; keep exactly one line." >&2
  exit 3
fi
# The token goes in a 0600 curl config file, not on any command line (`ps` on the box would show it).
CFG="$(mktemp "${TMPDIR:-/tmp}/takedown-curlrc.XXXXXX")"; trap 'rm -f "$CFG"' EXIT
chmod 600 "$CFG"
printf 'header = "X-Operator-Token: %s"\n' "$T" > "$CFG"
# Pinned by digest: this image runs inside the relay's network namespace.
printf '%s' "$BODY_B64" | base64 -d | docker run --rm -i --network container:sync-relay-server-relay-1 \
  -v "$CFG:/run/ops.curlrc:ro" --user 0 \
  curlimages/curl:8.10.1@sha256:d9b4541e214bcd85196d6e92e2753ac6d0ea699f0af5741f8c6cccbfcf00ef4b \
  -sS --fail-with-body -K /run/ops.curlrc -X POST -H 'Content-Type: application/json' \
  --data-binary @- http://127.0.0.1:8080/ops/share/takedown
echo
REMOTE
