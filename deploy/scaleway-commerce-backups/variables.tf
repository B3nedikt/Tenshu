variable "access_key" {
  description = "Scaleway API access key. Doubles as the S3 access key (see versions.tf)."
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "Scaleway API secret key. Doubles as the S3 secret key."
  type        = string
  sensitive   = true
}

variable "project_id" {
  description = "Scaleway Project ID."
  type        = string
}

variable "region" {
  description = "Object Storage region."
  type        = string
  default     = "fr-par"
}

variable "bucket_name" {
  description = <<-EOT
    Bucket holding nightly `pg_dump` snapshots of the commerce database. ⚠⚠ Globally unique across
    Scaleway and NOT re-creatable under the same name for a while after deletion.

    ⚠ Deliberately separate from the relay's backup bucket. Commerce dumps contain account, session
    and billing rows, and the relay box holds S3 credentials in `/opt/tenshu/deploy.env` — one bucket
    for both would mean a compromise of the relay box exposes billing backups.

    ⚠⚠ The separation is of DATA until deploy/scaleway-backup-access is applied AND each box carries
    only its own key: until then one project-wide key pair reaches every bucket. That module gives
    commerce its own key and puts a bucket policy on this bucket and its second-region copy naming
    only the admins and that key. ⚠ IAM itself scopes to a project, not a bucket, so the commerce key
    still reaches buckets that have NO policy (the content plane) — see its README for the limit.
  EOT
  type        = string
}

variable "retention_days" {
  description = "Days before a snapshot expires. Applied by set-lifecycle.py, not Terraform — see main.tf."
  type        = number
  default     = 30
}

variable "dr_region" {
  description = "Object Storage region for the second copy. ⚠ Must differ from `region`."
  type        = string
  default     = "nl-ams"

  validation {
    condition     = contains(["fr-par", "nl-ams", "pl-waw"], var.dr_region)
    error_message = "dr_region must be a Scaleway Object Storage region (fr-par, nl-ams, pl-waw)."
  }
}

variable "dr_bucket_name" {
  description = <<-EOT
    Second-region bucket for the same nightly dump. ⚠⚠ Globally unique, and Object Lock is decided at
    creation (`dr_snapshot_lock_days`) — settle it before the first apply.
  EOT
  type        = string
}

variable "dr_snapshot_lock_days" {
  description = <<-EOT
    Object Lock default retention on the second-region bucket. 0 = no lock, no versioning. With it, an
    overwrite keeps the old version and no box key can remove a version for this many days — the copy a
    compromised commerce box cannot destroy. Keep equal to `retention_days`. ⚠⚠ Creation-time only.
  EOT
  type        = number
  default     = 30

  validation {
    condition     = var.dr_snapshot_lock_days >= 0 && var.dr_snapshot_lock_days <= 90
    error_message = "dr_snapshot_lock_days must be 0 (off) to 90."
  }
}

variable "dr_snapshot_lock_mode" {
  description = "GOVERNANCE (an admin with the bypass right can still remove a version) or COMPLIANCE (nobody can)."
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.dr_snapshot_lock_mode)
    error_message = "dr_snapshot_lock_mode must be GOVERNANCE or COMPLIANCE."
  }
}
