# ⚠⚠ Every *_env output is a SECRET. Read with `terraform output -raw <name>` straight into the box
# (the runbook pipes it over ssh), never into a file in the checkout, a ticket or a chat.

output "relay_box_env" {
  description = "Append to the relay box's /opt/tenshu/deploy.env: the backup job's and the mirror job's keys."
  sensitive   = true
  value       = <<-EOT
    BACKUP_S3_ACCESS_KEY=${scaleway_iam_api_key.app["relay_backup"].access_key}
    BACKUP_S3_SECRET_KEY=${scaleway_iam_api_key.app["relay_backup"].secret_key}
    MIRROR_S3_ACCESS_KEY=${scaleway_iam_api_key.app["blob_mirror"].access_key}
    MIRROR_S3_SECRET_KEY=${scaleway_iam_api_key.app["blob_mirror"].secret_key}
  EOT
}

output "relay_content_env" {
  description = "Replaces S3_ACCESS_KEY / S3_SECRET_KEY in the relay box's deploy.env (runbook step 10 — a production rotation)."
  sensitive   = true
  value = var.create_relay_content_key ? join("\n", [
    "S3_ACCESS_KEY=${scaleway_iam_api_key.app["relay_content"].access_key}",
    "S3_SECRET_KEY=${scaleway_iam_api_key.app["relay_content"].secret_key}",
    "",
  ]) : ""
}

output "commerce_box_env" {
  description = "Append to the commerce box's /opt/tenshu/deploy.env: the commerce backup key."
  sensitive   = true
  value       = <<-EOT
    BACKUP_S3_ACCESS_KEY=${scaleway_iam_api_key.app["commerce_backup"].access_key}
    BACKUP_S3_SECRET_KEY=${scaleway_iam_api_key.app["commerce_backup"].secret_key}
  EOT
}

output "application_ids" {
  description = "Not secret. The principals the bucket policies name — compare with the console after apply."
  value       = { for k, a in scaleway_iam_application.app : k => a.id }
}

output "access_keys" {
  description = "Not secret (an access key is an identifier). Use it to tell which key a box is actually using."
  value       = { for k, key in scaleway_iam_api_key.app : k => key.access_key }
}

output "policed_buckets" {
  description = "Which buckets carry a policy right now. The existing fr-par ones only once enforce_on_existing_buckets = true."
  value = {
    for k, v in local.policed : v.bucket => (v.enforce ? "policed" : "IAM only (not yet policed)")
  }
}

output "buckets" {
  description = "Not secret. Every bucket this module knows, with its region — read by probe-keys.py and check-backups.py."
  value = {
    relay_backup       = { bucket = var.relay_backup_bucket, region = var.region }
    relay_backup_dr    = { bucket = var.relay_backup_dr_bucket, region = var.dr_region }
    blob_mirror        = { bucket = var.blob_mirror_bucket, region = var.region }
    blob_mirror_dr     = { bucket = var.blob_mirror_dr_bucket, region = var.dr_region }
    commerce_backup    = { bucket = var.commerce_backup_bucket, region = var.region }
    commerce_backup_dr = { bucket = var.commerce_backup_dr_bucket, region = var.dr_region }
    content            = { bucket = var.content_bucket, region = var.region }
  }
}
