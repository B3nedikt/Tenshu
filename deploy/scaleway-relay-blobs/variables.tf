variable "access_key" {
  description = "Scaleway API access key. Doubles as the S3 access key (see versions.tf)."
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "Scaleway API secret key. Doubles as the S3 secret key."
  type        = string
  sensitive   = true
}

variable "project_id" {
  description = "Scaleway Project ID. Every resource is scoped to a Project; there is no DO analogue."
  type        = string
}

variable "region" {
  description = <<-EOT
    Object Storage region, e.g. fr-par, nl-ams, pl-waw.
    ⚠ Put this where the RELAY is (fr-par, deploy/scaleway-relay), or every chunk PUT/GET crosses
    regions or providers — see README.md.
  EOT
  type        = string
  default     = "fr-par"
}

variable "bucket_name" {
  description = <<-EOT
    Bucket holding the relay's content-plane blobs. ⚠⚠ Globally unique across Scaleway, and NOT
    re-creatable under the same name for a while after deletion — pick one you can live with.
  EOT
  type        = string
}
