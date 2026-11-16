#!/usr/bin/env bash
# Copy the content-plane bucket Scaleway -> OVH, run on the NEW relay box. Re-runnable.
#
#   ./2-copy-blobs.sh               # missing / different-size keys; fine while the old relay runs
#   ./2-copy-blobs.sh --verify-all  # byte-compares everything; run with the old relay STOPPED
#
# Reads source credentials from /opt/tenshu/deploy.env.scaleway (the untouched copy 1-prepare kept)
# and the destination from /opt/tenshu/deploy.env (after the OVH overrides). Never writes the source.
. "$(dirname "$0")/lib.sh"
require_hosts relay
ssh "${SSH_OPTS[@]}" "root@$NEW_RELAY" "cat > /opt/tenshu/copy-bucket.py" < "$MIGRATE_DIR/copy-bucket.py"
rsh "$NEW_RELAY" "$@" <<'R'
set -euo pipefail
python3 /opt/tenshu/copy-bucket.py /opt/tenshu/deploy.env.scaleway /opt/tenshu/deploy.env "$@"
R
