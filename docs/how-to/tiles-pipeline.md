# Building & publishing offline map-region bundles (the tiles pipeline)

> ⚠⚠ **The serving host changed on 2026-09-14/15: Cloudflare R2, not DigitalOcean Spaces.** The build and
> sign steps below are unchanged. Everything that names Spaces, the DigitalOcean CDN, `deploy/tiles`
> or `doctl` is **retired**. Stand-up is [`deploy/cloudflare-tiles`](../../deploy/cloudflare-tiles/),
> publishing goes to R2 (A4), and the move itself is [`move-tiles-to-cloudflare.md`](move-tiles-to-cloudflare.md).
> R2 rejects `--acl` and has no CDN flush; `tiles-publish.sh` handles both from the endpoint.

Phase 1 of the offline map-region delivery
([`docs/proposals/maps-region-delivery.md`](../proposals/maps-region-delivery.md) §7 phase 1 +
§9): turn the OSM planet into per-region PMTiles extracts, sign a catalog, and publish both to an
object store behind Cloudflare's edge (R2) so `:ui-map` clients can download whole regions and render
fully offline.

There is **no tile server** (D1). The planet PMTiles exists only inside this pipeline; the
serving path is static files + HTTP range requests through Cloudflare's edge, with **zero serving compute**. The
gating gateway (`aud=tiles` vouchers, §4 D4) is **Phase 3** — Phase-1 bundles + catalog are
**public** (D3), integrity guaranteed by a Tenshu-signed manifest + per-bundle sha256 (R4).

## Two jobs, not one flow

The most useful thing to know before running anything: **this pipeline is two procedures with
different constraints** (D11). Conflating them is what makes it look daunting.

| | **A — regional bundles** | **B — the `global` planet** |
|---|---|---|
| Produces | `de-bayern.pmtiles`, `at.pmtiles`, … | one planet-sized `global.pmtiles` |
| Size | tens of MB → a few GB | 15–25 GB (z≤14) or ~100 GB (full) |
| Planet on your disk? | **No** — `pmtiles extract` range-fetches from a remote URL | Yes, that *is* the artifact |
| RAM needed | ~1 GB is fine | **≥ 8 GB** — the extract is memory-bound, see B |
| Where | **Your laptop.** Minutes. | **A rented build VM with a fast uplink.** Hours. |
| How often | when a region is added/refreshed | quarterly (D8) |

Do **A** first — it is the whole pipeline end to end at a size where mistakes cost seconds. **B**
is the same commands with a bigger input and a rented machine.

Neither procedure signs on the machine that uploads. See [Secrets](#secrets-what-lives-where).

## What's in the repo

| Path | Role |
|---|---|
| [`deploy/cloudflare-tiles/`](../../deploy/cloudflare-tiles/) | **Terraform module** — the R2 bucket + its custom domain `tiles.service.tenshu.app`. The serving infra. (`deploy/tiles/` is the retired DigitalOcean one.) |
| `deploy/tiles/regions.json` | **Region-definition file** — build date, schema versions, and per-region `{id, name, bbox}`. Checked in. |
| [`scripts/tiles-build.sh`](../../scripts/tiles-build.sh) | Extract per-region `.pmtiles`, write `build/hashes.json`, then (optionally) invoke the signer. |
| [`scripts/tiles-publish.sh`](../../scripts/tiles-publish.sh) | Upload the dated bundles + signed catalog to R2; GC old builds. ⚠ No default endpoint. |
| `:tiles-signer` (Gradle) | JVM tool that builds + Ed25519-signs `catalog.json` with the release-key primitives. |

> ⚠️ **Do NOT check in `.pmtiles` outputs.** The planet + per-region extracts are large binaries
> built out-of-band and published to R2 — `deploy/tiles/.gitignore` keeps `build/`, `*.pmtiles`,
> and the generated `catalog.json[.sig]` out of the tree. The only PMTiles binary in-tree is the
> ~1.3 MB bundled Firenze demo in `ui-map/src/androidMain/assets/` (the dev/fallback extract).

## Prerequisites

- **go-pmtiles** — the `pmtiles` CLI. A single Go binary:
  <https://github.com/protomaps/go-pmtiles/releases>; `brew install pmtiles` on macOS.
- **jq** — reads `regions.json` and writes `hashes.json`.
- **awscli** (`aws`) — S3-compatible uploads to R2 (`--region auto`, no ACL flags).
- **Terraform** ≥ 1.6 + the Cloudflare provider, to stand up the bucket and its custom domain once
  ([`deploy/cloudflare-tiles`](../../deploy/cloudflare-tiles/)).
- **Cloudflare credentials**, two different secrets:
  - a **Terraform API token** (Account → Workers R2 Storage: Edit; plus Zone: Read and DNS: Edit on
    `tenshu.app` for the custom domain), exported as `CLOUDFLARE_API_TOKEN` for Terraform only;
  - an **R2 S3 key pair** (R2 → Manage API tokens → Object Read & Write, this bucket only) for uploads.
    `r2publish` (A4) reads it from `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY` or the macOS Keychain.
- *(retired)* **doctl** and DigitalOcean credentials. They served the old Spaces/CDN host; R2 needs neither.

You do **not** need Planetiler. Producing a planet from raw OSM is a multi-hour job this pipeline
deliberately does not run — seed from a published Protomaps build instead ($0 build cost, §6).

## The region-definition file

`deploy/tiles/regions.json` drives everything:

```json
{
  "buildDate": "2026-07",
  "schemaVersion": 1,
  "tileSchemaVersion": 4,
  "regions": [
    { "id": "de-bayern", "name": "Bayern",  "bbox": [8.98, 47.27, 13.84, 50.56] },
    { "id": "at",        "name": "Österreich", "bbox": [9.53, 46.37, 17.16, 49.02] }
  ]
}
```

- `buildDate` — `YYYY-MM`; stamps the immutable dated build path `regions/<buildDate>/<id>.pmtiles`
  (D2/D8). Dates sort lexically, which the publish GC relies on.
- `tileSchemaVersion` — **must be `4`** (Protomaps v4). The `:ui-map` runtime style is built
  against v4; a silent v5 build renders blank (D3). `tiles-build.sh` hard-fails on any other value.
- `bbox` — `[west, south, east, north]`, matching `pmtiles extract --bbox`. Boundaries are seeded
  from **Organic Maps' `.poly` set** (Apache-2.0, tuned for whole-region offline download) — do not
  hand-maintain a boundary set (D7). For finer coverage than a bbox, `pmtiles extract` also accepts
  `--region=<geojson>`. Find bboxes at <http://bboxfinder.com/>.

**A whole-world bbox `[-180,-90,180,90]` is special**: `tiles-build.sh` treats it as the `global`
region and links the planet in as-is rather than running a pointless self-copy — [procedure
B](#procedure-b--the-global-planet-publish).

## Why one `global` archive covers the planet

Per-region extracts exist for **explicit offline download** ("keep Bayern for use without
network"). They are **not** how you get worldwide *online* coverage — cutting a region per place a
user might ever be does not scale.

For coverage everywhere, use the **streamed default (D10)**: the proxy range-fetches only the tiles
a camera needs and caches them, so the client never downloads a whole region. Combined with a
single `global` archive, one publish covers the planet — `RegionResolver.contains(point)` matches
every coordinate, so a place hero anywhere resolves to `global` and streams a few KiB. This is how
Protomaps serves the whole planet from one PMTiles on S3.

- **Storage:** a full-zoom planet is ~100 GB (~$200/yr on Spaces; **Cloudflare R2** is cheaper with
  *no egress fees* — D1 makes it the streaming-era default for a public endpoint). For thumbnails,
  pins and place heroes a **zoom-capped planet (z0–14)** is ~15–25 GB, which is what procedure B builds.
- Keep both models: `global` for coverage **plus** tight regions users can download for offline use
  (the resolver prefers the tighter installed region, falls back to `global`).

---

## One-time setup

### 1. Stand up the serving infra

> ⚠ **Retired.** The block below stood up DigitalOcean. Today's host is [`deploy/cloudflare-tiles`](../../deploy/cloudflare-tiles/)
> (stages 1–6 of [`move-tiles-to-cloudflare.md`](move-tiles-to-cloudflare.md)). Kept for the record.

```bash
cd deploy/tiles
cp terraform.tfvars.example terraform.tfvars    # DO token, Spaces key pair, domain, acme_email
terraform init
terraform apply
```

Creates the Spaces bucket, a public-read `GetObject` policy, and a CDN endpoint — plus a managed
Let's Encrypt cert + a CNAME for `tiles.service.tenshu.app` when `enable_custom_domain` and
`manage_dns` are on. No compute, nothing boots. Grab the outputs:

```bash
terraform output -raw bucket_name     # e.g. tenshu-tiles
terraform output -raw bucket_region   # e.g. fra1
terraform output -raw catalog_url     # https://tiles.service.tenshu.app/catalog.json
```

⚠️ `terraform.tfstate` now holds your Spaces keys **in plaintext** on disk. It is gitignored; keep
it so, and treat the directory as secret-bearing.

### 2. Generate the signing key (once, ever)

```bash
./gradlew :tiles-signer:run --args="keygen --out $HOME/.tenshu/tiles-keys"
```

Writes `tiles-signing.key` (32-byte seed, hex — **secret**) and `tiles-signing.pub` (pin in the
client). This is a **client-pinned trust anchor**: whoever holds it can sign bundles every Tenshu
client accepts and MapLibre parses. Same custody class as an app-signing key —

- keep it **offline**, on your machine, backed up out-of-band;
- **never** commit it, `scp` it to a build VM, or put it in CI secrets;
- rotating it means shipping a client update, so losing it is expensive.

---

## Procedure A — regional bundles, from your laptop

### A1. Pick a planet build to cut from

Browse <https://maps.protomaps.com/builds> for the current v4 daily build; the URL is
`https://build.protomaps.com/<YYYYMMDD>.pmtiles`. Verify before committing to a long job — this
reads only the header, a few KB:

```bash
pmtiles show https://build.protomaps.com/20260518.pmtiles
```

Check the reported max zoom and that it is the **v4** basemap. A v5 build renders blank (D3).

### A2. Cut the extracts

`pmtiles extract` takes the remote URL directly and range-fetches only the tiles inside each bbox —
**the planet is never downloaded**:

```bash
export PLANET_PMTILES=https://build.protomaps.com/20260518.pmtiles
./scripts/tiles-build.sh
```

Per region it runs `pmtiles extract $PLANET_PMTILES build/regions/<buildDate>/<id>.pmtiles
--bbox=w,s,e,n`, keeping the **full** basemap zoom band (no maxzoom clip — the demo-only trick that
shrank Firenze; production needs the whole band, D2). It prints each output's size + sha256 and
writes `build/hashes.json`.

> If `regions.json` still contains the `global` entry, this tries to materialise the whole planet.
> For a laptop run, point `REGIONS_JSON` at a copy without it.

### A3. Sign the catalog

`tiles-build.sh` runs the signer automatically when the key is present:

```bash
TILES_SIGNING_KEY=$HOME/.tenshu/tiles-keys/tiles-signing.key ./scripts/tiles-build.sh
```

Without `TILES_SIGNING_KEY` it prints the command instead, so you can sign separately:

```bash
./gradlew :tiles-signer:run --args="build-and-sign \
  --regions deploy/tiles/regions.json \
  --extracts deploy/tiles/build/regions/2026-07 \
  --out deploy/tiles/build/catalog.json \
  --key $HOME/.tenshu/tiles-keys/tiles-signing.key"
```

This writes `catalog.json` (a few KB) + a detached `catalog.json.sig` using the in-tree
`Ed25519Backend` — the same primitive the client verifies with, so there is one signature stack
(D3). It prints the public key; that must match what the client pins.

### A4. Publish

```bash
# ⚠ Every publish in this doc goes through this one function (A4, GC, overlay-only), so none of them
#   can miss the endpoint: the script has no default and refuses to run without it. It scopes the R2
#   settings to the one command and passes extra settings through: `r2publish DRY_RUN=1`, `r2publish GC=1`.
#   Credentials: the R2 S3 key pair (never the Terraform token), from R2_ACCESS_KEY_ID /
#   R2_SECRET_ACCESS_KEY when exported (any OS, CI), else from the macOS Keychain. It stops if neither
#   yields both. Store them once on macOS:
#     security add-generic-password -U -a tenshu -s r2-access-key-id -w
#     security add-generic-password -U -a tenshu -s r2-secret-access-key -w
#   (`terraform -chdir=deploy/cloudflare-tiles output publish_env_hint` prints the same invocation.)
r2key() {   # $1 = env var name, $2 = Keychain service name
  local v; eval "v=\${$1:-}"
  [ -n "$v" ] || v="$(security find-generic-password -a tenshu -s "$2" -w 2>/dev/null)"
  printf '%s' "$v"
}
r2publish() {
  local id secret
  id="$(r2key R2_ACCESS_KEY_ID r2-access-key-id)"; secret="$(r2key R2_SECRET_ACCESS_KEY r2-secret-access-key)"
  [ -n "$id" ] && [ -n "$secret" ] || {
    echo "r2publish: no R2 key pair — export R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY or store them in the Keychain" >&2; return 1; }
  env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN -u AWS_PROFILE \
    SPACES_BUCKET="$(terraform -chdir=deploy/cloudflare-tiles output -raw bucket_name)" \
    SPACES_ENDPOINT="$(terraform -chdir=deploy/cloudflare-tiles output -raw s3_endpoint)" \
    AWS_ACCESS_KEY_ID="$id" AWS_SECRET_ACCESS_KEY="$secret" \
    "$@" ./scripts/tiles-publish.sh
}

r2publish DRY_RUN=1     # rehearse — prints every remote command, touches nothing
r2publish               # for real
```

⚠ Then run the range gate against the public name, as the apps' User-Agent (the script's default):
`python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py --check-ranges --public-base https://tiles.service.tenshu.app`.

Uploads (public through the bucket's custom domain; R2 has no object ACLs):

- `build/regions/<buildDate>/*.pmtiles` → `s3://$SPACES_BUCKET/regions/<buildDate>/`, long
  `immutable` `Cache-Control` (dated builds never change),
- `build/catalog.json` (+ `.sig`) → the bucket root, short `Cache-Control` (the one mutable pointer).

No CDN flush: Cloudflare does not cache `.json`/`.sig` by default, so the new catalog is visible at once.
⚠ Keep it that way. Never add a "Cache Everything" rule over the catalog.

### A5. Verify

```bash
# The name every client reads (control-shared-proxy's tilesHostUrl), served by R2's custom domain.
# ⚠ Not an output of deploy/tiles: that module is retired and its state is empty.
BASE=https://tiles.service.tenshu.app
UA=ktor-client      # what the apps send; Cloudflare refuses some non-client User-Agents

curl -fsS -A "$UA" "$BASE/catalog.json" | jq .                              # catalog is live + parseable
curl -fsS -A "$UA" "$BASE/catalog.json.sig" -o /tmp/catalog.sig             # detached signature present

# Ranged GET works end to end (PMTiles is range-served, D1) — expect HTTP/2 206 + Content-Range:
curl -fsSI -A "$UA" -r 0-1023 "$BASE/regions/<buildDate>/de-bayern.pmtiles"
# …and the full gate, every region, twice per range:
python3 deploy/cloudflare-tiles/copy-tiles-from-digitalocean.py --check-ranges --public-base "$BASE"

# The bundle is a well-formed archive:
pmtiles verify deploy/tiles/build/regions/2026-07/de-bayern.pmtiles
pmtiles show   deploy/tiles/build/regions/2026-07/de-bayern.pmtiles   # min/max zoom, tile schema
```

The signature round-trip itself is covered by `:tiles-signer:test` (sign → verify → tamper →
reject, against the same `Ed25519Backend` the client uses). A real-region on-device pass (download
DACH on hardware, airplane mode, pan) closes each release.

---

## Procedure B — the `global` planet publish

Same pipeline, bigger input, **on a rented build VM with a fast uplink** (e.g. a Scaleway instance in
`fr-par`, near the R2 bucket's `weur` location): the artifact is 15–25 GB that must be both
materialised and pushed. The fetch is fast there, and R2 charges no ingress. (This procedure ran on a
DigitalOcean droplet before the move; the memory measurement below comes from one.)

> ⚠️ **Size the box by RAM, not disk. Use at least 8 GB.**
> The planet extract is **memory**-bound, not disk-bound — `pmtiles extract` holds the output
> archive's tile directory in memory, and a z14 planet has tens of millions of tile entries. A real
> attempt on a 1 GB droplet died with the process asking for **7.68 GB of address space**:
> `Out of memory: Killed process (pmtiles)`. Disk was never close (70 GB still free).
> `s-4vcpu-8gb` costs ~$0.06/hr; the failure mode on a smaller box is a system-wide OOM that can
> take neighbouring processes with it, so this is not a corner worth economising in.
> Regional extracts (procedure A) are far lighter — Bayern is ~270 k tile entries and runs fine in
> 1 GB — so this constraint is specific to whole-planet work.

**The signing key does not go on this machine.** The build VM builds and uploads *bundles* and emits
`hashes.json`; you sign at home from those digests (D11). The signer's `--hashes` flag exists
exactly for this.

### B1. On the build VM — tools

```bash
apt-get update && apt-get install -y jq unzip

# go-pmtiles embeds the version in its asset name, so there is NO stable
# /releases/latest/download/<name> URL — resolve the tag first. Note -f on the curl: without it a
# 404 pipes GitHub's HTML error page into tar, which fails as "stdin: not in gzip format".
PM_VER="$(curl -fsSL https://api.github.com/repos/protomaps/go-pmtiles/releases/latest \
  | jq -r .tag_name | sed 's/^v//')"
curl -fsSL "https://github.com/protomaps/go-pmtiles/releases/download/v${PM_VER}/go-pmtiles_${PM_VER}_Linux_x86_64.tar.gz" \
  | tar xz -C /usr/local/bin pmtiles

# Ubuntu 24.04 dropped the `awscli` package ("no installation candidate"), so take it from Amazon.
# Multipart upload is non-negotiable here — the global bundle is ~20 GB and S3 caps a single PUT at 5 GB.
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install
```

No JDK needed — the build VM never signs.

### B2. Fetch the planet, zoom-capped, in one step

Range-fetching z0–14 pulls ~15–25 GB, not the ~100 GB full archive. **There is no full-planet
intermediate** — the cap is applied during the fetch:

```bash
mkdir -p /data
pmtiles extract https://build.protomaps.com/<YYYYMMDD>.pmtiles /data/planet.pmtiles --maxzoom=14
```

This is the step that needs the RAM. If it dies with `Killed` and no other message, that is the OOM
killer, not a bug — check `dmesg -T | grep -i oom` and move to a bigger box. Lowering `--maxzoom`
cuts memory roughly 4× per level (a quarter the tile entries), so z12 is a fallback if you are
boxed in; it costs you detail below town level everywhere the user has not downloaded a region.

### B3. Build

`regions.json`'s whole-world bbox makes `tiles-build.sh` link the planet in as `global` rather than
cut an extract of it. With no `TILES_SIGNING_KEY` set it stops before signing — which is the point:

```bash
git clone <repo> && cd <repo>
export PLANET_PMTILES=/data/planet.pmtiles
./scripts/tiles-build.sh          # writes regions/2026-07/global.pmtiles + build/hashes.json
```

### B4. Upload the bundle

`tiles-publish.sh` refuses to run without a signed catalog (it publishes both together), so push
the bundle on its own; the catalog follows from your machine in B6.

Run this **from your machine, in the repo root**. It hands the build VM the R2 endpoint, bucket and key
pair through ssh stdin, never a file, an argument or the VM's shell history. The R2 key pair is the
bucket-scoped S3 pair (`r2key` from A4: exported `R2_*`, else the macOS Keychain), not the Terraform token:

```bash
# <build-host>: the VM from B1.  <repo>: its clone from B3.  <buildDate>: the build (regions.json).
{ terraform -chdir=deploy/cloudflare-tiles output -raw s3_endpoint; echo
  terraform -chdir=deploy/cloudflare-tiles output -raw bucket_name; echo
  r2key R2_ACCESS_KEY_ID r2-access-key-id; echo
  r2key R2_SECRET_ACCESS_KEY r2-secret-access-key; echo; } \
| ssh root@<build-host> 'read -r E; read -r B; read -r A; read -r S; cd <repo> &&
    env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN -u AWS_PROFILE \
      AWS_ACCESS_KEY_ID="$A" AWS_SECRET_ACCESS_KEY="$S" \
      aws --endpoint-url "$E" --region auto s3 cp \
        "deploy/tiles/build/regions/<buildDate>/global.pmtiles" "s3://$B/regions/<buildDate>/global.pmtiles" \
        --content-type application/octet-stream --cache-control "public, max-age=31536000, immutable"'
```

⚠ **No `--acl public-read`.** R2 refuses the header outright, and public access comes from the bucket's
custom domain. ⚠ `--region auto` is R2's only region. ⚠ The CLI switches to multipart above 8 MB,
which R2 accepts (equal-sized parts). A ~20 GB push can outlive a flaky ssh session. If it does, re-run
it: `s3 cp` rewrites the object whole, and nothing references it until B7 publishes a catalog naming it.

### B5. Take the digests home, then destroy the build VM

`hashes.json` is a few hundred bytes and entirely public — it is a subset of what `catalog.json`
publishes anyway:

```bash
# from your machine:
scp root@<build-host>:<repo>/deploy/tiles/build/hashes.json deploy/tiles/build/hashes.json
# then destroy the VM with ITS provider's tool, disks and public IP included. They keep billing
# otherwise. For a Scaleway instance:
scw instance server terminate <server-id> zone=fr-par-1 with-ip=true with-block=true
# (on DigitalOcean it was: doctl compute droplet delete <droplet>)
```

⚠ **Confirm it is gone**, e.g. with `scw instance server list zone=fr-par-1`. A terminate that failed
leaves a ~$0.06/h VM running with a 25 GB planet on it.

### B6. Sign at home, from the digests

The bundle is not on this machine and never will be. `--hashes` supplies its size + sha256; any
region whose extract *is* present locally is still hashed for real, so a mixed build (local DACH +
remote `global`) signs in one pass:

```bash
./gradlew :tiles-signer:run --args="build-and-sign \
  --regions deploy/tiles/regions.json \
  --extracts deploy/tiles/build/regions/2026-07 \
  --hashes deploy/tiles/build/hashes.json \
  --out deploy/tiles/build/catalog.json \
  --key $HOME/.tenshu/tiles-keys/tiles-signing.key"
```

The build dates in `hashes.json` and `regions.json` must agree — mismatched, it refuses rather than
publish a catalog pointing at the wrong dated build.

### B7. Publish the catalog + verify

Upload just the two catalog objects (the bundle is already up from B4), then run the [A5
checks](#a5-verify) against `regions/2026-07/global.pmtiles`. Once `catalog.json` carries `global`,
any place hero anywhere streams its real tiles on demand. Nothing changes in the apps.

---

## GC old builds

Retain the current build **plus one prior cycle** (for clients mid-download), then prune (D2):

```bash
r2publish GC=1          # keeps the newest RETAIN_BUILDS (default 2) dated builds (r2publish: A4)
```

GC is off by default (destructive) and honours `DRY_RUN=1`.

## Build cadence

**Quarterly** scheduled full builds + an **on-demand manual trigger** for corrections (D8). A
basemap with no POI depth changes slowly, and quarterly is ~3× cheaper than monthly (§6). Builds
are immutable + dated; clients always resolve the newest build per region through the signed
catalog (D3), and an update is the atomic download-then-rename in the client (§4).

---

## Secrets: what lives where

Two credentials, two custody classes — the split is load-bearing (D11):

| Secret | If leaked | Custody |
|---|---|---|
| **R2 S3 key pair** (Object Read & Write, `tenshu-tiles` only) | someone overwrites a bucket of public open data | Rotatable. Scoped to the one bucket. Fine handed to a build VM over ssh stdin (B4), fine in CI secrets. |
| **Cloudflare Terraform API token** | someone edits the bucket, its custom domain, or `tenshu.app` DNS records | Rotatable. Only where `terraform` runs, never on a build VM, never in a tfvars file (env only). |
| **`tiles-signing.key`** | clients accept attacker-signed bundles | **Offline, on one machine.** Never in CI, never on a rented VM, never in a "secret store". |

**No hosted secret store fixes this.** A rented build VM's `user_data` is readable from its metadata
endpoint by anything on the box, so a key must never go into it; hand keys over through ssh stdin
(B4) and keep them out of files. The answer is not a better vault — it is *fewer secrets in fewer
places*: one rotatable scoped key for the automatable half, one offline key that never moves. On a
workstation the R2 pair lives in the macOS Keychain (`security add-generic-password -U -a tenshu -s
r2-access-key-id -w`, and `r2-secret-access-key`). ⚠ `deploy/cloudflare-tiles/terraform.tfstate`
holds no S3 keys (the token comes from the environment). The retired `deploy/tiles` state used to
hold the Spaces pair in plaintext.

## Long-term: a builder image + a dispatch trigger

Not built yet; recorded so the next pass doesn't re-derive it (D11).

- **A pinned builder image** (`pmtiles` + `aws` + `jq`) removes the B1 install step and matches the
  existing prebuilt-image pattern (`relay-image.yml`, `apex-image.yml`, `commerce-image.yml`).
  Useful whether the run is triggered by hand or by CI.
- **CI is the button, not the muscle.** A GitHub-hosted runner cannot host the data path (~14 GB
  SSD documented, 6-hour job cap — a zoom-capped planet doesn't fit). The shape that pays is a
  `workflow_dispatch` that provisions an **ephemeral build VM** (e.g. a Scaleway instance) from that
  image, runs B2–B4, and destroys it. CI contributes the trigger, the audit trail, and independence
  from your laptop; the VM contributes disk, RAM and a fast uplink to R2.
- The workflow ends at B5 — bundles uploaded, `hashes.json` published as a build artifact, catalog
  **unsigned**. You run B6–B7. On a quarterly cadence that human gate costs minutes a year and is
  what keeps the trust anchor offline.

## Using s3cmd instead of aws

`tiles-publish.sh` uses `aws s3`, but R2 is S3-compatible, so `s3cmd` works if you'd rather. Configure
it once against the `s3_endpoint` output's host (`s3cmd --configure` with
`host_base = <account_id>.r2.cloudflarestorage.com`, `host_bucket` the same, i.e. path-style, and
`bucket_location = auto`), using the R2 S3 key pair. The equivalents are then
`s3cmd put --add-header='Cache-Control:…'` for the uploads and `s3cmd rm --recursive` for the GC.
Same object layout. ⚠ **No `--acl-public`**: R2 refuses ACLs, and objects are public through the
bucket's custom domain.

## Cross-links

- Design + decisions: [`maps-region-delivery.md`](../proposals/maps-region-delivery.md) (D1
  serving, D2 pipeline, D3 signed catalog, D7 boundaries, D8 cadence, **D11 execution + trust
  split**).
- Serving infra: [`deploy/tiles/README.md`](../../deploy/tiles/README.md).
- The `:ui-map` client terminus + region picker (Phase 2): `maps-region-delivery.md` §3 D5 +
  [`ui-map/ui-map-agent.md`](../../ui-map/ui-map-agent.md).


## Publishing without the bundles (overlay-only, or a re-signed catalog)

`tiles-publish.sh` no longer requires the local region extracts (fixed 2026-08-14, after an
overlay-only publish hit the wall). A publish that only *adds* something beside bundles already
uploaded — a transit-line overlay (§33), a re-signed catalog — has no reason to hold 1.8 GB of
immutable objects it will not touch:

```bash
./scripts/transit-overlay-build.sh de-bayern

./gradlew :tiles-signer:run --args="build-and-sign \
  --regions  <regions.json WITHOUT any unpublished region> \
  --extracts deploy/tiles/build/regions/<buildDate> \
  --overlays deploy/tiles/build/transit/<buildDate> \
  --hashes   <hashes.json> \
  --out      deploy/tiles/build/catalog.json \
  --key      <keyfile>"

r2publish DRY_RUN=1     # r2publish: A4 — endpoint, bucket and the R2 key pair in one place
r2publish
```

⚠⚠ **The guard that replaced the local check is stronger, not weaker.** Before the catalog — the live
pointer — is uploaded, every `path` and `overlayPath` it names is `head-object`ed **against the
bucket**. That is what the old check was reaching for and could not see: files existing *here* never
proved objects exist *there*, and it could not catch a bundle that was missed in an earlier run.
Read-only, so it runs under `DRY_RUN=1` too, which is the point of a dry run.

⚠ The script accepts `AWS_PROFILE` instead of `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` (keys in
`~/.aws` are keys), but `r2publish` unsets it: a profile set up for the retired Spaces bucket would
otherwise ride along and sign R2 requests with the wrong keys.

### ⚠⚠ Two traps, both hit for real on 2026-08-14

- **`regions.json` may list a region that was never published.** It carries `global`; the live catalog
  has six regions and no `global`, so signing against the checked-in file dies with
  `No bundle and no precomputed digest for region 'global'`. Sign against a copy with the unpublished
  regions removed.
- **`hashes.json` is derivable from the live catalog**, which is what makes a bundle-free publish
  possible at all: `jq -n --slurpfile c <(curl -s $HOST/catalog.json) '{buildDate: $c[0].buildDate,
  bundles: ($c[0].regions | map({key:.id, value:{byteSize, sha256}}) | from_entries)}'`.

### Verify the signature before uploading — the one irreversible mistake

A catalog signed by the wrong key makes **every device reject it** and loses all map delivery, not
just the new part. The signer prints the public half; compare it to `TILES_MANIFEST_PUBLIC_KEY_HEX`
in `ProxyModule.kt`. To check the bytes rather than the printout, with OpenSSL 3 and no extra tools:

```bash
printf '302a300506032b6570032100%s' "$PINNED_HEX" | xxd -r -p > pin.der
openssl pkey -pubin -inform DER -in pin.der -out pin.pem
openssl pkeyutl -verify -pubin -inkey pin.pem -rawin \
  -in deploy/tiles/build/catalog.json -sigfile deploy/tiles/build/catalog.json.sig
```
