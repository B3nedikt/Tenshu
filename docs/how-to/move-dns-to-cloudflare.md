# Moving tenshu.app's DNS to Cloudflare

What moves: the **nameservers** for `tenshu.app`, from DigitalOcean to Cloudflare (Free plan). What
does not: every record keeps its value, every service keeps its IP and its own TLS, and mail keeps
authenticating. Nothing a user touches changes — which is why every check below is about proving
the new zone serves *exactly* what the old one does before anyone relies on it.

Tiles moving onto R2 is a separate, later step with its own runbook. This one only has to leave the
zone ready for it.

## Why Cloudflare, and what it puts in its path (decided 2026-09-14)

**It is forced by tiles.** Tiles are moving to Cloudflare R2, and an R2 custom domain only attaches to
a zone hosted in the same Cloudflare account. The alternatives were checked and rejected: a CNAME
(partial) setup that would leave DNS elsewhere needs the Business plan (~$200/month), and delegating
only `service.tenshu.app` as its own zone is Enterprise-only (Cloudflare's *subdomain setup* table:
Free, Pro and Business — No).

**It does not route our traffic through Cloudflare.** Every record is DNS-only except tiles, which is
proxied by construction because R2 serves only through Cloudflare's edge — and tiles already
resolved to Cloudflare IPs, via DigitalOcean's CDN. Relay, apex and commerce still terminate TLS in
their own Caddy on our boxes; Cloudflare answers the lookup and never sees the request. Mail is DNS
records only.

**The trust that changes is the DNS host's**, and it is the same kind of trust for any host —
DigitalOcean today, or Scaleway had we gone there: the host sees which resolvers look up which names
(not what is then sent), and could repoint a name. ⚠ The one genuinely new concentration is **DNS
hosting and tiles serving in one account**: whoever controls that account controls both. Give it a
hardware key or TOTP before anything else.

⚠⚠ **Never flip a relay, apex or commerce record to Proxied.** It puts Cloudflare in the TLS path
[`sync-relay-server/Caddyfile`](../../sync-relay-server/Caddyfile) keeps it out of, pushes Caddy's
ACME validation through the proxy (its TLS-ALPN challenge cannot pass at all), caps request bodies at
100 MB on Free and ends any origin response slower than 125 s.

## The pieces

| | |
|---|---|
| [`deploy/dns/records.json`](../../deploy/dns/records.json) | every record, in a shape no host owns |
| [`scripts/dns-export.sh`](../../scripts/dns-export.sh) | renders it from the live DigitalOcean zone |
| [`deploy/cloudflare-dns/`](../../deploy/cloudflare-dns/) | the zone + every record, DNS-only |
| [`scripts/dns-diff.sh`](../../scripts/dns-diff.sh) | proves a nameserver serves every record, value for value |

## 0. Before you start — yours to do

1. **A Cloudflare account** — the one the R2 tiles bucket will live in, because the custom domain
   cannot cross accounts. Turn on 2FA.
2. **Its account ID**: dashboard → Account home → ⋯ next to the account → *Copy account ID*.
3. **An API token** (My Profile → API Tokens → Create Token → *Custom token*):
   - Permissions: **Zone → Zone → Edit** and **Zone → DNS → Edit**
   - Zone Resources: **Include → All zones from an account → *your account***

   ⚠ It cannot be scoped to `tenshu.app` yet — the zone does not exist until the first apply. Narrow
   it to *Specific zone → tenshu.app* afterwards (§8). ⚠ R2 needs different permissions; that is the
   tiles module's token, not a reason to widen this one.
4. **Access to the registrar**, and the domain not in a transfer, lock or redemption state that
   blocks a nameserver change.

## 1. Settle the records at DigitalOcean first

Do the planned record changes — tonight's apex flip is one — **at DigitalOcean**, and let them settle.
DigitalOcean is the truth until the switch, and the export in §2 copies whatever it serves.

There is nothing to lower in advance: the A and CNAME records are already at 300 s, and the TTL that
governs this move is the `.app` delegation's **10800 s**, which only the registry controls. Because
both zones will serve identical answers, no TTL here causes downtime — they only bound how long a
mistake lingers.

## 2. Export, apply — the zone exists, pending

```bash
./scripts/dns-export.sh                    # IMMEDIATELY before; prints tiles_cname_target
git diff deploy/dns/records.json           # only the changes you expect — commit them
cd deploy/cloudflare-dns
cp terraform.tfvars.example terraform.tfvars   # account_id, tiles_cname_target
export CLOUDFLARE_API_TOKEN=...
terraform init && terraform apply
terraform output name_servers                  # two names, e.g. xxx.ns.cloudflare.com
```

The zone is created through the API, so it starts **empty** — the dashboard's onboarding scan, which
guesses records and proxies them by default, never runs — and **pending**, which means Cloudflare
already answers on its assigned nameservers while the world still asks DigitalOcean.

⚠ **A Free zone pending for 28 days is deleted.** Do this within days of §4, not weeks.

⚠ **From here until DigitalOcean is retired, a record changes in both places** — §7 says why.

## 3. Prove the new zone complete, before switching anything

```bash
cd ../..
for ns in $(terraform -chdir=deploy/cloudflare-dns output -json name_servers | python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)))'); do
  NS=$ns ./scripts/dns-diff.sh                                  # DigitalOcean's zone vs Cloudflare
  RECORDS=deploy/dns/records.json NS=$ns ./scripts/dns-diff.sh  # the inventory vs Cloudflare
done
```

⚠⚠ **Do not proceed on a single MISS, on either nameserver.** The first form is the one that decides:
it compares against what DigitalOcean actually serves right now — including `tiles.service`, which the
inventory leaves out — so it also catches a record changed at DigitalOcean after the export.

⚠ It compares **full values**. Before 2026-09-14 it compared the first 60 characters, and every
2048-bit DKIM key starts with the same 60 characters of RSA boilerplate: fed a key with one character
changed, the old script printed `ok`. The rewrite reports `first difference at character 300`.
TXT strings are joined the way resolvers join them, so Cloudflare splitting the 420-character key
into 255 + 165 is not a difference; MX compares priority as well as target.

⚠ It proves **completeness, not the absence of extras** — DNS cannot list a zone. Every record at
Cloudflare comes from the inventory, so an extra means someone used the dashboard.

As built, 2026-09-14, against DigitalOcean's own nameserver as the control: 10/10 from `doctl`,
9/9 from the inventory (the difference is `tiles.service`).

## 4. Switch the nameservers at your registrar

Replace **all three** `ns{1,2,3}.digitalocean.com` with **exactly** the two Cloudflare names from
§2 — copied, not typed. ⚠ Leaving a DigitalOcean nameserver in the list does not make a gentler
transition: a full setup fails activation while any other provider's nameserver is listed, and
resolvers would split between the two.

## 5. Watch, don't assume

```bash
dig +trace tenshu.app NS +noall +authority +nodnssec   # the .app servers now list Cloudflare
dig +short NS tenshu.app                               # your resolver — up to 3 h behind that
./scripts/dns-diff.sh                                   # DigitalOcean's zone vs what resolvers get
curl -fsS -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  'https://api.cloudflare.com/client/v4/zones?name=tenshu.app' | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"][0]["status"])'
```

`status` turns `active` once Cloudflare sees its nameservers at the registry — minutes to hours;
the dashboard can re-trigger the check. Nothing waits on it for DNS-only records: Cloudflare answers
them whether the zone is pending or active. Activation matters only to tiles, whose R2 custom domain
needs an active zone.

## 6. Verify what fails quietly

**Mail.** Records resolving is not mail authenticating. Send a real OTP to an external mailbox —
Gmail or similar — and read the received headers for `dkim=pass`, `spf=pass` and `dmarc=pass`.

**The services still hold their own TLS.** A Cloudflare issuer here means a record got proxied:

```bash
for h in tenshu.app relay.tenshu.app commerce.service.tenshu.app; do
  printf '%s: ' $h; echo | openssl s_client -connect $h:443 -servername $h 2>/dev/null | openssl x509 -noout -issuer
done                                                     # every one: Let's Encrypt
curl -fsS https://relay.tenshu.app/health
```

Let's Encrypt validates a Caddy renewal by connecting to the box's own IP, which DNS-only records
leave exactly as it was.

**Tiles still serve ranges** through DigitalOcean's CDN:

```bash
p=$(curl -fsS https://tiles.service.tenshu.app/catalog.json | python3 -c 'import json,sys; print(json.load(sys.stdin)["regions"][0]["path"])')
curl -sS -r 0-1023 -o /dev/null -w '%{http_code} %{size_download}\n' "https://tiles.service.tenshu.app/$p"   # 206 1024
```

## 7. Rollback

Put DigitalOcean's three nameservers back at the registrar. Its zone is never touched by this
procedure — it *is* the rollback.

⚠⚠ **That only works while DigitalOcean still serves today's answers.** Any record change between
§2 and §9 goes to **both**: DigitalOcean (panel or `doctl`) *and* `records.json` + apply. A change
made only at Cloudflare is silently undone by a rollback; a change made only at DigitalOcean is
served to every resolver still asking it.

⚠ **Both zones stay in use longer than the 10800 s delegation suggests.** Some resolvers trust the
nameserver set the zone itself returns, and Cloudflare publishes its NS records with a **24 h** TTL
(DigitalOcean's: 30 min). So a rollback converges within about a day, not three hours — which is
also why both zones must agree for that long.

## 8. Once it has settled

- Narrow the API token to *Specific zone → tenshu.app*.
- Keep `deploy/cloudflare-dns/terraform.tfstate` backed up; it is gitignored and holds the zone id.
- Hand over to tiles. ⚠⚠ **Deadline: the tiles swap must land before 2026-12-09.** DigitalOcean's CDN
  serves `tiles.service` with a DigitalOcean-managed Let's Encrypt certificate that DNS-validates
  against DigitalOcean's zone (`deploy/tiles/main.tf`). From §4 nobody asks that zone, so the renewal
  cannot validate. The current certificate expires **2026-12-09** (read 2026-09-14). The swap is
  `tiles_cname_target = ""` plus the R2 custom domain, in one window — the tiles runbook.

## 9. Retiring DigitalOcean's zone

Not before **all** of:

- the tiles swap has landed, so nothing is served through DigitalOcean's CDN;
- the apex, commerce and relay cutovers have settled — each may still need a record rollback, and
  until then DigitalOcean's zone is part of it;
- a week with `RECORDS=deploy/dns/records.json ./scripts/dns-diff.sh` clean.

⚠⚠ Then release the zone from Terraform **before** any destroy: both `deploy/digitalocean` and
`deploy/tiles` hold `digitalocean_domain.zone[0]`, and a destroy in either deletes the zone and every
record in it — before §9 that is the rollback gone, and before §4 it is the domain gone.
`terraform state rm 'digitalocean_domain.zone[0]'` in each module that still holds it, then delete
the zone as its own deliberate step.

After that the `doctl` form of `dns-diff` has nothing to read; the `RECORDS=` form is the check.

## Cloudflare settings that can reach a DNS-only zone

Proxy features, WAF, caching and Universal SSL apply to proxied hostnames only. These do not:

- **The dashboard's proxy toggle.** Records added by hand in the dashboard invite the orange cloud;
  change records through the inventory instead.
- **Email Routing.** Turning it on adds its own MX records and an SPF record for Cloudflare. This
  domain sends through Scaleway TEM and deliberately receives nothing (`blackhole.tem.scaleway.com`),
  and SPF allows exactly one record — leave it off.
- **CNAME flattening.** Always on at the zone apex, but only for a CNAME *at* the apex; ours is an A
  record. *Flatten all CNAMEs* is a paid option, so `tiles.service` is served as a CNAME.
- **DNSSEC.** Off at DigitalOcean, off here by default — keep it off until §9. Turning it on means
  publishing a DS record at the registrar, and with a DS in place a rollback to DigitalOcean's
  unsigned zone fails validation at every validating resolver.
- **Multi-provider DNS.** Off; a full setup expects only Cloudflare's nameservers at the registrar.

## Not verified when this was written

No Cloudflare account existed, so nothing here has been applied. Verified from Cloudflare's docs,
API schema and provider source: API zone creation (pending, no scan), answers from pending zones,
the 28-day deletion, token permission groups, the v5 name/TXT/CNAME behaviours
(`deploy/cloudflare-dns/README.md`), CNAME flattening and the subdomain-setup plan table. A local
`terraform plan` rendered all 11 resources, and the inventory checks fail a broken inventory.
**Unverified**: an actual apply round-tripping without drift, and whether the Free dashboard lets you
lower the 24 h nameserver TTL.
