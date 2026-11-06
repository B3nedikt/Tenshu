# Local store orphan GC — the delivery log is never collected

- **Status:** **Draft v0.3 — G1 SHIPPED, G3 SHIPPED, G3b SHIPPED (iOS, this
  slice). G2 DROPPED. G4 optional and unscheduled.** ⚠⚠ G0 **overturned v0.1's central claim**:
  the orphans are **outbound**, not inbound (§1.2), the persist site is
  `DatabaseImpl.addToArray`'s `saveData(value)` (§2.1), tombstones are **not leaked at all**, and
  **G2 is dropped** — inbound blocks are never persisted. Net effect: the fix is smaller than
  designed (one `deleteData` beside an existing `removeFromArray`) and still removes **99.87%** of
  block rows. **G3b** closes the platform that needed it most (§7.1). Everything below the status
  block is v0.1 unless marked. ⚠ *G3b was built on the iOS `FileObjectStore`, deleted 2026-11-05
  ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9): iOS now sweeps over the SQLite
  store's type index, and the file store's `OrphanedFileSweeper.IOS_EXAMINE_PAGE` budget is gone.*
- **Superseded framing (v0.1):** measurement + design, no code (2026-08-02). Found while diagnosing a
  file that appeared to be missing after a `TENSHU_STORAGE_FORMAT_VERSION` bump. Nothing here is
  built. Phases G1–G4 in §7; the load-bearing open question is Q1 (§6).
- **Date:** 2026-08-02
- **Owner:** Benedikt
- **Related:** [`retention-quota-model.md`](retention-quota-model.md) (§2 tier 1 — this is the
  unbuilt client half of it) · [`core-schema-evolution.md`](core-schema-evolution.md) §8.3 (E4
  compaction — adjacent, **not** the same thing, see §4) ·
  [`bounded-store-cache.md`](bounded-store-cache.md) (bounded *residency*; this is bounded
  *content*) · `docs/tech-debt.md`

---

## 1. The observation

A device's object store grows with **every write anyone in the realm has ever made**, not with the
amount of data the user currently has. Nothing ever removes the difference.

Measured on the desktop Control store
(`~/.tenshu/app.tenshu.control.desktop/objects.db`) on 2026-08-02 — **210,277 rows, 145 MB of row
data, 205 MB on disk**:

| type | rows | % rows | MB |
|---|---:|---:|---:|
| `tenshu/core/time/hlc` | 75,861 | 36.1 | 7.2 |
| `tenshu/sync/block` | 51,297 | 24.4 | **129.5** |
| `tenshu/core/entity/dataId/string` | 28,083 | 13.4 | 2.6 |
| `tenshu/sync/resolvedDataId` | 20,604 | 9.8 | 1.4 |
| `tenshu/sync/metadata` | 14,042 | 6.7 | 0.9 |
| `tenshu/sync/block/delete` | 10,521 | 5.0 | 2.1 |
| `tenshu/health/*` (workout, rollup, dailyActivity) | 8,835 | 4.2 | 0.8 |
| `tenshu/timeline/*` (moment, subjectRef, trace, route) | 283 | 0.1 | ~0.2 |
| `tenshu/file` + `tenshu/file/folder` | **17** | 0.008 | ~0.01 |
| everything else | ~700 | 0.3 | ~0.1 |

**About 95% of the rows and 89% of the bytes are sync bookkeeping.** The user's actual content is
~8,800 health rows, 117 timeline moments, 20 places and **13 files**.

### 1.1 The arithmetic that proves it is a leak, not a design budget

```
sync/block        51,297
sync/block/delete 10,521
sync/metadata     14,042
                  ------
                  75,860        vs   core/time/hlc = 75,861
```

Exactly **one HLC object per block, per tombstone and per metadata row, and not one of them is ever
collected** (the spare is the local tip). This is not an approximation — it is a one-to-one
correspondence across 75k rows.

### 1.2 ⚠⚠ CORRECTED by G0 — they are OUTBOUND blocks, and tombstones are not leaked at all

**Draft v0.1 said these were inbound blocks. That was wrong**, and the reasoning was unsound: the
measured store had been rebuilt by pulling, so "it pushed almost nothing" was inferred rather than
checked. A device pushes its *own* status-row churn continuously (and per `docs/tech-debt.md`, N
client sessions each run their own `SyncScheduler`, multiplying it), so a pull-rebuilt store still
accumulates outbound orphans fast — 51,297 block rows that morning had become **77,800** four hours
later, ≈1.8/s.

G0 measured reachability directly instead — every `TList` row decoded, its `LongValue` refs
collected, and every block row's hash tested for membership:

| type | rows | referenced | orphaned |
|---|---:|---:|---:|
| `tenshu/sync/block` | 77,800 | **101** | **77,699** (99.87%) |
| `tenshu/sync/block/delete` | 10,522 | **10,522** | **0** |

Two conclusions, both changing the plan:

1. **Tombstones are not leaked.** Every `DeleteBlock` is referenced by the array
   `TombstoneRegistry.record` writes, deliberately, so the in-memory map can be rebuilt across
   restarts. That is durable *state*, not garbage. (It still grows without bound — a retention
   question for [ADR 0006](../adr/0006-sync-tombstones.md) Phase 4 (the GC sweep), not a GC one. Draft v0.1 wrongly counted these
   toward the leak.)
2. **The 101 referenced blocks are the live pending queue.** Everything else is an outbound block
   that was pushed and dropped from the queue.

Confirmed independently by device attribution: HLC rows carry their emitter's `deviceId`, and in a
60k sample **45,787 are this desktop's own** id against 14,211 for the phone — and that 14,211
tracks the 14,042 `SyncMetadata` rows almost exactly, which is where an *ingested* block's HLC
survives. Inbound blocks leave a metadata row, not a block row.

---

## 2. Mechanism

The object store is **append-only in practice**. There is no refcount and no reachability sweep.

1. The only removal path is `DatabaseImpl.deleteData(dataId)` →
   `ObjectStore.removeObject(dataIdHash)` (`core-common/.../db/DatabaseImpl.kt`,
   `SqliteObjectStore.kt`). It requires an explicit `DataId` — a caller must already know the
   object exists and want it gone.
2. `DatabaseImpl.removeFromArray(...)` — what runs when a pending block is successfully pushed —
   rewrites the `TList`'s member refs and stores the shortened list. **It never deletes the member
   object.** The block leaves the queue and stays in the store.
3. Nothing walks the store looking for objects no array or entity references. There is no
   equivalent of a mark-and-sweep, and no per-object refcount that reaching zero could trigger one.

So every object that becomes unreferenced — pushed blocks, ingested blocks, their HLCs, their
`dataId/string` children — is immortal.

### 2.1 Q1 ANSWERED (G0) — the persist site is `addToArray`, on the *outbound* path

```kotlin
// DatabaseImpl.addToArray(reference, value, origin)
val dataId = getDataId(value)
val newEntryRef = dataId.getDataIdHash()
…
saveData(value)                       // ← the block becomes a row HERE
objectStore.storeObject(/* the TList with newRefs */)
```

Queueing a block for push (`SyncObserver` → `addToArray(pendingBlocksRef, block)`) persists the
block itself, because `addToArray` calls `saveData(value)` on the member. The drain then calls
`removeFromArray(pendingBlocksRef, dataId)` on a successful push — which rewrites the list's refs
and **never deletes the member row**. That asymmetry is the whole leak.

**There is no ingest-side persist site to find.** `processSyncBlock` applies a block and stores its
*effects* (the entity, its `SyncMetadata`); the block object is never written. The v0.1 hunt for a
materialisation-path store was chasing something that does not exist.

Consequences for the plan, all simplifying:

- **G1 alone removes 99.87% of block rows.** The `DataId` is already in hand at the removal site —
  the fix is a `deleteData(...)` beside the existing `removeFromArray(...)`.
- **G2 (inbound delete) is unnecessary** and should be dropped: there is nothing to delete.
- **Q3 (post-apply readers) is moot for this path.** A block that has been pushed and dequeued has
  no reader; `fetchEntityBlocks` / `openContentStream` fetch from the *relay*, not the local store.

---

## 3. Why it matters — three symptoms observed on one day

All three of these were diagnosed on 2026-08-02 as separate problems before they were traced here.

1. **Recovery is O(all history), not O(current state).** A desktop that had to rebuild its store
   spent ~1 hour replaying 51k blocks in order to end up with 13 files and ~8.8k health rows. The
   working set is ~10k objects; the replay was 210k.
2. **It is the real cause of the iOS launch-watchdog kill**
   ([`project_ios_store_wipe_watchdog_kill`](../../AGENTS.md) / tech-debt). `FileObjectStore.open`
   wipes on an unreachable core version; on iOS that is ~100k individual file unlinks on the
   scene-create path, which blows the 20 s watchdog and gets the app `SIGKILL`ed
   (`0x8BADF00D`). **Most of what it is deleting is dead HLC records.** Control needed two forced
   relaunches and Drive three to get through it. A user with no `devicectl` sees the app die at
   ~20 s, repeatedly, with nothing on screen.
3. **Disk.** 205 MB to hold 13 files and some health history, growing monotonically with fleet
   write volume, on every device including phones.

4. **It multiplies a second, independent bug.** Restoring a device from empty also hits the
   shared-cursor lane coupling (`docs/tech-debt.md`, 2026-08-02): blocks and tombstones share one
   `relay_seq` cursor, tombstones are dense where blocks are sparse, so `advancedCursor`'s `min`
   ceiling pins the whole pull to **+500 seq per tick** and the same ~500 blocks are re-delivered
   and re-applied every tick. Measured on-device: 12 pulls, 5,727 ingests, 0 drops, cursor moved
   5,516 of ~56,000. The two compound — a shorter log means fewer pages to crawl — but they are
   separate fixes.

There is a fifth, latent: every one of those blocks is ciphertext the device must keep the keys
able to open. Retaining the entire delivery log forever widens the window in which any single
device compromise yields the whole history rather than current state. That is a threat-model
observation, not a claim of a new vulnerability — see §8.

---

## 4. This is NOT what E4 compaction fixes

Easy to conflate, and the distinction decides whether this needs its own work.

- **E4 compaction** (`core-schema-evolution.md` §8.3, E4-1..E4-3a landed) advances the
  **relay's** floor: re-emit current state, drop history below the floor, gated on the device
  roster. Its subject is *relay history* and its purpose is *schema cut-off*.
- **This** is a **client-local** leak. Even with E4 fully shipped, a client would keep depositing a
  permanent copy of every block it ingests. E4 reduces how much history a *new* device replays; it
  does not sweep what an *existing* device has already accumulated, and it does not stop the
  accumulation.

They compose well — E4 shortens the log, this collects what has already been consumed — but
neither substitutes for the other.

`retention-quota-model.md` already classifies this correctly: **tier 1, the ephemeral delivery
log**, reclaimable after quorum-ack plus a retention window, explicitly opposed to tier 2 (durable
content, never time-reaped) and tier 3 (local cache, LRU-evictable). The model is written down. The
relay half exists (`ContentBlobStore.gcSweep`, `CONTENT_RETENTION_WINDOW_MS`). **The client half
was never built.** This proposal is that half.

---

## 5. Design options

### 5.1 Option A — delete at the two consumption points (recommended for v1)

A block has exactly two lifecycles, and both have a natural end with the `DataId` already in hand:

- **Outbound:** `SyncScheduler.drainPendingBlocks` already calls
  `removeFromArray(pendingBlocksRef, executionContext.getDataId(block)!!)` on a successful push.
  Add the `deleteData(...)` for the same id.
- **Inbound:** after `SyncProcessor.processSyncBlock` has applied a block, delete it.

Cheap, local, no new machinery, no background pass. Deletes the HLC child too (Q2).

**Reclaims ~124k of 210k rows and ~137 MB of 145 MB** on the measured store.

⚠ Requires Q1 answered, and requires certainty that nothing else reads a block after apply — the
legacy shared-realm manifest path and `fetchEntityBlocks` both address blocks after the fact (§6
Q3).

### 5.2 Option B — refcount in the object store

Give every object a refcount, maintained by array/field membership; collect at zero. Correct in
general, catches every orphan class rather than the two we know about — but it is a change to the
storage kernel that every write path pays for, and a wrong refcount silently deletes live data.
Disproportionate as a first move.

### 5.3 Option C — periodic mark-and-sweep

A maintenance pass (idle+charging, via `PlatformScheduler`) walking roots → reachable set, deleting
the rest. Catches orphan classes we have not enumerated, and is a natural home for tier-1 retention
policy. But it must walk the whole store, which is exactly the thing that is too big, and a bug
deletes user data. Good as G4 once A has removed the bulk and the store is small enough to sweep
cheaply.

### 5.4 Rejected — "just wipe more often"

The wipe *is* the current de-facto collector, and §3.2 is what it costs. Not a design.

---

## 6. Open questions

- **Q1 (blocking).** Where exactly does an inbound block become a stored object? `BinaryParser` does
  not store; materialisation does. Until this is named, no fix can be written. *Method: instrument
  `storeObject` with a type filter and pull a handful of blocks.*
- **Q2.** Does deleting a block delete its HLC child, or does that need its own call? The 1:1
  arithmetic (§1.1) means getting this wrong leaves 36% of the rows behind and reclaims only the
  bytes.
- **Q3.** Who legitimately reads a block *after* it has been applied? Known candidates: the legacy
  shared-realm manifest path (`SyncObserver` non-`contentId` branch), `fetchEntityBlocks`,
  `SyncProcessor.openContentStream`, and E4 re-emission. If any of these needs blocks retained, v1
  must scope the delete to the classes that do not.
- **Q4.** Retention window: delete immediately on apply, or keep for N days as a local
  replay/debug buffer? `retention-quota-model.md` tier 1 assumes quorum-ack + window for the
  *relay*; the client may not need any window at all, since it has already applied the effect.
- **Q5.** Migration for existing stores: a one-shot sweep at open would re-create the §3.2
  watchdog problem on iOS. Probably needs to be incremental and off the launch path.

---

## 7. Phasing

| Phase | Scope | Gate |
|---|---|---|
| ~~**G0**~~ **DONE** | Answer Q1 + Q3 — name the persist site | ✅ §2.1 — `DatabaseImpl.addToArray`'s `saveData(value)`, on the **outbound** path; 99.87% of block rows orphaned, tombstones 0% |
| **G1** | Delete the block beside the existing `removeFromArray` on a successful push (its `DataId` is already in hand) + its HLC child (Q2) | a test that fails if a pushed block outlives its dequeue; store stops growing with our own writes |
| ~~**G2**~~ **DROPPED** | ~~inbound delete after apply~~ | G0 proved inbound blocks are never persisted — nothing to delete |
| ~~**G3**~~ **SHIPPED** | Incremental catch-up sweep for existing stores, off the launch path (Q5) — bounded, resumable, refuses rather than guesses | ✅ a store that cannot enumerate, or a wiring without `RealmRegistry`, self-refuses; deletion is single-sourced with G1 |
| **G3b** **SHIPPED** | **iOS** — teach `FileObjectStore` by-type enumeration, wire the sweep into the iOS proxy graph, and re-read the queues before deleting (§7.1) | ✅ paging is lossless and hash-ordered; the scan does not enter residency (mutation-verified); the sweep re-reads pending-push queues from disk first |
| **G4** | Optional: periodic mark-and-sweep (§5.3) for unenumerated orphan classes | — |

G1 and G2 are independently shippable and together are the whole measured win. G3 is what makes the
fix reach devices that are already in this state — without it, existing stores stay large forever.

### 7.1 G3b — iOS (as built)

G3 shipped able to *refuse*, and on iOS it refused: `FileObjectStore.rowsOfTypePaged` returned
`null`, so the platform carrying the worst instance of the problem — a measured ~102 000-row phone —
was the one platform that could not shed a single row short of a wipe. Three things closed it.

**1. By-type enumeration where the index is hashes.** `SqliteObjectStore` answers from a
`WHERE type = ?` index. `FileObjectStore` has only `present`, a set of hashes, because the hash is
the *filename* and the type lives inside the blob. So the iOS implementation walks hashes in order
and decodes until it has a page.

⚠ **Building a type index instead was rejected**: it would have to decode every blob at `open`,
which is exactly the O(dataset) launch cost that SIGKILLed Drive-on-iOS at ~21 s and that the
store's laziness exists to remove. Paying those reads inside a bounded background sweep is the
right place for them.

⚠⚠ **Scanned rows are deliberately not cached.** `readThrough` populates the cache; a sweep using
it would pull the whole store into residency one page at a time — reintroducing the launch-memory
ceiling from the other direction. The scan reads raw and discards non-matches. This is
mutation-verified: swapping `readRaw` for `readThrough` fails
`scanningDoesNotPullTheStoreIntoResidency`.

⚠ **The scan must run to the end of the hash space rather than stop on a budget.** A short page is
the caller's `done` signal; a budget would make it ambiguous between "no more of this type" and "I
stopped early", and the sweep would latch `finished` with garbage still on disk. The cost is that
the final page of a sweep reads every remaining row once — a one-off bounded cost on a background
tick.

**2. ⚠⚠ A destructive read on a *shared* store.** This is the part with no analogue on
Android/desktop. iOS route 1 makes every first-party app its own in-process proxy over **one shared
replica directory**, so up to five live processes read and write the same rows, each with its own
resident cache. A co-resident write is invisible to this process until it re-reads.

That staleness is normally benign — online writes converge through the relay. It stops being benign
the moment a caller *deletes* based on what it read: the sweep proves a block is unreferenced by
reading the pending-push queues, and a stale queue row omits a block another app enqueued seconds
ago. Deleting it destroys an **unsent write**.

⚠ The cross-process `flock` does **not** cover this. It serialises writes; this process's cache was
already stale before it took the lock. Only a re-read fixes it — and re-reading everything is the
O(dataset) cost above. So a new narrow SPI, `ObjectStore.invalidateCached(hashes)` (default no-op),
lets the sweep name the handful of rows whose freshness its decision actually depends on. It drops
the cached copy, never the row.

**3. Wiring.** The iOS `PlatformProxyModule` did not pass `objectStore` into `initSyncService` at
all, so even an enumerating store would not have swept. It does now; a store that still cannot
enumerate self-refuses, so passing it is safe regardless of the bound implementation.

⚠ **JS is still unswept**, deliberately: `IndexedDbObjectStore` is asynchronous, so it cannot answer
a synchronous read-through at all — the property that made laziness (and therefore this scan)
available on iOS. It keeps returning `null` and the sweep keeps refusing, which is the safe
direction.

---

## 8. Threat model & house rules

- **HR #1 unaffected** — this is entirely client-local; the relay sees nothing different.
- **HR #9 unaffected** — no stored-row or wire shape changes. Deleting rows is not a reshape, so no
  service version moves and no `SchemaMigration` is owed.
- **No relay redeploy.**
- **Threat model:** retaining the full delivery log indefinitely means a device compromise yields
  the realm's entire write history rather than its current state. Collecting consumed blocks
  *narrows* that. If G2 lands, note it against A5 (device-local plaintext/ciphertext at rest) —
  a reduction, so an amendment rather than a new asset.

---

## 9. G4a — orphaned **entity** rows, and why they were not cosmetic (2026-08-14)

G1/G3/G3b collect `sync/block` rows. Nothing collected orphaned **entity** rows, which §7's table
parked under G4 as *"optional: periodic mark-and-sweep for unenumerated orphan classes"*. This is the
first slice of that, scoped down to the one class measured in the field.

### 9.1 What was measured

`DeleteFileImplementation` emits **one** tombstone, for the delete's root. Before
`DeletedFolderSubtreeReaper` a peer applying it deleted the folder row and nothing beneath
it. On the maintainer's devices, 2026-08-14: **787 `tenshu/file` rows under
`/Backup/OnePlus CPH2581/WhatsApp Images` with no folder row**, on Android *and* iOS, plus the
folder's 787-member `FILES` array still resident.

### 9.2 ⚠⚠ They block recovery — this is not a space problem

`UploadFileImplementation` tests for an existing file **by path `DataId`**, not by reachability:

```kotlin
val existing = executionContext.database.getData(dataId)   // StringDataId(File.ID, filePath)
if (!replaceExisting && alreadyExisted) throw AlreadyExistsError(...)
```

An orphaned row therefore still **occupies its destination path**. Synced Folders re-uploads resolve
to `BackupUploadOutcome.Skipped`, so the drain runs, skips every file, reports `UP_TO_DATE` — and the
folder can never be rebuilt. That was the live symptom: re-ticking *Screenshots* worked (deleted with
the reaper installed, so its children cascaded and the paths were free) while re-ticking *WhatsApp
Images* did nothing at all.

⚠ It was called "harmless, unreachable rows don't display" earlier the same day. That was wrong, and
the reason is worth keeping: **unreachable is not the same as inert** when another subsystem keys on
the same identity by a different route.

### 9.3 The rule, and why not reachability

A row is garbage **iff some ancestor on its own path is absent**.

§5.3 rejected a general mark-and-sweep for v1 because "it must walk the whole store … and a bug
deletes user data". This rule needs neither a global reference graph nor a root walk: each candidate
is decided by at most *path-depth* point lookups. ⭐ It cannot mis-delete a reachable row, because a
row whose ancestors all exist is never a candidate — **regardless of what else references it**, which
is exactly the property a reachability sweep has to prove the hard way.

- ⚠ **Every** ancestor, not just the parent: `/a/b/c/d.jpg` under a deleted `/a/b` is caught in one
  pass rather than waiting for `/a/b/c` to be collected on a later cycle.
- ⚠⚠ Root `/` is never a candidate — it has no parent, and deleting it would take the whole tree with
  it through the subtree reaper. `theRootIsNeverACandidate` pins that.
- Bounded, resumable, self-terminating (`SWEEP_COMPLETE`), and **refuses** when the store cannot
  enumerate — all four inherited from G3, for the same reasons.

### 9.4 One-time, and how it ends

> ⚠⚠ **Superseded by §9.7 (2026-08-16).** Every claim below was falsified on a real device: rows
> were found stranded on a store that had already latched complete, so the sweep is *not* one-time,
> and the terminal cursor is *not* a sufficient record. Read §9.7 before relying on this section.

The *cause* is fixed: `DeletedFolderSubtreeReaper` cascades on any folder row's death, so no new
orphans of this shape are created. This sweep is cleanup of pre-fix wreckage and latches complete.

⚠ Deleting the sweeper later is safe once every device has run it, and unlike the backfill repairs
(`sync-device-catch-up.md` §11.12) it needs no marker generation: the terminal cursor **is** the
record, and the rule is idempotent — re-running it on a clean store deletes nothing.

### 9.5 ⚠⚠ A sealed-realm process never ran it at all (2026-08-15)

G4a shipped registered on `SyncScheduler.addSyncCycleHook`. On **Drive-on-iOS it never executed a
single pass**, and the store it was written for kept its 786 orphans — the ones that also blocked
their own re-upload (§9.2).

`tickOnce` returns at `if (!syncManager.isEnabled())`, and the cycle hooks run *after* that guard.
Drive's route-1 runtime keeps its realm sealed by design (`DriveLocalRuntime`: *"no genesis, no
unlock"*), so its scheduler ticked, hit the guard, and returned — for weeks — with the sweeper
constructed on every launch and called on none.

**Fixed with `addMaintenanceHook`**, run *before* the guard. GC needs no keys, no relay and no
unlocked realm; the invite pickup does, so it stays where it is.
`SyncSchedulerEnableEdgeTest.maintenanceRunsOnALockedTickWhileCycleHooksDoNot` asserts **both**
halves, because moving the wrong hook is the easy mistake.

⚠⚠ **Two iOS apps do not share one store.** Control's container held ~45 863 objects and Drive's
~55 146, with **independent sweep cursors** — Control had swept itself to `COMPLETE` while Drive had
never started. Every orphan census in the investigation read *Drive's* container, so the sweep
looked broken on iOS when it was in fact only unreachable there. ⚠ Do not reason about "the iOS
store" as a single thing.

**And the diagnostics are what made this a one-launch answer.** `OrphanedFileSweeper` now logs, once
per process: that it was **created**, that a **first pass was requested**, and any **refusal with its
reason**. Those three lines distinguish *never wired* / *never called* / *called and refusing* —
states that are identical from outside and had previously cost a full device teardown to separate.
⚠ Deliberately **not** debug-only: the failure was found on release-signed installs on real devices,
which is exactly where debug-only logging is absent, and one-shot lines are not noise.

### 9.6 The examine budget is iOS's real limiter — measured, then raised (2026-08-16)

§9.5 got the sweep running on Drive-on-iOS. Watching it for four minutes then showed *what* paces it,
and it is not the match budget:

```
scanned=34 deleted=32   scanned=99 deleted=92   scanned=88 deleted=49
scanned=35 deleted=25   scanned=36 deleted=11   scanned=50 deleted=23
scanned=33 deleted=17   scanned=41 deleted=24   scanned=50 deleted=30
```

Nine passes, 303 rows collected, **never once approaching `PAGE = 250`**. `FileObjectStore` has no
type index — the hash is the filename and the type lives *inside* the blob — so a pass reads
`EXAMINE_PAGE` rows to find a few dozen matches. That store held ~53 000 objects of which ~1 300 were
file rows: a **2.5 % match density**, ~27 passes to traverse.

⚠⚠ **Foreground is the scarce resource, which is what makes this worth tuning.** Drive's route-1
runtime ticks while the app is open and effectively not while it is backgrounded — an earlier check
found the cursor frozen at the same value across half an hour, having run a burst at install and then
stopped. Passes are therefore a budget the user spends by leaving the app on screen: ~27 passes is
10–20 minutes of that. `IOS_EXAMINE_PAGE = 8_000` makes it ~7.

⚠ Per-platform, not global: on `SqliteObjectStore` the `WHERE type = ?` index means the examine
budget never binds, so raising it there would buy nothing. It is plumbed as
`initFileService(sweepExamineLimit = …)` and passed **only** from the iOS `PlatformProxyModule`.

⚠⚠ **The test for it had to go through `initFileService`, not construct the sweeper.** The first
version passed `examineLimit` to the constructor directly — which exercised a parameter that already
worked and skipped the plumbing this change adds. Hard-coding `EXAMINE_PAGE` inside `initFileService`
left that test green; routing through the real entry point turns it red at `scanned=250`. The same
shape as §11.11's lesson in `sync-device-catch-up.md`: *test the seam you changed, not a neighbour
that resembles it.*

### 9.7 ⚠⚠ §9.4 was wrong — the sweep needed re-arming (2026-08-16)

§9.4 said the cause was fixed, the sweep was cleanup of pre-fix wreckage, and the terminal cursor
**is** the record so no marker generation is needed. Measurement falsified all three parts.

**What was measured.** Both devices read `SWEEP_COMPLETE`, and iOS had genuinely finished: its
store went 70 490 → 69 249 objects, collecting 1 241 rows, at the raised iOS examine budget (§9.6).
On Android, the same store then showed:

| folder rows present | file rows under it |
|---|---|
| `/`, `/Backup`, `/Backup/iPhone`, `/Backup/iPhone/All Photos`, `/Backup/OnePlus CPH2581`, `/Backup/OnePlus CPH2581/Screenshots` | — |
| **`/Backup/OnePlus CPH2581/Camera` — absent** | **1 154** |

Those 1 154 are orphans by this document's own rule, on a store whose sweep is already terminal.
Nothing would ever have collected them, and per §9.2 they also occupy their destination paths, so
that bucket could never be rebuilt.

⚠ The sweeper is not blind to folder rows, which was the first hypothesis and is wrong: folders are
type `tenshu/file/folder` but addressed by `StringDataId(File.ID, path)`. Proof from the same
store — Screenshots' 98 children survived, which only happens if its folder row resolved.

**Where they come from is *not* established, and the fix does not pretend otherwise.**
`DeletedFolderSubtreeReaper.readChildren` degrades to "descendants not reaped" when it cannot read
the array, and the folder row dies regardless, so that path provably strands a subtree. The
historic trigger for it — a dangling member raising `Entry not found` — stopped applying on
**2026-08-13**, when top-level array reads started skipping missing members. So the Camera rows
most likely predate that change, and the degrade path may now be rare. What has *not* been shown is that
no other path strands rows. The sweep is therefore treated as a standing backstop, not a finished
job.

**Two mechanisms, doing different work:**

1. **Re-arm on a failed cascade.** `DeletedFolderSubtreeReaper` calls
   `ObjectStore.clearOrphanedFileSweepCursor()` on its degrade path — the one event in the system
   that knows a subtree was just stranded and previously told nobody. ⚠ On that path **only**,
   never on every folder delete: a successful cascade leaves nothing behind, and re-walking on each
   delete would reinstate the per-launch full read the sentinel exists to remove.
2. **A one-shot repair.** `FILE_SWEEP_REPAIR_VERSION` in a reserved row (`Long.MIN_VALUE + 6`),
   spent once at `initFileService`. This is the only mechanism that reaches rows *already*
   stranded, including the 1 154 — a future-triggered re-arm does nothing for them. Versioned
   rather than boolean, mirroring `sync_backfill_relatch_repaired_v3`, so a later repair bumps the
   generation instead of adding a row.

⚠⚠ **A re-arm the live sweeper ignores is not a re-arm.** `OrphanedFileSweeper` holds
`finished = true` in memory and outlives the store write, so it re-reads the sentinel when latched.
That is one point read per tick on a swept device — not a scan; what §9.4's sentinel exists to
prevent is the full read of *every row* (G3b), and a single reserved-row lookup is the price of
noticing that the world changed.

⚠ So §9.4's "needs no marker generation" is retracted. The terminal cursor is a record of *having
finished*, which is a different fact from *having finished after the last thing that could strand
rows* — exactly the distinction a marker generation exists to carry.

**Falsification.** Each of the three guards was removed in turn and the matching test went red:
drop the reaper's `clearOrphanedFileSweepCursor()` → `aCascadeThatCannotReadItsChildrenReArmsTheSweep`;
disable the one-shot → `theOneShotRepairReArmsAFinishedSweepExactlyOnce`; make the latch
unconditional → `aReArmedSweepIsNoticedByTheInstanceThatAlreadyFinished`.

⚠ The reaper test drives the degrade branch through a store stub failing one specific row, and says
so. Staging the *historic* trigger (strand a member, delete the folder) was written first and
passed for the wrong reason — top-level array reads skip the dangling member, so the cascade completed normally
and the fixture assertion caught it. A reproduction that no longer reproduces is worse than an
honest stub.

### 9.8 ⚠⚠ An absent folder is not evidence while the store is still pulling (2026-11-05)

The rule of §9.3 (a row is garbage iff an ancestor is absent) assumed that a row's absence means it
is gone. On a store that is still pulling its realm's history, that is false:
- the C2 backward walk (`sync-device-catch-up.md`) applies the log **newest-first**, so a file
  written into a folder arrives before the folder's own, older block;
- the sweep runs at the start of every tick, between pages;
- with sync present it removes an orphan **with a real tombstone** (CR-2 B3b).

So, on any device joining a realm with history, and on any store wiped to re-pull (the iOS reset of
`ios-shared-store-sqlite.md` B.22), it could tombstone a live file **on every device**.

**Fix:** a realm member sweeps only once `ObjectStore.hasPulledItsHistory()` holds: the first-pull
marker is `COMPLETED` and no backward walk is mid-way. ⚠ Unknown is not complete. The check comes
before the scan and before the cursor moves. A runtime without sync, or one in no realm, sweeps as
before. Pinned by `OrphanedFileSweeperTest.aFileWhoseFolderHasNotBeenPulledYetIsNeverSwept`
(`ios-shared-store-sqlite.md` B.22.6 (429)).

⚠ **Not closed:** after the history is pulled, a forward page boundary that splits a folder's row
from a child's (a move or a re-creation whose rows straddle two pages, with a tick between them) can
still look like an orphan for one tick. Not observed, and not ruled out.
