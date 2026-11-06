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
  description = "Scaleway Project ID. Every resource is scoped to a Project; there is no DO analogue."
  type        = string
}

variable "region" {
  description = "Object Storage region. Put this where the relay is, so a nightly upload stays local."
  type        = string
  default     = "fr-par"
}

variable "bucket_name" {
  description = <<-EOT
    Bucket holding nightly `relay.db` snapshots. ⚠⚠ Globally unique across Scaleway and NOT
    re-creatable under the same name for a while after deletion.

    ⚠ Deliberately NOT the content-plane bucket. Nothing in the relay lists that bucket — every
    enumeration goes through the SQLite index (`BlobMigration.enumerateKeys`) — so a backup object
    there would not be reclaimed. The separation is for the humans: the content bucket's object
    count and total size are load-bearing evidence during a migration (they were checked against
    the index and the source file count on 2026-09-13), and mixing in backups silently invalidates
    exactly that check.
  EOT
  type        = string
}

variable "retention_days" {
  description = <<-EOT
    How many daily snapshots the bucket keeps. Enforced by a lifecycle rule, NOT by the upload
    script — a pruner that only runs when the backup runs stops pruning exactly when backups stop,
    which is when the bucket is growing fastest.
  EOT
  type        = number
  default     = 30
}

variable "blob_mirror_bucket_name" {
  description = <<-EOT
    Bucket holding a mirror of the content plane that prunes only after a window. ⚠⚠ Globally unique and NOT
    re-creatable under the same name for a while after deletion.

    ⚠ What this protects against, and what it does not. Scaleway already replicates object storage
    within a region, so a same-region mirror buys nothing against disk or datacentre loss. It exists
    for **logical** deletion: the relay has a reclamation path (`dropRefs`, candidate → veto →
    reclaim; since B6 the content sweep) that deletes blobs, and an operator or a bug there can
    remove live content that no device still holds. That is the failure this copy survives — which
    is why the mirror deletes only keys the source has lacked for `blob_mirror_retention_days`, and
    only when pruning is switched on. Surviving a regional loss is `dr_blob_mirror_bucket_name`'s job: a
    cross-region copy cannot be server-side, so that one streams through the box.
  EOT
  type        = string
}

variable "source_bucket_name" {
  description = "The content-plane bucket the mirror copies FROM (deploy/scaleway-relay-blobs)."
  type        = string
  default     = "tenshu-relay-blobs"
}

variable "blob_mirror_retention_days" {
  description = <<-EOT
    How long a key must have been missing from the content bucket before `mirror-blobs.py` prunes it
    from the mirror — rendered as MIRROR_RETENTION_DAYS by `blob_mirror_settings_for_deploy_env`.

    ⚠ This is the trade the mirror makes: a reclamation bug noticed later than this is no longer
    recoverable from the mirror. A key is pruned once it has been missing for the window by the clock
    AND on ceil(window/7)+1 weekly runs, so with punctual runs a deleted file's blocks leave the mirror
    35–42 days after the delete at 30. ⚠ That is the normal case, not a bound: a failed run, a tripped
    breaker (held for review), a clock refusal or a dead timer all postpone it. The privacy policy
    (acceptable-use-and-limits.md §4.4) is worded accordingly.
  EOT
  type        = number
  default     = 30

  validation {
    # The script enforces the same bounds (and disables pruning outside them): below a week the
    # mirror stops covering a bug noticed at the next weekly look.
    condition     = floor(var.blob_mirror_retention_days) == var.blob_mirror_retention_days && var.blob_mirror_retention_days >= 7 && var.blob_mirror_retention_days <= 3650
    error_message = "blob_mirror_retention_days must be a whole number of days between 7 and 3650 (the script's bounds)."
  }
}

variable "blob_mirror_prune" {
  description = <<-EOT
    Whether `mirror-blobs.py` actually deletes keys past the window — rendered as MIRROR_PRUNE=on/off.
    ⚠⚠ Default false: deleting from the mirror is a deploy-time decision, not a default. With it off
    the script still keeps the ledger, so the window has already run when this is turned on. Nothing
    here reaches the box: the output is pasted into /opt/tenshu/deploy.env by hand.
  EOT
  type        = bool
  default     = false
}

# ---------------------------------------------------------------- the second region

variable "dr_region" {
  description = <<-EOT
    Object Storage region for the disaster-recovery copies. ⚠ Must differ from `region`: a "second
    region" in the first one is a same-region copy that looks like geo-redundancy.
  EOT
  type        = string
  default     = "nl-ams"

  validation {
    condition     = contains(["fr-par", "nl-ams", "pl-waw"], var.dr_region)
    error_message = "dr_region must be a Scaleway Object Storage region (fr-par, nl-ams, pl-waw)."
  }
}

variable "dr_bucket_name" {
  description = <<-EOT
    Second-region bucket for the nightly `relay.db` snapshots — the same file the box uploads to
    `bucket_name`, uploaded again. ⚠⚠ Globally unique and NOT re-creatable under the same name for a
    while after deletion. ⚠⚠ Object Lock is decided at CREATION (`dr_snapshot_lock_days`) and cannot be
    added to an existing bucket, so settle it before the first apply.
  EOT
  type        = string
}

variable "dr_blob_mirror_bucket_name" {
  description = <<-EOT
    Second-region mirror of the content plane, filled by `mirror-blobs.py --target dr` (streamed
    through the box). Own ledger, own prune switch (`MIRROR_DR_PRUNE`). ⚠⚠ Globally unique.
  EOT
  type        = string
}

variable "dr_snapshot_lock_days" {
  description = <<-EOT
    Object Lock default retention on the second-region SNAPSHOT bucket, in days. 0 = no lock and no
    versioning. With a lock, the bucket is versioned and every snapshot version is undeletable — and an
    overwrite keeps the old version — for this many days, by anyone without the bypass right; that is
    what makes this the one copy a compromised box cannot destroy (docs/how-to/back-up-the-relay-database.md,
    "Keys"). Keep it equal to `retention_days`: `set-lifecycle.py` expires the current version at
    `retention_days` and the old version a day later, which the lock delays until it lapses.
    ⚠⚠ Fixed at bucket creation: changing 0 <-> non-zero later means a new bucket (and a new name).
  EOT
  type        = number
  default     = 30

  validation {
    condition     = var.dr_snapshot_lock_days >= 0 && var.dr_snapshot_lock_days <= 90
    error_message = "dr_snapshot_lock_days must be 0 (off) to 90. A longer lock is a bill nobody can cancel."
  }
}

variable "dr_snapshot_lock_mode" {
  description = <<-EOT
    GOVERNANCE: a principal holding the bypass right (only the admin, never a box key) can still delete a
    locked version deliberately. COMPLIANCE: nobody can, the Organization owner included, until it lapses.
    Default GOVERNANCE so a mistake stays fixable; see the open decisions in the how-to.
  EOT
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.dr_snapshot_lock_mode)
    error_message = "dr_snapshot_lock_mode must be GOVERNANCE or COMPLIANCE."
  }
}
