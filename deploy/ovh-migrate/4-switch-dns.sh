#!/usr/bin/env bash
# Point every A record that names a Scaleway box at its OVH twin, through the usual path:
# deploy/dns/records.json, then deploy/cloudflare-dns. Shows the diff and the plan, asks first.
#
#   ./4-switch-dns.sh            # Scaleway -> OVH
#   ./4-switch-dns.sh --back     # OVH -> Scaleway (used by ./rollback.sh)
#
# ⚠ records.json is the inventory every DNS change goes through; never flip records in the
# Cloudflare dashboard (deploy/cloudflare-dns/README.md). Commit the edited file afterwards.
. "$(dirname "$0")/lib.sh"
require_hosts relay commerce apex
RECORDS="$DEPLOY_DIR/dns/records.json"

if [ "${1:-}" = "--back" ]; then MAP="$NEW_RELAY=$OLD_RELAY $NEW_COMMERCE=$OLD_COMMERCE $NEW_APEX=$OLD_APEX"
else MAP="$OLD_RELAY=$NEW_RELAY $OLD_COMMERCE=$NEW_COMMERCE $OLD_APEX=$NEW_APEX"; fi

# shellcheck disable=SC2086
python3 - "$RECORDS" $MAP <<'PY'
import json, sys
path, pairs = sys.argv[1], dict(p.split("=", 1) for p in sys.argv[2:])
doc = json.load(open(path))
changed = 0
for r in doc["records"]:
    for field in ("value", "content", "data"):
        if r.get("type") == "A" and r.get(field) in pairs:
            r[field] = pairs[r[field]]; changed += 1
json.dump(doc, open(path, "w"), indent=2, ensure_ascii=False)
open(path, "a").write("\n")
print(f"   {changed} A records rewritten")
PY

git -C "$DEPLOY_DIR" --no-pager diff --stat -- dns/records.json
cd "$DEPLOY_DIR/cloudflare-dns"
terraform plan -out=ovh-switch.tfplan
confirm "Apply this DNS change? (TTL is 300 s.)"
terraform apply ovh-switch.tfplan
rm -f ovh-switch.tfplan
say "DNS updated. Next: ./5-finish.sh (waits for resolvers, then moves the timers)."
