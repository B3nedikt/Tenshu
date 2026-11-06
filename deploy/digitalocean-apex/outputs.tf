output "apex_ip" {
  description = "Reserved public IP of the apex droplet. Point your DNS A record here if manage_dns=false."
  value       = digitalocean_reserved_ip.apex.ip_address
}

output "apex_url" {
  description = "HTTPS URL the frontend is served on once DNS + TLS are live."
  value       = "https://${var.domain}"
}

output "droplet_id" {
  description = "DO droplet ID (for `doctl compute ssh <id>` etc.)."
  value       = digitalocean_droplet.apex.id
}

output "first_boot_hint" {
  description = "Reminder that the first boot builds (or pulls) the image and obtains a cert; give it a few minutes."
  value       = "First boot clones + builds (or pulls) the apex image and provisions a TLS cert. Allow ~3-8 min (on-box build) or ~1-2 min (prebuilt), then check: curl -fsS https://${var.domain}/ (expect the apex HTML). Watch progress: ssh root@${var.domain} 'tail -f /var/log/tenshu-bootstrap.log' or 'docker compose -f /opt/tenshu/repo/web/apex/docker-compose.yml logs -f'"
}
