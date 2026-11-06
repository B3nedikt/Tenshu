# Credentials and settings for the Cloudflare R2 store/marketing asset buckets.

variable "api_token" {
  description = "Cloudflare API token for Terraform. Null reads CLOUDFLARE_API_TOKEN from the environment instead (tf.sh fills it from the Keychain), which keeps it out of terraform.tfvars. ⚠ Not the R2 S3 key pair — see versions.tf."
  type        = string
  sensitive   = true
  default     = null
}

variable "account_id" {
  description = "Cloudflare account ID — the same value deploy/cloudflare-tiles and deploy/cloudflare-models use. Not a secret. ⚠ Also part of the S3 endpoint (https://<account_id>.r2.cloudflarestorage.com)."
  type        = string
}

variable "zone_id" {
  description = "Zone ID of tenshu.app on Cloudflare (deploy/cloudflare-dns's `zone_id` output). The custom domain attaches to it and creates its own read-only DNS record there."
  type        = string
}

variable "bucket_name" {
  description = "The PUBLIC bucket. Objects land at sha256/<hex>.<ext> — the layout scripts/store-publish.sh uploads and the manifests' `base` names."
  type        = string
  default     = "tenshu-assets"
}

variable "location" {
  description = <<-EOT
    R2 location HINT: apac, eeur, enam, weur, wnam or oc — the tiles bucket's choice, for its reasons.
    ⚠ A hint, honoured only when a bucket of this name is first created. ⚠⚠ Deliberately NOT a
    jurisdiction: the screenshots are public, and a jurisdictional bucket answers only on its own S3
    endpoint, forever.
  EOT
  type        = string
  default     = "weur"

  validation {
    condition     = contains(["apac", "eeur", "enam", "weur", "wnam", "oc"], var.location)
    error_message = "location must be one of apac, eeur, enam, weur, wnam, oc."
  }
}

variable "domain" {
  description = <<-EOT
    The public hostname. ⚠ Baked into every committed manifest (`base`) and so into the support pages:
    changing it means re-writing the manifests, never a re-upload (the keys stay the same). One level
    below the zone on purpose — Universal SSL (*.tenshu.app) covers it, unlike the two-level
    *.service names — and outside *.service because no client compiles it in.
  EOT
  type        = string
  default     = "assets.tenshu.app"
}

variable "min_tls" {
  description = "Minimum TLS version on the custom domain. Cloudflare's default is 1.0; 1.2 is what every browser that renders the support pages speaks."
  type        = string
  default     = "1.2"

  validation {
    condition     = contains(["1.0", "1.1", "1.2", "1.3"], var.min_tls)
    error_message = "min_tls must be one of 1.0, 1.1, 1.2, 1.3."
  }
}

variable "enable_fixtures_bucket" {
  description = <<-EOT
    Create the PRIVATE bucket for licensed fixture photos (store/fixtures/<app>/manifest.json entries
    whose `fetch.url` is s3://<fixtures_bucket_name>/fixtures/<sha256>.<ext>). Off until the first real
    photo exists: today every fixture is generated.
  EOT
  type        = bool
  default     = false
}

variable "fixtures_bucket_name" {
  description = "The PRIVATE fixtures bucket. ⚠ Never give it a custom domain or r2.dev — see main.tf."
  type        = string
  default     = "tenshu-store-fixtures"
}
