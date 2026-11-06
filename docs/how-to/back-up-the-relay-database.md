# Backing up (and restoring) the relay database

Nightly snapshots of the relay's SQLite database to object storage, and the restore that makes them
worth taking.

⚠⚠ **Until 2026-09-14 there was no backup at all**, on either provider. That was survivable only by
accident: after the DigitalOcean → Scaleway cutover the old box sat stopped-but-intact, so a second
copy of the sync log and a duplicate of every content blob happened to exist. Destroying it — the
last step of the provider move — removes both. This exists so that step is safe.

---

## What is protected, and what is not

| | covered | |
|---|---|---|
| `relay.db` — `sync_block`, `device`, `content_blob`/`content_edge`/`content_ref`, shares, grants, mailboxes | ✅ nightly, two regions | one self-contained file |
| content blobs (5.35 GB) | ⚠ weekly mirror, two regions | see below |

### What lives where

⚠ The second-region rows, the classes and the keys are **prepared, not applied** until the maintainer
runs [*Applying the backup hardening*](#applying-the-backup-hardening) below. Until then only the
fr-par rows exist and every job runs on the one admin key.

| what | bucket | region | class | written by | key | can the box delete it? |
|---|---|---|---|---|---|---|
| relay DB snapshots | `tenshu-relay-db-backups` | fr-par | Standard (Multi-AZ) | `backup-relay-db.sh`, 03:17 | relay-backup | no — lifecycle expires at 30 d. ⚠ can overwrite |
| relay DB snapshots, copy | `tenshu-relay-db-backups-ams` | nl-ams | One Zone | the same run, second upload | relay-backup | **no, nor overwrite** — versioned + Object Lock 30 d |
| content mirror | `tenshu-relay-blobs-mirror` | fr-par | One Zone | `mirror-blobs.py`, Sun 04:40 | blob-mirror | yes — pruning needs it (off by default) |
| content mirror, copy | `tenshu-relay-blobs-mirror-ams` | nl-ams | One Zone | `mirror-blobs.py --target dr`, Sun 05:40 | blob-mirror | **no** — ⚠ can overwrite |
| commerce dumps | `tenshu-commerce-db-backups` | fr-par | Standard | `backup-commerce-db.sh`, 03:42 | commerce-backup | no. ⚠ can overwrite |
| commerce dumps, copy | `tenshu-commerce-db-backups-ams` | nl-ams | One Zone | the same run | commerce-backup | **no, nor overwrite** |

Terraform: buckets in `deploy/scaleway-relay-backups` and `deploy/scaleway-commerce-backups`; keys and
bucket policies in [`deploy/scaleway-backup-access`](../../deploy/scaleway-backup-access).

### The content blobs: a mirror that deletes only after a window

`mirror-blobs.py` + a weekly timer (Sundays 04:40 UTC) copy the content bucket into
`tenshu-relay-blobs-mirror` (server-side `CopyObject`, so the bytes never transit the box and there is
no egress cost).

⚠⚠ **It never deletes on the source's say-so alone, and that is the point.** The threat is not
Scaleway losing a disk — object storage is already replicated within a region, so a same-region mirror
buys nothing there. The threat is **logical** deletion: the relay's content sweep removes blobs, and a
bug or an operator there can delete content no device still holds. A mirror that propagated deletes at
once would propagate exactly that failure.

⚠ The same-region mirror does **not** survive a regional loss; the nl-ams copy
(`mirror-blobs.py --target dr`) does. It runs the same code with its **own ledger** in its own bucket and
its **own prune switch**, `MIRROR_DR_PRUNE` — see [*Storage classes, the second region and the
keys*](#storage-classes-the-second-region-and-the-keys).

#### Pruning: the first-seen-missing ledger

The mirror cannot tell from its own objects when the source deleted a key (a lifecycle rule expires by
object *age*, and mirrored objects are old by design — a 30-day rule would delete the most valuable
contents first). So the script keeps a ledger **in the mirror bucket**, at
`_tenshu-mirror/missing-since.json`, so it survives a box rebuild. Per missing key it holds the run
that **first** saw it missing, the last run that counted a sighting, and the number of sightings; for
the whole ledger, the **source** it was measured against (bucket and endpoint — the script mirrors
the whole bucket, so `S3_KEY_PREFIX` is not part of it), the source's object count and bytes at the
last run, the source's **peak** object count (for the slow-drain guard), the last run's time, and any
**hold**. Each entry also records whether an override **accepted** it.
A v1 ledger (the first version of this script) is upgraded in place: each `since` is kept, at one
sighting.

- A key that reappears in the source leaves the ledger (its clock restarts if it goes again).
- A key is **prunable** once it has been missing for **≥ `MIRROR_RETENTION_DAYS`** (default 30) by the
  clock **and** on **≥ ceil(window/7)+1 runs** (6 at 30) that are at least 6 days apart — so a clock
  that jumps once adds one sighting, not a window, and extra manual runs add none.
- It is deleted only if `MIRROR_PRUNE=on` and every guard below passes.
- The ledger is written whether or not pruning is on: recording deletes nothing, and it means the
  window has already run when pruning is turned on. It is one `PutObject` (S3 replaces an object
  atomically), read back after writing, written *before* any delete and again after.
- `_tenshu-mirror/` is reserved: never copied from the source (a source key there is skipped with a
  warning, so it cannot overwrite the ledger), never pruned, never counted.

⚠⚠ **What the window means in practice.** With punctual weekly runs a deleted file's blocks leave the
mirror **35–42 days** after the delete (first seen 0–7 days after it, pruned at the sixth sighting,
35 days on), and never under 30. ⚠ That is the normal case, **not an upper bound**: a failed run, a
**late** run (a catch-up run less than 6 days after the previous one adds no sighting — up to 49 days
observed), a tripped breaker (held for review), a clock refusal, a dead timer and the first-enable
backlog all postpone it, and nothing makes up the delay. `acceptable-use-and-limits.md` §4.4 says so. Watch
`systemctl list-timers tenshu-blob-mirror.timer` and `systemctl --failed`: unlike the DB snapshots'
lifecycle rule, this pruner stops when the script stops.

#### Safety rails

⚠⚠ **The copy always runs; only pruning fails closed.** A bad setting, an unreadable or malformed
ledger (including a hand edit — never edit it; clear a hold with `--override-breaker`), a clock
anomaly, a guard that raises, or a tripped guard stops the prune and makes the run exit non-zero,
never the additive copy. Every guard lives in one function (`assess`) that `--dry-run` and the real
run share.

⚠⚠ **It never loses the first copy.** A source object whose size differs from the mirror's copy is
either a corrupt write or a relation-slice re-seal, and the two **cannot be told apart by key**:
snapshots (`RelationSliceSnapshot.ref`), manifests (`ContentManifestRef`) and content chunks are all
`<ownerId>/blob-<64 hex>`. So before overwriting, the run copies the mirror's current object to
`_tenshu-mirror/prior/<key>` — **only if no prior exists**, because a content blob never legitimately
changes and the first prior is the trustworthy one (for a re-seal it is the oldest version, which is
fine) — confirms its size, then overwrites the main key and logs `⚠ SIZE CHANGED <key> (prior kept
at …)`, exit 0. If the prior cannot be saved, the main key is **not** overwritten and the run exits
**9**. Prior keys sit under the reserved prefix: outside every count, breaker and the missing logic,
deleted together with their main key when it is pruned, never on their own. A prior whose main key is
in neither the mirror, the source nor the ledger (a manual delete, or a prior whose delete failed) is
reported as `ORPHANED PRIOR` every run; it is deleted only on a run that is allowed to prune (and
after the same fresh-listing and HEAD check that its key is gone from the source), and while it
remains the run exits **10**. ⚠ A `SIZE CHANGED` on a
key that is not a snapshot is a corrupt source object — see *Restoring from the mirror*.

| rail | what it does |
|---|---|
| off by default | `MIRROR_PRUNE` must be exactly `on`. Any bad `MIRROR_*` value (`yes`, `30.5`, a fraction outside (0, 1), a window outside [7, 3650]) disables pruning for the run and exits **2**; the copy still runs. |
| same source | The ledger records bucket and endpoint. A different source records nothing, prunes nothing, exits **2**. `--accept-source-change` adopts a deliberate move and **restarts every clock**. |
| clock | Refuses (records nothing, prunes nothing, exit **5**) if the clock is before the last run, more than 21 days after it, or more than 15 min off the S3 endpoint's `Date` header (compared with the **first** response, so a long listing is not skew). ⚠ No readable `Date` header fails closed the same way: the copy runs, nothing is pruned. `--accept-clock` records such a run (after a box was down for weeks, say) but prunes nothing in it. |
| breakers | All measured against the **pre-copy** mirror, so a flood of copies cannot dilute them: keys newly missing **this run** > `MIRROR_PRUNE_MAX_NEW_MISSING_FRACTION` (0.10) of the mirror by count or bytes — the mass-deletion signal, which ordinary churn does not reach; **total** missing > `MIRROR_PRUNE_MAX_TOTAL_MISSING_FRACTION` (0.50), or > `MIRROR_PRUNE_MAX_MISSING_GB` if set (unset by default); copies this run > `MIRROR_PRUNE_MAX_COPY_FRACTION` (0.50) of the mirror (a different or flooded source); the source's object count down > 25 % since the last run; ⚠ and down > 30 % from the ledger's **peak** source count — the **slow drain** that stays under every per-run limit. The peak is max(peak, current) at every recorded run and is reset to the current count only by `--override-breaker` (or an accepted source change), so a drain of any rate is caught once it has taken 30 % of the largest the source has been: at 6.5 %/week, in week 6, before the first prune. ⚠ **Uploads mask a drain**: the peak grows with them, so a drain offset by uploads, or one that follows a burst of uploads, is caught only once the source is 30 % below that higher peak, and objects can be pruned on the way down. On a new ledger only the total applies (everything would read as new). An empty source listing trips at 100 %. The GB cap is unset by default and in no Terraform variable: the fractions scale with the dataset, a fixed number would not. |
| hold | A tripped breaker sets a **hold** in the ledger, and pruning stays off until a run with `--override-breaker` — a flag, never an env setting, so it is one deliberate run. A breaker cannot quietly un-trip next week. The override marks every key missing at that moment **accepted** — which exempts them from the **total-missing** breaker only — and resets the peak to the current source count; the drain guards keep watching from there. Accepted keys are pruned at their sixth sighting like any other. ⚠⚠ The run that **newly sets** a hold exits **2 even with pruning off** (production today), so a mass deletion shows in `systemctl --failed`. Under an existing hold, a run with pruning off exits 0 **unless something happened this run** (keys newly missing, a copy flood, a drop since the last run): that exits 2 too, and the new reason is appended to the hold's `events`, so a second incident is never silenced by the first. |
| complete listings only | Every listing is paginated to the end; a page that claims truncation without a continuation token, or repeats a token, aborts the run (exit **3**) before anything is written. |
| re-check before delete | Candidates are checked against a **fresh** source listing and a `HEAD` per key immediately before deletion. Only a definite 404 counts as absent; a key the source has, a 403, a 5xx or a timeout aborts (exit 3), prunes nothing. |
| no prune after a failed copy | If any source object is still absent from the mirror after copying, the ledger is recorded and the run exits **1** without pruning; a size change whose prior copy cannot be saved exits **9** (above). |
| one at a time | `flock` on `/run/lock/tenshu-blob-mirror.lock` — `…-dr.lock` for `--target dr`, so the two mirrors never block each other (`MIRROR_LOCK_FILE` overrides either); a second run, or a lock file that cannot be opened, exits **6** — there is no fallback path, because a different file would exclude nothing. ⚠ The lock is per host: a run from a maintainer's checkout is **not** excluded by the box's lock, so never run both at once. |
| logs | Each run logs the missing set, new/returned keys, what is prunable, every refusal, and `pruned: N objects, B bytes`. |

Exit codes: 0 ok · 1 copy incomplete · 2 prune refused (guard, newly set hold, setting, source
change) · 3 listing incomplete · 4 ledger unreadable, malformed or not stored · 5 clock or no server
date · 6 lock held or unopenable · 7 some prune deletes failed (they stay in the ledger) · 8 other S3
or unexpected error, missing boto3 or tfvars (one line, no traceback) · 9 a size-changed object's
prior copy could not be saved (main key not overwritten) · 10 orphaned prior copies remain (below).

```bash
python3 mirror-blobs.py --dry-run        # what would be copied AND pruned, and which guard refuses; writes nothing
python3 mirror-blobs.py --prune-report   # the prune half only; writes nothing
```

#### Turning pruning on

⚠⚠ **A deploy-time decision** (`retention-quota-model.md`): once on, a reclamation bug noticed more
than `MIRROR_RETENTION_DAYS` after it happened is no longer recoverable from the mirror. The settings
live in `/opt/tenshu/deploy.env`; `terraform output blob_mirror_settings_for_deploy_env` in
`deploy/scaleway-relay-backups` renders them from `blob_mirror_retention_days` / `blob_mirror_prune`.

```bash
# 0. check the box's systemd honours EnvironmentFile= on a transient unit: non-zero = it does,
#    0 = it ignored the property (use the throwaway unit below). After step 4, `grep -c "^MIRROR_"`.
ssh root@<box> 'systemd-run --pipe --wait --collect -p EnvironmentFile=/opt/tenshu/deploy.env /usr/bin/env | grep -c "^S3_"'

# 1. ship the script and unit (from a checkout)
scp deploy/scaleway-relay-backups/mirror-blobs.py root@<box>:/opt/tenshu/
scp deploy/scaleway-relay/tenshu-blob-mirror.service root@<box>:/etc/systemd/system/
ssh root@<box> 'systemctl daemon-reload'

# 2. dry run with the unit's own environment — writes nothing
ssh root@<box> 'systemd-run --pipe --wait --collect -p EnvironmentFile=/opt/tenshu/deploy.env \
  /usr/bin/python3 /opt/tenshu/mirror-blobs.py --dry-run'

# 3. one real run with pruning still off — starts the ledger
ssh root@<box> 'systemctl start tenshu-blob-mirror.service; tail -30 /var/log/tenshu-blob-mirror.log'

# 4. switch it on (any time; nothing is pruned until a key has six weekly sightings)
ssh root@<box> 'grep -q "^MIRROR_PRUNE=" /opt/tenshu/deploy.env \
  && sed -i "s/^MIRROR_PRUNE=.*/MIRROR_PRUNE=on/" /opt/tenshu/deploy.env \
  || printf "MIRROR_PRUNE=on\nMIRROR_RETENTION_DAYS=30\n" >> /opt/tenshu/deploy.env'
```

If step 0 prints `0` (the box's systemd ignores the property), run the one-off flags through a
throwaway copy of the unit instead — same environment, same log:

```bash
ssh root@<box> 'sed "s|^ExecStart=.*|ExecStart=/usr/bin/python3 /opt/tenshu/mirror-blobs.py --dry-run|" \
    /etc/systemd/system/tenshu-blob-mirror.service > /run/systemd/system/tenshu-blob-mirror-once.service &&
  systemctl daemon-reload && systemctl start tenshu-blob-mirror-once.service;
  rm /run/systemd/system/tenshu-blob-mirror-once.service; systemctl daemon-reload;
  tail -30 /var/log/tenshu-blob-mirror.log'
```

⚠ **The backlog.** Everything the mirror accumulated before the ledger existed (including the CR-2
reclaims) is recorded at step 3 as missing *since step 3*. That first run is a baseline, so only the
total breaker applies: if the backlog is over half the mirror, step 3 trips it, sets the hold and
**exits 2** (pruning off or not). Then:

1. Account for the backlog — `--prune-report` lists it; it should match deletes and reclaims you know
   about. Until you act, nothing is ever pruned, and with pruning on every run exits 2.
2. Run once with `--override-breaker` (the throwaway unit with that flag; any week). It clears the hold,
   marks every key missing at that moment **accepted** (so the backlog no longer counts towards the
   total-missing breaker) and resets the peak to the current source count.
3. The backlog is pruned at its sixth weekly sighting (≥ 35 days after step 3), by the timer, with no
   further action — provided pruning is on by then.

If step 3 does not trip (the backlog is under half the mirror), skip 1–2.

⚠ **During an incident**, set `MIRROR_PRUNE=off` (or `systemctl stop tenshu-blob-mirror.timer`) before
anything else. The breakers catch a sudden mass deletion (per run), a wrong source, and a slow drain
once the source is 30 % below its peak — ⚠ not a slow drain hidden by uploads, and not anything
smaller than 30 % of the peak.

#### Restoring from the mirror

- Only keys deleted from the source **within the window** are still there. Copy them back with
  server-side `CopyObject` from the mirror to the content bucket, ⚠⚠ **excluding `_tenshu-mirror/`**.
- A key restored to the source leaves the ledger at the next run on its own.
- **A corrupt overwrite** (`SIZE CHANGED` in the log for a key that should not change): the original
  bytes are at `_tenshu-mirror/prior/<key>`. Copy them back to `<key>` in the **content bucket**
  (server-side `CopyObject`, source `tenshu-relay-blobs-mirror/_tenshu-mirror/prior/<key>`), and to
  `<key>` in the mirror too. The next run sees equal sizes and changes nothing; the prior stays (it
  is only ever removed with a prune of its main key). ⚠ For a relation-slice snapshot the prior is
  the *oldest* sealed version, not the previous one — a joiner replays the log, so restoring it is
  never needed.
- The mirror can be up to a week behind the source: a blob uploaded and deleted between two runs was
  never mirrored.
- Restoring blobs does not restore the relay's rows for them (`content_ref`, edges); that comes from
  the DB snapshot, and the two are from different instants.

⚠⚠ **The content bucket was single-copy until this existed.** It has no versioning — a
deliberate choice, because blobs are content-addressed (`blob-<sha256(ciphertext)>`), so a re-PUT
writes identical bytes and versioning would only double the bill and defeat `dropRefs`. Copying
5.35 GB nightly is not the answer either. The real mitigation is that a blob is re-uploadable by any
device that still holds the file; the exposure is content whose only copy was the relay's. Size it
before deciding it is acceptable.

## How it works

`deploy/scaleway-relay/backup-relay-db.sh`, run by a systemd timer at 03:17 UTC.

⚠⚠ **`VACUUM INTO`, never `cp`.** A `cp` of a live WAL database can capture the main file and the WAL
from different instants — a torn snapshot that opens cleanly and is quietly short. (The related trap,
copying `relay.db` without its `-wal`, yields a valid and completely **empty** database with no error
anywhere: [`move-the-relay-to-another-provider.md`](move-the-relay-to-another-provider.md).)
`VACUUM INTO` is one of SQLite's two documented online-safe routes, needs no downtime, and writes a
fully checkpointed file with **no WAL beside it** — which is what makes the restore a single file
drop. Needs SQLite ≥ 3.27; the box has 3.37.2.

The script refuses to upload a suspect snapshot: it retries `VACUUM INTO` on a transient lock, then
checks that the file passes `integrity_check`, that its `schema_version` matches the live database's,
that its `realm` table reads, and that it is not suspiciously small (below). After uploading, it
**reads the object's size back** rather than trusting a silent `PUT`.

⚠⚠ **The size floor is RELATIVE, and it can refuse a legitimate shrink.** The script refuses a
snapshot under **50 %** of the previous upload (`BACKUP_MIN_FRACTION`), unless `/opt/tenshu/reset-epoch`
is newer than that upload. The reset wipe writes that file, so the first snapshot after a reset goes
through. An absolute floor would have refused every post-reset night (an empty relay database is ~272 KiB, 3.6 KiB gzipped). When the
shrink is real — a big deletion, a purge — the run fails with `/fail` and says so. There are two ways
out, both on the box:

- **One run:** `date -u +%s > /opt/tenshu/reset-epoch`, then `systemctl start tenshu-relay-backup.service`. The next
  upload after it is exempt, then the floor applies again against the new, smaller size.
- **Standing:** set `BACKUP_MIN_FRACTION=0.2` (or `0` to disable) in `/opt/tenshu/deploy.env`. The
  script sources it.

⚠ **The floor needs `s3:ListBucket` on the backup bucket** (it lists `relay-db/` to find the previous
upload), not just `PutObject`/`HeadObject`. Until the backup hardening is applied the backup key is the
project-wide Scaleway API key (see `deploy/scaleway-relay-backups/outputs.tf`); no bucket policy narrows it,
so it can list. The scoped `relay-backup` key keeps `ListBucket` on the **fr-par** bucket for this
(`deploy/scaleway-backup-access`, pinned by its `terraform test` and checked live by `probe-keys.py`); keep it
there, or every night fails with *"cannot list"*. The floor runs on the fr-par upload only, so nl-ams is never
listed and its policy grants no `ListBucket`.

⚠⚠ **Retention is applied by `set-lifecycle.py`, not by Terraform.** The Scaleway provider
serialises `expired_object_delete_marker` alongside `days`, and Scaleway's S3 rejects the pair —
`MalformedXML: Cannot have a Expiration.Date or Expiration.Days with Expiration.ExpiredObjectDeleteMarker`.
It fails *after* the bucket is created, leaving the resource **tainted**, so the next plan proposes
destroying and recreating a bucket whose name is globally unique and not immediately re-creatable. If
you hit that: `terraform untaint scaleway_object_bucket.relay_backups`, then run the script. There is
no standalone lifecycle resource in the provider (2.82.0) to route around it.

⚠ Retention is still a **bucket lifecycle rule** (30 days), not a pruning step in the script. A pruner that
only runs when the backup runs stops pruning at exactly the moment backups stop working — which is
when the bucket is growing fastest and nobody is looking.

⚠ The temp snapshot is written to the **data** volume, never `/`. Root is 20 GB and holds every docker
image layer; a backup job that fills it takes the relay down with it.

## Restore

The snapshot is a whole database, so (⚠⚠ read *Re-applying erasures after a restore* below first: it
adds a step before 3 and one straight after 4):

```bash
# 1. fetch and unpack (pick the snapshot you want; keys are relay-db/<year>/relay-<stamp>.db.gz)
#    ⚠ list with the BACKUP_S3_* credentials, not the content-plane ones — different bucket.
gunzip relay-20260914T031700Z.db.gz

# 2. stop the relay
cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml \
  --env-file /opt/tenshu/deploy.env stop relay

# 3. put it in place — ⚠⚠ and delete any stale WAL beside it, or SQLite replays the OLD database's
#    tail over your restored file
mv relay-20260914T031700Z.db /mnt/relay_data/relay.db
rm -f /mnt/relay_data/relay.db-wal /mnt/relay_data/relay.db-shm

# 4. start, and verify counts before believing it
docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env up -d relay
sqlite3 -readonly /mnt/relay_data/relay.db "SELECT COUNT(*) FROM sync_block; SELECT COUNT(*) FROM device;"
```

### Re-applying erasures after a restore

⚠⚠ **A snapshot older than an erasure brings the erased data back**: the realm, devices, the log,
shares, everything `AccountErasure` deleted. It does **not** contain the `erased_realm` row either, so
the restored relay would even accept the realm being re-created. Every owner erased after the
snapshot's timestamp, and every closure that came due after it, must be erased again before the relay
serves anyone. That is what the notice promises (a closed account's data is gone after 30 days), and
a restore does not suspend the promise.

**Where the list comes from.** The restored file cannot supply it. These sources survive:

1. **Commerce's database**, the authoritative one. Commerce re-posts a closure record for **every**
   closed account whose data store no open account holds, every sweep, forever (`AccountClosureDelivery`;
   the relay keeps no closure state by design). So as long as commerce was **not** restored too, its
   next sweep re-erases every owner past grace, and the relay writes `erased_realm` again. The sweep
   runs at commerce **boot** and then every `ACCOUNT_CLOSURE_SWEEP_MINUTES` (60).
2. **The live `relay.db` you are replacing**, if it is still readable. Its `erased_realm` holds every
   erasure up to the failure. Save it **before step 3**:
   ```bash
   sqlite3 -readonly /mnt/relay_data/relay.db "SELECT owner_id, erased_at FROM erased_realm" > /root/erased-before-restore.txt
   ```
3. **The erasure request log** ([`policy/erasure-request-runbook.md`](../policy/erasure-request-runbook.md)
   §1), kept outside both databases. It lists only requests that came by email; self-service
   deletions are not in it.

**The step, straight after 4.** Restart commerce so its boot sweep runs now rather than within the
hour, then confirm it reclaimed:

```bash
# on the commerce box
ssh root@<commerce-box> "cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml \
  -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env restart commerce"
ssh root@<commerce-box> 'docker logs --since 5m commerce-service-commerce-1 2>&1 | grep "Account-closure sweep"'
# expect "… ×200 reclaimed" for the owners past grace; ⚠ a run of 401s means nothing was erased

# what commerce says must be erased (closed ≥ 30 d ago, no open account holding the owner):
ssh root@<commerce-box> "docker exec commerce-service-db-1 psql -U commerce -d commerce -At -c \"
  SELECT o.owner_id FROM account a
  JOIN account_owner o ON o.account_id = a.id AND o.superseded_at IS NULL
  WHERE a.closed_at IS NOT NULL
    AND a.closed_at < (extract(epoch from now()) * 1000)::bigint - 30::bigint * 86400000
    AND NOT EXISTS (SELECT 1 FROM account_owner o2 JOIN account a2 ON a2.id = o2.account_id
                    WHERE o2.owner_id = o.owner_id AND o2.superseded_at IS NULL AND a2.closed_at IS NULL)\"" \
  > /tmp/owners-due.txt

# on the relay box: each of those, plus each line of erased-before-restore.txt, must be erased
cut -d'|' -f1 /root/erased-before-restore.txt | cat - /tmp/owners-due.txt | sort -u | while read -r O; do
  [ -n "$O" ] || continue
  printf '%s realm=%s erased=%s\n' "$O" \
    "$(sqlite3 -readonly /mnt/relay_data/relay.db "SELECT COUNT(*) FROM realm WHERE owner_id='$O'")" \
    "$(sqlite3 -readonly /mnt/relay_data/relay.db "SELECT COUNT(*) FROM erased_realm WHERE owner_id='$O'")"
done   # every line must read realm=0 erased=1
```

(Copy `/tmp/owners-due.txt` to the relay box, or run the loop where both files are.)

**What this does not cover. Each is a gap, not a step you can take** ([`tech-debt.md`](../tech-debt.md)
*A database restore does not re-apply erasures*):

- ⚠ **An owner in `erased-before-restore.txt` that commerce no longer posts** prints `realm=1 erased=0`,
  or `realm=0 erased=0` when the snapshot predates the realm itself (no data came back, but the marker
  that blocks re-creation is gone). The only route that erases a relay owner or writes the marker is
  `/sync/account/closure` with a commerce-signed record. There is no operator CLI and no admin route,
  and commerce mints only for closed accounts in its own database. If commerce shows that owner's
  account open, closing it again (`/admin/accounts/close`) puts it back on the sweep; otherwise
  nothing can be done until a tool exists. Record the gap in the request log.
  ⚠ If the saved list is missing, an `erased_realm` row lost this way is gone silently.
- ⚠ **The window between `up -d relay` and the sweep** (seconds with the restart above, up to an hour
  without it): the relay serves the resurrected data, and a still-paired device of a closed account
  could sync into it. The sweep erases it again. Nothing keeps the relay dark meanwhile, because
  commerce reaches the closure route through the same front door as clients.
- **Blobs**: an erased owner's bytes were deleted from the bucket and stay deleted. The restored rows
  point at nothing and are erased with the rest. ⚠ **Restoring blobs from the mirror** is different:
  exclude every `<ownerId>/` prefix on the list above, or erased content comes back with no row that
  would ever delete it again.
- **If commerce was restored too**, see the matching section in
  [`back-up-the-commerce-database.md`](back-up-the-commerce-database.md). An older commerce dump
  forgets closures and erasures made after it, so source 1 is short by exactly those.

⚠⚠ **A restore rolls the sync log back to the snapshot's instant.** Clients hold blocks the restored
relay has never seen, and block sequence numbers are relay-assigned — so the relay will re-issue `seq`
values that clients already used for different blocks. Treat a restore as a recovery of last resort,
not routine maintenance, and expect to verify each device afterwards rather than assuming it converged.

⚠ Restoring the database does **not** restore content blobs. If the loss that prompted the restore
also lost bucket objects, the rows will reference blobs that are not there — a client holding the file
can re-upload it, nothing else can.

### Restoring from the second region (nl-ams)

When fr-par is down, or its copies are gone or suspect. The snapshots are the same files under the same
keys (`relay-db/<year>/relay-<stamp>.db.gz`); only the endpoint and bucket differ. Use the **admin** key:
a box key can read back what it wrote (HEAD/GET) but has no `ListBucket` on the nl-ams buckets (it lists
only its fr-par bucket, for the size floor).

```bash
# on a workstation, admin credentials in the environment
python3 - <<'PY'
import boto3, os
s3 = boto3.client("s3", endpoint_url="https://s3.nl-ams.scw.cloud", region_name="nl-ams",
                  aws_access_key_id=os.environ["SCW_ACCESS_KEY"], aws_secret_access_key=os.environ["SCW_SECRET_KEY"])
objs = s3.list_objects_v2(Bucket="tenshu-relay-db-backups-ams", Prefix="relay-db/")["Contents"]
key = max(objs, key=lambda o: o["LastModified"])["Key"]          # ⚠ paginate if you need older ones
s3.download_file("tenshu-relay-db-backups-ams", key, os.path.basename(key)); print(key)
PY
```

Then continue at step 1 above (`gunzip`, stop, drop in, delete `-wal`/`-shm`, start, count).

- ⚠ The bucket is **versioned**. A listing shows the current version of each key; an object that was
  overwritten (by a compromised box, say) still has its earlier versions — `list_object_versions` shows
  them, and `get_object(..., VersionId=…)` fetches one. Compare `ContentLength` with neighbouring nights
  before trusting the newest.
- ⚠ One Zone storage: reads are instant (not Glacier), but the copy lives in one nl-ams zone.
- ⚠⚠ **Nothing older than the last reset.** A test-data reset does not empty nl-ams (D13): a snapshot or
  blob older than `/opt/tenshu/reset-epoch` is pre-reset data and must never reach the relay.
- **Blobs from nl-ams.** Copy keys back from `tenshu-relay-blobs-mirror-ams` into the content bucket.
  ⚠ Not server-side — it is cross-region, so stream them (GET → upload) as `mirror-blobs.py` does, and
  ⚠⚠ **exclude `_tenshu-mirror/`**, and ⚠⚠ every `<owner id>/` prefix whose owner is in `erased_realm` or
  the erasure request log ([`erasure-request-runbook.md`](../policy/erasure-request-runbook.md)): the nl-ams
  mirror is never pruned from the box, so it keeps erased owners' bytes. If the whole region is lost, the relay itself has to be rebuilt in
  another region first; that is a provider move ([`move-the-relay-to-another-provider.md`](move-the-relay-to-another-provider.md)),
  with the nl-ams buckets as the source.

## Verifying it actually works

⭐ A backup nobody has restored is a hypothesis. At least once, restore the newest snapshot onto a
throwaway box (or a copy of the data volume) and check `sync_block` / `device` counts against
production. Reading the log line is not the same check.

```bash
command -v sqlite3 || echo 'MISSING — every run will fail'   # the dependency with no other tell
systemctl list-timers 'tenshu-*'                  # three on the relay box: backup, mirror, mirror-dr
systemctl --failed 'tenshu-*'                     # any unit whose last run exited non-zero
tail -20 /var/log/tenshu-relay-backup.log         # every run appends; failures say FAILED
grep -c 'DR: OK' /var/log/tenshu-relay-backup.log # ⚠ grows by one a night once the second region is on
tail -25 /var/log/tenshu-blob-mirror.log          # look for `pruned:` and CIRCUIT BREAKER
tail -25 /var/log/tenshu-blob-mirror-dr.log       # the nl-ams mirror; same lines, its own ledger
```

⚠ **Exit codes mean different things per job**, and every non-zero one marks the unit failed:
backup **1** = no snapshot at all, **2** = fr-par copy OK but the **nl-ams copy failed**; the mirrors
(both targets) use the codes in the safety-rails table above — **1** copy incomplete, **2** prune refused,
… up to **10** (see the table for each).

⭐ **From outside the box** — the buckets' view, which a box that has stopped logging cannot fake:

```bash
cd deploy/scaleway-backup-access && python3 check-backups.py
# newest snapshot in each of the four snapshot buckets < 26 h; each mirror's ledger `updated` < 8 days
```

⚠ The timer is `Persistent=true`, so a box that was off at 03:17 runs the snapshot at next boot
rather than skipping the day.

⚠⚠ **Reading the log only works if someone reads it.** The commerce backup failed every night for 23
days in October 2026 behind a full disk, with nothing noticing. Both backups ping a dead man's switch
on healthchecks.io: [`box-health.md`](box-health.md), installed with
`deploy/box-health/install.sh <host> relay` (which also re-copies this script).

## Installing it on a box

```bash
# ⚠ BOTH. sqlite3 is not on the base image and is not installed by cloud-init on boxes
# provisioned before 2026-09-14 — the script shells out to it for `VACUUM INTO`, and without
# it every run exits command-not-found having uploaded nothing.
# ⚠ ON THE BOX — the rest of this block runs from a checkout, and a bare apt-get would too
ssh root@<box> 'apt-get install -y sqlite3 python3-boto3'   # boto3 uploads; the box's curl is too old for SigV4
scp deploy/scaleway-relay/backup-relay-db.sh root@<box>:/opt/tenshu/
scp deploy/scaleway-relay/tenshu-relay-backup.{service,timer} root@<box>:/etc/systemd/system/
ssh root@<box> 'chmod +x /opt/tenshu/backup-relay-db.sh &&
  systemctl daemon-reload && systemctl enable --now tenshu-relay-backup.timer &&
  /opt/tenshu/backup-relay-db.sh && tail -5 /var/log/tenshu-relay-backup.log'
```

```bash
# after `terraform apply`, and re-runnable any time to verify what the bucket reports
cd deploy/scaleway-relay-backups && python3 set-lifecycle.py
```

The `BACKUP_S3_*` and `BACKUP_DR_S3_*` settings come from `terraform output
backup_settings_for_deploy_env` in [`deploy/scaleway-relay-backups`](../../deploy/scaleway-relay-backups)
and go in `/opt/tenshu/deploy.env`; the credentials (`BACKUP_S3_ACCESS_KEY` / `_SECRET_KEY`, and the
mirror's `MIRROR_S3_*`) come from `terraform output -raw relay_box_env` in
[`deploy/scaleway-backup-access`](../../deploy/scaleway-backup-access) — see the runbook below for how
to move them onto the box without writing them to a file. ⚠ They are a **separate bucket** from the content plane — nothing in the
relay lists the content bucket (every enumeration goes through the SQLite index), so a backup object
there would not be reclaimed, but the content bucket's object count and total size are load-bearing
evidence during a migration and mixing backups in silently invalidates that check.

## Storage classes, the second region and the keys

Decided by the maintainer on 2026-10-23, from the recommendations this section used to carry. ⚠⚠
**Prepared in code, not applied**: nothing below exists in Scaleway until the runbook that follows has
been run.

### Prices

Scaleway's published fr-par list prices as recorded here on 2026-10-23, excl. VAT: Standard Multi-AZ
€0.000022/GB-h (≈ €0.016/GB-month), Standard One Zone (`ONEZONE_IA`) €0.000011/GB-h (≈ €0.008),
Glacier €0.0000035/GB-h (≈ €0.0025) plus €0.009/GB to restore. Requests and ingress are free; egress
and **inter-region transfer** are €0.01/GB after 75 GB/month free. Scaleway documents no minimum
storage duration for any class. ⚠ **Unverified since**: a search on 2026-10-23 (the pricing page itself
could not be fetched) reported Multi-AZ at €0.00002/GB-h (≈ €0.0146) and One Zone at €0.0000165/GB-h
(≈ €0.012). Check the live page before quoting either; the conclusions below hold under both.

### The mirrors are One Zone (`ONEZONE_IA`)

A mirror is read only in a disaster, and the source it copies is Multi-AZ already, so losing the mirror
needs an AZ loss *and* a logical deletion at once. One Zone keeps reads instant. **Glacier is still not
worth it** at this size (~€0.03/month saved, against a per-object, hours-long, billed restore of
thousands of small objects); revisit around 1 TB.

- **New copies.** `mirror-blobs.py` passes `StorageClass=$MIRROR_STORAGE_CLASS` (default `ONEZONE_IA`;
  `STANDARD` accepted, `GLACIER` refused) on every copy into either mirror, then reads the class back
  from the post-copy listing and **warns** if the endpoint dropped it — a wrong class costs money, not
  data, so it does not fail the run or block a prune. The ledger itself stays Standard.
- **Objects already in the mirror** move with `mirror-blobs.py --migrate-storage-class` — dry run
  unless `--apply`, idempotent (objects already in the class are skipped), and it never touches
  `_tenshu-mirror/` (left out of the work list, and its bytes compared before and after).

  ⚠ **Why a self-copy and not a lifecycle transition rule.** Both are allowed by Scaleway (a transition
  only goes downward, Standard → One Zone is one). The script was picked because it is synchronous and
  checked **per object** — each one is copied onto itself with the new class and then `HEAD`ed for class
  *and* size before it counts. A lifecycle rule runs whenever the provider gets to it, reports nothing,
  would sit on the one bucket whose value is that nothing on it expires (one wrong field — `Expiration`
  for `Transition` — and the mirror is gone), and would show up as drift in every later `terraform plan`
  of a bucket that has no `ignore_changes`. A failed self-copy leaves the original intact (each copy is
  atomic) and is retried by the next run.

### The second region (nl-ams)

Everything used to be in fr-par; a regional loss took every copy at once.

- **Database snapshots and commerce dumps**: the nightly script uploads the same file a second time,
  to `tenshu-*-db-backups-ams`, **after** the fr-par upload is in place and size-verified. A single
  `PUT` with `Content-MD5` (the bucket is Object-Locked, which requires an integrity header; the boxes'
  boto3 1.20 adds none), class One Zone, size read back. ⚠⚠ A failure is loud and harmless to the first
  copy: `FAILED: DR: …` in the log, exit **2**, unit failed — and the step only reads the local file;
  nothing in either script deletes from any bucket. With `BACKUP_DR_S3_BUCKET` unset it logs
  `⚠ DR: … unset` and exits 0, so the rollout can be staged.
- **Retention**: 30 days, through the same `set-lifecycle.py` (the provider bug above), which now covers
  both buckets and adds `NoncurrentVersionExpiration` on a versioned one — expiring the current version
  only makes it noncurrent, which is still stored and billed. ⚠ Whether Scaleway honours that clause is
  checked by the script's read-back, not assumed; if it does not, see the open decisions.
- **Versioning + Object Lock (30 days, GOVERNANCE)** on those two buckets: the box key can write but not
  delete, and on an unversioned bucket an overwrite destroys a snapshot just as well. With versioning
  an overwrite only adds a version and the lock keeps every version for the window. ⚠⚠ Lock can only be
  enabled when a bucket is **created** — it is `dr_snapshot_lock_days`, decide it before the first apply.
- **Blobs**: `mirror-blobs.py --target dr` (unit `tenshu-blob-mirror-dr`, Sundays 05:40) runs the same
  `run()` with a second client. `CopyObject` cannot cross regions, so each object is a GET from the
  source piped into an upload to nl-ams — ⚠ **streamed, never read whole**: resident memory is bounded by
  the transfer config (8 MiB chunks × 4 buffered ≈ 32 MiB), whatever the object size, and the unit's
  `MemoryMax=512M` is the backstop. Its **own ledger** lives in its own bucket, the **same** window,
  breaker and listing rails apply, and pruning is its **own switch**, `MIRROR_DR_PRUNE` (default off).
  The first run carries the whole bucket (~5.35 GB through the box); later runs carry what nl-ams lacks.
  ⚠ The nl-ams mirror is **not** versioned: pruning must remove bytes for the privacy bound to mean
  anything, and on a versioned bucket a delete only adds a marker.

### Keys

Every job used to run on **one** project-wide key — the admin key that also runs Terraform and serves
the relay's content plane (`S3_*`). A compromised relay or commerce box could delete every copy.

⚠⚠ **What Scaleway can express** (it decides the whole design; the full reasoning is in
`deploy/scaleway-backup-access/main.tf`):

1. **IAM permission sets scope to a Project, never a bucket.** `ObjectStorageObjectsRead` / `Write` /
   `Delete` are separate sets, so *write-but-not-delete* is expressible — project-wide.
2. **A bucket policy narrows IAM; it cannot widen it.** A principal needs the IAM set *and*, if the
   bucket has a policy, an `Allow` in it. A bucket with a policy is closed to every principal it does
   not name (the Organization owner excepted) — which is also the lock-out risk.
3. **`Deny` statements do nothing** under the policy version Scaleway uses (`2023-04-17`): everything
   not allowed is already denied. The module writes only `Allow`s.
4. **Nothing distinguishes an overwrite from a new write** (`s3:PutObject` is both). *Write but never
   destroy* is only reachable with versioning + Object Lock — hence the nl-ams snapshot buckets.
5. **An unpoliced bucket is IAM-only**, so every key reaches it with its full project-wide sets. That is
   the content bucket until `enforce_on_content_bucket`.

⚠ (2) and (3) are from Scaleway's documentation as summarised by a search on 2026-10-23 — the pages
could not be fetched from the sandbox that wrote this. `probe-keys.py` is how they get verified live, and
the design fails safe on the part that matters most: the two backup writers hold **no delete set at all**,
so they cannot delete anything in the Project whatever the policies do.

| key | IAM sets (project-wide) | bucket policies narrow it to |
|---|---|---|
| **relay-backup** (relay box, `BACKUP_S3_*`) | BucketsRead, ObjectsRead, ObjectsWrite | Put + Get on the two relay snapshot buckets; **List on fr-par only** (the size floor) |
| **commerce-backup** (commerce box, `BACKUP_S3_*`) | BucketsRead, ObjectsRead, ObjectsWrite | Put + Get on the two commerce buckets; **List on fr-par only** (the size floor) |

⚠ `BucketsRead` is project-wide like every set. Until a bucket is policed (fr-par at step 11, content at
step 12), both backup keys can **list** it, not just GET keys they already know: the content bucket, the
fr-par mirror and each other's dumps. `probe-keys.py` reports those cross-key listings as "IAM only" until
the switch is on, and as a failure after.
| **blob-mirror** (relay box, `MIRROR_S3_*`) | BucketsRead, ObjectsRead, ObjectsWrite, ObjectsDelete | List/Get/Put/**Delete** on the fr-par mirror; List/Get/Put, **no Delete**, on the nl-ams mirror; List/Get on the content bucket once policed |
| **relay-content** (relay box, `S3_*`) | BucketsRead, ObjectsRead, ObjectsWrite, ObjectsDelete | the content bucket only, once policed; named on no backup bucket |

- **Pruning needs delete.** `MIRROR_PRUNE=on` deletes from the fr-par mirror, so the mirror key holds the
  Delete set and the fr-par mirror's policy allows it. The nl-ams mirror's policy does **not**: with
  `MIRROR_DR_PRUNE` off (the default) that copy cannot be emptied from the box. Pruning it is an admin
  run from a workstation — an open decision below.
- **What a fully compromised relay box can still do** (all four relay-box keys, after the whole runbook):
  delete content blobs (the relay's sweep needs that right) and the fr-par mirror; **overwrite** objects
  in the fr-par snapshot bucket and the nl-ams mirror. It can **not** delete or overwrite any nl-ams
  snapshot version for 30 days, delete anything in the nl-ams mirror, or touch commerce's buckets.
- **What a compromised commerce box can still do**: overwrite fr-par commerce dumps; and, until
  `enforce_on_content_bucket`, read and **overwrite** content blobs (IAM-only bucket, rule 5). Not delete
  anything, anywhere.
- ⚠⚠ **The relay-box split is nominal until the relay's content key is rotated (runbook step 10).** While
  `S3_ACCESS_KEY` on the box is still the admin key, that key reaches every bucket with full rights, and
  the narrower backup keys beside it protect nothing.

## Applying the backup hardening

The maintainer's offline runbook. Ordered; each step that deletes or rotates anything has its check
**first**, and every check that fails means **stop**, not "carry on and fix later". `<relay>` and
`<commerce>` are the boxes' SSH targets. All Terraform runs use the **admin** key in each module's
`terraform.tfvars`.

**0. Before anything.** Work from current `master` (it carries the mirror pruning). Settle open
decisions **D1** and **D2** below — Object Lock is fixed when the nl-ams buckets are created in step 1.
Note the admin key's access key (`SCW…`) as `$OLD_AK`; it is what step 11 revokes.

⚠⚠ **And the privacy notice first (D12).** `web/apex/support-pages/privacy.ts` says, in all three
languages, that all data stays in the Paris region: the Scaleway processor entry (*"alle Daten liegen in der
Region Paris"*), the transfers paragraph (*"Server, Datenbanken und Sicherungen liegen in der EU (Paris)"*)
and the relay paragraph (*"verschlüsselte Dateiinhalte liegen im Objektspeicher von Scaleway in derselben
Region"*). Step 1 makes that untrue for the snapshots, the dumps and the blob mirror. Ship the amended
notice (German binding, then EN/FR; bump `PRIVACY_POLICY_DATE` and the `PRIVACY_VERSION` copies the file's header lists, and go through `docs/policy/privacy-review-checklist.md`) **before** step 1,
not after: the notice has to be true from the first nl-ams object on. Also check the retention wording:
an nl-ams snapshot lives up to ~31 days (30-day expiry, then 1 day as a noncurrent version), where the
notice says 30.

**1. Create the nl-ams buckets.**

```bash
cd deploy/scaleway-relay-backups      # add the dr_* lines from terraform.tfvars.example to terraform.tfvars
terraform init && terraform plan -out=dr.plan
```

✅ **Check before applying:** the plan *only adds* — `relay_backups_dr`, its lock configuration,
`blob_mirror_dr`. ⛔ Any `~` or `-` on `relay_backups` or `blob_mirror` (in particular a `lifecycle_rule`
change) means stop. Then `terraform apply dr.plan`. Same in `deploy/scaleway-commerce-backups`
(adds `commerce_backups_dr` + its lock).

**2. Retention on the nl-ams buckets.** In each of the two modules:

```bash
python3 set-lifecycle.py          # primary + dr; prints what each bucket REPORTS afterwards
```

✅ Expect `OK` for both buckets, the nl-ams one with `noncurrent_days=1 versioned=True`. The fr-par rule is
re-put unchanged (`expiration_days=30`). ⛔ `FAILED … with noncurrent-version expiry` = Scaleway rejected or
ignored the clause → open decision **D6** before going on.

**3. Mint the keys and police the new buckets.**

```bash
cd deploy/scaleway-backup-access && cp terraform.tfvars.example terraform.tfvars   # fill in
terraform init && terraform test && terraform plan -out=keys.plan
```

✅ **Check before applying** (a bucket policy that omits you locks you out of restores):
`admin_principals` holds your user id **and** the principal that owns `$OLD_AK` (console → IAM → API
keys → `$OLD_AK` → bearer). The plan creates four applications, four policies, four API keys and
**exactly three** bucket policies, all on `*-ams` buckets — none on a fr-par bucket. Read the three
policy documents in the plan. Then `terraform apply keys.plan`.

**4. Prove the keys before any box uses them.**

```bash
python3 probe-keys.py
```

✅ Every line `ok` or `IAM only — not policed yet`; final `PASS`. ⛔ Any `UNEXPECTED` → stop; the most
likely cause is a permission-set name or the IAM/policy reading in *Keys* being wrong, and that is
exactly what this step exists to catch before production depends on it.

**5. Relay box: install the new scripts and units** (nothing changes behaviour until step 6's env lines):

```bash
ssh <relay> 'cp -p /opt/tenshu/deploy.env /root/deploy.env.pre-hardening && grep -q "^S3_BUCKET=." /root/deploy.env.pre-hardening && echo saved'
scp deploy/scaleway-relay/backup-relay-db.sh deploy/scaleway-relay-backups/mirror-blobs.py <relay>:/opt/tenshu/
scp deploy/scaleway-relay/tenshu-blob-mirror{,-dr}.{service,timer} deploy/scaleway-relay/tenshu-relay-backup.{service,timer} <relay>:/etc/systemd/system/
ssh <relay> 'chmod +x /opt/tenshu/backup-relay-db.sh && systemctl daemon-reload'
```

✅ `saved` printed — that copy is the rollback for steps 6–8. ⛔ No `saved`, no step 6.

**6. Relay box: the new env lines, and rotate the backup job onto its own key.**

```bash
# a) non-secret lines: append once (skip any already present)
( cd deploy/scaleway-relay-backups && terraform output -raw backup_settings_for_deploy_env \
    && terraform output -raw blob_mirror_settings_for_deploy_env ) \
  | ssh <relay> 'f=/opt/tenshu/deploy.env; while IFS= read -r l; do k=${l%%=*}; grep -q "^$k=" $f || echo "$l" >> $f; done; grep -c "^BACKUP_DR_S3_\|^BLOB_MIRROR_DR_" $f'
# b) the keys: piped, never written to a file in the checkout. Replaces BACKUP_S3_ACCESS/SECRET_KEY,
#    adds MIRROR_S3_*; refuses unless exactly four key lines result.
( cd deploy/scaleway-backup-access && terraform output -raw relay_box_env ) \
  | ssh <relay> 'set -e; f=/opt/tenshu/deploy.env; t=$(mktemp); trap "rm -f $t" EXIT
      grep -v -E "^(BACKUP_S3_ACCESS_KEY|BACKUP_S3_SECRET_KEY|MIRROR_S3_ACCESS_KEY|MIRROR_S3_SECRET_KEY)=" $f > $t
      cat >> $t
      [ "$(grep -c -E "^(BACKUP_S3|MIRROR_S3)_(ACCESS|SECRET)_KEY=." $t)" = 4 ] && grep -q "^S3_BUCKET=." $t || { echo "STOP: not rotated" >&2; exit 1; }
      cat $t > $f && echo rotated'
```

✅ a) prints `5` (three `BACKUP_DR_S3_*`, two `BLOB_MIRROR_DR_*`); b) prints `rotated`. ⛔ Anything else
→ `ssh <relay> 'cp -p /root/deploy.env.pre-hardening /opt/tenshu/deploy.env'` and stop.

Then one of each run, by hand, **before** the timers do it unattended:

```bash
ssh <relay> '/opt/tenshu/backup-relay-db.sh; rc=$?; tail -6 /var/log/tenshu-relay-backup.log; exit $rc'
#   ✅ rc 0, "uploaded … to primary", "uploaded … to dr", "DR: OK"
ssh <relay> 'systemd-run --pipe --wait --collect -p EnvironmentFile=/opt/tenshu/deploy.env /usr/bin/python3 /opt/tenshu/mirror-blobs.py --dry-run'
ssh <relay> 'systemd-run --pipe --wait --collect -p EnvironmentFile=/opt/tenshu/deploy.env /usr/bin/python3 /opt/tenshu/mirror-blobs.py --target dr --dry-run'
#   ✅ no "MIRROR_S3_ACCESS_KEY unset" warning; target lines name the right buckets; "to copy" for dr ≈ the whole bucket
ssh <relay> 'systemctl start --no-block tenshu-blob-mirror-dr.service'   # the first fill, ~5.35 GB; watch:
ssh <relay> 'tail -f /var/log/tenshu-blob-mirror-dr.log'                 #   ✅ ends "OK — every source object is present"
ssh <relay> 'systemctl enable --now tenshu-blob-mirror-dr.timer && systemctl list-timers "tenshu-*"'
```

**7. Move the fr-par mirror onto One Zone** (rewrites objects in place; deletes nothing).

```bash
ssh <relay> 'systemd-run --pipe --wait --collect -p EnvironmentFile=/opt/tenshu/deploy.env \
  /usr/bin/python3 /opt/tenshu/mirror-blobs.py --migrate-storage-class'           # dry run
```

✅ **Check before `--apply`:** the dry run's object count matches the mirror's (`mirror …: N objects` in the
last weekly log), the reserved prefix count is the ledger plus any `prior/` copies (all left alone), and no mirror run is in progress
(`systemctl is-active tenshu-blob-mirror.service` → `inactive`). Then the same line with `--apply`.
✅ `moved: N failed: 0`; re-running reports `to move …: 0 object(s)`. ⛔ `failed > 0` → re-run (it retries
only those); `still STANDARD` repeatedly → the endpoint ignores the class, open decision **D7**.

**8. Commerce box: the same as 5–6.**

```bash
ssh <commerce> 'cp -p /opt/tenshu/deploy.env /root/deploy.env.pre-hardening && grep -q "^BACKUP_S3_BUCKET=." /root/deploy.env.pre-hardening && echo saved'
scp deploy/scaleway-commerce/backup-commerce-db.sh <commerce>:/opt/tenshu/
( cd deploy/scaleway-commerce-backups && terraform output -raw backup_settings_for_deploy_env ) \
  | ssh <commerce> 'f=/opt/tenshu/deploy.env; while IFS= read -r l; do k=${l%%=*}; grep -q "^$k=" $f || echo "$l" >> $f; done; grep -c "^BACKUP_DR_S3_" $f'
( cd deploy/scaleway-backup-access && terraform output -raw commerce_box_env ) \
  | ssh <commerce> 'set -e; f=/opt/tenshu/deploy.env; t=$(mktemp); trap "rm -f $t" EXIT
      grep -v -E "^BACKUP_S3_(ACCESS|SECRET)_KEY=" $f > $t; cat >> $t
      [ "$(grep -c -E "^BACKUP_S3_(ACCESS|SECRET)_KEY=." $t)" = 2 ] && grep -q "^BACKUP_S3_BUCKET=." $t || { echo "STOP: not rotated" >&2; exit 1; }
      cat $t > $f && echo rotated'
ssh <commerce> '/opt/tenshu/backup-commerce-db.sh; rc=$?; tail -6 /var/log/tenshu-commerce-backup.log; exit $rc'
```

✅ `saved`, `3`, `rotated`, then rc 0 with `DR: OK`. ⛔ Otherwise restore `/root/deploy.env.pre-hardening`.

**9. Verify the first unattended runs** — the next morning, and the next Sunday afternoon:

```bash
ssh <relay> 'systemctl --failed "tenshu-*"; grep -E "DR: OK|FAILED" /var/log/tenshu-relay-backup.log | tail -2'
ssh <commerce> 'systemctl --failed "tenshu-*"; grep -E "DR: OK|FAILED" /var/log/tenshu-commerce-backup.log | tail -2'
cd deploy/scaleway-backup-access && python3 check-backups.py    # ✅ six "ok" lines (after the first Sunday)
```

**10. Rotate the relay's content plane onto its own key** (decision **D4**; production credentials).

✅ **Checks before:** `probe-keys.py relay_content` → `PASS` (it can list and delete in the content bucket,
and nothing in any backup bucket); `/root/deploy.env.pre-hardening` still exists on `<relay>`; a quiet
moment (the rotation recreates the relay container, a few seconds of 502s).

```bash
( cd deploy/scaleway-backup-access && terraform output -raw relay_content_env ) \
  | ssh <relay> 'set -e; f=/opt/tenshu/deploy.env; t=$(mktemp); trap "rm -f $t" EXIT
      grep -v -E "^S3_(ACCESS|SECRET)_KEY=" $f > $t; cat >> $t
      [ "$(grep -c -E "^S3_(ACCESS|SECRET)_KEY=." $t)" = 2 ] && grep -q "^S3_BUCKET=." $t || { echo "STOP: not rotated" >&2; exit 1; }
      cat $t > $f && echo rotated
      cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml --env-file $f up -d --force-recreate relay'
curl -fsS https://relay.tenshu.app/health
```

✅ `/health` answers, the relay log shows no S3 `403`, and an upload from a client device lands (a new
`blob-…` key in the content bucket). ⛔ Any of those fails → restore `/root/deploy.env.pre-hardening`,
`up -d --force-recreate relay` again, and stop.

**11. Police the existing fr-par buckets.**

✅ **Checks before:** `$OLD_AK` is on **no** box:
`ssh <relay> "grep -c $OLD_AK /opt/tenshu/deploy.env"` and the same on `<commerce>` both print `0` (⚠ until
step 10 the relay box still carries it as `S3_ACCESS_KEY` — then this step would lock nothing out but
also protect nothing; do 10 first). Step 9's runs were green. Then set `enforce_on_existing_buckets = true`,
`terraform plan` → ✅ exactly **three** bucket policies added, all fr-par, none on the content bucket →
apply → `python3 probe-keys.py` → `PASS`, now with no `IAM only` line on a backup bucket. Run each
backup once by hand again (step 6/8's lines) — ✅ rc 0.

**12. Police the content bucket** (decision **D5**; optional, last, and production).

✅ **Checks before:** the relay runs on its own key —
`terraform output -json access_keys` → `relay_content` equals
`ssh <relay> 'grep ^S3_ACCESS_KEY= /opt/tenshu/deploy.env'`; it has done so for at least a week; the
plan adds **one** policy, on the content bucket, naming the admins, `relay-content` and `blob-mirror`.
Apply, then **at once**: `/health`, a client upload, `mirror-blobs.py --dry-run` on the box, and
`probe-keys.py` (✅ `PASS` with no `IAM only` lines left). ⛔ Rollback:
`terraform apply -var enforce_on_content_bucket=false` (you are in `admin_principals`, so you can still
remove it; the Organization owner always can).

**13. Revoke the old shared key.**

✅ **Checks before:** (a) `$OLD_AK` on no box (step 11's greps); (b) a **new admin key** exists for you
(console → IAM → API keys) and is in every `deploy/scaleway-*/terraform.tfvars` (seven modules:
`-apex`, `-commerce`, `-commerce-backups`, `-relay`, `-relay-backups`, `-relay-blobs`, `-backup-access`)
— `grep -l $OLD_AK deploy/scaleway-*/terraform.tfvars` prints nothing; (c) `terraform plan` in
`deploy/scaleway-backup-access` with the new key shows **no changes**; (d) nothing else uses it — CI
secrets, other machines, password managers' notes. Then delete `$OLD_AK` in the console. ✅ The next
night's `check-backups.py` is all `ok`.

Afterwards: delete `/root/deploy.env.pre-hardening` on both boxes (it holds the old key) — ✅ only once
step 13's check is green.

## Open decisions

Each with a recommendation; none blocks preparing the code, **D1, D2 and D12 block step 1**.

| | decision | recommendation |
|---|---|---|
| **D1** | Object Lock mode on the nl-ams snapshot buckets: GOVERNANCE (an admin with the bypass right can still remove a version) or COMPLIANCE (nobody can, the Organization owner included, until it lapses). The mode can be changed later; enabling the lock cannot. | **GOVERNANCE** now. The threat is a compromised *box*, whose keys never hold the bypass right. Revisit COMPLIANCE once a month of runs shows the lifecycle behaves. |
| **D2** | Object Lock at all (`dr_snapshot_lock_days`, 30 vs 0). Creation-time only. | **30**. Without it the box key can overwrite the only off-region copy. Cost: noncurrent versions held ≤ 1 day longer. |
| **D3** | Pruning the nl-ams blob mirror. The box's key cannot delete there, so `MIRROR_DR_PRUNE` must stay off on the box. Options: never prune (it keeps deleted users' ciphertext indefinitely — the privacy bound in `acceptable-use-and-limits.md` §4.4 then does not hold for that copy); prune from a workstation with the admin key (`MIRROR_DR_PRUNE=on MIRROR_LOCK_FILE=… python3 mirror-blobs.py --target dr`, tfvars mode; ⚠ never while the box's Sunday run is active — the lock is per host — and the box's weekly runs keep supplying the sightings the prune needs); or give the box delete there. | Moot while `MIRROR_PRUNE` is off everywhere. When it is turned on for fr-par, **prune nl-ams monthly from a workstation**; do not give the box delete. |
| **D4** | Rotate the relay's content plane onto `relay-content` (step 10). | **Yes.** Without it every relay-box restriction is nominal. |
| **D5** | Police the content bucket (step 12). | **Yes, a week after D4.** It is the only way to stop the commerce and mirror keys reaching content blobs, since IAM cannot scope to a bucket. |
| **D6** | If Scaleway rejects or ignores `NoncurrentVersionExpiration`: overwritten/expired snapshot versions are never removed. | Accept it for a while (≤ ~3 GB/month growth, ≈ €0.02–0.04/month/month) and file it; or recreate the buckets with `dr_snapshot_lock_days = 0` (new names). Do **not** add a box-side pruner. |
| **D7** | If Scaleway ignores `StorageClass` on `CopyObject`: the mirror stays Multi-AZ. | Accept (it is ~€0.04/month); fall back to a lifecycle *transition* rule via `set-lifecycle.py`-style script only if it matters. |
| **D8** | Key expiry (`key_expires_at`). | **One year**, with a calendar reminder; an expired key fails loudly (exit 1/2) rather than silently. |
| **D9** | Pin each key to its box's IP with an IAM `condition` (`request.ip`). | Worth investigating; the condition syntax is **unverified** here, and a wrong one fails every job. Not prepared. |
| **D10** | A separate Project for commerce (real IAM isolation instead of D5's bucket policy). | Only if D5 is declined — it means new bucket names and moving the dumps. |
| **D11** | fr-par snapshot buckets stay Standard (Multi-AZ) and unversioned (they cannot gain Object Lock). | **Keep.** They are the first restore path, and the nl-ams copy now covers the overwrite case. |
| **D12** | The privacy notice names Paris as the only region (processor entry, transfers paragraph, relay paragraph; DE/EN/FR). The nl-ams copies make that untrue. | **Amend it before step 1**: "in the EU, at Scaleway in Paris (France) and, for backup copies, Amsterdam (Netherlands)". Both are EU regions of the same processor, so no new processor or transfer basis is involved, only the location. |
| **D13** | A test-data reset (`deploy/reset-test-data.sh`, `reset-and-commerce-test.md`) empties the **fr-par** buckets only. It quiesces and re-arms `tenshu-blob-mirror-dr` like the other two units, but it does not touch nl-ams: the snapshot buckets are Object-Locked (no box key can empty them; under D1's GOVERNANCE only an admin with the bypass right can), and the nl-ams mirror keeps every pre-reset blob, with its next run tripping the source-drop breaker (a hold, exit 2, nothing deleted there). | Accept for test-data resets: the locked snapshots age out in ~31 days. ⚠⚠ Until then they are pre-reset data: never restore an nl-ams snapshot or blob older than `/opt/tenshu/reset-epoch` (see *Restoring from the second region*). Apply step 1 **after** a pending production reset, not before, so the region starts on post-reset data. Empty the nl-ams mirror and its `_tenshu-mirror/` ledger with the admin key after a reset, then let the next run start a fresh ledger. Teach the reset script to do that only if resets stay routine after release. |

## Costs

Monthly, storage only, using the prices recorded above (⚠ unverified; the alternative figures from
2026-10-23's search are in the last column). Sizes: relay snapshots ≲ 3 GB for 30 nights (the payload
is ciphertext, so gzip saves little), commerce dumps a few MB, blobs 5.35 GB.

| bucket | before | after | after, alt. prices |
|---|---|---|---|
| relay snapshots, fr-par (Multi-AZ, 3 GB) | €0.048 | €0.048 | €0.044 |
| relay snapshots, nl-ams (One Zone, 3 GB + ≤ 1 day of noncurrent) | — | €0.025 | €0.037 |
| blob mirror, fr-par (Multi-AZ → One Zone, 5.35 GB) | €0.086 | €0.043 | €0.064 |
| blob mirror, nl-ams (One Zone, 5.35 GB) | — | €0.043 | €0.064 |
| commerce dumps, both regions | < €0.001 | < €0.001 | < €0.001 |
| **total** | **≈ €0.13** | **≈ €0.16** | **≈ €0.21** |

Transfer: the first nl-ams fill moves 5.35 GB through the relay box, then ~3 GB/month of snapshots plus
weekly blob deltas — inside the 75 GB/month free allowance, so **€0**. Requests are free. The real cost
is one more weekly job to watch, and the runbook above.
