# Credentials and settings for the Cloudflare R2 tiles bucket.

variable "api_token" {
  description = "Cloudflare API token for Terraform. Null reads CLOUDFLARE_API_TOKEN from the environment instead, which keeps it out of terraform.tfvars. ⚠ Not the R2 S3 key pair — see versions.tf."
  type        = string
  sensitive   = true
  default     = null
}

variable "account_id" {
  description = "Cloudflare account ID (dashboard → any zone → Overview, right column). Not a secret. ⚠ Also part of the S3 endpoint (https://<account_id>.r2.cloudflarestorage.com), and the custom domain's zone must live in THIS account."
  type        = string
}

variable "bucket_name" {
  description = <<-EOT
    R2 bucket name. Artifacts land at whatever `path` the catalog names them by, with catalog.json +
    catalog.json.sig at the root — the layout scripts/tiles-publish.sh uploads. The default matches
    the DigitalOcean bucket so the two are interchangeable in every command.
  EOT
  type        = string
  default     = "tenshu-tiles"
}

variable "location" {
  description = <<-EOT
    R2 location HINT: apac, eeur, enam, weur, wnam or oc.

    ⚠ A hint, not a guarantee, and honoured only when a bucket of this name is first created — a
    deleted-and-recreated bucket keeps its original placement.

    ⚠⚠ Deliberately NOT a jurisdiction (`eu`). A jurisdiction is a residency guarantee, and these
    bundles are public ODbL open data that anyone may mirror — residency buys them nothing. What it
    would cost is real: a jurisdictional bucket answers ONLY on its own S3 endpoint
    (https://<account_id>.eu.r2.cloudflarestorage.com), so every tool that touches it has to know,
    and it cannot be changed after creation.
  EOT
  type        = string
  default     = "weur"

  validation {
    condition     = contains(["apac", "eeur", "enam", "weur", "wnam", "oc"], var.location)
    error_message = "location must be one of apac, eeur, enam, weur, wnam, oc."
  }
}

variable "enable_dev_url" {
  description = <<-EOT
    Serve the bucket on its Cloudflare-managed r2.dev URL.

    ⚠⚠ Off except for the pre-DNS range test (docs/how-to/move-tiles-to-cloudflare.md). r2.dev is
    rate-limited and documented as non-production, and while it is on the bucket is public through it
    regardless of anything configured on the custom domain.
  EOT
  type        = bool
  default     = false
}

variable "zone_id" {
  description = "Zone ID of tenshu.app on Cloudflare (dashboard → the zone → Overview, or deploy/cloudflare-dns's output). Required only when a custom domain is enabled. ⚠ The zone must be ACTIVE — nameservers switched — before a custom domain can attach."
  type        = string
  default     = ""
}

variable "domain" {
  description = "The hostname CLIENTS use. ⚠⚠ Must stay tiles.service.tenshu.app: it is compiled into every shipped client (control-shared-proxy ProxyModule `tilesHostUrl`), and serving the same name from R2 is what makes this a cutover rather than an app release."
  type        = string
  default     = "tiles.service.tenshu.app"
}

variable "enable_custom_domain" {
  description = <<-EOT
    Attach var.domain to the bucket — THE cutover.

    ⚠⚠ Assume attaching fails while the DigitalOcean CNAME still exists at that name. Cloudflare
    documents exactly that for Workers custom domains, and R2's docs say nothing either way. Removing
    the CNAME first (deploy/cloudflare-dns) opens a gap. Do not flip this without the runbook's
    sibling-hostname measurement: it tells you how long that gap lasts and whether a certificate
    for a two-level name arrives at all.
  EOT
  type        = bool
  default     = false
}

variable "test_domain" {
  description = <<-EOT
    A second, throwaway hostname at the SAME depth as var.domain (e.g. tiles-r2.service.tenshu.app),
    attached before the cutover to measure what the real swap will do: whether a two-level name
    gets a valid certificate, how long that takes, and whether ranges survive the custom-domain path.
    Empty attaches nothing. Remove it after the cutover.
  EOT
  type        = string
  default     = ""
}

variable "min_tls" {
  description = "Minimum TLS version on the custom domain(s). Cloudflare's default is 1.0. ⚠ 1.2 is safe for every client that reads tiles — Android minSdk 24, iOS and the desktop JVM all speak it — and nothing else is meant to."
  type        = string
  default     = "1.2"

  validation {
    condition     = contains(["1.0", "1.1", "1.2", "1.3"], var.min_tls)
    error_message = "min_tls must be one of 1.0, 1.1, 1.2, 1.3."
  }
}

variable "cors_origins" {
  description = "Origins whose pages may READ the bucket in a browser: the apex origins whose web runtime streams tiles (maps-region-delivery.md §Web scope W.1). Never an app subdomain, never *."
  type        = list(string)
  default     = ["https://tenshu.app", "https://tenshu.xyz"]

  validation {
    condition     = alltrue([for o in var.cors_origins : can(regex("^https://[a-z0-9.-]+$", o)) && o != "*"])
    error_message = "cors_origins are exact https origins (no path, no wildcard)."
  }
}

