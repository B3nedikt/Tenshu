# Offline-tiles serving infra (Cloudflare R2)

The successor to [`deploy/tiles/`](../tiles/) (DigitalOcean Spaces + CDN): an **R2 bucket** behind a
**custom domain**, plus the copy script that moves the published bundles across. No compute.

The move itself, in order and with its gates: **[`docs/how-to/move-tiles-to-cloudflare.md`](../../docs/how-to/move-tiles-to-cloudflare.md)**.

## Why R2 and not Scaleway like everything else

Decided 2026-09-14 ([`maps-region-delivery.md`](../../docs/proposals/maps-region-delivery.md) D1). Tiles
is a **public** endpoint serving streamed ranged GETs, and on a per-GB-billed origin — DigitalOcean,
Scaleway Object Storage, Scaleway Edge Services (€13.50/TB past 100 GB) — a crawler's bill has no
ceiling. R2's egress is free. Cloudflare also documents `Range` behaviour; Edge Services does not.

The costs: `tenshu.app` has to move its nameservers to Cloudflare (a custom domain needs the zone in
the same account, and the CNAME-only setup is Business-plan), and this is a third provider.

## ⭐ Same hostname, so this is a cutover, not an app release

Every shipped client reads `https://tiles.service.tenshu.app` (`control-shared-proxy`'s `ProxyModule`
`tilesHostUrl`). Attaching that name to the bucket moves every client at once, with no release and no
months-long overlap. The paths inside the signed catalog are relative, so the signature carries over
unchanged.

## ⚠⚠ What will bite

**The certificate for a two-level name is not a given.** `tiles.service.tenshu.app` is two labels
below the zone. Universal SSL covers only `tenshu.app` and `*.tenshu.app`, and R2's docs do not say
whether a custom domain gets its own certificate. Workers custom domains do (documented). That is
what `test_domain` is for: attach a sibling at the same depth, then read `custom_domain_status` and
time how long a valid certificate takes. Do this **before** the real name is touched.

**The swap had no gap, as measured on 2026-09-14.** An R2 custom domain *can* attach over an existing
CNAME: it replaces the record with its own read-only one. Cloudflare documents the opposite for Workers
custom domains, and R2's docs are silent, so it was measured on a sibling name first. The real swap
then served DigitalOcean for ~40 s and R2's content under DigitalOcean's certificate for ~100 s, with
no NXDOMAIN (the runbook's stage 6 as-run).

**R2 has no object ACLs.** `x-amz-acl` is not implemented, so an upload carrying `--acl public-read` is
refused. `scripts/tiles-publish.sh` drops the flag when `SPACES_ENDPOINT` is an R2 endpoint. Public
access is the custom domain (or the r2.dev toggle), nothing else.

**`SPACES_ENDPOINT` is required.** `tiles-publish.sh` used to default to DigitalOcean. Since that bucket
was deleted (2026-09-15) it has no default at all and refuses to run without one.

**Don't cache the catalog at the edge.** Cloudflare caches by extension, and `.json`, `.sig`,
`.pmtiles` and `.tlne` are all outside its default list. The Free plan also caches nothing over
512 MB. Ranged reads therefore go to R2 (still zero egress), and the mutable catalog is never stale
at the edge. A "Cache Everything" rule must be scoped away from `catalog.json` and
`catalog.json.sig`.

**Two credentials, never mixed.** A Cloudflare **API token** for Terraform (`CLOUDFLARE_API_TOKEN`),
and an **R2 S3 key pair** for the scripts (`R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` for the copy,
`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` for `tiles-publish.sh`).

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; account_id only, to start
export CLOUDFLARE_API_TOKEN=...
terraform init && terraform apply              # the bucket; everything else is staged by the runbook
terraform output publish_env_hint
```

⚠ `.terraform.lock.hcl` is committed, as in every other module here.
