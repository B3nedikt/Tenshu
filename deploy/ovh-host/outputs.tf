output "ipv4" {
  description = "Public IPv4 of the instance. ⚠ Not a floating IP: it survives reboots and user_data reinstalls, but a REPLACED instance gets a new one, and the DNS records must follow."
  value       = local.public_ipv4
}

output "ipv6" {
  description = "Public IPv6, if OVH assigned one."
  value       = local.public_ipv6
}

output "instance_id" {
  value = ovh_cloud_instance.this.id
}

output "volume_id" {
  value = try(ovh_cloud_storage_block_volume.data[0].id, null)
}
