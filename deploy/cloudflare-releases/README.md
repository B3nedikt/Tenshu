# Direct releases: Android APKs and macOS `.dmg` (Cloudflare R2)

An **R2 bucket**, `tenshu-releases`, behind the custom domain **`releases.tenshu.app`**. It holds the
signed APKs that tenshu.app offers to phones without Google Play
([`android-direct-apk-distribution.md`](../../docs/proposals/android-direct-apk-distribution.md) D3,
Phase 1 item 3), and the notarized Developer ID `.dmg` of the ten desktop apps that tenshu.app and the
Homebrew casks offer ([`desktop-macos-distribution.md`](../../docs/proposals/desktop-macos-distribution.md)
§2.7). No compute.

| | Built by | Published by | Manifest | Runbook |
|---|---|---|---|---|
| APK | `scripts/android-release.sh --direct` | `scripts/android-direct-publish.sh` | `store/android-release.json` | [`android-release.md`](../../docs/how-to/android-release.md) *Publishing a direct release* |
| `.dmg` | `scripts/macos-release.sh` | `scripts/macos-direct-publish.sh` | `store/macos-release.json` | [`macos-release.md`](../../docs/how-to/macos-release.md) §7 |

⚠ One bucket and one publish key for both, on purpose: both are install trust roots (threat model
A44), both are published by the maintainer from the same Mac, and R2 cannot scope a key to a prefix,
so a second bucket would add a key to custody without separating who can write what.

A sibling of [`deploy/cloudflare-assets/`](../cloudflare-assets/): same provider pin, same account,
same zone, same layout. **A module, a bucket and a publish key of its own**, and that is the point of
it (D3): R2 does not check that an object matches its key, so anyone holding a write key for the
bucket can replace `sha256/<hex>.apk` with other bytes, and a *fresh* install trusts whatever key
signed the APK it gets. This bucket is an **install trust root** (threat model A44, T-DA-01). The
assets key is used by more tooling and far more often; scoping a separate key to this bucket alone
keeps a leak of that one away from here.

## Layout

```
https://releases.tenshu.app/sha256/<64 hex>.apk
    Content-Type:        application/vnd.android.package-archive
    Cache-Control:       public, max-age=31536000, immutable
    Content-Disposition: attachment; filename="tenshu-<app>-<versionCode>.apk"

https://releases.tenshu.app/sha256/<64 hex>.dmg
    Content-Type:        application/x-apple-diskimage
    Cache-Control:       public, max-age=31536000, immutable
    Content-Disposition: attachment; filename="Tenshu<App>-<version>.dmg"
```

- **Content-addressed, flat**, exactly as on `assets.tenshu.app`. The key is the SHA-256 of the
  APK, so an object never changes and `immutable` is true. What an APK *is* (app, version, signer)
  lives in the committed `store/android-release.json` (`store/macos-release.json` for a `.dmg`),
  never in its URL.
- **Every header is the object's own.** The publish script sets all three on each PUT, R2 serves
  them back as stored, and no rule here has to agree with the script. Without the
  `Content-Disposition` a browser would save the download under its hash. The filename is fixed per
  key because the bytes carry their own package name and versionCode.
- **`releases.tenshu.app`**, one level below the zone, so Universal SSL (`*.tenshu.app`) covers it.
  Outside `*.service` because no client compiles it in: the apps link the download *page*
  (`/apps/<app>/download/android`), never this host, so the host can move by rewriting the manifest.
- **Resumable.** R2 custom domains answer `Range` with 206 (verified live on `assets.tenshu.app`,
  the same setup), which matters for a 35 MB download on a phone.

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; account_id + zone_id (the assets values)
./tf.sh init && ./tf.sh plan                   # token from the Keychain (`cloudflare-api-token`)
./tf.sh apply                                  # ⚠ the maintainer's call — bucket + DNS record
./tf.sh output custom_domain_status            # ⚠ wait for ssl = active before publishing
```

Then mint the publish key — **after** the bucket exists, since a key scoped to one bucket can only
name one that is there: R2 → Manage API tokens → *Object Read & Write* → *Apply to specific buckets
only* → `tenshu-releases` **and nothing else**. Store the pair once (`-w` with no value prompts, so
nothing reaches history):

```bash
security add-generic-password -U -a tenshu -s r2-releases-access-key-id -w
security add-generic-password -U -a tenshu -s r2-releases-secret-access-key -w
```

`scripts/android-direct-publish.sh` and `scripts/macos-direct-publish.sh` read them (or `RELEASES_R2_ACCESS_KEY_ID` /
`RELEASES_R2_SECRET_ACCESS_KEY` when exported — deliberately not `R2_ACCESS_KEY_ID`, the name the
assets script reads, so an exported assets pair is never sent here by accident).

## ⚠⚠ What will bite

**Two credentials, never mixed.** A Cloudflare **API token** for Terraform (`tf.sh`, or
`CLOUDFLARE_API_TOKEN`), and an **R2 S3 key pair** for the publish script. ⚠ The publish pair is an
operational credential ([`ops-secret-custody.md`](../../docs/proposals/ops-secret-custody.md) D1/D2),
not a trust anchor: a leak lets someone replace an APK, which a fresh install would trust, but it
cannot make an APK that **updates** an installed copy — only the side-channel signing key can
(threat model A43). Rotate it on any doubt: mint a new one, delete the old one in R2.

**Never a second bucket's key, never this key elsewhere.** No CI job holds it. A key with access to
more than this bucket undoes D3.

**R2 has no object ACLs.** Public read is the custom domain, for the **whole bucket**. Nothing but
published APKs and `.dmg` goes in here.

**Never overwrite.** Never upload anything under a key that is not its own hash: `immutable` would
then be a lie the edge believes for a year, and a user who checks the SHA-256 the download page
shows would see a mismatch. `scripts/android-direct-publish.sh --verify` (and `macos-direct-publish.sh --verify`) downloads every
file its manifest names and checks the bytes, the size and all three headers — run it after each publish, and
on any suspicion that the key leaked.

**A header fixed later is not fixed for caches.** The publish step re-uploads an object whose headers
are wrong (`REPAIR`), but a copy the edge or a browser already holds as `immutable` keeps the old
ones: purge that URL by hand (Caching → Purge → Custom URL).

**The DNS record is the custom domain's, not the inventory's.** Attaching creates a read-only record
for `releases` in the zone; `deploy/cloudflare-dns` must never declare one.

**Garbage.** Nothing deletes an object automatically, and an APK the manifest no longer names may
still be the one an old link or a user's bookmark points at. Remove old versions by hand, if ever;
storage of a few hundred MB per release costs next to nothing.

## Not verified

- Whether Cloudflare's edge caches `.apk` from an R2 custom domain by default. `apk` is on
  Cloudflare's default cached-extension list as we remember it; not checked live. Either way the
  bytes are right: an uncached response comes from R2 with the same headers.
- Any rate limit: none is configured, and R2 egress is free.
