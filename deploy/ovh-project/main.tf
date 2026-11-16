# Project-level pieces of the OVH deployment: the SSH key the boxes boot with, the four buckets, and
# one S3 key per job, each limited to the buckets that job needs.
#
#   bucket                      | written by              | key (deploy.env names)
#   ----------------------------+-------------------------+-------------------------------------------
#   <prefix>-relay-blobs        | relay (content plane)   | relay_content   -> S3_*
#   <prefix>-relay-blobs-mirror | tenshu-blob-mirror      | blob_mirror     -> MIRROR_S3_*  (reads the content bucket)
#   <prefix>-relay-db-backups   | tenshu-relay-backup     | relay_backup    -> BACKUP_S3_*  on the relay box
#   <prefix>-commerce-db-backups| tenshu-commerce-backup  | commerce_backup -> BACKUP_S3_*  on the commerce box
#
# ⚠ Compared with Scaleway (deploy/scaleway-backup-access): OVH scopes an S3 user's policy to named
# buckets directly, so least privilege needs no bucket policies. The content bucket is policed from
# day one, which Scaleway's setup never got to.
#
# ⚠⚠ No bucket is public. The content bucket holds every user's ciphertext; anonymous reads would hand
# an observer per-owner object sizes and access patterns (threat model T-R-09). House Rule #1 is
# unchanged: the relay only ever stores ciphertext.
#
# ⚠ No versioning, deliberately, same as on Scaleway: blobs are content-addressed (`blob-<sha256>`), so
# a re-PUT writes identical bytes, and versioning would double the bill and defeat reclamation.

terraform {
  required_version = ">= 1.7.0"
  required_providers {
    ovh = {
      source  = "ovh/ovh"
      version = "~> 2.24"
    }
  }
}

# Credentials come from the environment (OVH_ENDPOINT, OVH_APPLICATION_KEY, OVH_APPLICATION_SECRET,
# OVH_CONSUMER_KEY); see README.md.
provider "ovh" {}

locals {
  buckets = {
    content         = "${var.bucket_prefix}-relay-blobs"
    mirror          = "${var.bucket_prefix}-relay-blobs-mirror"
    relay_backup    = "${var.bucket_prefix}-relay-db-backups"
    commerce_backup = "${var.bucket_prefix}-commerce-db-backups"
  }

  object_rw = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
  bucket_rw = ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
  # Backups may write and read back, never delete. ⚠ s3:PutObject is also an overwrite, so this is
  # "cannot delete", not "cannot destroy" (same limit as on Scaleway).
  object_put = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"]
  # ListBucket is load-bearing for the backups: the nightly size floor lists the previous upload.
  bucket_ro = ["s3:ListBucket", "s3:GetBucketLocation"]

  arn = { for k, b in local.buckets : k => ["arn:aws:s3:::${b}", "arn:aws:s3:::${b}/*"] }

  keys = {
    relay_content = [
      { sid = "Content", actions = concat(local.object_rw, local.bucket_rw), resources = local.arn.content },
    ]
    blob_mirror = [
      { sid = "ReadSource", actions = ["s3:GetObject", "s3:ListBucket", "s3:GetBucketLocation"], resources = local.arn.content },
      # Delete is needed only for MIRROR_PRUNE=on, which is off unless deploy.env says otherwise.
      { sid = "Mirror", actions = concat(local.object_rw, local.bucket_rw), resources = local.arn.mirror },
    ]
    relay_backup = [
      { sid = "Backups", actions = concat(local.object_put, local.bucket_ro), resources = local.arn.relay_backup },
    ]
    commerce_backup = [
      { sid = "Backups", actions = concat(local.object_put, local.bucket_ro), resources = local.arn.commerce_backup },
    ]
  }

  s3_region   = lower(var.bucket_region)
  s3_endpoint = "https://s3.${local.s3_region}.io.cloud.ovh.net"
}

resource "ovh_cloud_ssh_key" "deploy" {
  service_name = var.service_name
  name         = var.ssh_key_name
  public_key   = var.ssh_public_key
}

resource "ovh_cloud_storage_object_bucket" "this" {
  for_each = local.buckets

  service_name = var.service_name
  region       = upper(var.bucket_region)
  name         = each.value
  encryption   = { algorithm = "AES256" }
  tags         = { project = "tenshu", purpose = each.key }

  # ⚠⚠ The content bucket becomes the only copy of user content once Scaleway is gone, and the
  # provider EMPTIES a bucket before deleting it.
  lifecycle {
    prevent_destroy = true
  }
}

resource "ovh_cloud_project_user" "s3" {
  for_each = local.keys

  service_name = var.service_name
  description  = "tenshu-${replace(each.key, "_", "-")}"
  role_names   = ["objectstore_operator"]
}

resource "ovh_cloud_project_user_s3_credential" "s3" {
  for_each = local.keys

  service_name = var.service_name
  user_id      = ovh_cloud_project_user.s3[each.key].id
}

# ⚠⚠ Without a policy an objectstore_operator user reaches EVERY bucket in the project. The policy is
# what makes each key least-privilege; README.md has the negative checks that prove it.
resource "ovh_cloud_project_user_s3_policy" "s3" {
  for_each = local.keys

  service_name = var.service_name
  user_id      = ovh_cloud_project_user.s3[each.key].id
  policy = jsonencode({
    Statement = [for st in each.value : {
      Sid      = st.sid
      Effect   = "Allow"
      Action   = st.actions
      Resource = st.resources
    }]
  })

  depends_on = [ovh_cloud_storage_object_bucket.this]
}

# Retention on the two database-backup buckets: every object expires after `backup_retention_days`.
# The privacy notice and docs/policy/acceptable-use-and-limits.md promise this 30-day figure, so the
# rule is what keeps them true. ⚠ The content bucket and the blob mirror get NO expiry rule: content
# is durable and is deleted only by reclamation (retention-quota-model.md §2), and the mirror is
# pruned by its own script (MIRROR_PRUNE). An age rule on either deletes user data.
# Unlike Scaleway (deploy/scaleway-*-backups/set-lifecycle.py), OVH's provider expresses this.
resource "ovh_cloud_project_storage_object_bucket_lifecycle_configuration" "backups" {
  for_each = toset(["relay_backup", "commerce_backup"])

  service_name   = var.service_name
  region_name    = upper(var.bucket_region)
  container_name = ovh_cloud_storage_object_bucket.this[each.key].name

  rules = [{
    id         = "expire-daily-snapshots"
    status     = "enabled"
    filter     = { prefix = "" }
    expiration = { days = var.backup_retention_days }
    # A backup upload that died half-way leaves parts that are billed and never listed.
    abort_incomplete_multipart_upload = { days_after_initiation = 7 }
  }]
}
