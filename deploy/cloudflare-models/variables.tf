# Credentials and settings for the Cloudflare R2 model-catalog bucket.

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

variable "zone_id" {
  description = "Zone ID of tenshu.app on Cloudflare (deploy/cloudflare-dns's `zone_id` output). The custom domain attaches to it and creates its own read-only DNS record there."
  type        = string
}

variable "bucket_name" {
  description = <<-EOT
    R2 bucket name. Artifacts land at whatever `path` the catalog names them by
    (deploy/models/models.json), with catalog.json + catalog.json.sig at the root — the layout
    scripts/models-publish.sh uploads.
  EOT
  type        = string
  default     = "tenshu-models"
}

variable "location" {
  description = <<-EOT
    R2 location HINT: apac, eeur, enam, weur, wnam or oc — the tiles bucket's choice, for its reasons.
    ⚠ A hint, honoured only when a bucket of this name is first created. ⚠⚠ Deliberately NOT a
    jurisdiction: the weights are public, residency buys them nothing, and a jurisdictional bucket
    answers only on its own S3 endpoint, forever.
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
    ⚠ Off. There is no cutover to measure here — the custom domain is new, and tiles already proved a
    two-level name gets a certificate — so r2.dev would only be a second, rate-limited public route.
  EOT
  type        = bool
  default     = false
}

variable "domain" {
  description = "The hostname CLIENTS use. ⚠⚠ Compiled into Control (control-shared-proxy ProxyModule `modelCatalogUrl`): changing it is an app release, so the name is ours and never a provider's."
  type        = string
  default     = "models.service.tenshu.app"
}

variable "min_tls" {
  description = "Minimum TLS version on the custom domain. Cloudflare's default is 1.0. ⚠ 1.2 is safe for every client that downloads a model — Android minSdk 24 and the desktop JVM both speak it."
  type        = string
  default     = "1.2"

  validation {
    condition     = contains(["1.0", "1.1", "1.2", "1.3"], var.min_tls)
    error_message = "min_tls must be one of 1.0, 1.1, 1.2, 1.3."
  }
}
