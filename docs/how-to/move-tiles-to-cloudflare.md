# Moving offline tiles to Cloudflare R2

> ✅ **Done.** Swapped 2026-09-14 20:23Z, and DigitalOcean was retired (stage 7) on 2026-09-15. R2 is
> the only tiles host and the **only copy** of the published builds, so the rollback below no longer
> exists. The stages stay as the record of how it was done, and as the procedure for a future move.

⭐ **This is a DNS cutover, not an app release.** Every shipped client reads
`https://tiles.service.tenshu.app` (`control-shared-proxy`'s `ProxyModule` `tilesHostUrl`), and that
name moves to R2 unchanged. The paths in the signed catalog are relative, so the catalog and its
signature carry over byte for byte.

Why R2 rather than Scaleway: [`maps-region-delivery.md`](../proposals/maps-region-delivery.md) D1
(decided 2026-09-14). Module: [`deploy/cloudflare-tiles`](../../deploy/cloudflare-tiles/).

Survey taken 2026-09-14 against the live catalog.

---

## What is published today

| region | bundle | overlay |
|---|---|---|
| `de-bayern` | 1.803 GB | 50.1 kB |
| `at` | 2.021 GB | — |
| `ch` | 0.989 GB | — |
| `de-berlin-brandenburg` | 0.573 GB | — |
| `test-toscana` | 0.140 GB | — |
| `test-firenze` | 0.011 GB | — |
| **total** | **5.537 GB** (5 536 949 129 bytes) | + `catalog.json`, `catalog.json.sig` |

`schemaVersion=1`, `buildDate=2026-08`, 6 regions.

## ⚠⚠ Three things decide whether this works

**1. Ranges.** PMTiles is range-native: the client reads a tile by byte range and never fetches a
2 GB region whole. A route that drops `Range` does not merely break resume, it **breaks rendering**.
The gate is:

```bash
python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py \
  --check-ranges --public-base https://<the route under test>
```

It needs only the standard library. For every region it requests two ranges, one at the start and one
mid-file, **twice each**. Every answer must be `206` with exactly 1024 bytes and a `Content-Range`
naming that slice **and the catalog's `byteSize` as the total**. The repeat matters because an edge
can answer a cache miss correctly and then serve the hit as a whole-object `200`. The total matters
because a truncated object still passes both probes by slice alone. Checked against local servers that
ignore `Range`, return the wrong offset, break only on the second hit, or serve a truncated object:
all four fail the gate, and a correct server passes.

| route | result |
|---|---|
| DigitalOcean today (`tiles.service.tenshu.app`, via its CDN) | **206 ×2, exact, all 6 regions** ✅ (2026-09-14) |
| DigitalOcean bucket origin (`tenshu-tiles.fra1.digitaloceanspaces.com`) | **206**, exact ✅ (2026-09-14) |
| R2 via r2.dev | **206 ×2, exact, all 6 regions, as `ktor-client`** (what the apps send) ✅ (2026-09-14). ⚠ `403` to Python's default User-Agent |
| R2 via the custom domain | **206 ×2, exact, all 6 regions, as `ktor-client`**, on the sibling and then on `tiles.service.tenshu.app` itself ✅ (2026-09-14) |

**2. The certificate for a two-level name.** `tiles.service.tenshu.app` sits two labels below the
zone. Cloudflare's free Universal SSL covers only `tenshu.app` and `*.tenshu.app`, and **R2's docs do
not say whether a custom domain gets its own certificate**. Workers custom domains get a per-hostname
Advanced Certificate (documented). Stage 5 answers the question on a sibling name before the real one
is touched.

**3. The swap gap.** Cloudflare documents that a Workers custom domain cannot attach over an existing
CNAME, and R2's docs are silent. If R2 behaves the same, the DigitalOcean CNAME has to go first, and
⚠⚠ **a resolver that asks during the gap caches NXDOMAIN for up to 30 minutes.** The negative TTL is
the SOA's `Minimum TTL`, 1800 s by default, and changing SOA values is **Enterprise-only**
(developers.cloudflare.com/dns/manage-dns-records/reference/dns-record-types/#soa). Two
Cloudflare-hosted zones queried 2026-09-14 both served `… 604800 1800`. It cannot be lowered here,
so stage 5 tests whether the gap exists at all, and stage 6 keeps it to seconds.

## Before you start (maintainer)

1. A **Cloudflare account**, with **R2 purchased**. The free tier still requires subscribing and a
   payment method: *"You must purchase R2 before you can generate an API token"*
   (developers.cloudflare.com/r2/api/tokens/).
2. A **Terraform API token** (My Profile → API Tokens → Create → *Custom token*), exported as
   `CLOUDFLARE_API_TOKEN`. It grows in two steps, because the zone permissions cannot be scoped
   before the zone exists:
   - **Stages 1–3:** Account → **Workers R2 Storage: Edit** only (the bucket and the r2.dev toggle).
     ⚠ Don't add zone permissions yet. `tenshu.app` is not on Cloudflare until stage 4, so they could
     only be *All zones*, and that would be broader than this token ever needs
     (`move-dns-to-cloudflare.md` hits the same ordering for its own token).
   - **Before stage 5** (zone active): edit the token to add Zone → **Zone: Read** and **DNS: Edit**,
     under *Zone Resources → Specific zone → tenshu.app*. A custom domain writes its own DNS record.
   - ⚠ The custom-domain endpoint's required permissions are not in Cloudflare's API reference. If the
     stage-5 attach returns 403, add Zone → **SSL and Certificates: Edit** (same zone) and retry.
3. An **R2 S3 key pair**, minted in **stage 1, right after the bucket exists**. A key scoped to one
   bucket can only name a bucket that is already there. It is a different credential from the API
   token.
4. The zone itself is the DNS track's job ([`move-dns-to-cloudflare.md`](move-dns-to-cloudflare.md),
   `deploy/cloudflare-dns`), in the **same** Cloudflare account. Stages 1–3 do not need it. ⚠ That
   runbook's token is scoped to DNS; this one is a separate token, not a reason to widen that one.

⚠ Keep the two credentials under different names. The copy script reads `R2_ACCESS_KEY_ID` /
`R2_SECRET_ACCESS_KEY` and deliberately ignores `AWS_*`: a shell used for DigitalOcean publishing
usually has DigitalOcean's `AWS_*` exported.

## The move

### Stage 1 — the bucket, and the r2.dev test route

```bash
# every command in this runbook runs from the repo root
cp deploy/cloudflare-tiles/terraform.tfvars.example deploy/cloudflare-tiles/terraform.tfvars
#   set account_id, and enable_dev_url = true
export CLOUDFLARE_API_TOKEN=...                    # the Terraform token
terraform -chdir=deploy/cloudflare-tiles init
terraform -chdir=deploy/cloudflare-tiles apply
```

**Now mint the R2 S3 key pair.** The bucket exists, so the key can be scoped to it alone: R2 → Manage API
tokens → *Object Read & Write* → *Apply to specific buckets only* → the bucket
(`terraform -chdir=deploy/cloudflare-tiles output -raw bucket_name`). The secret is shown **once**.

```bash
export R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=...   # the R2 S3 key pair, never the Terraform token
```

⚠⚠ **Smoke-test the S3 path with a ROUND TRIP, not an upload.** The failure this is looking for does
not error. A store that doesn't decode an `aws-chunked` request body *stores the framing*, so the
object comes back larger and corrupt while the PUT reports success. The relay's S3 client hit exactly
that (`hosting-provider-economics.md` D10, S3/S5), and that doc lists R2 among the stores that break
this way. Cloudflare's own docs point the other way: it removed its aws-cli/boto3 *checksum
incompatibility* notes on 2025-07-07, when it documented CRC64NVME support. Those two sources
disagree, and nothing here has tested it, so let the bytes decide. Test both single-part and
multipart, because the CLI switches at 8 MB:

```bash
E="$(terraform -chdir=deploy/cloudflare-tiles output -raw s3_endpoint)"
B="$(terraform -chdir=deploy/cloudflare-tiles output -raw bucket_name)"   # never retyped: bucket_name is a variable
# ⚠ `env -u` the session token: R2's key is a static PAIR, and a token left exported by some earlier
#   temporary-credential session would be sent with it and fail authentication.
r2() { env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN \
         AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
         aws --endpoint-url "$E" --region auto "$@"; }
t=$(mktemp -d); ok=1
for n in 200000 20000000; do                 # single-part, then multipart
  head -c $n /dev/urandom > "$t/up"
  r2 s3 cp "$t/up" "s3://$B/smoke/$n" && r2 s3 cp "s3://$B/smoke/$n" "$t/down" \
    && cmp -s "$t/up" "$t/down" && echo "$n bytes: round trip identical" \
    || { echo "⚠⚠ $n bytes: NOT identical ($(wc -c < "$t/down" 2>/dev/null) bytes back)"; ok=0; }
  r2 s3 rm "s3://$B/smoke/$n"
done; rm -rf "$t"; [ "$ok" = 1 ]
```

If an upload is refused with *"Header 'x-amz-checksum-…' not implemented"*, or the bytes come back
different, export `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` and
`AWS_RESPONSE_CHECKSUM_VALIDATION=when_required` for every R2 command from then on and run the round
trip again. ⚠ Then `--verify-existing` stops being optional, because nothing else checks the bytes in
flight. The copy script is covered either way: it compares every uploaded object's size with the
catalog's, which a stored framing fails, and `--verify-existing` downloads every object back, new
uploads included, and hashes it before the catalog is published.

**Then plant the canary** — an object that exists **only in R2**, kept until retirement. It is how
every later step tells which backend answered. DigitalOcean's CDN is itself served from Cloudflare's
network (`tiles.service.tenshu.app` resolves to Cloudflare IPs today), so an IP address or a
`server: cloudflare` header proves nothing:

```bash
printf r2 | r2 s3 cp - "s3://$B/r2-canary.txt" --content-type text/plain
```

### Stage 2 — copy

```bash
DO="https://$(terraform -chdir=deploy/tiles output -raw bucket_origin_host)"   # the source: DO's bucket origin
python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py --source-base "$DO" --dry-run
python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py --source-base "$DO" --verify-existing
```

Needs `boto3` and `cryptography`.

⚠ **Run it where the uplink is fast.** Every byte streams through the machine that runs it, and
`--verify-existing` downloads it all back. A home line measured 2026-09-14 at ~5 Mbit/s up would have
taken hours. An idle host in the source's region did the whole verified copy in about five minutes.
Hand it the R2 key through ssh stdin into the process environment, never a file or an argument:

```bash
{ security find-generic-password -a tenshu -s r2-access-key-id -w
  security find-generic-password -a tenshu -s r2-secret-access-key -w; } \
  | ssh root@<host> 'read -r A; read -r S; cd /root/tilecopy &&
      R2_ACCESS_KEY_ID="$A" R2_SECRET_ACCESS_KEY="$S" nohup setsid python3 -u \
        copy-tiles-from-digitalocean.py --source-base "<DO origin>" --verify-existing \
        > copy.log 2>&1 < /dev/null &'
```

⚠ The script needs only itself and `terraform.tfvars` (the account ID) on that host; remove both
afterwards. ⚠ `cd` inside a backgrounded `&&` chain does not change the directory of the shell that
started it, so check progress with an absolute path (`tail /root/tilecopy/copy.log`).

- **Source.** The copy reads from the **DigitalOcean bucket origin** (`deploy/tiles`'
  `bucket_origin_host`, passed explicitly above), not from `tiles.service.tenshu.app`. That name is what is moving. Read through it after the cutover and the
  script would verify R2 against itself. DigitalOcean's CDN can also hold `catalog.json` for up to an
  hour, so a copy made right after a publish would republish the *previous* catalog.
- **Per-object hash.** Every object is hashed as it streams and checked against the catalog's
  `sha256`. A mismatch fails the run before anything is uploaded.
- ⚠⚠ **Catalog signature.** The script verifies `catalog.json` against `catalog.json.sig` under the
  client's pinned Ed25519 key, at fetch and again before publishing. `tiles-publish.sh` uploads the
  catalog *before* the signature, so a publish that overlaps the two fetches returns a new catalog
  with the old signature. Re-reading the catalog alone can't catch that, and every client would
  reject the pair. Without `cryptography` the script **refuses to run** instead of skipping the check.
- ⚠ **One snapshot.** It publishes the **exact bytes it verified**, and aborts if the source catalog
  changes mid-copy: a new build during the transfer would name bundles this run never copied.
- ⚠ **What "verified" means.** Every upload's *source* stream is hashed, but that proves what left
  this machine, not what R2 kept, and a same-length corruption passes the size check.
  `--verify-existing` downloads each object back from R2 and hashes it: objects skipped as already
  present, and each new upload before the catalog is published. The summary reports only objects
  hashed **as stored**. In a local S3 test, a same-length corrupted upload published without the
  flag and was refused with it. A same-size object that fails the hash is **re-copied** on the next
  run, so a refused upload converges without anyone deleting it by hand. With `--dry-run` (the
  Lockstep gate) the same mismatch fails instead. Skipped objects with the wrong `Cache-Control`/`Content-Type` are
  rewritten in place.

### Stage 3 — the range gate on r2.dev, before any DNS

```bash
python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py \
  --check-ranges --public-base "$(terraform -chdir=deploy/cloudflare-tiles output -raw dev_url)"
```

⚠ This proves **R2** serves ranges. It says nothing about the custom-domain path, which stages 5 and 6
gate separately. r2.dev is rate-limited; the gate makes 24 small requests per User-Agent.

⚠⚠ **The gate speaks as the apps: `User-Agent: ktor-client`**, Ktor 3.5.2's default. Every app sends it
on every platform, because no app sets one and both engines (OkHttp, Darwin) fall back to Ktor's
`KTOR_DEFAULT_USER_AGENT`; the script's `CLIENT_USER_AGENTS` comment carries the evidence and what
invalidates it. The User-Agent is not neutral: on 2026-09-14 r2.dev answered Python's
default `Python-urllib/3.x` with **403 on every path**, catalog included, while `okhttp`, curl and no
User-Agent at all got 200/206. The first run of this gate therefore reported "the catalog itself is
not reachable" against a bucket that served ranges perfectly. A proxied zone is no different (stage 5).

**As run, 2026-09-14:**

| check | result |
|---|---|
| S3 round trip, 200 KB and 20 MB (multipart) | byte-identical both ways |
| copy, 5.537 GB, `--verify-existing` | 7 of 7 objects hash-verified as stored; ~5 min from an in-region host |
| `catalog.json` / `.sig` vs DigitalOcean | byte-identical, 2077 B sha256 `d537217ad4d8…` / 64 B `d849296ea1b8…` |
| canary `r2-canary.txt` | `r2` via R2, `403` via the DigitalOcean origin |
| range gate via r2.dev | passed as `ktor-client`, and also as `okhttp/4.12.0` and a CFNetwork string |

Then prove the catalog pair is in lockstep with DigitalOcean (see *Lockstep*, below, with
`R2=<dev_url>`).

### Stage 4 — the zone moves to Cloudflare (DNS track)

Run [`move-dns-to-cloudflare.md`](move-dns-to-cloudflare.md) (`deploy/cloudflare-dns`) first.
Its zone must be **active**, not just pending: DNS-only records answer either way, but a custom domain
needs an active zone. What tiles needs from it: once the nameservers switch, `tiles.service` must still be a **DNS-only (grey) CNAME** to
`tenshu-tiles.fra1.cdn.digitaloceanspaces.com` (its `tiles_cname_target` variable), so tiles keeps
being served by DigitalOcean.

⚠⚠ **One owner per record, and ownership changes hands at the swap.** Until stage 6 that CNAME
belongs to `deploy/cloudflare-dns` through `tiles_cname_target`. From stage 6 on, the record at
`tiles.service` is the one the R2 custom domain creates, owned by `deploy/cloudflare-tiles`.
`cloudflare-dns` must then declare **nothing** at that name, or the two states fight over it.

Afterwards:

```bash
python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py \
  --check-ranges --public-base https://tiles.service.tenshu.app
```

⚠⚠ **This starts a clock.** DigitalOcean's CDN certificate is a managed Let's Encrypt certificate that
renews by validating against **DigitalOcean's** DNS (`deploy/tiles`). Once DigitalOcean is no longer
authoritative, renewal fails. The live certificate expires **2026-12-09 11:35 UTC** (checked
2026-09-14). The cutover, and every rollback that points the name back at DigitalOcean, has to happen
before then.

### Stage 5 — measure the swap on a sibling name

```bash
# ⚠ first: the token's zone permissions (Before you start, 2), now that tenshu.app exists
# terraform.tfvars: zone_id     = "<terraform -chdir=deploy/cloudflare-dns output -raw zone_id>"
#                   test_domain = "tiles-r2.service.tenshu.app"
H=tiles-r2.service.tenshu.app
t0=$(date +%s); state=waiting
# ⚠ Guarded: an apply that fails (a 403 for a missing permission is the likely one) must not turn
#   into an hour of polling and a "never valid" verdict about a certificate nobody requested.
if terraform -chdir=deploy/cloudflare-tiles apply; then
  # curl verifies the chain AND the hostname; exit 60 is a certificate problem, 6 is not resolving yet.
  # ⚠ As the APPS' User-Agent (ktor-client): Browser Integrity Check (below) can challenge curl's own,
  #   and a challenge must not read as "no certificate yet". ⚠ Exit 22 is an HTTP error over VALID
  #   TLS: its own state, so it is never reported as a missing certificate.
  while [ $(( $(date +%s) - t0 )) -le 3600 ]; do
    curl -fsS -A 'ktor-client' -o /dev/null "https://$H/catalog.json" 2>/dev/null; rc=$?
    [ $rc = 0 ]  && { state=serving; break; }
    [ $rc = 22 ] && { state=tls_ok_http_error; break; }
    sleep 10
  done
  case "$state" in
    serving)
      echo "serving with a valid certificate $(( $(date +%s) - t0 ))s after apply"
      terraform -chdir=deploy/cloudflare-tiles apply -refresh-only   # custom_domain_status: ssl "active"
      python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py --check-ranges --public-base "https://$H" ;;
    tls_ok_http_error)
      echo "⚠ the certificate IS valid ($(( $(date +%s) - t0 ))s after apply), but $H answered an HTTP error to the apps' User-Agent:"
      curl -sS -A 'ktor-client' -o /dev/null -w '    HTTP %{http_code}\n' "https://$H/catalog.json"
      echo "  ⚠⚠ STOP: a zone security setting is filtering the apps (see below). Fix it, then re-run the gate; do not swap." ;;
    *)
      echo "⚠⚠ STOP: no valid certificate for $H after 1 h — the 'never valid' row below. Nothing further in stage 5 applies." ;;
  esac
else
  echo "⚠⚠ the apply FAILED — NOT a certificate result. A 403 means a missing token permission (Before you start, 2). Fix it and re-run this block."
fi
```

⚠⚠ **A proxied custom domain answers through the zone's security settings, and r2.dev does not.**
Browser Integrity Check is *"enabled by default"* and *"challenges visitors without a user agent or
with a non-standard user agent such as commonly used by abusive bots"*
(developers.cloudflare.com/waf/tools/browser-integrity-check/), and Bot Fight Mode can challenge
automated clients if it is on. The apps are exactly such non-browser clients, sending `ktor-client`,
which is not a browser string. If the gate fails there, or the sibling answers `403` over valid TLS,
that is **Security → Settings** filtering the app, not `Range`: skip it for this
hostname (a configuration rule, or a custom rule that skips BIC) and re-run. Never swap on a gate that
passed only as some other User-Agent, because every device would lose its maps.

**Then — only if the block above printed a valid certificate and the gate passed — can a custom
domain attach over an existing record?** This decides whether stage 6 has a gap
at all, and the sibling is the only place to find out safely. Detach the sibling, put a record where
it was, and attach again:

```bash
# terraform.tfvars: test_domain = ""   → apply (detaches the sibling)
# dashboard → tenshu.app → DNS → add: CNAME  tiles-r2.service  →  tenshu.app  (DNS only)
# terraform.tfvars: test_domain = "tiles-r2.service.tenshu.app"   → apply, and read what happens
```

- **The apply fails on the existing record** → there is a gap, as assumed. Delete the dashboard
  record and apply again.
- **It succeeds and the record is replaced** → stage 6 can attach **without removing the CNAME
  first**: no gap, no negative caching. ⚠ Confirm with the curl loop above that the sibling then
  serves R2's catalog, not the CNAME target.

Record four facts: **did a valid certificate arrive**, **how long it took**, **did the gate pass**, and
**does attaching need the name empty**. They choose the swap method:

| sibling result | swap method |
|---|---|
| valid in a minute or two, gate passes | **Midnight swap** (stage 6). The gap is about that long. |
| valid, but tens of minutes | Pre-issue: **Advanced Certificate Manager** ($10/mo) for `tiles.service.tenshu.app` before the swap. ⚠ Whether an ACM certificate is used for an R2 custom domain is not documented — re-run this stage with ACM active to see the sibling's certificate come from it. |
| never valid | Stop and decide. The documented route is a **Worker custom domain** bound to the bucket, which gets a per-hostname Advanced Certificate. That means Worker code serving `Range` from the binding, and Workers request limits. ACM is the other option. |
| gate fails | Stop. A custom domain that drops `Range` is not a route. |

Then set `enable_dev_url = false`. The sibling replaces r2.dev for any further comparison.

**As run, 2026-09-14** (zone active 19:48Z), which picked the attach-over path with no gap:

| question | answer |
|---|---|
| certificate for a two-level name | **yes**, R2 issues its own per hostname (Google Trust Services WE1), `ssl: active` about 1 min after the attach |
| bot protection vs the apps | `ktor-client`, curl and no User-Agent: 200. `Python-urllib`: 403, as on r2.dev |
| gate through the custom domain | passed |
| attach over an existing CNAME | **works**: R2 replaces the record with its own read-only proxied `CNAME public.r2.dev`. The test record pointed at DigitalOcean's CDN, like the real one. For ~20 s the edge served an HTML page with status 200, then the canary read `r2` on every probe |

⚠⚠ **Test through Cloudflare's nameservers and edge, not the workstation's resolver.** For up to the
`.app` delegation's 3 h after the nameserver switch, a resolver can still have `tenshu.app`
delegated to DigitalOcean, whose zone has no sibling record. It returns "no such name", so the stage-5
poll waits on DNS, not the certificate, and can end in a false "never valid". Resolve with
`dig @<cloudflare-ns>` and pin requests with `curl --resolve` (or pin `socket.getaddrinfo` for the gate).

### Stage 6 — the swap (midnight, maintainer's go)

**Ahead of time: make the gap seconds, not minutes.** Unless stage 5 showed the attach works over
an existing record, every second between removing the CNAME and the custom domain answering is a
second in which a resolver can cache NXDOMAIN for **30 minutes**. So plan both changes in advance and
apply the saved plans back-to-back, with nothing to review in between:

```bash
# ⚠⚠ In deploy/cloudflare-dns/terraform.tfvars, NOT as a -var: tiles_cname_target = ""
#    A -var lives only in the saved plan. The next ordinary apply of that module would read the tfvars
#    again, recreate the DigitalOcean CNAME and collide with the R2 custom domain's record. The
#    handoff has to persist where the module reads it.
terraform -chdir=deploy/cloudflare-dns   plan -out=/tmp/dns-swap.plan
# In deploy/cloudflare-tiles/terraform.tfvars: enable_custom_domain = true
terraform -chdir=deploy/cloudflare-tiles plan -out=/tmp/tiles-swap.plan
# read both: the first destroys exactly one record (tiles.service), the second adds exactly one custom domain
```

⚠⚠ **If stage 5 showed the attach works over an existing record, the DNS module must still let go —
without deleting anything.** The custom domain takes over the name while `deploy/cloudflare-dns` still
tracks the CNAME in its state. Its next apply would then delete a record it no longer owns, possibly
the one R2 now serves from. Release it from state instead, and persist the empty target:

```bash
terraform -chdir=deploy/cloudflare-dns state rm 'cloudflare_dns_record.tiles_cname[0]'
# deploy/cloudflare-dns/terraform.tfvars: tiles_cname_target = ""
terraform -chdir=deploy/cloudflare-dns plan        # must report: No changes
```

Do this **after** the attach, and skip `/tmp/dns-swap.plan`.

**Confirm the canary (stage 1) says DigitalOcean still answers:**

```bash
curl -s https://tiles.service.tenshu.app/r2-canary.txt   # before: NOT "r2"
```

**The swap:**

```bash
date -u                                                         # note the start
# ⚠⚠ Every step below is guarded on the one before. A pasted block has no `set -e`, and from the first
#    apply on, clients may be failing: any failure branches to ROLL BACK instead of carrying on.
# 1 + 2. remove the DigitalOcean CNAME, then attach the real name — the saved plans, back-to-back.
#        Attach-over-existing path: drop the first apply here, and do the `state rm` above afterwards.
r2_ok=0
if terraform -chdir=deploy/cloudflare-dns apply /tmp/dns-swap.plan &&
   terraform -chdir=deploy/cloudflare-tiles apply /tmp/tiles-swap.plan; then
  # 3. watch who answers. Bounded, and every line says which case you are in, so a wrong answer shows
  #    at once rather than after a timeout. LIMIT: about 3× the sibling's measured time, at least 300.
  LIMIT=900; t0=$(date +%s)
  while [ $(( $(date +%s) - t0 )) -le "$LIMIT" ]; do
    code=$(curl -s -o /tmp/r2-canary.body -w '%{http_code}' --max-time 10 https://tiles.service.tenshu.app/r2-canary.txt)
    el=$(( $(date +%s) - t0 ))
    if [ "$code" = 200 ] && [ "$(cat /tmp/r2-canary.body)" = r2 ]; then echo "R2 answering after ${el}s"; r2_ok=1; break
    elif [ "$code" = 000 ]; then echo "${el}s: nothing valid yet (not resolving, or no valid certificate)"
    else echo "${el}s: HTTP $code from something that is NOT R2 — see the ⚠⚠ below, now"; fi
    sleep 5
  done
else
  echo "⚠⚠ an apply FAILED — the DigitalOcean CNAME may already be gone"
fi; date -u
if [ "$r2_ok" != 1 ]; then
  echo "⚠⚠ R2 is NOT serving tiles.service.tenshu.app — ROLL BACK NOW (below). Do not run the gate or Lockstep."
# 4. the gate, on the name clients use. ⚠⚠ Its failure is an OUTAGE, not a warning: every client is on
#    this route now, and Lockstep checks catalogs and hashes, never ranges, so it would pass on it.
elif python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py \
       --check-ranges --public-base https://tiles.service.tenshu.app; then
  echo "✅ swap done: R2 answers and serves ranges. Next: Lockstep, with R2=https://tiles.service.tenshu.app"
else
  echo "⚠⚠ R2 answers but the RANGE GATE FAILED — maps are broken for every client. ROLL BACK NOW (below)."
fi
```

Only after the `✅` line: run *Lockstep* with `R2=https://tiles.service.tenshu.app`.

⚠⚠ **If step 3 prints `NOT R2` (a valid HTTPS answer without the canary), DigitalOcean still owns the name.** Its CDN
registers `tiles.service.tenshu.app` as a custom hostname on Cloudflare's network, and which
registration wins once our zone also claims the name is not documented. Removing the custom domain
from the DigitalOcean CDN endpoint settles it (console → Spaces → `tenshu-tiles` → CDN, or
`doctl compute cdn update <cdn-id> --domain ""`). That is a DigitalOcean change, so it needs the
maintainer's go, and it **ends the rollback path** below.

**As run, 2026-09-14, attach-over path.** 20:22:5xZ apply → `r2` on every probe from **20:23:45Z**:

| phase | what the edge served |
|---|---|
| first ~40 s | the name already resolved to R2's edge IPs, but DigitalOcean's CDN still answered (its XML `403` for the canary; the real `catalog.json` kept being served) |
| next ~100 s | **R2's content under DigitalOcean's certificate** (the Let's Encrypt one on DigitalOcean's CDN hostname registration), R2 `ssl: pending` |
| from 20:25:26Z | R2's own certificate (Google Trust Services WE1), `ssl: active` |

There was no NXDOMAIN gap at any point, and every check passed: canary `r2` on both edge IPs, the gate on
`tiles.service.tenshu.app` as `ktor-client`, and Lockstep (`catalog.json` + `.sig` byte-identical to
DigitalOcean). Then `state rm` released the CNAME and `tiles_cname_target = ""` went into the DNS
tfvars. Both modules plan clean, with only `custom_domain_status` outputs moving to `active`.
⚠ DigitalOcean's CDN registration did **not** have to be removed for R2 to win, so the rollback path
below stays intact.

### Rollback

> ⚠⚠ **Gone since stage 7 (2026-09-15).** DigitalOcean's bucket, CDN and certificate no longer exist,
> so there is nothing to roll back to. What follows is how it worked during the window.

Both halves go back into the **tfvars files**, so they persist, and apply back-to-back. The rollback
crosses the same NXDOMAIN gap as the swap:

```bash
# deploy/cloudflare-tiles/terraform.tfvars:  enable_custom_domain = false
# deploy/cloudflare-dns/terraform.tfvars:    tiles_cname_target   = "<terraform -chdir=deploy/tiles output -raw cdn_endpoint>"
#                                             (tenshu-tiles.fra1.cdn.digitaloceanspaces.com as deployed)
terraform -chdir=deploy/cloudflare-tiles plan -out=/tmp/tiles-rollback.plan   # destroys only the custom domain (or nothing, if its apply failed)
terraform -chdir=deploy/cloudflare-dns   plan -out=/tmp/dns-rollback.plan     # creates only the tiles.service CNAME
if terraform -chdir=deploy/cloudflare-tiles apply /tmp/tiles-rollback.plan &&
   terraform -chdir=deploy/cloudflare-dns   apply /tmp/dns-rollback.plan; then
  python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py \
    --check-ranges --public-base https://tiles.service.tenshu.app \
    && [ "$(curl -s https://tiles.service.tenshu.app/r2-canary.txt)" != r2 ] \
    && echo "✅ rolled back: DigitalOcean answers and serves ranges" \
    || echo "⚠⚠ rolled back in Terraform, but the name is not served correctly yet — re-run the check; resolvers may hold NXDOMAIN for up to 30 min"
else
  echo "⚠⚠ a rollback apply FAILED — fix it in the dashboard: remove the R2 custom domain, add the DNS-only CNAME"
fi
```

That works while three things hold: DigitalOcean's bucket still has every build (see below), its CDN
still has the custom domain, and its certificate is still valid (**2026-12-09**).

### Stage 7 — retire (maintainer's go, after a quiet rollback window)

- Delete the canary (`r2-canary.txt`) and set `test_domain = ""` and apply — ⚠ after the rollback
  window, not before: the lockstep check below depends on it.
- `deploy/tiles`: ⚠⚠ `digitalocean_domain.zone` is the **shared** zone and carries `prevent_destroy`.
  First run `terraform state rm 'digitalocean_domain.zone[0]'`. `plan -destroy` must then list exactly
  the bucket, its policy, the certificate, the CDN and the `tiles_cname` record, and nothing else.
  ⚠ The bucket is not empty, and it is the only other copy of every build. Empty it deliberately, as
  its own decision, not as a side effect of making a destroy go through.

**As run, 2026-09-15** (~9 h after the swap, on the maintainer's go):

1. **R2 proven complete first.** The full key+size listing of both buckets was compared. Every object
   the catalog names was identical. DigitalOcean held two more, `regions/2026-07/test-{firenze,toscana}.pmtiles`
   (152 MB, a superseded test build no catalog referenced), which the maintainer chose to let go rather
   than copy.
2. The live route was re-checked: canary `r2`, R2's own certificate, and the range gate as `ktor-client`.
3. The canary was deleted (it now 404s), `test_domain = ""` was applied (sibling detached), and
   `state rm 'digitalocean_domain.zone[0]'` was run in `deploy/tiles`.
4. The bucket was emptied: 11 objects, no versions or delete markers left. ⚠ Verify the listing before
   deleting. An `aws` invocation that fails (a shell quoting slip) lists 0 objects, which reads as
   "already empty".
5. `plan -destroy` listed exactly the bucket, policy, certificate, CDN and `tiles_cname`, and the destroy ran.
   The DigitalOcean zone survives, 13 records, minus its tiles CNAME.
6. After: DigitalOcean has 0 CDNs, certificates and buckets; `tiles.service.tenshu.app` passes the gate
   on R2; `cloudflare-dns` plans clean.

## ⚠⚠ Every build during the rollback window is published TWICE

> ✅ **Ended with stage 7 (2026-09-15).** Publish to R2 only: `scripts/tiles-publish.sh` has no
> default endpoint any more, and `terraform -chdir=deploy/cloudflare-tiles output publish_env_hint`
> prints the invocation. The section below is the record of the window.

Rollback is only real if DigitalOcean keeps *receiving* builds, not just serving old ones.
`scripts/tiles-publish.sh` writes to one endpoint per run. A build published only to R2 would roll
back to a DigitalOcean catalog that lacks it.

⚠ **And a missed half fails silently.** The stale catalog is still correctly signed and every bundle
it names still exists (dated prefixes are never deleted), so nothing errors and no health check
changes.

```bash
# 1. R2 — what clients read after the swap
#    ⚠⚠ EXPORT THE ENDPOINT. The script defaults to DigitalOcean's, and BOTH buckets are named
#    `tenshu-tiles` by default, so with it unset (and DigitalOcean keys in the environment) this republishes to
#    DigitalOcean, succeeds, and R2 never receives the build.
#    The script derives `--region auto` and drops `--acl public-read` for an R2 endpoint: R2 refuses
#    the ACL header outright.
#    ⚠ `env -u` the session token too — R2's key is a static pair (see stage 1).
env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN \
  SPACES_BUCKET="$(terraform -chdir=deploy/cloudflare-tiles output -raw bucket_name)" \
  SPACES_ENDPOINT="$(terraform -chdir=deploy/cloudflare-tiles output -raw s3_endpoint)" \
  AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
  ./scripts/tiles-publish.sh

# 2. DigitalOcean — its endpoint, region and credentials PINNED for this one invocation.
#    ⚠⚠ Inherit nothing. A shell that has run publish_env_hint's R2 exports would otherwise keep
#    R2's SPACES_ENDPOINT and keys, so "the DigitalOcean half" republishes to R2 (or fails auth), and
#    DigitalOcean's catalog quietly goes stale — the rollback target with it.
#    The target comes from deploy/tiles' own outputs (local state, no API call), never retyped.
#    ⚠ Credentials under explicit names, mirroring R2_*: DO_SPACES_ACCESS_KEY_ID /
#    DO_SPACES_SECRET_ACCESS_KEY are the Spaces key pair (DigitalOcean → API → Spaces Keys, the
#    `spaces_access_id` / `spaces_secret_key` in deploy/tiles/terraform.tfvars). `env -u` first, so
#    nothing ambient (R2 keys, a session token, a profile) rides along.
#    DO_TILES_CDN_ID: the id from `doctl compute cdn list`, so the CDN's cached catalog is flushed.
env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN -u AWS_PROFILE \
  SPACES_BUCKET="$(terraform -chdir=deploy/tiles output -raw bucket_name)" \
  SPACES_ENDPOINT="$(terraform -chdir=deploy/tiles output -raw spaces_endpoint)" \
  SPACES_REGION="$(terraform -chdir=deploy/tiles output -raw bucket_region)" \
  AWS_ACCESS_KEY_ID="$DO_SPACES_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$DO_SPACES_SECRET_ACCESS_KEY" \
  CDN_ID="$DO_TILES_CDN_ID" \
  ./scripts/tiles-publish.sh
```

⭐ Each run uploads its new dated bundles **before** swapping that bucket's catalog, under
`set -euo pipefail`, so neither bucket ever has a catalog pointing at bundles it lacks.
⚠ That covers **bundles**, not the catalog and its signature. Those are two separate PUTs in every
publish, so a failure between them leaves a new catalog with the old signature. The lockstep check
compares both files for that reason.

### Lockstep

```bash
# R2 = the public R2 route: the dev_url (stage 3), the sibling (stage 5 on), tiles.service.tenshu.app
#      after the swap. No default, deliberately: before the swap tiles.service.tenshu.app IS
#      DigitalOcean, and a default pointing at it compares DigitalOcean with itself and passes.
# DO = the bucket ORIGIN — never tiles.service.tenshu.app, which is R2 after the swap.
# ⚠⚠ The canary check is what makes "R2" mean R2 whatever was pasted into it.
# ⚠ Compares the SIGNATURE too: a catalog-only cmp passes on a new-catalog/old-signature pair.
# ⚠ Returns non-zero on a mismatch but does not `exit` — pasted into an interactive shell that would
#   close the terminal. STOP if it fails.
# ⚠ Fetched to files and required non-empty: two failed fetches would otherwise compare "identical".
R2="<the public R2 route>"; DO="https://$(terraform -chdir=deploy/tiles output -raw bucket_origin_host)"
ok=1; t=$(mktemp -d)
[ "$(curl -fsS "$R2/r2-canary.txt" 2>/dev/null)" = r2 ] \
  || { echo "⚠⚠ $R2 is not serving R2 (no canary) — this comparison would prove nothing"; ok=0; }
for f in catalog.json catalog.json.sig; do
  curl -fsS "$DO/$f" -o "$t/do" || { echo "⚠⚠ $f: DigitalOcean fetch failed"; ok=0; continue; }
  curl -fsS "$R2/$f" -o "$t/r2" || { echo "⚠⚠ $f: R2 fetch failed"; ok=0; continue; }
  [ -s "$t/do" ] && cmp -s "$t/do" "$t/r2" && echo "$f identical" || { echo "⚠⚠ $f DIFFERS"; ok=0; }
done
rm -rf "$t"
# ⚠ Only on a clean comparison: run unconditionally, the hash check below could exit 0 and become the
#   block's result, hiding a catalog/signature mismatch printed just above it.
if [ "$ok" = 1 ]; then
  # --source-base "$DO": the SAME publication the comparison above read, not the script's default
  python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py --source-base "$DO" --dry-run --verify-existing
else
  echo "⚠⚠ LOCKSTEP FAILED — stop; the ⚠⚠ lines above say which check"; false
fi
#   expect "catalog signature: verified against the pinned Ed25519 key", "to copy: 0", and every
#   object hash-verified. ⚠ With both flags a missing or wrong-size object FAILS (exit 1) rather than
#   printing "would copy"; plain --dry-run is still just a plan. ⭐ That line verifies DigitalOcean's pair cryptographically; the byte-identical
#   comparisons above are what carry it over to R2.
```

⚠ Publish new builds from the local build directory, not by re-running the copy script: a new build
is a new dated prefix, and the copy would stream every region cross-provider again.

## What this does not touch

- **The client.** `tilesHostUrl` and the pinned catalog key are unchanged.
- **The model catalog**, which was greenfield (never applied, nothing published) and later got its own R2 bucket, `deploy/cloudflare-models`.
- **Privacy posture.** `threat-model.md` T-MR-01/T-MR-03 describe *the tile host / CDN* observing
  region interest and a viewport pattern by IP. That observer was already partly Cloudflare, since
  DigitalOcean's CDN is served from Cloudflare's network. After the swap it is Cloudflare alone. The
  threat classes are unchanged.
