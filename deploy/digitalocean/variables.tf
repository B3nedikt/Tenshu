# Inputs for the single-droplet relay deploy. Everything has a sensible default
# except the secrets/identity (do_token, ssh_keys, domain). Copy
# terraform.tfvars.example -> terraform.tfvars and fill those in.

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
  description = "Fully-qualified hostname the relay is served on, e.g. relay.tenshu.app. Used for the DNS A record (when manage_dns=true) and for Caddy's automatic TLS certificate."
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
    `domain` at the relay's reserved IP. Set false if your DNS lives elsewhere
    (Cloudflare, registrar, etc.) - then create an A record for `domain` pointing
    at the `relay_ip` output yourself.
  EOT
  type        = bool
  default     = true
}

variable "dns_zone" {
  description = "The DO-managed apex zone (e.g. tenshu.app) that `domain` is a record under. Only used when manage_dns=true. Empty = derive the registrable apex from `domain`."
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
    Droplet size slug. The default builds the Ktor fat jar ON the droplet (multi-module
    Gradle, RAM-hungry), so give it headroom: s-2vcpu-4gb builds comfortably. The relay
    itself runs fine on 512MB-1GB at idle - you can resize down after the first build,
    or switch to a prebuilt image (see the how-to doc) and run on a smaller box.
  EOT
  type        = string
  default     = "s-2vcpu-4gb"
}

variable "droplet_image" {
  description = "Base image slug. Ubuntu LTS keeps cloud-init + Docker install simple."
  type        = string
  default     = "ubuntu-24-04-x64"
}

variable "data_volume_size_gb" {
  description = "Block-storage volume size (GB) for the SQLite DB + content/share blobs. Grows the durable plane independently of the droplet. 10GB is a roomy start."
  type        = number
  default     = 10
}

# ---- Firewall --------------------------------------------------------------

variable "ssh_allowed_cidrs" {
  description = <<-EOT
    Source CIDRs allowed to reach SSH (port 22). Defaults to the whole internet,
    which is convenient but noisy - set this to your own IP(s) (e.g. ["203.0.113.4/32"])
    to lock SSH down. Ports 80/443 are always open to the world (that's the relay).
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

# ---- Application config (passed through to the relay container) -----------

variable "git_ref" {
  description = "Git ref (branch, tag, or commit SHA) of B3nedikt/Drive to build the relay from on first boot. Pin to a tag/SHA for reproducible deploys; 'master' tracks tip."
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

# ---- Prebuilt-image deploy (skip the on-box build) ------------------------

variable "relay_image_tag" {
  description = <<-EOT
    When set, the droplet PULLS ghcr.io/b3nedikt/tenshu-relay:<this-tag> on first
    boot instead of building the fat jar on-box. This removes the RAM-hungry build,
    so the droplet can drop to s-1vcpu-1gb. Use a short commit sha (reproducible)
    or "latest". The image must already be in GHCR - push it via the CI workflow
    (.github/workflows/relay-image.yml) or deploy/build-and-push.sh first. Empty
    (the default) keeps the original on-box build path.
  EOT
  type        = string
  default     = ""
}

variable "ghcr_user" {
  description = "GHCR username for `docker login` when pulling a private relay image. Only used if relay_image_tag + ghcr_token are set."
  type        = string
  default     = "b3nedikt"
}

variable "ghcr_token" {
  description = <<-EOT
    Read-only GHCR token (PAT with read:packages) so the droplet can pull a
    PRIVATE relay image on first boot. Leave empty if the package is public
    (anonymous pull) or if building on-box (relay_image_tag empty). Lands in
    Terraform state + cloud-init metadata when set; scope it to read:packages and
    rotate it. Or just make the package public and skip this.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "cors_allowed_origins" {
  description = <<-EOT
    Comma-separated web origins allowed to call the relay cross-origin (the web SDK +
    the anonymous Mode-A share viewer). Empty = the relay's built-in apex default
    (https://tenshu.app + drive/control subdomains). See RelayConfig.corsAllowedOrigins.
  EOT
  type        = string
  default     = ""
}

# ---- Commerce entitlement enforcement (commerce-billing-plane.md §8) -------
# All default off/empty so an existing relay is unaffected. Turn on once the
# commerce issuer is deployed (docs/how-to/deploy-commerce-digitalocean.md).

variable "entitlement_enforce" {
  description = <<-EOT
    Gate relay WRITES on a valid commerce entitlement token. Default false (verify-only / no-op).
    Set "true" only once entitlement_issuer_pubkey is set AND your clients present a token, or all
    writes from un-configured clients get 402. See deploy-commerce-digitalocean.md.
  EOT
  type        = string
  default     = "false"
}

variable "entitlement_issuer_pubkey" {
  description = <<-EOT
    Base64 Ed25519 PUBLIC key of the commerce issuer (its private half never leaves the commerce
    box). Printed on commerce boot ("Commerce issuer public key: ..."). Empty = entitlement
    verification disabled. Public key, not a secret.
  EOT
  type        = string
  default     = ""
}

variable "entitlement_issuer_key_id" {
  description = "Token `kid` for A29 rotation — set only if the commerce issuer sets SIGNING_KEY_ID. Empty = the issuer's default key."
  type        = string
  default     = ""
}

# ---- Abuse resistance (docs/how-to/deploy-relay-digitalocean.md §"DDoS & abuse") ----

variable "genesis_rate_limit_per_hour" {
  description = <<-EOT
    Per-IP hourly cap on the unauthenticated POST /sync/realm bootstrap — the one write-shaped
    route entitlement enforcement can't cover (a brand-new owner has no token yet). Default "20" is
    on by default and generous for legitimate onboarding. Caddy forwards X-Forwarded-For so the cap
    keys on the real client IP. Raise behind a shared NAT; "0" disables the limiter.
  EOT
  type        = string
  default     = "20"
}
