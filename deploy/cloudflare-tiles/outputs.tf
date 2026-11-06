output "bucket_name" {
  description = "The bucket name. Export as SPACES_BUCKET for scripts/tiles-publish.sh."
  value       = cloudflare_r2_bucket.tiles.name
}

output "s3_endpoint" {
  description = "The S3-compatible endpoint, which tiles-publish.sh takes as SPACES_ENDPOINT. ⚠ Required: the script has no default endpoint since DigitalOcean was retired (2026-09-15), and refuses to run without one."
  value       = "https://${var.account_id}.r2.cloudflarestorage.com"
}

output "dev_url" {
  description = "The r2.dev base URL while enable_dev_url = true, else null. ⚠ For the pre-DNS range test only — rate-limited, non-production."
  value       = var.enable_dev_url ? "https://${cloudflare_r2_managed_domain.dev.domain}" : null
}

output "custom_domain_status" {
  description = "Ownership + SSL status per attached hostname. ⚠⚠ Read `ssl` before trusting a hostname: `active` is the only value under which a client's TLS handshake can succeed. Refresh with `terraform apply -refresh-only` — the value is a snapshot."
  value       = { for k, d in cloudflare_r2_custom_domain.tiles : d.domain => d.status }
}

output "publish_env_hint" {
  description = "The R2 publish, as ONE invocation. ⚠ Deliberately not `export`s, so R2 credentials never linger in a shell that later runs something else."
  value       = <<-EOT
    env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN \
      SPACES_BUCKET=${cloudflare_r2_bucket.tiles.name} \
      SPACES_ENDPOINT=https://${var.account_id}.r2.cloudflarestorage.com \
      AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
      ./scripts/tiles-publish.sh
    # ⚠ SPACES_ENDPOINT is REQUIRED (no default since DigitalOcean was retired). Region is forced to "auto".
    # R2_* is the R2 S3 key pair (R2 → Manage API tokens), NOT the Terraform token.
  EOT
}
