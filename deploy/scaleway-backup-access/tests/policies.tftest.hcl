# Offline checks of what this module would put on each bucket — no credentials, no network.
#
#   terraform init -backend=false && terraform test      (⚠ needs Terraform >= 1.7 for mock_provider)
#
# The mocks stand in for Scaleway, so these prove the RENDERED policies and the staging switches, not
# how Scaleway evaluates them; the runbook's negative checks are the live half.

mock_provider "scaleway" {
  mock_resource "scaleway_iam_application" {
    defaults = { id = "11111111-1111-1111-1111-111111111111" }
  }
}

mock_provider "scaleway" {
  alias = "dr"
}

variables {
  access_key       = "SCWXXXXXXXXXXXXXXXXX"
  secret_key       = "00000000-0000-0000-0000-000000000000"
  organization_id  = "00000000-0000-0000-0000-000000000000"
  project_id       = "00000000-0000-0000-0000-000000000000"
  admin_principals = ["user_id:22222222-2222-2222-2222-222222222222"]
}

run "defaults_police_only_the_new_nl_ams_buckets" {
  command = apply

  assert {
    condition     = toset(keys(scaleway_object_bucket_policy.dr)) == toset(["relay_backup_dr", "blob_mirror_dr", "commerce_backup_dr"])
    error_message = "the three nl-ams buckets must be policed from the first apply"
  }
  assert {
    condition     = length(scaleway_object_bucket_policy.primary) == 0
    error_message = "existing fr-par buckets must stay unpoliced until enforce_on_existing_buckets"
  }
}

run "no_box_key_may_delete_a_snapshot_or_the_nl_ams_mirror" {
  command = apply

  variables {
    enforce_on_existing_buckets = true
  }

  assert {
    condition = alltrue([
      for k in ["relay_backup", "relay_backup_dr", "commerce_backup", "commerce_backup_dr", "blob_mirror_dr"] :
      alltrue([for s in jsondecode(local.policy_docs[k]).Statement :
      !contains(s.Action, "s3:DeleteObject") if s.Sid != "AdminsKeepFullAccess"])
    ])
    error_message = "a box principal was granted s3:DeleteObject on a snapshot bucket or the nl-ams mirror"
  }
  assert {
    condition = anytrue([for s in jsondecode(local.policy_docs["blob_mirror"]).Statement :
    contains(s.Action, "s3:DeleteObject") if s.Sid == "blobmirrorObjects"])
    error_message = "the fr-par mirror must allow the mirror key to delete (pruning)"
  }
  assert {
    condition = alltrue([for k in ["relay_backup", "relay_backup_dr", "blob_mirror", "blob_mirror_dr", "commerce_backup", "commerce_backup_dr"] :
    jsonencode(jsondecode(local.policy_docs[k]).Statement[0].Principal.SCW) == jsonencode(var.admin_principals)])
    error_message = "every policy must keep the admins in (lock-out guard)"
  }
  assert {
    condition     = length(scaleway_object_bucket_policy.primary) == 3
    error_message = "enforce_on_existing_buckets must police the three fr-par buckets, and not the content bucket"
  }
}

run "backup_keys_can_list_their_primary_bucket_only" {
  command = apply

  variables {
    enforce_on_existing_buckets = true
  }

  # The nightly size floor lists the primary bucket (backup-relay-db.sh / backup-commerce-db.sh): without
  # ListBucket there, step 11 of the runbook turns every night into "cannot list". The second region is
  # never listed, so it gets no ListBucket.
  assert {
    condition = alltrue([for k in ["relay_backup", "commerce_backup"] :
      anytrue([for s in jsondecode(local.policy_docs[k]).Statement :
    s.Action == ["s3:ListBucket"] && s.Resource == [local.policed[k].bucket] if s.Sid != "AdminsKeepFullAccess"])])
    error_message = "a backup key lost s3:ListBucket on its primary bucket — the nightly size floor needs it"
  }
  assert {
    condition = alltrue([for k in ["relay_backup_dr", "commerce_backup_dr"] :
      alltrue([for s in jsondecode(local.policy_docs[k]).Statement :
    !contains(s.Action, "s3:ListBucket") if s.Sid != "AdminsKeepFullAccess"])])
    error_message = "a backup key was granted s3:ListBucket on a second-region snapshot bucket it never lists"
  }
  assert {
    condition = alltrue([for k in ["relay_backup", "commerce_backup"] :
    contains(local.apps[k].sets, "ObjectStorageBucketsRead")])
    error_message = "the backup keys need ObjectStorageBucketsRead for the size floor's listing"
  }
}

run "keys_without_an_iam_delete_set" {
  command = plan

  assert {
    condition = alltrue([for k in ["relay_backup", "commerce_backup"] :
    !contains(local.apps[k].sets, "ObjectStorageObjectsDelete")])
    error_message = "the backup writers must hold no delete permission set anywhere in the project"
  }
}

run "content_bucket_needs_the_relay_content_key" {
  command = plan

  variables {
    enforce_on_content_bucket = true
    create_relay_content_key  = false
  }

  expect_failures = [scaleway_object_bucket_policy.primary]
}

run "admin_principals_required" {
  command = plan

  variables {
    admin_principals = []
  }

  expect_failures = [var.admin_principals]
}
