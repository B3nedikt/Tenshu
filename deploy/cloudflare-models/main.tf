# Tenshu model-catalog serving infra on Cloudflare R2 (inference-service.md IN-D10).
#
#   bucket        = an R2 bucket holding the signed catalog + the immutable, versioned checkpoint
#                   artifacts it names by path. Egress is free.
#   r2.dev URL    = a toggle, OFF
#   custom domain = models.service.tenshu.app, the name compiled into Control — so a later move to
#                   another origin is a DNS change, not an app release
#
# There is NO compute here. Publishing is a plain S3 upload from scripts/models-publish.sh.
#
# ⚠⚠ Resumable downloads need `Range`. `ModelDownloader` resumes a ~380 MB artifact with a Range
# header, and R2 serves ranges on the custom-domain path — tiles, which are range-NATIVE, depend on
# exactly that and were measured before their cutover. Verify once after the first publish anyway
# (README: expect 206).
#
# ⚠⚠ Cloudflare's CACHE is not in the path for these objects: it caches by extension and .json, .sig
# and .onnx/.bin are outside the default list, and the Free plan caches nothing over 512 MB. So the
# mutable catalog is never stale at the edge. Do not add a "Cache Everything" rule without scoping it
# away from catalog.json and catalog.json.sig.

# ---- The bucket ------------------------------------------------------------
# ⚠ No versioning, no lifecycle. Artifacts are immutable by construction — a model version publishes
# under a new versioned path (models.json) rather than overwriting — and a withdrawn model is removed
# by hand, deliberately, after the catalog no longer names it.
resource "cloudflare_r2_bucket" "models" {
  account_id = var.account_id
  name       = var.bucket_name
  location   = var.location
}

# ---- r2.dev ----------------------------------------------------------------------
# Declared even when disabled, so "off" is a state Terraform asserts rather than a default nobody
# checked. ⚠ R2 has no object ACLs: this toggle and the custom domain ARE the public-read policy.
resource "cloudflare_r2_managed_domain" "dev" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.models.name
  enabled     = var.enable_dev_url
}

# ---- Custom domain ---------------------------------------------------------------
# ⚠ Two levels below the zone, like tiles.service.tenshu.app — which is the evidence that an R2 custom
# domain at that depth gets its own certificate (measured 2026-09-14). Read `custom_domain_status`
# before publishing: `ssl = active` is the only state a client's TLS handshake can succeed in.
resource "cloudflare_r2_custom_domain" "models" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.models.name
  zone_id     = var.zone_id
  domain      = var.domain
  enabled     = true
  min_tls     = var.min_tls
}
