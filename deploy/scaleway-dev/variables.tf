# ---- Scaleway account / auth ------------------------------------------------

variable "access_key" {
  type        = string
  description = "Scaleway API access key (SCWXXXXXXXXXXXXXXXXXX). Console → Organization → API keys."
}

variable "secret_key" {
  type        = string
  sensitive   = true
  description = "Scaleway API secret key — shown ONCE at key creation and never again."
}

variable "project_id" {
  type        = string
  description = <<-EOT
    Project the dev lab lives in.

    ⚠⚠ Use a SEPARATE Project from production, not the default one. Projects are free, and this is
    the cheapest isolation in the whole design: a `terraform destroy` aimed at the dev lab then
    cannot reach the production relay even if it is pointed at the wrong state file, and the dev
    lab's resources and billing are visible apart from the real ones.
  EOT
}

# ---- Placement --------------------------------------------------------------

variable "zone" {
  type        = string
  description = "Zone for the instance and its volume, e.g. fr-par-1. ⚠ Both must be in the SAME zone — a volume in fr-par-2 will not attach to a server in fr-par-1."
  default     = "fr-par-1"
}

variable "region" {
  type        = string
  description = "Region for regional resources (DNS). Should contain var.zone."
  default     = "fr-par"
}

# ---- The box ----------------------------------------------------------------

variable "instance_type" {
  type        = string
  description = <<-EOT
    Commercial instance type. DEV1-M (3 vCPU / 4 GB) is what deploy/scaleway-relay runs, and this
    box carries more: relay + commerce + Postgres + apex + Caddy + mailpit.

    ⚠ x86 on purpose. COPARM1 (Ampere) is cheaper per GB, but a dev plane on a different
    architecture from production can no longer catch an architecture-specific problem — in either
    direction. The DL-2 multi-arch image work still stands and costs nothing; it is simply not
    needed here.

    ⚠ Availability is per-zone: a type that exists in fr-par-1 may not in nl-ams-1, and that
    surfaces at apply, not at plan.
  EOT
  default     = "DEV1-M"
}

variable "instance_image" {
  type        = string
  description = "Base image label. ⚠ The cloud-init assumes Debian/Ubuntu with apt."
  default     = "ubuntu_jammy"
}

variable "root_volume_size_gb" {
  type        = number
  description = <<-EOT
    ROOT volume — where Docker image layers extract, NOT where dev data lives.

    ⚠⚠ 30, not the relay module's 20, and not Scaleway's ~9 GB default. This box pulls or loads SIX
    images (relay, commerce, apex, postgres, caddy, mailpit) where the relay box pulls one. The
    relay's 9 GB root has filled to 100 % and failed an image pull with `no space left on
    device` while its 46 GB data volume sat at 1 % — see deploy/scaleway-relay/README.md.
  EOT
  default     = 30
}

variable "data_volume_size_gb" {
  type        = number
  description = "Durable volume: relay SQLite + blobs, the commerce Postgres, and later redroid's /data."
  default     = 40
}

variable "data_volume_iops" {
  type        = number
  description = "Provisioned IOPS. ⚠ REQUIRED by scaleway_block_volume, and the accepted values are tier-dependent — a rejected number fails at CREATE, not at plan. 5000 is the standard tier."
  default     = 5000
}

# ⚠⚠ There is deliberately NO ssh_key variable, and its absence is the documentation.
#
# `scaleway_instance_server` has no SSH-key argument: Scaleway injects every key registered on the
# PROJECT through the metadata service, at FIRST BOOT only. So register your key in the console
# (Project → SSH keys) BEFORE `terraform apply`. Adding it afterwards does not reach a running
# instance — you get `Permission denied (publickey)` on a box that is otherwise perfectly healthy,
# and the recovery is a rebuild or the console's VNC.
#
# ⚠ There is deliberately no `ssh_key_ids` variable: nothing could reference it, so it would look
# like the knob and grant nothing.

variable "ssh_ca_public_key" {
  type        = string
  description = <<-EOT
    PUBLIC half of an SSH certificate authority the box will trust for user logins, as one line of
    `ssh-ed25519 AAAA...`. Empty (the default) leaves the box on Project keys alone.

    ⭐ This is how an AGENT gets in without anyone holding a long-lived key. You sign a short-lived
    certificate for a throwaway keypair and hand over the pair; the certificate EXPIRES BY ITSELF, so
    a session that ends — or a cloud sandbox that leaks tomorrow — leaves nothing behind to revoke.
    Pure OpenSSH: no daemon, no overlay network, no third party, and every existing script
    (`ssh`, `docker save | ssh docker load`, /opt/tenshu/compose) keeps working unchanged.

      ssh-keygen -t ed25519 -f ~/.ssh/tenshu-dev-ca -C "tenshu dev lab CA"   # once
      ssh-keygen -s ~/.ssh/tenshu-dev-ca -I <who> -n root -V +8h agent.pub   # per session

    ⚠⚠ The CA's PRIVATE half must never reach this box, a cloud session, or the repo. Anyone holding
    it can mint access to every box that trusts the CA, for as long as they like.

    ⚠ Narrowing ssh_allowed_cidrs is NOT an alternative for agents: a cloud session's egress address
    is neither stable nor knowable ahead of time. Certificates are the lever that actually exists.

    ⚠ Independent of Project SSH keys, which Scaleway injects at FIRST BOOT ONLY — so register every
    key you will want before the first apply. A certificate, by contrast, can be issued any time
    after the box exists, which is the other half of why this is here.
  EOT
  default     = ""
}

variable "ssh_allowed_cidrs" {
  type        = list(string)
  description = "Who may reach :22. ⚠⚠ Scaleway security groups default to ACCEPT — this module sets inbound_default_policy = \"drop\", so this list is the whole of your SSH exposure."
  default     = ["0.0.0.0/0"]
}

# ---- The dev plane ----------------------------------------------------------

variable "dev_domain" {
  type        = string
  description = <<-EOT
    The throwaway domain the dev plane is served on, e.g. tenshu.xyz. Three hostnames come from it:
    relay.<domain>, commerce.<domain>, and the apex at the zone root.

    ⚠⚠ A SEPARATE registrable domain, never a subdomain of the production zone. main.tf asserts
    this at plan time: a `dev-` prefix under tenshu.app is one typo away from a client that reaches
    production without failing, which is the whole reason the plane is isolated
    (docs/proposals/ops-dev-lab.md §4).
  EOT
}

variable "production_domain" {
  type        = string
  description = "The production zone the dev plane must not live under. Only used by the plan-time isolation guard in main.tf."
  default     = "tenshu.app"
}

variable "manage_dns" {
  type        = bool
  description = <<-EOT
    Create the three A records in **Scaleway** DNS. ⚠⚠ Defaults FALSE, because tenshu.xyz's zone
    lives at CLOUDFLARE — the same host as production's tenshu.app, by decision. Turning
    this on with the zone served elsewhere is a silent failure: the apply is clean, the records are
    created in a Scaleway zone nobody queries, and the only symptom is that nothing resolves.

    Leave it false and read `dns_records_needed` instead: three A records, added once in the
    Cloudflare dashboard. ⭐ Once, not per deploy — `scaleway_instance_ip.dev` is a standalone
    resource that the server references rather than owns, so rebuilding the instance keeps the
    same address. (⚠ Editing cloud-init does not rebuild it — see main.tf.)

    ⚠ This deliberately does NOT use production's DNS machinery either: that zone lives in a
    provider-neutral inventory (deploy/dns/records.json) behind an adapter (deploy/cloudflare-dns)
    so no service module owns a record, which earns its keep for a zone serving mail with a rollback
    story. Three static A records have neither problem. If the dev lab ever grows records worth
    keeping, move it into the inventory rather than turning this on.

    Set it true only if you actually move the zone to Scaleway DNS (console → Domains and DNS →
    external domain, then repoint the registrar's nameservers). The provider manages records, never
    the zone itself.
  EOT
  default     = false
}

# ---- Secrets ----------------------------------------------------------------

variable "git_repo_url" {
  type        = string
  description = "HTTPS clone URL. ⚠ Required even when pulling prebuilt images — the compose file and Caddyfile live in the repo."
  default     = "https://github.com/B3nedikt/Tenshu.git"
}

variable "git_ref" {
  type        = string
  description = "Branch/tag/sha the box checks out at first boot."
  default     = "master"
}

variable "git_token" {
  type        = string
  sensitive   = true
  description = "Read-only PAT. ⚠ Required — the repo is private, and without it the clone fails as `could not read Username for 'https://github.com'`, which reads like a prompt problem rather than a missing credential."
  default     = ""
}

variable "ghcr_user" {
  type        = string
  description = "GHCR username for pulling prebuilt images. Empty => no docker login, and the box relies on images shipped with deploy/ship-dev-stack.sh."
  default     = ""
}

variable "ghcr_token" {
  type        = string
  sensitive   = true
  description = "GHCR read token. See ghcr_user."
  default     = ""
}

variable "acme_email" {
  type        = string
  description = "Let's Encrypt contact. ⚠⚠ Must be real: Let's Encrypt REFUSES example.com outright (`invalidContact … forbidden domain`), and the failure is a TLS handshake error with no certificate ever issued, not an error at apply."
}

variable "issuer_seed" {
  type        = string
  sensitive   = true
  description = <<-EOT
    Base64 32-byte Ed25519 seed the DEV commerce issuer mints entitlements with.
    Generate with: head -c 32 /dev/urandom | base64

    ⚠⚠ Generate a NEW one. Never production's. A dev-signed entitlement being worthless against the
    production relay — and vice versa — is the isolation property this whole plane exists to have.
    Empty => commerce refuses to start, which is the correct failure; main.tf catches it at plan.
  EOT
  default     = ""
}

variable "entitlement_issuer_pubkey" {
  type        = string
  description = <<-EOT
    PUBLIC half of issuer_seed, base64 — what the relay verifies entitlements against. Set => the
    box boots with enforcement ON. Empty (the default) => enforcement OFF, which is only right for
    the very first apply of a new plane, before the key is known.

    It is a pure function of issuer_seed, so read it once and keep it here:
      ssh root@<ip> '/opt/tenshu/compose logs commerce | grep -i "public key"'

    ⚠⚠ Without it every REBUILD silently comes back unenforced: dev.env is re-rendered from this
    template and the relay stays healthy, so nothing shows — tier and quota tests just pass for the
    wrong reason (ops-dev-lab.md §10.11). ⚠ Rotate issuer_seed and this must move with it, or the
    relay refuses every token; that failure is loud, which is the right way round.

    Not a secret, so it lives in terraform.tfvars rather than the Keychain.
  EOT
  default     = ""
}

variable "postgres_password" {
  type        = string
  sensitive   = true
  description = "Password for the dev commerce Postgres. Never leaves the box's compose network."
  default     = ""
}

# ---- Mail endpoint for agents (optional) -------------------------------------
# mailpit's inbox is bound to the box's loopback, which is all a session with SSH needs. A cloud
# session has no SSH — its sandbox lets out HTTPS only — so it cannot read a sign-up code at all.
# With this set, the front Caddy also serves https://mail.<dev_domain>, READ-ONLY (three GET routes
# of mailpit's API), behind this bearer token. Empty => the hostname is not served.

variable "mailpit_token" {
  type        = string
  sensitive   = true
  description = "Bearer token for https://mail.<dev_domain> (read-only mailpit API). Empty => no such endpoint. Generate: openssl rand -hex 24"
  default     = ""
}

# ---- Stripe TEST mode (optional) ---------------------------------------------
# All three empty (the default) => the fake rail, which needs no Stripe account. Set all three and
# the box runs the REAL rail against a Stripe sandbox instead: the hosted Checkout page, and
# Stripe's own webhook payloads, which is the one thing the fake rail cannot show.
#
# ⚠⚠ TEST keys only, and main.tf enforces it. This plane runs commerce with DEV_MODE=true, which
# relaxes startup refusals a plane taking real money must not relax.

variable "stripe_secret_key" {
  type        = string
  sensitive   = true
  description = "Stripe TEST secret key (sk_test_… / rk_test_…). Empty => the fake rail."
  default     = ""
}

variable "stripe_webhook_secret" {
  type        = string
  sensitive   = true
  description = "Signing secret (whsec_…) of the sandbox webhook endpoint pointed at https://commerce.<dev_domain>/commerce/webhook/stripe. Required together with stripe_secret_key."
  default     = ""
}

variable "stripe_price_ids" {
  type        = string
  description = "Tier -> sandbox price map: `tenshu.drive.basic=price_x,tenshu.drive.premium=price_y`. Price ids are not secret, so this lives in terraform.tfvars rather than the Keychain."
  default     = ""
}

# ---- Image tags -------------------------------------------------------------

variable "relay_image_tag" {
  type        = string
  description = "Tag of ghcr.io/b3nedikt/tenshu-relay to run. With ship-dev-stack.sh the image is already on the box under this tag and nothing is pulled — the tag is the cutover switch."
  default     = "latest"
}

variable "commerce_image_tag" {
  type        = string
  description = "Tag of ghcr.io/b3nedikt/tenshu-commerce to run. See relay_image_tag."
  default     = "latest"
}

variable "apex_image_tag" {
  type        = string
  description = "Tag of ghcr.io/b3nedikt/tenshu-apex to run. See relay_image_tag."
  default     = "latest"
}
