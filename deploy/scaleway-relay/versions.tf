# Provider + Terraform pins for the Tenshu sync relay on Scaleway.
#
# ⚠⚠ A SEPARATE module from `deploy/digitalocean/` (the retired relay), not a conversion of it, and
# that is deliberate: converting a live module in place would orphan its state and leave a
# `terraform apply` pointed at production with a provider it no longer matches. This one is now
# production. Any future move repeats the pattern — stand up beside, verify, move the data, cut DNS,
# destroy the old one — in that order.

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
