# Least-privilege keys for the backup jobs, and the bucket policies that make them least-privilege.
#
# ## Why this exists
#
# Until this module every backup job ran on ONE project-wide key pair — the admin key that also runs
# Terraform and serves the relay's content plane. A compromised relay box or commerce box could delete
# every snapshot, every dump and both mirrors. docs/tech-debt.md, "Two backup buckets, one key".
#
# ## What Scaleway can express (and what it cannot)
#
# ⚠⚠ Read this before trusting any line below; it is the whole design.
#
# 1. IAM permission sets scope to a PROJECT, never a bucket. ObjectStorageObjectsRead / Write / Delete
#    are separate sets, so "can write, cannot delete" IS expressible — project-wide.
# 2. A bucket policy is evaluated AFTER IAM and can only NARROW it: a principal needs the IAM permission
#    AND, if the bucket has a policy, an Allow in it. Once a bucket has a policy, a principal it does not
#    name has no access at all (the Organization owner excepted).
# 3. Under the policy version Scaleway uses (2023-04-17) Deny statements have no effect — everything not
#    allowed is already denied — so this module writes only Allow statements.
# 4. Nothing can tell an overwrite from a new write: s3:PutObject is both. "Write but never destroy" is
#    therefore only achievable with versioning + Object Lock, which is why the nl-ams snapshot buckets
#    are created that way (deploy/scaleway-*-backups). The fr-par buckets are unversioned: the box keys
#    cannot DELETE there, but can overwrite.
# 5. A bucket with no policy is governed by IAM alone, so every key below reaches it with its full
#    project-wide sets. That is the content-plane bucket today; `enforce_on_content_bucket` closes it.
#
# ⚠ (2)/(3) come from Scaleway's documentation as summarised by search on 2026-10-23; the pages
# themselves could not be fetched from the sandbox that wrote this. A wrong reading fails SAFE in one
# direction only: if a policy did NOT narrow IAM, the relay and commerce keys would still be unable to
# delete (no ObjectsDelete set), but the mirror key could delete outside the fr-par mirror. Verify with
# the runbook's negative checks before relying on it.
#
# ## The keys
#
# | key | IAM (project-wide) | narrowed by bucket policy to |
# |---|---|---|
# | relay-backup | BucketsRead, ObjectsRead, ObjectsWrite | Put+Get on the relay snapshot buckets (fr-par, nl-ams); List on fr-par only (the size floor) |
# | commerce-backup | BucketsRead, ObjectsRead, ObjectsWrite | Put+Get on the commerce dump buckets (fr-par, nl-ams); List on fr-par only (the size floor) |
# | blob-mirror | BucketsRead, ObjectsRead, ObjectsWrite, ObjectsDelete | List/Get/Put/Delete on the fr-par mirror; List/Get/Put (NO delete) on the nl-ams mirror; List/Get on the content bucket once it is policed |
# | relay-content | BucketsRead, ObjectsRead, ObjectsWrite, ObjectsDelete | the content bucket only, once it is policed; named on no backup bucket |
#
# ⚠ The mirror key needs Delete because pruning deletes from the fr-par mirror (MIRROR_PRUNE). It is
# deliberately NOT allowed to delete in nl-ams, so with pruning off there one mirror copy cannot be
# emptied from the box; pruning nl-ams is an admin run from a workstation (the how-to's open decisions).

locals {
  # Every bucket a box writes to, with who may do what. `enforce` gates the existing fr-par buckets.
  policed = {
    relay_backup = {
      bucket  = var.relay_backup_bucket
      dr      = false
      enforce = var.enforce_on_existing_buckets
      # ⚠ ListBucket is load-bearing: the nightly script's size floor lists relay-db/ to find the previous
      # upload, and without it every night fails "cannot list" (docs/how-to/back-up-the-relay-database.md).
      grants = [{ app = "relay_backup", actions = ["s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload"], bucket_level = ["s3:ListBucket"] }]
    }
    relay_backup_dr = {
      bucket  = var.relay_backup_dr_bucket
      dr      = true
      enforce = true
      grants  = [{ app = "relay_backup", actions = ["s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload"], bucket_level = [] }]
    }
    blob_mirror = {
      bucket  = var.blob_mirror_bucket
      dr      = false
      enforce = var.enforce_on_existing_buckets
      grants  = [{ app = "blob_mirror", actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"], bucket_level = ["s3:ListBucket"] }]
    }
    blob_mirror_dr = {
      bucket  = var.blob_mirror_dr_bucket
      dr      = true
      enforce = true
      # ⚠ No s3:DeleteObject: the one mirror copy the box cannot empty.
      grants = [{ app = "blob_mirror", actions = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"], bucket_level = ["s3:ListBucket"] }]
    }
    commerce_backup = {
      bucket  = var.commerce_backup_bucket
      dr      = false
      enforce = var.enforce_on_existing_buckets
      # ⚠ ListBucket: the size floor lists commerce-db/ (see relay_backup above).
      grants = [{ app = "commerce_backup", actions = ["s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload"], bucket_level = ["s3:ListBucket"] }]
    }
    commerce_backup_dr = {
      bucket  = var.commerce_backup_dr_bucket
      dr      = true
      enforce = true
      grants  = [{ app = "commerce_backup", actions = ["s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload"], bucket_level = [] }]
    }
    content = {
      bucket  = var.content_bucket
      dr      = false
      enforce = var.enforce_on_content_bucket
      grants = concat(
        [{ app = "blob_mirror", actions = ["s3:GetObject"], bucket_level = ["s3:ListBucket"] }],
        var.create_relay_content_key ? [{
          app          = "relay_content"
          actions      = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"]
          bucket_level = ["s3:ListBucket"]
        }] : [],
      )
    }
  }

  apps = merge(
    {
      relay_backup = {
        description = "Relay box: nightly relay.db snapshots. Write, never delete."
        # BucketsRead for the size floor's ListBucket on the primary bucket, as blob_mirror has it.
        sets = ["ObjectStorageBucketsRead", "ObjectStorageObjectsRead", "ObjectStorageObjectsWrite"]
      }
      commerce_backup = {
        description = "Commerce box: nightly pg_dump. Write, never delete."
        sets        = ["ObjectStorageBucketsRead", "ObjectStorageObjectsRead", "ObjectStorageObjectsWrite"]
      }
      blob_mirror = {
        description = "Relay box: weekly content mirror (fr-par prune needs delete; nl-ams policy withholds it)."
        sets        = ["ObjectStorageBucketsRead", "ObjectStorageObjectsRead", "ObjectStorageObjectsWrite", "ObjectStorageObjectsDelete"]
      }
    },
    var.create_relay_content_key ? {
      relay_content = {
        description = "Relay box: the content plane (S3_*). The sweep deletes, so it needs delete."
        sets        = ["ObjectStorageBucketsRead", "ObjectStorageObjectsRead", "ObjectStorageObjectsWrite", "ObjectStorageObjectsDelete"]
      }
    } : {},
  )

  policy_docs = {
    for name, b in local.policed : name => jsonencode({
      Version = "2023-04-17"
      Id      = "tenshu-${name}"
      Statement = concat(
        [{
          Sid       = "AdminsKeepFullAccess"
          Effect    = "Allow"
          Principal = { SCW = var.admin_principals }
          Action    = ["s3:*"]
          Resource  = [b.bucket, "${b.bucket}/*"]
        }],
        [for g in b.grants : {
          Sid       = "${replace(g.app, "_", "")}Objects"
          Effect    = "Allow"
          Principal = { SCW = "application_id:${scaleway_iam_application.app[g.app].id}" }
          Action    = g.actions
          Resource  = ["${b.bucket}/*"]
        }],
        [for g in b.grants : {
          Sid       = "${replace(g.app, "_", "")}Bucket"
          Effect    = "Allow"
          Principal = { SCW = "application_id:${scaleway_iam_application.app[g.app].id}" }
          Action    = g.bucket_level
          Resource  = [b.bucket]
        } if length(g.bucket_level) > 0],
      )
    })
  }
}

resource "scaleway_iam_application" "app" {
  for_each    = local.apps
  name        = "tenshu-${replace(each.key, "_", "-")}"
  description = each.value.description
  tags        = ["tenshu", "backups"]
}

resource "scaleway_iam_policy" "app" {
  for_each       = local.apps
  name           = "tenshu-${replace(each.key, "_", "-")}"
  description    = each.value.description
  application_id = scaleway_iam_application.app[each.key].id

  rule {
    project_ids          = [var.project_id]
    permission_set_names = each.value.sets
  }
}

# ⚠ The secret is in this module's STATE. Treat the state file like the keys themselves.
resource "scaleway_iam_api_key" "app" {
  for_each           = local.apps
  application_id     = scaleway_iam_application.app[each.key].id
  description        = "tenshu ${each.key} (deploy/scaleway-backup-access)"
  default_project_id = var.project_id
  expires_at         = var.key_expires_at
}

resource "scaleway_object_bucket_policy" "primary" {
  for_each = { for k, v in local.policed : k => v if v.enforce && !v.dr }
  bucket   = each.value.bucket
  region   = var.region
  policy   = local.policy_docs[each.key]

  lifecycle {
    precondition {
      condition     = each.key != "content" || var.create_relay_content_key
      error_message = "enforce_on_content_bucket without create_relay_content_key would cut the relay off its own storage."
    }
  }
}

resource "scaleway_object_bucket_policy" "dr" {
  for_each = { for k, v in local.policed : k => v if v.enforce && v.dr }
  provider = scaleway.dr
  bucket   = each.value.bucket
  region   = var.dr_region
  policy   = local.policy_docs[each.key]
}
