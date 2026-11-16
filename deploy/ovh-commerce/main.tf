# The Tenshu commerce (JVM + Postgres sidecar). Today's Scaleway box has 3 vCPU / 3 GB.
#
# ⚠⚠ Terraform prepares an idle host. The stack is COPIED onto it from the Scaleway box by
# deploy/ovh-migrate (docs/how-to/move-to-ovh.md), never started from here.

module "host" {
  source = "../ovh-host"

  service_name        = var.service_name
  region              = var.region
  role                = "commerce"
  flavor_name         = var.flavor_name
  image_name          = var.image_name
  ssh_key_name        = var.ssh_key_name
  ssh_allowed_cidrs   = var.ssh_allowed_cidrs
  security_group      = var.security_group
  data_volume_size_gb = var.data_volume_size_gb
  data_mount          = "/mnt/commerce_data"
}
