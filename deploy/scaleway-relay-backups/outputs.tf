output "backup_settings_for_deploy_env" {
  description = <<-EOT
    Paste into the relay box's /opt/tenshu/deploy.env, beside the S3_* content-plane settings. The
    credentials are NOT here: they are the relay-backup key from deploy/scaleway-backup-access
    (`terraform output -raw relay_box_env` there), which can write snapshots and cannot delete them.
  EOT
  value       = <<-EOT
    BACKUP_S3_ENDPOINT=https://s3.${var.region}.scw.cloud
    BACKUP_S3_BUCKET=${scaleway_object_bucket.relay_backups.name}
    BACKUP_S3_REGION=${var.region}
    BACKUP_DR_S3_ENDPOINT=https://s3.${var.dr_region}.scw.cloud
    BACKUP_DR_S3_BUCKET=${scaleway_object_bucket.relay_backups_dr.name}
    BACKUP_DR_S3_REGION=${var.dr_region}
  EOT
}

output "bucket_name" {
  value = scaleway_object_bucket.relay_backups.name
}

output "dr_bucket_name" {
  value = scaleway_object_bucket.relay_backups_dr.name
}

output "blob_mirror_bucket" {
  value = scaleway_object_bucket.blob_mirror.name
}

output "dr_blob_mirror_bucket" {
  value = scaleway_object_bucket.blob_mirror_dr.name
}

output "blob_mirror_settings_for_deploy_env" {
  description = <<-EOT
    Paste into the relay box's /opt/tenshu/deploy.env for tenshu-blob-mirror.service and
    tenshu-blob-mirror-dr.service (mirror-blobs.py). Credentials: MIRROR_S3_* from
    deploy/scaleway-backup-access. ⚠ MIRROR_DR_PRUNE stays off: the box's mirror key cannot delete in the
    second region (see the how-to's "Keys"), so turning it on there only produces failed deletes.
  EOT
  value       = <<-EOT
    BLOB_MIRROR_BUCKET=${scaleway_object_bucket.blob_mirror.name}
    BLOB_MIRROR_DR_BUCKET=${scaleway_object_bucket.blob_mirror_dr.name}
    BLOB_MIRROR_DR_REGION=${var.dr_region}
    MIRROR_STORAGE_CLASS=ONEZONE_IA
    MIRROR_PRUNE=${var.blob_mirror_prune ? "on" : "off"}
    MIRROR_DR_PRUNE=off
    MIRROR_RETENTION_DAYS=${var.blob_mirror_retention_days}
  EOT
}
