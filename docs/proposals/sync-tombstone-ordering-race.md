# Sync tombstone-ordering race — local deletes can be resurrected by a re-pulled create

**Status:** **FIXED in two halves.** §0 (2026-06-25, branch `fix/sync-tombstone-ordering-race`) closed
the **sequential** case: a local delete left no entry in the short-circuit index, so a later re-pulled
create resurrected the row. §0.1 (2026-07-26) closes the **concurrent** case found chasing the
`DeleteAndReuploadFolderSystemTest` flake: with the index now written, the delete
and the ingest still each read-then-wrote it unlocked, so an ingest that overlapped the delete
resurrected the row anyway. The GR-7 call-site guard in `pollPendingGrants` is kept (defence in depth) but is subsumed by
the root fix. Sections 1–7 below are the original investigation handover, retained for context.

---

## 0. Resolution (2026-06-25)

**Framing correction.** The original handover (§1) says local deletes *never* reach
`TombstoneRegistry.record`. That is only half true. There are two local-delete paths:

1. **Explicit tombstone emit** (`TombstoneEmitter.emit`, used by `DeleteFileImplementation` for the
   delete's *top-level* target, and by `RevokeDevice`). `SyncObserver.emit` **does** call
   `tombstoneRegistry.record(block)` — these were never vulnerable.
2. **Plain `executionContext.deleteData(...)` with no emit** — fires `onDataDeleted` only. This is
   the genuinely-unrecorded path, and it is exactly where the two reproduced symptoms live:
   - `SharedRealmGrantManager.deletePendingGrant` (the GR-7 `PendingGrant` resurrection), and
   - `DeleteFileImplementation.deleteRecursively`'s **cascade child** deletes (one tombstone is
     emitted for the operation's top-level target; children are deleted local-only — see that file's
     own comment "Recursive child deletions stay local-only").

So the bug is: **a `deleteData` that doesn't emit a relay tombstone leaves no entry in the
short-circuit index, so a re-pulled own-create (relay echo) resurrects the row.**

**Fix** (`:sync-service-proxy`, proxy-local, no wire/relay/protocol change):

- `TombstoneRegistry` gains a second in-memory map `localTombstones` — kept **separate** from the
  real `tombstones` map so it never feeds `typeCounts` (the §5 epoch watch-out: a local delete is
  not a revocation and must not move `currentRealmEpoch`). In-memory only, like `idHashToDataId`.
- `onDataDeleted` now stamps `localTombstones[idHash]` for **local-origin** deletes (skips
  `SYNC_INGEST`, which `processDeleteBlock` already recorded authoritatively). The recorded HLC is
  `max(currentHlc(), entity's SyncMetadata.hlc)` — `current()` dominates locally-stamped /
  personal-ingested creates; the metadata max additionally covers a shared-realm-ingested entity
  whose create advanced the realm's own clock, and pins the stamp to *exactly* the create's HLC so a
  legitimate later recreate (HLC > delete) is still accepted.
- `tombstoneHlcFor` returns the max of the real and local maps, so `resolveConflictAndSave`'s
  existing short-circuit drops the re-pulled create unchanged.
- `currentHlc` is injected into `TombstoneRegistry` (`{ syncManager.hlcManager.current() }` in
  `initSyncService`; `null` default keeps the direct-construction tests compiling).

**Validation.** `SyncProcessorTombstoneTest.localDelete_isNotResurrected_byRepulledCreate` —
create-by-ingest → local `deleteData` → re-ingest the same (own-device-id) create → assert it stays
deleted. RED before the fix, GREEN after. Full `:sync-service-proxy:jvmTest` and
`:system-test-utils:test` suites green (including the three §3 tests). Because the fix targets the
mechanism (not the GR call site), the GR-7 `pollPendingGrants` guard is now redundant but retained.

**House-rule #2:** no threat-model amendment needed — `localTombstones` is in-memory, proxy-local,
and deliberately excluded from `typeCounts`, so `currentRealmEpoch` semantics are unchanged.

**Not independently confirmed:** the §3 `FileSyncSettingsRelationSyncSystemTest` `ClassCastException`
was flagged as *possibly* a separate "op returns Failure before pairing converged" issue; it passed
here but was not stress-reproduced, so if it recurs treat it as its own bug.

---

## 0.1 Second half — the same symptom, concurrently (2026-07-26)

§0's fix is correct and still necessary; it is just not sufficient, because **both sides of the
guard are check-then-act and neither was atomic**:

- a local delete is *remove the row*, then (via the observer fanout, which
  `ExecutionContext.deleteData` dispatched **after** releasing the DB lock) *stamp the
  short-circuit tombstone*;
- an ingest is *read the tombstone index*, then *save*.

They run on different coroutines — the user's request vs the `SyncScheduler` tick — and the relay
echoes a device its own pushed blocks, so a create block for the very row being deleted is routinely
in flight at that moment. Interleaved, §0's index is invisible:

```
onDataDeleted(/docs/report.txt …)                       ← delete op mid-fanout
ingest /docs  hlc=…037  tomb=null  shortCircuit=false   ← gate passes: nothing stamped yet
localDelete /docs recorded=…207                         ← delete finishes, stamps
saveData /docs ← SyncProcessor.resolveConflictAndSave   ← ingest saves anyway; the row is back
createFolder /docs existing=FolderDto → AlreadyExistsError
```

That is the §3 `DeleteAndReuploadFolderSystemTest` row, unresolved by §0 and still ~1.5 % per
delete→re-create cycle. It is **not** load- or suite-sensitive as the §4 recipe assumed: it
reproduces in isolation at the harness's ordinary 100 ms polling cadence, and disappears entirely
when the scheduler is stopped (`pollingIntervalMs = 1 h`) — the load in §4 mattered only because it
widened the window.

**Fix** (still proxy-local, no wire/relay/protocol change — house rule #2 unaffected for the same
reasons §0 gives): make each half one transaction over the existing reentrant DB lock.

- `ExecutionContext.deleteData` wraps the row removal **and** its observer fanout in
  `database.transaction { }`. Deliberately asymmetric with `saveData`, which still dispatches
  unlocked: a delete's observers *complete* the delete (the tombstone stamp is part of it), whereas a
  save's merely react to it.
- `SyncProcessor.resolveConflictAndSave` wraps gate-through-save in `database.transaction { }`.

Both are needed. With only the ingest atomic, the delete can still slip its row-removal in before the
gate and its stamp in after the save; with only the delete atomic, the ingest can read the gate
before the delete starts and save after it finishes. Together the two possible orders are ingest
entirely before the delete (its save is then removed by it) and ingest entirely after (the gate
fires).

**Validation.** New `LocalDeleteIngestRaceTest` (`:sync-service-proxy` **jvmTest** — forcing the
interleaving needs a real second thread; it also asks `TestProxy` for the production
`ReentrantLockDatabaseLock`, since the default no-op lock cannot express what is being serialised).
It drives an ingest from inside the delete's fanout and asserts the row stays deleted; RED with
either transaction removed, GREEN with both. `DeleteAndReuploadFolderSystemTest`'s scenario went
0/250 failures on a looping probe (≈4 expected at the measured pre-fix rate).

**Rejected alternative — drop self-authored blocks at ingest** (the relay echo is, after all, work a
device does on its own writes). It would remove this race class outright, but the echo is
load-bearing: `reconcileStaleSyncCursor`'s desync self-heal resets the cursor to 0 over an emptied
store and rebuilds it precisely by re-ingesting the device's *own* history. Skipping own blocks would
silently break restore-after-wipe.

**Not closed by this**: `SyncProcessor.attachPropertyAndSave` (property-targeted blocks) still has no
tombstone gate at all. A property block for a deleted entity is dropped only incidentally, because
the same `onDataDeleted` also drops the `idHash → DataId` reverse-index entry it needs to resolve its
target. That is an accident of ordering, not a check.

---

## 1. One-paragraph summary

A locally-deleted Syncable row can be **resurrected** when the device pulls back a create block it
had previously pushed for the same entity. Concretely: `TombstoneRegistry.onDataDeleted` (the hook
fired on a **local** `deleteData`) does **not** record the entity's tombstone HLC in the registry's
short-circuit index — only *ingested* (remote) tombstones, via `record(DeleteBlock)` from
`SyncProcessor.processDeleteBlock`, populate it. So when a previously-pushed **create** block for the
same entity is pulled back and runs through `SyncProcessor.resolveConflictAndSave`, the tombstone
short-circuit (`tombstoneRegistry.tombstoneHlcFor(idHash)`) returns `null`, the create is applied,
and the locally-deleted row reappears. It is a create→delete ordering race: under load the re-pulled
create lands after the local delete but the local delete left no short-circuit record to suppress it.

---

## 2. Where the bug lives (code pointers)

All in `:sync-service-proxy`:

- **`TombstoneRegistry.onDataDeleted(dataId, origin)`** — fired on every local delete. Today it only
  does `idHashToDataId.remove(...)`. **It never writes `tombstones[idHash]`** (the short-circuit
  index read by `tombstoneHlcFor`). ← *this is the gap.*
- **`TombstoneRegistry.record(block: DeleteBlock)`** — the only writer of `tombstones[idHash]`.
  Called exclusively from `SyncProcessor.processDeleteBlock` (i.e. for tombstones **ingested from the
  relay**). Local deletes never reach it.
- **`SyncProcessor.resolveConflictAndSave(incomingData, block)`** — the create-apply path. Its
  tombstone short-circuit:
  ```
  val tombstoneHlc = tombstoneRegistry.tombstoneHlcFor(dataIdHash.toString())
  if (tombstoneHlc != null && tombstoneHlc.compare(block.hlc) >= 0) return
  ```
  Correct in principle (a create at HLC ≤ a known tombstone is dropped), but `tombstoneHlcFor`
  returns `null` for a **locally**-deleted entity, so the create is applied.
- **`SyncProcessor.processDeleteBlock(block)`** — ingest path: `hlcManager().update`, `record`,
  then `deleteData(..., origin = WriteOrigin.SYNC_INGEST)`. This is the symmetric counterpart a local
  delete is missing.

Existing test harness to extend: **`SyncProcessorTombstoneTest`** (`:sync-service-proxy` commonTest).

---

## 3. Evidence — three observed symptoms (all the same root)

Surfaced while investigating an intermittent `SharedRealmKrRotationSystemTest` failure. Under the
full `:system-test-utils` suite's CPU load, *different* tests flake on different runs (~5%/full-suite
run on a lightly-loaded machine; higher when the box is busy):

| Test | Symptom | Link to this root |
|------|---------|-------------------|
| `SharedRealmKrRotationSystemTest:130` | GR-7 re-key surfaced a phantom *Shared with me* card instead of auto-applying | A `PendingGrant` (synced row) quarantined pre-accept, deleted by `accept`, then **resurrected**; the stale card made `pollPendingGrants` skip the mailbox pickup so the re-key was never collected. **Symptom patched by the GR-7 call-site guard** (`pollPendingGrants` now drops a pending whose realm we already joined). The resurrection itself is unfixed. |
| `DeleteAndReuploadFolderSystemTest` (both cases) | `AlreadyExistsError` re-uploading at a just-deleted path | Single-client. `deleteFile` tombstones children locally; a re-pulled child-create resurrects the row, so the existence check in `UploadFileImplementation` (see its `:79` "tombstone retention window races" note) sees a ghost. |
| `FileSyncSettingsRelationSyncSystemTest:128` | `ClassCastException` (`getPendingPairingRequests` returned `Failure`) | Less certain this is the *same* root — may be a separate "op returns Failure before pairing converged" issue. Verify during the fix work; don't assume. |

The GR symptom was the one reproduced and root-caused in depth; the other two are strong
circumstantial matches (same create→delete shape, same load sensitivity).

---

## 4. How to reproduce (warm-start recipe)

The race is timing-dependent and **does not** reproduce in isolation (19 green runs) — it needs
sustained CPU load. The fastest confirmed repro is an **instrumented concentrated loop**, not the
full suite:

1. **Instrument** `SharedRealmGrantManager.pollPendingGrants` (or, better for the root fix,
   `TombstoneRegistry`/`SyncProcessor.resolveConflictAndSave`) with `println` on the decision points
   (pickup / `isExistingMember` / quarantine / short-circuit-or-not).
2. Add a **temporary looping probe test** that runs the rotation scenario ~40× per JVM with 14
   daemon busy-loop threads (`Thread { while (!interrupted) {} }`) burning CPU, and `error()`s on the
   first quarantine — see the exact harness used in this session's GR-7 re-key investigation (it lived as
   `SharedRealmKrRotationSystemTest.probe_rotationRace`, reverted before merge; reconstruct from the
   scenario in `removeMember_rotatesKr_...`). It tripped reliably by iteration 8–22.
3. The decisive log from this session:
   ```
   [PROBE] accept done: hadPending=true pendingAfterDelete=false   ← accept deletes it; gone locally
   [PROBE] poll skip: existing pending from contact=…              ← but it's BACK at the next poll
   [PROBE] *** REPRO *** memberAtPoll=true pendingCount=1
   ```
   `pendingAfterDelete=false` then a later non-null read = the synced create was re-applied after the
   local delete.

For a **root-level** repro independent of GR, prefer driving `SyncProcessor` directly in a
`commonTest` (à la `SyncProcessorTombstoneTest`): push a create, locally `deleteData` it, then feed
the same create block back through `processSyncBlock` and assert it stays deleted. That isolates the
mechanism with no relay/load needed and would become the regression test.

---

## 5. Candidate fix directions

**Primary candidate — record local tombstones in the short-circuit index.** Make a local-origin
delete populate `tombstones[idHash]` with the delete's HLC, symmetric with ingest. The local delete
already emits a `DeleteBlock` (via `TombstoneEmitter`) carrying an HLC; route that same block through
`tombstoneRegistry.record(...)` (or a local-only variant) so `tombstoneHlcFor` knows about it. Then
`resolveConflictAndSave` will short-circuit the re-pulled create (create HLC < delete HLC).

**⚠ Watch-out — `record()` bumps `typeCounts`, which feeds `currentRealmEpoch`.**
`TombstoneRegistry.record` increments `typeCounts[type]` on first observation, and
`SyncManager.currentRealmEpoch` derives the realm epoch from `tombstoneCountForType(Device.ID)`.
There is a **recovery-race** interaction documented at `SyncProcessor.resolveConflictAndSave`'s
"freshly-recovered receiver has `currentRealmEpoch == 0` until tombstones land" comment and in
the threat model's **T-K-02** recovery race ([`docs/sync/threat-model.md`](../sync/threat-model.md), graduated from the old `sync-device-management.md` note). If local deletes start counting toward the
epoch, epoch derivation changes. So the fix probably wants to record the **short-circuit HLC**
(`tombstones[idHash]`) **without** double-counting `typeCounts` for an entity this device deleted
locally (it would already be counted when the same tombstone is later ingested, or never, depending
on the path). Decide this deliberately and cover it in `SyncProcessorTombstoneTest`.

**Secondary considerations:**
- HLC monotonicity: confirm the re-pulled create truly carries an HLC *below* the local delete's
  (it should — the create happened first). If any path re-stamps the create with a fresh HLC on
  re-push, the short-circuit (`>=`) wouldn't save us and the real bug is elsewhere (HLC assignment).
  Verify before committing to the registry fix.
- The in-memory `tombstones`/`idHashToDataId` maps are explicitly noted as not persisted across
  restart (`TombstoneRegistry` class doc, "A future iteration may persist the side-cache"); a local
  tombstone recorded only in memory is lost on restart — usually fine (the row is gone from the DB
  too) but think through the re-pull-after-restart window.

**House-rule #2 check:** recording a local tombstone in the *local* registry is purely a
proxy-side/local change — it does not alter what is pushed, the relay data shape, or the wire
protocol. So a threat-model amendment is likely **not** required — **unless** the chosen approach
changes `currentRealmEpoch` semantics (which the relay/recovery flow observes). Re-confirm once the
approach is locked.

---

## 6. Suggested first steps for the next session

1. Read `TombstoneRegistry` + `SyncProcessor` (`processSyncBlock` → `resolveConflictAndSave`,
   `processDeleteBlock`) and the `TombstoneEmitter` local-delete emit path end to end.
2. Write a failing `commonTest` in `SyncProcessorTombstoneTest`: create → local `deleteData` →
   re-ingest the same create block → assert it stays deleted. (Should fail today.)
3. Implement the registry fix (record local tombstone HLC), resolving the `typeCounts`/epoch
   question explicitly.
4. Make the new unit test pass; then drop the temporary GR probe back in and confirm the rotation
   scenario no longer resurrects even **without** the GR-7 call-site guard (i.e. the root fix
   subsumes the symptom patch — keep both, but prove the root fix stands alone).
5. Re-run the full `:system-test-utils` suite repeatedly under load; check `DeleteAndReupload` and
   `FileSyncSettings` flake rates drop. If `FileSyncSettings` persists, it's a separate issue (§3).
6. If `currentRealmEpoch` semantics move, amend `docs/sync/threat-model.md` in the same PR
   (house rule #2) and update the **T-K-02** recovery-race note there — `sync-device-management.md`
   graduated into the threat model + [ADR 0007](../adr/0007-sync-device-management.md).

---

## 7. References

- The GR-7 re-key symptom fix (`pollPendingGrants` call-site guard) + as-built note in
  [`shared-realm-membership-grant.md`](shared-realm-membership-grant.md) §14.3.
- `UploadFileImplementation.kt` (`:file-service-proxy`) — the `:79` "tombstone retention window
  races" comment is the same race seen from the file plane.
- [`docs/sync/design.md`](../sync/design.md) §2.6 + [ADR 0006](../adr/0006-sync-tombstones.md), and
  §2.2 + [ADR 0007](../adr/0007-sync-device-management.md) (where `sync-device-management.md` went) — tombstone
  design + the recovery-race the epoch interacts with.
- `SyncProcessorTombstoneTest` (`:sync-service-proxy` commonTest) — extend for the regression test.
