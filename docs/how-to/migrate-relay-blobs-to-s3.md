# Migrating the relay's blobs from the block volume to S3

Moves the content plane's chunk bodies and Mode-A share chunks off the droplet's block volume and
into an S3-compatible bucket, using `BlobMigrationTool` and one short maintenance window.

Why: on the volume, running out of space is an **outage** — 2026-08-14, when the relay answered 500
to every push while serving reads and reporting `200` on `GET /`. On object storage the same
unreclaimed growth is a **bill**. Background and the decision record:
[`hosting-provider-economics.md`](../proposals/hosting-provider-economics.md) D10;
[`sync-relay-persistence.md`](../proposals/sync-relay-persistence.md) §7–§8.

⚠⚠ **This does not make the relay multi-node.** The single-writer constraint is on the SQL side.

⚠ **Deletes batch, and they have to.** The four GC paths (`gcSweep`, `reclaimOwner`, `dropRefs`,
the share GC) used to loop one blob at a time, which was free against a local volume and an HTTP
round-trip each against a bucket — `POST /sync/content/delete` alone caps at 512 refs. They now go
through `BlobStore.deleteAll`, which `S3BlobStore` serves with `DeleteObjects` (1,000 keys per
request). If you are reading this from a checkout that predates that change, do not switch S3 on.

---

## The shape, and why it is not negotiable

Three passes, in this order:

1. **Bulk copy, relay LIVE.** The relay is still on `FileBlobStore`. Nothing reads the bucket, so a
   partly-filled one is unobservable and no invariant can be violated by it. This is the long pass.
2. **Stop the relay. Delta copy.** Catches everything written during the bulk pass. Short, because
   the copy is idempotent — already-present keys are skipped.
3. **Flip the env vars. Start.** The downtime is steps 2–3, minutes rather than hours.

⚠⚠ **Do not instead flip the env vars first and copy in the background.** A relay reading an
incomplete bucket is not merely slow, it is *wrong*, and one of the two ways is destructive:

- `SqliteContentBlobStore.get` is `blobs.get(...) ?: return null` → `ContentRouting` answers 404 →
  the client maps it to a soft failure and the file **silently does not surface**. Recovers when the
  copy finishes.
- `SqliteShareStore.isCollectible` reads `!blobs.exists(chunkKey(shareId, 0))` as *"this share never
  received chunk 0"*. A live share whose bytes are still on the volume answers `false`, so the GC
  **deletes its record**. It fires only on shares older than a full retention window — i.e. the
  oldest, most-established links — long after the migration looks finished, and the record does not
  come back when the bytes do.

A read-through composite store closes both, but has to fall through on `exists` and `delete` as well
as `get`, and `BlobStoreContractTest` cannot catch a mistake (it runs against one store, so a
composite passes trivially). `BlobMigration`'s KDoc carries that list if zero-downtime moves ever
become worth building.

---

## Before the window

- [ ] Bucket created; credentials have `PutObject`, `GetObject`, `HeadObject`, `DeleteObject`.
      ⭐ On Scaleway there is a module for it: [`deploy/scaleway-relay-blobs`](../../deploy/scaleway-relay-blobs/)
      — **private**, no bucket policy, and its `terraform output s3_settings_for_deploy_env` prints
      the four settings this runbook wants. ⚠ Read its README's placement note first: keep the
      bucket where the relay is. (This migration ran on 2026-09-13 with the relay still on
      DigitalOcean, a deliberate and short-lived cross-provider hop.) ⚠⚠ And its `403 AccessDenied` note: a wrong
      credential and an under-scoped one give the **same** error, and `aws s3 ls` against the
      endpoint is the one command that tells them apart.
- [ ] **Verify the store accepts our requests at all — run the preflight.**

      ```bash
      set -a; . ./deploy.env; set +a          # the four S3_* settings; no DATABASE_URL needed
      java -cp sync-relay-server-all.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt --preflight
      ```

      It writes one 2 MiB object — the real chunk size — reads it back byte-for-byte, checks
      `exists` before and after a delete, and cleans up. Exits non-zero on any failure and names the
      setting to change.

      ⚠⚠ **No test replaces this, and a better test would not help.** The stores that break do so by
      being **less** capable than S3, so the `aws-chunked` bug is invisible against real S3, MinIO
      and S3Mock alike — only the bucket you are about to depend on settles it. ⚠ And the payload
      size is load-bearing: a small object round-trips *correctly even through framing corruption*.
- [ ] Note the current volume usage, so you can compare afterwards:
      `ssh root@<ip> 'df -h /mnt/relay_data; du -sh /mnt/relay_data/relay-blobs'`
- [ ] Pick a window. ⚠ There is **no activity data** to choose it from — the relay has no metrics
      backend. A rough proxy from data already on the box:
      ```sql
      SELECT strftime('%H', last_referenced_at/1000, 'unixepoch') AS hour, COUNT(*)
      FROM content_blob GROUP BY hour ORDER BY 2 DESC;
      ```
      Treat it as a hint, not a guarantee: it is per-blob access, not per-request, and one Frankfurt
      region serves whoever signed up — EU night is midday in Japan.
- [ ] Decide whether to put a maintenance page in front. First-party apps are local-first and mostly
      will not notice a short window (reads and writes hit the local replica; sync retries). The
      exception is **anonymous Mode-A share links** — `GET /share/{id}` and `/share/{id}/chunk/{i}`
      have no local replica and no fallback, so a recipient gets a broken page. A static 503 in Caddy
      for those routes is the cheap fix.

---

## 1 — Bulk copy, with the relay still running

```bash
ssh root@<relay-ip>
cd /opt/tenshu

# The tool reads the SAME env the relay does, so source and destination cannot drift from it.
# Add the S3_* settings to deploy.env now — the RUNNING relay does not re-read this file, so
# writing them here does not switch it. (⚠ deploy.env is hand-maintained, not Terraform-managed.)
cat >> deploy.env <<'ENV'
S3_ENDPOINT=https://fsn1.your-objectstorage.com
S3_BUCKET=tenshu-relay-blobs
S3_ACCESS_KEY=...
S3_SECRET_KEY=...
ENV

set -a; . ./deploy.env; set +a
java -cp sync-relay-server-all.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt
```

Expect `copied` ≈ the key count and `failed: 0`. The tool exits non-zero on any failure, and copying
is idempotent, so a killed run is simply re-run.

⚠ Its closing "OK — every enumerated key is present at the destination **as of now**" is deliberately
not "safe to switch": a clean pass against a live relay says nothing about what has been written
since. Step 2 is what makes the switch safe.

---

## 2 — Stop the relay, copy the delta

```bash
docker compose stop relay          # Caddy keeps serving; 502s until step 3
java -cp sync-relay-server-all.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt
```

`copied` should now be small (whatever arrived during step 1) and `skipped` should be nearly the
whole set. **`failed: 0` is the gate — do not continue otherwise.**

`missing at source` is not a failure: those keys have an index row but no bytes, so they are
unreadable on the current relay too. Note the count; it should not grow between passes.

---

## 3 — Flip and start

⚠⚠ **First check that the compose file forwards the settings**, or this step does nothing at all:

```bash
grep -c S3_ENDPOINT /opt/tenshu/repo/sync-relay-server/docker-compose.yml   # must be >= 1
```

`--env-file` feeds compose-file **substitution**; a container receives only what its `environment:`
block declares. A relay whose checkout predates the passthrough (added 2026-09-13) has no `S3_*`
lines there, so it comes back up on `FileBlobStore` having read a `deploy.env` full of S3 settings,
reports `{"status":"ok","durable":true}`, and serves reads exactly as before. **Nothing announces
this.** If the count is 0, update the box's checkout before continuing.

⚠ `git -C /opt/tenshu/repo pull` is **not** guaranteed to be that update. The checkout is cloned by
cloud-init through a tokenised URL only when `git_token` was supplied; without one the origin is a
plain `https://` remote and every fetch on a private repo dies with *"Invalid username or token"*.
The production DO box was in that state — its checkout sat at an old commit while the deployed image was many
commits newer, because the two are independent. `scp` the file up if the pull fails:

```bash
scp sync-relay-server/docker-compose.yml root@<relay-ip>:/opt/tenshu/repo/sync-relay-server/
ssh root@<relay-ip> 'cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml \
  --env-file /opt/tenshu/deploy.env config | grep S3_'   # resolved values, before recreating
```

```bash
docker compose up -d relay         # picks up the S3_* vars now in deploy.env
curl -fsS https://<relay-domain>/health          # {"status":"ok","durable":true}

# ⚠ The check that actually proves the switch — the relay names its backend on startup:
docker logs sync-relay-server-relay-1 2>&1 | grep 'Relay blobs:'
#   Relay blobs: S3 (https://…, bucket=…, region=…)      <- switched
#   Relay blobs: filesystem (/data/relay-blobs)          <- did NOT switch
```

`/health` now probes **two** volumes — the bucket and the disk still holding `relay.db` — and both
must accept a write. That second probe exists because this migration removed the coverage the first
one used to provide by accident (D10 S3).

Smoke, in this order:

- [ ] `/health` is 200.
- [ ] `Relay blobs: S3 (…)` in the startup log. ⚠ `/health` passing is **not** this check: with the
      blobs copied, a relay still on the volume passes every probe and serves every read.
- [ ] Upload a small file from a client; confirm it appears in the bucket.
- [ ] Download a file **uploaded before the migration** — this is the one that proves the copy.
- [ ] Open a Mode-A share link created before the migration.

---

## Related

⭐ **Doing this move before a provider change makes that change much smaller** — the 5.1 G of blobs
stops being something the provider move has to carry, leaving only a ~107 MB database.
See [`move-the-relay-to-another-provider.md`](move-the-relay-to-another-provider.md), which also
records the trap this runbook does not hit: a `docker compose stop` does **not** checkpoint the WAL,
so copying `relay.db` alone yields a valid, openable, **empty** database with no error anywhere.

## Rolling back

Until you delete the volume's blobs, rollback is: remove the `S3_*` lines from `deploy.env` and
restart (leaving them in the compose file is harmless — each defaults to empty, and the relay treats
blank as unset). The relay returns to `FileBlobStore` and the volume is still authoritative — nothing in the
migration mutates or removes the source.

⚠⚠ **Do not delete the source blobs on the day.** Leave them until the bucket has served real reads
for a while (a full retention window is the natural interval, since that is when the share GC's
orphan branch would fire). They are the rollback.

⚠ Moving to a bucket does not reclaim anything; it changes the failure from an outage to a bill.
Reclamation is separate: account closure (`retention-quota-model.md` P3, both flags ON in production
since 2026-10-24) and the relay's content sweep (§3.4).
