#!/usr/bin/env bash
# Stage the OVH boxes from the Scaleway ones while production keeps running. Re-runnable.
#
#   ./1-prepare.sh [relay|commerce|apex|all]
#
# Copies the exact running images, /opt/tenshu, the tenshu-* systemd units (left disabled) and
# Caddy's volumes (certificates included), points the env files at OVH storage, then runs the
# checks that only the new box can answer. Starts NOTHING and touches no production data: the old
# boxes are only read.
. "$(dirname "$0")/lib.sh"
ROLES="$(roles_from_args "$@")"
# shellcheck disable=SC2086
require_hosts $ROLES

for r in $ROLES; do
  say "$r: $(role_old "$r") -> $(role_new "$r")"
  check_new_host "$r" || die "$r: the new host is not ready"
  [ -z "$(running_on "$(role_new "$r")" "$r")" ] || die "$r: the stack is already running on the NEW box; 1-prepare is for an idle box"
  copy_images "$r"
  sync_opt "$r"
  apply_overrides "$r"
  pin_running_tag "$r"
  copy_volumes "$r"

  # The compose files must render on the new box with its env, images present, nothing pulled.
  compose_on "$(role_new "$r")" "$r" config -q && ok "compose config renders"

  case "$r" in
    relay)
      # ⚠⚠ The one check no test replaces: does THIS bucket return what was written? A store that
      # does not decode aws-chunked framing returns more bytes than went in, at the real 2 MiB size.
      img="$(rsh "$(role_old relay)" <<'R'
docker inspect sync-relay-server-relay-1 --format '{{.Config.Image}}'
R
      )"
      rsh "$(role_new relay)" "$img" <<'R' && ok "content-bucket preflight passed"
set -euo pipefail
docker run --rm --env-file /opt/tenshu/deploy.env --entrypoint java "$1" \
  -cp /app/app.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt --preflight
R
      ;;
    commerce)
      # Mail stays on Scaleway TEM. Some clouds filter SMTP ports; prove this one reaches it.
      rsh "$(role_new commerce)" <<'R' && ok "SMTP relay reachable"
set -euo pipefail
. /opt/tenshu/deploy.env
timeout 10 bash -c "exec 3<>/dev/tcp/$SMTP_HOST/$SMTP_PORT && head -c 3 <&3" | grep -q 220 \
  || { echo "no SMTP greeting from $SMTP_HOST:$SMTP_PORT"; exit 1; }
R
      ;;
  esac
done

say "prepared: $ROLES. Next: ./2-copy-blobs.sh (relay live), then ./3-cutover.sh in the window."
