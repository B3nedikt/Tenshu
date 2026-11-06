output "bucket_name" {
  description = "The public bucket. ASSETS_BUCKET for scripts/store-publish.sh (its default)."
  value       = cloudflare_r2_bucket.assets.name
}

output "s3_endpoint" {
  description = "The S3-compatible endpoint, ASSETS_ENDPOINT for scripts/store-publish.sh. ⚠ The script reads this output when the variable is unset, so it works from the checkout that holds this module's state."
  value       = "https://${var.account_id}.r2.cloudflarestorage.com"
}

output "public_base_url" {
  description = "The manifests' `base`: an object's URL is <this><sha256>.<ext>."
  value       = "https://${var.domain}/sha256/"
}

output "custom_domain_status" {
  description = "Ownership + SSL status of the custom domain. ⚠⚠ Read `ssl` before publishing: `active` is the only value under which a browser's TLS handshake can succeed. Refresh with `terraform apply -refresh-only` — the value is a snapshot."
  value       = { (cloudflare_r2_custom_domain.assets.domain) = cloudflare_r2_custom_domain.assets.status }
}

output "fixtures_bucket_name" {
  description = "The PRIVATE fixtures bucket, or null while enable_fixtures_bucket = false."
  value       = var.enable_fixtures_bucket ? cloudflare_r2_bucket.fixtures[0].name : null
}
