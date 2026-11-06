output "bucket_name" {
  description = "The release bucket. RELEASES_BUCKET for scripts/android-direct-publish.sh (its default)."
  value       = cloudflare_r2_bucket.releases.name
}

output "s3_endpoint" {
  description = "The S3-compatible endpoint, RELEASES_ENDPOINT for scripts/android-direct-publish.sh. ⚠ The script reads this output when the variable is unset, so it works from the checkout that holds this module's state."
  value       = "https://${var.account_id}.r2.cloudflarestorage.com"
}

output "public_base_url" {
  description = "store/android-release.json's `base`: an APK's URL is <this><sha256>.apk."
  value       = "https://${var.domain}/sha256/"
}

output "custom_domain_status" {
  description = "Ownership + SSL status of the custom domain. ⚠⚠ Read `ssl` before publishing: `active` is the only value under which a browser's TLS handshake can succeed. Refresh with `terraform apply -refresh-only` — the value is a snapshot."
  value       = { (cloudflare_r2_custom_domain.releases.domain) = cloudflare_r2_custom_domain.releases.status }
}
