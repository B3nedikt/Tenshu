output "relay_ip" {
  description = "Reserved public IP of the relay. Point your DNS A record here if manage_dns=false."
  value       = digitalocean_reserved_ip.relay.ip_address
}

output "relay_url" {
  description = "HTTPS base URL clients should be pointed at once DNS + TLS are live."
  value       = "https://${var.domain}"
}

output "droplet_id" {
  description = "DO droplet ID (for `doctl compute ssh <id>` etc.)."
  value       = digitalocean_droplet.relay.id
}

output "first_boot_hint" {
  description = "Reminder that the first boot builds the fat jar and obtains a cert; give it a few minutes."
  value       = "First boot clones + builds the relay and provisions a TLS cert. Allow ~3-8 min, then check: curl -fsS https://${var.domain}/ (expect 'Tenshu Sync Relay is running!'). Watch progress: ssh root@${var.domain} 'tail -f /var/log/tenshu-bootstrap.log' or 'docker compose -f /opt/tenshu/repo/sync-relay-server/docker-compose.yml logs -f'"
}
