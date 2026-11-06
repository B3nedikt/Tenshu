#!/usr/bin/env bash
# Check that a zone serves every expected record, value for value.
#
#   ./scripts/dns-diff.sh                                     # DigitalOcean's zone vs live answers
#   NS=<ns>.ns.cloudflare.com ./scripts/dns-diff.sh           # DO's zone vs a new host, BEFORE switching
#   RECORDS=deploy/dns/records.json ./scripts/dns-diff.sh     # the inventory vs live answers
#   RECORDS=deploy/dns/records.json NS=<ns> ./scripts/dns-diff.sh
#
# ⚠⚠ This is what makes a zone migration checkable instead of hopeful. Querying the new host's
# nameservers DIRECTLY — before any NS change — answers "would the new zone serve what the old one
# does?" while the old one is still authoritative and nothing is at risk.
#
# Expected records come from one of two places:
#   doctl (default)  the live DigitalOcean zone — the source of truth for as long as DO is authoritative.
#                    Includes tiles.service, which the inventory deliberately does not.
#   RECORDS=<file>   the provider-neutral inventory — the source of truth once DO is retired, and the
#                    reason this check still works for whatever host comes after Cloudflare.
#
# ⚠⚠ It checks COMPLETENESS, not the absence of extras: DNS cannot list a zone, so a stray record at
# the new host passes. It compares FULL values: TXT strings are joined the way resolvers join them
# (a 420-char DKIM key is served as two strings), MX compares priority and target.
set -euo pipefail

ZONE="${ZONE:-tenshu.app}"
NS="${NS:-}"
RECORDS="${RECORDS:-}"

command -v dig >/dev/null || { echo "ERROR: dig not on PATH" >&2; exit 1; }

if [ -n "$RECORDS" ]; then
  [ -f "$RECORDS" ] || { echo "ERROR: $RECORDS not found" >&2; exit 1; }
  expected="$(cat "$RECORDS")"
  source_label="the inventory $RECORDS"
else
  command -v doctl >/dev/null || { echo "ERROR: doctl not on PATH (or set RECORDS=)" >&2; exit 1; }
  expected="$(doctl compute domain records list "$ZONE" -o json)"
  source_label="DigitalOcean's zone"
fi

if [ -n "$NS" ]; then
  echo "==> Comparing $source_label against $NS (authoritative query)"
else
  echo "==> Comparing $source_label against what resolvers currently serve"
fi
echo

ZONE="$ZONE" NS="$NS" python3 -c '
import json, os, subprocess, sys

zone, ns = os.environ["ZONE"], os.environ["NS"]
doc = json.load(sys.stdin)
# The inventory is {"zone", "records"}; doctl is a bare list with the value under "data".
rows = doc["records"] if isinstance(doc, dict) else doc
if isinstance(doc, dict) and doc.get("zone") not in (None, zone):
    sys.exit("ERROR: inventory is for %s, not %s" % (doc["zone"], zone))

def fqdn(name):
    return zone if name == "@" else "%s.%s" % (name, zone)

def txt_value(line):
    # One answer line is one TXT record made of quoted strings; resolvers concatenate them with NO
    # separator. Comparing the joined value is what makes a 255-byte split invisible, as it should be.
    # dig escapes a quote or backslash inside a string as \" or \\, and other bytes as \DDD (decimal).
    out, i, quoted = [], 0, False
    while i < len(line):
        c = line[i]
        if c == "\"":
            quoted, i = not quoted, i + 1
        elif not quoted:
            i += 1
        elif c == "\\" and line[i + 1:i + 4].isdigit() and len(line[i + 1:i + 4]) == 3:
            out.append(chr(int(line[i + 1:i + 4])))
            i += 4
        elif c == "\\":
            out.append(line[i + 1:i + 2])
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)

def normalise(rtype, value, priority=None):
    if rtype == "TXT":
        return value
    value = value.strip().rstrip(".").lower()
    if rtype == "MX":
        return "%s %s" % (priority, value)
    return value

def served(rtype, name):
    cmd = ["dig", "+time=3", "+tries=2"]
    if ns:
        cmd.append("@" + ns)
    if rtype == "NS":
        # ⚠ A parent answers a delegation with a REFERRAL: the NS records sit in the authority
        # section and +short prints nothing, so a correct delegation would read as missing.
        out = subprocess.run(cmd + ["+norecurse", "+noall", "+answer", "+authority", "NS", fqdn(name)],
                             capture_output=True, text=True).stdout
        return {normalise("NS", f[4]) for f in (l.split() for l in out.splitlines())
                if len(f) >= 5 and f[3] == "NS" and f[0].rstrip(".").lower() == fqdn(name).lower()}
    out = subprocess.run(cmd + ["+short", rtype, fqdn(name)], capture_output=True, text=True).stdout
    got = set()
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith(";"):
            continue
        if rtype == "TXT":
            got.add(txt_value(line))
        elif rtype == "MX":
            prio, _, target = line.partition(" ")
            got.add(normalise("MX", target, prio))
        else:
            got.add(normalise(rtype, line))
    return got

fail = checked = 0
for r in rows:
    rtype = r["type"]
    if rtype == "SOA" or (rtype == "NS" and r["name"] == "@"):
        continue   # the zone plumbing; it differs per host by definition. NS BELOW the apex is a
                   # delegation, and is checked like any other record.
    value = r.get("content", r.get("data"))
    want = normalise(rtype, value, r.get("priority"))
    got = served(rtype, r["name"])
    checked += 1
    if want in got:
        print("  ok    %-6s %s" % (rtype, fqdn(r["name"])))
    else:
        fail += 1
        print("  MISS  %-6s %s" % (rtype, fqdn(r["name"])))
        print("        want: %s" % want[:100])
        print("        got:  %s" % (" | ".join(sorted(g[:100] for g in got)) or "(nothing)"))
        # A long value differing past the 100 shown characters would otherwise print as two
        # identical lines — which is exactly what a one-character DKIM corruption looks like.
        for g in got:
            at = next((i for i, (a, b) in enumerate(zip(want, g)) if a != b), min(len(want), len(g)))
            if at >= 100:
                print("        first difference at character %d" % at)

print()
sys.stdout.flush()
if fail:
    print("==> %d of %d records MISMATCH. Do not switch nameservers." % (fail, checked), file=sys.stderr)
    sys.exit(1)
print("==> All %d records match." % checked)
if ns:
    print("    This nameserver is complete. Check every one the new host assigned before switching.")
' <<<"$expected"
