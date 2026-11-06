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
  description = "Scaleway Project ID (a UUID). ⚠ Scaleway scopes every resource to a Project — there is no DigitalOcean equivalent, and omitting it puts commerce somewhere you did not intend."
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

variable "acme_email" {
  description = "Contact email for the Let's Encrypt account Caddy registers (expiry notices). Required for automatic HTTPS."
  type        = string
}

# ---- DNS -------------------------------------------------------------------

variable "domain" {
  description = "Public hostname commerce serves on, e.g. commerce.service.tenshu.app. Drives Caddy's automatic HTTPS."
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


# ---- SMTP: it already works, and here is the one way to break it -----------
#
# ⭐ **2587 needs no special handling on Scaleway.** `scaleway_instance_security_group`'s
# `enable_default_security` blocks 25, 465 and 587 by default — 2587 is NOT on that list, so
# Transactional Email over 2587 works from a Scaleway Instance exactly as it did from the DO
# droplet. No support ticket, no flag, no change to this module.
#
# ⚠⚠ **The trap is "tidying" the port to 587.** Scaleway's own TEM documentation recommends 587
# (STARTTLS), 465 (SSL) or 2465 (TLS) — and those are either blocked by `enable_default_security`,
# or need implicit TLS that `SmtpEmailSender` does not do (it sets `starttls.required`). So the port
# that looks most correct by the vendor's own docs is the one that silently fails HERE while
# continuing to work on DigitalOcean.
#
# ⚠ 2587 was originally chosen to dodge DIGITALOCEAN's block of 25/465/587. That it also dodges
# Scaleway's identical block is luck, not design — but it is why this migration needs nothing.
#
# ⚠ If you ever do need 465/587: `enable_default_security = false` on the security group unblocks
# them "if your account is authorized to" — that authorization is the support-ticket path, and it is
# for self-hosted mail, not for TEM.

# ---- DNS -------------------------------------------------------------------



# ---- Droplet sizing / placement -------------------------------------------


# ---- Firewall --------------------------------------------------------------


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

variable "git_repo_is_private" {
  description = "Whether git_repo_url needs git_token to clone. True refuses a plan with an empty token; set false once the repo is public."
  type        = bool
  default     = true
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
  # ⚠ Live codes that grant storage. Every other secret here was sensitive and this one was not, so
  # `terraform plan` printed them.
  sensitive = true
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

# ⚠⚠ Browser access (CORS, threat model T-CB-09/T-CB-10). Empty = no browser can call commerce, so the
# web apex's sign-in, checkout and plan sync all fail in CORS preflight. Exactly one origin, never a
# wildcard; it is also where web checkouts return to.
variable "web_app_origin" {
  description = "The one browser origin allowed to call commerce, e.g. https://tenshu.app. Empty disables browser access."
  type        = string
  default     = ""

  validation {
    condition     = var.web_app_origin == "" || can(regex("^https://[a-z0-9.-]+(:[0-9]{1,5})?$", var.web_app_origin))
    error_message = "web_app_origin must be empty or a bare lower-case https origin (optional port, no path, no wildcard), e.g. https://tenshu.app."
  }
}

variable "swapfile_size_gb" {
  description = "Swapfile size on the ROOT disk, in GB (0 = none). Null (the default) means 1 when commerce_image_tag is set (prebuilt image) and 4 when it is empty (on-box build, which is RAM-hungry). A fixed 4G swapfile filled commerce's 8.9G root disk in October 2026 (docs/how-to/box-health.md)."
  type        = number
  default     = null
}

variable "google_play_service_account_json" {
  description = "The Play Developer API service account's JSON key (Keychain item `play-service-account`). Empty => Google Play rail off. Needs google_play_rtdn_token too."
  type        = string
  default     = ""
  sensitive   = true
}

variable "google_play_rtdn_token" {
  description = "Shared secret Pub/Sub puts in the RTDN push URL (`/commerce/iap/google/rtdn?token=…`). Required with google_play_service_account_json; commerce refuses to start without it."
  type        = string
  default     = ""
  sensitive   = true
}

# ⚠⚠ Account-closure delivery (retention-quota-model.md §3.3). With this set AND the relay's
# account_closure_reclaim = "true", a closed account's stored content is permanently deleted after
# the grace window (30 days). Empty = closure records are never delivered.
variable "account_closure_relay_url" {
  description = "Relay closure endpoint, e.g. https://relay.tenshu.app/sync/account/closure. Empty disables delivery."
  type        = string
  default     = ""

  # ⚠ A typo here is silent: commerce posts to the wrong URL, every sweep logs a failure nobody
  # reads, and closed accounts are never reclaimed. Pin the shape so a plan refuses it instead.
  validation {
    condition     = var.account_closure_relay_url == "" || can(regex("^https://[^/]+/sync/account/closure$", var.account_closure_relay_url))
    error_message = "account_closure_relay_url must be empty or https://<relay-host>/sync/account/closure."
  }
}

# ---- Store-review sign-in (commerce-accounts-and-sessions.md D13, T-CB-12) ----
# Both empty = off. Set both or neither: commerce refuses to start otherwise, and so does the plan
# (main.tf precondition). ⚠⚠ The codes are never Terraform inputs — only their HMACs under the key.
# ⚠⚠ Do NOT use these to turn review sign-in on or off on a LIVE box: cloud-init writes them only on
# first boot, and anything set here lands in the instance's user_data and in Terraform state. On a
# running box, edit /opt/tenshu/deploy.env by hand (docs/how-to/play-console-answers.md §14). These
# exist for a box built from scratch.

variable "review_signin_accounts" {
  description = "`<address>=<hex HMAC-SHA256 of that account's 6-digit code>`, comma-separated, one unpublished review address per store. Secret (the addresses are unpublished). Empty => review sign-in off."
  type        = string
  default     = ""
  sensitive   = true
}

variable "review_signin_hmac_key" {
  description = "Secret HMAC key (>= 32 chars, e.g. `openssl rand -hex 32`, Keychain item commerce-review-signin-hmac-key) that the review_signin_accounts digests are keyed under. Empty => review sign-in off."
  type        = string
  default     = ""
  sensitive   = true

  validation {
    condition     = var.review_signin_hmac_key == "" || length(var.review_signin_hmac_key) >= 32
    error_message = "review_signin_hmac_key must be at least 32 characters."
  }
}
