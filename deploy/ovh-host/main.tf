# One Tenshu box on OVHcloud Public Cloud: the instance, its firewall and (optionally) its durable
# data volume. A CHILD module, used by deploy/ovh-relay, deploy/ovh-commerce and deploy/ovh-apex,
# so each box keeps its own state, the way every other deploy/ module does.
#
# Built on the provider's `ovh_cloud_*` family (Public Cloud API v2). ⚠ Not the `ovh_cloud_project_*`
# resources most examples online still show: those are deprecated, and moving off them later is a
# state surgery (the provider's public_cloud_resources_migration guide).
#
# ⚠⚠ What this does NOT do: start anything. The host is prepared by cloud-init and stays idle until
# deploy/ovh-migrate copies the stack over. See cloud-init.yaml.tftpl.

terraform {
  required_version = ">= 1.7.0"
  required_providers {
    ovh = {
      source  = "ovh/ovh"
      version = "~> 2.24"
    }
  }
}

data "ovh_cloud_instance_flavors" "catalog" {
  service_name = var.service_name
  region       = var.region
}

data "ovh_cloud_instance_images" "catalog" {
  service_name = var.service_name
  region       = var.region
}

locals {
  flavor_ids = distinct([for f in data.ovh_cloud_instance_flavors.catalog.flavors : f.id if f.name == var.flavor_name])
  image_ids  = distinct([for i in data.ovh_cloud_instance_images.catalog.images : i.id if i.name == var.image_name && upper(i.status) == "ACTIVE"])

  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    role             = var.role
    data_mount       = var.data_volume_size_gb > 0 ? var.data_mount : ""
    swapfile_size_gb = var.swapfile_size_gb
    ssh_v4           = join(", ", [for c in var.ssh_allowed_cidrs : c if !strcontains(c, ":")])
    ssh_v6           = join(", ", [for c in var.ssh_allowed_cidrs : c if strcontains(c, ":")])
  })

  # The public (Ext-Net) interface is the one without a network id. ⚠ The API reports that id as
  # "" (empty string), not null, so test both.
  public_addresses = try(flatten([
    for n in ovh_cloud_instance.this.current_state.networks : n.addresses if try(n.id, "") == "" || n.id == null
  ]), [])
  public_ipv4 = try(one([for a in local.public_addresses : a.ip if a.version == 4]), null)
  public_ipv6 = try(one([for a in local.public_addresses : a.ip if a.version == 6]), null)
}

# ---- Firewall --------------------------------------------------------------
# Two layers. The HOST firewall (nftables, cloud-init.yaml.tftpl) is always on. The security group
# below is the network-side copy of the same rules, optional because a new project refuses it while
# port 25 is restricted (variables.tf `security_group`).
# ⚠⚠ With the group off, the instance gets the project's `default` group, which on a fresh OVH
# project allows everything in: the host firewall is then the only thing standing.
resource "ovh_cloud_security_group" "this" {
  count = var.security_group ? 1 : 0

  service_name = var.service_name
  region       = var.region
  name         = "tenshu-${var.role}"
  description  = "Tenshu ${var.role}: SSH from the allow-list, HTTP and HTTPS from anywhere"

  rule = concat(
    [for cidr in var.ssh_allowed_cidrs : {
      direction        = "INGRESS"
      ethernet_type    = strcontains(cidr, ":") ? "IPV6" : "IPV4"
      protocol         = "TCP"
      port_range_min   = 22
      port_range_max   = 22
      remote_ip_prefix = cidr
      description      = "SSH"
    }],
    # Caddy needs 80 for the ACME HTTP-01 challenge as well as 443 for serving. Closing 80 breaks
    # certificate renewal, which fails silently until the certificate expires.
    flatten([for port in [80, 443] : [
      for family in [{ t = "IPV4", p = "0.0.0.0/0" }, { t = "IPV6", p = "::/0" }] : {
        direction        = "INGRESS"
        ethernet_type    = family.t
        protocol         = "TCP"
        port_range_min   = port
        port_range_max   = port
        remote_ip_prefix = family.p
        description      = port == 80 ? "HTTP (ACME)" : "HTTPS"
      }
    ]]),
  )
}

# ---- Durable data plane ----------------------------------------------------
resource "ovh_cloud_storage_block_volume" "data" {
  count = var.data_volume_size_gb > 0 ? 1 : 0

  service_name = var.service_name
  region       = var.region
  name         = "tenshu-${var.role}-data"
  size         = var.data_volume_size_gb
  volume_type  = var.data_volume_type

  # ⚠⚠ This volume IS the production database once the migration has run. A plan that wants to
  # replace it is a plan that deletes it.
  lifecycle {
    prevent_destroy = true
  }
}

# ---- The instance ----------------------------------------------------------
resource "ovh_cloud_instance" "this" {
  service_name = var.service_name
  region       = var.region
  name         = "tenshu-${var.role}"
  flavor_id    = length(local.flavor_ids) == 1 ? local.flavor_ids[0] : null
  image_id     = length(local.image_ids) == 1 ? local.image_ids[0] : null
  ssh_key_name = var.ssh_key_name
  user_data    = base64encode(local.user_data)

  security_group_ids = var.security_group ? [ovh_cloud_security_group.this[0].id] : null
  # ⚠ null, not [], without a volume: the provider reads an empty list back as null and fails the
  # apply with "inconsistent result" (ovh provider 2.24).
  volume_ids = length(ovh_cloud_storage_block_volume.data) > 0 ? [for v in ovh_cloud_storage_block_volume.data : v.id] : null

  networks = [
    { auto_assign_public_ip = true },
  ]

  lifecycle {
    # ⚠⚠ On OVH, CHANGING `user_data` or `image_id` REINSTALLS the instance and wipes the root disk,
    # which holds /opt/tenshu (every env file and script the migration copied). The ids survive, so
    # nothing in the plan says "replace". Both only matter at first boot, so changes are ignored.
    # To really rebuild a box: `terraform apply -replace=module.host.ovh_cloud_instance.this`, then
    # re-run the copy.
    ignore_changes = [user_data, image_id]

    precondition {
      condition     = length(local.flavor_ids) == 1
      error_message = "Flavor \"${var.flavor_name}\" matched ${length(local.flavor_ids)} entries in ${var.region}. Names are lower-case, e.g. d2-4 or b3-8, and the catalog is per region."
    }
    precondition {
      condition     = length(local.image_ids) == 1
      error_message = "Image \"${var.image_name}\" matched ${length(local.image_ids)} active entries in ${var.region}. Names look like \"Ubuntu 24.04\"; list them with: terraform console -> data.ovh_cloud_instance_images.catalog."
    }
    precondition {
      condition     = var.data_volume_size_gb == 0 || var.data_mount != ""
      error_message = "A data volume needs a data_mount path."
    }
  }
}
