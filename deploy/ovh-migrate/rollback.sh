#!/usr/bin/env bash
# Back to Scaleway: point DNS back if it moved, stop the OVH stacks, start the Scaleway ones and
# give the timers back.
#
#   ./rollback.sh
#
# ⚠⚠ Lossless only BEFORE ./4-switch-dns.sh. After it, any write a client made to the OVH relay or
# commerce is LOST: two relay logs cannot be merged, and the Scaleway databases are as they were at
# the stop. The later this runs, the more it loses.
. "$(dirname "$0")/lib.sh"
require_hosts relay commerce apex

moved=0
grep -q "\"$NEW_RELAY\"" "$DEPLOY_DIR/dns/records.json" && moved=1
if [ "$moved" = 1 ]; then
  warn "DNS points at OVH: writes made there since the switch will be LOST."
fi
confirm "Roll production back to Scaleway?"

say "1. stop the OVH stacks and their timers"
for r in relay commerce apex; do
  compose_on "$(role_new "$r")" "$r" stop || true
  # shellcheck disable=SC2046
  rsh "$(role_new "$r")" $(role_timers "$r") <<'EOF'
systemctl disable --now "$@" >/dev/null 2>&1 || true
EOF
done
ok "OVH stopped"

say "2. start Scaleway again"
for r in relay commerce apex; do
  compose_on "$(role_old "$r")" "$r" start
  # shellcheck disable=SC2046
  rsh "$(role_old "$r")" $(role_timers "$r") <<'EOF'
systemctl enable --now "$@" >/dev/null 2>&1 || true
EOF
  running_on "$(role_old "$r")" "$r" | sed 's/^/   /'
done

if [ "$moved" = 1 ]; then
  say "3. DNS back to Scaleway"
  "$MIGRATE_DIR/4-switch-dns.sh" --back
fi
say "rolled back. The OVH boxes keep their copy; ./1-prepare.sh refreshes it for another attempt."
