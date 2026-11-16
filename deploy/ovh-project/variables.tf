variable "service_name" {
  description = "OVH Public Cloud project id (32 hex characters; console > Public Cloud > project, top left)."
  type        = string
}

variable "bucket_region" {
  description = "Object Storage region, e.g. GRA or SBG. ⚠ Keep it the region the boxes run in (GRA11 -> GRA), or every content chunk crosses regions."
  type        = string
  default     = "GRA"
}

variable "bucket_prefix" {
  description = "Bucket name prefix. ⚠ Bucket names are global across OVH and are not re-creatable for a while after deletion."
  type        = string
  default     = "tenshu-ovh"
}

variable "ssh_key_name" {
  description = "Name of the project SSH key the boxes boot with."
  type        = string
  default     = "tenshu-deploy"
}

variable "ssh_public_key" {
  description = "Public key content, e.g. the contents of ~/.ssh/id_ed25519.pub. Every tool reaches the boxes as root with it."
  type        = string
}

variable "backup_retention_days" {
  description = "Days a database backup is kept before the bucket deletes it. ⚠ The privacy notice states 30; change both together."
  type        = number
  default     = 30
}
