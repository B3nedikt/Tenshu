# Bounded read-through store cache (read-scaling option C)

**Status:** **Kernel slice SHIPPED 2026-07-23** — P1 bounded mailbox +
dedupe-by-hash, P2 C-lite size-tiered read-through, P3
soak/residency tests + docs. As-built deltas in §"As built (2026-07-23)".
The `spike/bounded-store-cache` branch is superseded by the shipped C-lite
shape (kept only as the full-LRU reference). **Option B also shipped
2026-07-23** (B1 core slice + B2 cross-device read; `fit-workout-streams-content-plane.md` now on
master with as-built) — `spike/workout-streams-content-plane` is superseded
too. Remaining tracks (task-scoped sync lane; C-full/D at metadata scale; the
JS twin fork) are pinned in the trimmed [`tech-debt.md`](../tech-debt.md)
entry.

**Revision note (v2, 2026-07-15) — two material corrections after review:**

1. **F3 corrected.** Write backpressure does *not* conflict with the
   `ObjectStore` contract: the contract forbids *suspending* (the ~99-consumer
   signature ripple), not *blocking*. A bounded mailbox with a blocking send is
   impl-internal and deadlock-free. This upgrades the C family from "read-side
   only" to a complete fix when paired with that mailbox.
2. **C-lite added (size-tiered retention)** — small rows stay resident forever
   exactly as production today; only large rows are read-through. Much smaller
   blast radius than the spiked full-LRU shape.

**The recommendation changed with these** (§Recommendation): B stays the fix
for Fit; the general kernel fix is one PR = bounded mailbox + C-lite; full-LRU
and no-app-cache are deferred end-state options for metadata-at-scale.

**v3 (2026-07-16):** added §"The task-scoped sync lane" (maintainer direction):
bulk ingest reframed as **content-plane-first** with one checkpoint sync block
per task — fixes the *flow* half at the sync plane (including receiver-side
ingest, which today replicates the OOM realm-wide), demotes the bounded store
mailbox to a safety net, and reframes B as the first instance of the
bulk-ingest rule rather than a one-off. C-lite retention still owns the *stock*
half (thumbnails). Recommendation updated again.

---

## Problem

`SqliteObjectStore` (the durable KV behind every proxy on Android + desktop) is
**cache-aside**: `open(...)` bulk-loads *every* row into an unbounded
`MutableMap<Long, DbEntry>`, and `getObject` only ever reads that map ("never
touch SQLite"). Resident memory is therefore O(entire dataset), and the write
mailbox is `Channel(UNLIMITED)`. Two casualties:

- **Fit (observed).** A full-history Takeout import puts thousands of ~56 KB
  `Workout.Streams` blobs into the map → Control OOMs (`DeadObjectException`)
  at ~4.5k records (~250 MB).
- **Drive thumbnails (predicted; see the tech-debt entry).**
  `File.Thumbnail` / `File.ThumbnailLarge` are ~25–35 KiB per file and
  **legitimately eager** (offline list rendering) → same ceiling at roughly
  8k–12k thumbnailed files, i.e. a large photo-library backup. **B structurally
  cannot fix this case** (the fields must stay eager); a retention change in
  the store is the only fix that keeps them eager-and-durable without keeping
  them all resident.

## The C spike (full-LRU shape)

One file changed: `core-common/.../db/SqliteObjectStore.kt` (compiles,
`:core-common:compileKotlinDesktop` green; branch `spike/bounded-store-cache`).
Shape:

- **Bounded LRU** — `LinkedHashMap(accessOrder = true)`, capped at
  `maxCacheEntries` (spike default 10 000).
- **Synchronous read-through** — `getObject` cache-miss does a blocking
  single-row `SELECT` on a **dedicated read connection** (`readConn`), decodes,
  populates the LRU, returns.
- **No bulk-load** — `open(...)` starts the cache empty.
- **Pin unflushed writes** — entries whose durable write hasn't committed are
  invisible to `readConn`, so they are pinned (per-hash refcount) and skipped
  by eviction until the writer commits them.

## Findings

**F1 — read-through must be a blocking SQLite read under the lock.** Fits the
sync `getObject` contract, but a cache miss pays a point-SELECT + decode while
holding `DatabaseLock`. In the full-LRU shape this can hit hot metadata; under
C-lite (below) only large-blob reads ever pay it.

**F2 — a second connection + WAL is required for correctness.** The writer
holds `conn` (single-threaded coroutine); read-through uses `readConn`. WAL
lets the reader see committed rows while the writer's transaction is open.

**F3 — the spiked C bounds READS; the write backlog needs a bounded mailbox —
and that is in-contract (CORRECTED).** The crash path is writes: a bulk import
calls `storeObject` faster than the single writer drains the UNLIMITED mailbox;
uncommitted entries are pinned and unevictable, so the cache grows with the
backlog. v1 of this finding claimed bounding the mailbox "conflicts with the
synchronous non-suspend `storeObject` contract" — **that was wrong**: the
contract forbids *suspending*, not *blocking*. A bounded `Channel` +
`trySendBlocking` inside the JVM impl changes no signature. Deadlock-free with
one provision: the writer coroutine never acquires `DatabaseLock` (`applyBatch`
touches only its own SQLite connection), **and it must run on a dispatcher that
blocked producers cannot starve** (give it its own thread / `Dispatchers.IO`,
not a small pool the blocked op-executor threads exhaust). Semantics are honest
backpressure: a full mailbox stalls the importing thread under `DatabaseLock`
until a batch commits — other clients see bounded latency spikes during a bulk
import instead of process death.

**F4 — blast radius.** This is the storage kernel under every app and every
type. Full-LRU makes *all* rows evictable and adds a tuning knob; C-lite
shrinks the changed surface to large rows only, but either way this needs the
full store test suite + a concurrency soak (writer vs read-through vs
eviction), not a feature smoke.

## The write side (companion fix, needed under every option)

- **Bounded mailbox + blocking send.** `Channel(capacity = N)` +
  `trySendBlocking` in `storeObject`/`removeObject` (per F3, in-contract and
  deadlock-free given the writer's dispatcher provision).
- **Coalesce-dedupe-by-hash.** Upsert semantics make every earlier `Put` to the
  same hash dead weight. Today an import appending n members to one array
  re-stores the array entry n times → n growing copies ride the mailbox and all
  get upserted. In `applyBatch`'s coalesce, keep only the **last** op per hash
  (preserving relative order of the survivors so Put/Delete interleavings stay
  correct).
- **`flushBarrier` on a bounded channel.** Its fire-and-forget `trySend` can
  now fail when full — harmless (every drained batch commits anyway; the
  barrier only marks a commit point that batching already provides), but worth
  a comment. `flush()` uses suspending `send` — unaffected.

## C-lite — size-tiered retention (recommended kernel shape)

The spiked full-LRU treats all rows uniformly, which is where most of its risk
lives: hot small metadata that chain queries walk constantly becomes evictable,
and the cap is a per-device tuning knob. Size-tiering splits by what the data
*is*:

- **Small rows (≤ T, T ≈ 4–8 KiB; ~99% of rows — entities, arrays of member
  hashes, metadata):** resident forever, **exactly as production today**.
  `open(...)` bulk-loads them (`WHERE length(data) <= T`). No LRU, no eviction,
  no read-through on hot paths — zero behavioural delta.
- **Large rows (> T):** never retained. Pinned only while their write is
  uncommitted; dropped after commit; later reads are a point-SELECT on
  `readConn` + decode. Peak large-row residency during a bulk import = the
  bounded mailbox's worth of pinned entries.
- **Absent-key probes stay O(1).** Keep a resident `Set<Long>` of large-row
  hashes (loaded at open via `SELECT hash … WHERE length(data) > T`, maintained
  on store/remove/clearAll; 8 B/entry). `getObject` miss: hash ∉ set → `null`
  with no SQLite hit (today's behaviour); ∈ set → read-through. Without this,
  every existence probe of an absent key would pay a point-SELECT.

Details worth pinning:

- **Tier crossing.** An update can cross T in either direction; `storeObject`
  decides the tier per store (small→large: drop from resident cache, add to the
  hash set, pin; large→small: retain resident, remove from the set).
- **Sizing without double-encoding.** Tiering needs the entry's encoded size at
  store time, but encoding currently happens on the writer thread. Estimate
  structurally (sum of the `DbValue` byte-array lengths dominates any blob-tier
  entry) rather than encoding on the `DatabaseLock` thread; exact size only
  matters near T, where either tier is acceptable.
- **Listener contract holds.** Array entries are member-hash lists (small →
  resident; array listeners unaffected). `setListener`'s fire-immediately goes
  through `getObject`, which read-through covers. `storeObject` fires listeners
  with the passed entry before enqueueing — no cache dependency.
- **Threshold is a constant, not a knob.** Anything above ~4–8 KiB is a blob by
  construction (both observed cases are 25–56 KiB); no per-device tuning.

This kills both observed cases by construction while the metadata plane behaves
identically to today, and eviction correctness reduces to "pin until commit".

## Option map (explored 2026-07-15)

Read-side retention policies (all presuppose read-through):

| Option | Verdict |
|---|---|
| **C-full** (spiked): LRU everything | End-state if *metadata* outgrows RAM (~1M+ entities); until then, unnecessary risk + a tuning knob |
| **C-lite**: size-tiered | **Recommended kernel shape** (above) |
| Origin-tiered: `WriteOrigin` hint ("bulk ingest → don't retain") | Adjunct only — size says more than origin. Same for Android `onTrimMemory`-driven eviction |
| **D**: no app cache — SQLite page cache (+ `PRAGMA mmap_size`), prepared point-SELECTs, decode per read | Simplest resident model; the only one that also survives metadata-at-scale. Needs a chain-walk benchmark first. ⚠ The JS twin (`IndexedDbObjectStore` — the template this store mirrors) cannot do synchronous read-through at all; any kernel change forks the two strategies |

Schema-level:

| Option | Verdict |
|---|---|
| **B** per-type (spiked) | Right for `Workout.Streams` — *wrongly* eager, nothing wants it resident |
| **B∞**: first-class blob-tier field kind in the schema DSL (auto writer/resolver/emit against `FileDatabase`; each future blob field = one-line declaration) | Long-term right shape for *deliberately* on-demand blobs; aligns with ADR 0017 / `file-content-addressing.md`. Most design work |
| Product knob: demote `ThumbnailLarge` to OnDemand, keep 100px `Thumbnail` eager | ~25–35 → ~5–10 KiB per file; ceiling ~10k → ~30–50k files. A lever, not a fix |

Non-fixes (for the record): `largeHeap` (raises the ceiling once; desktop
unaffected) · in-memory compression (thumbnails already JPEG; constant factor) ·
`SoftReference` values (ART clears aggressively; non-deterministic; still needs
the read-through machinery) · importer pacing / `ImportPacer` (bounds the
mailbox, never the cache) · reactive observers (delivery queue, not the
resident store) · per-service store files (same heap) · summary-only import
(data loss).

## B vs C (revised)

|                                   | **B — blobs to content plane** | **C — bounded store cache** |
|-----------------------------------|--------------------------------|------------------------------|
| Where the change lives            | `:health-service(-proxy)` only | `:core-common` storage kernel |
| Fixes the Fit **write** OOM       | **Yes, by construction** — the blob never enters the store cache *or* the mailbox; it streams straight to `FileDatabase` | Not as spiked (UNLIMITED mailbox); **yes with the bounded mailbox** (F3 corrected). Under C-lite, large uncommitted rows are pinned only until commit |
| Fixes the Fit **read** OOM        | Yes — `OnDemand`, never bulk-resident | Yes — bounded residency |
| Fixes the **thumbnail** ceiling   | **No** — thumbnails are legitimately eager | **Yes** — the case C exists for |
| Scales to TB                      | For the blob type it targets   | All reads, all apps (C-full/D for metadata-at-scale) |
| New op / schema / migration       | Yes (op + property flip + external key + migration) | None |
| Cross-module wiring               | Yes (proxy factory gains `FileDatabase`, writer registration, emit plumbing) | None — one file |
| Contract friction                 | None — existing content-plane seam | F1 blocking read under the lock; write bound is in-contract (blocking ≠ suspending) |
| Blast radius                      | One feature                    | Every app, every type (F4; C-lite shrinks it) |

## The task-scoped sync lane (maintainer direction, 2026-07-16)

Reviewing this survey surfaced that the *flow* half of the problem is better
fixed one level up — at the sync plane — than by backpressure at the store.
The observation (maintainer): the **relay mailbox's** role is to carry small
blocks that tell the other devices in the realm to fetch new data; bulk-import
overload of it is not an intended load, it is a misuse. Conceptually **one
import should be one message to the mailbox referencing many blobs uploaded to
the blob store**, and that aligns 1:1 with the background-processing model:

> **1 background-processing task == 1 liveness operation ==
> 1 sync block (per checkpoint) == N blobs to the content plane.**

Grounding in today's code:

- `SyncObserver.processSavedData` generates **one sync block per entity save**
  (`sync-service-proxy/.../SyncObserver.kt`). A 4.5k-workout import therefore
  pushes ≥4.5k blocks through the metadata plane, each dragging the eager
  ~56 KiB `Workout.Streams` payload — a **violation of the ADR 0017 two-plane
  split** (metadata plane = small blocks; bytes = content plane, pulled
  lazily), not a property of the design. Option B restores that split for one
  type; the task lane generalises it into the rule: **bulk ingest is
  content-plane-first; the metadata plane carries references, batched at task
  checkpoints.**
- **The OOM replicates across the realm today.** Every receiver ingests those
  fat blocks via `SyncProcessor` → `saveData` → *its own* unbounded store —
  the importing phone OOMs first; the desktop OOMs syncing it down.
  Content-plane-first fixes sender, relay mailbox, *and* every receiver at
  once (receivers ingest tiny rows; blobs are `OnDemand`).

Refinements settled in review:

- **One block per *checkpoint*, usually one.** A multi-hour backup emitting a
  single block at the end has zero cross-device visibility until it finishes
  and forfeits staged work on a crash; checkpoints (every N items) keep
  resumability and incremental convergence. Fit's import ≈ literally one
  checkpoint.
- **The checkpoint block is a container of per-entity deltas**, each with its
  own HLC — merge stays entity-granular (a concurrent edit to one workout on
  another device resolves normally). Batching, not semantic fusion.
- **The array flood collapses for free.** n appends to a membership array
  today re-emit the growing array entry n times; a checkpoint emits its final
  state once — the wire twin of the store's coalesce-dedupe-by-hash.
- **Organic edits keep the per-save reactive path.** This adds a bulk lane; it
  does not replace the reactive one.

Alignment with background processing — this is the pattern's third occurrence:
folder upload (the `TenshuFilesRepository` liveness precedent), the Fit import
FGS (`:app-liveness`), and the Maps region-download redesign (poll rejected in
favour of a background-live task driving the FGS with push progress) are all
*durable task + liveness + bulk content movement + small metadata commit*. The
equation above can serve as the **definition of a bulk task** in
`core-background-processing.md`. The seams already exist: the `WriteOrigin`
discriminator is already on the save path, and `SyncObserver.awaitEmitIdle`
was built anticipating the bgproc Phase 6 headless drain — the emit channel is
where a task-scoped checkpoint boundary naturally lands. The
checkpoint-block-referencing-blobs shape is the same **manifest** primitive
recurring in ADR 0017 and [`file-content-addressing.md`](file-content-addressing.md)
(and the folder-link-sharing tree manifest).

**Stock vs flow — what this changes in the recommendation.** The task lane
fixes the **flow** problem at its root: wire volume, relay-mailbox load,
receiver ingest, and (via checkpoint-batched commits) most of the local write
flood — demoting the bounded store mailbox from "the fix" to a **safety net**
(still worth having: ~20 lines, and it catches any non-task bulk writer). It
does **not** fix the **stock** problem: 50k thumbnails accumulated over a year
arrive politely and still sit eager + resident forever — *eager-on-device ≠
resident-in-RAM* is retention policy and stays C-lite's job. The two compose
rather than compete.

## Recommendation (v3)

1. **Now, for Fit:** B — `Workout.Streams` shouldn't be on the entity at all.
   With the task lane it stops being a one-off: it is the **first instance of
   the bulk-ingest rule** (content-plane-first, references in the metadata
   plane).
2. **Kernel slice (one PR):** C-lite size-tiered read-through for the
   **stock** ceiling (thumbnails, where B structurally doesn't apply) + the
   bounded mailbox & dedupe-by-hash as the flow **safety net**.
3. **Flow, properly:** the task-scoped sync lane (§ above) — spec it as part
   of `core-background-processing.md` (task == liveness == checkpoint block ==
   N blobs), with the checkpoint emit boundary at the `SyncObserver` emit
   channel.
4. **Later, at real scale:** B∞ for declared blobs; benchmark D vs C-full when
   metadata volume itself becomes the resident-set problem.

## If the kernel slice is built (beyond the spike)

- Writer on a starvation-proof dispatcher (F3 provision) — its own thread or
  `Dispatchers.IO`.
- Full store test suite + concurrency soak (writer vs read-through vs
  pin/unpin), crash/durability tests re-run against the pinned path; a
  bulk-import memory test asserting bounded residency (e.g. 50k × 30 KiB rows
  under a fixed heap).
- `IndexedDbObjectStore` (JS) explicitly out of scope — document the fork.

## As built (2026-07-23)

Shipped as three PRs on `core-common/.../db/SqliteObjectStore.kt` (+ tests);
no other module changed, no on-disk or wire shape change (no
`TENSHU_STORAGE_FORMAT_VERSION` bump owed — retention is in-memory policy).

**P1 — write side.** `Channel(512)` + `trySendBlocking` (F3: blocking
is in-contract); writer on a **dedicated daemon thread** by default, with a
`writerContext` parameter as the test seam for the paced crash/durability
tests (they previously paced the writer through the parent scope's
dispatcher). Dedupe = a survivor mask (last Put/Delete per hash, survivors in
original order; Flush/ClearAll are ordered markers, always kept).
`flushBarrier`'s `trySend` may fail on a full mailbox — harmless, documented.
`close()`/`simulateCrash()` close the mailbox so a racing producer fails fast.
⚠⚠ **Amended 2026-09-13** ([`drive-folder-files-dangling-member.md`](drive-folder-files-dangling-member.md)
§6.1): a batch whose `COMMIT` failed used to be rolled back and **dropped silently** — its rows
readable from their pins until the process exited and never on disk — and the writer connection
had no `busy_timeout`, so a foreign write-lock holder failed it on the first try. Now the writer
waits (`WRITER_BUSY_TIMEOUT_MS`), retries in place on a bounded backoff (`COMMIT_RETRY_BACKOFF_MS`)
with a `WARN` per attempt, drops loudly as the floor, and `open()` reports a second opener of the
same path (`crossProcessContended`). Pinned by `SqliteObjectStoreCommitFailureTest`.

**P2 — C-lite.** Threshold `LARGE_ROW_THRESHOLD_BYTES = 8 KiB`
(constant, not a knob). Store-time tier via structural size estimate; reopen
partitions by exact `length(data)` — divergence only near the threshold,
where either tier is acceptable. Pinning is a refcounted
`ConcurrentHashMap<Long, Pin(count, entry?)>`:

- each enqueued large Put pins; the writer unpins **after commit** — and
  equally when the batch dedupe drops a superseded Put (found in review: a
  dropped op's pin would otherwise leak and its stale copy shadow the
  read-through forever);
- `removeObject` nulls the pinned value (removal readable immediately) while
  the refcount survives until every enqueued op is accounted for; deletes
  themselves never pin (the large-hash set is dropped synchronously, so the
  read-through is never consulted for a removed key);
- a failed batch keeps its pins: the pinned copy is the only readable truth
  for a row disk never received.

`setListener`'s fire-immediately goes through `getObject` so a
dropped-but-durable large row still fires. `isEmpty()` =
resident ∪ large-hash set (a pinned row's hash is already in the set).

**P3 — verification.** Seeded 4k-op soak (sequential lock-thread mutations
vs the live writer thread; linearizable reads mid-soak; full-model check
post-flush and post-reopen) + a 1500 × 32 KiB bulk-import test asserting
peak pinned copies ≤ 2×mailbox+slack and zero large-row residency after
commit. Android smoke gate passed (Control + Drive + Maps on the tiered
store).

**Deliberately not built:** LRU/eviction of small rows (resident forever, as
production before); any `IndexedDbObjectStore` change (the JS twin still
bulk-loads — the strategies are now forked, see tech-debt); the task-scoped
sync lane (separate track — **specced 2026-07-24** in
`core-background-processing.md` §10 + `core-task-composite.md` §5–§6, incl.
a corrected premise: no array-append wire flood exists for the two migration
targets, membership is locally derived).
