# The Tenshu **dev lab** — an isolated copy of the server plane on one Scaleway instance.
#
#   relay    = :sync-relay-server            (House Rule #1: ciphertext only, here too)
#   commerce = :commerce-service + Postgres  (its OWN issuer seed; a fake Stripe rail)
#   apex     = the static SPA, built against THIS relay's URL
#   caddy    = one front proxy terminating TLS for all three hostnames
#   mailpit  = an SMTP sink, so dev OTP mail never reaches a real inbox
#
# Design + rationale: docs/proposals/ops-dev-lab.md. Console walkthrough:
# docs/how-to/deploy-dev-lab-scaleway.md.
#
# ⚠⚠ Every Scaleway-specific trap below also applies to deploy/scaleway-relay. They are repeated
# here rather than referenced because each one fails SILENTLY: the apply is clean and the box comes up serving nothing.

# ⚠⚠ These are PRECONDITIONS, not a `check` block, and the difference is the whole point.
# Terraform's `check` blocks emit a **Warning** and let the plan succeed with exit 0 — they exist for
# post-apply monitoring, not for refusing bad input. Measured on this very module:
# `-var dev_domain=dev.tenshu.app -var issuer_seed=` produced two warnings, then `Plan: 4 to add`
# and exit 0. An `apply` would have built a dev plane inside the production zone signing with the
# committed public dev seed — the two failures this module exists to prevent. A `precondition` is an
# Error: the plan aborts, nothing is created, exit 1. `deploy/cloudflare-dns` already does it this
# way; ⚠ the three scaleway-* production modules still use `check` and have the same hole.
#
# ⚠ They live on the instance because its cloud-init consumes every variable guarded here, and
# because a `variable ... validation` that reads a SECOND variable needs Terraform >= 1.9, while
# versions.tf allows >= 1.6.0. Preconditions have worked since 1.2.

locals {
  relay_domain    = "relay.${var.dev_domain}"
  commerce_domain = "commerce.${var.dev_domain}"
  apex_domain     = var.dev_domain
  mail_domain     = "mail.${var.dev_domain}"

  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    git_repo_url = var.git_repo_url
    git_ref      = var.git_ref
    git_token    = var.git_token
    ghcr_user    = var.ghcr_user
    ghcr_token   = var.ghcr_token

    ssh_ca_public_key = var.ssh_ca_public_key

    dev_domain      = var.dev_domain
    relay_domain    = local.relay_domain
    commerce_domain = local.commerce_domain
    apex_domain     = local.apex_domain
    acme_email      = var.acme_email

    issuer_seed       = var.issuer_seed
    postgres_password = var.postgres_password
    # ⚠ The JDBC URL carries the password in its QUERY, which pgjdbc percent-decodes — and decodes
    # `+` to a space. The runbook mints the password with base64, whose alphabet has `+`, `/` and
    # `=`, so the raw value authenticates Postgres itself and fails commerce's own connection.
    postgres_password_urlencoded = urlencode(var.postgres_password)

    entitlement_issuer_pubkey = var.entitlement_issuer_pubkey
    entitlement_enforce       = var.entitlement_issuer_pubkey != "" ? "true" : "false"

    # A key selects the real rail. STRIPE_MODE=fake would win over a configured key, so the two
    # are never rendered together.
    stripe_mode           = var.stripe_secret_key != "" ? "real" : "fake"
    stripe_secret_key     = var.stripe_secret_key
    stripe_webhook_secret = var.stripe_webhook_secret
    stripe_price_ids      = var.stripe_price_ids

    # Unset => a plain-HTTP placeholder name, so Caddy neither serves the inbox nor asks Let's
    # Encrypt for a certificate on a hostname that may have no record.
    mail_site     = var.mailpit_token != "" ? local.mail_domain : "http://mail.disabled.invalid"
    mailpit_token = var.mailpit_token

    relay_image_tag    = var.relay_image_tag
    commerce_image_tag = var.commerce_image_tag
    apex_image_tag     = var.apex_image_tag
  })
}

# ---- Durable data plane -----------------------------------------------------
# Relay SQLite + blobs, the commerce Postgres, and (DL-3) redroid's /data. Survives an instance
# rebuild — the instance is cattle, this is not.
#
# ⚠⚠ `iops` is REQUIRED, unlike DigitalOcean's volumes. It is also the field most likely to fail an
# apply: accepted values are tier-dependent, so a rejected number surfaces at create, not at plan.
resource "scaleway_block_volume" "dev_data" {
  name       = "tenshu-dev-data"
  size_in_gb = var.data_volume_size_gb
  iops       = var.data_volume_iops
  zone       = var.zone
  project_id = var.project_id
  tags       = ["tenshu", "dev-lab", "durable"]
}

# ---- A stable address -------------------------------------------------------
# Held separately from the server so a rebuild does not move the address the DNS records point at.
resource "scaleway_instance_ip" "dev" {
  zone       = var.zone
  project_id = var.project_id
}

# ---- Firewall ---------------------------------------------------------------
# ⚠⚠ `inbound_default_policy = "drop"` is the whole point: Scaleway security groups default to
# ACCEPT, so omitting this line produces a box with every port open and no error anywhere.
#
# ⚠ Note what is NOT opened: mailpit's UI (8025) and Postgres (5432) are deliberately absent. The
# compose file binds mailpit to loopback and reaches it over `ssh -L`; an unauthenticated inbox on
# the public internet is not a thing a dev plane should have.
resource "scaleway_instance_security_group" "dev" {
  name                    = "tenshu-dev-lab"
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

  # 80 is not optional even if you only use 443: Caddy's ACME HTTP-01 challenge needs it, and
  # closing it breaks issuance and renewal silently until the certificate expires.
  inbound_rule {
    action = "accept"
    port   = 80
  }

  inbound_rule {
    action = "accept"
    port   = 443
  }
}

# ---- The instance -----------------------------------------------------------
resource "scaleway_instance_server" "dev" {
  name       = "tenshu-dev-lab"
  type       = var.instance_type
  image      = var.instance_image
  zone       = var.zone
  project_id = var.project_id
  tags       = ["tenshu", "dev-lab"]

  ip_id             = scaleway_instance_ip.dev.id
  security_group_id = scaleway_instance_security_group.dev.id

  # ⚠⚠ The ROOT volume, not the data volume — Docker image layers extract here, and this box has
  # six images to the relay box's one. See variables.tf.
  root_volume {
    size_in_gb            = var.root_volume_size_gb
    delete_on_termination = true
  }

  # ⚠ Attached here rather than by a separate resource, and it must be in the SAME ZONE — a
  # cross-zone id fails at create.
  additional_volume_ids = [scaleway_block_volume.dev_data.id]

  # ⚠⚠ Scaleway takes cloud-init under the "cloud-init" key of a user_data MAP, where DigitalOcean
  # and OCI take the document directly. Passing a bare string here does NOT error — the box boots
  # clean and nothing is installed. This is the nastiest failure in the whole module.
  user_data = {
    cloud-init = local.user_data
  }

  lifecycle {
    # ⚠⚠ THE isolation guard. A dev plane under the production zone is one typo away from a client
    # that reaches production without failing — which is the exact failure the separate domain
    # exists to make loud (ops-dev-lab.md §4).
    precondition {
      condition     = var.dev_domain != var.production_domain && !endswith(var.dev_domain, ".${var.production_domain}")
      error_message = "dev_domain must not be the production zone or a subdomain of it. Use a separate registrable domain (e.g. tenshu.xyz) — a `dev-` prefix under production is one typo away from a client that silently reaches the real relay."
    }

    # ⚠ The repo is cloned even when images are prebuilt, and it carries more of the stack than the
    # images do: the box runs the two production composes plus deploy/scaleway-dev/stack/, so the
    # clone is the only source of the relay's and commerce's service definitions.
    precondition {
      condition     = var.git_repo_url != ""
      error_message = "git_repo_url is empty. The box clones the repo even when pulling prebuilt images, because sync-relay-server/docker-compose.yml, commerce-service/docker-compose.yml and deploy/scaleway-dev/stack/ live there — an empty URL fails as `fatal: repository '' does not exist`."
    }

    precondition {
      condition     = var.git_token != ""
      error_message = "git_token is empty and the repo is private. A fine-grained PAT, this repo only, Contents: read."
    }

    # ⚠⚠ Let's Encrypt refuses example.com outright, and the failure is a TLS handshake with no
    # certificate — never an error at apply.
    precondition {
      condition     = var.acme_email != "" && !endswith(var.acme_email, "@example.com")
      error_message = "acme_email must be a real address. Let's Encrypt rejects example.com, so Caddy never obtains a certificate and HTTPS fails at the TLS handshake."
    }

    # ⚠⚠ commerce runs DEV_MODE=**true** on this plane — the only way to get STRIPE_MODE=fake past
    # CommerceConfig.validate(), which refuses the pair outright — so nothing at RUNTIME forces a
    # real seed: an empty one would silently fall back to the committed public dev seed, whose private
    # half is in the repo. That makes this assert the ONLY thing standing between the dev plane and a
    # mint key anyone can forge against: a guard, not a convenience.
    precondition {
      condition     = var.issuer_seed != ""
      error_message = "issuer_seed is empty. commerce runs DEV_MODE=true here (the fake Stripe rail requires it), so an empty seed falls back to the COMMITTED public dev seed rather than refusing to start. Generate a NEW one — never production's: head -c 32 /dev/urandom | base64"
    }

    precondition {
      condition     = var.postgres_password != ""
      error_message = "postgres_password is empty. The commerce Postgres needs one; any random string will do, it never leaves the compose network."
    }

    # ⚠⚠ commerce runs DEV_MODE=true here, so nothing at runtime would refuse a LIVE key: this is
    # the only guard between the dev plane and real charges.
    precondition {
      condition     = var.stripe_secret_key == "" || can(regex("^(sk|rk)_test_", var.stripe_secret_key))
      error_message = "stripe_secret_key is not a TEST key (sk_test_… / rk_test_…). The dev plane must never hold a live Stripe key."
    }

    # commerce refuses to start with the key but no webhook secret (T-CB-07); the reverse, and a
    # missing price map, start fine and fail at the first checkout instead.
    precondition {
      condition     = (var.stripe_secret_key == "") == (var.stripe_webhook_secret == "") && (var.stripe_secret_key == "") == (var.stripe_price_ids == "")
      error_message = "Set ALL of stripe_secret_key, stripe_webhook_secret and stripe_price_ids, or none of them (none => the fake rail)."
    }

    # The token is the only thing between the internet and every sign-up code this plane sends, and
    # it travels through an `--env-file`, so it is held to a shape that is both long and inert
    # there. (Caddy reads it as a runtime placeholder — it is not spliced into the expression.)
    precondition {
      condition     = var.mailpit_token == "" || can(regex("^[A-Za-z0-9_-]{32,}$", var.mailpit_token))
      error_message = "mailpit_token must be at least 32 characters of [A-Za-z0-9_-] (openssl rand -hex 24), or empty to leave the mail endpoint off."
    }

    # A malformed key does not stop the relay from starting with enforcement on — it starts and
    # refuses every token, which reads as a client bug.
    precondition {
      condition     = var.entitlement_issuer_pubkey == "" || can(regex("^[A-Za-z0-9+/]{43}=$", var.entitlement_issuer_pubkey))
      error_message = "entitlement_issuer_pubkey is not a base64 Ed25519 public key (44 characters ending in '='). Copy it exactly from: /opt/tenshu/compose logs commerce | grep -i 'public key'"
    }

    # dev.env is a compose --env-file: `$` is interpolated, quotes are stripped, and whitespace or
    # `#` ends the value. Postgres would be initialised with one password and handed another.
    precondition {
      condition     = !can(regex("[$#'\"\\\\\\s]", var.postgres_password))
      error_message = "postgres_password contains a character compose's env file does not pass through verbatim ($, #, a quote, a backslash or whitespace). Generate one without them: head -c 24 /dev/urandom | base64"
    }
  }
}

# ⚠⚠ A user_data change is an IN-PLACE update with this provider (2.83.1), NOT a replacement
# (measured on a rebuild). Cloud-init runs once per
# instance, so a plain apply after touching the template (or rotating a secret it renders) changes
# the stored user_data and nothing on the box. To make the box actually boot from it:
#   ./tf.sh apply -replace=scaleway_instance_server.dev
# That DESTROYS the instance; the IP and the data volume are separate resources and survive. What
# does not: the shipped images and dev.env (ops-dev-lab.md §10.11).

# ---- DNS --------------------------------------------------------------------
# ⚠⚠ Defaults ON here, and that deliberately breaks the production pattern. Production keeps its
# records in a provider-neutral inventory (deploy/dns/records.json) behind a host adapter
# (deploy/cloudflare-dns), so no service module owns a record and a host change is a new adapter.
# That split earns its keep for a zone that serves mail. A throwaway zone with three A records and
# no rollback story does not need it — so the module that creates the box creates its records.
#
# ⚠ Grow records worth keeping and this belongs in the inventory instead. Never declare a record
# here that another module also declares: two owners in two states fight on every plan.
#
# ⚠ The zone must already exist at Scaleway (console → Domains and DNS → add an external domain)
# and the registrar's nameservers must point there. The provider manages RECORDS, never the zone:
# turning this on against a zone Scaleway does not serve creates records nothing resolves.
#
# ⚠⚠ "" is the ZONE ROOT, and it has to be spelled that way. `trimsuffix("tenshu.xyz",
# ".tenshu.xyz")` returns the input unchanged, so a naive derivation would create a record named
# `tenshu.xyz` INSIDE the zone — i.e. `tenshu.xyz.tenshu.xyz`. Silent, and visible only as "the
# apex does not resolve". The production modules have the same trap.
resource "scaleway_domain_record" "apex" {
  count = var.manage_dns ? 1 : 0

  dns_zone = var.dev_domain
  name     = ""
  type     = "A"
  data     = scaleway_instance_ip.dev.address
  ttl      = 300
}

resource "scaleway_domain_record" "relay" {
  count = var.manage_dns ? 1 : 0

  dns_zone = var.dev_domain
  name     = "relay"
  type     = "A"
  data     = scaleway_instance_ip.dev.address
  ttl      = 300
}

resource "scaleway_domain_record" "mail" {
  count = var.manage_dns && var.mailpit_token != "" ? 1 : 0

  dns_zone = var.dev_domain
  name     = "mail"
  type     = "A"
  data     = scaleway_instance_ip.dev.address
  ttl      = 300
}

resource "scaleway_domain_record" "drive" {
  count = var.manage_dns ? 1 : 0

  dns_zone = var.dev_domain
  name     = "drive"
  type     = "A"
  data     = scaleway_instance_ip.dev.address
  ttl      = 300
}

resource "scaleway_domain_record" "contacts" {
  count = var.manage_dns ? 1 : 0

  dns_zone = var.dev_domain
  name     = "contacts"
  type     = "A"
  data     = scaleway_instance_ip.dev.address
  ttl      = 300
}

resource "scaleway_domain_record" "commerce" {
  count = var.manage_dns ? 1 : 0

  dns_zone = var.dev_domain
  name     = "commerce"
  type     = "A"
  data     = scaleway_instance_ip.dev.address
  ttl      = 300
}
