# Provider + Terraform version pins for the commerce database's backup buckets.
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

# ⚠ One API key pair serves BOTH the Scaleway API and S3. This is the ADMIN pair that runs Terraform;
# the commerce box gets its own least-privilege key from deploy/scaleway-backup-access instead.
provider "scaleway" {
  access_key = var.access_key
  secret_key = var.secret_key
  project_id = var.project_id
  region     = var.region
}

# The second region (nl-ams): a bucket's region is the endpoint the provider talks to.
provider "scaleway" {
  alias      = "dr"
  access_key = var.access_key
  secret_key = var.secret_key
  project_id = var.project_id
  region     = var.dr_region
}
