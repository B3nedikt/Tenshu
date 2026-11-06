# Moving the relay to another provider (DigitalOcean → Scaleway)

Moves the live relay — its **SQLite log** and its **content blobs** — onto a box at another provider,
and cuts DNS over. Written against the DO → Scaleway move; nothing in it is provider-specific except
the two "what bit us on Scaleway" notes.

⚠⚠ **`deploy/scaleway-relay/README.md`'s three steps do not cover this.** They are *apply → verify →
cut DNS over → destroy the old one*, and its verify list is cloud-init, mount, `/health`. That is
correct for **apex**, which is stateless. The relay is the durable home of the sync log and every
content blob, so following those steps alone points every client at an **empty realm** and then
destroys the only copy. This file is the missing middle.

---

## The shape, and why it is not negotiable

Two payloads, and only one of them can move while the relay runs.

| | size on prod (2026-09-13) | live copy? |
|---|---|---|
| `relay-blobs` | **5.1 G**, 15,090 files | ✅ **yes** — immutable and content-addressed, so a partly-filled destination is unobservable and a second pass converges |
| `relay.db` (+ WAL) | **107 MB** (+ 4.2 MB) | ❌ **no** — see below |

**Why the SQL side cannot move live.** `RelayDatabase` opens **one** `Connection` under a
`ReentrantLock` with `PRAGMA journal_mode=WAL`. A `cp` of a live WAL database can capture the main
file and the WAL from different instants — a torn snapshot. SQLite's only safe online routes are the
backup API or `VACUUM INTO`, neither of which is wired here. And the blob runbook's
*"copy live, then delta"* shape **does not transfer**: blobs are immutable so a delta converges,
while SQL rows are mutable and have no delta semantics, so a second pass cannot repair an
inconsistent first one.

⇒ The expensive payload (5.1 G) moves live. The stop only has to cover **~107 MB**, so the window is
a couple of minutes, not the length of a 5 G copy.

## ⚠⚠ The failure that looks exactly like success

Verified on the Scaleway box 2026-09-13, against a database whose 23 tables lived entirely in a
444 KB WAL:

| what you copy | what you get |
|---|---|
| `relay.db` **alone** | ⚠⚠ **0 tables** — a valid, openable, **empty** database. **No error anywhere.** |
| `relay.db` + `-wal` + `-shm` | 23 tables (the main file grows on first open as the WAL replays) |
| `PRAGMA wal_checkpoint(TRUNCATE)`, then `relay.db` alone | 23 tables, WAL drained to 0, one self-contained file |

⚠⚠ **A clean `docker compose stop` does NOT checkpoint.** Before and after the stop the files were
byte-identical — `relay.db` 4,096 · `-wal` 444,992 · `-shm` 32,768. Stopping the relay makes the
files *quiescent*, not *consolidated*.

⇒ **Step 2 below checkpoints first and copies one file.** Copying all three also works, but the
checkpoint collapses *"you must remember all three suffixes"* into *"there is only one file"* — and a
runbook whose failure mode is forgetting a suffix will eventually be run by someone who forgets it,
for a punishment of an empty realm with nothing in any log.

---

## Before the window

- [ ] **Destination runs the same image as production.** Compare, do not assume:
      ```bash
      ssh root@<dest> 'docker inspect sync-relay-server-relay-1 --format "{{.Config.Image}}"'
      ssh root@<prod> 'docker inspect sync-relay-server-relay-1 --format "{{.Config.Image}}"'
      ```
      ⚠ A destination that is behind reintroduces every bug fixed since its tag. The Scaleway box sat
      **78 commits behind** for five days while answering `{"status":"ok","durable":true}` — carrying,
      among others, the relay that kept its content stores in RAM.
- [ ] ⚠⚠ **Prune the destination's ROOT volume.** It is where image layers extract, and it is *not*
      the data volume:
      ```bash
      ssh root@<dest> 'df -h /; docker image prune -af; docker builder prune -af; apt-get clean'
      ```
      Scaleway's default root volume is **8.9 G** against DigitalOcean's 24 G. Ours hit **100 %** and
      the pull died with `no space left on device` **while the 46 G data volume sat at 1 %** — the
      coupled-disks trap in a new costume. After pruning it was still only 94 % / 578 M free.
      ⭐ `deploy/scaleway-relay` now defaults `root_volume_size_gb` to **20**, so an instance applied
      after 2026-09-13 starts with room; this step is for one raised before that. ⚠ Raising the
      volume does **not** grow the filesystem — reboot, or
      `growpart /dev/sda 1 && resize2fs /dev/sda1`.
- [ ] ⚠ **Install `sqlite3` on the destination** — `apt-get install -y sqlite3`. It ships on the DO
      box and is **absent** on Scaleway, and every verification below needs it. A missing binary
      reads as an empty schema if you discard stderr, which cost an hour on 2026-09-13.
- [ ] Record the source figures to compare against afterwards:
      ```bash
      ssh root@<prod> 'stat -c "%s relay.db" /mnt/relay_data/relay.db; du -sh /mnt/relay_data/relay-blobs; ls /mnt/relay_data/relay-blobs | wc -l'
      ssh root@<prod> 'sqlite3 -readonly /mnt/relay_data/relay.db "SELECT COUNT(*) FROM sync_block; SELECT COUNT(*) FROM content_blob; SELECT COUNT(*) FROM device;"'
      ```
- [ ] Pick a window. ⚠ Same caveat as the blob runbook: **there is no activity data** — the relay has
      no metrics backend. `SELECT strftime('%H', last_referenced_at/1000,'unixepoch'), COUNT(*) FROM
      content_blob GROUP BY 1` is a hint about blob access, not request load, and one Frankfurt region
      serves whoever signed up.
- [ ] Decide on a maintenance page. First-party apps are local-first and mostly will not notice —
      reads and writes hit the local replica and sync retries. The exception is **anonymous Mode-A
      share links** (`GET /share/{id}`, `/share/{id}/chunk/{i}`): no local replica, no fallback, so a
      recipient sees a broken page. A static 503 in Caddy for those two routes is the cheap fix.
- [ ] Confirm DNS TTL on the relay record is short enough that a cutover propagates inside the
      window you planned, and lower it a TTL-length **beforehand** if not.

## 1 — Bulk-copy the blobs, relay still running

```bash
ssh root@<prod> 'rsync -a --info=progress2 /mnt/relay_data/relay-blobs/ root@<dest>:/mnt/relay_data/relay-blobs/'
```

Safe with the relay live: a blob is named `blob-<sha256(ciphertext)>`, immutable and written once, so
a partly-copied destination is not an inconsistent one — nothing reads it yet, and pass 2 fills the
gap. ⚠ Do **not** copy `relay.db` here; it would be stale by the time you flip, and step 2 replaces
it wholesale.

## 2 — Stop, checkpoint, copy the database, delta the blobs

```bash
# a. stop the SOURCE relay — Caddy may stay up so clients get a connection error, not a wrong answer
ssh root@<prod> 'cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml \
  --env-file /opt/tenshu/deploy.env stop relay'

# b. ⚠⚠ CHECKPOINT. Without this you copy an empty database and nothing tells you.
ssh root@<prod> 'sqlite3 /mnt/relay_data/relay.db "PRAGMA wal_checkpoint(TRUNCATE);" \
  && ls -la /mnt/relay_data/relay.db*'
#    Expect: -wal is now 0 bytes and relay.db has grown by roughly the old WAL size.

# c. copy the single consolidated file
ssh root@<prod> 'rsync -a /mnt/relay_data/relay.db root@<dest>:/mnt/relay_data/relay.db'

# d. delta the blobs (fast — only what arrived during step 1)
ssh root@<prod> 'rsync -a --info=progress2 /mnt/relay_data/relay-blobs/ root@<dest>:/mnt/relay_data/relay-blobs/'
```

⚠ If `sqlite3` is unavailable on the **source**, fall back to copying `relay.db`, `relay.db-wal` and
`relay.db-shm` together — all three, or the copy is empty.

## 3 — Verify the destination BEFORE any DNS change

⚠⚠ This is the step the existing README lacks, and `/health` is not one of the checks that matters:
it reports that *a database is configured and its volume is writable*, which an empty relay passes.

```bash
# counts must MATCH the figures recorded before the window
ssh root@<dest> 'sqlite3 /mnt/relay_data/relay.db "SELECT COUNT(*) FROM sync_block; SELECT COUNT(*) FROM content_blob; SELECT COUNT(*) FROM device;"'
ssh root@<dest> 'ls /mnt/relay_data/relay-blobs | wc -l; du -sh /mnt/relay_data/relay-blobs'
```

- [ ] `sync_block`, `content_blob` and `device` counts equal the source's.
- [ ] Blob **file count** and total size equal the source's. ⚠ Count as well as size: 278 of ours are
      **zero-length**, so size alone can agree while files are missing.
- [ ] Start the destination relay and confirm `restarts=0`. ⚠ That is a real check, not a formality —
      the relay **refuses to boot** when a database is configured alongside an in-memory
      content store, so a clean start proves the durable stores are wired.
- [ ] The protocol gate matches production, all three cases:
      ```bash
      for v in '' 2 3; do ... POST /sync/realm ... ; done   # expect 426, 426, 400
      ```
- [ ] ⚠ Compare against **production**, not against expectations — run the same probes at the old
      host and diff. Two relays on the same image should answer identically.

## 4 — Flip DNS, and keep the old box

- [ ] Point the relay record at the destination IP.
- [ ] Watch the destination's log for real client traffic: `POST /sync/log`, `POST /sync/push`.
- [ ] Confirm a **write** round-trips — upload a file from a client and watch the chunk `PUT`s land,
      then `POST /sync/content/declare` behind them, then rows appearing in `content_edge`.
      ⚠ Reads succeeding proves less than you would like: a relay with a copied database serves reads
      from data it may not be able to extend.
- [ ] ⚠⚠ **Do not destroy the source.** Keep it stopped but intact until the destination has served
      normal traffic for at least a few days. It is the only copy of anything the window missed, and
      `terraform destroy` takes the block volume with it.

## Rolling back

Before DNS propagates: point the record back and `start relay` on the source. The source database is
untouched by this procedure — step 2 only *reads* it (the checkpoint rewrites its own WAL into its
own main file, which is a normal SQLite operation and not a content change).

After clients have written to the destination, rolling back **loses those writes**. There is no merge
path between two relay databases; sync blocks are ordered by a relay-assigned sequence and two
divergent logs cannot be reconciled. ⇒ The decision point is the DNS flip, and it is one-way in
practice.

---

## What this does not cover

- **`deploy.env`** — hand-maintained, not Terraform-managed, and holds the entitlement issuer key.
  Copy it deliberately; a destination missing `ENTITLEMENT_ISSUER_PUBKEY` silently stops enforcing
  quota rather than failing.
- **Moving blobs to object storage**, which is a different move with its own runbook:
  [`migrate-relay-blobs-to-s3.md`](migrate-relay-blobs-to-s3.md). ⭐ Doing **that one first** makes
  this one much smaller — the 5.1 G stops being something a provider move has to carry at all.
- **Commerce and apex**, which have their own modules and no durable relay state.
