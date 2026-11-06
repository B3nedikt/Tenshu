# Nightly snapshots of the relay's SQLite database.
#
# ## Why this exists
#
# Until 2026-09-14 the relay's database had no backup at all, on either provider. That was survivable
# only by accident: the DigitalOcean box kept a stopped-but-intact copy after the Scaleway cutover, so
# there was a second copy of the sync log and a duplicate of every content blob. Destroying that box —
# the last step of the provider move — removes both, and would have left the log single-copy.
#
# ## What is and is not protected
#
# This covers the SQL side only: `sync_block`, `device`, `content_blob`/`content_edge`/`content_ref`,
# shares, grants, mailboxes. The content blobs themselves live in the content-plane bucket and are NOT
# copied here — 5.35 GB nightly would be absurd, and they are content-addressed and re-uploadable by a
# client that still holds the bytes. ⚠ But that makes the content bucket single-copy once the old box
# is gone: no versioning (deliberately — see the blobs module) and now no duplicate. A lost object is
# recoverable only from whichever device still has the file.
#
# ## Restore
#
# The snapshot is a whole, self-contained database — `VACUUM INTO` writes a checkpointed file with no
# WAL — so a restore is: stop the relay, drop it in as `/mnt/relay_data/relay.db`, delete any stale
# `-wal`/`-shm` beside it, start. See `docs/how-to/back-up-the-relay-database.md`.

resource "scaleway_object_bucket" "relay_backups" {
  name       = var.bucket_name
  region     = var.region
  project_id = var.project_id

  tags = {
    project = "tenshu"
    purpose = "relay-db-backups"
  }

  # ⚠⚠ Terraform's `lifecycle` meta-argument, not the bucket's `lifecycle_rule` attribute — the two
  # are unrelated despite the name, and this block is about the latter.
  #
  # Retention is applied out-of-band by `set-lifecycle.py` (see below for why it has to be). Without
  # this, every plan sees a rule on the bucket that the config does not declare and proposes removing
  # it — so each apply would silently delete the retention policy, and the drift would return for
  # ever. Measured: the plan that added the mirror bucket carried a `- lifecycle_rule` against this
  # bucket.
  lifecycle {
    ignore_changes = [lifecycle_rule]
  }
}

# ⚠⚠ Retention is NOT expressed as a `lifecycle_rule` above, and that is not an oversight.
#
# The Scaleway provider's `expiration` block carries `expired_object_delete_marker` and serialises it
# alongside `days`; Scaleway's S3 then rejects the whole request:
#
#   MalformedXML: Cannot have a Expiration.Date or Expiration.Days with Expiration.ExpiredObjectDeleteMarker
#
# It fails at apply time, AFTER the bucket is created — leaving the resource tainted, so the next plan
# proposes destroying and recreating a bucket whose name is globally unique and not re-creatable for a
# while (see `bucket_name`). There is no standalone `scaleway_object_bucket_lifecycle_configuration`
# resource in the provider to route around it (2.82.0 offers only _acl, _policy, _lock_configuration,
# _website_configuration and _server_side_encryption_configuration).
#
# ⇒ `set-lifecycle.py` applies the rule through the S3 API instead, where plain `Days` is accepted. It
# is idempotent and prints what the bucket reports afterwards, so it can be re-run to verify. Keeping
# retention on the BUCKET rather than in the upload script still matters for the original reason: a
# script-side pruner stops pruning at exactly the moment backups stop working, which is when the
# bucket grows fastest and nobody is looking.

# A mirror of the content-plane bucket that deletes only after a grace window.
#
# ⚠⚠ **It never deletes on the source's say-so alone.** The threat is the relay's own reclamation path
# (the content sweep) removing a blob that some device still needs, and a mirror that propagated
# deletes at once would propagate exactly that. `mirror-blobs.py` records in a ledger inside this
# bucket (`_tenshu-mirror/missing-since.json`) when each key was first seen missing from the source,
# and prunes a key only once it has been missing for `blob_mirror_retention_days` — and only when the
# box's deploy.env says `MIRROR_PRUNE=on`. Pruning is OFF by default: turning it on ends the mirror's
# protection for anything deleted longer ago than the window, so it is a deploy-time decision
# (`retention-quota-model.md`), not something an apply does. A circuit breaker refuses to prune when
# the missing set looks like a mass deletion or a different source, and a clock it cannot trust
# refuses too. See docs/how-to/back-up-the-relay-database.md.
#
# ⚠ Still no lifecycle rule, deliberately. Expiry is by object AGE, and these objects are old by
# design — a 30-day rule here would delete the mirror's most valuable contents first. The window has
# to run from when the SOURCE lost the key, which only the script's ledger knows.
resource "scaleway_object_bucket" "blob_mirror" {
  name       = var.blob_mirror_bucket_name
  region     = var.region
  project_id = var.project_id

  tags = {
    project = "tenshu"
    purpose = "relay-content-mirror"
  }
}

# ---------------------------------------------------------------- the second region (nl-ams)
#
# Everything above is in one region, so a regional loss would take every copy at once. These two
# buckets are the copies that survive it. Neither is filled server-side: S3 cannot copy across regions,
# so the box uploads the snapshot twice (backup-relay-db.sh) and streams the blobs (mirror-blobs.py
# --target dr). ⚠ Access is not decided here — the least-privilege keys and the bucket policies live in
# deploy/scaleway-backup-access, which reads these names.

# ⚠⚠ Versioned and Object-Locked (unless dr_snapshot_lock_days = 0). The box's key can PUT here but not
# delete (scaleway-backup-access), and a PUT is not harmless on an unversioned bucket — overwriting a
# snapshot's key destroys it as surely as a delete. With versioning the overwrite only adds a version,
# and the lock keeps every version for the window. So this bucket, not the fr-par one, is the copy a
# compromised relay box cannot destroy. Retention still goes through set-lifecycle.py (the provider bug
# above), which adds a noncurrent-version rule when it finds the bucket versioned.
resource "scaleway_object_bucket" "relay_backups_dr" {
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
    purpose = "relay-db-backups-dr"
  }

  # Same reason as relay_backups: set-lifecycle.py owns the rule.
  lifecycle {
    ignore_changes = [lifecycle_rule]

    precondition {
      condition     = var.dr_region != var.region
      error_message = "dr_region equals region: a second-region copy in the first region is not geo-redundancy."
    }
  }
}

resource "scaleway_object_bucket_lock_configuration" "relay_backups_dr" {
  count    = var.dr_snapshot_lock_days > 0 ? 1 : 0
  provider = scaleway.dr
  bucket   = scaleway_object_bucket.relay_backups_dr.id
  region   = var.dr_region

  rule {
    default_retention {
      mode = var.dr_snapshot_lock_mode
      days = var.dr_snapshot_lock_days
    }
  }
}

# The second-region content mirror. Same pruning semantics as blob_mirror, its own ledger in this
# bucket, objects written ONEZONE_IA by the script (a bucket has no default class on Scaleway).
#
# ⚠ Not versioned, deliberately: pruning (MIRROR_DR_PRUNE, off by default) has to remove bytes for the
# privacy bound to mean anything, and on a versioned bucket a delete only adds a marker. The box's key
# gets no delete here (scaleway-backup-access), so with pruning off this copy cannot be emptied from the
# box — but it can be overwritten (docs/tech-debt.md, "The content mirror never deletes, but it does
# overwrite"). Still no lifecycle rule, for the reason on blob_mirror.
resource "scaleway_object_bucket" "blob_mirror_dr" {
  provider   = scaleway.dr
  name       = var.dr_blob_mirror_bucket_name
  region     = var.dr_region
  project_id = var.project_id

  tags = {
    project = "tenshu"
    purpose = "relay-content-mirror-dr"
  }
}
