output "ipv4" {
  description = "Goes into deploy/ovh-migrate/hosts.env as NEW_COMMERCE, and into deploy/dns/records.json at the switch."
  value       = module.host.ipv4
}

output "ipv6" {
  value = module.host.ipv6
}

output "verify_hint" {
  value = <<-EOT
    ssh root@${module.host.ipv4} 'cloud-init status --long; cat /opt/tenshu-host/READY; findmnt /mnt/commerce_data'
  EOT
}
