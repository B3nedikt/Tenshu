variable "api_token" {
  description = "Cloudflare API token. Prefer the CLOUDFLARE_API_TOKEN env var and leave this null. Minimum permissions: Zone → Zone → Edit and Zone → DNS → Edit, on All zones from the account (the zone does not exist yet, so it cannot be scoped to it until after the first apply). See README."
  type        = string
  sensitive   = true
  default     = null
}

variable "account_id" {
  description = "Cloudflare account id the zone is created in (dashboard → Account home → ⋯ → Copy account ID). ⚠ Must be the SAME account as the R2 bucket — an R2 custom domain can only attach to a zone in its own account."
  type        = string
}

variable "zone" {
  description = "The zone. Must match the `zone` field of the inventory file."
  type        = string
  default     = "tenshu.app"
}

variable "records_file" {
  description = "The provider-neutral record inventory, rendered by scripts/dns-export.sh. ⚠ Re-export it immediately before an apply that precedes the nameserver switch — a copy made earlier carries yesterday's IPs."
  type        = string
  default     = "../dns/records.json"
}

variable "tiles_cname_target" {
  description = <<-EOT
    Target of a DNS-only tiles.service CNAME, for a tiles host that is served through a CNAME.
    Empty since the tiles swap on 2026-09-14: the hostname belongs to the R2 custom domain in
    deploy/cloudflare-tiles, which creates its own record, and DigitalOcean's CDN was retired the
    next day, so there is no CNAME target left.

    ⚠⚠ Leave it "" while the R2 custom domain exists. Setting it would give the name two owners, and
    this module's apply would collide with the custom domain's read-only record.
  EOT
  type        = string
  default     = ""
}
