# The direct-release bucket on Cloudflare R2: the APKs tenshu.app offers to phones without Google Play
# (android-direct-apk-distribution.md D3, Phase 1 item 3), and the notarized macOS .dmg that tenshu.app
# and the Homebrew casks offer (desktop-macos-distribution.md §2.7; scripts/macos-direct-publish.sh,
# sha256/<hex>.dmg, Content-Type application/x-apple-diskimage). One bucket, one key, for both.
#
#   bucket        = tenshu-releases: the signed APKs from scripts/android-release.sh --direct,
#                   content-addressed at sha256/<hex>.apk. Public, through the custom domain.
#   custom domain = releases.tenshu.app — one level below the zone, so Universal SSL's *.tenshu.app
#                   covers it; the download pages (tenshu.app/apps/<app>/download/android/…) link it.
#   r2.dev URL    = a toggle, OFF
#
# ⚠⚠ A bucket of its OWN, not a prefix of tenshu-assets (D3). R2 does not check that an object's key
# matches its hash, so whoever holds a write key for the bucket can replace sha256/<hex>.apk with
# other bytes — and a FRESH install trusts whatever key signed the APK it is given (threat model A44,
# T-DA-01). The store screenshots' publish key is used far more often and by more tooling; scoping a
# separate key to this bucket alone keeps a leak of that one from reaching an install trust root.
#
# There is NO compute here. Uploads are plain S3 PUTs from scripts/android-direct-publish.sh, which
# sets on every object:
#   Content-Type:        application/vnd.android.package-archive
#   Cache-Control:       public, max-age=31536000, immutable
#   Content-Disposition: attachment; filename="tenshu-<app>-<versionCode>.apk"
# R2 serves all three back as stored, so the header a browser sees is the object's own and no rule
# here has to agree with the script. Without the Content-Disposition the download would be named
# after its hash. ⚠ `immutable` is safe only because every key is content-addressed: the same key
# always holds the same bytes, and those bytes carry their own package name and versionCode, so the
# filename stored with them is fixed too.
#
# ⚠ No CORS, deliberately: the readers are plain <a href> navigations (no CORS, no CSP connect-src),
# `curl`, and people. Nothing fetches these from script.

# ---- The public bucket -------------------------------------------------------
# ⚠ No versioning, no lifecycle: keys are content hashes, so nothing is ever overwritten, and an APK
# that store/android-release.json no longer names stays until removed by hand (README: "Garbage") —
# a user may still be following an old link, and no rule nobody reviews should decide that.
resource "cloudflare_r2_bucket" "releases" {
  account_id = var.account_id
  name       = var.bucket_name
  location   = var.location
}

# Declared even when disabled, so "off" is a state Terraform asserts rather than a default nobody
# checked. ⚠ R2 has no object ACLs: this toggle and the custom domain ARE the public-read policy.
resource "cloudflare_r2_managed_domain" "dev" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.releases.name
  enabled     = false
}

# Creates its own read-only DNS record in the zone; deploy/cloudflare-dns must never declare
# `releases`, exactly as for assets, tiles.service and models.service.
resource "cloudflare_r2_custom_domain" "releases" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.releases.name
  zone_id     = var.zone_id
  domain      = var.domain
  enabled     = true
  min_tls     = var.min_tls
}
