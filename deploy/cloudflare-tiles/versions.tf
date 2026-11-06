# Provider + Terraform version pins for the Tenshu tiles serving infra on Cloudflare R2.
# Pinned (not floating) so a re-apply months later resolves the same plugin.
#
# ⚠⚠ CLOUDFLARE, the one piece of the backend that is NOT on Scaleway. Chosen 2026-09-14 over
# Scaleway Edge Services for one reason that outweighs the third provider: tiles is a PUBLIC endpoint
# serving streamed ranged GETs, and on a per-GB-billed origin a crawler's bill has no ceiling. R2's
# egress is free. The full comparison is in maps-region-delivery.md D1.
#
# ⚠ Provider v5 is a rewrite (generated from the API schema). Resource and attribute names differ
# from v4 — `cloudflare_r2_managed_domain` and `cloudflare_r2_custom_domain` do not exist in v4, and
# v4-era examples found online will not validate here.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.25"
    }
  }
}

# ⚠ ONE credential for Terraform, and it is NOT the one the copy/publish scripts use:
#   - api_token (here)     : a Cloudflare API token — creates the bucket, the r2.dev toggle and the
#                            custom domain through the Cloudflare REST API
#   - R2 access key pair   : an S3-compatible key minted separately under R2 → Manage API tokens,
#                            used by copy-tiles-from-digitalocean.py and scripts/tiles-publish.sh
# Leave api_token unset to use the CLOUDFLARE_API_TOKEN environment variable instead.
provider "cloudflare" {
  api_token = var.api_token
}
