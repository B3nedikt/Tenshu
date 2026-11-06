# Provider + Terraform version pins for the backup jobs' least-privilege keys and bucket policies.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    scaleway = {
      source  = "scaleway/scaleway"
      version = "~> 2.57"
    }
  }
}

# ⚠ The ADMIN pair: it must be allowed to create IAM applications, policies and API keys
# (IAMManager, or Organization owner) and to put bucket policies. It is the only key that should ever
# see this module's state — the state holds every secret it mints.
provider "scaleway" {
  access_key      = var.access_key
  secret_key      = var.secret_key
  organization_id = var.organization_id
  project_id      = var.project_id
  region          = var.region
}

# Bucket policies are regional: the second-region buckets' policies go through an nl-ams endpoint.
provider "scaleway" {
  alias           = "dr"
  access_key      = var.access_key
  secret_key      = var.secret_key
  organization_id = var.organization_id
  project_id      = var.project_id
  region          = var.dr_region
}
