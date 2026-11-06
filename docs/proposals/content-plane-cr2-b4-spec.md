# CR-2 B4 — freed content: refusal, REVIVE, retire, and adds before removes

**Status:** v0.1 (2026-10-02), decided by the maintainer the same day: spec first, then a relay half (B4r)
and a client half (B4c) built in parallel. **Both built 2026-10-02**: B4r on `claude/cr2-b4r-relay` (as-built §5),
B4c on `claude/cr2-b4c-client` (as-built §6), merged with the end-to-end system test of §2.5.

**Builds on, and does not re-argue:** `content-plane-cr2-quorumless.md` §10.2 (G2/G3 and REVIVE), §10.12
Q2/Q3 (the content states, the describe), §10.14 (T = 24 h; adds before removes; G3 as the atomic check),
§9 Q2 (a freed row with no bytes is **retired locally with a notice**, decided 2026-10-02); and the B3 wire
spec (`content-plane-cr2-b3-wire-spec.md`, v0.2 and its §10 as-built), whose v4 bodies already reserve
everything B4 puts on the wire.

**What B4 does not do:** free anything in production. Nothing marks content freed until B5's sweep; B4
builds the state, the logical free as a callable transaction (tests and B5 call it), the refusal and the
way out. The quota exclusion of UNREFERENCED bytes is B5's (§10.14).

---

## 0. Wire summary (no protocol bump)

`RELAY_PROTOCOL_VERSION` stays **4**. B3 reserved both slots:
- `EdgedPush.revive: List<ReviveEntry(contentId, refs)>`, which a B3 relay refuses when non-empty;
- **409** on `/sync/push`, reserved for `content_freed`.

New in B4, all JSON response bodies:

```json
// 409 on POST /sync/push and POST /sync/content/declare
{ "error": "content_freed", "contentIds": ["<id>", "..."] }
// 409 on POST /sync/push when a revive did not verify
{ "error": "revive_incomplete", "contentIds": ["<id>", "..."] }
```

- `contentIds` lists **every** offending id in the request (not only the first), deduplicated, in request
  order. A client must not depend on order.
- No other route changes. `/sync/push-tombstone`, `/sync/content/reap` and `/reclaim` never answer
  `content_freed`.

## 1. Relay (B4r)

### 1.1 Storage: migration v23

- `content_freed(owner_id TEXT, content_id TEXT, freed_at INTEGER, PRIMARY KEY(owner_id, content_id))`.
  A row means FREED (§10.12 Q2). Keyed by owner, like every content table.
- No `put_seq` column. ⭐ **"PUT since the free" is queue membership**, as B0 already settled
  (`ContentReclaimJob` KDoc): the logical free moves a content's refs into `content_free_queue`, and the
  store's PUT and GET take a ref out of the queue under the DB lock before touching bytes. So a ref that is
  **present in `content_blob` and not in `content_free_queue`** was PUT (or read) after the free, or was
  never part of the freed content (a fresh chunk under a new CEK).

### 1.2 The logical free (`ContentFreeing.free(owner, contentId)`)

One `db.inTransaction`, under the relay's single DB lock:
1. Precondition: the content has **zero live edges** (`holdersOf(owner, contentId)` empty, `none`
   registers excluded). If not, return `NOT_FREED` and change nothing.
2. Move every ref of `content_ref(owner, contentId)` into `content_free_queue` (`INSERT OR IGNORE`, with
   `byte_len` from `content_blob`), then delete those `content_ref` rows.
3. Delete the content's `content_reference` rows (handles; B6 retires them anyway).
4. Insert `content_freed(owner, contentId, now)`. ⚠ **Even when step 2 moved nothing** (PENDING content
   whose last edge went before its upload landed, §10.12 Q2), so the late describe is refused.

- ⚠ Steps 2–3 are what let `ContentReclaimJob.drain` delete the bytes: it spares any ref that is described
  and the derived manifest of any content with an edge, ref or handle. After the free there is none of
  those, so the drain's existing checks pass without change.
- No production caller in B4. `ContentReclaimJob`'s operator routes are not wired to it.
- ⚠ `SqliteContentRefStore.declareRefs` commits on its own and must not run inside `inTransaction`
  (`RelayDatabase.kt:50`). B4 adds a transactional variant and uses it in 1.2, 1.3 and 1.4.

### 1.3 G3 and REVIVE on `POST /sync/push`

Inside the existing `contentEdges.atomically { pushBlock; writeEdges }` transaction, **before** the block
is appended:

1. Let `E` = the push's edges with a non-null `contentId`.
2. **Regenerable edges** (`regenerable = true`) naming a FREED id: delete that `content_freed` row, so the
   rung leaves FREED (§10.11 Codex P2 fix). Its old refs stay queued; a later mint's PUT dequeues the
   derived manifest, and its describe is then accepted. Never a 409.
3. **Non-regenerable edges** naming a FREED id: each must be covered by a `revive` entry with the same
   `contentId`. Any that is not → **409 `content_freed`** listing them. Nothing is written.
4. **Each `revive` entry** must name a `contentId` that some non-regenerable edge of this push names
   (else **400** `"revive entry names no edge of this push"`), and its refs must pass the describe's
   validation (pattern, non-empty, `MAX_REFS_PER_EDGE`, and the request's `MAX_TOTAL_REFS_PER_REQUEST`).
   Then, per entry:
   - **every** ref is present in `content_blob(owner, ref)` and absent from `content_free_queue(owner,
     ref)`. Otherwise → **409 `revive_incomplete`** listing the entries' ids. Nothing is written.
   - Replace `content_ref(owner, contentId)` with the entry's refs (the transactional variant), and
     delete `content_freed(owner, contentId)` if present.
   - ⚠ A revive for an id that is **not** FREED is accepted and acts as a describe. That makes a client
     retry after a lost 200 idempotent.
5. Append the block and write the edges, as B3c does.

Either all of 2–5 lands or none of it does (§10.2: "the revive rides on the retried push itself, in the
same transaction").

### 1.4 `POST /sync/content/declare` (describe)

- Every entry's freed-check and its `content_ref` write run in **one** transaction for the whole request
  (today each entry is its own lock section, ContentRouting.kt:504-514, which would race a free).
- If any entry names a FREED id → **409 `content_freed`** listing them, and **nothing** in the request is
  written. A describe is never the way out of FREED; only a revive on a push is (§10.12 Q3).

### 1.5 The in-memory twin

The in-memory stores (used when the relay runs without `databaseUrl`, and by tests) get the same freed set
and the same 1.3/1.4 checks. They have no free queue, so "present and not queued" reduces to "present".

### 1.6 Tests owed (relay)

- G3: a push naming a freed non-regenerable id → 409 listing it, block **not** in the mailbox, no edge
  written; two freed ids → both listed.
- Regenerable: a push naming a freed rung id → 200, `content_freed` row gone, refs still queued.
- REVIVE: re-PUT chunks (new refs) and the manifest, push with the revive → 200, `content_ref` restored,
  freed row gone, block appended, edges written, in one transaction (fault-inject between steps).
- `revive_incomplete`: one ref missing, or one ref still queued (manifest not re-PUT) → 409, nothing
  written.
- A revive naming no edge → 400. A revive for a non-freed id → 200, acts as a describe.
- Describe of a freed id → 409, and a mixed request writes nothing.
- `free()`: refuses with a live edge; marks PENDING content freed with no refs; after a free,
  `ContentReclaimJob.drain` deletes the queued bytes (the existing checks no longer spare them).
- Concurrency: a push and a `free()` racing on one content serialise (whichever commits second sees the
  other).

## 2. Client (B4c)

### 2.1 A typed refusal

- `SyncRelay.pushBlock` gains a typed failure for 409: `ContentFreed(contentIds)` or
  `ReviveIncomplete(contentIds)`, parsed from the JSON body. ⚠ Not through `statusWithReason()`, which
  caps the body at 200 characters (RestSyncRelay.kt:1183).
- `RestContentBlobStore.describeContent` maps its 409 the same way.

### 2.2 Resolving a refused block

Applies at **every** push site that sends edges: `SyncScheduler.drainPendingBlocks`,
`drainExemptBlocks`, and `PendingBlocksFlusher` (`drainSharedRealms` never meets it: `SharedFile`
emits no edges, §10.12 Q6).

For a block refused with `content_freed` naming ids `F` (all non-regenerable by construction):

1. **Repairable?** A freed id is repairable when the block's edge for it is the row's **Primary**
   content (B3a's declaration) and this device holds the plaintext. Repairability is extended from
   `ContentRepair.REPAIRABLE_PROPERTY_IDS` (File.Data, SharedFile) to **every Primary declaration with a
   local file**, which adds `Workout.Streams` (`workoutStreamsPath`).
2. **All of `F` repairable → re-upload for revive.**
   - A new emit entry point re-uploads the content (fresh CEK, new chunks, manifest at the derived ref)
     and **returns its full ref list** (chunks + manifest) **without sending the describe**: the describe
     would be refused, and the revive replaces it. Today `emitFileContentNow` keeps the refs local
     (SyncObserver.kt:2776); this returns them.
   - Persist `PendingBlockRevive(blockId → ReviveEntry list)`, a new device-local composite, before
     retrying, so a crash between upload and push does not re-upload (each re-upload mints a fresh CEK,
     so a second one orphans the first's chunks: a leak, by G4).
   - Retry the push with `revive` set. On 200 the block retires as usual and the sidecar goes with it.
   - On `revive_incomplete`: drop the sidecar and re-upload once more on the next tick.
   - ⚠ If the upload gate is closed (metered, 507, locked realm), the block stays queued and is retried on
     a later tick. It is not retired: the bytes still exist on this device.
3. **Any of `F` not repairable → retire the row** (§9 Q2):
   - delete the local row with `WriteOrigin.SYNC_INGEST`, so no outbound tombstone is emitted (the
     refused block never reached the log, so no peer holds the row; `SyncProcessor.processDeleteBlock`
     is the precedent);
   - drop **every** queued block for that holder whose edges name an id in `F`, with their sidecars;
   - raise the P3 notice (2.4).
4. A describe refused with `content_freed` after an ordinary upload means content freed while PENDING
   (§10.12 Q2): drop the describe and its upload journal entry. The row's push meets 2.2 on its own.

### 2.3 Adds before removes (§10.14)

**The rule:** a removal of content C is not pushed while any queued, unacknowledged block has an edge
naming C. A removal is a tombstone or cascade naming a holder that referenced C, a reap of such a holder,
or a block whose edge for C's property goes to `none`.

What the client needs to know, which it does not record today (no component knows which content a removed
holder named):
- **Removed contents per holder.** `ExecutionContext.deleteData` gains a pre-delete observer hook, inside
  the existing transaction and before the row goes, mirroring the pre-write lane decision saves already
  have (`observerRegistry.laneFor`). `SyncObserver` uses it to persist `RemovedContents(holder wire name
  → content ids)` from `edgesOf(row)`, a new device-local composite. Cascaded and reaped descendants pass
  through the same hook, since each is deleted by its own `deleteData`.
- **Cleared contents per block.** B3d's Q7 staging already reads the row a save replaces. It records in
  the block's `PendingBlockEdges` sidecar the content ids the save **clears** (the replaced row's ids that
  the new edges do not name).

How the drain applies it, per tick:
- Blocks: in queue order, except that a block whose cleared ids intersect the adds of a **later** queued
  block for a **different** holder is pushed after that block. Blocks for the same holder never reorder.
- Tombstones and reaps: one whose holders' `RemovedContents` intersect the adds of any queued,
  unacknowledged block is held to a later tick. "Unacknowledged" means not yet answered 200; a block that
  is refused and still queued keeps holding.
- A removal held because its add is waiting on a closed upload gate stays held. That is deliberate: the
  alternative frees content the add still needs. It is visible as pending sync, not lost.
- `RemovedContents` entries are dropped with the tombstone or reap that consumes them.

With 2.3 and 1.3 in place, T drops from 24 h to minutes (§10.14). That change is a relay config value and
lands with B5's arming, not here.

### 2.4 The P3 notice

- A new `FileNotification` subtype, `ChangeLostNotification(fileName, path)`:
  "`<fileName>` was deleted on another device while your change was waiting." It goes through
  `:notification-service`'s `SendNotification`, so it is a **synced** row. That is deliberate: the user
  sees it on whichever device they look at.
- Control's ambient feed renders it (`NotificationMapper.mapToEventOrNull`). Drive also shows it once, as
  a dismissable message on its files screen. An OS alert goes through `NotificationPresenter` where one
  is bound (Android, desktop; iOS Control binds a no-op).
- ⚠ HR 9: a new member of a sealed constant family fails `SchemaShapeBaselineEnforcementTest`. Bump
  file-service's version with its `SchemaMigration`, as the test names it, and regenerate the baseline.

### 2.5 Tests owed (client)

- A 409 body with several ids parses into `ContentFreed` (no truncation).
- Repairable: a refused File push re-uploads, persists `PendingBlockRevive`, retries with the revive, and
  retires on 200. A crash between upload and retry does not re-upload.
- Workout streams: the same, through `Workout.Streams`.
- Not repairable: the row is deleted with no outbound tombstone, every queued block for the holder naming
  the id is dropped, and one `ChangeLostNotification` is sent.
- Adds before removes: a move whose tombstone would drain first waits for the new path's block 200;
  a block that clears C is pushed after a later block (other holder) that adds C; same-holder order is
  kept; a removal behind a closed upload gate stays held.
- End to end (system test, B4r + B4c; ⚠ written **after the merge**, by whoever merges the halves, since it
  needs both): two devices, one deletes a folder, the other's queued move lands
  after a test-driven `free()` → the mover repairs and revives (bytes intact), or, with its plaintext
  removed, retires the row and both devices see the notice. This is the delete-vs-move system test (bytes included), rewritten for B
  (§10.7), owed before B5.

## 3. Threat model (House Rule 2)

The amendment records:
- **New relay state:** `content_freed(owner, content_id)`. The relay already holds content ids and refs,
  so B4 adds no new linkage.
- **The refusal reveals only** that an id the client itself named is freed.
- **A revive cannot resurrect another owner's bytes**, because every check is keyed by owner.
- **A revive cannot admit a row naming missing blobs:** every ref must be present and not queued.

## 4. Split and ownership

- **B4r (relay):** §1 and §3. `:sync-relay-server`, plus the threat model.
- **B4c (client):** §2. `sync-service-proxy`, `sync-service-common` (the new device-local composites),
  `core-common` (the pre-delete hook), `file-service` / `notification-service` (the notice; HR 9),
  `control-shared-logic` (the mapper), `drive-shared-logic` / `drive-shared-ui` (the message). Wiring in
  `control-shared-proxy` platform modules, if needed, triggers HR 8's smoke gate.
- **Shared:** only §0's two JSON bodies and the existing `ReviveEntry`. Neither half changes
  `EdgedPush.kt`.

## 5. As built — B4r

Where the code lives: `ContentFreeing` (interface + in-memory twin `ContentFreedStore`,
`sync-relay-server/.../mailbox/ContentFreeing.kt`), `SqliteContentFreedStore` (storage), migration 23 in
`RelayDatabase`, `applyFreedRules` + `FreedRefusal` in `SyncRouting.kt`, the describe's transaction in
`ContentRouting.kt`. Tests: `ContentFreedRoutingTest` (routes, both backends), `SqliteContentFreedStoreTest`
(the free, the drain, nesting), and `PushEdgesRoutingTest`'s rewritten revive case.

Deviations from §1, each deliberate:
- **The "transactional variant" of `declareRefs` is the existing method, made to nest** (§1.2's last
  bullet). It now runs through `RelayDatabase.inTransaction`, which joins an outer transaction and commits
  only when it is the outermost. Standalone it is still one atomic delete-then-insert, so no caller
  changes; the footgun `RelayDatabase.kt:50` warned about is gone rather than worked around with a twin.
  `forgetRefs` still manages its own transaction and keeps its `check(!autoCommit)` guard.
- **`ContentFreeing` is wider than `free()`.** It also owns the checks the routes need — `freedAmong`,
  `unfree`, `unrevivableRefs` ("absent or queued") and `revive` (replace refs + clear FREED) — plus an
  `atomically` that is the push's transaction (SQLite: the one `RelayDatabase`; in-memory: the edge
  store's lock). `module()` builds the in-memory twin when none is passed, and refuses to boot with a
  `databaseUrl` and a non-SQLite one, the posture of the other content stores.
- **The FREED set is read once per push, before step 2.** An id named on both a regenerable and a
  non-regenerable edge of one push is therefore still refused unless revived; applying step 2 first would
  have let the regenerable edge un-free it for the non-regenerable one. No client sends that shape.
- **Order of refusals:** `content_freed` is decided before `revive_incomplete`, so a push that is both
  answers `content_freed`. All 400s (shape) precede the transaction.
- **Two 400s the spec did not list:** `duplicate contentId in revive`, and `revive is not available on
  this relay` when the route is built without a `ContentFreeing` (never in `main` or `module()`).
- **The describe's per-owner handle cap check stays outside the transaction**, where it was; only the
  freed-check and the writes are inside (all §1.4 asks for).
- **Re-freeing keeps the first `freed_at`** (`INSERT OR IGNORE`), and the queue's `byte_len` is
  `COALESCE(content_blob.byte_len, 0)` for a described ref with no index row.
- **Two §1.6 tests are SQLite-only by necessity:** the fault-injected rollback (the twin cannot roll back)
  and "manifest not re-PUT → still queued" (the twin has no queue). Every other route test runs on both.
- `testModule` with a `databaseUrl` config builds the SQLite `ContentFreeing` on its own connection, like
  its other content-store defaults; such a harness is not one transaction across stores (tests that need
  that pass the stores explicitly, as `ContentFreedRoutingTest` does).

Threat model: the B4r binding amendment in `docs/sync/threat-model.md` §8.3, after B3c's. OpenAPI:
`/sync/push` and `/sync/content/declare` document the 409 `FreedRefusal` body and the revive semantics.

## 6. As built — B4c (2026-10-02)

Where §2 lives, and every place the build departs from it.

**2.1 The typed refusal.**
- ⚠ **Deviation: a new port method, not a new `pushBlock` failure.** `pushBlock` returns a `TResult`, whose
  failure is a wire error and cannot carry a Kotlin value, and fourteen relay fakes override it. So
  `SyncRelay.pushEdged(block, edges, revive): PushOutcome` (`Accepted` / `Refused(ContentRefusal)` /
  `Failed`) carries the refusal, defaulting onto `pushBlock` for a fake that predates B4 (which throws on a
  non-empty revive rather than dropping it). `RestSyncRelay.pushBlock` is now `pushEdged` without a revive;
  a refusal there is a failure whose message carries every id (`CONTENT_REFUSAL_MARKER`), for the iOS probes.
- The 409 body is parsed whole (`ContentRefusalBody.kt`); a 409 without a B4 body is an ordinary failure.
- `ContentBlobStore.describe(contents): DescribeOutcome`. ⚠ `RestContentBlobStore` resends a refused batch
  once **without** the freed ids (the relay wrote nothing of it), so `Refused(freed)` means "everything else
  landed". The backfill counts a refusal as done for those ids; the iOS background store delegates it.
- The `PushBlock` op runs `pushEdged` with the persisted revive and throws `ContentRefusedException` (not a
  `TenshuException`) on a refusal, which the drains catch.

**2.2 Resolution** (`FreedContentResolver`, built in `SyncProxyInitialization.freedContentResolverFor`).
- The holder's local `DataId` comes from decrypting the refused block (`SyncProcessor.rowOfOwnBlock`): the
  block names only a wire name.
- Repairability is "the edge's declaration is `Primary`, and the external-property writer finds bytes",
  which covers `File.Data` and `Workout.Streams` (`workoutStreamsPath`) with no list to extend.
  `SyncObserver.reuploadForRevive` re-uploads under a fresh CEK (never resuming a parked attempt), returns
  chunks + manifest and sends no describe.
- ⚠⚠ **Addition: the stale block.** When the local row no longer names the freed id (a later save changed
  it, or the row is gone), the refused block is dropped with no retire and no notice: a later block or the
  tombstone carries what it said. Retiring would delete a row holding a newer change; re-uploading would put
  that newer file's bytes under the freed id. Also dropped: a refused block that cannot be decrypted.
- A refusal resolved to `Retry` is pushed once more at once; anything still refused waits for the next tick.
  An upload that cannot happen now (gate closed, `507`, failed `PUT`) keeps the block queued and keeps any
  part of the revive that landed.
- ⚠ The pairing flusher reaches the scheduler's resolver through `SyncSchedulerHolder` (already passed on
  every platform), so no platform module changed for it. Its retire shape is unchanged (queue and sidecars,
  not the row, which the scheduler's snapshot may still be reading).

**2.3 Adds before removes.**
- `DataObserver.beforeDataDeleted` (inside `deleteData`'s transaction) and
  `LaneAwareDataObserver.prepareSave` → token → `onDataSaved(…, prepared)` (`ObserverRegistry.prepareSave`).
  ⚠ A token rather than observer state, so two threads saving one row cannot swap observations. The extra
  store read runs only for a type that names content.
- ⚠ **Non-regenerable content only**, in all three of cleared, removed and adds: a regenerable rung is never
  `content_freed`, so nothing waits for it.
- Cleared ids ride the existing `PendingBlockEdges` string as `!cleared` lines (three columns kept, `!` is no
  schema id), so no reshape. `RemovedContents` is recorded for every delete but an ingest or derived one.
- `AddsBeforeRemoves` plans the block drain. ⚠ A block waiting on an add that was **attempted and failed**
  this tick is held (stronger than "pushed after it"): the spec's closed-gate rule, applied to blocks too.
  ⚠ A pure cycle of deferrals (two holders swapping content, through same-holder ordering) is released at its
  earliest block; one that reaches a failed add is not. Indexed by holder and by content, so a 10k-block
  queue is not quadratic.
- ⚠ `RemovedContents` entries are consumed only by an accepted tombstone or reap. A delete that owes neither
  (a local-only removal on a synced device) leaves its small row behind: a bounded leak, not a hold, since
  nothing reads an entry no removal names.

**2.4 The notice.**
- ⚠⚠ **Deviation: no file-service version bump.** `ChangeLostNotification` is a new composite, not a member
  of a sealed constant family: `SchemaShapeBaselineEnforcementTest` named it as a new type to pin, like
  `PendingBlockRevive` and `RemovedContents`, and asked for no bump. The baseline gained three lines.
- Sent through `SendNotification` when the op is registered, else saved directly (the op is that save).
- Control: `Event.FileEvent.ChangeLost` in the Compose feed, the desktop tray bridge (`change_lost`) and the
  iOS row (`ControlEventKind.CHANGE_LOST`, with `HomeView.swift`'s exhaustive switches; ⚠ Swift unverified
  off macOS).
- Drive: `FilesScreenViewModel.changeLost`, a dismissable message on the files screen. ⚠ Dismissal is per
  run (in memory), and the notice is read from the newest 50 notifications; iOS Drive (SwiftUI) does not show
  it.
- OS alert: Android only, through `NotificationPresenter` (`initSyncService(onChangeLost = …)`, channel
  `sync_change_lost`). Desktop's daemon cannot post an immediate alert (`PendingAlertSchedule`); its tray
  shows the feed row instead. ⚠ HR 8's smoke gate is owed for the `androidMain` wiring.

**Tests** (`:sync-service-proxy` `commonTest`, over `FreeingSyncRelay`, a fake answering B4's 409s):
`RestContentRefusalTest` (7), `FreedContentRepairTest` (7: repair and revive, no re-upload after a crash
before the retry, `Workout.Streams`, retire with one notice, `revive_incomplete`, upload not possible now,
describe refused), `AddsBeforeRemovesTest` (13: the planner, a move's tombstone held until the new path's
block is accepted, a clear pushed after another holder's add, same-holder order). Mutation-checked: removing
the tombstone hold or the pre-retry persist turns them red. `NotificationMapperTest` gains the change-lost
row. The two-device system test of §2.5 is the merger's.

