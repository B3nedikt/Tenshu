# DigitalOcean offline-tiles serving infra (Terraform)

> ⚠⚠ **RETIRED 2026-09-15.** Tiles are served from Cloudflare R2 ([`deploy/cloudflare-tiles/`](../cloudflare-tiles/),
> [`move-tiles-to-cloudflare.md`](../../docs/how-to/move-tiles-to-cloudflare.md)). This module's bucket,
> policy, certificate, CDN and CNAME were destroyed and its state is empty. The shared `tenshu.app`
> zone was released from this state first, so it was never destroyed. Never `apply` it: that would
> stand up a second tiles host nobody reads and re-create a CNAME the R2 custom domain owns.

The **serving** side of the Tenshu offline map-region delivery pipeline
([`maps-region-delivery.md`](../../docs/proposals/maps-region-delivery.md) §3 D1 + §7 phase 1):
a **DigitalOcean Spaces** bucket holding the signed region catalog + the immutable, dated
per-region PMTiles bundles, fronted by a **CDN endpoint** that serves plain ranged GETs from
the edge.

> **There is no tile server.** The serving path is static object storage + HTTP range requests
> + CDN — **zero serving compute** (D1, R5). The planet PMTiles never leaves the build box;
> clients download whole region extracts once and render fully offline. This module provisions
> only the bucket + CDN + DNS; the build/extract/sign/publish steps run out-of-band
> ([`scripts/tiles-build.sh`](../../scripts/tiles-build.sh),
> [`scripts/tiles-publish.sh`](../../scripts/tiles-publish.sh)).

> **The gating gateway is NOT here.** The `aud=tiles` voucher gateway (verify → S3-presign,
> §4 D4) is **Phase 3** and out of scope for this module. Phase-1 bundles + catalog are
> **public** (D3): a plain ranged GET, integrity guaranteed by the Tenshu-signed manifest +
> per-bundle sha256 (R4), not by the channel.

```bash
cp terraform.tfvars.example terraform.tfvars   # DO token, Spaces key pair, domain, acme_email
terraform init
terraform apply
```

`apply` creates the bucket, a public-read `GetObject` policy, a CDN endpoint (+ a managed
Let's Encrypt cert and CNAME when `enable_custom_domain`/`manage_dns` are on). Nothing boots
or runs. Then publish a build and verify the public path:

```bash
export SPACES_BUCKET=$(terraform output -raw bucket_name)
export SPACES_REGION=$(terraform output -raw bucket_region)
export AWS_ACCESS_KEY_ID=<spaces access id>  AWS_SECRET_ACCESS_KEY=<spaces secret key>
../../scripts/tiles-publish.sh
curl -fsS "$(terraform output -raw catalog_url)" | head
```

| File | Purpose |
|------|---------|
| `versions.tf` | Terraform + DO provider version pins; the two-scope provider block (API token + Spaces keys). |
| `variables.tf` | Inputs (credentials, bucket/region, public domain, CDN TTL, DNS toggle). |
| `main.tf` | Spaces bucket, public-read policy, managed cert, CDN endpoint, DNS + safety preconditions. |
| `outputs.tf` | Bucket/CDN hosts, `public_base_url`, `catalog_url`, and the publish-env hint. |
| `regions.json` | Region-definition file (build date, schema versions, per-region id/name/bbox). Consumed by `scripts/tiles-build.sh`. Checked in; the `.pmtiles` outputs it drives are **not**. |

## Region granularity

Region boundaries are seeded from **Organic Maps' `.poly` set** (Apache-2.0, tuned for
whole-region offline download), vendored where used — do **not** hand-maintain a boundary set
(D7). Phase-1 starter set: DACH + one city extract, to exercise the small→large size band
against real `pmtiles extract` output.

## Provider notes

- **Two credentials.** The DO **API token** creates the CDN/cert/DNS; the **Spaces key pair**
  (S3 keys) creates the bucket + policy. They are different secrets — see `versions.tf`.
- **Custom domain needs DO DNS.** `enable_custom_domain=true` provisions a managed Let's
  Encrypt cert that DNS-validates against the DO-hosted zone, so it requires `manage_dns=true`
  (enforced by a precondition). To front the CDN with your own DNS/cert instead, set both to
  `false` and CNAME `domain` → the `cdn_endpoint` output yourself.
- ⚠⚠ **Being replaced by Cloudflare R2** ([`deploy/cloudflare-tiles`](../cloudflare-tiles/),
  decided 2026-09-14, D1). Same hostname, so it is a DNS cutover and not a client change; runbook
  [`move-tiles-to-cloudflare.md`](../../docs/how-to/move-tiles-to-cloudflare.md). ⚠ Until it is
  retired this module is the **rollback**: do not destroy it, and keep publishing every build to it
  as well.
- **`.terraform.lock.hcl`** is intentionally not committed here yet (the authoring env can't
  run `terraform init`). Run `terraform init` once and commit the generated lock file so every
  apply resolves the same provider plugins/hashes.

Full operator runbook (build → extract → sign → publish → verify, cadence, the do-NOT-commit-
`.pmtiles` rule): [`docs/how-to/tiles-pipeline.md`](../../docs/how-to/tiles-pipeline.md).
