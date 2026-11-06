# Provider + Terraform version pins for the Android direct-release bucket on Cloudflare R2. The SAME
# pin as deploy/cloudflare-assets, whose lock file this one was copied from.
#
# ⚠ Provider v5 is a rewrite (generated from the API schema). `cloudflare_r2_managed_domain` and
# `cloudflare_r2_custom_domain` do not exist in v4, and v4-era examples will not validate here.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.25"
    }
  }
}

# ⚠ ONE credential for Terraform, and it is NOT the one the publish script uses:
#   - api_token (here)   : a Cloudflare API token — creates the bucket, the r2.dev toggle and the
#                          custom domain through the Cloudflare REST API. tf.sh reads it from the
#                          Keychain (`cloudflare-api-token`, the token the other R2 modules use).
#   - R2 access key pair : an S3-compatible key minted under R2 → Manage API tokens AFTER the bucket
#                          exists, scoped to it ALONE; scripts/android-direct-publish.sh reads
#                          `r2-releases-*`. ⚠⚠ Never the `r2-assets-*` pair (D3).
provider "cloudflare" {
  api_token = var.api_token
}
