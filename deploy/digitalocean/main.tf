# Single-droplet Tenshu sync relay on DigitalOcean.
#
#   relay  = one Ktor/Netty fat-jar container (House Rule #1: ciphertext only)
#   caddy  = reverse proxy terminating TLS (automatic Let's Encrypt) -> relay:8080
#   volume = block storage for the SQLite DB + content/share blobs (survives rebuild)
#
# `terraform apply` provisions all of it; the droplet's cloud-init (see
# cloud-init.yaml.tftpl) installs Docker, mounts the volume, clones the repo at
# `git_ref`, and `docker compose up`s the stack. One command, no manual SSH.
#
# Single-writer caveat (docs/proposals/sync-relay-persistence.md): SQLite on one
# volume = one machine. This module deliberately provisions exactly one droplet.
# Going multi-node needs the Postgres + S3 backend first; that's a different module.

locals {
  # Derive the registrable apex (e.g. relay.tenshu.app -> tenshu.app) when the
  # caller didn't pin dns_zone explicitly. Good enough for simple two-label TLDs.
  dns_zone = var.dns_zone != "" ? var.dns_zone : join(".", slice(split(".", var.domain), length(split(".", var.domain)) - 2, length(split(".", var.domain))))

  # The subdomain label under the zone (e.g. "relay"); "@" if domain == zone.
  dns_record_name = var.domain == local.dns_zone ? "@" : trimsuffix(var.domain, ".${local.dns_zone}")

  # Rendered bootstrap script handed to the droplet as user_data.
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    git_repo_url         = var.git_repo_url
    git_ref              = var.git_ref
    git_token            = var.git_token
    relay_domain         = var.domain
    acme_email           = var.acme_email
    cors_allowed_origins = var.cors_allowed_origins
    relay_image_tag      = var.relay_image_tag
    ghcr_user            = var.ghcr_user
    ghcr_token           = var.ghcr_token

    entitlement_enforce       = var.entitlement_enforce
    entitlement_issuer_pubkey = var.entitlement_issuer_pubkey
    entitlement_issuer_key_id = var.entitlement_issuer_key_id

    genesis_rate_limit_per_hour = var.genesis_rate_limit_per_hour
  })
}

# ---- Durable data plane: block-storage volume ------------------------------
# SQLite DB + blob dir live here so a droplet rebuild/resize never loses the
# device registry, mailbox, or content blobs.
resource "digitalocean_volume" "relay_data" {
  region                  = var.region
  name                    = "tenshu-relay-data"
  size                    = var.data_volume_size_gb
  initial_filesystem_type = "ext4"
  description             = "Tenshu sync relay: SQLite DB + content/share blobs (ciphertext only)."
}

# ---- The droplet ------------------------------------------------------------
resource "digitalocean_droplet" "relay" {
  name      = "tenshu-relay"
  region    = var.region
  size      = var.droplet_size
  image     = var.droplet_image
  ssh_keys  = var.ssh_keys
  user_data = local.user_data

  volume_ids = [digitalocean_volume.relay_data.id]

  # The fat jar is rebuilt on boot from a fresh repo clone; the durable state is
  # on the attached volume, so the droplet itself is cattle. Recreate it (e.g. to
  # change the git_ref baked into user_data) without fear of data loss.
  tags = ["tenshu", "relay"]

  lifecycle {
    # ⚠⚠ `user_data` is consumed ONCE, at first boot. Editing the template afterwards changes
    # nothing on a running droplet — but the DO provider treats any diff as `forces replacement`,
    # so an unrelated `terraform apply` silently plans to DESTROY AND REBUILD PRODUCTION.
    #
    # That is not hypothetical: growing the data volume once planned "2 to add, 2 to change, 2 to
    # destroy" with `digitalocean_droplet.relay must be replaced — user_data forces replacement`,
    # and the resize had to be `-target`ed to avoid it. The drift was one template line
    # (`GENESIS_RATE_LIMIT_PER_HOUR`) whose value the running relay already had from
    # docker-compose's own default — an outage threatened to correct nothing.
    #
    # ⚠ The trade this makes: a cloud-init change NEVER reaches the droplet on its own. That is
    # the honest state either way — without `ignore_changes` it does not reach it either, the plan
    # merely shouts about it. To actually apply one, rebuild deliberately:
    #
    #     terraform apply -replace=digitalocean_droplet.relay
    #
    # (a planned outage: fresh clone + rebuild on first boot, ~3–8 min; data survives on the volume)
    #
    # ⚠⚠ And note where the relay's real configuration lives: `/opt/tenshu/deploy.env` on the
    # droplet, hand-maintained and NOT managed by Terraform — which is why `ENTITLEMENT_ENFORCE`
    # is `true` in production while nothing in this repo says so.
    ignore_changes = [user_data]
  }
}

# ---- Stable public IP -------------------------------------------------------
# A reserved IP keeps the DNS record valid across droplet rebuilds.
resource "digitalocean_reserved_ip" "relay" {
  region = var.region
}

resource "digitalocean_reserved_ip_assignment" "relay" {
  ip_address = digitalocean_reserved_ip.relay.ip_address
  droplet_id = digitalocean_droplet.relay.id
}

# ---- Cloud firewall ---------------------------------------------------------
# 80/443 open to the world (the relay + ACME HTTP-01 challenge); SSH restricted
# to ssh_allowed_cidrs. Outbound open (apt, git clone, ACME, image layers).
resource "digitalocean_firewall" "relay" {
  name        = "tenshu-relay"
  droplet_ids = [digitalocean_droplet.relay.id]

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
# Only when manage_dns=true. This creates the DO-hosted zone AND the A record, so
# all you do at your registrar is point the nameservers at DO (ns1/ns2/ns3.
# digitalocean.com). If your DNS lives elsewhere (Namecheap BasicDNS, Cloudflare),
# set manage_dns=false and add an A record for `domain` -> the relay_ip output there.
#
# NB: if the zone already exists in your DO account, `apply` will error ("domain
# already exists") - import it first:  terraform import digitalocean_domain.zone[0] <zone>
resource "digitalocean_domain" "zone" {
  count = var.manage_dns ? 1 : 0
  name  = local.dns_zone
}

resource "digitalocean_record" "relay_a" {
  count  = var.manage_dns ? 1 : 0
  domain = digitalocean_domain.zone[0].name
  type   = "A"
  name   = local.dns_record_name
  value  = digitalocean_reserved_ip.relay.ip_address
  ttl    = 300
}
