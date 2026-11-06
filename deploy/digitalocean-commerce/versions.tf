# Provider + Terraform version pins for the DigitalOcean commerce-issuer deployment.
# Pinned (not floating) so a re-apply months later resolves the same plugin — matching
# the repo ethos of reproducible, vendor-neutral infra. Mirrors deploy/digitalocean/.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.43"
    }
  }
}

# The DO token is the only infra secret. Supply it via the `do_token` variable
# (terraform.tfvars, which is git-ignored) or the DIGITALOCEAN_TOKEN env var.
provider "digitalocean" {
  token = var.do_token
}
