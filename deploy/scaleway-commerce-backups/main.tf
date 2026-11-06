# Nightly snapshots of the commerce database.
#
# ## Why this exists
#
# Surveyed 2026-09-14: commerce had **no backups of any kind** — no cron, no systemd timer, no dumps
# on disk — for the service holding account, session, entitlement and billing rows. The relay got
# nightly snapshots the same week; commerce had nothing, and its data is the kind you cannot
# reconstruct from a device.
#
# It is small — 8 071 kB, 12 tables — so this costs essentially nothing and there was never a
# capacity reason to skip it.
#
# ## Restore
#
# `pg_dump -Fc` writes a custom-format archive: compressed, and restorable selectively with
# `pg_restore`. ⚠ Postgres is **16.14**, and a restore target must be ≥ 16 — a 15 refuses the archive.
# See `docs/how-to/back-up-the-commerce-database.md`.

resource "scaleway_object_bucket" "commerce_backups" {
  name       = var.bucket_name
  region     = var.region
  project_id = var.project_id

  tags = {
    project = "tenshu"
    purpose = "commerce-db-backups"
  }

  # ⚠⚠ Terraform's `lifecycle` meta-argument, unrelated to the bucket's `lifecycle_rule` attribute
  # despite the name. Retention is applied out-of-band by `set-lifecycle.py`, because the Scaleway
  # provider serialises `expired_object_delete_marker` alongside `days` and Scaleway's S3 rejects the
  # pair with MalformedXML — failing AFTER the bucket is created and leaving it tainted, so the next
  # plan proposes destroying a bucket whose name is not immediately re-creatable. Without this guard
  # every later plan would also propose deleting the rule the script applied.
  lifecycle {
    ignore_changes = [lifecycle_rule]
  }
}

# The second-region copy (nl-ams) of the same nightly dump, uploaded a second time by
# backup-commerce-db.sh. ⚠⚠ Versioned + Object-Locked (unless dr_snapshot_lock_days = 0) for the reason
# spelled out on relay_backups_dr in deploy/scaleway-relay-backups: the box's key can PUT, and on an
# unversioned bucket an overwrite destroys a dump as surely as a delete. Retention via set-lifecycle.py,
# which adds the noncurrent-version clause on a versioned bucket.
resource "scaleway_object_bucket" "commerce_backups_dr" {
  provider   = scaleway.dr
  name       = var.dr_bucket_name
  region     = var.dr_region
  project_id = var.project_id

  object_lock_enabled = var.dr_snapshot_lock_days > 0

  versioning {
    enabled = var.dr_snapshot_lock_days > 0
  }

  tags = {
    project = "tenshu"
    purpose = "commerce-db-backups-dr"
  }

  lifecycle {
    ignore_changes = [lifecycle_rule]

    precondition {
      condition     = var.dr_region != var.region
      error_message = "dr_region equals region: a second-region copy in the first region is not geo-redundancy."
    }
  }
}

resource "scaleway_object_bucket_lock_configuration" "commerce_backups_dr" {
  count    = var.dr_snapshot_lock_days > 0 ? 1 : 0
  provider = scaleway.dr
  bucket   = scaleway_object_bucket.commerce_backups_dr.id
  region   = var.dr_region

  rule {
    default_retention {
      mode = var.dr_snapshot_lock_mode
      days = var.dr_snapshot_lock_days
    }
  }
}
