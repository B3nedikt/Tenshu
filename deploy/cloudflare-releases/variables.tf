# Credentials and settings for the Cloudflare R2 Android direct-release bucket.

variable "api_token" {
  description = "Cloudflare API token for Terraform. Null reads CLOUDFLARE_API_TOKEN from the environment instead (tf.sh fills it from the Keychain), which keeps it out of terraform.tfvars. ⚠ Not the R2 S3 key pair — see versions.tf."
  type        = string
  sensitive   = true
  default     = null
}

variable "account_id" {
  description = "Cloudflare account ID — the same value deploy/cloudflare-assets uses. Not a secret. ⚠ Also part of the S3 endpoint (https://<account_id>.r2.cloudflarestorage.com)."
  type        = string
}

variable "zone_id" {
  description = "Zone ID of tenshu.app on Cloudflare (deploy/cloudflare-dns's `zone_id` output). The custom domain attaches to it and creates its own read-only DNS record there."
  type        = string
}

variable "bucket_name" {
  description = "The PUBLIC release bucket. Objects land at sha256/<hex>.apk and sha256/<hex>.dmg — the layout scripts/android-direct-publish.sh and scripts/macos-direct-publish.sh upload. ⚠⚠ Never tenshu-assets (D3): a bucket of its own is the point."
  type        = string
  default     = "tenshu-releases"

  validation {
    condition     = var.bucket_name != "tenshu-assets"
    error_message = "The release bucket must not be tenshu-assets (android-direct-apk-distribution.md D3)."
  }
}

variable "location" {
  description = <<-EOT
    R2 location HINT: apac, eeur, enam, weur, wnam or oc — the assets bucket's choice, for its reasons.
    ⚠ A hint, honoured only when a bucket of this name is first created. ⚠⚠ Deliberately NOT a
    jurisdiction: the APKs are public, and a jurisdictional bucket answers only on its own S3
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
    The public hostname. ⚠ Baked into store/android-release.json (`base` and every `url`) and so into
    the download pages: changing it means re-writing the manifest, never a re-upload (the keys stay
    the same). One level below the zone on purpose — Universal SSL (*.tenshu.app) covers it — and
    outside *.service because no client compiles it in (the apps link the download PAGE, not this).
  EOT
  type        = string
  default     = "releases.tenshu.app"
}

variable "min_tls" {
  description = "Minimum TLS version on the custom domain. Cloudflare's default is 1.0; 1.2 is what every Android browser that can install these APKs (minSdk 24) speaks."
  type        = string
  default     = "1.2"

  validation {
    condition     = contains(["1.0", "1.1", "1.2", "1.3"], var.min_tls)
    error_message = "min_tls must be one of 1.0, 1.1, 1.2, 1.3."
  }
}
