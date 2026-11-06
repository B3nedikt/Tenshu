# Reclaim undescribed ciphertext on the relay (operator runbook)

**What this does:** permanently deletes `content_blob` entries, and their bytes, that **no content
describes**. That is the duplicate-emit garbage: losing chunk sets from repeated emits under fresh
keys, which count against the owner's quota and which nothing else will ever free.
CR-2 phase B0, `docs/proposals/content-plane-cr2-quorumless.md` §10.7. Code: `ContentReclaimJob`,
`OperatorRouting.kt`.

> **Not the automatic sweep.** Described content that lost its last edge is freed by the relay's own G2
> sweep (CR-2 B5, `content-plane-cr2-b5-spec.md`), switched by `CONTENT_SWEEP_MODE = off | dry_run |
> armed`. ⭐ **The deploy runs it `armed`** (`sync-relay-server/docker-compose.yml`'s default since CR-2
> B6, D1 in `content-plane-cr2-b6-spec.md`): B6 removed every client-side free, including
> `/sync/content/{delete,reclaim}`, so this sweep is the **only** thing that frees described content. The
> dry-run comparison B5 asked for first was given up on purpose (B6 D1). `off` / `dry_run` remain as
> `deploy.env` overrides, and a relay on either frees nothing and keeps charging deleted content (a
> leak, never a loss); `RelayConfig`'s own default stays `off`. `dry_run` still logs per owner what
> arming would free (`content-sweep dry_run` lines; the owner is an 8-character prefix since 2026-10-23,
> so resolve it with `WHERE owner_id LIKE '<prefix>%'`). `GET /ops/content-sweep/status` with this runbook's
> token and loopback shows the mode, T and totals. ⚠ `armed` also stops charging held bytes. The sweep
> drains only what it freed; a queue left by this runbook's `enqueue` still needs its own `drain`.

⚠⚠ **The operation is made REVERSIBLE by an archive, not proven safe in advance** (maintainer,
2026-10-01). Before anything is drained, the blob store and the database are snapshotted, and kept
**until the live set has been positively reconciled** (see *When the archive may go*). A live chunk
deleted by mistake shows up as a file that fails to open, and is restored from the snapshot (see
*Restore* below). This replaced a stack of up-front coverage proofs that each
review round found another gap in. Those checks are now **verification** steps: they measure whether
the lifecycle machinery works.

## Gates: all required before draining

1. **The emit dedupe fix (one content, one upload: published content is never re-emitted under fresh
   keys) is on every emitting device, and one device has completed the v6 declaration backfill after
   that.** In ~13% of duplicated contents the *live* chunk set was the
   undescribed one, and only a client's walk re-describes it.
   - Evidence is that device's log line: `ContentDeclarationBackfill -> declared … — recorded complete`,
     `… skipped: already backfilled at this format` (it latched v6 earlier), or `… nothing to declare
     (walk complete, device settled — recorded done)`. `NOT recorded complete`, `WALK INCOMPLETE`, or no
     line at all, means not yet.
   - ⚠ Before the `SyncSchedulerHolder` wiring fix (2026-10-01) the scheduled pass was a **silent no-op
     on desktop and iOS**. Older builds there never ran it.
2. **The plan's `edgeOnlyWithManifestCount` is 0** (the enqueue refuses otherwise). It catches declared
   content that nothing ever described. It does **not** catch a stale description (the 13% case); that is
   what the walk in gate 1, and the archive, are for.
3. **Rule out live undescribed content the relay cannot recognise** (`ContentInventory`'s table):
   - ⚠⚠ **Legacy entity-keyed emits.** A row with on-demand content but no content id: a Fit workout
     whose streams were saved before `Workout.ContentId` existed, or any writer whose `resolveContentId`
     returns null. Their chunks are undescribed **and live**; deleting them loses the data. Check on a
     device (for Fit: workouts with streams and an empty `ContentId`) that there are **none**, or
     re-emit them through the content plane first.
   - **Relation-slice snapshots** are also undescribed and will be deleted. That costs a joining device
     a log replay and the next maintenance pass a re-seal: churn, not loss.
4. **Snapshot the database AND the blob store**, and keep both until *When the archive may go* is met:
   - **Database:** [`back-up-the-relay-database.md`](back-up-the-relay-database.md).
   - **Blob store:** copy all of it. On the maintainer's realm that is about 5 GiB, cheap next to
     proving which chunks are safe. Check which backend runs:
     `docker exec sync-relay-server-relay-1 env | grep S3_`.
     - **S3** (production: Scaleway, `S3_ENDPOINT` + `S3_ACCESS_KEY` / `S3_SECRET_KEY`). ⚠ The box has
       no `aws` CLI, and a bare `aws s3 …` would talk to AWS under AWS's variable names. Run the CLI as
       a container, with the relay's own credentials mapped and the endpoint given (as run 2026-10-01):
       ```sh
       E=/root/b0-evidence; mkdir -p $E; umask 077
       docker exec sync-relay-server-relay-1 env | sed -n \
         -e 's/^S3_ACCESS_KEY=/AWS_ACCESS_KEY_ID=/p' -e 's/^S3_SECRET_KEY=/AWS_SECRET_ACCESS_KEY=/p' \
         -e 's/^S3_REGION=/AWS_DEFAULT_REGION=/p' > $E/aws.env          # ⚠ secrets: delete with the archive
       A="docker run --rm --env-file $E/aws.env amazon/aws-cli:2.17.0 --endpoint-url <S3_ENDPOINT>"
       # ⚠ The prefix exactly as S3BlobStore normalises it: empty stays empty, otherwise ONE trailing slash.
       P=$(docker exec sync-relay-server-relay-1 sh -c 'printf %s "$S3_KEY_PREFIX"'); [ -n "$P" ] && P="${P%/}/"
       SRC=s3://<S3_BUCKET>/${P}<owner-id>/
       DST=s3://<S3_BUCKET>/archive/reclaim-$(date +%F)/<owner-id>/
       $A s3 sync --only-show-errors $SRC $DST
       # Both must report the same object count and the same total size:
       $A s3 ls --recursive --summarize $SRC | tail -2; $A s3 ls --recursive --summarize $DST | tail -2
       $A s3 ls $DST | awk '{print $4}' | sort > $E/archive-keys.txt     # step 3b of Run reads this
       ```
       About 6 objects a second there: 16,000 objects took 10 minutes. Bucket versioning on the source
       is a second net.
     - **File volume:** copy the blob directory, for example
       `tar -C <blob dir> -czf /root/b0-evidence/blobs-<owner-id>-$(date -u +%Y%m%dT%H%M%SZ).tgz .`.
       ⚠ Named per owner and per run, never by date alone: a second reclaim that day would overwrite
       it with a snapshot taken **after** the first drain, which no longer holds what that drain deleted. Then write the same
       inventory step 3b reads, from the archive itself and not from the live directory:
       ```sh
       # ⚠⚠ A truncated archive still lists most of its names before `tar` fails, and a pipeline's status
       #    is its LAST command's. So: fail on any stage, and read the whole archive first.
       set -o pipefail
       gzip -t <that .tgz> && tar -xzOf <that .tgz> > /dev/null || { echo "STOP: the archive is not fully readable"; exit 1; }
       # ⚠ FileBlobStore names each file base64url("<owner-id>/<ref>"), unpadded: decode, keep this owner.
       tar -tzf <that .tgz> | python3 -c '
       import sys, base64
       owner = sys.argv[1] + "/"
       for line in sys.stdin:
           name = line.strip().rsplit("/", 1)[-1]
           try: key = base64.urlsafe_b64decode(name + "=" * (-len(name) % 4)).decode()
           except Exception: continue
           if key.startswith(owner): print(key[len(owner):])
       ' <owner-id> | sort > /root/b0-evidence/archive-keys.txt || { echo "STOP: the inventory failed"; exit 1; }
       ```
       The `tar -C … -czf` above must itself have exited 0.
   - ⚠ Archived bytes are **outside** the relay's `content_blob` index, so they do not count against
     the owner's quota. The quota comes back at drain time, as intended.
5. **Know the owner id** (realm `owner_id`) whose garbage you are reclaiming. Every call is scoped to it.
6. **The maintainer approves the plan**: its candidate count, its bytes and its digest.

## Verification: what tells us the machinery works (not gates)

Run these on a realm you can learn from. On test data their findings matter more than their verdict.

- **Second device, fresh walk.** On a second device with its own sync history, the pass must log
  `declared … — recorded complete`. `already backfilled` walks nothing, so it compares nothing. Take a
  snapshot of the described content ids (`content_ref`) **before and after** that walk. Content newly
  described in between is rows the first walk missed: a coverage gap worth understanding.
- **Explain every `without refs`** of a fresh walk, **by content id**, not by count. The legitimate ones
  are contents that never existed (a thumbnail class never emitted: an edge, no stored manifest). Any
  other means a manifest failed to resolve, and that row was not re-described.
- **Before and after the drain:** open a sample of files on each device, especially large ones and
  recently moved ones. Check the relay's `/sync/usage`, and watch for content-missing errors in the
  device logs over the following days.
- **Totals measure settling, not completion.** A realignment mostly swaps which chunk set of a content
  is described, so the undescribed total barely moves (observed 2026-10-01: the OnePlus had latched v6
  while the total stood at 4.257 GiB, against 4.25 on 2026-09-26). Two plans a day apart that agree mean
  the realm has settled.

- **How to get a device's backfill line**, per platform. The pass is idle + charging + unmetered on Android, about once
  per long-lived session on desktop, and only while Control is open on iOS:
  - **Android** (debug build, USB):
    `adb shell am broadcast -a app.tenshu.control.debug.RUN_CONTENT_MAINTENANCE -n app.tenshu.control.android/app.tenshu.control.android.debug.MaintenancePassReceiver`,
    then `adb logcat | grep ContentDeclarationBackfill`. It starts at once.
    ⚠⚠ **Only with Control ON SCREEN and the phone UNLOCKED, for the whole pass.** Android blocks a
    backgrounded Control's network, and the pass then fails every fetch, or loses its declares halfway
    (`tech-debt.md`, *Android blocks Control's network in the background*). Before triggering: unlock,
    open Control, and set the display timeout above the pass's length (about 30 minutes for 9,000
    manifests on LTE, 10 on Wi-Fi). Check it took: `adb shell dumpsys netpolicy | grep UID=<control's uid>`
    must show `procState=TOP` and `effective=NONE`.
    ⚠ Read the progress lines, not only the last one: `walked: … from N rows`, then
    `resolved K of C manifests (… GOT NO ANSWER)` every 500, then `declared batch i of n`. A count of
    `GOT NO ANSWER` that climbs with every line is a blocked network, not a slow relay.
    ⚠ The scheduled pass can start on its own alongside yours; two `walked:` lines mean two passes.
  - **Desktop:** quit Control, then start
    `/Applications/TenshuControl.app/Contents/MacOS/TenshuControl > control.log 2>&1 &`. A normal
    launch keeps no output. The pass runs about 15 minutes after start.
  - **iOS:** `xcrun devicectl device process launch --device <udid> --console --terminate-existing
    app.tenshu.control.ios > control.log`. Keep the phone unlocked and Control in front **until the
    line appears**: about 25–30 minutes. The pass starts 15 minutes after launch, then makes thousands
    of sequential manifest fetches, roughly 10–15 minutes more. Backgrounding Control earlier can cut
    it off.

## Run

The routes exist only while `RELAY_OPERATOR_TOKEN` is set, and answer only a **loopback** socket peer.
So call them from a container that shares the relay's network namespace; nothing on the public proxy
can reach them.

```sh
# 1. The token is set permanently since 2026-10-29 (it also enables the share-link takedown,
#    take-down-a-share-link.md §3). Check it is there exactly once; only if it is missing, add one and
#    recreate the relay. ⚠ Two assignments make the read-back below a two-line value, and every call
#    then fails authentication.
DC='docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env'
grep -c '^RELAY_OPERATOR_TOKEN=' /opt/tenshu/deploy.env   # must print 1
# If 0:
#   echo "RELAY_OPERATOR_TOKEN=$(openssl rand -hex 32)" >> /opt/tenshu/deploy.env
#   (cd /opt/tenshu/repo && $DC up -d relay)

# Read it back from the file, so a new shell (the run can span days) still has it.
T=$(sed -n 's/^RELAY_OPERATOR_TOKEN=//p' /opt/tenshu/deploy.env)
ops() {  # method path. ⚠ Returns curl's status: an HTTP error (409, 404, 5xx) or no connection is non-zero.
  docker run --rm --network container:sync-relay-server-relay-1 curlimages/curl:8.10.1 \
    -sS --fail-with-body -X "$1" -H "X-Operator-Token: $T" "http://127.0.0.1:8080$2"
}
OWNER=<owner-id>
# ⚠⚠ The relay's REAL database, by absolute path. A bare `sqlite3 relay.db` in some other directory
#    silently creates an empty database there and answers every query with nothing.
#    On the production box this path was the one used for the 2026-10-01 run. The `relay_data` volume is
#    bind-backed by `/mnt/relay_data`, so `/mnt/relay_data/relay.db` (the backup guide's spelling) is the
#    same file. Whichever you use, prove it is the live database before trusting an answer from it:
DB=/var/lib/docker/volumes/sync-relay-server_relay_data/_data/relay.db
[ "$(sqlite3 -readonly "$DB" "SELECT count(*) FROM content_blob WHERE owner_id='$OWNER'")" -gt 0 ] \
  || { echo "STOP: $DB is not the relay's database (no blobs for this owner)"; exit 1; }
E=/root/b0-evidence; mkdir -p $E

# 2. Plan: writes nothing.
ops GET "/ops/content-reclaim/plan?owner=$OWNER&minIdleHours=24"
```

Read the plan before going on:

| field | what it means |
|---|---|
| `candidateBlobs` / `candidateBytes` | what would be deleted |
| `describedBlobs` / `describedBytes` | described content, never touched |
| `protectedManifests` | undescribed manifests of known contents, spared |
| `recentlyTouched` | undescribed but touched within `minIdleHours` (possibly still uploading), spared |
| `edgeOnlyWithManifestCount` | ⚠⚠ **must be 0.** Contents with an edge and a stored manifest but no description: their chunks cannot be told from garbage. The enqueue refuses while it is not 0; let the backfill run again |
| `queued` | left over from an earlier, unfinished run |

Sanity-check that `ownerBytes − candidateBytes` is roughly what the user actually stores.

```sh
# 3. Enqueue exactly the planned candidates. Refused (409) if the SET moved since the plan: quote the
#    plan's candidateDigest (an equal count can hide a different set).
#    ⚠⚠ The plan's "queued" must be 0 first. Rows left by an interrupted run were authorised by an
#    OLDER plan; a drain would delete them under this one's name. Drain or clear them deliberately.
ENQ=$(ops POST "/ops/content-reclaim/enqueue?owner=$OWNER&minIdleHours=24&expectDigest=<candidateDigest>") \
  || { echo "STOP: the enqueue was refused or did not reach the relay: $ENQ"; exit 1; }
echo "$ENQ"
N=$(echo "$ENQ" | sed -n 's/.*"enqueued":\([0-9][0-9]*\).*/\1/p')

# 3b. ⚠⚠ BEFORE draining: save the queue. The drain deletes each queue row with its bytes, so this is
#     the last moment the exact list exists, and Restore needs it. Read-only.
#     One file per run, named once: a date-only name is overwritten by a second run that day (another
#     owner, or this one again), and Restore then has no lengths for the first.
Q=$E/enqueued-$OWNER-$(date -u +%Y%m%dT%H%M%SZ).txt
sqlite3 -readonly "$DB" "SELECT ref || ' ' || byte_len FROM content_free_queue
                         WHERE owner_id='$OWNER' ORDER BY ref" > "$Q"
#     The saved list must be exactly what THIS enqueue authorised. A different count means a failed
#     enqueue over a leftover queue, or the wrong $DB (which reads 0).
[ -n "$N" ] && [ "$N" -gt 0 ] && [ "$(wc -l < "$Q" | tr -d ' ')" = "$N" ] \
  || { echo "STOP: the queue holds $(wc -l < "$Q") refs, the enqueue reported '${N:-nothing}'"; exit 1; }
#     And every one of them must be in the blob archive (gate 4), or it is not restorable. Gate 4
#     wrote the archive's keys, sorted, to $E/archive-keys.txt.
#     ⚠⚠ This check FAILS CLOSED, and must: a missing or empty inventory makes `comm … | wc -l` print 0,
#     which is exactly what success looks like. On failure it ENDS THE SHELL, so a pasted or scripted
#     block cannot run on into the drain. (The queue stays; nothing is deleted until a drain.)
[ -s "$Q" ] && [ -s $E/archive-keys.txt ] \
  && cut -d' ' -f1 "$Q" | comm -23 - $E/archive-keys.txt > $E/not-in-archive.txt \
  && [ ! -s $E/not-in-archive.txt ] \
  && echo "OK: all $(wc -l < "$Q") queued refs are in the archive" \
  || { echo "STOP: no inventory, or queued refs are NOT archived. See $E/not-in-archive.txt"; exit 1; }
#     Keep a second copy of the list WITH the archive: Restore rebuilds the index rows from it.
#     File volume: replace the next line with `cp "$Q" <where the .tgz is kept>/ || exit 1`.
$A s3 cp "$Q" s3://<S3_BUCKET>/archive/reclaim-<archive date>/ || exit 1

# 4. Drain, repeating until "remaining": 0. Each call does at most 50 batches of 200.
#    "pausedOnEdgeOnly" > 0 means a declaration was halfway through: nothing was deleted, call again later.
ops POST "/ops/content-reclaim/drain?owner=$OWNER"

# 5. Leave the token in place: it stays set permanently since 2026-10-29, because the share-link
#    takedown needs it (take-down-a-share-link.md §3). The reclaim routes stay behind the same three
#    brakes: loopback socket peer, constant-time token, Caddy's edge 404 on /ops/*.
```

## What protects live data

- A drain **re-checks** each queued ref: one that became described since the enqueue, or is the manifest
  of a content the relay now knows, is dropped from the queue without being deleted (`spared`).
- A **PUT** or a **GET** of a queued ref removes it from the queue first, under the same lock the drain
  deletes in, and before its own byte write or read. A repair, a re-upload or a live read is never
  followed by deletion.
- Bytes are deleted **while holding** the store lock, so no PUT can land between the index delete and the
  byte delete.
- The queue is **durable** and every step is idempotent: a crash or a failed storage call mid-drain is
  finished by the next `drain`.
- Everything is keyed by **owner**: an identical ref under another owner is never touched.

## Restore (while the archive exists)

The drain deletes exactly the refs that were enqueued, and step 3b of *Run* saved that list, with each
ref's `byte_len`, **before** the drain ran.

If a file will not open after the drain (a content-missing or manifest error in the device log):
1. **Stop the relay**, or unset the operator token, so no drain runs while you restore. ⚠ Unsetting it
   also disables the share-link takedown: put the token back afterwards (it stays set permanently).
2. **Copy the affected refs back** from the blob archive into the live store. When you cannot tell
   which refs a broken file needs, copy back every ref on the enqueued list. That undoes the whole
   reclaim, which is a valid outcome on test data.
3. **Reinsert their `content_blob` rows from the saved queue list**, in one transaction, so reads and
   the quota see them again. Start the relay. A row is `(owner_id, ref, last_referenced_at, byte_len)`,
   and the list holds each ref with its `byte_len`:
   ```sh
   NOW=$(($(date +%s) * 1000))
   { echo 'BEGIN;'
     while read -r ref len; do
       echo "INSERT OR IGNORE INTO content_blob(owner_id, ref, last_referenced_at, byte_len)
             VALUES('$OWNER', '$ref', $NOW, $len);"
     done < <the saved queue list>      # step 3b's $Q; or only the lines of the refs you copied back
     echo 'COMMIT;'; } | sqlite3 "$DB"
   ```
   ⚠⚠ **Not from the database snapshot.** The backup bucket expires every object after 30 days
   (`back-up-the-relay-database.md`), and the reconciliation below can take longer than that. The
   queue list is all a restore needs, so keep it **with the blob archive, for as long as the archive**:
   step 3b copies it into the archive as well as leaving it on the box.
4. **Record what broke and why** in `content-plane-cr2-quorumless.md` §10.10. A deleted live chunk is a
   coverage gap in the backfill or the plan, and finding those is the point of this run.

## When the archive may go

⚠⚠ **Not on a timer, and not because nobody complained.** A mistakenly deleted live chunk produces no
report until its file is opened, and a sample opens only some. A cold file first opened after the
archive is gone would fail for good, which makes "reversible" untrue exactly where it matters. So the
archive is deleted only once the live set has been **positively reconciled** against what the relay
still stores:

1. **A fresh walk has finished on a device with complete history** (`declared … — recorded complete`,
   not `already backfilled`). A walk reads every live content's manifest and describes the chunk set
   that manifest names, and a drain never deletes a described ref. So every chunk the walk reached is
   accounted for, whether the walk ran before the drain or after it.
   - ⚠⚠ **The walk must account for every row it skipped, so it must be a `v7-counted-walk` pass**.
     Before v7, `SchemaDrivenContentWalk` dropped a row that would not decode, a row with no
     `DataId`, and a property whose `resolveContentId` threw, counted none of them, and still reported
     the walk complete (`content-plane-cr2-quorumless.md` §10.10). Skipped content is not in
     `without refs` and is not described, so the query in 2 prints 0 over it. **A v6 line, which has
     no `from N rows`, never satisfies this condition.** On a v7 line, read:
     - `— recorded complete` at the end, and no `WALK INCOMPLETE`. The fallback tree walk (a store
       that cannot enumerate) also prints `from N rows`, counting files, and is never complete: it is
       told apart by `WALK INCOMPLETE`, not by the count;
     - `from N rows`: N must be what that device should hold;
     - no `ROWS SKIPPED UNDECLARED` and no `PROPERTIES SKIPPED UNDECLARED`, or each skipped row
       identified and its content shown to be stored.
   - ⚠ A walk **before** the drain counts only if it finished after the emit dedupe fix reached every device, and it
     leaves content written between the walk and the drain to the `minIdleHours` window.
   - ⚠ Every device that has latched the format answers `already backfilled` and walks nothing. A walk
     **after** the drain therefore needs a device that has not latched, or a format bump.
2. **Nothing described is missing.** After the drain, on the relay, this must print 0:
   ```sh
   sqlite3 -readonly "$DB" "SELECT count(DISTINCT r.ref) FROM content_ref r WHERE r.owner_id='$OWNER'
     AND NOT EXISTS (SELECT 1 FROM content_blob b WHERE b.owner_id=r.owner_id AND b.ref=r.ref)"
   ```
   A described ref with no stored blob is a live chunk that is gone: restore it, then find out why.
   The declare route records a description without checking the bytes exist, which is what makes this
   query see a missing chunk rather than hide it.
3. **Every `without refs` of that walk is explained by content id** (*Verification* above). An
   unexplained one is a content the walk could not read, so the reconciliation does not cover it.
4. **Gate 3 held.** Content with no content id is invisible to the walk and to the query, so for it the
   archive is the only net. Either there was none, or its loss was explicitly accepted for this realm.

Until all four hold, keep the archive, however long that takes. It costs the price of storing the
realm once more. When they hold, delete the blob archive and the saved credentials next to it.
