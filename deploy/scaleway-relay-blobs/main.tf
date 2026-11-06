# The relay's CONTENT-PLANE bucket on Scaleway Object Storage
# (`hosting-provider-economics.md` D10 step 1; runbook `docs/how-to/migrate-relay-blobs-to-s3.md`).
#
# Holds the opaque AEAD ciphertext the relay stores under `<ownerId>/<ref>` and `share/<id>/<idx>`.
# Nothing here is public and nothing here is readable by Scaleway: House Rule #1 means the relay only
# ever handled ciphertext, so moving those bytes to object storage changes who holds them, not what
# they are.
#
# ## ⚠⚠ Why this is NOT the tiles bucket's shape
#
# The tiles bucket (`deploy/cloudflare-tiles`) is public-read, because map bundles are public
# artifacts whose property is integrity, not confidentiality. This bucket is the opposite: there is
# **no bucket policy at all**, so the API key pair is the only way in. A public-read policy here
# would expose every user's content-plane ciphertext to anonymous ranged GETs — still encrypted, but
# it hands an observer per-owner object sizes and access patterns, which is precisely the residual
# T-R-09 accepted only because the store was private.
#
# ## ⚠ Why no versioning
#
# Blobs are content-addressed — `blob-<sha256(ciphertext)>` — so a re-PUT writes identical bytes to
# the same key and there is no version history to keep. Versioning would silently double the bill and
# defeat `dropRefs`, which is how the owner's own reclamation frees space.

resource "scaleway_object_bucket" "relay_blobs" {
  name       = var.bucket_name
  region     = var.region
  project_id = var.project_id

  tags = {
    project = "tenshu"
    purpose = "relay-content-plane"
  }
}
