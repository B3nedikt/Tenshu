variable "service_name" {
  description = "OVH Public Cloud project id (32 hex characters)."
  type        = string
}

variable "region" {
  description = "Instance region, e.g. GRA11 or SBG5. ⚠ The data volume lives in the same region; the bucket region (GRA, SBG) is the region code without the number."
  type        = string
}

variable "role" {
  description = "relay, commerce or apex. Names the instance, the firewall and the volume."
  type        = string
}

variable "flavor_name" {
  description = "Instance flavor name, e.g. d2-2, d2-4, b3-8."
  type        = string
}

variable "image_name" {
  description = "Base image name. ⚠ The bootstrap assumes Ubuntu (Docker's apt repository)."
  type        = string
}

variable "ssh_key_name" {
  description = "Name of an ovh_cloud_ssh_key in the project (deploy/ovh-project creates one). ⚠ Changing it recreates the instance."
  type        = string
}

variable "ssh_allowed_cidrs" {
  description = "Sources allowed to reach port 22."
  type        = list(string)
}

variable "data_volume_size_gb" {
  description = "Size of the durable data volume. 0 = no volume (apex is stateless)."
  type        = number
}

variable "data_volume_type" {
  description = "CLASSIC, HIGH_SPEED or HIGH_SPEED_GEN2."
  type        = string
  default     = "CLASSIC"
}

variable "data_mount" {
  description = "Where the data volume is mounted. Must match the source box: /mnt/relay_data or /mnt/commerce_data."
  type        = string
  default     = ""
}

variable "swapfile_size_gb" {
  description = "Swapfile on the ROOT disk, in GB (0 = none). Images are prebuilt, so 1 is enough."
  type        = number
  default     = 1
}

variable "security_group" {
  description = <<-EOT
    Create and attach an OVH security group. false = the project's `default` group (allows all
    inbound) and the host firewall alone decides. ⚠ A NEW project refuses every security-group
    call while outgoing port 25 is restricted (403 SMTPPort25Restricted), hence the switch.
    ⚠ Turning it on later changes the instance's security_group_ids, which may REINSTALL it
    (root disk wiped): do it only while `deploy/ovh-migrate/1-prepare.sh` can simply be re-run.
  EOT
  type        = bool
  default     = true
}
