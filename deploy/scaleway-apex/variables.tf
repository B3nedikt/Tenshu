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
  description = "Public hostname the apex serves on, e.g. apex.service.tenshu.app. Drives Caddy's automatic HTTPS."
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

# ---- Apex configuration (identical to the DO module) -----------------------

variable "acme_email" {
  description = "Contact address for Caddy's Let's Encrypt registration."
  type        = string
}

variable "git_repo_url" {
  description = "Repository the box clones to build/run the apex."
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


variable "relay_url" {
  description = "The relay the apex talks to. ⚠ Point this at the relay you actually want — a Scaleway apex against the DO relay is a perfectly valid mixed deploy while you migrate, and probably what you want first."
  type        = string
  default     = ""
}

variable "construction_code" {
  description = "Apex construction code."
  type        = string
  default     = ""
  sensitive   = true
}

variable "apex_image_tag" {
  description = "Prebuilt apex image tag. Empty means build on the box."
  type        = string
  default     = ""
}

variable "signup_disabled" {
  description = <<-EOT
    Pre-launch signup gate (`web/apex/src/lib/signupGate.ts`). Any non-empty value closes the door
    behind "Get started": the marketing site stays public, but realm creation, device pairing and
    try-mode become unreachable. Empty (the default) = signup OPEN.

    ⚠⚠ Like `relay_url` and `construction_code` this is compiled in at BUILD time, so it takes effect
    only on the on-box build path. **A prebuilt image bakes whatever CI built it with, and these
    variables are then inert** — which makes the deploy config actively misleading rather than merely
    incomplete. Measured on 2026-09-14, with both apex boxes running prebuilt images:

    | | `apex.env` says | what the served bundle contains |
    |---|---|---|
    | production (DO) | `VITE_CONSTRUCTION_CODE=191919` | curtain code `171716` |
    | staged (Scaleway) | `VITE_CONSTRUCTION_CODE=` (empty) | curtain code `191919` |

    Production's own file names a code its image does not carry. ⇒ Verify a cutover against the
    **bundle the site serves**, never against this file: `docs/how-to/move-apex-to-scaleway.md`.
  EOT
  type        = string
  default     = ""
}

variable "swapfile_size_gb" {
  description = "Swapfile size on the ROOT disk, in GB (0 = none). Null (the default) means 1 when apex_image_tag is set (prebuilt image) and 4 when it is empty (on-box build, which is RAM-hungry). A fixed 4G swapfile filled commerce's 8.9G root disk in October 2026 (docs/how-to/box-health.md)."
  type        = number
  default     = null
}
