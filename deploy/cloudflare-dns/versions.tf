# Provider + Terraform pins for the tenshu.app zone on Cloudflare DNS.

terraform {
  # `strcontains` (1.5) and `startswith`/`endswith` (1.3) are used by the inventory checks.
  required_version = ">= 1.6.0"

  required_providers {
    cloudflare = {
      source = "cloudflare/cloudflare"
      # ⚠ v5 is a rewrite, not a bump: `cloudflare_record` became `cloudflare_dns_record`, `name`
      # must be the FQDN, and long TXT content drifts unless pre-split. main.tf handles all three
      # for 5.25 — re-check them before moving this pin.
      version = "~> 5.25"
    }
  }
}

# ⚠ null falls through to the CLOUDFLARE_API_TOKEN env var, which is the better place for it.
provider "cloudflare" {
  api_token = var.api_token
}
