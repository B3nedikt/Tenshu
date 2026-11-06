# Tenshu commerce on Scaleway — the twin of `deploy/digitalocean-commerce/`.
#
#   volume  = block storage for the commerce DB (survives an instance rebuild)
#   ip      = a flexible public IP, so a rebuild keeps the address the DNS record points at
#   sg      = security group: 22 (narrowable), 80 + 443 open for Caddy's ACME + serving
#   server  = the instance; cloud-init installs Docker, mounts the volume, brings commerce up
#   record  = (optional, leave off) an A record, only if the zone were hosted at Scaleway — it is not
#
# ⚠⚠ This is PRODUCTION commerce (in production since 2026-08-07, on this module since 2026-09-14): a
# mistaken apply is a billing outage, not a test failure. It was stood up BESIDE
# `deploy/digitalocean-commerce/` (now retired) rather than converted in place — see versions.tf.
#
# ⭐ SMTP needs no special handling: `enable_default_security` blocks 25/465/587, and this deploy
# uses 2587, which is not on that list. ⚠⚠ The trap is "tidying" the port to 587 — the value
# Scaleway's own TEM docs recommend — which would silently fail here while still working on DO.
# See variables.tf.

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

  # The bundled Postgres (default) or an external managed DB when database_url is set.
  #
  # ⚠ Carried over from the DO module unchanged. Scaleway does offer Managed Databases, and pointing
  # `database_url` at one would be a real improvement over a Postgres container sharing the box —
  # but that is a different decision from changing provider, and doing both at once would make a
  # failed deploy ambiguous between the two.
  database_url = var.database_url != "" ? var.database_url : "jdbc:postgresql://db:5432/commerce?user=commerce&password=${var.database_password}"

  # ⚠ The DO module hardcodes a `scsi-0DO_Volume_…` by-id path. That has no Scaleway equivalent, so
  # the shared cloud-init now derives the data disk from the ROOT device instead — provider-neutral,
  # and correct on both. See cloud-init.yaml.tftpl.
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    swapfile_size_gb = var.swapfile_size_gb != null ? var.swapfile_size_gb : (var.commerce_image_tag == "" ? 4 : 1)
    git_repo_url     = var.git_repo_url
    git_ref          = var.git_ref
    git_token        = var.git_token

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

    account_closure_relay_url = var.account_closure_relay_url

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
    web_app_origin        = var.web_app_origin
    # base64, because the key is multi-line JSON and a deploy.env line is not.
    google_play_service_account_json_b64 = var.google_play_service_account_json == "" ? "" : base64encode(var.google_play_service_account_json)
    google_play_rtdn_token               = var.google_play_rtdn_token

    review_signin_accounts = var.review_signin_accounts
    review_signin_hmac_key = var.review_signin_hmac_key
  })
}

# ---- Durable data plane ----------------------------------------------------
# ⚠⚠ `iops` is REQUIRED here, unlike DigitalOcean's volumes which expose no such knob. It is also
# the field most likely to fail an apply: the accepted values are tier-dependent, so a rejected
# number surfaces at create time, not at plan.
resource "scaleway_block_volume" "commerce_data" {
  name       = "tenshu-commerce-data"
  size_in_gb = var.data_volume_size_gb
  iops       = var.data_volume_iops
  zone       = var.zone
  project_id = var.project_id
  tags       = ["tenshu", "commerce", "durable"]
}

# ---- A stable address ------------------------------------------------------
# ⚠ Held separately from the server so an instance rebuild does not change the IP the DNS record
# points at — the same reason the DO module uses a reserved IP rather than the droplet's own.
resource "scaleway_instance_ip" "commerce" {
  zone       = var.zone
  project_id = var.project_id
}

# ---- Firewall --------------------------------------------------------------
# ⚠⚠ `inbound_default_policy = "drop"` is the whole point: Scaleway security groups default to
# ACCEPT, so omitting this line produces a box with every port open and no error anywhere.
resource "scaleway_instance_security_group" "commerce" {
  name                    = "tenshu-commerce"
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
resource "scaleway_instance_server" "commerce" {
  name       = "tenshu-commerce"
  type       = var.instance_type
  image      = var.instance_image
  zone       = var.zone
  project_id = var.project_id
  tags       = ["tenshu", "commerce"]

  ip_id             = scaleway_instance_ip.commerce.id
  security_group_id = scaleway_instance_security_group.commerce.id

  # ⚠ The durable volume is attached here rather than by a separate attachment resource. It must be
  # in the SAME ZONE as the server; a cross-zone id fails at create.
  additional_volume_ids = [scaleway_block_volume.commerce_data.id]

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
    # been deleted from GHCR and kept working only on its local Docker cache — the pull 404s on any
    # rebuild. Terraform cannot check a registry, so this only reminds; verify with:
    #   curl -sH "Authorization: Bearer $(printf %s "$PAT" | base64)" \
    #        https://ghcr.io/v2/b3nedikt/tenshu-commerce/tags/list
    # Commerce itself refuses to start half-configured (CommerceConfig.validate); refusing the PLAN
    # catches it before a box boots into a crash loop.
    precondition {
      condition     = (var.review_signin_accounts == "") == (var.review_signin_hmac_key == "")
      error_message = "review_signin_accounts and review_signin_hmac_key must be set together (or both left empty = review sign-in off). commerce refuses to start otherwise."
    }

    precondition {
      condition     = var.commerce_image_tag != "" || var.git_repo_url != ""
      error_message = "Set commerce_image_tag to pull a prebuilt image, or leave it empty to build on the box (needs the bigger instance and the swapfile)."
    }
  }
}

# ---- DNS (optional) --------------------------------------------------------
# ⚠⚠ Leave this OFF. It only works when the zone is HOSTED at Scaleway, and tenshu.app moves from
# DigitalOcean to Cloudflare DNS instead (docs/how-to/move-dns-to-cloudflare.md) — turning it on
# creates a record nothing resolves. The A record lives wherever the zone is served.
resource "scaleway_domain_record" "commerce" {
  count = var.manage_dns ? 1 : 0

  dns_zone = local.dns_zone
  name     = local.dns_record_name
  type     = "A"
  data     = scaleway_instance_ip.commerce.address
  ttl      = 300
}
