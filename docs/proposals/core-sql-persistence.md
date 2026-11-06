# Durable Persistence — SQLite Blob-KV `ObjectStore` Behind a Sync Write-Through Cache

**Status:** All phases (1–4) LANDED 2026-05-15; see §6. Decision locked
with the maintainer
(2026-05-15): a real durable store is wanted now that the data model is
stable; it is "durable KV behind the cache" only — **no relational querying
is or will be needed**, so this is *not* an ORM/SQL-query problem. Land as a
new `ObjectStore` implementation behind the unchanged interface; `InMemoryObjectStore`
stays for tests + quick-swap; JS keeps `IndexedDbObjectStore`.

**Scope:** `:core-common` only — a new `SqliteObjectStore` in an
android+desktop source set, plus the one-line DI binding swap in
`:control-shared` `ProxyModule`. Adds an `androidx.sqlite` dependency. **No
change** to the `ObjectStore` or `Database` interface, to `DatabaseImpl`, to
the observer/listener surface, or to any of the ~99 `Database` consumers.
Threat-model note required (§5) — entity metadata now persists at-rest
locally; consistent with the existing `FileDatabase` blob posture but a new
persistent surface.

Today the proxy's entire materialised entity/relation state lives in
`InMemoryObjectStore` (`MutableMap<Long, DbEntry>`) and is **lost on process
death**. File *bytes* already persist (separate `FileDatabase` blob store);
entity/relation metadata does not. This proposal makes that state durable
with the smallest correct mechanism, chosen by looking at the actual access
pattern rather than reaching for "a database".

---

## 1. Motivation

`InMemoryObjectStore` means: every cold start re-materialises the whole realm
by re-pulling and re-applying the block log from the relay. That is slow,
bandwidth-heavy, and fragile (a crash mid-session loses local mutations not
yet pushed). The data model has stabilised (file/folder/device/notification
schemas, relation system, tombstones all landed), so durable local
materialised state is now worth its cost.

The naive framing is "pick Room or SQLDelight". That is the wrong frame: the
`ObjectStore` contract has **no queries**.

---

## 2. Design Decisions

Locked for the rest of the doc.

### 2.1 The access pattern is pure point-lookup KV by `Long` — SQL features are unused

The entire SPI (`core-common/.../proxy/db/ObjectStore.kt`):

```kotlin
interface ObjectStore {
    fun storeObject(dbEntry: DbEntry)
    fun getObject(dataIdHash: Long): DbEntry?
    fun setListener(dataIdHash: Long, listener: (update: DbEntry) -> Unit)
    fun removeListener(dataIdHash: Long)
    fun removeObject(dataIdHash: Long)
    data class DbEntry(type: String, dataIdHash: Long,
                       fields: List<DbValue>, properties: Map<String, DbValue>)
    sealed class DbValue { StringValue|LongValue|ByteArrayValue|BooleanValue|NullValue }
}
```

There is no range scan, join, secondary index, or ad-hoc query. Arrays/relations
are stored as a `DbEntry` whose `fields` are `LongValue` refs; `DatabaseImpl`
walks the graph in Kotlin. A SQL engine here is **one table**:
`objects(hash INTEGER PRIMARY KEY, type TEXT, data BLOB)`. A query planner,
relations, and migration tooling are dead weight.

**Decision:** treat the durable store as a blob-KV, not a relational DB.

### 2.2 Raw SQLite, no ORM, no SQL migrations

Chosen mechanism: **SQLite accessed via the first-party `androidx.sqlite`
KMP driver (`BundledSQLiteDriver`), used as a single blob-KV table.** No
Room, no SQLDelight. Rationale (alternatives in §4):

- The real requirement is **crash-atomic durability**, which SQLite (WAL +
  atomic commit) solves correctly; a hand-rolled file reimplements
  journaling and is a corruption long-tail.
- Values are serialised blobs. **Schema evolution of `DbEntry`/`DbValue`
  happens at the serialisation layer** (the binary protocol already versions
  data), so the SQL table is *forever* `(hash, type, data)` — **no
  `ALTER TABLE`, ever**. This removes the single biggest reason to adopt an
  ORM/migration framework.
- `androidx.sqlite` is first-party Jetpack, KMP (Android + JVM/desktop +
  native), bundles SQLite, and a 1-table store is ~30 lines of statements —
  less total surface than SQLDelight's plugin + `.sq` files, far less than
  Room's DAO codegen.

### 2.3 Cache-authoritative, durable write-behind — mirror `IndexedDbObjectStore`

`ObjectStore` is **synchronous** and called inline by `DatabaseImpl` under
`DatabaseLock`. SQLite I/O is not free. The codebase already solved this for
JS: `IndexedDbObjectStore` is **cache-aside** — an in-memory
`MutableMap<Long, DbEntry>` is authoritative for `getObject`/listeners
(synchronous), and durable writes are queued async. `SqliteObjectStore`
mirrors this exactly, swapping IndexedDB for SQLite. This is the load-bearing
decision: it keeps the synchronous level-triggered listener contract intact
(§2.4) with **zero `Database`/`DatabaseImpl` changes**.

### 2.4 The `Database`/`ObjectStore` observer surface is FROZEN (coordination contract)

`core-background-processing.md` §8 has a deferred forward-link to an
event-driven `setArrayListener` redesign + `WriteOrigin`-into-`Database`
(a hypothetical `core-database-events.md`). **This proposal must not touch
the listener/observer surface, and the background track must not either,
while both run in parallel.** A new `ObjectStore` impl only needs to preserve
the existing listener semantics (register → immediate callback with current
entry if present; fire on every mutation). The two tracks are otherwise at
different abstraction levels (this = persistence SPI; background = scheduling
above `ExecutionContext`) and are independent. The event-driven redesign is a
**single-owner follow-up after both land**, not raced.

### 2.5 The block log is the source of truth — bounded durability is tolerable, with a caveat

The relay block log is authoritative; `ObjectStore` holds *materialised
derived* state. Lost **inbound** state self-heals (re-pulled on next sync).
The exception is **outbound local mutations not yet pushed**: a user-created
folder lives as an entity *and* a `PENDING_BLOCKS` entry — both in the
`ObjectStore`. Losing the unflushed tail loses that mutation entirely (not in
relay, not local). Therefore durability is **not** "best-effort whenever":
the design commits to disk on **`Database.transaction` boundaries** (§3.3),
so the loss window is one in-flight transaction, not an arbitrary debounce.

---

## 3. Final Shape

### 3.1 Schema (created on first open; never migrated)

```sql
CREATE TABLE IF NOT EXISTS objects (
    hash  INTEGER PRIMARY KEY NOT NULL,   -- DbEntry.dataIdHash
    type  TEXT    NOT NULL,               -- DbEntry.type (debug/inspection only)
    data  BLOB    NOT NULL                -- serialised DbEntry (binary protocol)
);
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;   -- WAL + NORMAL = crash-safe, fsync per checkpoint
```

`data` is the existing binary serialisation of `DbEntry` (reuse the protocol
serializer over the `DbValue` hierarchy — the JS impl's
`encodeEntry`/`decodeEntry` is the structural template; on JVM use the
project's `BinarySerializer` rather than a JS object map). `type` is a plain
column purely so the DB is inspectable; it is never queried by the runtime.

### 3.2 `SqliteObjectStore` (android + desktop)

Structural mirror of `IndexedDbObjectStore`:

```kotlin
internal class SqliteObjectStore private constructor(
    private val cache: MutableMap<Long, DbEntry>,   // authoritative for reads/listeners
    private val conn: SQLiteConnection,              // androidx.sqlite
    parentScope: CoroutineScope,
) : ObjectStore {

    private val writeScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val listeners = mutableMapOf<Long, MutableList<(DbEntry) -> Unit>>()
    // single-writer mailbox: ordered, coalescing per hash
    private val writeQueue: Channel<WriteOp> = Channel(UNLIMITED)

    override fun storeObject(dbEntry: DbEntry) {           // sync, non-blocking
        cache[dbEntry.dataIdHash] = dbEntry
        notifyListeners(dbEntry.dataIdHash, dbEntry)
        writeQueue.trySend(WriteOp.Put(dbEntry))
    }
    override fun getObject(dataIdHash: Long) = cache[dataIdHash]
    override fun setListener(h, l) { listeners.getOrPut(h){mutableListOf()}.add(l); cache[h]?.let(l) }
    override fun removeListener(h) { listeners.remove(h) }
    override fun removeObject(h) { cache.remove(h); writeQueue.trySend(WriteOp.Delete(h)) }

    suspend fun flush()      // drains writeQueue, awaits SQLite commit — called on tx boundary + shutdown
    suspend fun clearAll()   // flush, cache.clear(), DELETE FROM objects — for revocation purge

    companion object {
        suspend fun open(path: String, scope: CoroutineScope): SqliteObjectStore {
            // open conn, create table+pragmas, SELECT hash,type,data → bulk-load cache
        }
    }
}
```

The single-writer coroutine drains `writeQueue` and batches operations into
SQLite transactions. Reads/listeners never touch SQLite (cache is
authoritative), so the synchronous `ObjectStore`/`DatabaseLock` contract is
unchanged.

### 3.3 Durability hook on the transaction boundary

`DatabaseImpl.transaction { … }` already brackets every logical mutation
under `DatabaseLock`. The proposal threads an optional
`suspend (durableBarrier)` so that on transaction *commit* the store
`flush()`es the writes accumulated during that transaction (one SQLite
commit per logical transaction). This is the **only** `DatabaseImpl` touch
and it is additive (a no-op for `InMemoryObjectStore`); it does **not**
change the `Database` interface signature seen by consumers.

**RESOLVED (Phase 2, 2026-05-15):** both, not either/or. A
**non-suspend, default-no-op `ObjectStore.flushBarrier()`** SPI method
(the spec's "preferred" shape — `DatabaseImpl` calls the SPI, never
impl-specific code) **plus** the minimal `DatabaseImpl.transaction`
change to invoke it under the lock after `block()`. The `suspend
(durableBarrier)` framing is rejected: `transaction` is synchronous
common code, so a suspend barrier would force `transaction` (hence the
`Database` interface) suspend → ~99-consumer ripple, explicitly
forbidden. Consequence: the barrier is **fire-and-forget** — the
durable store enqueues an ordered commit point and returns; "durable on
the boundary" therefore means *bounded loss* (the unflushed tail, one
in-flight transaction per §2.5), **not** synchronous fsync. A truly
synchronous barrier is impossible without blocking/suspend in common
code; §2.5's wording was already written to this bounded model.

### 3.4 Source-set placement

`androidx.sqlite:sqlite-bundled` covers Android + JVM + native, **not JS**.
`:core-common` targets android/jvm(desktop)/js. So `SqliteObjectStore` lives
in a new **android+desktop intermediate source set** (custom KMP source set
in `core-common/build.gradle.kts`, or `expect`/`actual` factory with
android+desktop actuals delegating to one shared internal class). JS source
set is untouched (keeps `IndexedDbObjectStore`); commonMain keeps
`InMemoryObjectStore`.

### 3.5 DI swap (the entire production wiring change)

`control-shared/.../di/ProxyModule.kt` — the `single<Database>` block, today:

```kotlin
objectStore = InMemoryObjectStore(),
```

becomes a platform-provided binding: a `single<ObjectStore>` bound per
platform to `SqliteObjectStore.open(appDbPath, scope)` (android/desktop) and
left as `InMemoryObjectStore()` for test/system-test modules + as the
quick-swap escape hatch. `open` is suspend → resolved during proxy init
(there is already an init phase; `JsRuntime.awaitReady()` is the JS
precedent). `InMemoryObjectStore` and `IndexedDbObjectStore` are untouched.

**LANDED (Phase 3, 2026-05-15) — wiring as built:** `ProxyModule`'s
`single<Database>` takes `objectStore = get<ObjectStore>()`. The concrete
`single<ObjectStore>` is bound per platform in `PlatformProxyModule`
(the established pattern — `FileDatabase`/`DatabaseLock` already live
there, not in commonMain): desktop → `~/.tenshu/<pkg>/objects.db`,
android → `filesDir/objects.db`. The suspend `open()` is bridged with
`runBlocking` *in the platform Koin provider* — **not** the
`Deferred`+`awaitReady` JS shape: JVM can block and the first
`get<ObjectStore>()` is already the proxy-init path
(`koin.get<ProxyGateway>()` in `Main`/ProxyService, before the IPC
server serves), so the one-time cache hydration happens at startup, not
on a request thread. The impl class stays `internal`; a public
`openSqliteObjectStore(path, scope)` factory in `:core-common` jvmMain
is the cross-module surface (same convention as `Interface.create(...)`).
`InMemoryObjectStore`/`IndexedDbObjectStore` untouched;
`:core-test-utils` `TestProxy` + `:system-test-utils` build `DatabaseImpl`
directly (never via `ProxyModule`) so they stay InMemory automatically.
The two desktop integration harnesses (`control-shared` + `drive-shared`
`AppIntegrationTestUtils`) override `single<ObjectStore>` so suites stay
ephemeral/isolated — see §6 Phase 3 for the dual-backend sweep.

---

## 4. Alternatives Considered

- **Room KMP** — ORM/DAO codegen for relational access that does not exist
  here; KMP excludes JS; compiler-plugin friction for a 1-table store;
  migration tooling unused (§2.2). Rejected: heaviest, solves absent problems.
- **SQLDelight** — type-safe SQL + good KMP drivers, but `.sq` + plugin for
  one trivial table, and its migration tooling is moot (serialisation-layer
  versioning, §2.2). Workable but strictly more machinery than raw SQLite for
  zero benefit at this access pattern. Re-evaluate only if real relational
  querying is ever needed (maintainer confirmed it is not).
- **Single structured file** (snapshot or append-log) — naive snapshot is
  O(n) per write; append-log needs compaction; both reimplement
  journaling/atomic-commit that SQLite already has, debugged for 20 years.
  Rejected as primary; it is hand-rolling a storage engine.
- **Embedded KV** (RocksDB/LevelDB/MVStore) — not clean KMP / heavy native
  (RocksDB), or JVM-only (MVStore); no ubiquity or correctness advantage over
  SQLite. Rejected.

---

## 5. Threat-Model Considerations

This changes **local at-rest** posture, not the relay/sync contract. No new
wire shape, no protocol change, **assets A1–A4/A9 untouched** (those govern
the relay/ciphertext; this is purely local materialised state) — so House
Rule #2's relay/protocol trigger does not fire. However, entity/relation
**metadata now persists to disk in cleartext**, where before it was
in-memory only. This is *consistent with the existing posture*: the
`FileDatabase` blob store already persists file bytes in cleartext locally,
relying on OS/device-level storage protection, not app-level encryption.

`docs/sync/threat-model.md` gets a short note: the local materialised
`ObjectStore` is now a persistent cleartext at-rest surface with the same
protection class as `FileDatabase`. **Whether to encrypt the local DB**
(SQLCipher, or app/master-key-wrapped blobs) is an explicit **open decision,
out of scope here**, to be taken jointly with a `FileDatabase` encryption
decision (they share the same threat surface and should not diverge).

---

## 6. Implementation Plan

### Phase 1 — `SqliteObjectStore` + dependency + source set
**LANDED (2026-05-15).** `androidx.sqlite` 2.5.0 (`sqlite` +
`sqlite-bundled`) in the version catalog; `SqliteObjectStore` +
`DbEntryCodec` in `:core-common`; 7 desktop unit tests green
(round-trip, cold reopen, listener immediate-if-present + on-mutation,
`removeObject`, `clearAll`, `ByteArrayValue` blob fidelity); android +
desktop targets both compile. Two deviations from the spec text, with
rationale:

1. **No new `sqliteMain` source set** — `:core-common` already has a
   hand-rolled `jvmMain` intermediate spanning *exactly* android +
   desktop (created for `BouncyCastleEd25519Backend`; JS/iOS/common do
   not depend on it). It already *is* the "custom android+desktop
   intermediate" §3.4/§6b prescribes; the spec's premise ("no
   android+jvm intermediate") was stale. Adding a parallel `sqliteMain`
   would be redundant machinery, so `SqliteObjectStore` lives in
   `jvmMain` and `androidx-sqlite-bundled` is declared there.
2. **`DbEntryCodec`, not `BinarySerializer`** — the spec assumed the
   project `BinarySerializer` could serialise `DbEntry` blobs. It
   cannot: `BinarySerializer` operates over the `Data`/schema type
   system (`TValue`/`Reference`/`Type`), not the `ObjectStore.DbValue`
   hierarchy, and no `DbEntry`↔bytes codec existed. Added a small
   deterministic length-prefixed `kotlinx.io` codec
   (`jvmMain/.../proxy/db/DbEntryCodec.kt`) — the JVM analogue of the
   JS store's `encodeEntry`/`decodeEntry`. The SQLite table stays
   forever `(hash, type, data)`; schema evolution remains the binary
   protocol's job upstream.

**Exit (met):** unit tests — store/get/remove round-trip; cold reopen
restores state; listener fires immediately-if-present + on mutation;
`clearAll` purges.

### Phase 2 — Transaction-boundary durability
**LANDED (2026-05-15).** Non-suspend default-no-op
`ObjectStore.flushBarrier()`; `DatabaseImpl.transaction` calls it under
the lock after `block()`; `SqliteObjectStore` overrides it as a
fire-and-forget `Flush`-sentinel enqueue. The §3.3 open sub-question is
resolved there (non-suspend fire-and-forget, both SPI + `DatabaseImpl`
touch). Also: dropped the forced `Dispatchers.IO` on the writer scope —
it now inherits the injected scope (matches the JS template, mirrors how
the codebase injects background scopes, and lets a `StandardTestDispatcher`
pace the writer for deterministic crash tests); the one-shot bulk-load in
`open` still uses `Dispatchers.IO`. Added an `internal simulateCrash()`
test seam (cancel writer + close conn, no flush) — test-only, `close()`
remains the graceful path. **Exit (met):** 2 crash tests via
`StandardTestDispatcher` — committed (barriered) transaction + its
`PENDING_BLOCKS`-style `TList` entry survive; the post-barrier tail is
the only loss; an un-barriered write is lost on crash (proves the
barrier, not `open()`/the cache, is what persists). Regression-checked:
`:core-common:desktopTest`, `:file-service-proxy:jvmTest` (67),
`:sync-service-proxy:jvmTest` (69) all green.

### Phase 3 — DI wiring + dual-backend test sweep
**LANDED (2026-05-15).** Wiring detail + the `runBlocking`-vs-`Deferred`
resolution in §3.5. Dual-backend sweep is **env-switched** (not a build
change): `integrationObjectStore()` returns `InMemoryObjectStore` by
default, or a fresh **per-call** isolated temp-dir `SqliteObjectStore`
when `TENSHU_TEST_SQLITE=true` (env, so it reaches the forked Gradle test
JVM; per-call temp dir because the harness rebuilds Koin per test).
`startAppIntegrationKoin` also takes an `objectStore` param for targeted
override. **Exit (met):** `control-shared:desktopTest` (14) +
`drive-shared:desktopTest` (25) run **green against both** backends
(identical counts, 0 failures) — no hidden ordering/contract assumptions.
proxy/service suites don't go through `ProxyModule` (they build
`DatabaseImpl` directly) so they're backend-agnostic and were already
green in Phase 2. **Caveat:** the "manual desktop GUI run survives
restart" step was **not** executed (no GUI/relay in this environment);
it's covered in substance by Phase 1 `cold reopen restores`, Phase 2
crash tests, and the SQLite-backed full integration sweep through the
real proxy/service stack — but a literal desktop-app restart check
remains a manual follow-up.

### Phase 4 — Threat-model note
**LANDED (2026-05-15).** Added `T-C-09` to `docs/sync/threat-model.md`
§8.4 (persistent cleartext materialised store; same protection class as
the existing `FileDatabase`; A1–A4/A9 untouched so House Rule #2's
relay/protocol trigger does not fire — voluntary at-rest documentation)
and a §11a Still-Open-Questions entry recording the local at-rest
encryption decision as a tracked item **to be taken jointly with
`FileDatabase`** (one threat class; diverging would be incoherent).

---

## 6b. Implementation Notes for Claude Code

- **Template:** `tenshu-sdk-js/.../internal/IndexedDbObjectStore.kt` is the
  exact structural pattern (cache-aside, suspend `open` bulk-load, async
  write-behind, in-memory listeners, `flush`/`clearAll`). Copy its shape;
  swap IDB calls for `androidx.sqlite` `SQLiteConnection`/`prepare`/`step`.
- **Serialization (done, Phase 1):** ~~reuse the project binary
  serializer~~ — superseded; `BinarySerializer` is `Data`/schema-typed,
  not `DbValue`-typed. See §6 Phase 1 deviation #2. Use the landed
  `DbEntryCodec` (`jvmMain/.../proxy/db/DbEntryCodec.kt`); `ByteArrayValue`
  round-trips inside the entry blob (covered by a test).
- **Listener parity is contractual:** `setListener` MUST invoke the listener
  immediately with the current entry if present (see `InMemoryObjectStore`
  lines 27–31) — `DatabaseImpl.setArrayListener`'s old/new diff depends on
  it. Do not change this.
- **Do NOT touch** `ObjectStore`/`Database`/`DatabaseImpl` listener/observer
  code beyond the §3.3 no-op-default barrier (frozen seam, §2.4) — the
  background track / future `core-database-events.md` owns that.
- **Dependency (done, Phase 1):** `androidx-sqlite` +
  `androidx-sqlite-bundled` at `2.5.0` in `gradle/libs.versions.toml`,
  `implementation`-wired into `jvmMain` only.
- **Source set (done, Phase 1):** ~~add a new `sqliteMain`~~ —
  superseded; the existing `jvmMain` intermediate already spans exactly
  android+desktop. `SqliteObjectStore` lives there. See §6 Phase 1
  deviation #1.
- **DI (done, Phase 3):** `ProxyModule` `single<Database>` →
  `objectStore = get<ObjectStore>()`; concrete `single<ObjectStore>` per
  platform in `PlatformProxyModule` (desktop + android). `TestProxy` /
  `:system-test-utils` build `DatabaseImpl` directly so they stay
  InMemory with no change. See §3.5 + §6 Phase 3.
- **Quick-swap requirement (maintainer):** still one line — flip the
  platform `single<ObjectStore>` back to `InMemoryObjectStore()` (or set
  the harness `objectStore` param). SQLite is not entangled in
  `DatabaseImpl`/`TestProxy`.

---

## 7. Out of Scope

- **`FileDatabase` (file-byte blob store).** Separate store; not migrated
  here. Its durability already exists; its encryption decision is joint with
  §5 but separate work.
- **Local at-rest encryption** of the DB (SQLCipher / key-wrapped). §5 records
  it as an open decision; not built here.
- **Event-driven `setArrayListener` / `WriteOrigin`-into-`Database`** — the
  frozen seam (§2.4); owned by a future single-owner `core-database-events.md`.
- **Relational querying / `DatabaseImpl` rewrite.** The maintainer confirmed
  durable-KV-behind-the-cache is the whole ambition.
- **JS** — keeps `IndexedDbObjectStore` unchanged.

---

## 8. Test Plan

- Unit (`:core-common` android+desktop): round-trip; cold-reopen restores;
  listener immediate-if-present + on-mutation; `removeObject`; `clearAll`;
  large-entry (`ByteArrayValue`) blob fidelity.
- Crash/durability: transaction-boundary commit (Phase 2 exit); kill-and-reopen
  loses only the in-flight transaction; `PENDING_BLOCKS` survives a tx boundary.
- Dual-backend sweep: parameterise the proxy/service fixture so the existing
  suites pass against `InMemoryObjectStore` **and** `SqliteObjectStore`
  (catches any hidden ordering/contract assumptions).
- Manual: desktop app restart preserves the realm without a relay re-pull.

---

## 9. Sequencing & independence from the background track

This and `core-background-processing.md` are **architecturally independent**
(different abstraction levels: persistence SPI vs scheduling above
`ExecutionContext`; the background track makes zero `Database`/`ObjectStore`
interface changes). They can run as parallel branches/worktrees in either
merge order. The **only** coordination contract is the frozen
`Database`/`ObjectStore` observer/listener surface (§2.4): neither track
evolves it; the event-driven redesign is a later single-owner follow-up.
