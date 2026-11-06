# Provider + Terraform pins for the Tenshu apex on Scaleway.
#
# ⚠⚠ This is a PARALLEL module to `deploy/digitalocean-apex/`, not a replacement, and that is
# deliberate. The DO apex is LIVE. Converting that module in place would orphan its state and leave a
# `terraform apply` pointed at production with a provider it no longer matches. Stand this up beside
# it, verify, then cut DNS over and destroy the old one — in that order.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    scaleway = {
      source  = "scaleway/scaleway"
      version = "~> 2.57"
    }
  }
}

# ⚠ ONE key pair, unlike DigitalOcean's separate API token and Spaces keys.
provider "scaleway" {
  access_key = var.access_key
  secret_key = var.secret_key
  project_id = var.project_id
  zone       = var.zone
  region     = var.region
}
