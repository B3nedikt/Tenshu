# Provider + Terraform version pins for the relay's CONTENT-PLANE bucket.
# Pinned (not floating) so a re-apply months later resolves the same plugin.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    scaleway = {
      source  = "scaleway/scaleway"
      version = "~> 2.57"
    }
  }
}

# ⚠ One API key pair serves BOTH the Scaleway API and S3 — unlike DigitalOcean, where the API token
# and the Spaces keys are separate things. The same pair therefore goes into the relay's `deploy.env`
# as `S3_ACCESS_KEY` / `S3_SECRET_KEY`; see outputs.tf.
provider "scaleway" {
  access_key = var.access_key
  secret_key = var.secret_key
  project_id = var.project_id
  region     = var.region
}
