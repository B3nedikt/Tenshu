output "dev_ip" {
  value       = scaleway_instance_ip.dev.address
  description = "The flexible public IP. Survives an instance rebuild."
}

output "ssh" {
  value       = "ssh root@${scaleway_instance_ip.dev.address}"
  description = "⚠ Scaleway's Ubuntu images log in as root (unlike OCI's, which disable it). ⚠⚠ Only works if your key was on the PROJECT before first boot — see README."
}

output "relay_url" {
  value = "https://relay.${var.dev_domain}"
}

output "commerce_url" {
  value = "https://commerce.${var.dev_domain}"
}

output "apex_url" {
  value = "https://${var.dev_domain}"
}

output "dns_records_needed" {
  description = "The three records to add at Cloudflare (the default path). With manage_dns on, Terraform owns these three."
  value = var.manage_dns ? ["(managed by this module)"] : [
    "${var.dev_domain}          A  ${scaleway_instance_ip.dev.address}",
    "relay.${var.dev_domain}    A  ${scaleway_instance_ip.dev.address}",
    "commerce.${var.dev_domain} A  ${scaleway_instance_ip.dev.address}",
  ]
}

output "mail_endpoint" {
  description = "Read-only mailpit API for sessions with no SSH. Needs a DNS-only A record for the hostname. ⚠ What the TEMPLATE renders — a running box keeps the token it booted with until dev.env is edited there (runbook §10)."
  # nonsensitive: the output says only WHETHER a token is set, never what it is.
  value = nonsensitive(var.mailpit_token != "") ? "https://${local.mail_domain}/api/v1/messages  (Authorization: Bearer <dev-mailpit-token>)" : "off (no mailpit_token)"
}

output "mailpit_tunnel" {
  description = "The dev SMTP sink's web UI is bound to loopback — reach it through SSH, never a port."
  value       = "ssh -L 8025:127.0.0.1:8025 root@${scaleway_instance_ip.dev.address}  # then http://localhost:8025"
}

# ⚠⚠ A green /health is NOT proof the box is right — deploy/scaleway-relay/README.md learned this
# the expensive way, answering `{"status":"ok","durable":true}` for five days while holding zero
# rows. Check the mount before you believe anything.
output "verify_hint" {
  value = <<-EOT
    ssh root@${scaleway_instance_ip.dev.address} 'cloud-init status --long'   # 1. bootstrap finished
    ssh root@${scaleway_instance_ip.dev.address} 'findmnt /mnt/tenshu'        # 2. the DURABLE volume is mounted
    curl -fsS https://relay.${var.dev_domain}/health                          # 3. the relay answers
    curl -fsSI https://${var.dev_domain} | head -1                            # 4. the apex serves
  EOT
}
