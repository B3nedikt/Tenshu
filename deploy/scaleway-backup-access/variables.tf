variable "access_key" {
  description = "ADMIN Scaleway API access key (IAMManager + Object Storage). Never a box key."
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "ADMIN Scaleway API secret key."
  type        = string
  sensitive   = true
}

variable "organization_id" {
  description = "Scaleway Organization ID. IAM applications and policies belong to the Organization, not a Project."
  type        = string
}

variable "project_id" {
  description = <<-EOT
    The Project holding every bucket below. ⚠ IAM permission sets scope to a Project, not a bucket:
    the per-bucket restriction comes from the bucket policies in main.tf.
  EOT
  type        = string
}

variable "region" {
  description = "Region of the content plane and the primary backup buckets."
  type        = string
  default     = "fr-par"
}

variable "dr_region" {
  description = "Region of the second-region copies (deploy/scaleway-*-backups `dr_region`)."
  type        = string
  default     = "nl-ams"
}

# ---------------------------------------------------------------- buckets (names from the bucket modules)

variable "relay_backup_bucket" {
  description = "deploy/scaleway-relay-backups `bucket_name` (fr-par relay.db snapshots)."
  type        = string
  default     = "tenshu-relay-db-backups"
}

variable "relay_backup_dr_bucket" {
  description = "deploy/scaleway-relay-backups `dr_bucket_name`."
  type        = string
  default     = "tenshu-relay-db-backups-ams"
}

variable "blob_mirror_bucket" {
  description = "deploy/scaleway-relay-backups `blob_mirror_bucket_name`."
  type        = string
  default     = "tenshu-relay-blobs-mirror"
}

variable "blob_mirror_dr_bucket" {
  description = "deploy/scaleway-relay-backups `dr_blob_mirror_bucket_name`."
  type        = string
  default     = "tenshu-relay-blobs-mirror-ams"
}

variable "commerce_backup_bucket" {
  description = "deploy/scaleway-commerce-backups `bucket_name`."
  type        = string
  default     = "tenshu-commerce-db-backups"
}

variable "commerce_backup_dr_bucket" {
  description = "deploy/scaleway-commerce-backups `dr_bucket_name`."
  type        = string
  default     = "tenshu-commerce-db-backups-ams"
}

variable "content_bucket" {
  description = "The relay's content-plane bucket (deploy/scaleway-relay-blobs). Policed only if `enforce_on_content_bucket`."
  type        = string
  default     = "tenshu-relay-blobs"
}

# ---------------------------------------------------------------- who keeps full access

variable "admin_principals" {
  description = <<-EOT
    Principals that keep FULL access to every bucket this module puts a policy on — at least the
    maintainer, and whatever principal owns the admin key above. Scaleway format:
    "user_id:<uuid>" or "application_id:<uuid>".

    ⚠⚠ Lock-out guard. On Scaleway a bucket with a policy is reachable ONLY by the principals it names
    (the Organization owner excepted), and IAM permissions do not override that. A policy that omits
    you leaves the bucket readable by nobody but the box key — including for restores and for
    set-lifecycle.py — until the Organization owner deletes the policy.
  EOT
  type        = list(string)

  validation {
    condition = length(var.admin_principals) > 0 && alltrue([
      for p in var.admin_principals : can(regex("^(user_id|application_id):[0-9a-f-]{36}$", p))
    ])
    error_message = "admin_principals needs at least one \"user_id:<uuid>\" or \"application_id:<uuid>\"."
  }
}

# ---------------------------------------------------------------- staged switches

variable "enforce_on_existing_buckets" {
  description = <<-EOT
    Put bucket policies on the three EXISTING fr-par buckets (relay snapshots, blob mirror, commerce
    dumps). ⚠⚠ Default false: the boxes still write there with the OLD shared key until they are
    rotated, and a policy that does not name that key's principal stops last night's job tonight.
    Turn it on only after both boxes have run a nightly (and the mirror a weekly) on the new keys —
    runbook step 11 in docs/how-to/back-up-the-relay-database.md. The NEW nl-ams buckets are policed
    from the start: nothing has written to them with the old key.
  EOT
  type        = bool
  default     = false
}

variable "create_relay_content_key" {
  description = <<-EOT
    Mint a dedicated key for the relay's CONTENT PLANE (S3_ACCESS_KEY on the relay box), replacing the
    admin key it carries today. ⚠⚠ Without this the backup split on the relay box is nominal: the
    admin key sitting in S3_* reaches every backup bucket with full rights, so a compromised box
    deletes everything regardless of what BACKUP_S3_* can do. Creating the key changes nothing on its
    own; putting it in the relay's deploy.env is a production credential rotation (runbook step 10).
  EOT
  type        = bool
  default     = true
}

variable "enforce_on_content_bucket" {
  description = <<-EOT
    Put a bucket policy on the CONTENT-PLANE bucket naming only the admins, the relay's content key
    and the mirror job (read-only). Closes the one gap IAM cannot: every other key here has
    project-wide object rights and would otherwise reach the content bucket (the commerce key could
    overwrite blobs). ⚠⚠ Default false, and LAST in the runbook: a mistake here takes the production
    relay's storage away from it. Requires create_relay_content_key, and the relay running on that key.
  EOT
  type        = bool
  default     = false
}

variable "key_expires_at" {
  description = "Optional RFC3339 expiry for every minted key (null = no expiry). Forces a rotation cadence."
  type        = string
  default     = null
}
