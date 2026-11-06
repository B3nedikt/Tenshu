# Provider + Terraform version pins for the DigitalOcean relay deployment.
# Pinned (not floating) so a re-apply months later resolves the same plugin -
# matching the repo ethos of reproducible, vendor-neutral infra.

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
