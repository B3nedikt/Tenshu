# Single-droplet Tenshu commerce issuer on DigitalOcean.
#
#   commerce = one Ktor/Netty fat-jar container (issues signed entitlements; holds A29 seed)
#   db       = a bundled postgres:16 sidecar (A28 store), pgdata on the block volume
#   caddy    = reverse proxy terminating TLS (automatic Let's Encrypt) -> commerce:8081
#   volume   = block storage for the Postgres data dir (survives a droplet rebuild)
#
# `terraform apply` provisions all of it; the droplet's cloud-init (see
# cloud-init.yaml.tftpl) installs Docker, mounts the volume, clones the repo at
# `git_ref` for the compose/Caddy files, writes the env, and `docker compose up`s.
#
# SEPARATE trust boundary from the relay (commerce-billing-plane.md D1): this box holds
# the private signing seed + email PII; it shares no host, network, or credential with the
# ciphertext-only relay. Mirrors deploy/digitalocean/ but is deliberately its own module.

locals {
  # Derive the registrable apex (e.g. commerce.service.tenshu.app -> tenshu.app) when the
  # caller didn't pin dns_zone explicitly. Good enough for simple two-label TLDs.
  dns_zone = var.dns_zone != "" ? var.dns_zone : join(".", slice(split(".", var.domain), length(split(".", var.domain)) - 2, length(split(".", var.domain))))

  # The subdomain label(s) under the zone (e.g. "commerce.service"); "@" if domain == zone.
  dns_record_name = var.domain == local.dns_zone ? "@" : trimsuffix(var.domain, ".${local.dns_zone}")

  # The bundled Postgres (default) or an external managed DB when database_url is set.
  database_url = var.database_url != "" ? var.database_url : "jdbc:postgresql://db:5432/commerce?user=commerce&password=${var.database_password}"

  # Rendered bootstrap script handed to the droplet as user_data.
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    git_repo_url = var.git_repo_url
    git_ref      = var.git_ref
    git_token    = var.git_token

    commerce_domain    = var.domain
    acme_email         = var.acme_email
    commerce_image_tag = var.commerce_image_tag
    ghcr_user          = var.ghcr_user
    ghcr_token         = var.ghcr_token

    dev_mode             = var.dev_mode
    issuer_seed          = var.issuer_seed
    issuer_id            = var.issuer_id
    signing_key_id       = var.signing_key_id
    database_url         = local.database_url
    database_password    = var.database_password
    redemption_codes     = var.redemption_codes
    token_ttl_hours      = var.token_ttl_hours
    top_tier_quota_bytes = var.top_tier_quota_bytes
    admin_token          = var.admin_token
    admin_totp_secret    = var.admin_totp_secret

    smtp_host      = var.smtp_host
    smtp_port      = var.smtp_port
    smtp_from      = var.smtp_from
    smtp_from_name = var.smtp_from_name
    smtp_username  = var.smtp_username
    smtp_password  = var.smtp_password
    smtp_starttls  = var.smtp_starttls

    stripe_secret_key     = var.stripe_secret_key
    stripe_webhook_secret = var.stripe_webhook_secret
    stripe_price_ids      = var.stripe_price_ids
  })
}

# ---- Durable data plane: block-storage volume ------------------------------
# The Postgres data dir (accounts, sessions, entitlement ledger, statutory invoices)
# lives here so a droplet rebuild/resize never loses the A28 store.
resource "digitalocean_volume" "commerce_data" {
  region                  = var.region
  name                    = "tenshu-commerce-data"
  size                    = var.data_volume_size_gb
  initial_filesystem_type = "ext4"
  description             = "Tenshu commerce issuer: Postgres data (accounts, sessions, entitlement ledger, invoices — A28 PII)."
}

# ---- The droplet ------------------------------------------------------------
resource "digitalocean_droplet" "commerce" {
  name      = "tenshu-commerce"
  region    = var.region
  size      = var.droplet_size
  image     = var.droplet_image
  ssh_keys  = var.ssh_keys
  user_data = local.user_data

  volume_ids = [digitalocean_volume.commerce_data.id]

  tags = ["tenshu", "commerce"]

  lifecycle {
    precondition {
      condition     = var.dev_mode == "true" || var.issuer_seed != ""
      error_message = "issuer_seed is required when dev_mode=\"false\" — the issuer refuses to mint with the committed dev seed. Generate one: head -c 32 /dev/urandom | base64."
    }
    # Mirrors CommerceConfig.validate(): without a real transport the OTP — the account
    # model's only factor — is printed to the container log. Caught here so the failure is
    # a plan error naming the fix, not a droplet that boots into a crash loop.
    precondition {
      condition     = var.dev_mode == "true" || (var.smtp_host != "" && var.smtp_from != "")
      error_message = "smtp_host and smtp_from are required when dev_mode=\"false\" — the issuer refuses to start with the dev sender, which prints the OTP to the server log. See docs/how-to/set-up-otp-email.md."
    }
    precondition {
      condition     = (var.stripe_secret_key == "") == (var.stripe_webhook_secret == "")
      error_message = "Set BOTH stripe_secret_key and stripe_webhook_secret, or neither — an unverified webhook is a mint-forgery vector (T-CB-07)."
    }
  }
}

# ---- Stable public IP -------------------------------------------------------
# A reserved IP keeps the DNS record valid across droplet rebuilds.
resource "digitalocean_reserved_ip" "commerce" {
  region = var.region
}

resource "digitalocean_reserved_ip_assignment" "commerce" {
  ip_address = digitalocean_reserved_ip.commerce.ip_address
  droplet_id = digitalocean_droplet.commerce.id
}

# ---- Cloud firewall ---------------------------------------------------------
# 80/443 open to the world (the issuer API + ACME HTTP-01 challenge); SSH restricted
# to ssh_allowed_cidrs. The issuer (8081), admin console (8082) and Postgres (5432) are
# NOT published to the host — only Caddy reaches the issuer over the compose network, and the
# admin console binds the CONTAINER's loopback, so it is reached by SSHing to the box and joining
# that container's network namespace (NOT by an ssh -L tunnel, which lands on the host's loopback
# where nothing listens — see admin_token). Outbound open (apt, git, ACME, layers, Stripe).
resource "digitalocean_firewall" "commerce" {
  name        = "tenshu-commerce"
  droplet_ids = [digitalocean_droplet.commerce.id]

  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = var.ssh_allowed_cidrs
  }

  inbound_rule {
    protocol         = "tcp"
    port_range       = "80"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }

  inbound_rule {
    protocol         = "tcp"
    port_range       = "443"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }

  # ICMP for diagnostics (ping/MTU discovery).
  inbound_rule {
    protocol         = "icmp"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
}

# ---- DNS (optional) ---------------------------------------------------------
# Only when manage_dns=true. Creates the DO-hosted zone AND the A record, so all you
# do at your registrar is point the nameservers at DO. If your DNS lives elsewhere,
# set manage_dns=false and add an A record for `domain` -> the commerce_ip output there.
#
# NB: if the zone already exists in your DO account, `apply` will error ("domain already
# exists") — import it first:  terraform import digitalocean_domain.zone[0] <zone>
resource "digitalocean_domain" "zone" {
  count = var.manage_dns ? 1 : 0
  name  = local.dns_zone
}

resource "digitalocean_record" "commerce_a" {
  count  = var.manage_dns ? 1 : 0
  domain = digitalocean_domain.zone[0].name
  type   = "A"
  name   = local.dns_record_name
  value  = digitalocean_reserved_ip.commerce.ip_address
  ttl    = 300
}
