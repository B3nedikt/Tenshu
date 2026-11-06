output "bucket_name" {
  description = "The Spaces bucket name. Export as SPACES_BUCKET for scripts/tiles-publish.sh."
  value       = digitalocean_spaces_bucket.tiles.name
}

output "bucket_region" {
  description = "The Spaces region slug. Export as SPACES_REGION for scripts/tiles-publish.sh."
  value       = var.region
}

output "spaces_endpoint" {
  description = "The regional S3-compatible Spaces endpoint the publish script targets (aws --endpoint-url / s3cmd host)."
  value       = "https://${var.region}.digitaloceanspaces.com"
}

output "bucket_origin_host" {
  description = "The bucket's origin hostname (what the CDN pulls from). Not the public host — clients use public_base_url."
  value       = digitalocean_spaces_bucket.tiles.bucket_domain_name
}

output "cdn_endpoint" {
  description = "The DO-assigned CDN endpoint hostname. Point var.domain's CNAME here when manage_dns=false, or hit it directly when enable_custom_domain=false."
  value       = digitalocean_cdn.tiles.endpoint
}

output "public_base_url" {
  description = "HTTPS base URL the proxy's HttpRegionCatalogSource + the client download path are pointed at (maps-region-delivery.md §3 D1)."
  value       = local.public_base_url
}

output "catalog_url" {
  description = "Fully-qualified URL of the signed region catalog (D3). Verify Phase 1 with: curl -fsS <this>."
  value       = "${local.public_base_url}/catalog.json"
}

output "publish_env_hint" {
  description = "Copy-paste env for the publish script once the bucket is live."
  value       = <<-EOT
    Publish a build with scripts/tiles-publish.sh after these are exported:
      export SPACES_BUCKET=${digitalocean_spaces_bucket.tiles.name}
      export SPACES_REGION=${var.region}
      export AWS_ACCESS_KEY_ID=<your spaces access id>
      export AWS_SECRET_ACCESS_KEY=<your spaces secret key>
    Then verify the public path:
      curl -fsS ${local.public_base_url}/catalog.json | head
      curl -fsSI -r 0-1023 ${local.public_base_url}/regions/<YYYY-MM>/<id>.pmtiles   # range check
  EOT
}
