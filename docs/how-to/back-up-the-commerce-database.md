# Backing up (and restoring) the commerce database

⚠⚠ **Surveyed 2026-09-14: commerce had no backups of any kind** — no cron, no systemd timer, no dumps
on disk — for the service holding account, session, entitlement and billing rows. The relay got
nightly snapshots the same week. This closes that.

It is small (8 071 kB, 12 tables), so there was never a capacity reason to skip it.

⚠ **Prepared, not applied (2026-10-23):** a second copy in nl-ams, a least-privilege key for this box,
and bucket policies. The relay how-to's [*What lives where*](back-up-the-relay-database.md#what-lives-where)
table covers both boxes, and its [*Applying the backup hardening*](back-up-the-relay-database.md#applying-the-backup-hardening)
runbook is the one place the rollout is written down — steps 1–4 and 8–9, 11 and 13 touch commerce.

---

## What it does

`deploy/scaleway-commerce/backup-commerce-db.sh`, run by a systemd timer at **03:42 UTC** (offset from
the relay's 03:17 so the two do not contend for upload bandwidth).

⚠ `pg_dump` runs **inside** the database container. Postgres is an internal-only Compose service and
the host has no postgres client packages, so a bare `pg_dump` on the host has neither a reachable
server nor the binary.

⚠ `-Fc` (custom format) is compressed and restorable selectively with `pg_restore`. It is also
self-describing, which is what lets the script validate the archive before uploading: it runs
`pg_restore --list` and requires at least one `TABLE DATA` entry, so a truncated or corrupt dump fails
on the night it is taken rather than on the day it is needed. It also requires `TABLE DATA` for
`schema_version` and `account`, a readable live `schema_version`, and a dump that is not suspiciously
small (below), and it reads the uploaded object's size back rather than trusting a silent `PUT`.

⚠⚠ **The size floor is RELATIVE, and it can refuse a legitimate shrink.** The script refuses a
dump under **50 %** of the previous upload (`BACKUP_MIN_FRACTION`), unless `/opt/tenshu/reset-epoch`
is newer than that upload. The reset wipe writes that file, so the first dump after a reset goes
through. An absolute floor would have refused every post-reset night (an empty commerce dump is ~16 kB, 3 kB gzipped). When the
shrink is real — a big deletion, a purge — the run fails with `/fail` and says so. There are two ways
out, both on the box:

- **One run:** `date -u +%s > /opt/tenshu/reset-epoch`, then `systemctl start tenshu-commerce-backup.service`. The next
  upload after it is exempt, then the floor applies again against the new, smaller size.
- **Standing:** set `BACKUP_MIN_FRACTION=0.2` (or `0` to disable) in `/opt/tenshu/deploy.env`. The
  script sources it.

⚠ **The floor needs `s3:ListBucket` on the backup bucket** (it lists `commerce-db/` to find the previous
upload), not just `PutObject`/`HeadObject`. Until the backup hardening is applied the backup key is the
project-wide Scaleway API key (see `deploy/scaleway-commerce-backups/outputs.tf`); no bucket policy narrows
it, so it can list. The scoped `commerce-backup` key keeps `ListBucket` on the **fr-par** bucket for this
(`deploy/scaleway-backup-access`); keep it there, or every night fails with *"cannot list"*. nl-ams is never
listed.

**Where it goes** — two buckets, the second only when `BACKUP_DR_S3_BUCKET` is set:

| bucket | region | class | what can the commerce key do there |
|---|---|---|---|
| `tenshu-commerce-db-backups` | fr-par | Standard (Multi-AZ) | write and read back; ⚠ cannot delete, **can** overwrite |
| `tenshu-commerce-db-backups-ams` | nl-ams | One Zone | write and read back; versioned + Object Lock 30 d, so neither a delete nor an overwrite destroys a dump |

The second upload runs only after the first is in place and size-verified, as one `PUT` with
`Content-MD5` (the locked bucket requires an integrity header). ⚠⚠ If it fails the log says
`FAILED: DR: …`, the run exits **2** (unit failed), and the fr-par dump is untouched — the step only
reads the local file. Both buckets expire after 30 days through `set-lifecycle.py` (the provider cannot
express it; on the versioned bucket it also expires noncurrent versions a day later).

⚠⚠ **A nightly dump is a backup, not a cutover.** `pg_dump` takes a consistent MVCC snapshot, so it is
internally consistent without stopping anything — but consistent *as of its start*. Migrating commerce
needs the source quiesced first, or writes accepted after the dump exist only on the old database:
[`move-commerce-to-scaleway.md`](move-commerce-to-scaleway.md).

## Restore

⚠ Postgres is **16.14**. A restore target must be **≥ 16** — a 15 refuses the archive.

```bash
gunzip commerce-20260914T034200Z.dump.gz

# ⚠ stop commerce first: restoring under a running service races its writes, and --clean drops
#   objects the service may be mid-query on
# ⚠⚠ The ONLY correct invocation — see move-commerce-to-scaleway.md. One that merely fixes the path
#    renders cleanly with an EMPTY seed and no db service, and its `up -d` recreates commerce seedless.
DC='cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env'
# ⚠ pipe INTO the container — the host has no pg_restore. Each step runs only if the one before worked.
restored=
ssh root@<box> "$DC stop commerce" &&
  ssh root@<box> '[ -z "$(docker ps -q --filter name=^commerce-service-commerce-1$ --filter status=running)" ]' &&
  ssh root@<box> 'docker exec -i commerce-service-db-1 pg_restore -U commerce -d commerce --clean --if-exists' \
    < commerce-20260914T034200Z.dump &&
  restored=1

# ⚠⚠ the start is INSIDE the check, and the check includes the restore: pasted as separate lines, a
#    failed step does not stop the next one. ⚠ pg_restore exits non-zero on ANY error, even an ignored one.
if [ "$restored" = 1 ] &&
   ssh root@<box> "$DC config" | grep -qE '^\s+ISSUER_SEED: .{20,}' &&
   ssh root@<box> "$DC config --services" | grep -qx db; then
  ssh root@<box> "$DC up -d commerce"
else
  echo 'STOP: restore not confirmed, or no seed / no db service in the rendered config — commerce NOT started' >&2
fi
```

**From nl-ams** (fr-par down, or its dumps gone or suspect): the same keys, `commerce-db/<year>/commerce-<stamp>.dump.gz`,
in `tenshu-commerce-db-backups-ams` at `https://s3.nl-ams.scw.cloud`. Fetch with the **admin** key (the box
key cannot list); the relay how-to's [*Restoring from the second region*](back-up-the-relay-database.md#restoring-from-the-second-region-nl-ams)
has the snippet — change the bucket and the prefix. ⚠ The bucket is versioned: if the newest dump looks
wrong (size far off its neighbours), `list_object_versions` shows earlier versions of the same key, which
an overwrite cannot remove for 30 days. Then restore exactly as above.

Then verify against the counts you expect — `account`, `session`, `acceptance`, `redemption_code`:

```bash
ssh root@<box> 'docker exec commerce-service-db-1 psql -U commerce -d commerce \
  -c "select relname, n_live_tup from pg_stat_user_tables order by n_live_tup desc"'
```

⚠⚠ **And check the issuer.** A restore that silently changed `ISSUER_SEED` — because the box was
rebuilt from tfvars rather than restored — produces a different issuer public key, and the relay then
rejects every entitlement with `402 entitlement_required`. That failure looks like the relay refusing
writes, not like a commerce problem:

```bash
ssh root@<box> 'docker logs commerce-service-commerce-1 2>&1 | grep "issuer public key"'
# sha256 of that value must equal the sha256 of the relay's ENTITLEMENT_ISSUER_PUBKEY
```

### ⚠⚠ Re-applying erasures after a restore

A dump older than an account deletion brings the person back: their email, sessions and the account
row as it was before `eraseAccount`, or an open account where it had been closed. **Before commerce
serves traffic**, every account erased or closed after the dump's timestamp must be erased or closed
again. Otherwise we hold an address we told the person we deleted, and because an older dump also
forgets the closure, the relay sweep stops posting that owner.

- **Where the list comes from.** If the live database you are replacing is still readable, save
  `SELECT id, closed_at, erased_at FROM account WHERE closed_at IS NOT NULL` from it **before** the
  `pg_restore`. Otherwise the only source is the erasure request log
  ([`policy/erasure-request-runbook.md`](../policy/erasure-request-runbook.md) §1), which covers email
  requests only. ⚠ **Self-service deletions made after the dump and lost with the live database
  are not recorded anywhere else**: the app logs name accounts by id prefix and roll over within days.
- **Closures** can be re-applied with `/admin/accounts/close` (by email, so only while the restored
  row still carries the address). ⚠ It stamps `closed_at` = now, which restarts the relay's 30-day
  grace; to keep the original date, set `closed_at` from the saved list by SQL. ⚠ The console is
  probably off in production ([`tech-debt.md`](../tech-debt.md) *Commerce's compose drops ~18 env vars*).
- **Erasures** have **no operator tool**. `eraseAccount` is reachable only through the user's
  deletion code. Until one exists, re-erasing is the SQL inside `PostgresCommerceStore.eraseAccount`,
  run by hand in one transaction per account (it keeps an earlier `closed_at` via `COALESCE`).
- Then follow the relay side ([`back-up-the-relay-database.md`](back-up-the-relay-database.md)
  *Re-applying erasures after a restore*): once closures are back, the closure sweep re-erases relay
  owners on its own.

Gap and sizing: [`tech-debt.md`](../tech-debt.md) *A database restore does not re-apply erasures*.

## Installing it on a box

⚠⚠ **First, the five `BACKUP_S3_*` lines in the box's `/opt/tenshu/deploy.env`.** Neither
`deploy/scaleway-commerce`'s cloud-init nor anything else writes them, and without them the script
exits at its required-variable checks having dumped nothing.

- **Moving boxes:** carry them across box to box, so the key is never printed:
  ```bash
  ssh root@<old> "grep '^BACKUP_S3_' /opt/tenshu/deploy.env" \
    | ssh root@<box> 'grep -q "^BACKUP_S3_" /opt/tenshu/deploy.env || cat >> /opt/tenshu/deploy.env'
  ```
  ⚠⚠ And **disable the old box's timer** at the cutover (`systemctl disable --now
  tenshu-commerce-backup.timer`): both boxes upload to the same prefix under the same key pattern, so
  a stale database's nightly dump would sit beside, and look as new as, the live one.
- **First install:** append `terraform output -raw backup_settings_for_deploy_env` from
  [`deploy/scaleway-commerce-backups`](../../deploy/scaleway-commerce-backups) (endpoints, buckets,
  regions — `BACKUP_S3_*` and `BACKUP_DR_S3_*`, no secrets), then the two key lines from
  `terraform output -raw commerce_box_env` in [`deploy/scaleway-backup-access`](../../deploy/scaleway-backup-access),
  **piped over ssh, never written to a file in the checkout** (runbook step 8 has the line). Then run
  `python3 set-lifecycle.py` in `deploy/scaleway-commerce-backups` to apply the 30-day expiry to both
  buckets (Terraform cannot express it; see that module's `main.tf`).

```bash
# ⚠ ON THE BOX: cloud-init installs no boto3, and without it the uploader dies at `import boto3`
ssh root@<box> 'apt-get install -y python3-boto3'
scp deploy/scaleway-commerce/backup-commerce-db.sh root@<box>:/opt/tenshu/
scp deploy/scaleway-commerce/tenshu-commerce-backup.{service,timer} root@<box>:/etc/systemd/system/
# ⚠ gated: five BACKUP_S3_* lines with real values (a `<placeholder>` does not count), or nothing is enabled.
#   (BACKUP_DR_S3_* is optional to the gate: without it the run warns "DR: … unset" and exits 0.)
#   The log is shown either way (the script writes failures there, not to the terminal), and the ssh exits
#   with the SCRIPT's status, not tail's.
ssh root@<box> '[ "$(grep -c "^BACKUP_S3_[A-Z_]*=[^<]" /opt/tenshu/deploy.env)" = 5 ] ||
    { echo "STOP: BACKUP_S3_* incomplete in deploy.env — nothing enabled" >&2; exit 1; }
  chmod +x /opt/tenshu/backup-commerce-db.sh &&
  systemctl daemon-reload && systemctl enable --now tenshu-commerce-backup.timer &&
  { /opt/tenshu/backup-commerce-db.sh; rc=$?; tail -5 /var/log/tenshu-commerce-backup.log; exit $rc; }'
```

⚠⚠ **Then give it a dead man's switch:** [`box-health.md`](box-health.md). Its failures go to a log
file on the box, and in October 2026 a full root disk failed this backup every night for 23 days with
nothing noticing. `deploy/box-health/install.sh <host> commerce` sets up the ping — and re-copies this
script, so use it rather than the `scp` above whenever the script changes.

⭐ Unlike the relay's, this script needs **no sqlite3** — but it does need `docker exec` access to the
database container, so it must run on the box itself and not from a workstation.

## Monitoring that it ran

```bash
ssh root@<box> 'systemctl list-timers tenshu-commerce-backup.timer; systemctl --failed "tenshu-*"'
ssh root@<box> 'grep -E "DR: OK|FAILED|done" /var/log/tenshu-commerce-backup.log | tail -3'
#   exit 1 = no dump at all · exit 2 = fr-par OK, nl-ams FAILED — both mark the unit failed
cd deploy/scaleway-backup-access && python3 check-backups.py   # the buckets' view: newest dump < 26 h, both regions
```

## What the separation does and does not buy

The dumps go to **different buckets** from the relay's snapshots, because they contain billing rows and
the relay box holds S3 credentials in its `deploy.env`.

⚠⚠ **Until the runbook is applied, that is a separation of data, not of access**: both boxes carry the
one project-wide admin key, which reaches every bucket. After it:

- this box carries **only** the `commerce-backup` key. IAM gives it object read + write in the Project
  and **no delete anywhere**; bucket policies limit it, among the backup buckets, to the two commerce
  ones, and every backup bucket's policy leaves it out — so it cannot read or touch the relay's
  snapshots or mirrors, and nothing on the relay box can touch the commerce dumps;
- ⚠ IAM scopes to a **Project**, not a bucket, so on a bucket with **no** policy the key keeps its
  project-wide read + write. Today that is the relay's content bucket: a compromised commerce box could
  read and **overwrite** content blobs until the runbook's step 12 (`enforce_on_content_bucket`) polices
  it. The alternative is a separate Project for commerce (open decision D10 in the relay how-to).

Design and limits: [`deploy/scaleway-backup-access/main.tf`](../../deploy/scaleway-backup-access/main.tf)
and the relay how-to's [*Keys*](back-up-the-relay-database.md#keys).
