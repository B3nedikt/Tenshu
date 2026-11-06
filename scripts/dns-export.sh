#!/usr/bin/env bash
# Export the live DigitalOcean zone into the provider-neutral record inventory.
#
#   ./scripts/dns-export.sh          # writes deploy/dns/records.json
#   OUT=- ./scripts/dns-export.sh    # prints it instead
#
# ⚠⚠ The inventory is only as fresh as its last export. Run this IMMEDIATELY before applying a DNS
# host's module, never from a copy made earlier: a record changed at DigitalOcean after the export
# would be applied at the new host with its OLD value.
#
# ⚠ Never retype a record instead. The DKIM value is a 420-character key where one wrong character
# fails signature verification silently — mail still sends, and lands in spam days later.
#
# Deliberately left out:
#   SOA, apex NS   the zone's own plumbing; every host generates its own
#                  (an NS record BELOW the apex is a delegation, and fails the export like any other
#                  type the inventory cannot hold)
#   tiles.service  owned by the tiles adapter, not the inventory — printed below so it is not lost
set -euo pipefail

ZONE="${ZONE:-tenshu.app}"
OUT="${OUT:-$(cd "$(dirname "$0")/.." && pwd)/deploy/dns/records.json}"

command -v doctl >/dev/null || { echo "ERROR: doctl not on PATH" >&2; exit 1; }

doctl compute domain records list "$ZONE" -o json | ZONE="$ZONE" OUT="$OUT" python3 -c '
import json, os, sys

zone, out = os.environ["ZONE"], os.environ["OUT"]
# ⚠ The neutral shape holds these five types and nothing else. An unsupported type FAILS rather than
# being skipped: a CAA or SRV record dropped here would vanish at the new host without a word.
SUPPORTED = {"A", "AAAA", "CNAME", "MX", "TXT"}

records, tiles, unsupported = [], None, []
for r in json.load(sys.stdin):
    if r["type"] == "SOA" or (r["type"] == "NS" and r["name"] == "@"):
        continue
    if (r["type"], r["name"]) == ("CNAME", "tiles.service"):
        tiles = r["data"].rstrip(".")
        continue
    if r["type"] not in SUPPORTED:
        unsupported.append("%s %s" % (r["type"], r["name"]))
        continue
    rec = {"name": r["name"], "type": r["type"], "content": r["data"], "ttl": r["ttl"]}
    if r["type"] in ("CNAME", "MX"):
        # ⚠ Stored WITHOUT the trailing dot; each host adapter adds or strips it as that host needs.
        rec["content"] = rec["content"].rstrip(".")
    if r["type"] == "MX":
        rec["priority"] = r["priority"]
    records.append(rec)

if unsupported:
    sys.exit("ERROR: record types the inventory cannot hold: " + ", ".join(unsupported))

records.sort(key=lambda x: (x["name"] != "@", x["name"], x["type"], x["content"]))
text = json.dumps({"zone": zone, "records": records}, indent=2) + "\n"
if out == "-":
    sys.stdout.write(text)
else:
    open(out, "w").write(text)
    print("==> Wrote %s (%d records)" % (out, len(records)), file=sys.stderr)

if tiles:
    print("==> tiles.service is a CNAME to %s and is NOT in the inventory." % tiles, file=sys.stderr)
    print("    While DigitalOcean still serves tiles, deploy/cloudflare-dns/terraform.tfvars needs:", file=sys.stderr)
    print("      tiles_cname_target = \"%s\"" % tiles, file=sys.stderr)
'
