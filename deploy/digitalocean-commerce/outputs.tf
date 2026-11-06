output "commerce_ip" {
  description = "Reserved public IP of the commerce issuer. Point your DNS A record here if manage_dns=false."
  value       = digitalocean_reserved_ip.commerce.ip_address
}

output "commerce_url" {
  description = "HTTPS base URL clients should be pointed at (TENSHU_COMMERCE_URL) once DNS + TLS are live."
  value       = "https://${var.domain}"
}

output "droplet_id" {
  description = "DO droplet ID (for `doctl compute ssh <id>` etc.)."
  value       = digitalocean_droplet.commerce.id
}

output "first_boot_hint" {
  description = "Reminder: first boot builds/pulls the issuer, stands up Postgres, and obtains a cert; give it a few minutes, then grab the issuer public key for the relay."
  value       = <<-EOT
    First boot clones the repo, brings up Postgres + the issuer + Caddy, and provisions a TLS cert.
    Allow ~2-8 min (longer on an on-box build), then:
      curl -fsS https://${var.domain}/            # issuer liveness
    Read the issuer PUBLIC key (set the relay's ENTITLEMENT_ISSUER_PUBKEY to it):
      ssh root@${var.domain} "docker compose -f /opt/tenshu/repo/commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env logs commerce | grep -i 'issuer public key'"
    Watch progress:
      ssh root@${var.domain} 'tail -f /var/log/tenshu-bootstrap.log'
  EOT
}
