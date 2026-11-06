output "backup_settings_for_deploy_env" {
  description = <<-EOT
    Paste into the commerce box's /opt/tenshu/deploy.env. The credentials are NOT here: they are the
    commerce-backup key from deploy/scaleway-backup-access (`terraform output -raw commerce_box_env`).
  EOT
  value       = <<-EOT
    BACKUP_S3_ENDPOINT=https://s3.${var.region}.scw.cloud
    BACKUP_S3_BUCKET=${scaleway_object_bucket.commerce_backups.name}
    BACKUP_S3_REGION=${var.region}
    BACKUP_DR_S3_ENDPOINT=https://s3.${var.dr_region}.scw.cloud
    BACKUP_DR_S3_BUCKET=${scaleway_object_bucket.commerce_backups_dr.name}
    BACKUP_DR_S3_REGION=${var.dr_region}
  EOT
}

output "bucket_name" {
  value = scaleway_object_bucket.commerce_backups.name
}

output "dr_bucket_name" {
  value = scaleway_object_bucket.commerce_backups_dr.name
}
