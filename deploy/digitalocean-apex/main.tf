# Single-droplet apex web frontend on DigitalOcean.
#
#   apex   = one Caddy container serving the static SPA baked into the image
#   caddy  = (same container) reverse proxy terminating TLS (automatic Let's Encrypt)
#
# `terraform apply` provisions the droplet, firewall, reserved IP (+ optional DNS);
# the droplet's cloud-init (see cloud-init.yaml.tftpl) installs Docker, clones the
# repo at `git_ref`, and `docker compose up`s the stack. One command, no manual SSH.
#
# Stateless by design: apex serves only the public open-source build, so there's
# no data volume here (contrast deploy/digitalocean/ for the relay, which carries
# the SQLite + blob volume). The only persisted state is Caddy's ACME cert store,
# a named docker volume that survives restarts.

locals {
  # Derive the registrable apex (e.g. www.tenshu.app -> tenshu.app) when the caller
  # didn't pin dns_zone explicitly. Good enough for simple two-label TLDs; for an
  # apex domain like tenshu.app this is just the domain itself.
  dns_zone = var.dns_zone != "" ? var.dns_zone : join(".", slice(split(".", var.domain), length(split(".", var.domain)) - 2, length(split(".", var.domain))))

  # The subdomain label under the zone (e.g. "www"); "@" if domain == zone.
  dns_record_name = var.domain == local.dns_zone ? "@" : trimsuffix(var.domain, ".${local.dns_zone}")

  # Rendered bootstrap script handed to the droplet as user_data.
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    git_repo_url   = var.git_repo_url
    git_ref        = var.git_ref
    git_token      = var.git_token
    apex_domain    = var.domain
    acme_email     = var.acme_email
    relay_url      = var.relay_url
    apex_image_tag = var.apex_image_tag
    ghcr_user      = var.ghcr_user
    ghcr_token     = var.ghcr_token

    construction_code = var.construction_code
    signup_disabled   = var.signup_disabled
  })
}

# ---- The droplet ------------------------------------------------------------
resource "digitalocean_droplet" "apex" {
  name      = "tenshu-apex"
  region    = var.region
  size      = var.droplet_size
  image     = var.droplet_image
  ssh_keys  = var.ssh_keys
  user_data = local.user_data

  # The site is rebuilt on boot from a fresh repo clone (or pulled prebuilt); there
  # is no durable state on the box (Caddy's certs re-issue on their own), so the
  # droplet is pure cattle. Recreate it without fear of data loss.
  tags = ["tenshu", "apex"]
}

# ---- Stable public IP -------------------------------------------------------
# A reserved IP keeps the DNS record valid across droplet rebuilds.
resource "digitalocean_reserved_ip" "apex" {
  region = var.region
}

resource "digitalocean_reserved_ip_assignment" "apex" {
  ip_address = digitalocean_reserved_ip.apex.ip_address
  droplet_id = digitalocean_droplet.apex.id
}

# ---- Cloud firewall ---------------------------------------------------------
# 80/443 open to the world (the site + ACME HTTP-01 challenge); SSH restricted to
# ssh_allowed_cidrs. Outbound open (apt, git clone, ACME, image layers).
resource "digitalocean_firewall" "apex" {
  name        = "tenshu-apex"
  droplet_ids = [digitalocean_droplet.apex.id]

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
# Only when manage_dns=true. Creates the DO-hosted zone AND the A record. If your
# DNS lives elsewhere, set manage_dns=false and add an A record for `domain` ->
# the apex_ip output there.
#
# NB: if the zone already exists in your DO account (very likely, since the relay
# module manages the same tenshu.app zone), `apply` will error ("domain already
# exists") — import it first, or set manage_dns=false here and add the apex A
# record alongside the relay's in whichever module/place owns the zone. See the
# how-to's "DNS when the zone is shared with the relay" note.
resource "digitalocean_domain" "zone" {
  count = var.manage_dns ? 1 : 0
  name  = local.dns_zone
}

resource "digitalocean_record" "apex_a" {
  count  = var.manage_dns ? 1 : 0
  domain = digitalocean_domain.zone[0].name
  type   = "A"
  name   = local.dns_record_name
  value  = digitalocean_reserved_ip.apex.ip_address
  ttl    = 300
}
