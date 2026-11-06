# Drive — a dangling member in a folder's `files` list, and how it did *not* get there

- **Status:** **Investigation, 2026-09-13. The mechanism is established; the trigger is not.**
  The two mechanisms the report named — a delete that skips the nested list, and a re-seed
  applying a list row ahead of its members — are **ruled out on current code by test** (§4,
  §5), and every *delete* is ruled out for this instance by evidence: both rows are alive on
  the phone and the desktop is a complete replica minus exactly them (§7.1). Both missing
  members are **named** (§1.2). The rows were lost by **failed commits of at least two separate
  writer batches** during the v21 re-seed of 2026-09-13 18:42 → ~18:50 — the SQLite writer
  dropped a failed batch silently, and the frozen copy carries the fingerprint: a row enqueued
  *after* each lost file row is on disk while the file row is not, which no suffix loss and no
  delete can produce (§7.1). That defect is **fixed with a regression test** (§6.1). What made
  those commits fail is narrowed to **a foreign SQLite connection taking the write lock a few
  times in that window** — not a second Control, not the investigation, not a full disk, not a
  second in-process store — and its identity is **not recoverable on that machine** (§7.2 (3));
  the fix's logging and `fs_usage` on the next re-seed are what replace the guess. ⚠ The
  store may now be repaired or resynced; the evidence that could be captured has been.
- **Re-verified against master 2026-09-22** (`b775a2ad7`, 104 commits on). Every ruling below
  still holds on current code; what moved is the *read* side, which shipped meanwhile and is
  named where it is relevant (§1.1, §7.2 (6)). ⚠ Two things this doc said in the present tense
  are now history rather than plan — the nested-member skip is merged, and
  `ReferenceResolverImpl` has the same posture for an absent composite row.
- **Date:** 2026-09-13
- **Owner:** Benedikt
- **Related:** [`core-local-store-orphan-gc.md`](core-local-store-orphan-gc.md) (G4a — the
  same folder, from the other side: rows with no folder) ·
  [`drive-synced-folders.md`](drive-synced-folders.md) §3.9 (deleting a backup destination) ·
  [`bounded-store-cache.md`](bounded-store-cache.md) (the SQLite store's write mailbox) ·
  [`sync-entity-identity.md`](sync-entity-identity.md) (EI-1b — the 64-bit local hash, and the
  v21 wipe that dates every row in this store) · [ADR 0024](../adr/0024-merger-field-resolution.md)
  · `docs/tech-debt.md` *"`PendingBlockRestamper` threw on a dangling queue member"* (the iOS
  twin of this symptom, cause also unconfirmed)

---

## 1. The evidence

### 1.1 As reported

Control's desktop store (`~/.tenshu/app.tenshu.control.desktop/objects.db`) held, in the
`Folder.FILES` list row of `/Backup/OnePlus CPH2581/WhatsApp Images`, references to rows that
do not exist:

| what | value |
|---|---|
| folder composite row | `6939062656711727838` |
| its `files` list row | `-4299254704025414121` — 7 506 bytes, 830 references, 828 resolve |
| dangling reference, slot 708 (byte offset 6 378) | `2329932176197719662`, `dataId = null` at read time — the one the production throw reported |
| dangling reference, slot 710 (byte offset 6 396) | `-5762757953325457904` — found by the maintainer's walk of the whole list row, 2026-09-13 |

⚠ The report said *one*; there are **two**, two slots apart with one healthy member between
them, near the end of the list. Two independent methods agree: the runtime `WARN DatabaseImpl:
relation … skipped 2 member(s)` (observable only with the reader-side tolerance, which master
did **not** carry when this was written — it does now, so the count is reproducible),
and a static walk of the 7 506-byte row on its 9-byte stride — 828 present
+ 2 missing = 830 slots, 4 trailing bytes. The static walk was wrong twice before it was right
(a mis-phased grid, then a filter bug); the number is trusted because the runtime count
corroborates it.

The read that failed is the **nested** one: `DbEntryMapper.deSerialize` with no `DataId` in
hand (a member reached through another relation, or `getComposite` on a hash the process has
never mapped) materialises the list through the strict `deserializeArray`, which threw `Entry
not found`. The reader-side tolerance for that path **shipped separately**, with no file overlap
with this one (`0ff4d5b4`, 2026-09-13): it threads `getMemberEntry` through `deSerialize`
into nested list reads, so that read now skips the member and counts it at
`DatabaseImpl.reportMissingMembers` instead of killing the whole tree; a later change gave
`ReferenceResolverImpl` the same posture for a composite reference whose row is absent. Neither
touches how the row went missing — this document is about the writer, and nothing below is
superseded by either.

`~/.tenshu/app.tenshu.control.desktop/local-store.properties` carried
`sync_backfill_relatch_repaired_v3=true` and `sync_seeded_realm_epoch=16`.

### 1.2 What the hashes add — the member has a name

A store row's key is `DataId.getDataIdHash()`: since EI-1b a **64-bit FNV-1a** over
`<type>_<identity>`, and every `File` *and* `Folder` row keys under `tenshu/file` (a folder
inherits `FileDataIdProvider`). A folder's list row keys as
`EntityFieldDataId(TList, <folder id>, "tenshu/file/folder/files")`, which folds the parent's
bytes into the same hash. Re-deriving both from the folder's path reproduces the two hashes
above exactly (`scripts/store-relation-audit.py --self-test`, `DataIdHashVectorsTest`), which
settles that the derivation is right and dates the row: **a 19-digit hash is a 64-bit one, so
this reference was written by post-EI-1b code.** ⚠ On *this* machine that is not "after
2026-09-11": the Control bundle in place until 09-13 was built 09-08, three days before EI-1b
merged, and no dev `:run` followed — so the first post-v21 Control was the one installed
**2026-09-13 at 18:38**, whose `open()` found a v20 store, wiped it and re-seeded it (watched
live: `objects.db` 8.46 MB at 18:42 → 9.08 MB by 18:45), and the first *"Couldn't load this
folder"* came minutes later. **The references were minted between 18:42 and roughly 18:50 on
2026-09-13, during a full v21 re-seed** — the maximum-pressure case for the commit path §6.1
fixes — and within minutes of `reinstall-all-apps.sh` having jpackaged ten desktop bundles,
`pkill`ed Control, and launched ten apps.

A member's hash cannot be inverted, but a WhatsApp media name is `IMG-YYYYMMDD-WA0000.jpg`,
so the space under that one folder is enumerable. `scripts/DataIdHashSearch.java` walks every
day since 2010 × 10 000 sequence numbers × 16 extensions × 6 prefixes — 5.86 × 10⁹ candidates —
and found **exactly one** match for each hash:

```
slot 708   2329932176197719662   /Backup/OnePlus CPH2581/WhatsApp Images/IMG-20240410-WA0004.jpg
slot 710  -5762757953325457904   /Backup/OnePlus CPH2581/WhatsApp Images/IMG-20240830-WA0004.jpg
```

A false positive on a 64-bit hash over that many candidates has probability ≈ 3 × 10⁻¹⁰ per
search; the names are the files. Both are WhatsApp images from **2024** — 2024-04-10 and
2024-08-30 — i.e. two of the bucket's *old* files, present at every earlier upload of this
folder, not recent additions. (Both being `WA0004` is not a signal: a day's sequence numbers in
this bucket are small, so low numbers repeat constantly.)

**The pair says more than either alone — but not what it first seemed to.** The first reading
was one *window*: a contiguous stretch of lost enqueue order in which the member between them
was lost too and **written again afterwards**. The relay refutes it (§7.1): the survivor has
exactly two blocks, both early (seq 1824/1825) and both *between* the lost pair's blocks (1820,
1832), with no later write — and two blocks is ordinary in that neighbourhood, not a re-write.
Its writes were applied between the two losses and committed, so the losses are **two separate
failed batches with a committed batch between them**: intermittent, not one cut. That is the
signature of a *sustained* condition during that stretch of the re-seed (§7.2).

### 1.3 What could not be done here

Steps 1 and 2 of the report as written — run Control against the store and sweep its `WARN`
lines; read the phone's store — need the maintainer's machine and phone. Neither was available
to this investigation, and §7 says exactly what to run. ⚠ A `WARN DatabaseImpl: relation …
skipped` line fires only when a relation is **read**, so a log sweep undercounts by
construction; `scripts/store-relation-audit.py` walks the whole store copy instead and is the
enumeration step 1 actually wants.

---

## 2. "Nested inline list" and "top-level relation" are one row

The question the report raised — *does deleting a File maintain the nested `folder/files` list,
or only top-level relations?* — assumes two lists. There is one.

- `saveData(Folder(files = [a, b, c]))` writes the list through `writeValuesToDb`'s `TListDto`
  branch, at `EntityFieldDataId(TList, <folder>, files).getDataIdHash()`, after minting a row
  for each member that has none (create-only), unioned with whatever that row already held
  (only-grow) — `DbEntryMapper.kt`.
- `RelationMaintainer` (`FolderFilesMembership`, `FileRelationMembership.kt`) derives the parent
  from the member's **path** and targets the same `EntityFieldDataId` with `addToArray` /
  `removeFromArray`. `DeleteFileImplementation` builds the same id by hand for its step 1.

So the list a folder block writes inline and the list a delete maintains are the same bytes at
the same hash, and the reader's `dataId = null` says only *how it was read*, not how it was
written.

---

## 3. Every way a `File` row leaves this store

Enumerated by reading every `removeObject` and `deleteData` call site in `commonMain` that can
name a `File` row. (The ~30 other-service `ExecutionContext.deleteData` sites — calendar, photo,
contact, health, preference, routine — each build a `StringDataId` under their **own** type id, so
none can address `File.ID`; they are not listed.)

| path | fires `RelationMaintainer.onDataDeleted`? |
|---|---|
| `ExecutionContext.deleteData` — `DeleteFile`'s cascade, `processDeleteBlock`, `DeletedFolderSubtreeReaper`, `OrphanedFileSweeper`, `DataIdMigrator.retire` (moves), `MoveFileImplementation`'s `extraLocalCleanup` (a moved folder's own `FILES` row), `DeleteRealmData`, `RemoveApp` (`AppGrant` rows only) | **yes** — one transaction, row removal + observer fanout under the lock |
| raw `Database.deleteData` — `SyncObserver` (`PendingContentEmit`), `TombstoneRegistry` (`ResolvedDataId`, `LocalWriteMetadata`), `RetiredBlockDisposal`'s `deleteRetiredBlock` (blocks + block-owned children), `DataIdMigrator.sweepSyncMetadata`, health attribution + rollup rows | n/a — **none of them touches a `File` row** |
| `ObjectStore.removeObject` outside `DatabaseImpl` | only the two reserved cursor rows |
| the core-version wipe (`DELETE FROM objects`), and `SyncManagerImpl.wipeLocalStore` | takes the list rows with it |
| ⚠ **`StoreSchemaMigrator` at store open** — `StoreMigrationPort.deleteRow`, *below* the `Database` layer, when a registered `SchemaMigration`'s lambda returns null | **no** — and it does not need to: the same transaction re-filters every `TList.ID` row against the dropped hashes (`StoreSchemaMigrator.kt`, "membershipSweeps"). Inert for files today — all thirteen `FileService` steps are no-ops |

Every delete of a `File` row therefore runs the maintainer, which computes the folder's list row
from the file's path and rewrites it without the member. For the reference to dangle, one of
these had to hold at runtime: the maintainer was not installed, the resolved `DataId` was not
the shape it keys on, the member's path did not point at this folder, **the row's write never
reached disk**, or the reference was re-added after the delete by a writer reading stale
membership. §4–§6 take those in turn.

⚠ The first is not a state a proxy context can be in: `ClientRegistry.mintSession` — the shared
builder behind `getExecutionContext` *and* `systemContext()` — calls `installRelationMaintainer()`
on every context it mints, before `runInitialization()` runs any `init<X>Service` — which is why
the tests in §4 pass with `initFileService()` removed (checked). The
maintainer needs only the registered `RelationSpec`s, and `fileServiceCommonModule` registers
those at schema time.

---

## 4. Mechanism (a) — a delete that skips the nested list: **ruled out by test**

`sync-service-proxy/…/FolderFilesDanglingMemberTest.kt`, over a proxy with the file service's
production observers installed (`initFileService()` — the reaper and the history policy; the
maintainer comes with the context, §3), `FolderMerger` registered, and the production
`WireIdResolver`:

| test | what it pins |
|---|---|
| `anIngestedTombstoneUnlinksTheChildFromTheNestedFilesList` | a folder saved with a **materialised** list, then a tombstone for one member: the row goes, the list row drops the hash, and a cold strict read of the folder (a `DatabaseImpl` with an empty `hashToDataId`, i.e. the exact read that threw in production) succeeds |
| `anIngestedTombstoneStillUnlinksTheChildAfterARestart` | the same through a **fresh** `SyncProcessor` — the tombstone's target comes back from the durable `ResolvedDataId` row, not the registry's cache, and the maintainer still finds the parent from it |
| `aLocalDeleteUnlinksTheChildFromTheNestedFilesList` | `ExecutionContext.deleteData` directly — what the cascade, the reaper and the sweeper call |

The oracle is two-fold on purpose: the list row's member hashes are checked against the object
store directly (the invariant), *and* the cold strict read is performed (the symptom).

---

## 5. Mechanism (b) — a re-seed applying a list row ahead of its members: **ruled out by test**

The write side cannot produce it by construction, and the tests say so:

- the create-only nested write mints a row for every listed member that has none, *before* the
  list row is written (`aFolderBlockWhoseMembersNeverArriveCreatesEveryMemberRow`);
- a tombstone that lands **before** the block listing its target (the lanes drift arbitrarily)
  is honoured by `purgeDeadMembers` — tombstoned **and** absent — and the list row agrees
  (`aTombstoneAppliedBeforeTheBlockListingItsTargetLeavesNoDanglingReference`);
- the newest-first walk (`sync-device-catch-up.md` C2) offering a re-create above the older
  tombstone it superseded keeps both the row and the reference — `processDeleteBlock`'s HLC
  guard (`aReCreateAppliedAboveAnOlderTombstoneKeepsBothRowAndReference`);
- a member known only through its parent's block is unindexed, so its tombstone deletes
  nothing — the safe side of the invariant
  (`aTombstoneForAMemberKnownOnlyThroughItsParentLeavesNoDanglingReference`; ⚠ see §6.3 for
  what it leaves behind instead).

Two further points about the seed, from reading rather than testing:

- `SliceSnapshotReader` / `FileSliceSnapshotProducer.applySlice` never writes membership: it
  saves each entry through `ExecutionContext.saveData` and lets the maintainer hang it on its
  parent, exactly as `UploadFile` does.
- The only-grow union keeps a dangling reference **forever** once one exists: `existingRefs` is
  read from the stored list row and unioned in unconditionally, and `purgeDeadMembers` shapes
  the *list*, never those refs. Nor does a read repair one — `DatabaseImpl.getArrayData` counts
  and warns, nothing more. ⚠ (`OrphanedFileSweeper.kt`'s KDoc claims the dangling-member fix made reads "skip
  missing members **and repair the reference**"; the second half is not true of any code, then or
  now.) What removes one is a `removeFromArray` naming that member — in practice
  `RelationMaintainer.onDataDeleted` or `DeleteFile`'s step 1, i.e. a real delete of that path —
  or `removeDanglingArrayMembers`, which runs **once per process, personal realm only, over the
  two outbound block queues** (`SyncScheduler.repairDanglingQueueRefsOnce`; `pendingTombstonesRef`
  is not repaired, and a sealed realm never reaches it). That is why the reference
  persisted through 829 successful reads; it says nothing about how it was born.

---

## 6. What was found instead

### 6.1 ⚠⚠ The SQLite writer dropped a failed batch silently — fixed

`SqliteObjectStore` commits each drained mailbox batch as one transaction. Until this change:

1. the **writer** connection had no `busy_timeout` — only `readConn` did — so any other
   connection holding SQLite's write lock failed the writer's `BEGIN IMMEDIATE` on the spot;
2. `applyBatch` caught the failure, rolled back, failed the batch's barrier acks (which nothing
   awaits — `flushBarrier`'s ack is a throwaway) and **moved on, with no log line and no
   retry**.

Every row in a dropped batch stays readable from its pin for the life of the process, so the
process that suffered the drop notices nothing. After a restart those rows are simply gone —
and a list row committed by a *later* batch that referenced one of them now dangles. That is
this incident's shape exactly, and in a re-seed the batch boundaries fall between a member's
`Put` and the list row's `Put` constantly (512 ops per batch, `addToArray` enqueues them
back-to-back). It is also the shape of the iOS queue's four dangling blocks
(`tech-debt.md`, `PendingBlockRestamper`), though that store is a different implementation.

⚠ What would trigger it on a desktop: **a second process open on the same store.** Control's
packaged build autostarts as a LaunchAgent daemon and `control-desktop/Main.kt` records that
*"there is no single-instance guard yet"*; a dev `:run` — which is how one reads a store's
`WARN` lines — opens the very same `~/.tenshu/app.tenshu.control.desktop/objects.db`. Two
writers contend on every commit, and each one's `knownHashes` / row cache goes stale under the
other's writes, which is the cross-process staleness the iOS entry favours as *its* cause. A
`sqlite3` shell mid-write on the live file does the same for the duration of its statement.

**Fixed** (`SqliteObjectStore.kt`), pinned by `SqliteObjectStoreCommitFailureTest`:

- `PRAGMA busy_timeout = 5000` on the writer connection, matching `readConn`;
- a failed commit is **retried in place** on a bounded backoff (100 → 1 600 ms, five retries),
  logging every attempt — in place, so every later batch stays behind it and a member row still
  reaches disk no later than the relation that references it;
- a batch that fails every attempt is dropped **loudly**, naming the op count and the hazard;
- a drop in a batch **no barrier rode in** is carried to the next barrier (`unreportedDrop`), so
  `flush()`/`close()` answers for it — whether a flush's writes share its batch is a scheduling
  detail, and the answer must not be. Consumed, never latched; ⚠ best-effort, since that next
  barrier may be a `flushBarrier` nobody awaits — the **log** is the guarantee (D1);
- a batch of nothing but barriers takes no transaction at all: it has no rows to lose, so
  putting it through the ladder under a foreign lock would stall ~33 s and then log
  `DROPPED a batch of 0 write op(s)` naming nothing — spending the alarm on a non-loss;
- `open()` takes an advisory lock beside the file and reports a second opener — another
  process, or a second instance in this one — as `crossProcessContended`, with a `WARN` at open.

⚠ Bounded rather than infinite on purpose: a permanently failing disk must not wedge every
producer behind a full mailbox forever, and a loud drop is the honest floor. ⚠ The bound is a
real stall: six attempts × 5 s busy timeout + 3.1 s of backoff ≈ **33 s** in which the sole
mailbox consumer commits nothing, so producers block on a full mailbox — a frozen desktop, or
an ANR wherever a producer sits on a main thread. It costs anything only while something else
holds the write lock, which is the condition the advisory lock reports; the number is in the
class comment beside the bound.

**Process death is not this defect, and cannot produce this shape.** The maintainer's comment
raised it because `scripts/reinstall-all-apps.sh` stops Control with `pkill -f
"/Applications/Tenshu"` and a two-second grace, and that ran right before the broken listing was
first seen. Nothing in Control handles `SIGTERM` — the clean-quit path (`exitApplication` →
`shutdownAndJoin`) quiesces sessions and never closes the store, and the writer is a daemon
thread — so a `pkill` (and, for that matter, a clean quit) loses whatever the writer has not
committed. But it loses it as a **suffix** of the enqueue order: the mailbox is FIFO, every
batch commits atomically, and every writer enqueues a member's row *before* the list row that
names it (`addToArray` saves the value first; `writeValuesToDb` serialises members before the
list). A suffix cut therefore leaves a row without its membership — an orphan the G4a sweep
already handles — never a membership without its row. Only a **non-suffix** loss produces the
dangling shape, and a dropped batch is the one non-suffix loss this store had. Pinned from both
sides: `everyWriterStoresTheMemberRowBeforeTheListRowThatNamesIt` on the real writers, and
`a crash loses the tail…` on the store.

### 6.2 What this does *not* prove

Nothing in this container can show that a batch was dropped on the maintainer's desktop
between 2026-09-11 and 2026-09-13. The fix is justified on its own — the hole is real, and
silent — but calling it the cause of this instance would be picking the most plausible story.
§7 lists the two facts that decide it.

### 6.3 ⚠ A member learned only through its parent's block can never be deleted by its tombstone

The create-only nested write (`writeValuesToDb`) mints member rows through `saveEntry` directly
— no observer fires — so `TombstoneRegistry.onDataSaved` never indexes them and
`processDeleteBlock`'s `resolveDataId` answers `null` for their tombstones. The row lingers; the
list keeps referencing it; `purgeDeadMembers` sees it as *present* and keeps it. A zombie, not a
dangle — the safe side of this invariant, and a separate defect. Tracked in `tech-debt.md`.
⚠ "Never" has one escape: `TombstoneRegistry.rememberDataId` repairs the index from the read side,
and the OnDemand byte-fetch path reaches it (`OnDemandResolutionImpl` → `SyncProcessor.rememberEntity`).
So a parent-learned member whose bytes are fetched on demand *does* become tombstone-deletable
afterwards — which is why the zombie is a hole in indexing, not in delete.

### 6.4 ⚠ `OrphanedFileSweeper` is not gated on catch-up

It runs as a maintenance hook on every tick. During a newest-first backfill a file's row can
exist before its folder's (older) block has been walked, and the sweeper's rule — *an ancestor
row is absent* — would delete it, stamping a local tombstone at replay time that then drops the
file's own (older) block when the walk reaches it. It is inert in practice only because a fresh
store latches `SWEEP_COMPLETE` on its first, near-empty pass and is re-armed only by the reaper
or the one-shot repair version. Not measured; recorded so the next person does not have to
re-derive it.

---

## 7. What would establish the cause — the runbook

Everything below is read-only. **Copy the store before anything else**, and copy `-wal` with it.

### 7.1 Established on the maintainer's machine (2026-09-13)

- **A frozen copy exists** at `~/tenshu-store-evidence-20260913/` — `objects.db` + `-wal` +
  `-shm`, taken with Control stopped, checksums verified, `integrity_check: ok`, 44 571
  objects, `removeDanglingArrayMembers` **not** run. ⚠ Its WAL was later checkpointed by a
  plain `sqlite3` open (see 7.2 (3)): content-identical, no longer byte-identical; the
  at-freeze digests are kept beside it. ⚠ A copy taken with this build carries an empty
  `objects.db.lock` beside it; ignore it.
- **Both rows are alive on the phone and absent only on the desktop** — `2329932176197719662`
  and `-5762757953325457904` each 1 row on the phone, 0 on the desktop. **That rules out every
  delete mechanism for this instance**: a realm-wide delete would have removed the rows there
  too and maintained the relation. Nothing is lost; a desktop resync restores both.
- **The desktop is otherwise a complete replica.** Read at the same moment, caught up to the
  same relay tip (21 769): `tenshu/file` 1 598 on the desktop vs **1 600** on the phone — a
  gap of exactly the two rows — with `tenshu/file/folder` (10), `modification/created`
  (1 608) and the object total (44 637) equal. So this is a **local write loss of two file rows
  on the desktop store, with their list membership intact** — §6.1's shape, and nothing else
  in the store is off.
- **The survivor's bytes cannot decide the window question.** Slot 709 (`IMG-20231230-WA0003.jpg`
  in the maintainer's count, which runs three lower than §1.1's) is byte-identical in shape to
  its eight nearest neighbours — all `tenshu/file`, all 434 bytes, in a population whose sizes
  do vary — but a re-applied write of the same entity produces a byte-identical row, so "looks
  like its neighbours" is consistent with both readings. The maintainer's walk also found
  **no row anywhere in the store referencing any of the nine hashes** except the list row: the
  write metadata is not reachable from the member row. ⚠ That is a null result, not a
  negative one — see 7.2 (3) for why it is reachable another way.
- **Was a second process open?** No direct evidence either way: one Control held the store
  when looked at, no Tenshu crash reports. (The phone's Control had been OS-reaped —
  `reason=13 OTHER KILLS BY SYSTEM`, cached-empty, 274 MB — which is the phone, not the
  desktop, but says the platform reaps backgrounded Tenshu processes aggressively.)
- **Process death is out** (§6.1) — including `reinstall-all-apps.sh`'s `pkill`, which ran
  right before the listing broke. ⚠ But that script is still a lead, for two different
  reasons: see 7.2.
- **The relay's per-target rows, and the EI-1a boundary in them.** `sync_block` is addressable
  by `target_entity_id_hash` in the clear (only `envelope` is ciphertext), and the table shows
  the cutover: targets below seq 20 232 are 32-bit numerics as text, 20 232 → 21 774 are 32-char
  hex — independent corroboration of §1.2's dating, and the reason the three files' blocks
  were reachable **without the phrase**: they predate EI-1a, so their targets are the legacy
  `"tenshu/file_<path>".hashCode()`. The rows: lost file 1 → one block, seq **1820**; the
  survivor → two blocks, **1824/1825**; lost file 2 → one block, **1832**. `delete_block` holds
  0 rows for all three in a realm with 255 delete blocks. Present neighbours sit on both sides
  and between (1816, 1836, …), and 2 of the 8 nearest also carry two blocks.
- ⭐ **The sidecar fingerprint — found, and it decides the mechanism.** Searching both stores
  for each path string (validated against known-present controls):

  | file | phone | desktop (frozen) |
  |---|---|---|
  | `IMG-20231230-WA0003.jpg` (survivor) | `dataId/string` ×2, `file` ×1, `modification/created` ×1 | identical |
  | `IMG-20240410-WA0004.jpg` (lost) | same four | `string` ×1, `created` ×1 — **file row and one `string` row missing** |
  | `IMG-20240830-WA0004.jpg` (lost) | same four | `string` ×2, `created` ×1 — **file row missing, nothing else** |

  The two `dataId/string` rows are the `targetDataId` **children of the two `ResolvedDataId`
  rows** — the file's and its `created` modification's — since those are the only composites
  that persist a `DataId` as a child row. Now follow one file block through ingest, in enqueue
  order: `resolveConflictAndSave` → `saveData(file)` enqueues **Put(file)**; the observers fire
  in registration order — `RelationMaintainer` (installed at context mint) → `addToArray` →
  **Put(file)** again, **Put(list)**; then `TombstoneRegistry` (registered by `initSyncService`,
  later) → `saveData(ResolvedDataId)` → **Put(string child)**, **Put(ResolvedDataId)**; then
  **Put(hlc)**, **Put(SyncMetadata)**. For the second lost file, the `string` child — enqueued
  *after* both file Puts and the list Put — is on disk while the file row is not. Under a FIFO
  mailbox with atomic batches that is impossible for a suffix loss (§6.1) and impossible for a
  delete (the phone has the row, there is no tombstone, and a delete removes the
  `ResolvedDataId` and the membership too). **A later-enqueued write persisted while an
  earlier-enqueued one did not: a non-suffix loss, which only a rolled-back batch produces.**
  The membership survived because the list row is re-put by every later member add, carrying
  the reference forward; the file row had no later write. The first lost file's boundary fell
  one row later (its own `string` child went with it), and the surviving `created` rows come
  from separate blocks in separate batches — both exactly what batch boundaries at arbitrary
  points predict. ⚠ The maintainer's comment read this table as "a partial loss inside an
  entity's row set" and moved on; it is the fingerprint §7.2 (3) was written to find.

### 7.2 What is decided, and what is still open

**Decided: the mechanism.** The two rows were lost by **failed commits of at least two separate
writer batches, with a committed batch between them**, during the re-seed's pass over relay seq
≈1820–1832 (the window reading is out, §1.2; the fingerprint is above). Every alternative is
excluded by evidence, not by argument: no delete (phone + relay), no suffix loss (a later Put
persisted over an earlier one), no corruption (`integrity_check: ok`). §6.1's fix is therefore
the fix for *this* instance, not only for its class.

**Open: the trigger** — what made two commits in a short stretch fail while the ones around
them succeeded. What the shape of the failure says, before any candidate:

- **It was interleaved at sub-second scale.** Seq 1820, 1824/1825 and 1832 sit in one 500-block
  page, applied within the same tick; two batches failed with a committed batch between them.
  A lock held for a long stretch (a `TRUNCATE` checkpoint, a `VACUUM`, one large foreign
  transaction) fails a *contiguous run* of batches; interleaving means the lock came and went
  repeatedly — **a competing writer taking short transactions**, or an I/O condition flapping
  at that rate, which nothing supports.
- **A lone process cannot do it to itself.** In WAL mode readers never block `BEGIN
  IMMEDIATE`, the passive auto-checkpoint never errors, the store's two connections are
  single-threaded on their own sides, and every statement is closed before `COMMIT`. The
  single-process failures left are I/O-level (`SQLITE_FULL`, `SQLITE_IOERR`), for which
  there is no evidence (below). So the trigger was almost certainly **another connection to
  `objects.db` taking the write lock, repeatedly, between 18:42 and ~18:50 on 09-13.**

Candidates, with what the maintainer's machine has said so far:

1. **A two-process overlap around the reinstall.** `reinstall-all-apps.sh` does
   `pkill -f "/Applications/Tenshu"`, `sleep 2`, copies the new bundle, then (by default,
   `LAUNCH_CHECK=1`) `open -a` the new Control. Its `pkill` pattern matches only a Control
   launched from `/Applications`; a **dev `:run` Control** (`…/bin/java …`) or a copy launched
   from a build directory survives it and keeps syncing — and the new Control then opens the
   same `~/.tenshu/app.tenshu.control.desktop/objects.db` beside it. Two syncing writers on one
   file is the trigger §6.1 names; every collision dropped a batch on whichever side lost, and
   the loser's rows stayed readable from their pins until it exited. (The daemon LaunchAgent is
   `KeepAlive=false` and the tray agent never launches Control, so launchd does not manufacture
   the overlap by itself — only a second launch does.) ⚠ Neither process would have logged it:
   the old code was silent, and under launchd the daemon's stdout goes nowhere.
   *Status — closed for a dev `:run`.* The Gradle daemon logs put the last
   `:control-desktop:run` builds on 09-06 and 09-09 and none after; and since a `:run` build
   stays running for as long as the app does, the three 09-09 builds' **end** lines
   (`BUILD FAILED in 17m 30s` 19:35:31, `BUILD SUCCESSFUL in 26s` 19:39:39, `BUILD FAILED`
   19:43:22) bound those JVMs to 09-09. The daemon agent is `RunAtLoad=true`, so a login while
   a Finder-launched copy is "reopened at login" stays a *theoretical* second path with no
   instance here. ⚠ The unified log cannot help on this machine: `eventMessage CONTAINS[c]
   "tenshu"` returns 0 lines, launchd included. `lsof ~/.tenshu/app.tenshu.control.desktop/
   objects.db` is the definitive *current* check; going forward the store says it itself at
   open (`WARN SqliteObjectStore: another store already holds …`) — on stdout **and** in
   `objects.db.log` beside the store, since the daemon's stdout is exactly where that line
   would otherwise have gone to die.
2. **A full disk.** `SQLITE_FULL` fails a commit the same way `SQLITE_BUSY` does and the old
   code dropped it just as silently; the reinstall's *build* phase is when this machine's disk
   has filled before (`tech-debt.md`, 2026-08-15). *Status — no evidence:* not one
   `No space left on device` in any Gradle daemon log, no `SQLITE_FULL` / `database or disk is
   full` anywhere, `/` at 24 % with 51 GiB free. And it does not fit the interleaving.
3. ⚠ **A non-Tenshu SQLite client on the live file — the one candidate left, and it cannot
   be named after the fact.** The advisory lock detects a second *store*, not a second
   *connection*: `sqlite3`, Python's `sqlite3`, DB Browser, a backup agent that opens the
   database rather than copying it — any of them taking the write lock (a write, `VACUUM`,
   `wal_checkpoint(FULL|RESTART|TRUNCATE)`, an index for analysis) failed the app's `BEGIN
   IMMEDIATE` on the old code, and a *sequence* of short ones interleaves. Pure reads
   (`SELECT`, `LIKE`, `integrity_check`, `.dump`) cannot block a WAL writer and are not it.
   *Status:*
   - **The investigation itself is excluded.** The damage was reported minutes after 18:38,
     while the store was still growing; the maintainer's first access came later and every
     query ran against a `cp` of `objects.db` + `-wal` + `-shm`, never the live file.
     (⚠ In passing, that copy was later opened with plain `sqlite3` rather than
     `sqlite3 -readonly`, which checkpointed its WAL on close — 4 124 152 → 0 bytes — so the
     copy is content-identical but no longer byte-identical to the freeze; the original
     digests are kept beside it. ⚠ That is *not* a demonstration of the live mechanism: the
     close-time checkpoint runs only when the closing connection is the **last** one on the
     file, so beside a running Control a CLI's close takes no lock. It is still the right
     habit for evidence: `sqlite3 -readonly`, or a `file:…?immutable=1` URI;
     `store-relation-audit.py` opens `mode=ro`, which cannot checkpoint.)
   - **A second store instance in the same process is excluded by code.** Desktop Control has
     one production opener (`PlatformProxyModule`'s `single<ObjectStore>`); shared realms
     share that store under `"$realmId::$baseKey"` rather than opening their own; the tray
     agent, the FFM helpers and the hub runtime (own data root) never open it.
   - **Shell history cannot answer it.** Commands run by an agent session execute in a
     non-interactive shell that never appends to `~/.zsh_history`; the maintainer's own
     dozens of `sqlite3` invocations that day left zero lines. "Nothing in history" is not
     evidence of absence for the most likely class of opener.
   - **The count says the writer was sparse.** Two or three dropped batches in a re-seed of
     ~44 000 rows means two or three collisions in the window — a tool that wrote or
     checkpointed a few times, not a process hammering the file (that would have cost
     hundreds of rows). Nothing on the machine records who that was: `lsof` answers only
     "now" (one Control, nothing else), and these apps write nothing to the unified log.
   **Verdict: the identity of the opener is not established and is probably no longer
   recoverable on this machine.** Going forward two things replace the guesswork: the
   `WARN … commit … failed` line carries the SQLite error (`Error code: 5, message: database
   is locked` vs. a `FULL`/`IOERR`) and the `DROPPED` line names the first row hashes — and
   both persist in **`objects.db.log` beside the store** (a `FileDiagnosticSink`; codes
   `store_commit_retry` / `store_batch_dropped`, 2026-09-14), because under launchd the
   daemon's stdout goes nowhere and a stdout-only announcement of a drop is itself a silent
   drop; and the next re-seed can be *watched* — `sudo fs_usage -w -f filesys | grep
   objects.db` names every process that opens or writes the file while it runs, which is the
   observation this incident never had.
4. **Enumerate, don't sample.** `scripts/store-relation-audit.py <frozen copy>` lists every list
   row with a missing member, its owner (type + path), and a count by owner prefix. The
   WhatsApp Images row has been walked by hand (two members, §1.1); the other 44 570 rows have
   not. *Status — the collateral is sized:* per-type counts against the phone, both caught up
   (a 5-block drift): `hlc` −8, `tenshu/file` −2, `dataId/string` −1, `health/workout` −1,
   `logging/error-event` −1 — **13 synced rows short, 8 of them `hlc`** (device-local types
   excluded). Not all 13 need be the same failures' — `workout` and `error-event` are
   device-generated and the drift is real — but the two file rows were not the whole of it,
   which is what several dropped batches predict.
5. **The wire-id route, if a keyed-era row ever needs the same check.** `SyncMetadata`,
   `ResolvedDataId` and `LocalWriteMetadata` are keyed by the entity's **wire id** —
   `HMAC(K_entity, "tenshu/file_<path>")` — which is why a walk for the file's local hash finds
   nothing and why the path-string search above worked only through the `DataId` *child*
   rows. For blocks after seq 20 232 the relay target is the keyed id too. The personal realm's
   keys derive from the recovery phrase alone, so `WireIdsForPathsTool`
   (`sync-service-proxy/src/jvmTest`) prints each path's wire id and the four sidecar row
   hashes offline; `scripts/store-relation-audit.py <copy> --has <hashes>` says which exist;
   `SELECT seq, hlc_physical, body_dropped FROM sync_block WHERE target_entity_id_hash =
   '<wireId>' ORDER BY seq` (and `delete_block`) on a relay snapshot gives the ordering. Not
   needed for these three files — their blocks are legacy-addressed.
6. **Repair — now unblocked.** Everything this store could say has been read (7.1, and (3)
   above). The reader-side skip tolerates it on master, and `Database.removeDanglingArrayMembers`
   can now express the fix for a `Folder.FILES` row; a one-shot over folder lists is a small
   follow-up — or simply a desktop resync, since both rows are on the phone and the relay.

---

## 8. Decisions recorded here

- **D1.** A failed commit is retried, then dropped loudly — never dropped silently
  (`SqliteObjectStore` class comment). A store must never lose a write without saying so.
- **D2.** The writer connection waits for SQLite's write lock (`WRITER_BUSY_TIMEOUT_MS`) like the
  reader already did.
- **D2b.** A barrier answers for every write enqueued before it, including one lost in an earlier
  batch — the drop is carried forward, not swallowed because the batch boundary fell elsewhere.
  Consumed by the first barrier after it, never latched: one lost batch must not read as a dead store.
- **D3.** A second store on one path is a reported condition, not a refusal — the JVM store was
  never built for it, and the report is what makes the next such incident diagnosable.
- **D4.** `Folder.FILES` has one storage row for both its write paths; a future reader or writer
  must not reason about "the nested list" and "the relation" as two things (§2).

## 9. Files, and how they were verified

- `core-common/src/jvmMain/…/db/SqliteObjectStore.kt` — busy timeout, retry, loud drop,
  advisory lock.
- `core-common/src/desktopTest/…/db/SqliteObjectStoreCommitFailureTest.kt` — eight tests: the
  three commit-failure behaviours, the trace beside the store carrying every retry and the drop,
  the barrier that arrives *after* the batch it waited on was dropped, two stores writing one
  file concurrently (the production trigger, §7.2 (1)), the advisory lock, and the
  crash-loses-a-suffix half of §6.1.
- `sync-service-proxy/src/commonTest/…/FolderFilesDanglingMemberTest.kt` — eight tests: §4–§5,
  plus the writer-order half of §6.1 over real ingest, a local save and the maintainer.
- `sync-service-proxy/src/jvmTest/…/WireIdsForPathsTool.kt` — the wire-id diagnostic for
  §7.2 (3); inert without its environment variables.
- `core/src/commonTest/…/entity/DataIdHashVectorsTest.kt` — the four hashes, pinned.
- `scripts/store-relation-audit.py` — the whole-store enumeration, hash helpers (`--file-hash`,
  `--files-array-hash`, `--string-id`), a presence check (`--has`), self-tested against the
  same four vectors.
- `scripts/DataIdHashSearch.java` — names a WhatsApp-patterned member from its hash.

Verification (2026-09-13, Linux container, JDK 17/21/25 per `docs/how-to/claude-web-environment.md`):

| run | result |
|---|---|
| the 18 new tests (7 store, 8 ingest, 3 hash-vector tests over 4 vectors) + the 41 other `SqliteObjectStore*` tests | green |
| full `:core-common:desktopTest` / `:sync-service-proxy:jvmTest` / `:core:jvmTest` / `:file-service-proxy:jvmTest` | 834 / 800 / 58 / 405 tests, 0 failures |
| **negative**: the store fix neutralised through its seams (`writerBusyTimeoutMs = 0`, no retries) | `a commit that fails its first attempts is retried and lands in order` **fails** with `SQLiteException: Error code: 5, message: database is locked` — the old behaviour, reproduced |
| **negative**: `FolderFilesDanglingMemberTest` without `initFileService()` | all of them still pass — which is how §3's note on `ClientRegistry` was found; the delete tests are exercising the maintainer the context installs, not one the test wired |
| `scripts/store-relation-audit.py` against the `objects.db` files the store tests leave in `/tmp` | reports exactly **one** dangling member in each store the "dropped loudly" test produced (its deliberate residual: list `100` → member `1`) and none elsewhere — the codec and the walk verified on real `SqliteObjectStore` output, not on a hand-built fixture |

⚠ Not verified in that run: iOS and JS (K/N cannot cross-compile on Linux; the store change is
`jvmMain`-only and touches neither), and the Android smoke gate — the change is in the JVM
source set Android shares, uses `java.io` rather than `java.nio.file` for that reason
(`minSdk 24`), and House Rule 8 asks for `scripts/android-smoke.sh` before merge.

Re-verification after merging master (2026-09-22, macOS, branch at `b775a2ad7` + this work):

| run | result |
|---|---|
| `scripts/run-gate.sh` — 112 test tasks executed, 1 686 test classes, **10 176 tests, 0 failures, 0 errors, 2 skipped** | green |
| the new tests, all with fresh XML stamps in that run | `SqliteObjectStoreCommitFailureTest` 8/8, `FolderFilesDanglingMemberTest` 8/8, `DataIdHashVectorsTest` 3/3 |
| JS lane (`compileTestKotlinJs`) and iOS lane (`compileTestKotlinIosSimulatorArm64`) | both green — the two the 2026-09-13 run could not reach |
| ⚠ **a real intermittent, found by re-running** — the trace test failed ~1 run in several: the writer had drained the Put as its own batch, so the barrier arrived in a batch of its own, which was *also* retried and dropped. Fixed in the store (the two bullets added to §6.1) and pinned by the eighth test | green 8 runs in a row after the fix |
| **mutation-checked**: each half of that fix deleted in turn | dropping the carry-forward → the new test fails "the barrier must answer for the batch it waited on"; dropping the barrier-only short-circuit → the new test *and* the trace test go red |

⚠ `scripts/android-smoke.sh` still owed (House Rule 8).
