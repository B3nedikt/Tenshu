# Tenshu offline-tiles serving infra on DigitalOcean (maps-region-delivery.md §3 D1 + §7 phase 1).
#
#   bucket  = a DO Spaces bucket (S3-compatible) holding the signed catalog + the immutable,
#             dated per-region PMTiles bundles (regions/<YYYY-MM>/<id>.pmtiles, D2/D3)
#   policy  = a public-read GetObject bucket policy — the catalog + bundles are public (D3);
#             a compromised CDN still can't tamper them (signed manifest + sha256, R4)
#   cert    = a managed Let's Encrypt cert for var.domain (only when enable_custom_domain)
#   cdn     = a CDN endpoint fronting the bucket, serving ranged GETs from the edge
#   dns     = (optional) the DO-managed zone + a CNAME pointing var.domain at the CDN
#
# There is NO compute here on purpose: the serving path is static files + range requests +
# CDN, zero serving compute (D1, R5). The gating gateway (§4 D4) is a SEPARATE, later
# (Phase 3) verify->presign box and is not provisioned by this module.
#
# `terraform apply` provisions all of the above; nothing bootstraps or runs — publishing is
# a plain S3 upload done out-of-band by scripts/tiles-publish.sh.

locals {
  # Derive the registrable apex (e.g. tiles.service.tenshu.app -> tenshu.app) when the caller
  # didn't pin dns_zone explicitly. Good enough for simple two-label TLDs.
  dns_zone = var.dns_zone != "" ? var.dns_zone : join(".", slice(split(".", var.domain), length(split(".", var.domain)) - 2, length(split(".", var.domain))))

  # The subdomain label(s) under the zone (e.g. "tiles.service"); "@" if domain == zone.
  dns_record_name = var.domain == local.dns_zone ? "@" : trimsuffix(var.domain, ".${local.dns_zone}")

  # Public base URL clients (the proxy's HttpRegionCatalogSource) are pointed at.
  public_base_url = var.enable_custom_domain ? "https://${var.domain}" : "https://${digitalocean_cdn.tiles.endpoint}"
}

# ---- The bucket ------------------------------------------------------------
# Objects are made world-readable via the bucket policy below (not a bucket-wide public ACL),
# so anonymous GET works for the catalog + bundles while the bucket itself is not publicly
# listable. The CDN reads public objects from this origin.
resource "digitalocean_spaces_bucket" "tiles" {
  name   = var.bucket_name
  region = var.region
  acl    = "private"
}

# ---- Public-read policy ----------------------------------------------------
# GetObject to everyone on every key. No ListBucket (the bucket is not a public index) and no
# write — publishing uses the Spaces credentials, not anonymous access. This is what makes the
# public download path (D3) work; integrity is the signed catalog + per-bundle sha256 (R4).
resource "digitalocean_spaces_bucket_policy" "public_read" {
  region = var.region
  bucket = digitalocean_spaces_bucket.tiles.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "PublicReadGetObject"
        Effect    = "Allow"
        Principal = "*"
        Action    = ["s3:GetObject"]
        Resource  = ["arn:aws:s3:::${digitalocean_spaces_bucket.tiles.name}/*"]
      }
    ]
  })
}

# ---- Managed TLS certificate (optional) ------------------------------------
# A DO-managed Let's Encrypt cert for the custom domain, DNS-validated against the DO-hosted
# zone. Only created when enable_custom_domain=true; requires manage_dns=true (see precondition).
resource "digitalocean_certificate" "tiles" {
  count = var.enable_custom_domain ? 1 : 0

  name    = "tenshu-tiles-cert"
  type    = "lets_encrypt"
  domains = [var.domain]

  lifecycle {
    precondition {
      condition     = var.manage_dns
      error_message = "enable_custom_domain=true needs manage_dns=true so the managed Let's Encrypt cert can DNS-validate var.domain in DO DNS. Either manage DNS here, or set enable_custom_domain=false and put your own DNS + cert in front of the cdn_endpoint output."
    }
    precondition {
      condition     = var.acme_email != ""
      error_message = "Set acme_email when enable_custom_domain=true — Let's Encrypt registration needs a contact address for expiry notices."
    }
    create_before_destroy = true
  }
}

# ---- CDN endpoint ----------------------------------------------------------
# Fronts the Spaces bucket. Serves ranged GETs (PMTiles is resumable/range-friendly by design,
# D1) from the edge. With a custom domain it terminates TLS with the managed cert; without one
# it is reachable at the DO-assigned endpoint (cdn_endpoint output).
resource "digitalocean_cdn" "tiles" {
  origin = digitalocean_spaces_bucket.tiles.bucket_domain_name
  ttl    = var.cdn_ttl

  custom_domain    = var.enable_custom_domain ? var.domain : null
  certificate_name = var.enable_custom_domain ? digitalocean_certificate.tiles[0].name : null
}

# ---- DNS (optional) --------------------------------------------------------
# Only when manage_dns=true. Creates the DO-hosted zone AND a CNAME pointing var.domain at the
# CDN endpoint. If your DNS lives elsewhere, set manage_dns=false and create the CNAME
# (var.domain -> the cdn_endpoint output) at your provider yourself.
#
# NB: if the zone already exists in your DO account, `apply` errors ("domain already exists")
# — import it first:  terraform import digitalocean_domain.zone[0] <zone>
resource "digitalocean_domain" "zone" {
  count = var.manage_dns ? 1 : 0
  name  = local.dns_zone

  # The tenshu.app zone is SHARED — the relay/apex/commerce DO states already created it, so
  # this module imports it (terraform import digitalocean_domain.zone[0] tenshu.app) rather than
  # creating it. prevent_destroy guards against a `terraform destroy` here deleting the shared
  # zone and every other module's records. Intentional teardown: first
  # `terraform state rm digitalocean_domain.zone[0]` to release it without deleting the zone.
  lifecycle {
    prevent_destroy = true
  }
}

resource "digitalocean_record" "tiles_cname" {
  count  = var.manage_dns ? 1 : 0
  domain = digitalocean_domain.zone[0].name
  type   = "CNAME"
  name   = local.dns_record_name
  value  = "${digitalocean_cdn.tiles.endpoint}."
  ttl    = 300
}
