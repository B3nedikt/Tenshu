#!/usr/bin/env bash
# THE WINDOW. Stops production relay + commerce on Scaleway, moves their data to OVH, starts all
# three stacks on OVH and checks them over HTTPS (by IP, with --resolve) BEFORE any DNS change.
#
#   ./3-cutover.sh
#
# Downtime starts at step 2 and ends when DNS has moved (./4-switch-dns.sh, TTL 300 s). Apex stays
# up on Scaleway throughout: it is static and holds no data.
#
# ⚠⚠ Until ./4-switch-dns.sh, every client still talks to Scaleway, where relay and commerce are
# now STOPPED, so nothing can write to either side. Backing out at any point before the DNS switch
# is ./rollback.sh, and it loses nothing.
. "$(dirname "$0")/lib.sh"
require_hosts relay commerce apex
T0=$(date +%s)
elapsed() { echo "$(( $(date +%s) - T0 ))s"; }

say "0. preconditions"
for r in relay commerce apex; do
  check_new_host "$r" >/dev/null || die "$r: new host not ready (./1-prepare.sh first)"
  rsh "$(role_new "$r")" "$(role_env "$r")" <<'EOF' || die "$r: not prepared (./1-prepare.sh first)"
[ -f "$1.scaleway" ]
EOF
  [ -z "$(running_on "$(role_new "$r")" "$r")" ] || die "$r: already running on the new box"
done
ok "all three OVH hosts are prepared and idle"
confirm "This STOPS production relay and commerce on Scaleway."

say "1. last backups on Scaleway (the rollback point)"
rsh "$OLD_RELAY" <<'EOF'
systemctl start tenshu-relay-backup.service && tail -n 3 /var/log/tenshu-relay-backup.log 2>/dev/null || journalctl -u tenshu-relay-backup -n 5 --no-pager
EOF
rsh "$OLD_COMMERCE" <<'EOF'
systemctl start tenshu-commerce-backup.service && journalctl -u tenshu-commerce-backup -n 5 --no-pager
EOF
ok "backups done ($(elapsed))"

say "2. stop the writers on Scaleway (Caddy stays up, so a stale client gets a 502, not a wrong answer)"
compose_on "$OLD_RELAY" relay stop relay
compose_on "$OLD_COMMERCE" commerce stop commerce
ok "relay and commerce stopped — downtime starts ($(elapsed))"

say "3. relay database"
# ⚠⚠ CHECKPOINT first. Copying relay.db without its WAL gives a valid, EMPTY database and no error
# anywhere (measured, docs/how-to/move-the-relay-to-another-provider.md). A clean stop does not
# checkpoint.
COUNTS_SQL="SELECT 'sync_block',COUNT(*) FROM sync_block UNION ALL SELECT 'content_blob',COUNT(*) FROM content_blob UNION ALL SELECT 'content_ref',COUNT(*) FROM content_ref UNION ALL SELECT 'device',COUNT(*) FROM device UNION ALL SELECT 'realm',COUNT(*) FROM realm;"
OLD_COUNTS="$(rsh "$OLD_RELAY" "$COUNTS_SQL" <<'EOF'
set -euo pipefail
cd /mnt/relay_data
sqlite3 relay.db "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null
[ ! -s relay.db-wal ] || { echo "WAL still holds $(stat -c %s relay.db-wal) bytes after the checkpoint" >&2; exit 1; }
sqlite3 -readonly relay.db "$1"
EOF
)" || die "relay checkpoint failed; nothing copied (./rollback.sh restarts the old relay)"
ssh "${SSH_OPTS[@]}" "root@$OLD_RELAY" \
  "tar -C /mnt/relay_data --exclude=./lost+found --exclude=./relay.db-wal --exclude=./relay.db-shm -czf - ." \
  | ssh "${SSH_OPTS[@]}" "root@$NEW_RELAY" \
  "rm -f /mnt/relay_data/relay.db-wal /mnt/relay_data/relay.db-shm && tar --numeric-owner -C /mnt/relay_data -xzf - && chmod -R a+rwX /mnt/relay_data"
NEW_COUNTS="$(rsh "$NEW_RELAY" "$COUNTS_SQL" <<'EOF'
set -euo pipefail
[ "$(sqlite3 -readonly /mnt/relay_data/relay.db 'PRAGMA quick_check;')" = ok ] || { echo "quick_check failed" >&2; exit 1; }
sqlite3 -readonly /mnt/relay_data/relay.db "$1"
EOF
)"
echo "$OLD_COUNTS" | sed 's/^/   scaleway  /'
[ "$OLD_COUNTS" = "$NEW_COUNTS" ] || { echo "$NEW_COUNTS" | sed 's/^/   ovh       /'; die "relay row counts differ"; }
ok "relay.db copied, quick_check ok, row counts identical ($(elapsed))"

say "4. commerce database"
PG_COUNTS="SELECT 'account', count(*) FROM account UNION ALL SELECT 'account_owner', count(*) FROM account_owner UNION ALL SELECT 'session', count(*) FROM session UNION ALL SELECT 'entitlement_history', count(*) FROM entitlement_history UNION ALL SELECT 'acceptance', count(*) FROM acceptance UNION ALL SELECT 'invoice', count(*) FROM invoice UNION ALL SELECT 'schema_version', count(*) FROM schema_version;"
pg_counts() {
  rsh "$1" "$PG_COUNTS" <<'EOF'
set -euo pipefail
for _ in $(seq 1 30); do docker exec commerce-service-db-1 pg_isready -U commerce -q && break; sleep 2; done
docker exec commerce-service-db-1 psql -U commerce -d commerce -t -A -F'|' -c "$1"
EOF
}
OLD_PG="$(pg_counts "$OLD_COMMERCE")"
compose_on "$OLD_COMMERCE" commerce stop db
# ⚠ --numeric-owner: pgdata belongs to uid 999 (postgres inside the container), which is an
# unrelated named user on each host (fwupd-refresh on the Scaleway box). By name, tar would remap it.
ssh "${SSH_OPTS[@]}" "root@$OLD_COMMERCE" "tar -C /mnt/commerce_data -czf - pgdata" \
  | ssh "${SSH_OPTS[@]}" "root@$NEW_COMMERCE" \
  "rm -rf /mnt/commerce_data/pgdata && tar --numeric-owner -C /mnt/commerce_data -xzf -"
compose_on "$NEW_COMMERCE" commerce up -d --pull never db
NEW_PG="$(pg_counts "$NEW_COMMERCE")"
echo "$OLD_PG" | sed 's/^/   scaleway  /'
[ "$OLD_PG" = "$NEW_PG" ] || { echo "$NEW_PG" | sed 's/^/   ovh       /'; die "commerce row counts differ"; }
ok "pgdata copied, row counts identical ($(elapsed))"

say "5. refresh config from Scaleway (anything edited since 1-prepare), then the final bucket pass"
for r in relay commerce apex; do
  copy_images "$r"
  sync_opt "$r"
  apply_overrides "$r"
  pin_running_tag "$r"
done
"$MIGRATE_DIR/2-copy-blobs.sh" --verify-all || die "content bucket does not match; NOT starting the new relay"
ok "config and bucket in sync ($(elapsed))"

say "6. start the stacks on OVH"
for r in relay commerce apex; do
  compose_on "$(role_new "$r")" "$r" up -d --pull never
done
sleep 15
fail=0
for r in relay commerce apex; do
  rsh "$(role_new "$r")" "$(role_project "$r")" <<'EOF' || fail=1
docker ps -a --filter "label=com.docker.compose.project=$1" --format '{{.Names}}' | while read -r c; do
  st="$(docker inspect "$c" --format '{{.State.Status}} restarts={{.RestartCount}}')"
  echo "   $c: $st"
  case "$st" in "running restarts=0") ;; *) exit 1;; esac
done
EOF
done
[ "$fail" = 0 ] || die "a container is not running cleanly on OVH (docker logs on the box). ./rollback.sh is safe: DNS has not moved."

say "7. HTTPS checks against the OVH IPs (DNS still points at Scaleway)"
check() { # url ip expected-code
  local host code; host="$(echo "$1" | sed -E 's#https://([^/]+).*#\1#')"
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 15 --resolve "$host:443:$2" "$1" || true)"
  if [ "$code" = "$3" ]; then ok "$1 -> $code"; else warn "$1 -> $code (expected $3)"; fail=1; fi
}
check "https://relay.tenshu.app/health" "$NEW_RELAY" 200
curl -s -m 15 --resolve "relay.tenshu.app:443:$NEW_RELAY" https://relay.tenshu.app/health | sed 's/^/   relay health: /'; echo
check "https://commerce.service.tenshu.app/health" "$NEW_COMMERCE" 200
for d in $(role_domains apex); do check "https://$d/" "$NEW_APEX" 200; done
[ "$fail" = 0 ] || die "an OVH endpoint failed its check. DNS has not moved; ./rollback.sh is safe."

say "OVH is serving and verified ($(elapsed) since the start). Clients still see Scaleway."
echo "   Next, now: ./4-switch-dns.sh"
