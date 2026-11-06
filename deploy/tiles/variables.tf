# Inputs for the offline-tiles serving infra (maps-region-delivery.md §3 D1 + §7 phase 1).
#
# This module is the SERVING side only: a DigitalOcean Spaces bucket + a CDN endpoint in
# front of it. There is deliberately NO compute here (D1 — static object storage + ranged
# HTTP behind a CDN, no serving compute). The gating gateway (the thin verify -> presign
# box, §4 D4) is Phase 3 and is OUT OF SCOPE for this module — the Phase-1 catalog + bundles
# are public (D3), fetched with a plain ranged GET.
#
# Trust boundary: the tile host carries NO user data (D6). The bundles are ODbL open data and
# the catalog is public metadata; integrity comes from the Tenshu-signed manifest + per-bundle
# sha256 (R4), not from the channel. This box shares nothing with the relay or commerce planes.
#
# Copy terraform.tfvars.example -> terraform.tfvars and fill in the secrets/identity
# (do_token, spaces_access_id, spaces_secret_key, domain, acme_email).

# ---- Credentials -----------------------------------------------------------

variable "do_token" {
  description = "DigitalOcean API token (write scope). Creates the CDN endpoint, the managed TLS certificate, and (when manage_dns=true) the DNS records. Prefer the DIGITALOCEAN_TOKEN env var over committing it."
  type        = string
  sensitive   = true
}

variable "spaces_access_id" {
  description = <<-EOT
    DigitalOcean Spaces access key ID (the S3-compatible key pair, generated under
    API > Spaces Keys). Distinct from the API token: Spaces resources are provisioned over
    the S3 API. May instead be supplied via the SPACES_ACCESS_KEY_ID env var.
  EOT
  type        = string
  sensitive   = true
}

variable "spaces_secret_key" {
  description = "DigitalOcean Spaces secret access key (pairs with spaces_access_id). May instead be supplied via the SPACES_SECRET_ACCESS_KEY env var."
  type        = string
  sensitive   = true
}

# ---- Bucket / region -------------------------------------------------------

variable "bucket_name" {
  description = <<-EOT
    Spaces bucket name (globally unique within the region). The public host is the CDN's
    custom_domain (var.domain), NOT the bucket name, so this can stay a plain slug. Bundle
    objects land under regions/<YYYY-MM>/<id>.pmtiles with catalog.json + catalog.json.sig at
    the root (maps-region-delivery.md §3 D2/D3).
  EOT
  type        = string
  default     = "tenshu-tiles"
}

variable "region" {
  description = "DO Spaces region slug. Spaces is available in a subset of regions (fra1, ams3, nyc3, sfo3, sgp1, syd1). Pick one near your users; fra1 = Frankfurt matches the commerce/relay footprint."
  type        = string
  default     = "fra1"
}

# ---- Public host / CDN -----------------------------------------------------

variable "domain" {
  description = "Public hostname the catalog + bundles are served on, e.g. tiles.service.tenshu.app (the *.service.tenshu.app host convention, maps-region-delivery.md §3 D1). Becomes the CDN endpoint's custom_domain and (when manage_dns=true) a CNAME to the CDN. This is what the proxy's HttpRegionCatalogSource points at."
  type        = string
  default     = "tiles.service.tenshu.app"
}

variable "enable_custom_domain" {
  description = <<-EOT
    When true, attach var.domain to the CDN endpoint with a managed Let's Encrypt certificate
    (needs manage_dns=true so the cert can DNS-validate in DO DNS). When false, the CDN is
    reachable only at its DO-assigned *.cdn.digitaloceanspaces.com endpoint (the cdn_endpoint
    output) — put your own DNS/cert in front of it yourself.
  EOT
  type        = bool
  default     = true
}

variable "acme_email" {
  description = "Contact email registered on the managed Let's Encrypt certificate (expiry notices). Only used when enable_custom_domain=true."
  type        = string
  default     = ""
}

variable "cdn_ttl" {
  description = <<-EOT
    CDN edge cache TTL in seconds (endpoint-wide). Dated bundle objects are immutable
    (regions/<YYYY-MM>/…, D2/D8) so a long TTL is safe for them; the ONE mutable object is
    catalog.json — the publish script (scripts/tiles-publish.sh) uploads it with a short
    Cache-Control and flushes the CDN cache for it, so this endpoint TTL is the bundle default.
    3600s is a sensible middle ground.
  EOT
  type        = number
  default     = 3600
}

# ---- DNS -------------------------------------------------------------------

variable "manage_dns" {
  description = <<-EOT
    When true, Terraform creates the DO-managed domain (zone) + a CNAME pointing var.domain at
    the CDN endpoint, and the managed cert DNS-validates against that zone. Set false if your
    DNS lives elsewhere (Cloudflare, registrar) — then create the CNAME (var.domain -> the
    cdn_endpoint output) yourself, and set enable_custom_domain=false (the managed cert needs
    DO-hosted DNS).
  EOT
  type        = bool
  default     = true
}

variable "dns_zone" {
  description = "The DO-managed apex zone (e.g. tenshu.app) that var.domain is a record under. Only used when manage_dns=true. Empty = derive the registrable apex from var.domain."
  type        = string
  default     = ""
}
