# DNS: the tenshu.app zone on Cloudflare

A thin adapter over the provider-neutral inventory in [`deploy/dns/records.json`](../dns/records.json):
it creates the zone and every record in it, **DNS-only**. The move itself — order, checks, rollback —
is [`docs/how-to/move-dns-to-cloudflare.md`](../../docs/how-to/move-dns-to-cloudflare.md), and so is
*why* Cloudflare and what that does and does not put in its path.

## ⚠⚠ Never proxy a record here

Relay, apex and commerce each terminate TLS in their own Caddy with a Let's Encrypt certificate.
Turning one to Proxied puts Cloudflare in that TLS path — which
[`sync-relay-server/Caddyfile`](../../sync-relay-server/Caddyfile) records as a separate, explicit
decision — pushes Caddy's ACME validation through Cloudflare's proxy (its TLS-ALPN challenge cannot
pass through at all), caps request bodies at 100 MB on Free (a 413) and ends any origin response
slower than 125 s (a 524). `proxied = false` is hard-coded, not a variable, and the dashboard is the
one place that can still flip it — so records are changed through the inventory, never by hand.

Tiles is the only proxied hostname in the zone, and it is not declared here (below).

## What it owns

| | owner |
|---|---|
| the zone | **here** (`prevent_destroy`) |
| every record in `records.json` — apex, relay, commerce, SPF, DKIM, DMARC, MX | **here** |
| `CNAME tiles.service` → DigitalOcean's CDN, while that still serves tiles | **here**, via `tiles_cname_target` |
| `tiles.service` after the swap | the R2 custom domain in `deploy/cloudflare-tiles` |

⚠⚠ Deliberately **not** one A record per service module: that works only while DNS and compute
are one provider. They are not, so the record set is data in one file, and service IPs live there rather than in
service-module outputs.

⚠⚠ **The tiles handoff is one variable.** Setting `tiles_cname_target = ""` deletes the CNAME, and the
tiles runbook attaches the R2 custom domain in the same window. Leaving both declared gives one name
two owners in two states.

## ⚠ Provider v5 traps this module already handles

Re-check these before moving the `~> 5.25` pin:

- **`name` must be the FQDN.** A bare label or `@` is accepted, then refreshed to the FQDN
  (terraform-provider-cloudflare#7289). `main.tf` expands names from the inventory.
- **A TXT record over 255 characters drifts forever** unless sent pre-split into quoted strings,
  because the API re-splits it (terraform-provider-cloudflare#6238). The DKIM key is 420.
  `main.tf` splits at 255 — the same 255 + 165 DigitalOcean serves.
- **A CNAME target with a trailing dot drifts forever** (terraform-provider-cloudflare#7286). The
  inventory stores targets without one, and a plan fails on an inventory that has one.

The plan also fails on an inventory missing the apex, relay or commerce A record, or any of
SPF/DKIM/DMARC/MX — a zone that resolves but cannot authenticate mail looks healthy for days.

## Use

```bash
../../scripts/dns-export.sh                 # IMMEDIATELY before — never a stale copy
cp terraform.tfvars.example terraform.tfvars
export CLOUDFLARE_API_TOKEN=...             # Zone:Zone:Edit + Zone:DNS:Edit — see the runbook
terraform init && terraform apply
terraform output name_servers
```

⚠ A Free zone that stays *pending* for 28 days is deleted. Apply close to the nameserver switch.

## Moving off Cloudflare later

The records port mechanically: a new adapter over `records.json`, then
`RECORDS=deploy/dns/records.json NS=<new ns> scripts/dns-diff.sh` before switching — the same check
this move used, without DigitalOcean.

⚠⚠ The real lock-in is **tiles**. An R2 custom domain needs the zone on Cloudflare, so leaving
Cloudflare DNS means moving tiles off R2 too, or paying for Business to keep a CNAME (partial) setup.

⚠ DNSSEC is off, which is what keeps any handoff this simple. If it is turned on later, a move also
needs a DS/key handoff at the registrar, or a window with DNSSEC off.
