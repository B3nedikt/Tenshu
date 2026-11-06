# Inputs for the single-droplet commerce-issuer deploy. Everything has a sensible
# default except the secrets/identity (do_token, ssh_keys, domain, acme_email,
# issuer_seed, database_password). Copy terraform.tfvars.example -> terraform.tfvars
# and fill those in.
#
# Trust boundary: this is a SEPARATE plane from the relay (commerce-billing-plane.md D1).
# Deploy it on its OWN droplet — the issuer holds the A29 signing seed + email PII (A28)
# and must never share a host or credential with the ciphertext-only relay.

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
  description = "Fully-qualified hostname the commerce issuer is served on, e.g. commerce.service.tenshu.app (web/architecture.md §3.4 D9). Used for the DNS A record (when manage_dns=true) and for Caddy's automatic TLS certificate. TLS is load-bearing: request bodies carry the user's email (A28 PII)."
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
    `domain` at the issuer's reserved IP. Set false if your DNS lives elsewhere
    (Cloudflare, registrar, etc.) - then create an A record for `domain` pointing
    at the `commerce_ip` output yourself.
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
    Gradle, RAM-hungry), so give it headroom: s-2vcpu-4gb builds comfortably. With a
    prebuilt image (commerce_image_tag set — recommended) there's no on-box build, so the
    box only runs the JVM issuer + the bundled Postgres; s-1vcpu-2gb is plenty.
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
  description = "Block-storage volume size (GB) for the bundled Postgres data dir (accounts, sessions, entitlement ledger, statutory invoices — A28). Survives a droplet rebuild. 10GB is a roomy start for a small user base."
  type        = number
  default     = 10
}

# ---- Firewall --------------------------------------------------------------

variable "ssh_allowed_cidrs" {
  description = <<-EOT
    Source CIDRs allowed to reach SSH (port 22). Defaults to the whole internet,
    which is convenient but noisy - set this to your own IP(s) (e.g. ["203.0.113.4/32"])
    to lock SSH down. Ports 80/443 are always open to the world (that's the issuer API).
    The issuer (8081), admin console (8082) and Postgres (5432) are never published to the
    host — only Caddy reaches the issuer over the internal compose network.

    ⚠ This list is therefore the real perimeter of the admin console: it is reached by SSHing
    to the box (see admin_token), and root there can read both admin factors out of
    /opt/tenshu/deploy.env. Lock it to your own IP(s) if the console is enabled.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

# ---- Repo clone (for the compose + Caddy files) ---------------------------

variable "git_ref" {
  description = "Git ref (branch, tag, or commit SHA) of B3nedikt/Drive whose commerce-service/docker-compose.yml + Caddyfile the droplet uses. Pin to a tag/SHA for reproducible deploys; 'master' tracks tip."
  type        = string
  default     = "master"
}

variable "git_repo_url" {
  description = "HTTPS clone URL of the repo the droplet reads the compose/Caddy files from. Public repo => no auth needed in cloud-init."
  type        = string
  default     = "https://github.com/B3nedikt/Tenshu.git"
}

variable "git_token" {
  description = <<-EOT
    Read-only GitHub token for cloning a PRIVATE repo on first boot. Use a
    fine-grained PAT scoped to only this repo with Contents: Read-only. Leave
    empty for a public repo (anonymous clone). When set it's spliced into the
    clone URL - so it lands in Terraform state and the droplet's cloud-init
    metadata. The minimal single-repo read-only scope keeps that acceptable;
    rotate/revoke it when the repo goes public.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

# ---- Prebuilt-image deploy (recommended — skip the on-box build) ----------

variable "commerce_image_tag" {
  description = <<-EOT
    When set, the droplet PULLS ghcr.io/b3nedikt/tenshu-commerce:<this-tag> on first
    boot instead of building the fat jar on-box. Recommended: the image is amd64 (matches
    the droplet) and there's no RAM-hungry on-box build, so the box can drop to s-1vcpu-2gb.
    Use a short commit sha (reproducible) or "latest". Push it via CI first
    (.github/workflows/commerce-image.yml). Empty (default) keeps the on-box build path.
  EOT
  type        = string
  default     = ""
}

variable "ghcr_user" {
  description = "GHCR username for `docker login` when pulling a private commerce image. Only used if commerce_image_tag + ghcr_token are set."
  type        = string
  default     = "b3nedikt"
}

variable "ghcr_token" {
  description = <<-EOT
    Read-only GHCR token (PAT with read:packages) so the droplet can pull a PRIVATE
    commerce image on first boot. Leave empty if the package is public (anonymous pull)
    or if building on-box. Lands in Terraform state + cloud-init metadata when set; scope
    it to read:packages and rotate it. Or make the package public and skip this.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

# ---- Issuer identity (A29) -------------------------------------------------

variable "dev_mode" {
  description = <<-EOT
    "false" (default) forces a REAL issuer seed — CommerceConfig.validate() refuses to
    start in non-dev mode with the committed dev seed. Set "true" ONLY for a throwaway
    closed test: it falls back to the public dev seed whose pubkey is the pinned
    EntitlementSignature.COMMERCE_PUBLIC_KEY (insecure by design). Live money => keep "false".
  EOT
  type        = string
  default     = "false"
}

variable "issuer_seed" {
  description = <<-EOT
    32-byte Ed25519 issuer signing seed, base64 — the key that mints "paid" (A29).
    REQUIRED when dev_mode="false". Generate with:  head -c 32 /dev/urandom | base64
    Keep it secret; it lands in Terraform state + the droplet's deploy.env. The matching
    PUBLIC half is logged on boot — copy it into the relay's ENTITLEMENT_ISSUER_PUBKEY.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "issuer_id" {
  description = "The commerce `publisherId` stamped onto issued entitlements/tokens."
  type        = string
  default     = "tenshu.commerce"
}

variable "signing_key_id" {
  description = "Optional A29 rotation key id (token `kid`). Empty => issuer default key. If set, set the relay's ENTITLEMENT_ISSUER_KEY_ID to the same value."
  type        = string
  default     = ""
}

# ---- Durable account store (A28) — bundled Postgres -----------------------

variable "database_password" {
  description = <<-EOT
    Password for the bundled Postgres (user `commerce`, db `commerce`). REQUIRED — no
    default (never commit a password). Generate with:  openssl rand -hex 24
    Postgres is internal-only (never published off the compose network), so this is
    defence-in-depth; it still lands in Terraform state + deploy.env, so treat it as a
    secret. To use an EXTERNAL managed Postgres instead, set database_url and this is
    ignored (the bundled db still starts but goes unused — edit the compose override to drop it).
  EOT
  type        = string
  sensitive   = true
}

variable "database_url" {
  description = <<-EOT
    Optional JDBC URL of an EXTERNAL Postgres to use instead of the bundled one, e.g.
    jdbc:postgresql://db.example:5432/commerce?user=commerce&password=…&sslmode=require .
    Empty (default) => use the bundled Postgres sidecar with database_password.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

# ---- Bootstrap redemption codes (§4.3) ------------------------------------

variable "redemption_codes" {
  description = <<-EOT
    Founder/privacy redemption-code seeds (the old whitelist's successor): comma-separated
    `code:productId:maxUses[:expiresAtMs]`. Empty => none. Handy for granting yourself a
    top tier without the Stripe rail while testing.
  EOT
  type        = string
  default     = ""
}

# ---- Optional tuning -------------------------------------------------------

variable "token_ttl_hours" {
  description = "Entitlement token TTL in hours (CommerceConfig default 72)."
  type        = string
  default     = "72"
}

variable "top_tier_quota_bytes" {
  description = "Top-tier (Premium) quota in bytes stamped on tokens. Default 2 TiB."
  type        = string
  default     = "2199023255552"
}

# ---- Outbound OTP email (CE-2) — REQUIRED when dev_mode = "false" ---------
# The OTP is the account model's ONLY factor, so `CommerceConfig.validate()` refuses to
# start in non-dev mode without a real transport: the fallback sender prints the code to
# the container log. smtp_host + smtp_from are therefore effectively required here — a
# deploy without them boots into a crash loop, not into a degraded-but-running issuer.
# Plain SMTP (never a vendor HTTP API) so the provider is a tfvars change, no code (D3).
# ⚠ smtp_from's domain needs SPF + DKIM + DMARC at the provider or the OTP lands in spam,
# which is indistinguishable from "the OTP is broken" from a user's side.

variable "smtp_host" {
  description = "SMTP relay hostname, e.g. smtp.tem.scaleway.com (Scaleway TEM) or email-smtp.eu-central-1.amazonaws.com (SES). Empty is only legal with dev_mode = \"true\"."
  type        = string
  default     = ""
}

variable "smtp_port" {
  description = <<-EOT
    SMTP submission port. ⚠⚠ On a DigitalOcean droplet set this to 2587: DO blocks outbound 25,
    465 AND 587, so the standard submission port fails with a connect timeout (~15s) that
    surfaces as a 500 on /commerce/account/register. Scaleway's 2587 is the STARTTLS
    alternative, which is what SmtpEmailSender needs (it sets starttls.required; 2465 would
    need implicit TLS). ⚠ The block can appear AFTER a first send succeeds — DO applies it once
    it observes outbound mail — so a delivered test email is not proof the port is open.
    587 remains correct on a host that does not block it.
  EOT
  type        = string
  default     = "2587"
}

variable "smtp_from" {
  description = "Envelope + header From address, e.g. no-reply@tenshu.app. MUST be on a domain verified at the provider — an unverified From surfaces as a generic send error that names nothing."
  type        = string
  default     = ""
}

variable "smtp_from_name" {
  description = "Display name on the OTP message."
  type        = string
  default     = "Tenshu"
}

variable "smtp_username" {
  description = "SMTP auth username. Scaleway TEM: the Project ID of the project the TEM domain lives in. SES: the generated SMTP user (NOT an AWS access key id)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "smtp_password" {
  description = <<-EOT
    SMTP auth password. Scaleway TEM: the SECRET KEY of an API key in that project.
    SES: the derived SMTP password (NOT a raw AWS secret key). Lands in Terraform state
    and the droplet's deploy.env — treat it as a secret and rotate it at the provider,
    which is cheap for both.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "smtp_starttls" {
  description = "Require STARTTLS. Leave \"true\": SmtpEmailSender sets starttls.required so it refuses to fall back to plaintext, and the OTP is a live credential."
  type        = string
  default     = "true"
}

# ---- Admin console (§4.5) --------------------------------------------------

variable "admin_token" {
  description = <<-EOT
    Bearer token for the internal admin console (§4.5) — first of TWO factors; see
    admin_totp_secret. A secret you choose: `head -c 32 /dev/urandom | base64`.
    Empty => console OFF (the default).

    It binds a SEPARATE port (8082) that this module never publishes to the host or DNS.
    ⚠⚠ An `ssh -L 8082:localhost:8082` tunnel does NOT reach it: the console binds the
    CONTAINER's loopback (ADMIN_BIND_HOST defaults to 127.0.0.1) and compose publishes no
    host port for the commerce service, so a tunnel lands on the droplet's loopback where
    nothing listens. Join the container's network namespace instead — the recipe is in
    docs/how-to/deploy-commerce-digitalocean.md, "The admin console needs two factors".
    Keep it OFF public DNS (D8).
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "admin_totp_secret" {
  description = <<-EOT
    Base32 TOTP seed — the admin console's SECOND factor (WC-5, §5.3). Enrol it in an
    authenticator app; operators then send the six-digit code in an X-Admin-Totp header
    alongside the bearer token.

    ⚠⚠ REQUIRED whenever admin_token is set and DEV_MODE=false: the service REFUSES TO
    START without it. That is deliberate — the console is the one surface joining real PII
    with the ownerId that indexes relay metadata, and since WC-4 it can export, so a single
    stolen bearer token would de-anonymise every paying user at once.

    ⚠ Upgrading an existing deployment: set this BEFORE applying, or the container will
    crash-loop on boot with an InvalidCommerceConfigException naming ADMIN_TOTP_SECRET.

    Generate one with: head -c 20 /dev/urandom | base32 | tr -d '='
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

# ---- Stripe rail (§6) — set all three to enable paid storage --------------
# Leave empty for a free/redeem-only issuer (the /checkout, /portal, /webhook routes
# answer 503). The service refuses to start with the secret key but not the webhook
# secret (an unverified webhook is a mint-forgery vector, T-CB-07).

variable "stripe_secret_key" {
  description = "Stripe secret key (sk_test_… for the test-mode round-trip, sk_live_… for real money). Empty => Stripe rail off."
  type        = string
  default     = ""
  sensitive   = true
}

variable "stripe_webhook_secret" {
  description = "Stripe webhook signing secret (whsec_…) for POST /commerce/webhook/stripe. Required together with stripe_secret_key. Empty => Stripe rail off."
  type        = string
  default     = ""
  sensitive   = true
}

variable "stripe_price_ids" {
  description = "Tier -> Stripe price map: `tenshu.drive.basic=price_x,tenshu.drive.premium=price_y`. Create the prices in the Stripe dashboard (matching the test/live mode of stripe_secret_key)."
  type        = string
  default     = ""
}
