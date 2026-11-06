# Provider + Terraform version pins for the Tenshu offline-tiles serving infra.
# Pinned (not floating) so a re-apply months later resolves the same plugin — matching
# the repo ethos of reproducible, vendor-neutral infra. Mirrors deploy/digitalocean-commerce/.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.43"
    }
  }
}

# Two credentials, two scopes (maps-region-delivery.md §3 D1):
#   - token             : the DO API token (creates the CDN endpoint, cert, DNS records)
#   - spaces_access_id  : the S3-compatible Spaces key pair (creates the bucket + policy)
# Supply the API token via `do_token` (terraform.tfvars, git-ignored) or DIGITALOCEAN_TOKEN;
# supply the Spaces keys via the spaces_* vars or the SPACES_ACCESS_KEY_ID /
# SPACES_SECRET_ACCESS_KEY env vars the provider also reads.
provider "digitalocean" {
  token             = var.do_token
  spaces_access_id  = var.spaces_access_id
  spaces_secret_key = var.spaces_secret_key
}
