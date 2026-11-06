# CR-2 B5 — G2: the relay sweep, dry-run first, then armed

**Status:** v0.1 (2026-10-03), ⭐ **D1–D5 decided by the maintainer the same day.** Built in two parallel
halves: B5r (relay, §1–§3, §5) and the provisional-row move guard (client, §4). ⭐ **Both built 2026-10-03**:
B5r on `claude/cr2-b5r-relay` (as-built §6; ships `off`), the guard on `claude/cr2-b5-provisional-guard` (as-built §7).

> ⚠⚠ **Superseded in part by CR-2 B6 D1 (2026-10-04, `content-plane-cr2-b6-spec.md`): the deploy is armed
> directly; there is no dry-run phase.** B6 removed every client-side free in the same PR, so this sweep is
> the only freeing path, and `sync-relay-server/docker-compose.yml` defaults `CONTENT_SWEEP_MODE` to
> **`armed`**. `RelayConfig`'s own default stays `off`, and `off` / `dry_run` remain as overrides, but a
> relay on either now frees **nothing** (a leak, never a loss). The dry-run comparison was given up knowingly
> (B6 D1). D3's 410 routes are **gone** (removed in B6, so a 404 in every mode). The "dry-run first" text
> below is the B5 plan as decided on 2026-10-03.

**Builds on, and does not re-argue:** `content-plane-cr2-quorumless.md` §10.2 (G2's rules: the clock, the
serialised free, the conditional physical delete, the owner-keyed queue), §10.7 (B5's row and its arming
gates), §10.10 (the queue-ABA finding), §10.14 (T, the quota exclusion, minutes once B4 lands); and the B4
spec (`content-plane-cr2-b4-spec.md`), whose `ContentFreeing.free` is the logical free this sweep calls.

**What B5 is:** the first and only production caller of the free. Everything before it built the state and
the way out; B5 starts the clock and runs the sweep. It ships **off**, runs **dry** until the maintainer has
read what it would free, and is **armed** by a deploy-time switch. ⚠ B6 D1 skipped the dry phase: the deploy
arms it directly (see the note at the top).

---

## 0. Decisions (⭐ maintainer, 2026-10-03)

- **D1 — T when armed: 15 minutes, sweep every 5 minutes.** §10.14 decided "minutes once B4 lands"; B4
  is built (adds before removes + G3). 15 min covers push-to-acknowledgement latency with a wide margin.
  Config: `CONTENT_GRACE_MS` (default 15 min) and `CONTENT_SWEEP_INTERVAL_MS` (default 5 min). A content
  is freed between T and T + interval after its last edge.
- **D2 — One switch, three modes:** `CONTENT_SWEEP_MODE = off | dry_run | armed`, default **off**. ⚠ Since
  B6 D1 that is `RelayConfig`'s default only; the compose deploy defaults to **`armed`**.
  - `off`: the clock is still kept (§1.1), nothing else runs.
  - `dry_run`: the sweep runs and logs what it would free (content ids, ref counts, bytes), frees nothing.
  - `armed`: the sweep frees and drains.
  Same posture as `retention-quota-model.md` P3: one flag per plane, deletion is a deploy-time decision.
- **D3 — The legacy FREE routes are refused outright when armed.** `/sync/content/delete` and
  `/sync/content/reclaim` answer **410 Gone** `{"error":"freeing_is_the_relays"}` in `armed` mode, for
  every content. The relay was reset at B3, so every content is G2-governed; there is no legacy population
  to keep serving. The device sweeps that still call them (until B6 removes them) then simply stop freeing.
  In `off` and `dry_run` they work as today. ⚠ **B6 removed both routes and the device sweeps** (B6 spec
  §2, §7): they are a 404 in every mode now, and no client frees.
- **D4 — The clock is seeded at arming.** On the first sweep in `armed` mode, every **described** content
  (has `content_ref` rows) with **zero** live edges gets `unreferenced_since = now` (§10.7's "seed the
  clock"), so it enters T rather than never seeing a one-to-zero transition. Content with no description
  and no edge stays outside the cycle (G4).
- **D5 — The quota exclusion applies in `armed` mode only.** UNREFERENCED and FREED-but-queued bytes are
  left out of `/sync/usage` and the quota check (§10.14). In `off`/`dry_run` they are still charged:
  bytes nothing will ever free must count, or deleting would grant unbounded uncharged storage.

## 1. Relay

### 1.1 The clock: `content_unreferenced` (migration v24)

- `content_unreferenced(owner_id, content_id, since, PRIMARY KEY(owner_id, content_id))`.
- **Set** when a content's live-edge count goes **from one to zero**, inside the transaction that removed
  the edge: `writeEdges` (an edge moved to another content or to `none`), `removeHolders` (tombstone,
  cascade, reap). Detect the transition per content id touched, after the writes: "had a live holder
  before, has none now".
- **Cleared** when any live edge names the content again (`writeEdges`), and by `free()` (the content
  leaves UNREFERENCED for FREED) and by a revive.
- Kept in **every** mode, so a later `armed` has an accurate clock from the moment it was kept, not only
  from arming. D4's seed covers what predates it.
- ⚠ Regenerable rungs follow the same clock; a FREED rung is cleared by a push naming it (B4 §1.3 step 2).

### 1.2 The sweep (`ContentSweepJob`)

Every `CONTENT_SWEEP_INTERVAL_MS`, per owner, in `dry_run` or `armed`:
1. Select `content_unreferenced` rows with `since + T <= now`.
2. **`dry_run`:** log one line per owner — count, ids (capped), refs, bytes — and stop.
3. **`armed`:** for each, call `ContentFreeing.free(owner, contentId)` (B4). Its own transaction re-checks
   zero live edges and serialises with a racing push (§10.2 G2); `NOT_FREED` means a push won, and the
   clock row is already cleared by that push.
4. **`armed`:** drain the free queue: reuse `ContentReclaimJob.drain`'s batch (lock held across the byte
   delete; spares anything described or a protected manifest; a PUT or GET dequeues first). After `free()`
   nothing of the content is described or protected, so its refs drain.
- The first `armed` run performs D4's seed before step 1.
- ⚠ **Queue ABA (§10.10).** A drain batch holds the store lock from selecting a queued ref to deleting its
  queue row, so a revive (which re-PUTs, dequeuing) and a re-free cannot interleave inside one batch. B5
  adds a test that pins this; if the drain ever releases the lock mid-batch, acknowledgement must become
  compare-and-delete on the observed enqueue time.

### 1.3 Legacy routes (D3)

`armed`: `/sync/content/delete` and `/sync/content/reclaim` → 410 `freeing_is_the_relays`. ⚠ Removed in B6
(404 in every mode). Operator routes
(`/ops/content-reclaim/*`, B0) stay, behind the operator token, for undescribed garbage (G4 populations).

### 1.4 Quota (D5)

`armed`: the owner's charged bytes = all `content_blob` bytes − bytes of refs that belong only to content
in `content_unreferenced` or queued in `content_free_queue`. A ref shared with live content stays charged.
`/sync/usage` reports the charged figure, plus `heldBytes` (the excluded part) for diagnostics.

### 1.5 Observability

- One log line per sweep per owner (mode, candidates, freed, NOT_FREED, drained bytes).
- `GET /ops/content-sweep/status` (operator token): mode, T, last run, totals. Read-only.

## 2. Gates before switching to `armed` (§10.7, restated)

- B4 shipped: ✅.
- Legacy FREE routes closed in the same deploy: D3, automatic with the mode. ⭐ B6 removed them outright.
- The push floor and side routes: met by B3's reset (wire spec §0).
- The delete-vs-move system test, with bytes: ✅ `FreedContentResolutionSystemTest` (B4).
- **The provisional-row move guard (§12.3): owed.** A client item, built alongside B5 (§4).
- ~~**A dry run read by the maintainer:** at least one `dry_run` period on the real relay whose would-free
  list matches expectation.~~ ⚠ **Waived by B6 D1** (2026-10-04): the deploy arms directly, on test data,
  in the same deploy that removes the device sweeps.

## 3. Tests owed

- Clock: one→zero via overwrite, via `none`, via tombstone, via cascade, via reap sets `since`; a new edge
  clears it; two holders → removing one sets nothing.
- Sweep modes: `off` frees nothing and logs nothing; `dry_run` logs and frees nothing; `armed` frees after
  T and not before, and the bytes are gone after the drain.
- Race: a push adding an edge between selection and `free()` → `NOT_FREED`, content live, bytes intact.
- Seed: first `armed` run seeds described zero-edge content only.
- D3: 410 in `armed`, unchanged otherwise. D5: usage excludes held bytes in `armed` only; a shared ref
  stays charged.
- ABA: free → revive → free within one drain cadence never deletes the revived bytes.
- System test: a delete, T elapses (test clock), the sweep frees, the drain deletes, quota drops; a revive
  after that via B4's path.

## 4. The provisional-row move guard (client, §12.3)

A provisional slice-snapshot row (no `SyncMetadata` yet) must not be moved before it catches up. Test
first (the audit marks "nothing blocks a move on a provisional row" UNVERIFIED), then hold the move until
the row has `SyncMetadata`, with a user-visible "still syncing" refusal. Independent of §1; a separate,
small change.

## 5. Threat model (House Rule 2)

The amendment records: deletion is now relay-initiated on the relay's clock (T), with no device input; the
relay learns when content became unreferenced (already derivable from its edge registers); the legacy
client-initiated free routes are closed when armed.

## 6. As built — B5r (2026-10-03)

**Where it lives.** Migration 24 in `RelayDatabase` (`content_unreferenced`, plus `content_sweep_state`).
The clock is kept by both edge stores (`SqliteContentEdgeStore`, the in-memory `ContentEdgeStore`) inside
`writeEdges` / `removeHolders`, and read through new `ContentEdgeRegistry` methods (`unreferencedSince`,
`expiredUnreferenced`, `startClockIfUnreferenced`, …). `ContentFreeing` gains `seedClockOnce`, `seededAt`,
`pendingSeedCount` and `heldBytes`; `free()` clears the clock row. The sweep is
`sync-relay-server/.../storage/ContentSweepJob.kt`, scheduled in `Application.module` like the other gc
loops (not launched in `off`). Config: `RelayConfig.contentSweepMode / contentGraceMs /
contentSweepIntervalMs` from `CONTENT_SWEEP_MODE / CONTENT_GRACE_MS / CONTENT_SWEEP_INTERVAL_MS`, all three
forwarded by `docker-compose.yml` (`ComposeEnvPassthroughTest` caught the omission). D3 and D5 are in
`ContentRouting.kt` (`FreeingIsTheRelays`, `UsageResponse.heldBytes`, `quotaWithHeld`); the status route is
in `OperatorRouting.kt`. **No `RELAY_PROTOCOL_VERSION` bump:** the 410 is a new answer on two existing routes
that only `armed` gives, and `heldBytes` is an additive field older clients ignore (`ignoreUnknownKeys`).

**Deviations from §1, each deliberate:**
- **A second table, `content_sweep_state`** (one row). D4 says the *first* armed run seeds; without a durable
  marker every restart would re-seed, putting described content that is still waiting for its first push
  (a describe lands before the edge) on the clock each time. The marker is written in the seed's transaction.
- **A revive does not blindly clear the clock; it restarts it when no live edge holds the content.** By the
  time a revive runs, `free()` has already removed the row. Clearing would then be a no-op, and so would the
  push's edge if it loses LWW (a stale stamp, a holder watermark above it), leaving described, edgeless
  content off the clock: a leak no later one-to-zero transition could end. So the revive starts the clock
  (`INSERT OR IGNORE`) iff no live edge names the content, and the push's own edge write, later in the same
  transaction, clears it in the normal case. Net effect as §1.1 intends.
- **`NOT_FREED` also clears the row** in the sweep. The push that won already did; this only restores the
  invariant "a live edge has no clock row" should anything ever leave one.
- **Which queues the sweep drains.** The owners it freed for in this run, plus owners whose earlier drain
  left work (in memory, so after a restart a leftover waits for that owner's next free or the operator
  route). It does **not** drain every owner with queue rows: a B0 operator enqueue keeps its explicit drain
  step. Per run: at most 5,000 frees (`MAX_CANDIDATES_PER_RUN`, so the D4 backlog spreads over runs) and 50
  drain batches of 200 refs per owner.
- **The drain is `ContentReclaimJob.drain` unchanged**, including B0's pause while some content has an edge
  and a stored manifest but no description: a content mid-declaration pauses that owner's drain until a
  later run (`pausedOnEdgeOnly` is logged).
- **`dry_run` also reports the arming backlog** (`pendingSeed`: described zero-edge content D4 would seed),
  because arming frees it T later and the maintainer should read that number before arming.
- **The quota exclusion is an allowance**, `quotaBytes + heldBytes`, read just before the store's own locked
  check (the store sums every indexed byte and has no notion of the clock). A push reviving held content in
  that window can overshoot the quota once by at most that content: the in-memory store's bounded-overshoot
  posture, never a lasting excess. `heldBytes` is reported as 0 (not absent) outside `armed`.
- **The in-memory relay** frees logically and never physically (no queue, B4 §1.5); its `heldBytes` is the
  UNREFERENCED half only, the status reports `durableDrain: false`, and the boot log says so when armed.
- **One source for the mode.** `module` takes an optional `contentSweepJob` (built from the config when
  absent), and D3/D5 read *its* mode, so the routes and the sweep cannot disagree. An unknown
  `CONTENT_SWEEP_MODE`, or a non-positive duration, refuses to start rather than default.
- **`GET /ops/content-sweep/status`** is registered whenever `RELAY_OPERATOR_TOKEN` is set, durable store or
  not (it is read-only); the B0 reclaim routes still need the durable store.

**Queue ABA (§1.2, §10.10): holds, no compare-and-delete needed.** `ContentReclaimJob.drain` holds the store
lock from selecting a batch to deleting its queue rows, and a PUT/GET dequeues under that lock first.
`ContentSweepJobTest` pins it twice: free → revive (re-PUT of the derived manifest) → drain → free → drain
never deletes revived bytes, and a revive's re-PUT issued while a batch is mid-delete blocks until the batch
ends and its bytes stand.

**Findings (for the maintainer reading the first dry run, and for B6):**
- ⚠ **A deleted file frees more than one id.** The row's regenerable rungs (thumbnail classes) had edges too,
  were never described, and reach zero edges with it; the sweep frees them alongside, which only writes
  FREED rows (B4 §1.2 step 4). A dry run's `wouldFree` therefore counts several ids per deleted file, most
  with no refs and no bytes. Measured in the system test: one file → six ids, one with bytes.
- ⚠⚠ **Until B6, the device-side `ContentReclamationSweep` can keep a freed manifest alive.** It reads a
  deleted content's manifest (to name its chunks) and then calls `/sync/content/delete`, which now answers
  410; it does not treat that as done, so it retries every cycle. A read landing between the sweep's free and
  its drain takes the manifest out of the queue (a read proves use, by design), and it then survives as
  undescribed bytes: a ~2 KB leak per affected delete, never a loss (seen ~1 run in 5 in the system test,
  whose clients sweep every 200 ms). Owed to B6 (remove the sweep) or, sooner, the client treating 410 as
  final; the operator reclaim (B0) can collect the leftovers.

**Tests.** `ContentClockTest` (the clock contract on both backends: overwrite, `none`, tombstone, cascade,
reap; a new edge clears; two holders; stale writes; free clears; revive; the seed once; held bytes with a
shared ref; restart survival — 12 + 13), `ContentSweepJobTest` (off / dry_run / armed, T's boundary, a shared
chunk spared, the race, the seed, both ABA pins, status, in-memory — 11), `ContentSweepRoutingTest` (D3 410
armed only, D5 usage and quota armed only with a shared ref charged, the status route's brakes — 7),
`RelayConfigTest` (+3). `:sync-relay-server:test` 733 tests green. System test
`ContentSweepSystemTest` on `TestSystemBuilder.durableContentPlane` (the SQLite + filesystem content plane
with a test clock): a delete is held at once (D5), not freed before T, freed after T, its chunks drained and
usage down by exactly what was drained; and a move landing after that free **and** drain is refused, re-
uploaded and revived through B4's path, live again with every ref stored. `:system-test-utils:test` 91 green.

**Threat model:** the B5r binding amendment in `docs/sync/threat-model.md` §8.3, after B4r's. **OpenAPI:**
410 `FreeingIsTheRelays` on `/sync/content/{delete,reclaim}`, `heldBytes` on `/sync/usage`,
`/ops/content-sweep/status` with `ContentSweepStatus` (validated with `openapi-spec-validator`).

**Still owed before arming (§2):** the provisional-row move guard (§4, client) and a `dry_run` period on the
real relay read by the maintainer. ⚠ The latter was waived by B6 D1; the guard is built (§7).

## 7. As built — provisional-row guard (2026-10-03)

⭐ **Finding, test first:** nothing blocked a move of a provisional row. Measured by
`ProvisionalRowMoveTest.withoutAProbe_aProvisionalRowMoves_andPushesItsStaleContent` (real slice applier,
ingest, `TombstoneRegistry` and `MoveFile`): the move succeeded, the new row carried the snapshot's content
id, and the old path was tombstoned for every peer. The audit's UNVERIFIED (B2 audit §3.5, W14) is
therefore **confirmed as a gap**, and the guard below closes it.

- **What "provisional" means, as built** (`ProvisionalRowProbe`, sync-service-proxy): the device is in a
  realm (`residentOwnerId != null`), is still catching up (`backfillCursor != 0`), and holds **no version**
  of the row — neither `SyncMetadata` (an ingested block) nor `LocalWriteMetadata` (its own write). That is
  exactly the state `SliceSnapshotProducer.applySlice` leaves a row in; it ends when the real block lands
  or the user writes the row. It reads `TombstoneRegistry.versionOf`, the same "later of the two" B3b's
  reaper orders by.
  - ⚠⚠ **Gated on catch-up, deliberately.** "No version" alone also describes a row written before
    `LocalWriteMetadata` shipped and never ingested back, and any row while the realm is locked (no wire
    name). Snapshots are applied only while `backfillCursor != 0`, and a completed walk has ingested every
    real block, so outside catch-up nothing is refused. Inside it the rule over-approximates (such a
    legacy row is refused too): the safe side, because the refusal is temporary and says so.
  - ⚠ Not "has `SyncMetadata`" alone, as §4 first worded it: `SyncMetadata` is ingest-only, so that rule
    would refuse every row the user created on this device.
- **Port:** `ProvisionalRows` (sync-service-common), carried as `TombstoneEmitter.provisionalRows` (default
  null ⇒ nothing refused) and served by `SyncObserver`, for the reason `reapOrdering` rides there: the
  emitter already reaches `MoveFile` on desktop, Android, iOS, the web runtime and the system-test harness.
- **Refuse, not park.** `MoveFileImplementation` checks after validation and **before** D3's fence, so a
  provisional row is never parked either (a parked move would replay the same stale row). A folder move
  walks the subtree, because it re-keys every descendant. The walk runs only while `mayExist()` is true,
  so an ordinary move pays one boolean. Rename is a move, so it is covered too; no other op re-keys a row
  (`DataIdMigrator.migrate` has one caller, the move). A parked move whose replay hits the refusal stays
  queued (the replay treats a failed move as transient).
- **Error:** `StillSyncingError` (`tenshu/file/system/error/stillSyncing`, `ExpectedOutcome`), a new type,
  so no service version moves (HR 9; `SchemaShapeBaseline` regenerated). Catalogue key
  `error_still_syncing` in `:ui-core` and the iOS `ErrorCopySurface`. Drive maps it to
  `RepositoryError.StillSyncing` → `ERROR_STILL_SYNCING` in the move picker and rename dialog
  (commonMain), and in `RenameSheet.swift`.
  - ⚠ iOS's `MovePickerView.swift` renders no move error at all today (pre-existing), so a refused move
    there closes silently-unmoved. Swift is not compiled off macOS: the `RenameSheet` case is unverified.
- **Pinned by** `ProvisionalRowMoveTest` (6 cases): refused with nothing written or pushed; moves once the
  real block lands; the user's own row moves mid-catch-up; nothing refused once caught up; a folder with a
  provisional child refused; and the unguarded finding above.

**Merge note (2026-10-03).** B5r's finding 2 (the device sweep retrying a 410 and re-reading a freed
manifest mid-drain) is fixed client-side in the merge: `RestContentBlobStore.deleteChunks` and
`reclaimContents` treat **410** as done, so the candidate is forgotten (`RestB3WireTest`). B6 still removes
the device sweeps outright.
