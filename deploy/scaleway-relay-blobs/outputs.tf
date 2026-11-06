output "s3_settings_for_deploy_env" {
  description = <<-EOT
    Paste into the relay's /opt/tenshu/deploy.env. ⚠⚠ All four or none: a PARTIAL S3 configuration
    makes the relay REFUSE TO START rather than silently fall back to the volume — deliberate, after
    a deploy that looked successful while writing to the wrong place (2026-08-14).
  EOT
  value       = <<-EOT
    S3_ENDPOINT=https://s3.${var.region}.scw.cloud
    S3_BUCKET=${scaleway_object_bucket.relay_blobs.name}
    S3_REGION=${var.region}
    S3_ACCESS_KEY=<the same Scaleway API access key this module used>
    S3_SECRET_KEY=<the same Scaleway API secret key this module used>
  EOT
}

output "preflight_hint" {
  description = "The one check no test replaces — run it BEFORE the migration window."
  value       = <<-EOT
    set -a; . ./deploy.env; set +a
    java -cp sync-relay-server-all.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt --preflight

    It writes one 2 MiB object (the real chunk size), reads it back byte-for-byte, checks exists
    before and after a delete, and cleans up. ⚠⚠ A store that does not decode `aws-chunked` framing
    returns MORE bytes than went in — 2,098,678 for a 2 MiB blob — and only the bucket you are about
    to depend on can tell you. The payload size is load-bearing: a small object round-trips
    correctly even through framing corruption.
  EOT
}
