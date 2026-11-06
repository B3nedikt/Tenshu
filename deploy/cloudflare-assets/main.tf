# Tenshu store/marketing image hosting on Cloudflare R2 (store-presence-and-support-pages.md §3.3).
#
#   bucket        = tenshu-assets: GENERATED images (store screenshots, later the rendered marketing
#                   graphics), content-addressed at sha256/<hex>.<ext>. Public, through the custom domain.
#   custom domain = assets.tenshu.app — one level below the zone, so Universal SSL's *.tenshu.app
#                   covers it; the support pages (tenshu.app/apps/<app>/support/…) link it directly.
#   r2.dev URL    = a toggle, OFF
#   fixtures      = tenshu-store-fixtures, OPTIONAL and PRIVATE (no domain, no r2.dev): licensed source
#                   photos the screenshot run seeds Drive with. Off until the first real photo exists.
#
# There is NO compute here. Uploads are plain S3 PUTs from scripts/store-publish.sh, which sets
# `Cache-Control: public, max-age=31536000, immutable` and `Content-Type` on every object. R2 serves
# both back as stored, and Cloudflare's edge caches .png by default — so the header the browser sees
# is the one the object carries, and no cache rule is needed. ⚠ Safe only because every key is
# content-addressed: an object is never overwritten with different bytes, so "immutable" is true.
#
# ⚠ No CORS, deliberately: the readers are <img> tags (no CORS needed) and the store consoles,
# which upload from a local file. A cloud session reading these needs the HOST on its egress
# allowlist (a maintainer setting), not a CORS rule.

# ---- The public bucket -------------------------------------------------------
# ⚠ No versioning, no lifecycle: keys are content hashes, so nothing is ever overwritten, and an image
# no manifest references any more is deleted by hand (README: "Garbage"), never by a rule nobody reviews.
resource "cloudflare_r2_bucket" "assets" {
  account_id = var.account_id
  name       = var.bucket_name
  location   = var.location
}

# Declared even when disabled, so "off" is a state Terraform asserts rather than a default nobody
# checked. ⚠ R2 has no object ACLs: this toggle and the custom domain ARE the public-read policy.
resource "cloudflare_r2_managed_domain" "dev" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.assets.name
  enabled     = false
}

# Creates its own read-only DNS record in the zone; deploy/cloudflare-dns must never declare
# `assets`, exactly as for tiles.service and models.service.
resource "cloudflare_r2_custom_domain" "assets" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.assets.name
  zone_id     = var.zone_id
  domain      = var.domain
  enabled     = true
  min_tls     = var.min_tls
}

# ---- The private fixtures bucket (optional) -------------------------------------
# ⚠⚠ A SEPARATE bucket, not a prefix of the public one: R2 makes a whole bucket public through its
# custom domain, so a "private prefix" in tenshu-assets would be public. Photos licensed under
# Unsplash/Pexels-style terms may be used commercially but not redistributed as a collection — a
# public URL per photo, listed in a public repo's manifest, is exactly that collection. So these stay
# behind an S3 key: no custom domain, no r2.dev, read by store/fixtures/generate.py with credentials.
resource "cloudflare_r2_bucket" "fixtures" {
  count = var.enable_fixtures_bucket ? 1 : 0

  account_id = var.account_id
  name       = var.fixtures_bucket_name
  location   = var.location
}
