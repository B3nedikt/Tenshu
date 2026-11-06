# The relay's content-plane bucket (Scaleway Object Storage)

Creates the **private** bucket that `S3BlobStore` writes the relay's content-plane ciphertext to.
Step 1 of `hosting-provider-economics.md` D10; the move itself is
[`docs/how-to/migrate-relay-blobs-to-s3.md`](../../docs/how-to/migrate-relay-blobs-to-s3.md).

## Why this is worth doing before a provider move

⭐ It took **5.1 G** off the DigitalOcean → Scaleway migration. With blobs on the volume, moving the
relay meant copying 5.1 G and a 107 MB database. With blobs already in a Scaleway bucket it was **the
database alone**, and the bucket did not move. That is D10's whole argument for doing this first.
⚠ That held because the bucket was already at the destination. For a move to another provider, the
locality rule below applies, and the bucket's contents move too.

⭐ And independently of any migration: **on a volume, running out of space is an outage; on object
storage it is a bill.** The 2026-08-14 incident had the relay answering 500 to every push while
serving reads and reporting `200` on `GET /` — same underlying gap, hugely different blast radius.
Until CR-2 B6 almost nothing reclaimed content (`retention-quota-model.md` P3 was built but off, both
flags, until 2026-10-24), so the volume only grew. ⚠ Since B6 the relay's content sweep runs **`armed`** by default
(`CONTENT_SWEEP_MODE` in `sync-relay-server/docker-compose.yml`, `content-plane-cr2-b5-spec.md`) and is
the only thing that frees content; a relay deployed `off` or `dry_run` frees nothing and grows again.

## ⚠ Placement: keep the bucket where the relay is

The relay runs on **Scaleway fr-par** (`deploy/scaleway-relay`), and so does this bucket, so chunk
`PUT`/`GET`s stay in-region. For a while the relay was still on DigitalOcean fra1 and every chunk
crossed providers. That was accepted only because the relay was about to follow. ⚠ If the relay
ever moves again, move the bucket with it, or the content plane pays cross-provider latency and
egress on every chunk.

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; fill in
terraform init
terraform apply
terraform output s3_settings_for_deploy_env
```

Then, **before** any migration window:

```bash
terraform output preflight_hint    # and run what it prints, against THIS bucket
```

## ⚠⚠ What will bite

**No bucket policy, deliberately.** Unlike the tiles bucket ([`../cloudflare-tiles`](../cloudflare-tiles),
public through its custom domain), there is no public-read statement. Map bundles are public
artifacts whose property is integrity; this is every
user's content ciphertext, and anonymous reads would hand an observer per-owner object sizes and
access patterns — the residual T-R-09 accepted only because the store was private.

**All four `S3_*` settings or none.** A partial configuration makes the relay **refuse to start**
rather than fall back to the volume. That is deliberate: a deploy that looks successful while putting
bytes where the operator did not intend is worse than one that does not start.

**`aws-chunked` framing is the failure that silently corrupts.** A store that does not decode it
returns *more* bytes than went in — 2,098,678 for a 2 MiB blob. ⚠ It is invisible against real S3,
MinIO and S3Mock alike, because the stores that break do so by being **less** capable than S3. Only
the preflight, against this bucket, settles it.

**The bucket name is globally unique** across Scaleway and is not re-creatable under the same name
for a while after deletion.

**⚠⚠ A `403 AccessDenied` on `CreateBucket` has two causes that look identical.** Terraform reports
only:

```
Error: operation error S3: CreateBucket, https response error StatusCode: 403,
api error AccessDenied: Access Denied
```

which is the same message whether the credential is **wrong** or merely **under-scoped**. On
2026-09-13 that cost three rounds of guessing at IAM policies when `terraform.tfvars` still said
`SCWXXXXXXXXXXXXXXXXX` — the file had been re-copied from the example rather than edited, and it is
651 bytes either way, so its size tells you nothing.

⭐ **One command separates them**, and it is worth running before touching IAM:

```bash
set -a; . ./terraform.tfvars 2>/dev/null; set +a   # or export the pair by hand
AWS_ACCESS_KEY_ID=$access_key AWS_SECRET_ACCESS_KEY=$secret_key \
  aws s3 ls --endpoint-url "https://s3.$region.scw.cloud"
```

| result | meaning |
|---|---|
| lists (even empty) | the key is fine and has Object Storage access ⇒ only `CreateBucket` is denied, so look at **IAM scope** |
| `AccessDenied` on **ListBuckets** too | the **credential itself** is wrong — a placeholder, a typo, or the wrong pair |

⚠ And check the obvious thing first: `grep -c SCWXXX terraform.tfvars` must print **0**. On Scaleway
one API key pair serves both the API and S3, so a pair that provisions instances happily can still
be denied here — but only once it is a real pair.
