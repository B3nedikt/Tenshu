# DNS: the record inventory

[`records.json`](records.json) is every record `tenshu.app` serves, in a shape no DNS host owns. The
host-specific part lives in an adapter over it — today [`deploy/cloudflare-dns/`](../cloudflare-dns/)
— so moving to another host is a new adapter, not a re-typing of the zone.

It is read by:

| | |
|---|---|
| [`deploy/cloudflare-dns`](../cloudflare-dns/) | creates each record, DNS-only |
| `RECORDS=deploy/dns/records.json scripts/dns-diff.sh` | proves a nameserver serves every record, before and after a switch |

## Shape

```json
{ "zone": "tenshu.app",
  "records": [ { "name": "@", "type": "MX", "content": "blackhole.tem.scaleway.com", "ttl": 14400, "priority": 10 } ] }
```

- `name` is relative to the zone, `@` for the root.
- `content` is the value as a resolver returns it: TXT **unquoted and unsplit**, CNAME/MX targets
  **without** a trailing dot. Quoting, 255-character splitting and dots are each adapter's job,
  because hosts disagree on all three.
- Types: A, AAAA, CNAME, MX, TXT. ⚠ The export fails on anything else rather than dropping it.

## ⚠ Not in here, on purpose

- **SOA and the apex NS records** — every host generates its own. ⚠ An NS record *below* the apex is
  a delegation, not plumbing: the export fails on one rather than dropping it, because the shape has
  no place for it yet.
- **`tiles.service`** — a DNS-only CNAME to DigitalOcean's CDN until the tiles swap, then an R2 custom
  domain, which is not a record any other host could serve. The Cloudflare adapter owns it
  (`tiles_cname_target`); `scripts/dns-export.sh` prints the current value.

## Changing a record

⚠⚠ **While DigitalOcean is authoritative, DigitalOcean is the truth.** Change the record there,
re-run `scripts/dns-export.sh`, and commit the diff — never edit this file first, or the next export
silently reverts you.

After the move, this file is the truth: edit it, apply `deploy/cloudflare-dns`, run `dns-diff`.
⚠ Until DigitalOcean's zone is retired it is also the rollback, so a change in that window goes to
**both** — [`move-dns-to-cloudflare.md`](../../docs/how-to/move-dns-to-cloudflare.md) §7.

⚠ Never retype the DKIM record. It is a 420-character key where one wrong character fails signature
verification silently: mail still sends, and lands in spam days later.
