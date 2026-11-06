# Store and marketing images (Cloudflare R2)

An **R2 bucket**, `tenshu-assets`, behind the custom domain **`assets.tenshu.app`**. It holds the
*generated* images of the store presence: the App Store screenshots from
`scripts/store-screenshots.sh`, and later the rendered marketing graphics. No compute. Why the images
live here and not in git: [`store-presence-and-support-pages.md`](../../docs/proposals/store-presence-and-support-pages.md) §3.3.
How they get here: [`docs/how-to/store-screenshots.md`](../../docs/how-to/store-screenshots.md) §7.

It is the third sibling of [`deploy/cloudflare-tiles/`](../cloudflare-tiles/) and
[`deploy/cloudflare-models/`](../cloudflare-models/): same provider pin, same account, same zone.
A module of its own rather than another bucket in either: those two serve app clients, are applied
on their own schedules (tiles had a staged cutover), and have their own bucket-scoped S3 keys. A
third key scoped to this bucket keeps a leaked publish key from touching either of them.

## Layout

```
https://assets.tenshu.app/sha256/<64 hex>.png     Cache-Control: public, max-age=31536000, immutable
```

- **Content-addressed, flat.** The key is the SHA-256 of the bytes, so an object never changes, the
  header's `immutable` is true, and identical captures (a screen that did not change between runs,
  or the same image used by two apps) are stored once. The prefix names the hash algorithm, not a
  category: what an image *is* lives in the repo's manifests, never in its URL, so reclassifying a
  picture costs no re-upload.
- **`assets.tenshu.app`**, one level below the zone: Universal SSL (`*.tenshu.app`) covers it with
  no per-host certificate, unlike the two-level `*.service` names. It is outside `*.service` because
  nothing compiles it into a client; the only readers are the support pages, the store upload, and
  people.

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; account_id + zone_id (tiles' values)
./tf.sh init && ./tf.sh plan                   # token from the Keychain (`cloudflare-api-token`)
./tf.sh apply                                  # ⚠ the maintainer's call — bucket + DNS record
./tf.sh output custom_domain_status            # ⚠ wait for ssl = active before publishing
```

Then mint the publish key — **after** the bucket exists, since a key scoped to one bucket can only
name one that is there: R2 → Manage API tokens → *Object Read & Write* → *Apply to specific buckets
only* → `tenshu-assets`. Store the pair once (`-w` with no value prompts, so nothing reaches history):

```bash
security add-generic-password -U -a tenshu -s r2-assets-access-key-id -w
security add-generic-password -U -a tenshu -s r2-assets-secret-access-key -w
```

`scripts/store-publish.sh` reads them (or `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` when exported).
⚠ Neither the tiles pair nor the models pair works here: both are bucket-scoped.

## ⚠⚠ What will bite

**Two credentials, never mixed.** A Cloudflare **API token** for Terraform (`tf.sh`, or
`CLOUDFLARE_API_TOKEN`), and an **R2 S3 key pair** for the publish script.

**R2 has no object ACLs.** Public read is the custom domain, for the **whole bucket**. That is why
licensed fixture photos go to a *second*, private bucket (`enable_fixtures_bucket`), never under a
"private" prefix here.

**The header is the object's.** R2 returns the `Cache-Control` and `Content-Type` stored at upload, and
Cloudflare's edge caches `.png` by default, honouring that header. So the publish script sets both on
every PUT, and there is no cache rule to keep in sync. ⚠ Never upload anything here under a key that
is not its own hash — `immutable` would then be a lie the edge believes for a year.

**The DNS record is the custom domain's, not the inventory's.** Attaching creates a read-only record
for `assets` in the zone; `deploy/cloudflare-dns` must never declare one.

**Cloud sessions.** A cloud agent session that needs to read these images needs `assets.tenshu.app` on
its environment's network allowlist — a maintainer setting, not something this module can do.

**Garbage.** Nothing deletes an object automatically. One that no committed manifest names any more
(on any branch you still care about) can be removed by hand; `scripts/store-publish.sh --verify`
checks the opposite direction — that everything a manifest names is there.

## The optional private bucket

`enable_fixtures_bucket = true` adds `tenshu-store-fixtures`: no custom domain, no r2.dev, readable
only with an S3 key. It holds licensed source photos for the screenshot fixture, at
`fixtures/<sha256>.<ext>`, which `store/fixtures/generate.py` fetches with credentials. Off until the
first real photo exists — today every fixture is generated.
