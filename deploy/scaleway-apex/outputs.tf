output "relay_ip" {
  description = "The flexible public IP. Point var.domain's A record here when manage_dns = false."
  value       = scaleway_instance_ip.apex.address
}

output "server_id" {
  description = "Instance ID, for `scw instance server ...` or the console."
  value       = scaleway_instance_server.apex.id
}

output "ssh_hint" {
  description = "How to get on the box to read cloud-init's log."
  value       = "ssh root@${scaleway_instance_ip.apex.address}   # then: cloud-init status --long; tail -n 200 /var/log/cloud-init-output.log"
}

output "dns_hint" {
  description = "What to do about DNS."
  value       = <<-EOT
    A  ${var.domain}  ->  ${scaleway_instance_ip.apex.address}

    ⚠ manage_dns is ${var.manage_dns}. Leave it false: Scaleway can only serve this record if the zone
    is HOSTED there, and tenshu.app moves from DigitalOcean to Cloudflare DNS instead. Create the A
    record at DigitalOcean until the move, in deploy/dns/records.json after it.
  EOT
}

output "verify_hint" {
  description = "What to check once it is up."
  value       = <<-EOT
    1. cloud-init finished:  ssh root@${scaleway_instance_ip.apex.address} 'cloud-init status --long'
    2. the apex answers:     curl -fsS https://${var.domain}/health

    ⚠ There is no volume step here: the apex is stateless by design, so unlike the relay there is
    no "healthy but writing to the wrong disk" failure to check for.
  EOT
}
