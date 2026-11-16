#!/usr/bin/env bash
# After the DNS switch: wait until public resolvers answer with OVH, check the public endpoints,
# then hand the timers (backups, blob mirror, disk check) from the Scaleway boxes to the OVH ones
# and run the first OVH backups.
#
#   ./5-finish.sh
#
# ⚠⚠ Leaves the Scaleway boxes STOPPED but intact. They are the rollback, and the only copy of
# anything the window missed. Destroy them after a quiet week, not before (move-to-ovh.md).
. "$(dirname "$0")/lib.sh"
require_hosts relay commerce apex

say "1. waiting for public resolvers to answer with the OVH addresses"
for r in relay commerce apex; do
  for d in $(role_domains "$r"); do
    for ns in 1.1.1.1 8.8.8.8; do
      for i in $(seq 1 60); do
        [ "$(dig +short @"$ns" A "$d" | tail -n1)" = "$(role_new "$r")" ] && break
        [ "$i" = 60 ] && die "$d still resolves to $(dig +short @"$ns" A "$d") at $ns after 10 minutes"
        sleep 10
      done
    done
    ok "$d -> $(role_new "$r")"
  done
done

say "2. public checks (real DNS, no --resolve)"
fail=0
for url in https://relay.tenshu.app/health https://commerce.service.tenshu.app/health $(for d in $(role_domains apex); do echo "https://$d/"; done); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 15 "$url" || true)"
  if [ "$code" = 200 ]; then ok "$url"; else warn "$url -> $code"; fail=1; fi
done
[ "$fail" = 0 ] || die "a public endpoint failed; investigate before moving the timers"

say "3. stop what still runs on Scaleway, and its timers"
for r in relay commerce apex; do
  compose_on "$(role_old "$r")" "$r" stop
  # shellcheck disable=SC2046
  rsh "$(role_old "$r")" $(role_timers "$r") <<'EOF'
systemctl disable --now "$@" 2>&1 | grep -v '^Removed' || true
EOF
  ok "$r: Scaleway stack stopped, timers disabled"
done

say "4. enable the timers on OVH and run the first backups"
for r in relay commerce apex; do
  # shellcheck disable=SC2046
  rsh "$(role_new "$r")" $(role_timers "$r") <<'EOF'
systemctl enable --now "$@" 2>&1 | grep -v '^Created' || true
EOF
  ok "$r: timers enabled on OVH"
done
rsh "$NEW_RELAY" <<'EOF' && ok "first relay backup to OVH" || warn "relay backup FAILED: journalctl -u tenshu-relay-backup"
systemctl start tenshu-relay-backup.service
EOF
rsh "$NEW_COMMERCE" <<'EOF' && ok "first commerce backup to OVH" || warn "commerce backup FAILED: journalctl -u tenshu-commerce-backup"
systemctl start tenshu-commerce-backup.service
EOF

say "5. real client traffic on the OVH relay (last 10 minutes)"
rsh "$NEW_RELAY" <<'EOF'
docker logs --since 10m sync-relay-server-caddy-1 2>&1 | grep -oE '"(GET|POST|PUT) [^ ?"]+' | sort | uniq -c | sort -rn | head -10 || true
EOF

say "done. Still to do by hand:"
cat <<'TXT'
   - Open the apps; make an edit on one device and watch it arrive on another; upload a file
     (chunk PUTs, then POST /sync/content/declare, on the OVH relay's log).
   - Sign in once (an OTP mail through Scaleway TEM from the new box).
   - Commit deploy/dns/records.json.
   - Run the blob mirror once: ssh root@NEW_RELAY systemctl start tenshu-blob-mirror.service
   - After a quiet week: destroy the Scaleway boxes and buckets (move-to-ovh.md, "Teardown").
TXT
