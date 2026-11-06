output "bucket_name" {
  description = "The bucket name. SPACES_BUCKET for scripts/models-publish.sh."
  value       = cloudflare_r2_bucket.models.name
}

output "s3_endpoint" {
  description = "The S3-compatible endpoint, SPACES_ENDPOINT for scripts/models-publish.sh. ⚠ Required: the script has no default endpoint."
  value       = "https://${var.account_id}.r2.cloudflarestorage.com"
}

output "public_base_url" {
  description = "HTTPS base URL clients read — the value of named(\"modelCatalogUrl\") in control-shared-proxy's ProxyModule. The client GETs <this>/catalog.json, <this>/catalog.json.sig and each artifact path."
  value       = "https://${var.domain}"
}

output "catalog_url" {
  description = "Fully-qualified URL of the signed catalog. Verify a publish with: curl -fsS <this> | head"
  value       = "https://${var.domain}/catalog.json"
}

output "custom_domain_status" {
  description = "Ownership + SSL status of the custom domain. ⚠⚠ Read `ssl` before publishing: `active` is the only value under which a client's TLS handshake can succeed. Refresh with `terraform apply -refresh-only` — the value is a snapshot."
  value       = { (cloudflare_r2_custom_domain.models.domain) = cloudflare_r2_custom_domain.models.status }
}

output "publish_env_hint" {
  description = "The R2 publish, as ONE invocation. ⚠ Deliberately not `export`s, so R2 credentials never linger in a shell that later runs something else."
  value       = <<-EOT
    env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN \
      SPACES_BUCKET=${cloudflare_r2_bucket.models.name} \
      SPACES_ENDPOINT=https://${var.account_id}.r2.cloudflarestorage.com \
      AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
      ./scripts/models-publish.sh
    # ⚠ SPACES_ENDPOINT is REQUIRED. Region is forced to "auto" and no ACL is sent (R2 implements neither).
    # R2_* is an R2 S3 key pair that can write THIS bucket (R2 → Manage API tokens), NOT the Terraform token.
    Then verify from outside:
      curl -fsS https://${var.domain}/catalog.json | head -c 400
      curl -fsSI -r 0-1023 https://${var.domain}/<an artifact path from catalog.json>
        # ⚠⚠ Expect "206". ModelDownloader RESUMES with Range; a 200 means every interrupted
        # download of a ~380 MB artifact restarts from zero.
  EOT
}
