# Provider + Terraform pins for the Tenshu dev lab on Scaleway.
#
# ⚠⚠ This module is a SIBLING of deploy/scaleway-relay/ + -commerce/ + -apex/, not a variant of
# them. Those three each run one service on its own box, because that is what production needs.
# This one runs ALL THREE on a single box against a throwaway domain, because that is what a dev
# plane needs: a third of the cost, one IP, and a reset that is one command rather than three.
#
# ⚠⚠ Point it at a SEPARATE Scaleway Project. Projects are free, and the isolation is the point —
# a `terraform destroy` here can then never reach a production resource, and the dev lab's billing
# and IAM are separable. See variables.tf `project_id`.

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
#
# ⚠ The *preferred Project* trap documented in deploy/models/ and deploy/scaleway-tiles/ does NOT
# apply here: that one is S3-only (Object Storage carries no Project parameter), and this module
# creates no bucket. `project_id` below is passed explicitly to every resource.
provider "scaleway" {
  access_key = var.access_key
  secret_key = var.secret_key
  project_id = var.project_id
  zone       = var.zone
  region     = var.region
}
