# Provider + Terraform version pins for the Tenshu model-catalog serving infra on Cloudflare R2.
# Pinned (not floating) so a re-apply months later resolves the same plugin — the SAME pin as
# deploy/cloudflare-tiles, whose lock file this module's was copied from.
#
# ⚠⚠ R2, for the tiles reason and one of its own (README.md): a public endpoint that streams ~380 MB
# artifacts with resumable ranged GETs, where a per-GB-billed origin's bill has no ceiling — and a
# custom domain on our own zone, so the URL compiled into Control names us rather than a provider.
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
#                          custom domain through the Cloudflare REST API
#   - R2 access key pair : an S3-compatible key minted separately under R2 → Manage API tokens, used by
#                          scripts/models-publish.sh
# Leave api_token unset to use the CLOUDFLARE_API_TOKEN environment variable instead.
provider "cloudflare" {
  api_token = var.api_token
}
