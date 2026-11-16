variable "service_name" {
  description = "OVH Public Cloud project id."
  type        = string
}

variable "region" {
  description = "Instance region. ⚠ Keep it next to the buckets (GRA11 with bucket region GRA)."
  type        = string
  default     = "GRA11"
}

variable "flavor_name" {
  description = "Instance flavor. d2-2 = 1 vCPU / 2 GB / 25 GB root; d2-4 = 2 vCPU / 4 GB / 50 GB root."
  type        = string
  default     = "d2-2"
}

variable "image_name" {
  description = "Base image (Ubuntu; the bootstrap uses Docker's Ubuntu apt repository). 22.04 matches the Scaleway boxes, so the move changes the provider and nothing else."
  type        = string
  default     = "Ubuntu 22.04"
}

variable "ssh_key_name" {
  description = "deploy/ovh-project's ssh_key_name output."
  type        = string
  default     = "tenshu-deploy"
}

variable "ssh_allowed_cidrs" {
  description = "Sources allowed to reach port 22. Narrow it if you can."
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

variable "data_volume_size_gb" {
  description = "Durable data volume, mounted at /mnt/relay_data like on Scaleway."
  type        = number
  default     = 20
}

variable "security_group" {
  description = "false while the project's port-25 restriction blocks security groups; the host firewall (nftables, cloud-init) applies either way. See deploy/ovh-host/variables.tf."
  type        = bool
  default     = true
}
