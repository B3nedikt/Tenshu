output "name_servers" {
  description = "The two nameservers Cloudflare assigned. Query one directly with scripts/dns-diff.sh BEFORE setting them at the registrar."
  value       = cloudflare_zone.this.name_servers
}

output "zone_id" {
  description = "Zone id — the R2 custom domain in deploy/cloudflare-tiles attaches to this zone."
  value       = cloudflare_zone.this.id
}

output "status" {
  description = "pending until Cloudflare sees its nameservers at the registrar, then active. ⚠ A Free zone left pending for 28 days is deleted."
  value       = cloudflare_zone.this.status
}
