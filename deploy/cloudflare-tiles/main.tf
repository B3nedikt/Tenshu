# Tenshu offline-tiles serving infra on Cloudflare R2 (maps-region-delivery.md D1-D3).
#
#   bucket        = an R2 bucket holding the signed catalog + the immutable, dated per-region PMTiles
#                   bundles. Egress is free, which is the whole reason it is here (versions.tf).
#   r2.dev URL    = a toggle, OFF, on only for the pre-DNS range test
#   custom domain = tiles.service.tenshu.app, the name every shipped client already uses — so the
#                   move is a DNS cutover, not an app release
#
# There is NO compute here. Publishing is a plain S3 upload from scripts/tiles-publish.sh.
#
# ⚠⚠ PMTiles is RANGE-NATIVE: the map reads a tile by byte range instead of downloading the bundle.
# Anything that drops `Range` does not merely break resume, it breaks rendering. Every route in this
# module is gated by `copy-tiles-from-digitalocean.py --check-ranges` before a client is put on it.
#
# ⚠⚠ Cloudflare's CACHE is not in the path for most of this, and that is fine. The Free plan caches
# nothing over 512 MB (four of today's six bundles are larger), and it caches by file extension —
# .json, .sig, .pmtiles and .tlne are all outside its default list. So ranged reads go straight to R2,
# still at zero egress. It also means the MUTABLE catalog is never held at the edge.
# ⚠ Do not add a "Cache Everything" rule without scoping it away from catalog.json and
# catalog.json.sig. That would bring back the stale-catalog-at-the-edge problem DigitalOcean's CDN
# has, where tiles-publish.sh has to flush after every publish.
#
# ⚠ CORS admits the APEX origins only (`cors_origins`), never an app origin and never `*`. Since
# 2026-10-28 the web runtime reads tiles from the browser (maps-region-delivery.md §Web scope W.1): the
# apex origin's runtime fetches catalog.json, its signature and ranged .pmtiles reads, exactly what
# Control's HTTP client does natively. The app frames (maps./fit./timeline.) never fetch tiles; they
# get bytes over the bridge. A rule wider than the apex lets any page read the bucket from a browser.

locals {
  custom_domains = merge(
    var.enable_custom_domain ? { primary = var.domain } : {},
    var.test_domain != "" ? { test = var.test_domain } : {},
  )
}

# ---- The bucket ------------------------------------------------------------
# ⚠ No versioning, no lifecycle. Bundles are immutable by construction — published under a DATED
# prefix (regions/<YYYY-MM>/<id>.pmtiles), so a rebuild writes new keys rather than overwriting old
# ones — and old builds are pruned by tiles-publish.sh's GC=1, not by a bucket rule nobody reviews.
resource "cloudflare_r2_bucket" "tiles" {
  account_id = var.account_id
  name       = var.bucket_name
  location   = var.location
}

# ---- r2.dev (the pre-DNS test route) ----------------------------------------
# Declared even when disabled, so "off" is a state Terraform asserts rather than a default nobody
# checked. ⚠ R2 has no object ACLs: this toggle and the custom domain ARE the public-read policy.
resource "cloudflare_r2_managed_domain" "dev" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.tiles.name
  enabled     = var.enable_dev_url
}

# ---- Custom domains -----------------------------------------------------------
# The real hostname (only once the swap is decided) and an optional sibling at the same depth for
# measuring the swap before doing it.
#
# ⚠⚠ Both names are TWO levels below the zone. Universal SSL covers only the apex and first-level
# names (*.tenshu.app), and R2's documentation does not say whether a custom domain gets its own
# certificate. Workers custom domains do (an Advanced Certificate per hostname, documented). Do not
# assume R2's do: the sibling hostname exists to find out, and `status.ssl` in the outputs is where
# the answer shows.
resource "cloudflare_r2_custom_domain" "tiles" {
  for_each = local.custom_domains

  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.tiles.name
  zone_id     = var.zone_id
  domain      = each.value
  enabled     = true
  min_tls     = var.min_tls

  lifecycle {
    precondition {
      condition     = var.zone_id != ""
      error_message = "A custom domain needs zone_id — the tenshu.app zone on Cloudflare, which must already be ACTIVE (nameservers switched)."
    }
    precondition {
      condition     = var.test_domain != var.domain
      error_message = "test_domain must differ from domain: the sibling exists to measure the swap without touching the name clients use."
    }
  }
}

# ---- CORS: the web runtime (W.1) ---------------------------------------------
# GET/HEAD only. `Range` is CORS-safelisted for a single range, so there is no preflight; it is listed
# anyway so a browser that does preflight is not refused. Content-Range must be exposed or the runtime
# cannot read which bytes it got.
resource "cloudflare_r2_bucket_cors" "tiles" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.tiles.name

  rules = [{
    allowed = {
      methods = ["GET", "HEAD"]
      origins = var.cors_origins
      headers = ["Range"]
    }
    expose_headers  = ["Content-Range", "Content-Length", "ETag"]
    max_age_seconds = 3600
  }]
}

