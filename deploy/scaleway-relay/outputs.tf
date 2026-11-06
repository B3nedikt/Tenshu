output "relay_ip" {
  description = "The flexible public IP. Point var.domain's A record here when manage_dns = false."
  value       = scaleway_instance_ip.relay.address
}

output "server_id" {
  description = "Instance ID, for `scw instance server ...` or the console."
  value       = scaleway_instance_server.relay.id
}

output "ssh_hint" {
  description = "How to get on the box to read cloud-init's log."
  value       = "ssh root@${scaleway_instance_ip.relay.address}   # then: cloud-init status --long; tail -n 200 /var/log/cloud-init-output.log"
}

output "dns_hint" {
  description = "What to do about DNS."
  value       = <<-EOT
    A  ${var.domain}  ->  ${scaleway_instance_ip.relay.address}

    ⚠ manage_dns is ${var.manage_dns}. Leave it false: Scaleway can only serve this record if the zone
    is HOSTED there, and tenshu.app moves from DigitalOcean to Cloudflare DNS instead. Create the A
    record at DigitalOcean until the move, in deploy/dns/records.json after it.
  EOT
}

output "verify_hint" {
  description = "What to check once it is up — in order, because a green health check with the wrong volume mounted is the failure that costs data."
  value       = <<-EOT
    1. cloud-init finished:  ssh root@${scaleway_instance_ip.relay.address} 'cloud-init status --long'
    2. ⚠⚠ the DURABLE volume is mounted, not the root disk:
         ssh root@${scaleway_instance_ip.relay.address} 'findmnt /mnt/relay_data'
       An empty result means the relay is writing to a disk an instance rebuild destroys.
    3. the relay answers:    curl -fsS https://${var.domain}/health
  EOT
}
