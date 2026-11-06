# Model-catalog serving infra (Cloudflare R2)

An **R2 bucket** behind the custom domain **`models.service.tenshu.app`**, serving the signed model
catalog and the checkpoint artifacts it names (`inference-service.md` IN-D10). No compute. The
pipeline that fills it: **[`docs/how-to/models-pipeline.md`](../../docs/how-to/models-pipeline.md)**.

It is the sibling of [`deploy/cloudflare-tiles/`](../cloudflare-tiles/) — same provider pin, same
account, same zone, same depth of hostname — and replaces the Scaleway bucket module that used to
live in `deploy/models/` and was never applied.

## Why R2, and why our own name

**R2** for the tiles reason: a public endpoint streaming large ranged GETs, where a per-GB-billed
origin's bill has no ceiling. R2's egress is free. A checkpoint is ~380 MB per device, and every
interrupted download resumes with `Range`.

**`models.service.tenshu.app`** because the base URL is **compiled into Control**
(`control-shared-proxy` `ProxyModule` `named("modelCatalogUrl")`). A provider URL there — the
Scaleway module's `https://s3.fr-par.scw.cloud/tenshu-models` — would make any later move an app
release on every device. On our own name it is a DNS change, the way the tiles move from DigitalOcean
was (2026-09-14).

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; account_id + zone_id
export CLOUDFLARE_API_TOKEN=...                # needs R2 edit + DNS edit on tenshu.app
terraform init && terraform apply              # bucket + custom domain, in one apply
terraform output custom_domain_status          # ⚠ wait for ssl = active before publishing
terraform output publish_env_hint
```

⚠ `.terraform.lock.hcl` is committed, copied from `deploy/cloudflare-tiles` so both modules resolve
the same provider build.

## ⚠⚠ What will bite

**Two credentials, never mixed.** A Cloudflare **API token** for Terraform (`CLOUDFLARE_API_TOKEN`), and
an **R2 S3 key pair** for `scripts/models-publish.sh`. ⚠ The S3 pair minted for tiles is bucket-scoped:
unless its scope includes `tenshu-models` it authenticates here and is then refused. Mint a pair scoped
to `tenshu-models` (R2 → Manage API tokens → Object Read & Write), or widen the existing one.

**R2 has no object ACLs.** `x-amz-acl` is not implemented, so an upload carrying `--acl public-read` is
refused with `NotImplemented`. `models-publish.sh` drops the flag for an R2 endpoint. Public read is the
custom domain, nothing else.

**`SPACES_ENDPOINT` is required**, and R2's region is `auto`. The script refuses to run without an
endpoint and forces the region for R2, because a shell set up for another store exports one R2 does
not have.

**The DNS record is the custom domain's, not the inventory's.** Attaching creates a read-only record
for `models.service` in the zone; `deploy/cloudflare-dns` must never declare one, exactly as for
`tiles.service`.

**Verify `Range` once after the first publish:**

```bash
curl -fsSI -r 0-1023 "https://models.service.tenshu.app/<an artifact path from catalog.json>"
# expect: HTTP/2 206  (a 200 means the range was ignored — every interrupted download restarts)
```

## ⚠ The weights are world-readable, and that is intended

They are published models, and a device that must not talk to the internet still fetches them through
Control. Confidentiality was never the property here — **integrity** is, and it comes from the signed
catalog plus a `sha256` per artifact, checked on the device.
