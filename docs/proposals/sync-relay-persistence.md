# Sync Relay Persistence — design sketch

**Status:** Draft / sketch (no code yet). Refines `sync-backend-relay-plan.md` §6 (Phase 4.1),
which named a concrete stack (Supabase Postgres + Supabase Storage + Fly.io). This doc keeps the
*shape* but makes it **vendor-neutral and locally runnable**, and answers: what must persist, is
SQL+S3 actually right, and can we run it in Docker.

---

## 0. Maintainer review — decisions (2026-06-06)

Reviewing the first draft surfaced a simpler direction. Captured here as the working decision; the
sections below still record the full option space.

- **v1 = SQLite only, behind the SPI. No Postgres, no S3, no Docker for Phase 1.** The relay needs
  **no Postgres-specific feature** — the workload is KV + append-log, and SQLite does all of it
  (`INSERT … ON CONFLICT DO NOTHING`, `INTEGER PRIMARY KEY AUTOINCREMENT` cursor, range scans,
  transactions). Postgres is **not functionally better here**; its only edge is *operational at
  scale* (see below). The relay is a single fat-jar process today, and SQLite serves exactly one
  process well (WAL + `busy_timeout`).
- **Postgres's only real advantage is multi-node + managed ops** (one DB shared by N relay nodes,
  managed backups/PITR/failover, higher write concurrency). None of that is needed for a single-node
  relay. **It becomes a backend swap (not a rewrite) the day we go multi-node** — that's what the SPI
  buys us.
- **Single-file durability is solved without Postgres** via **Litestream** (continuously replicates
  the SQLite WAL to S3 → disk loss ≠ data loss, point-in-time restore). This is the "SQLite in prod"
  answer.
- **Large blobs → local filesystem volume (`FileBlobStore`) in the single-node phase.** **MinIO/S3
  only matter to the blob plane** (content chunks, share chunks) — irrelevant to the device registry,
  so Phase 1 touches neither. MinIO is just a *local* stand-in for cloud object storage when we do
  reach the blob phase and want to exercise the S3 path without a cloud account.
- **Postgres + MinIO + docker-compose stay documented as drop-in SPI backends**, gated on the
  multi-node trigger — not built for v1.

Net: SPI from day one; SQLite + (later) filesystem blobs is the whole v1 stack. Everything below that
says "Postgres/S3/MinIO" is the *scale-up path*, not a v1 requirement.

**Phase 1 — SHIPPED.** Storage SPI (`DeviceRegistry`, `RealmRegistry`) + SQLite backends
(`storage/RelayDatabase` with a hand-rolled versioned migration runner, `SqliteDeviceRegistry`,
`SqliteRealmStore`). `KnownDevicesStore`/`RealmStore` are retained as the in-memory backends (the
default + test backend — zero call-site churn). Opt-in via `DATABASE_URL` (absent → in-memory,
unchanged). `RelayPersistenceTest` covers round-trip + revoke + atomic create-if-absent + **survives
reopen**; full relay suite green. Threat-model amended (A13/relay row). Deviation from §4 sketch: the
interfaces are named `DeviceRegistry`/`RealmRegistry` (the concrete class names stayed) to avoid
renaming ~20 test construction sites. Hand-rolled migration runner chosen over Flyway (no new dep).

### 0.1 The migration chain — re-cut twice (as-built 2026-10-28; the operational reset is still ahead)

⚠ The `V2…V25` numbers in §5 below are the **first** chain and no longer exist. `RelayDatabase.MIGRATIONS`
was squashed twice in code, both valid only because the relay goes onto an empty volume at the coming
complete reset (⚠ **not yet done** — record its date here when it is):

- **The release-baseline reset (2026-10-27):** 25 steps → one `version = 1`
  (`core-schema-evolution.md` Appendix A.2).
- **2026-10-28 (this re-cut):** the post-release `v2` (`erased_realm`) and `v3` (F3 sealed
  share meta — dropped `share_record`'s `file_name`/`mime_type`/`total_size`, revoked pre-F3 rows) and
  `v4` (the one-shot `VACUUM` purging those dropped columns from freed pages — moot on a fresh file;
  the runner's `vacuum` hook went with it) folded into `version = 1`, and the legacy-only `byte_len DEFAULT 0` (pre-v9 rows) went with them. Version 1
  now also writes a `schema_baseline` row (`cut = '2026-10-reset-b'` — `-b` since the length-prefixed signed-message change edited the v1 baseline in place; a database carrying the earlier `2026-10-reset` is refused like a pre-squash one).
- **2026-11-05 (the second re-cut, `core-schema-evolution.md` Appendix A.6):** v2/v3 (the offer
  flow's `pairing_offer_response.reg_*` columns), v4 (`share_takedown`) and v5 (`DROP TABLE
  device_cursor`) folded into `version = 1`; `device_cursor` is never created. Marker
  **`2026-11-reset`** — a `2026-10-reset-b` database (at version 5) is refused like any other.

⚠⚠ **A pre-re-cut database is refused, not migrated.** Its recorded version (1–4) is ≥ the new
baseline's, so the runner would otherwise skip every statement and serve a schema it never created.
`refusePreSquashLadder` fails startup on any versioned database without the marker, with *"wipe it"*
— which today means every deployed relay, so the image must ship together with the volume wipe
(`RelayPersistenceTest.aPreSquashDatabase_isRefused_notSkipped`). ⚠ `deploy/reset-test-data.sh`
reads the expected version as the max `version = N,` in `RelayDatabase.kt` / `CommerceDatabase.kt`
at the tag — 1 for both after this re-cut. The rule is back in force: append
`version = 2, 3, …` and never edit version 1.

Appended since the re-cut: `v2`/`v3` (`pairing_offer_response.reg_*`, the offer flow registering on
delivery) and `v4` (`share_takedown`, the append-only operator takedown audit,
`take-down-a-share-link.md`). ⚠ Not folded into `version = 1`, although all three precede the reset:
an in-place edit of v1 has to move `BASELINE_MARKER` again (as `-b` did), and that refuses every
database already cut at `-b` (the dev lab, local relays) and forces their wipe, while an appended step
costs nothing on the empty production volume either way.

⚠ `device.admitted_hlc_*` stays **nullable** — not for old clients: recovery re-admission, the shared-realm
membership grant *and* `SharedRealmDeviceAdmitter` on `/sync/admit-device` all register without a stamp.

---

## 1. Why

Every relay store is an in-memory `ConcurrentHashMap` (`KnownDevicesStore`, `RealmStore`,
`SyncMailbox`, `HlcTipStore`, `RecoveryBlobStore`, `ContentBlobStore`, `ShareStore`). A process
restart wipes all of it. The concrete user-visible failure this already caused: the relay restarted
under a long-lived autostarted Control daemon, dropped the device registry, and every
JWT-authenticated call 401'd — pairing silently broke (see `ReregisterDevice`, which is the
*recovery* affordance; **persistence is the actual fix**).

Goal: survive restart, redeploy, and (eventually) horizontal scale, **without changing the
zero-trust contract** (House Rule #1 — the relay only ever holds ciphertext + public material).

---

## 2. What actually has to persist (and what doesn't)

The relay's workload is **not relational** — there are no joins, no queries *inside* payloads, no
analytics. It is key-value + append-log + monotonic-cursor scans over opaque bytes. Classifying each
store by access pattern is what drives the storage choice:

| Store | Access pattern | Contents (all opaque/public) | Persist? | Home |
|---|---|---|---|---|
| `KnownDevicesStore` | point get/put/delete by `(ownerId, deviceId)` | A4 **public** key | **Yes** | SQL |
| `RealmStore` | get by `ownerId`; **atomic** create-if-absent | master **public** key | **Yes** | SQL |
| `SyncMailbox` | append w/ monotonic seq; range-scan `seq > cursor`; tombstone GC | client-**encrypted** blocks | **Yes** | SQL (+ S3 for big block bodies) |
| `HlcTipStore` | get/put by `(ownerId, deviceId)`, last-writer-wins | signed HLC tip (metadata) | **Yes** | SQL |
| `RecoveryBlobStore` | get/put by `ownerId`, versioned | client-**encrypted** recovery blob (small) | **Yes** | SQL (bytea) |
| `ContentBlobStore` | get/put by `(ownerId, ref)`; retention GC | **large** ciphertext chunk bodies (e.g. 12 MB) | **Yes** | **S3** (index in SQL) |
| `ShareStore` | records (small) + chunks (large) | ciphertext | **Yes** | SQL records + **S3** chunks |
| `RecoveryChallengeStore` | random 32-byte challenge, **5-min TTL** | ephemeral nonce | **No** | in-memory (re-issued on restart) |

Two things fall out immediately:
- **Not everything needs a database.** `RecoveryChallengeStore` is ephemeral with a TTL — persisting
  it buys nothing; a restart just makes a client re-request a challenge. Keep it in memory.
- **Nothing here is user plaintext.** Every byte is either a *public* key or client-side ciphertext.
  So persistence does **not** widen the trust surface — the DB/S3 hold exactly what RAM held
  (House Rule #1 intact). At-rest encryption of the DB/bucket is defence-in-depth / availability, not
  a confidentiality requirement (same posture the threat model takes on TLS).

---

## 3. Is SQL + S3 actually the right choice?

**Short answer: yes, with a nuance.** Split by payload size:

- **Index / KV / log → SQL (Postgres).** Even though the data isn't relational, Postgres is the
  pragmatic *generic* default because it gives exactly the primitives these stores need:
  - **ACID create-if-absent** for `RealmStore` (`INSERT … ON CONFLICT DO NOTHING`) — the realm
    bootstrap race is currently a `ConcurrentHashMap.putIfAbsent`; it needs a transactional equivalent.
  - **Monotonic cursor** for `SyncMailbox` (`BIGSERIAL`/sequence) — replaces the in-memory
    `AtomicLong arrivalCounter`; range scans become `WHERE owner_id = ? AND seq > ? ORDER BY seq`.
  - TTL/GC via a periodic sweeper (the relay already runs `gcSweep` on a timer).
  - Ubiquitous, trivially Dockerised, portable across every host (not just Supabase).
- **Large opaque blobs → S3-compatible object storage.** `ContentBlobStore` chunk bodies (and share
  chunks) are big, immutable, content-addressed ciphertext. Putting multi-MB `bytea` in Postgres
  bloats the DB, slows backup/vacuum, and streams poorly. Object storage is the standard split: **SQL
  row holds the index** (`owner_id, ref, object_key, size, created_at` for GC), **S3 holds the bytes**.

**The nuance / alternatives considered** (so we're not cargo-culting):
- **SQLite instead of Postgres.** The relay is a single Netty fat-jar today. SQLite (one file on a
  mounted volume) covers *all* the SQL needs above with zero extra containers and the simplest
  possible local story. Cost: single writer / single node — no horizontal scale, and Fly volumes are
  single-attach. **Recommendation:** make the backend an SPI (§4) so SQLite is the zero-dependency
  tier-0 / dev option and Postgres is the scale-up — same code path, swapped at startup.
- **Pure KV (Redis / FoundationDB) or embedded RocksDB.** Fits the access shape, but Redis's
  durability is opt-in + memory-bound, and FDB/Cassandra are ops-heavy — premature for a relay that
  still fits on one node. No win over Postgres today.
- **Blobs on a plain filesystem volume** (Fly volume) instead of S3. Fine for single-node; S3 wins
  the moment compute and storage need to decouple or scale, and it's the cloud-portable default.
  Keep both behind the `BlobStore` SPI.

So: **SQL for the index/log/KV, S3 for the big blobs** is correct — but expressed through an SPI so
"SQL" can be SQLite *or* Postgres and "S3" can be MinIO / S3 / R2 / B2 / Supabase / a local volume.

---

## 4. Design — a storage SPI (the load-bearing decision)

`Application.module(...)` **already** takes every store as an injectable constructor parameter with
an in-memory default. That is the entire seam — we don't restructure routing, we just make each store
an interface with pluggable backends.

```
// relay/storage/  (new package)
interface DeviceRegistry {            // was KnownDevicesStore
    fun register(ownerId, deviceId, publicKey)
    fun publicKeyOf(ownerId, deviceId): ByteArray?
    fun revoke(ownerId, deviceId)
}
interface RealmStore { fun createIfAbsent(record): Boolean; fun get(ownerId): RealmRecord? }
interface MailboxStore { fun append(...): Long /*seq*/; fun since(ownerId, cursor): List<…>; … }
interface HlcTipStore  { … }
interface RecoveryBlobStore { … }
interface BlobStore    { suspend fun put(key, bytes); suspend fun get(key): ByteArray?; fun delete(key) }  // S3/FS
// ContentBlobStore / ShareStore = SQL index rows + a BlobStore for the bytes
```

Backends:
- `InMemory*` — today's `ConcurrentHashMap` impls, *unchanged*. Stay the **default** for unit tests
  and `devMode` (fast, hermetic). This is the "no regression for existing tests" guarantee.
- `Sql*` — JDBC against Postgres **or** SQLite (same SQL, dialect-guarded where needed).
- `S3BlobStore` / `FileBlobStore` — the `BlobStore` impls.

Selection happens once in `main()` from config (§6). No routing/handler code changes — they already
talk to the interfaces.

**Concurrency / correctness notes the SQL backend must preserve:**
- `RealmStore.createIfAbsent` → `INSERT … ON CONFLICT (owner_id) DO NOTHING` returning rowcount.
- `MailboxStore` seq → DB sequence / `BIGSERIAL`, **globally** monotonic (matches today's global
  `AtomicLong`; per-owner cursors still work via `WHERE owner_id=?`).
- GC sweeps (`SyncMailbox.gcSweep`, `ContentBlobStore.gcSweep`) become `DELETE … WHERE` + a paired
  `BlobStore.delete` for the bytes; keep the existing nightly cadence.

---

## 5. Schema sketch (device registry first — the smallest bug-fixing slice)

```sql
-- migrations are versioned (Flyway/Liquibase or a tiny hand-rolled runner)
CREATE TABLE realm (
    owner_id          TEXT PRIMARY KEY,
    master_public_key BYTEA NOT NULL,
    created_at        BIGINT NOT NULL
);
CREATE TABLE device (
    owner_id   TEXT NOT NULL,
    device_id  TEXT NOT NULL,
    public_key BYTEA NOT NULL,
    PRIMARY KEY (owner_id, device_id)
);
-- later phases:
CREATE TABLE sync_block (
    seq        BIGSERIAL PRIMARY KEY,         -- global monotonic cursor
    owner_id   TEXT NOT NULL,
    block_id   TEXT NOT NULL,
    body       BYTEA,                          -- or object_key TEXT → BlobStore for big bodies
    hlc        TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX ON sync_block (owner_id, seq);
-- content_blob(owner_id, ref) → object_key + size + created_at ;  bytes live in S3
-- hlc_tip(owner_id, device_id) → signed_tip ;  recovery_blob(owner_id) → blob, version
```

Everything stored is public-key-or-ciphertext (§2) — no schema column ever holds plaintext.

---

## 6. Running it locally in Docker

Two tiers, both vendor-neutral:

**Tier 0 — zero containers (SQLite + local volume).** `DATABASE_URL=jdbc:sqlite:./relay.db`,
`BLOB_DIR=./blobs`. `./gradlew :sync-relay-server:run` and state survives restart. Fastest dev loop;
also the minimal single-node production deploy (Fly volume).

**Tier 1 — production-shaped stack (`docker-compose.yml`).**
```yaml
services:
  postgres: { image: postgres:16, environment: {POSTGRES_PASSWORD: relay}, ports: ["5432:5432"], volumes: [pgdata:/var/lib/postgresql/data] }
  minio:    { image: minio/minio, command: server /data, ports: ["9000:9000","9001:9001"], volumes: [miniodata:/data] }   # S3-compatible
  relay:    { build: ., environment: {DATABASE_URL: jdbc:postgresql://postgres:5432/relay, S3_ENDPOINT: http://minio:9000, S3_BUCKET: relay-blobs, …}, depends_on: [postgres, minio] }
```
`docker compose up` → a fully persistent relay against Postgres + MinIO, byte-identical in shape to a
cloud deploy (just swap endpoints/creds). This needs a `Dockerfile` for the fat jar (we don't have
one yet — the relay is hand-built today; §6 of the relay plan assumed one).

`RelayConfig` (today a 3-field data class) grows env-parsed `database`/`blobStore` sections; absent →
in-memory (current behaviour, no surprise for `devMode`).

---

## 7. Genericness / no vendor lock-in

- **DB:** plain **JDBC** + a portable schema, not the Supabase client SDK. Works on any
  Postgres (Supabase / Fly Postgres / RDS / self-host) and on SQLite. Migrations via Flyway or a
  ~50-line runner.
- **Blobs:** **AWS SDK v2 S3 client pointed at a configurable endpoint** — works against MinIO
  (local), S3, Cloudflare R2, Backblaze B2, Supabase Storage. Or `FileBlobStore` for a volume.
  **As built (2026-09-01):** the SDK's default Netty transport is excluded in favour of
  `url-connection-client`, because the calls are plain blocking request/response. Measured, since
  the alternatives are not what reputation suggests: s3 + netty = 43 jars / 12.4 MB, s3 +
  url-connection = 33 jars / **8.6 MB**, the MinIO client = 24 jars / **21.5 MB** (it drags Guava +
  OkHttp), and a hand-rolled SigV4 over Ktor's client = 21 jars / 6.7 MB — so bespoke signing would
  save ~1.9 MB and cost us owning SigV4, and presigned URLs (the folder-link D9 relief valve) come
  free with the SDK. ⚠⚠ Two client settings are load-bearing for **non-AWS** stores and neither is
  implied by the other: `forcePathStyle(true)` and `chunkedEncodingEnabled(false)` — with chunked
  encoding left on, `PutObject` frames the body as `aws-chunked` and a store that does not decode
  the framing stores it, so a 2 MiB blob round-trips as 2,098,678 bytes of corruption rather than
  as a failed write.
- **Config by env** (`DATABASE_URL`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`).
  The only real secret is the DB connection string + S3 creds (JWT verify keys are *public*) — any
  host's secret manager suffices; this matches `sync-backend-relay-plan.md` §6.4.

Net: the relay plan's Fly.io + Supabase remains *one valid deployment*, not a hard dependency.

---

## 8. Phasing (each slice independently shippable)

1. **Device registry** (`DeviceRegistry` + `RealmStore`) behind the SPI, with a **SQLite** backend +
   a tiny versioned migration runner. **No Docker, no Postgres, no S3.** Config: `DATABASE_URL`
   absent → in-memory (today's behaviour); `jdbc:sqlite:…` → persistent. **This alone fixes the
   recurring 401-on-restart bug.** Smallest meaningful slice.
2. Split by serialization dependency:
   - **2a — `RecoveryBlobStore` → SQLite (SHIPPED).** Plain bytes (opaque A9 ciphertext + monotonic
     version), no wire serialization — mirrors Phase 1. `RecoveryBlobRegistry` interface +
     `SqliteRecoveryBlobStore` + migration V2. Opt-in via the same `DATABASE_URL`. ⚠ Removed by
     F8 (2026-10-28, the 2026-10 relay baseline has no `recovery_blob` table; threat model T-RC-08).
   - **2b-i — `HlcTipStore` + shared codec (SHIPPED).** `RelayBinaryCodec` encapsulates the
     `BinarySerializer`/`BinaryParser`/registry/`TContext` setup (mirrors `SyncRouting`) so wire
     types persist as wire-format-identical bytes — the infra the mailbox slice reuses.
     `HlcTipRegistry` interface + `SqliteHlcTipStore` (stores the serialized `SignedHlcTip` keyed by
     `(ownerId, deviceId)`); migration V3.
   - **2b-ii — `SyncMailbox` → SQLite (SHIPPED).** `MailboxStore` interface; `SyncMailbox` retained
     as the in-memory backend; `SqliteSyncMailbox` persists the sync-block + tombstone logs (envelopes
     serialized via `RelayBinaryCodec`); migration V4. A **shared `relay_seq` counter** replaces the
     per-process `AtomicLong` and **continues across restart** (the blocks it numbered also persist —
     resetting would collide seqs / strand client cursors). The `EnvelopeOnlySyncBlock` body-drop
     became a `body_dropped` column (re-wrap the parsed block on read; extracted the wrapper to a
     shared internal type). **Ephemeral** pairing req/resp + the retention-override admin seam stay
     in-memory in both backends. **Resolved the storage-vs-logic split:** `acknowledgementSet` +
     `gcSweep` are now **shared default methods on `MailboxStore`** expressed over the storage
     primitives — one GC impl for both backends (the in-memory `SyncMailboxGcTest`, 14 tests, still
     passes unchanged against the inherited defaults).
3. **Large blobs** → `BlobStore` SPI, **`FileBlobStore` (local volume) first**:
   - **3a — `ContentBlobStore` → SQLite index + `BlobStore` bytes (SHIPPED).** `BlobStore` SPI +
     `FileBlobStore` (one base64url-named file per `<ownerId>/<ref>` key); `ContentBlobRegistry`
     interface; `ContentBlobStore` retained as the in-memory backend; `SqliteContentBlobStore` keeps
     the `(ownerId, ref) → last_referenced_at` index in SQLite (migration V5) and the multi-MB opaque
     ciphertext in the `BlobStore`. The retention/orphan GC selects-then-deletes index rows under the
     write lock, then deletes the corresponding files. Blob dir = `<db-dir>/relay-blobs` (or `BLOB_DIR`).
   - **3b — `ShareStore` → SQLite records + `BlobStore` chunks (SHIPPED).** `ShareRegistry`
     interface; `ShareStore` retained as the in-memory backend; `SqliteShareStore` keeps Mode-A
     descriptors in SQLite (migration V6; `chunkHashes` as a length-framed BLOB) and chunk bytes in
     the shared `BlobStore` (key `share/<shareId>/<idx>`). `open`'s cap/expiry/revoke check + the
     `opensSoFar` bump run under one write lock (no race past the cap); the revoked/expired/orphan GC
     deletes both rows and chunk files. `K_share` is never stored (A11). Added `BlobStore.exists` for
     first-write-wins `putChunk` + the orphan check.
   `S3BlobStore` is a config-selectable `BlobStore` for when storage decouples from compute — not
   required single-node.

**Track complete (Phases 1–3).** Every relay store is durable behind the storage SPI when
`DATABASE_URL` is set: device registry + realm, recovery blob, HLC tips, the sync-block + tombstone
logs, content-plane chunks, and Mode-A shares. In-memory remains the default (and every backend's test
backend). A `DATABASE_URL` relay loses nothing on restart — the original 401-on-restart bug is closed
at the root. Remaining items are the orthogonal scale-up track (Postgres + S3 backends, docker-compose,
Litestream) and on-device verification.

**As-built — a revocation forgets the device's tip and cursor** (`relay-metadata-minimisation.md`
F11, Phase 4 (b)). `hlc_tip` and `device_cursor` rows used to outlive a revoked device for ever and
keep being served to peers as `peerTips` / `peerCursors`. `auth/DeviceRevocation` now deletes both in
the same `RelayDatabase.inTransaction` as the registration delete and the removal-ledger write, on an
applied `Device` tombstone (`/sync/push-tombstone`) and on shared-realm member removal; a **stale**
tombstone (one `revokeIfNotStale` declines) prunes nothing. ⚠⚠ A revoked device's in-flight
`/changes` or `/log` can write *after* the prune; the routes re-check the registration **after** each
tip/cursor write and drop the rows + answer 401 if it is gone (`forgetIfUnregistered`). Readers traced
before deleting: the tombstone-GC quorum iterates the device registry and only looks tips up (a
revoked device's tip never counted); the client's `HlcDivergenceTracker` treats an absent tip as no
observation; `peerCursors` has had no client consumer since CR-2 B6 removed `ReclamationBarrier`.
Pure delete — no stored-row or wire shape change, no version bump. ⚠ *Since 2026-10-29 there is no
cursor at all:* relay schema v5 drops `device_cursor`, the store and `peerCursors` are gone, and only
the tip is pruned (threat model, the 2026-10-29 amendment).
4. **Scale-up backends (deferred, gated on multi-node):** `Postgres` SQL backend + `S3BlobStore` +
   `Dockerfile` + `docker-compose` (Postgres + MinIO). Pure additions behind the SPI — no rewrite.
   ⚠ **Split since (`hosting-provider-economics.md` D10): `S3BlobStore` lands first and is NOT gated
   on multi-node.** It is a reliability fix — blobs on the volume make a full disk an *outage*
   (2026-08-14; see `BlobStore.probe`), on object storage the same gap is a *bill* — plus ~15× lower
   storage cost and backend portability. The Postgres half stays gated on the multi-node trigger,
   and remains the thing `S3BlobStore` alone does **not** buy: the single-writer constraint is on
   the SQL side, so object storage is not by itself a second region.
5. **Ops** (rate limiting, observability, backups/Litestream) — folds into
   `sync-backend-relay-plan.md` §6.2–6.4.

`RecoveryChallengeStore` stays in-memory throughout.

---

## 9. Open questions

- **SQLite vs Postgres as the *reference* backend.** **RESOLVED (§0):** SQLite-only for v1 behind
  the SPI; Postgres deferred to the multi-node trigger. Open sub-question: adopt **Litestream**
  (WAL→S3 continuous replication) for single-file durability/PITR now, or rely on volume snapshots
  until there's real data to protect?
- **Migration tool**: Flyway (battle-tested, +dep) vs a tiny hand-rolled versioned runner (no dep,
  matches the "generic, few deps" ethos). Leaning hand-rolled for v1.
- **Threat-model amendment** (House Rule #2): persistence touches the relay's data-at-rest shape.
  Add a row noting the DB/S3 hold only public keys + client ciphertext + opaque refs (no new plaintext
  at rest), at-rest encryption is availability/defence-in-depth, and the only infra secrets are the DB
  DSN + S3 creds. To co-land with Phase 1.
- **`highestSeenByDevice`** (regression detection, relay-plan §note) — currently in-memory only;
  persist with the tips in Phase 2 or leave ephemeral?
