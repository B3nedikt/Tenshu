# The tenshu.app zone on Cloudflare DNS — a thin adapter over deploy/dns/records.json.
#
# ⚠⚠ Every record here is DNS-only (proxied = false), and that is a constraint, not a default.
# Relay, apex and commerce terminate TLS in their own Caddy; proxying one would put Cloudflare in that
# TLS path (sync-relay-server/Caddyfile records that as a separate, explicit decision), push Caddy's
# ACME validation through Cloudflare's proxy, cap request bodies at 100 MB on Free and end any origin
# response slower than 125 s. Tiles is the only proxied hostname in the zone, and it is not declared
# here: the R2 custom domain in deploy/cloudflare-tiles owns it.
#
# ⚠⚠ This module owns the WHOLE zone and every record in it — deliberately not one A record per
# service module. The DNS host is not the compute host: a Scaleway module cannot write a Cloudflare
# record without a second provider and a second credential. So the record set is DATA — one
# provider-neutral inventory — and the next host needs only a new adapter over the same file.
#
# ⚠ Service IPs therefore live in the inventory, not in service-module outputs. Moving a service is
# a re-export (or an edit) of records.json plus an apply here.

locals {
  inventory = jsondecode(file(var.records_file))
  records   = local.inventory.records

  # Stable for_each keys: "TYPE name", plus "#n" for the n-th record sharing both. Keyed by position
  # within that group rather than by content, so an IP change is an in-place update instead of a
  # delete-and-create that leaves the name briefly empty.
  keyed = {
    for i, r in local.records :
    (length([for j, s in local.records : j if j < i && s.type == r.type && s.name == r.name]) == 0
      ? "${r.type} ${r.name}"
      : "${r.type} ${r.name} #${length([for j, s in local.records : j if j < i && s.type == r.type && s.name == r.name]) + 1}"
    ) => r
  }

  rendered = {
    for k, r in local.keyed : k => {
      # ⚠ provider v5: `name` must be the FQDN. A bare label or "@" is accepted, then refreshed to the
      # FQDN, so every reference to it reads differently before and after the next plan.
      fqdn = r.name == "@" ? var.zone : "${r.name}.${var.zone}"
      type = r.type
      ttl  = r.ttl
      # ⚠⚠ TXT content is sent as quoted strings of at most 255 characters. Cloudflare re-splits a
      # longer single string at 255 on its side, so an unsplit 420-character DKIM key drifts on every
      # plan — and an UNQUOTED one is stored as a single over-long string. Pre-splitting matches
      # what the API returns and what DigitalOcean serves (255 + 165).
      content = (r.type == "TXT"
        ? join(" ", [for c in chunklist(split("", r.content), 255) : "\"${join("", c)}\""])
        : r.content
      )
      priority = r.type == "MX" ? r.priority : null
    }
  }

  # ---- Inventory checks ----------------------------------------------------
  # A hand-edited or half-exported inventory must fail the plan, not produce a zone that silently
  # lacks mail authentication or its apex.
  problems = concat(
    [for r in local.records : "${r.type} ${r.name}: unsupported type (A, AAAA, CNAME, MX, TXT only)" if !contains(["A", "AAAA", "CNAME", "MX", "TXT"], r.type)],
    [for r in local.records : "${r.type} ${r.name}: name must be relative to ${var.zone}, with @ for the root" if r.name == var.zone || endswith(r.name, ".${var.zone}") || endswith(r.name, ".")],
    # ⚠ The API drops a CNAME's trailing dot and provider v5 then diffs it on every plan.
    [for r in local.records : "${r.type} ${r.name}: store the target without a trailing dot" if contains(["CNAME", "MX"], r.type) && endswith(r.content, ".")],
    [for r in local.records : "TXT ${r.name}: printable ASCII only, no \" or \\ (the 255-character split counts characters, and quoting is added here)" if r.type == "TXT" && (!can(regex("^[[:print:]]*$", r.content)) || strcontains(r.content, "\"") || strcontains(r.content, "\\"))],
    [for r in local.records : "MX ${r.name}: needs a priority" if r.type == "MX" && try(r.priority, null) == null],
    [for r in local.records : "${r.type} ${r.name}: ttl must be 1 (auto) or 60-86400" if !(r.ttl == 1 || (r.ttl >= 60 && r.ttl <= 86400))],
    [for r in local.records : "${r.type} ${r.name}: tiles.service belongs to tiles_cname_target, then to the R2 custom domain — never the inventory" if r.name == "tiles.service"],
    local.inventory.zone == var.zone ? [] : ["the inventory is for ${local.inventory.zone}, not ${var.zone}"],
  )

  required = {
    "A at the root (the apex service)"   = anytrue([for r in local.records : r.type == "A" && r.name == "@"])
    "A relay"                            = anytrue([for r in local.records : r.type == "A" && r.name == "relay"])
    "A commerce.service"                 = anytrue([for r in local.records : r.type == "A" && r.name == "commerce.service"])
    "MX at the root"                     = anytrue([for r in local.records : r.type == "MX" && r.name == "@"])
    "SPF (TXT v=spf1 at the root)"       = anytrue([for r in local.records : r.type == "TXT" && r.name == "@" && startswith(r.content, "v=spf1")])
    "DKIM (TXT v=DKIM1 at *._domainkey)" = anytrue([for r in local.records : r.type == "TXT" && endswith(r.name, "._domainkey") && startswith(r.content, "v=DKIM1")])
    "DMARC (TXT v=DMARC1 at _dmarc)"     = anytrue([for r in local.records : r.type == "TXT" && r.name == "_dmarc" && startswith(r.content, "v=DMARC1")])
  }
  missing = [for label, present in local.required : label if !present]
}

# ---- The zone --------------------------------------------------------------
# ⚠ Created through the API, so it starts EMPTY and PENDING: the dashboard's onboarding scan (which
# guesses records and proxies them by default) never runs. Every record comes from the inventory.
#
# ⚠⚠ Cloudflare answers queries for a pending zone on its assigned nameservers — that is what lets
# scripts/dns-diff.sh prove the zone complete before the registrar change. A Free zone still pending
# after 28 days is deleted, so apply this close to the switch, not weeks ahead.
resource "cloudflare_zone" "this" {
  account = {
    id = var.account_id
  }
  name = var.zone
  type = "full"

  lifecycle {
    # ⚠⚠ Once the nameservers point here, destroying this is the whole domain going dark — relay,
    # apex, commerce, mail and tiles at once.
    prevent_destroy = true

    precondition {
      condition     = length(local.problems) == 0
      error_message = "The inventory has problems:\n  ${join("\n  ", local.problems)}"
    }
    precondition {
      condition     = length(local.missing) == 0
      error_message = "The inventory is missing records a working tenshu.app needs:\n  ${join("\n  ", local.missing)}\nRe-export with scripts/dns-export.sh rather than adding them by hand."
    }
  }
}

# ---- The records -----------------------------------------------------------
resource "cloudflare_dns_record" "inventory" {
  for_each = local.rendered

  zone_id  = cloudflare_zone.this.id
  name     = each.value.fqdn
  type     = each.value.type
  content  = each.value.content
  ttl      = each.value.ttl
  priority = each.value.priority

  # ⚠⚠ DNS-only, and not a variable on purpose — see the header.
  proxied = false
}

# ---- tiles.service, until the R2 swap --------------------------------------
# ⚠⚠ DNS-only while it points at DigitalOcean's CDN, which is itself a Cloudflare for SaaS hostname
# on DigitalOcean's account. Proxying this record would switch that path to "orange-to-orange"
# routing through this zone first — a different request path from the one serving tiles today.
resource "cloudflare_dns_record" "tiles_cname" {
  count = var.tiles_cname_target != "" ? 1 : 0

  zone_id = cloudflare_zone.this.id
  name    = "tiles.service.${var.zone}"
  type    = "CNAME"
  content = trimsuffix(var.tiles_cname_target, ".")
  ttl     = 300
  proxied = false
}
