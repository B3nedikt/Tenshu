# Inputs for the single-droplet apex web-frontend deploy. Everything has a sensible
# default except the secrets/identity (do_token, ssh_keys, domain, acme_email).
# Copy terraform.tfvars.example -> terraform.tfvars and fill those in.
#
# This module is deliberately a sibling of deploy/digitalocean/ (the relay) rather
# than a shared one: apex and the relay are separate hosts with separate DNS,
# separate lifecycles, and — crucially — separate Terraform state, so redeploying
# one never touches the other.

variable "do_token" {
  description = "DigitalOcean API token (write scope). The only infra secret. Prefer the DIGITALOCEAN_TOKEN env var over committing it."
  type        = string
  sensitive   = true
}

variable "ssh_keys" {
  description = <<-EOT
    SSH key fingerprints or names already uploaded to your DO account. These keys
    are added to the droplet's root user - without one you cannot SSH in. List them
    in the DO console under Settings > Security, or `doctl compute ssh-key list`.
  EOT
  type        = list(string)
}

variable "domain" {
  description = "Fully-qualified hostname the apex frontend is served on, e.g. tenshu.app. Used for the DNS record (when manage_dns=true) and for Caddy's automatic TLS certificate."
  type        = string
}

variable "acme_email" {
  description = "Contact email for the Let's Encrypt account Caddy registers (expiry notices). Required for automatic HTTPS."
  type        = string
}

# ---- DNS -------------------------------------------------------------------

variable "manage_dns" {
  description = <<-EOT
    When true, Terraform creates the DO-managed domain + A record pointing the
    `domain` at the apex droplet's reserved IP. Set false if your DNS lives
    elsewhere (Cloudflare, registrar, etc.) - then create an A record for `domain`
    pointing at the `apex_ip` output yourself.
  EOT
  type        = bool
  default     = true
}

variable "dns_zone" {
  description = "The DO-managed apex zone (e.g. tenshu.app) that `domain` is a record under. Only used when manage_dns=true. Empty = derive the registrable apex from `domain` (and for an apex like tenshu.app, the record is the zero-label '@' record)."
  type        = string
  default     = ""
}

# ---- Droplet sizing / placement -------------------------------------------

variable "region" {
  description = "DO region slug. Pick one near your users (fra1 = Frankfurt, nyc3, sfo3, sgp1, ...)."
  type        = string
  default     = "fra1"
}

variable "droplet_size" {
  description = <<-EOT
    Droplet size slug. The default builds the image ON the droplet (multi-module
    Kotlin/JS SDK bundle + the apex Vite build, RAM-hungry), so give it headroom:
    s-2vcpu-4gb builds comfortably. Caddy serving a static SPA idles in well under
    512MB - so once you switch to the prebuilt-image path (apex_image_tag, see the
    how-to) you can drop to s-1vcpu-1gb.
  EOT
  type        = string
  default     = "s-2vcpu-4gb"
}

variable "droplet_image" {
  description = "Base image slug. Ubuntu LTS keeps cloud-init + Docker install simple."
  type        = string
  default     = "ubuntu-24-04-x64"
}

# ---- Firewall --------------------------------------------------------------

variable "ssh_allowed_cidrs" {
  description = <<-EOT
    Source CIDRs allowed to reach SSH (port 22). Defaults to the whole internet,
    which is convenient but noisy - set this to your own IP(s) (e.g. ["203.0.113.4/32"])
    to lock SSH down. Ports 80/443 are always open to the world (that's the site).
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

# ---- Application config (passed through to the apex container) ------------

variable "git_ref" {
  description = "Git ref (branch, tag, or commit SHA) of B3nedikt/Drive the droplet builds/deploys from on first boot. Pin to a tag/SHA for reproducible deploys; 'master' tracks tip."
  type        = string
  default     = "master"
}

variable "git_repo_url" {
  description = "HTTPS clone URL of the repo the droplet builds from. Public repo => no auth needed in cloud-init."
  type        = string
  default     = "https://github.com/B3nedikt/Tenshu.git"
}

variable "git_token" {
  description = <<-EOT
    Read-only GitHub token for cloning a PRIVATE repo on first boot. Use a
    fine-grained PAT scoped to only this repo with Contents: Read-only. Leave
    empty for a public repo (anonymous clone). When set, it's spliced into the
    clone URL - so it lands in Terraform state and the droplet's cloud-init
    metadata + the on-box bootstrap script. The minimal single-repo read-only
    scope is what keeps that exposure acceptable; rotate/revoke it when the repo
    goes public and you can drop this variable entirely.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "relay_url" {
  description = <<-EOT
    The relay origin compiled INTO the SPA. Only used on the ON-BOX build path
    (apex_image_tag empty) - a prebuilt image already has the relay URL baked in
    at CI build time. Defaults to the production relay; set it only if your relay
    lives on a different host (see web/apex/src/lib/relayUrl.ts).
  EOT
  type        = string
  default     = "https://relay.tenshu.app"
}

variable "construction_code" {
  description = <<-EOT
    Pre-launch construction-gate code (web/apex/src/components/ConstructionGate.tsx). When set to a
    6-digit code, the apex is curtained until a visitor enters it — for hiding the site before
    marketing. Empty (the default) = no curtain. Polish, not security (the bundle still ships). Like
    relay_url it's compiled in at BUILD time, so it only takes effect on the on-box build path; a
    prebuilt image bakes whatever code CI built it with. Clear it + redeploy to lift the curtain.
  EOT
  type        = string
  default     = ""
}

variable "signup_disabled" {
  description = <<-EOT
    Pre-launch signup gate (web/apex/src/lib/signupGate.ts). Any non-empty value closes the door
    behind "Get started": the marketing site stays public, but realm creation, device pairing and
    try-mode are unreachable and /onboarding renders a "not open yet" notice. Existing realms in a
    visitor's browser are untouched — they can still unlock and open the control plane. Empty (the
    default) = signup open, which is what a test deployment wants; only the public instance sets
    this. Like relay_url it is compiled in at BUILD time, so it only takes effect on the on-box
    build path; a prebuilt image bakes whatever CI built it with. A curtain, not security.
  EOT
  type        = string
  default     = ""
}

# ---- Prebuilt-image deploy (skip the on-box build) ------------------------

variable "apex_image_tag" {
  description = <<-EOT
    When set, the droplet PULLS ghcr.io/b3nedikt/tenshu-apex:<this-tag> on first
    boot instead of building the image on-box. This removes the RAM-hungry build,
    so the droplet can drop to s-1vcpu-1gb. Use a short commit sha (reproducible)
    or "latest". The image must already be in GHCR - push it via the CI workflow
    (.github/workflows/apex-image.yml) or deploy/build-and-push-apex.sh first.
    Empty (the default) keeps the on-box build path. NB: the relay URL is fixed
    when the image is built, so a prebuilt image ignores `relay_url` - cut the
    image against the right relay via the workflow's relay_url input.
  EOT
  type        = string
  default     = ""
}

variable "ghcr_user" {
  description = "GHCR username for `docker login` when pulling a private apex image. Only used if apex_image_tag + ghcr_token are set."
  type        = string
  default     = "b3nedikt"
}

variable "ghcr_token" {
  description = <<-EOT
    Read-only GHCR token (PAT with read:packages) so the droplet can pull a
    PRIVATE apex image on first boot. Leave empty if the package is public
    (anonymous pull) or if building on-box (apex_image_tag empty). Lands in
    Terraform state + cloud-init metadata when set; scope it to read:packages and
    rotate it. Or just make the package public and skip this.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}
