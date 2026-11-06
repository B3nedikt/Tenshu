# ---- Credentials -----------------------------------------------------------

variable "access_key" {
  description = "Scaleway API access key (SCWxxxxxxxxxxxxxxxxxxxx). Prefer the SCW_ACCESS_KEY env var over committing it."
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "Scaleway API secret key (a UUID). May instead be supplied via SCW_SECRET_KEY."
  type        = string
  sensitive   = true
}

variable "project_id" {
  description = "Scaleway Project ID (a UUID). ⚠ Scaleway scopes every resource to a Project — there is no DigitalOcean equivalent, and omitting it puts the relay somewhere you did not intend."
  type        = string
}

# ---- Placement -------------------------------------------------------------

variable "zone" {
  description = "Availability zone for the instance and its volume, e.g. fr-par-1, fr-par-2, nl-ams-1. ⚠ The instance and its block volume MUST be in the same zone — a volume in fr-par-2 cannot attach to a server in fr-par-1."
  type        = string
  default     = "fr-par-1"
}

variable "region" {
  description = "Region for regional resources (DNS). fr-par, nl-ams or pl-waw. Should contain var.zone."
  type        = string
  default     = "fr-par"
}

# ---- The box ---------------------------------------------------------------

variable "instance_type" {
  description = <<-EOT
    Scaleway commercial instance type. Rough DigitalOcean equivalents:
      DEV1-S   (2 vCPU / 2 GB)  ≈ s-1vcpu-2gb
      DEV1-M   (3 vCPU / 4 GB)  ≈ s-2vcpu-4gb
      PLAY2-NANO / PLAY2-MICRO  cheaper shared-vCPU line
    ⚠ Availability is per-zone; a type that exists in fr-par-1 may not in nl-ams-1, and the apply
    fails at create time rather than at plan.
  EOT
  type        = string
  default     = "DEV1-M"
}

variable "instance_image" {
  description = "Base image label, e.g. ubuntu_jammy or ubuntu_noble. ⚠ The cloud-init assumes a Debian/Ubuntu box with apt."
  type        = string
  default     = "ubuntu_jammy"
}

variable "data_volume_size_gb" {
  description = "Size of the durable block volume holding the SQLite DB + blobs. Survives an instance rebuild."
  type        = number
  default     = 20
}

variable "data_volume_iops" {
  description = "Provisioned IOPS for the block volume. ⚠ REQUIRED by scaleway_block_volume — unlike DigitalOcean's volumes, which have no such knob. 5000 is the standard tier."
  type        = number
  default     = 5000
}

# ⚠⚠ There is deliberately NO ssh_key variable here, and its absence is the documentation.
#
# `scaleway_instance_server` has no SSH-key argument: Scaleway injects **every SSH key registered on
# the PROJECT** at first boot, via the metadata service. So access is granted in the console
# (Project → SSH Keys), not in this file.
#
# ⚠⚠ **At FIRST boot only.** Adding a key to the Project afterwards does not reach a running
# instance — the failure is `Permission denied (publickey)` on a box that is otherwise healthy. Fix
# it by rebuilding (cheap for a stateless service) or through the console's VNC, which needs no SSH.
#
# ⚠ An earlier version of this module carried an `ssh_key_ids` variable that was never referenced by
# anything. It looked like the knob that granted access and was not, which is worse than having no
# knob at all — hence this comment rather than a silent deletion.

variable "ssh_allowed_cidrs" {
  description = "Source CIDRs allowed to reach port 22. Default is open, matching the DO module; narrow it if you can."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# ---- DNS -------------------------------------------------------------------

variable "domain" {
  description = "Public hostname the relay serves on, e.g. relay.service.tenshu.app. Drives Caddy's automatic HTTPS."
  type        = string
}

variable "manage_dns" {
  description = "Create the A record in Scaleway DNS. ⚠⚠ Leave false: it only works if the zone is HOSTED at Scaleway, and tenshu.app moves from DigitalOcean to Cloudflare DNS instead (docs/how-to/move-dns-to-cloudflare.md). Create the record where the zone is served — DigitalOcean until the move, deploy/dns/records.json after it."
  type        = bool
  default     = false
}

variable "dns_zone" {
  description = "The DNS zone var.domain sits under, e.g. tenshu.app. Only used when manage_dns = true."
  type        = string
  default     = ""
}

# ---- Relay configuration (identical to the DO module) ----------------------

variable "acme_email" {
  description = "Contact address for Caddy's Let's Encrypt registration."
  type        = string
}

variable "git_repo_url" {
  description = "Repository the box clones to build/run the relay."
  type        = string
  default     = "https://github.com/B3nedikt/Tenshu.git"
}

variable "git_ref" {
  description = "Pinned ref the box checks out."
  type        = string
  default     = "master"
}

variable "git_token" {
  description = "Token for a private clone. Empty for a public repo."
  type        = string
  default     = ""
  sensitive   = true
}

variable "git_repo_is_private" {
  description = "Whether git_repo_url needs git_token to clone. True refuses a plan with an empty token; set false once the repo is public."
  type        = bool
  default     = true
}

variable "relay_image_tag" {
  description = "Prebuilt relay image tag. Empty means build on the box (RAM-hungry — size the instance accordingly)."
  type        = string
  default     = ""
}

variable "ghcr_user" {
  description = "GHCR username for pulling the prebuilt image."
  type        = string
  default     = ""
}

variable "ghcr_token" {
  description = "GHCR token for pulling the prebuilt image."
  type        = string
  default     = ""
  sensitive   = true
}

variable "cors_allowed_origins" {
  description = "CORS allow-list for the relay."
  type        = string
  default     = ""
}

variable "entitlement_enforce" {
  description = "Whether the relay enforces entitlements."
  type        = string
  default     = "false"
}

variable "entitlement_issuer_pubkey" {
  description = "Public key the relay verifies entitlement tokens with."
  type        = string
  default     = ""
}

variable "entitlement_issuer_key_id" {
  description = "Key id for the entitlement issuer."
  type        = string
  default     = ""
}

variable "genesis_rate_limit_per_hour" {
  description = "Genesis endpoint rate limit."
  type        = string
  default     = ""
}

variable "root_volume_size_gb" {
  description = <<-EOT
    Size of the instance's ROOT volume — where docker image layers extract, NOT where relay data
    lives. ⚠⚠ Scaleway's default for DEV1-S is ~9 GB against DigitalOcean's 24 GB, and 9 GB fills:
    on 2026-09-13 an image pull died with `no space left on device` while the 46 GB data volume sat
    at 1%. Raise it before a migration window rather than during one.
  EOT
  type        = number
  default     = 20
}

variable "swapfile_size_gb" {
  description = "Swapfile size on the ROOT disk, in GB (0 = none). Null (the default) means 1 when relay_image_tag is set (prebuilt image) and 4 when it is empty (on-box build, which is RAM-hungry). A fixed 4G swapfile filled commerce's 8.9G root disk in October 2026 (docs/how-to/box-health.md)."
  type        = number
  default     = null
}

# ⚠⚠ Accepts commerce closure records and PERMANENTLY DELETES a closed account's content after the
# grace window (retention-quota-model.md §3.3). Pairs with commerce's account_closure_relay_url.
variable "account_closure_reclaim" {
  description = "\"true\" enables POST /sync/account/closure and content reclamation; anything else keeps it off."
  type        = string
  default     = "false"
}
