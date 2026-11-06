# Tenshu apex on Scaleway — the twin of `deploy/digitalocean-apex/`.
#
#   ip      = a flexible public IP, so a rebuild keeps the address the DNS record points at
#   sg      = security group: 22 (narrowable), 80 + 443 open for Caddy's ACME + serving
#   server  = the instance; cloud-init installs Docker and brings the apex up
#   record  = (optional, leave off) an A record, only if the zone were hosted at Scaleway — it is not
#
# ⚠⚠ PARALLEL to the live DO apex, not a replacement — see versions.tf. Stand up, verify, cut DNS,
# then destroy the old one. In that order.
#
# ⚠⚠ STATELESS ON PURPOSE — no block volume, matching `deploy/digitalocean-apex/`. If a future
# apex needs durable state, take the volume + provider-neutral mount from `deploy/scaleway-relay/`
# rather than writing to the root disk, which an instance rebuild destroys.

# ⚠⚠ PRECONDITIONS, not a `check` block — the difference is the whole point.
#
# Terraform's `check` blocks emit a **Warning** and let the plan succeed with exit 0; they exist for
# post-apply monitoring, not for refusing bad input. That was measured on `deploy/scaleway-dev`,
# which carried an identical block: every one of its seven guards, fed exactly the input it exists
# to reject, produced a warning and then `Plan: N to add` with exit 0 (`ops-dev-lab.md` §10.7). A
# `precondition` is an Error instead: the plan aborts, nothing is created, exit 1.
#
# ⚠ They live on the instance because its cloud-init consumes every variable guarded here, and
# because a `variable ... validation` that reads a SECOND variable needs Terraform >= 1.9 while
# versions.tf allows >= 1.6.0. Preconditions have worked since 1.2.
#
# Every one of these was a real failure on the first Scaleway deploy. Terraform reported a clean
# apply each time and the box came up serving nothing.

locals {
  # ⚠ Derive the registrable apex when dns_zone is not pinned — same shape as the DO modules.
  dns_zone = var.dns_zone != "" ? var.dns_zone : join(".", slice(split(".", var.domain), length(split(".", var.domain)) - 2, length(split(".", var.domain))))

  # ⚠⚠ "" is the ZONE ROOT, and this conditional is why. `trimsuffix("tenshu.app", ".tenshu.app")`
  # returns the input unchanged — the string does not end with the separator — so a root-level
  # service (the apex IS tenshu.app) would otherwise create a record named `tenshu.app` INSIDE the
  # zone, i.e. `tenshu.app.tenshu.app`. Silent, and only visible as "the name does not resolve".
  dns_record_name = var.domain == local.dns_zone ? "" : trimsuffix(var.domain, ".${local.dns_zone}")

  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    swapfile_size_gb  = var.swapfile_size_gb != null ? var.swapfile_size_gb : (var.apex_image_tag == "" ? 4 : 1)
    apex_domain       = var.domain
    relay_url         = var.relay_url
    construction_code = var.construction_code
    signup_disabled   = var.signup_disabled
    acme_email        = var.acme_email
    apex_image_tag    = var.apex_image_tag
    git_repo_url      = var.git_repo_url
    git_ref           = var.git_ref
    git_token         = var.git_token
    ghcr_user         = var.ghcr_user
    ghcr_token        = var.ghcr_token
  })
}

# ---- A stable address ------------------------------------------------------
# ⚠ Held separately from the server so an instance rebuild does not change the IP the DNS record
# points at — the same reason the DO module uses a reserved IP rather than the droplet's own.
resource "scaleway_instance_ip" "apex" {
  zone       = var.zone
  project_id = var.project_id
}

# ---- Firewall --------------------------------------------------------------
# ⚠⚠ `inbound_default_policy = "drop"` is the whole point: Scaleway security groups default to
# ACCEPT, so omitting this line produces a box with every port open and no error anywhere.
resource "scaleway_instance_security_group" "apex" {
  name                    = "tenshu-apex"
  zone                    = var.zone
  project_id              = var.project_id
  inbound_default_policy  = "drop"
  outbound_default_policy = "accept"

  dynamic "inbound_rule" {
    for_each = var.ssh_allowed_cidrs
    content {
      action   = "accept"
      port     = 22
      ip_range = inbound_rule.value
    }
  }

  # Caddy needs 80 for the ACME HTTP-01 challenge as well as 443 for serving — closing 80 breaks
  # certificate issuance and renewal, which fails silently until the cert expires.
  inbound_rule {
    action = "accept"
    port   = 80
  }

  inbound_rule {
    action = "accept"
    port   = 443
  }
}

# ---- The instance ----------------------------------------------------------
resource "scaleway_instance_server" "apex" {
  name       = "tenshu-apex"
  type       = var.instance_type
  image      = var.instance_image
  zone       = var.zone
  project_id = var.project_id
  tags       = ["tenshu", "apex"]

  ip_id             = scaleway_instance_ip.apex.id
  security_group_id = scaleway_instance_security_group.apex.id

  # ⚠⚠ Scaleway takes cloud-init under the "cloud-init" key of a user_data MAP, where DigitalOcean
  # takes the document directly as `user_data`. Passing the document as a bare string here silently
  # does nothing — the box boots clean and nothing is installed.
  user_data = {
    cloud-init = local.user_data
  }

  lifecycle {
    # ⚠⚠ The repo is ALWAYS required — including with a prebuilt image, because the COMPOSE FILE lives
    # in it. An earlier version of this check said "image tag OR repo url", which is false and would
    # have waved through exactly the config that failed.
    precondition {
      condition     = var.git_repo_url != ""
      error_message = "git_repo_url is empty. The box clones the repo even when pulling a prebuilt image, because web/*/docker-compose.yml lives there — an empty URL fails as `fatal: repository '' does not exist`."
    }

    # ⚠ The repo is PRIVATE today. Without a token the clone fails as `could not read Username for
    # 'https://github.com'`, which reads like a prompt problem rather than a missing credential.
    # ⚠ Conditioned on `git_repo_is_private` and not unconditional: cloud-init clones anonymously
    # when the token is empty, and `git_token`'s own description calls that valid for a public repo.
    # A hard refusal of it would have to be deleted the day the repo opens — by whoever is mid-deploy.
    precondition {
      condition     = !var.git_repo_is_private || var.git_token != ""
      error_message = "git_token is empty and git_repo_is_private is true. Supply the token, or set git_repo_is_private = false if the repo really is public (the box then clones anonymously)."
    }

    # ⚠⚠ Let's Encrypt REFUSES example.com outright (`invalidContact ... forbidden domain`). The
    # placeholder in the example tfvars could never have worked: the container comes up, serves
    # locally, and every HTTPS request dies at TLS with no certificate ever issued.
    precondition {
      condition     = var.acme_email != "" && !endswith(var.acme_email, "@example.com")
      error_message = "acme_email must be a real address. Let's Encrypt rejects example.com, so Caddy never obtains a certificate and HTTPS fails at the TLS handshake."
    }

    # ⚠ A prebuilt tag is not proof the tag EXISTS. The DigitalOcean deploy pinned one that had since
    # vanished from GHCR and kept working only on its local Docker cache — the pull 404s on any
    # rebuild. Terraform cannot query a registry, so nothing here can assert it; verify by hand:
    #   curl -sH "Authorization: Bearer $(printf %s "$PAT" | base64)" \
    #        https://ghcr.io/v2/b3nedikt/tenshu-apex/tags/list
    #
    # ⚠⚠ What CAN be asserted is the on-box build path's silent failure mode. `construction_code` is
    # inlined by Vite at BUILD time, so with no pinned tag an empty one bakes an SPA with NO
    # pre-launch gate — a wide-open apex that looks identical to a working deploy. On the pinned path
    # the value here is inert (the image already carries it), which is exactly why the omission
    # survives review: it costs nothing until the day the tag is cleared.
    precondition {
      condition     = var.apex_image_tag != "" || var.construction_code != ""
      error_message = "apex_image_tag is empty, so the box builds the SPA itself — but construction_code is empty too, which bakes an apex with no pre-launch gate. Set construction_code, or pin an image tag that already carries one."
    }
  }
}

# ---- DNS (optional) --------------------------------------------------------
# ⚠⚠ Leave this OFF. It only works when the zone is HOSTED at Scaleway, and tenshu.app moves from
# DigitalOcean to Cloudflare DNS instead (docs/how-to/move-dns-to-cloudflare.md) — turning it on
# creates a record nothing resolves. The A record lives wherever the zone is served.
resource "scaleway_domain_record" "apex" {
  count = var.manage_dns ? 1 : 0

  dns_zone = local.dns_zone
  name     = local.dns_record_name
  type     = "A"
  data     = scaleway_instance_ip.apex.address
  ttl      = 300
}
