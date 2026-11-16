output "ssh_key_name" {
  description = "Pass to ovh-relay / ovh-commerce / ovh-apex as ssh_key_name."
  value       = ovh_cloud_ssh_key.deploy.name
}

output "buckets" {
  value = local.buckets
}

output "s3_endpoint" {
  value = local.s3_endpoint
}

# ⚠ The two outputs below are the deploy.env lines that deploy/ovh-migrate writes over the Scaleway
# values it copied. They hold secret keys: read them with `terraform output -raw`, never print them
# into a log or a ticket.
output "relay_env_overrides" {
  description = "deploy.env lines for the relay box: content plane, mirror and backups on OVH."
  sensitive   = true
  value       = <<-EOT
    S3_ENDPOINT=${local.s3_endpoint}
    S3_BUCKET=${local.buckets.content}
    S3_REGION=${local.s3_region}
    S3_ACCESS_KEY=${ovh_cloud_project_user_s3_credential.s3["relay_content"].access_key_id}
    S3_SECRET_KEY=${ovh_cloud_project_user_s3_credential.s3["relay_content"].secret_access_key}
    BLOB_MIRROR_BUCKET=${local.buckets.mirror}
    MIRROR_S3_ACCESS_KEY=${ovh_cloud_project_user_s3_credential.s3["blob_mirror"].access_key_id}
    MIRROR_S3_SECRET_KEY=${ovh_cloud_project_user_s3_credential.s3["blob_mirror"].secret_access_key}
    MIRROR_STORAGE_CLASS=STANDARD
    BACKUP_S3_ENDPOINT=${local.s3_endpoint}
    BACKUP_S3_BUCKET=${local.buckets.relay_backup}
    BACKUP_S3_REGION=${local.s3_region}
    BACKUP_S3_ACCESS_KEY=${ovh_cloud_project_user_s3_credential.s3["relay_backup"].access_key_id}
    BACKUP_S3_SECRET_KEY=${ovh_cloud_project_user_s3_credential.s3["relay_backup"].secret_access_key}
  EOT
}

output "commerce_env_overrides" {
  description = "deploy.env lines for the commerce box: backups on OVH. ⚠ SMTP stays on Scaleway TEM."
  sensitive   = true
  value       = <<-EOT
    BACKUP_S3_ENDPOINT=${local.s3_endpoint}
    BACKUP_S3_BUCKET=${local.buckets.commerce_backup}
    BACKUP_S3_REGION=${local.s3_region}
    BACKUP_S3_ACCESS_KEY=${ovh_cloud_project_user_s3_credential.s3["commerce_backup"].access_key_id}
    BACKUP_S3_SECRET_KEY=${ovh_cloud_project_user_s3_credential.s3["commerce_backup"].secret_access_key}
  EOT
}
