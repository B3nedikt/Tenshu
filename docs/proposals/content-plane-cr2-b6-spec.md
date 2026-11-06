# CR-2 B6 — retire what B replaces

**Status:** v0.2 (2026-10-04). D1–D4 decided by the maintainer (D1 = b, D2–D4 = a); being built. ⭐ §2 and
D1's arming are built on `claude/cr2-b6-sweeps` (as-built §7); §1/§3 in parallel.

**Builds on:** `content-plane-cr2-quorumless.md` §10.7 (B6 row), §10.8 (handles retired), §12.2 (B6 needs
"B5 armed"), §9 Q5 (the deferred-move machinery), §3 P1 (offline moves of unrepairable content); and the B5
spec (`content-plane-cr2-b5-spec.md`), whose sweep ships `off`.

**Already gone (B3d), so not in B6:** the declare-on-move successor declares and `TombstoneCascades`' successor record;
`/sync/content/undeclare` and the edge form of `/declare`. `TombstoneCascades` itself stays: it is B3's wire
cascade. Only stale comments remain (e.g. `EmitKeyLocks.kt`).

---

## 0. Decisions (decided 2026-10-04)

- **D1 — All of B6 in this PR, in one go; the relay deploys `armed` (decided: option b).** The maintainer
  chose one deploy over the split, because the PR already carries the hard cutover and a relay reset and is
  large anyway: "doing it in one go and fixing what does not work might be easier".
  - So §1–§3 all land in this PR, including the device-side sweeps and ST-3b's barrier (§2).
  - Once they are gone the relay sweep is the **only** thing that frees content, so the deploy arms it:
    `sync-relay-server/docker-compose.yml` defaults `CONTENT_SWEEP_MODE` to **`armed`**. `RelayConfig`'s own
    default stays `off` (a relay started without the variable leaks, never loses), and `dry_run` / `off` remain
    overrides.
  - ⚠ Given up, knowingly: the dry-run comparison of relay-would-free against devices-did-free (§10.7). On
    test data that costs little. A relay deployed `off` or `dry_run` now frees **nothing** and keeps charging
    deleted content to quota; that is a leak, never a loss.
  - Not taken: (a) split B6a now / B6b at arming; (c) all of B6 after arming, which would have pushed the
    version bumps past the reset; (d) keep the device sweeps as dead code behind the 410.
- **D2 — One HR 9 bump per service, all in this PR (decided: a).** file-service v15 → **v16** (roots, plus D4's removal of the
  persisted `PendingMove`), health-service v3 → **v4** (`Workout.ReferenceRoot`), sync-service v8 → **v9**
  (the persisted `PendingContentEmit.referenceId`), each with its `SchemaMigration`, and the relay
  migration **v25** dropping `content_reference`. Clients move in lockstep (they already do for v4).
- **D3 — P1 is a refusal, not a dialog flow (decided: a).** An offline move of a file whose bytes are **not on this
  device** is refused with a typed `MoveNeedsLocalBytesError` ("download it first, or move it once you're
  online"), surfaced like B5's `StillSyncingError`. A device holding the bytes may move offline: if it loses
  the race, B4 repairs and revives. This is "tell the user early" for the one case B4 cannot repair.
- **D4 — Remove the deferred-move machinery (§9 Q5: remove; decided: a).** With D3's refusal, B4's repair/retire and
  B5's provisional-row guard, nothing reaches the defer path that is not better served by refusing or by
  B4: `MoveFencePolicy.deferIfUnsafe`, `MoveFencePreflight`, `PendingMoveStore` (intents and content pins),
  `PendingMoveReplay(+Schedule)`, `ReplayPendingMoves(Implementation)`, and the persisted `PendingMove`
  (HR 9, in D2's file-service bump). Online moves held back by a 402/507 simply stay queued as ordinary
  pushes, as every other write does.

## 1. The reference roots and handles

Remove, keeping `ContentReferencing` and its `CONTENT_ID` (§10.11):
- **Schema:** `File.ReferenceRoot`, `SharedFile`'s root, `Workout.ReferenceRoot`; their `valueProperty` and
  `REFERENCE_ROOT` fulfilments (`FileServiceCommonModule`, `HealthServiceCommonModule`); the
  `ContentReferencing.REFERENCE_ROOT` requirement.
- **Minting:** `UploadFileImplementation` (incl. replaceExisting's preservation), `RecordWorkoutImplementation`,
  `ShareFolderIntoRealmImplementation`; `MoveFileOperationTest.moveFile_preservesTheReferenceRoot` goes.
- **Readers:** `ReferenceHandle.referenceHandle()`, `SyncObserver.referenceHandleFor` and its three call sites,
  `ContentDeclarationBackfill.handleFor`, the describe's `referenceId` (client DTO and `ContentDescription`),
  `PendingContentEmit.referenceId`, and `StoredContentReferences` (keeps `CONTENT_ID` only).
- **Relay:** migration v25 drops `content_reference`; remove `ContentReferenceRegistry` and its stores, the
  describe's handle cap and pattern check, and the calls in `ContentFreeing.free`, `SqliteContentFreedStore` and
  `ContentReclaimJob`'s protected-manifest union. ⚠ The describe DTO keeps **accepting** `referenceId` and
  ignores it, so a mismatched client is not a 400 (Ktor rejects unknown keys).
- **Docs:** OpenAPI (already marked "Retired in B6"), §10.8, the threat model's handle mentions.
- **Tests:** ContentHandleDescribedTest, ContentHandleReachesTheRelaySystemTest and the relay's
  ContentReferenceStoreTest go; ContentDescribeRoutingTest, ContentDeclarationBackfillTest, WorkoutContentIdTest
  and B4/B5's relay tests are adjusted.

## 2. The device-side sweeps and ST-3b's barrier

Remove:
- `ContentReclamationSweep`, `ContentReclamationPolicy`, `ProxyContentReclamationRecorder`,
  `ProxyReclamationCandidates`, `ContentReclamationCandidates`, the `reclamationRecorder` in
  `DeleteFile`/`ReplaceFile`, `ContentEnumerationSweep` (and its scheduler hooks), `ReclamationBarrier`,
  `RealmConvergence` (after checking `SyncScheduler`'s cursor recording has no other reader), the platform
  switches (`RECLAMATION_SWEEP_ENABLED` ×3) and their wiring, `MaintenancePassReceiver`'s reclamation leg.
- Relay: the `/sync/content/delete` and `/sync/content/reclaim` routes (410 when armed already; removed, so a
  404 — clients no longer call them) and
  `ContentEnumerationAgeWindow`; the client's `deleteChunks` / `reclaimContents` / `orphanCandidates`.

Keep (the inventory found these coupled):
- `onTickSettled`, which also drives `orphanedBytesSweep` (§10.9).
- From `ReclamationSources`: `stampTombstoneSeq` and the backfill's `declarableContent` / `readBackfillComplete`.
  `ContentDeclarationBackfill` (the describe backfill) is still needed for `content_ref`.

## 3. Moves (D3, D4)

- `MoveFile` refuses `MoveNeedsLocalBytesError` when offline and the file (or any file in a moved folder)
  has no local bytes. Mapped in Drive's move picker and rename dialog next to `ERROR_STILL_SYNCING`.
- The deferred-move machinery listed in D4 is removed, with `EvictFileBytes`/`OfflineKeeper`'s pin checks
  (`ContentPinnedError` goes with the pins).

## 4. Tests owed

- §1/§3: a re-record / overwrite / move no longer writes any root; the describe sends no `referenceId` and the
  relay ignores one if sent; v15→v16, v3→v4, v8→v9 migrations run (`ServiceMigrationChainsReachTheirVersionTest`);
  the baseline is regenerated; an offline move without local bytes is refused, with bytes allowed.
- §2: the gate stays green with the sweeps gone; `ContentSweepSystemTest` (B5) still frees after T; the
  orphaned-bytes hook still fires; the describe backfill still latches.

## 5. Threat model

The amendment records: the relay no longer holds reference handles (`content_reference` dropped); clients no
longer initiate frees at all (§2); the relay sweep, deployed `armed`, is the only freeing path.

## 6. As built — §1 roots and handles, §3 moves, D2's bumps (2026-10-04)

§2 (the device-side sweeps, ST-3b's barrier, `/sync/content/delete|reclaim`) is a separate change; this
section covers only §1, §3 and the HR 9 bumps.

### 6.1 What landed

- **Schema.** `File.ReferenceRoot`, `SharedFile.ReferenceRoot`, `Workout.ReferenceRoot`, their
  `valueProperty`/`fulfilValueRequirement` lines and `ContentReferencing.REFERENCE_ROOT` are gone. The trait
  stays with `CONTENT_ID` alone. `StoredContentReferences` keeps only the content-id slot.
- **Minting.** `UploadFileImplementation` (incl. `replaceExisting`'s read-back), `RecordWorkoutImplementation`
  (incl. the re-record read-back) and `ShareFolderIntoRealmImplementation` write no root. `MoveFile` never
  re-minted; its comment block about preserving the root is gone with it.
- **Readers.** `referenceHandle()` (`ReferenceHandle.kt`), `SyncObserver.referenceHandleFor` and its
  rootless-row reporting, the three `ContentTarget.referenceId` sites, `ContentDeclarationBackfill.handleFor`
  (and its `handles`/`rootless` report columns), `ContentDescription.referenceId` **and `hlc`**, and the REST
  DTO's two fields. A describe is `{contentId, refs}`.
- **HR 9.** file-service **v16** (strips both roots with the new `dropProperty`, drops `PendingMove` rows with
  `dropType`; adds `MoveNeedsLocalBytesError`, removes `ReplayPendingMoves`), health-service **v4** (strips
  `Workout`'s root), sync-service **v9** (`PendingContentEmit` drops `referenceId`; v7→v8's `to` is now the
  frozen `SchemaAtV8`). `TENSHU_STORAGE_FORMAT_VERSION` untouched. Baseline regenerated.
- **Relay.** Migration **v25** drops `content_reference`. `ContentReferenceRegistry`, `ContentReferenceStore`,
  `SqliteContentReferenceStore`, `MAX_REFERENCES_PER_OWNER`, the describe's handle shape check and row cap
  (and its `507`), and the handle cascades in `ContentFreedStore.free`, `SqliteContentFreedStore` and
  `ContentReclaimJob`'s protected-manifest union are gone. The describe DTO declared `referenceId`
  and `hlc` and ignored them until the 2026-10 reset; they are now unknown keys, a 400
  (`ContentDescribeRoutingTest.aRetiredReferenceIdIsAnUnknownKey`).
- **D3.** `MoveNeedsLocalBytesError` (`ExpectedOutcome`), thrown by `MoveNeedsLocalBytes.refuseIfUnsafe` from
  `MoveFile` once per operation, before the first write and after the source/target validation and B5's
  provisional-row refusal. Mapped to `RepositoryError.MoveNeedsLocalBytes` → `ERROR_MOVE_NEEDS_LOCAL_BYTES`
  in the move picker and the rename dialog (Compose and the iOS `RenameSheet`), and to
  `error_move_needs_local_bytes` in `:ui-core`'s catalogue and the iOS `ErrorCopySurface`. Web had no
  `StillSyncing` copy to sit beside, so it has none for this either. The iOS `MovePickerView` mapped no error
  until the short-subtree move refusal (2026-10-29), which gave it the Compose picker's tokens and copy.
- **D4.** `MoveFencePolicy`, `MoveFencePreflight`, `PendingMoveStore`, `PendingMoveReplay(+Schedule)` and its
  three `PlatformProxyModule` registrations, `ReplayPendingMoves(Implementation)`, `PendingMove` (+DTO, data-id
  provider), `MoveFile.resumeMove` and its `resume` branch (the half-applied-folder resume — reachable only
  from the replay), the pin checks in `EvictFileBytes` and `OfflineKeeper` (both sweeps), and `FileContentIds.kt`
  (`contentIdsAtPath`, whose only callers were the pin).

### 6.2 Deviations, and why

- **`ContentPinnedError` stays.** §3 says it "goes with the pins", but `EvictFileBytes` also throws it for
  an **unsent upload** (the bytes are the only copy), and its copy already says "still uploading".
  Removing it would have dropped that refusal or minted an identical error. Its KDoc now names the upload
  journal as its only source.
- **A new migration primitive, `SchemaMigrationBuilder.dropProperty`.** A stored row's named property slot is
  read by looking its id up in the registry, and `DbEntryMapper` **throws** on an unknown id
  (`"Unknown relation schema type null"`) — so unregistering the roots without stripping them would have
  broken every full read of every existing `File` and `Workout`. Nothing in the migration DSL could remove a
  property (the codec preserves properties verbatim), so `SchemaMigration` gained `droppedProperties`, applied
  by `StoreSchemaMigrator.runStep` in the same journaled transaction as the step's rewrites (and so by the
  in-memory iOS/JS seam too; `InMemoryStoreMigrationTest.aDroppedPropertyLeavesEveryRowOfItsTypeAndNothingElse`).
  ⚠ **Store seam only**: `PayloadUpcaster` does not strip it from relay history. Sound here because a peer
  still writing the roots is below the bump and fenced, and D1's relay reset leaves no history carrying them;
  a later property retirement that must read old history owes that half.
- **The describe drops `hlc` too.** It was the stamp of the handle ("the HLC of the row version naming this
  handle", for N4's never-built CAS); with no handle it describes nothing. The relay accepts and ignores it
  exactly like `referenceId`.
- **The relay no longer shape-checks a present `referenceId`.** It used to 400 a malformed one; ignoring a
  field while still validating it would 400 an older client for a value nobody reads.
- **D3's order is walk-then-probe, the reverse of the old fence.** "Has local bytes" is an O(1) `fileSize`
  per file (the old preflight SHA-256'd the subtree), so the walk runs first and the relay probe
  (`confirmWritable`) is asked only when a file without bytes was found. A move of content held here
  never costs a round trip, online or not. "Offline" is unchanged: `isOffline` =
  `SyncHealthHolder.relayUnreachableForFencing()` (never `caughtUp`), `confirmWritable` the acknowledged
  probe, both still audited by `MoveFenceWiringTest` (name kept; its replay audit is gone).
- **What D3 asks for is the primary bytes only.** Thumbnail rungs are not required: B4 re-mints them from the
  primary. A file with no content id references nothing and passes. A subtree the walk cannot fully see (an
  unreadable member list, a dangling row, a cycle, > 50 000 nodes) is refused — unseen is not safe.
- **A deferred move already parked on a device is dropped, not applied.** The v16 `dropType` deletes the
  intents; the user sees the file where it was and moves it again. Realistically there are none: the fence
  shipped behind `isOffline` only recently and the realm resets with D1.

### 6.3 Tests

New: `MoveFileOperationTest` — `uploadMoveAndOverwrite_writeNoReferenceRoot`, an offline move without bytes
refused (file, and folder holding one) with nothing written, with bytes allowed, online allowed after the
probe, a stale online verdict refused by the probe, and no probe when the bytes are here;
`WorkoutContentIdTest.aRecordAndAReRecordWriteNoReferenceRoot`; `RestB3WireTest`'s describe carries neither
key; the relay ignore test above; the `dropProperty` test above; `RepositoryErrorCopyKeyTest` and
`ErrorCopyCatalogueTest` cover the new key. Deleted: `ContentHandleDescribedTest`,
`ContentHandleReachesTheRelaySystemTest`, the relay's `ContentReferenceStoreTest`, and D4's
`PendingMoveStoreTest`, `PendingMoveReplayTest`, `MoveFencePreflightTest`, `MoveFenceProbeTest`,
`ReplayVerifiesEveryFileTest`, `ResumeHalfAppliedFolderMoveTest`, `PendingMoveEncodingTest`.
`EvictRefusesPinnedContentTest` became `EvictRefusesUnsentUploadTest` (the upload half). The `forgetRefs`
cascade tests in `SqliteContentRefStoreTest` now cascade into a probe table (the property is the store's).

### 6.4 Left for the §2 change (settled by §7 at the merge)

`/sync/content/delete` and `/reclaim` still exist here; only their handle cascade went (`forgetRefs` keeps its
now-unused `cascade` parameter). `ContentDeleteMaintainsIndexTest` and `ContentLifecycleSystemTest` lost their
handle assertions and go or change with those routes and the device sweep.

## 7. As built — §2, the device-side sweeps, and arming the deploy (2026-10-04)

Built on `claude/cr2-b6-sweeps`, in parallel with §1/§3 ("B6 roots/moves") and the relay-metadata fixes.
⚠ **No HR 9 bump is needed for this part**: nothing removed here is a service-owned composite. ST-3b's
candidate list was a `DeviceSetting` preference row (`file.reclamation.candidates`, a `StringList` in the
generic value slot), so it simply stops being written; a device's old row is inert. The D2 bumps belong to
§1/§3.

### 7.1 Removed (client)

- `:sync-service-proxy`: `ContentReclamationSweep`, `ContentReclamationPolicy`, `ReclamationBarrier`,
  `RealmConvergence`, `ContentEnumerationSweep`; `initSyncService`'s `reclamationDryRun`, the veto's live set,
  both sweeps' construction, and `SyncProxyComponents.realmConvergence`.
- ⚠ **`SyncScheduler`'s cursor recording had no other reader.** `onPeerCursor` and `onPullOutcome` fed
  `RealmConvergence` only (`onPeerTip` also feeds `HlcDivergenceTracker`, and stays), so both hooks are
  gone, with the `pullReported` try/finally that existed to report a failed pull pessimistically to the
  barrier. The relay still publishes `peerCursors` (`ChangesResponse`/`SyncLogResponse`); nothing reads them.
  (Removed 2026-10-29 — §7.6.)
- `SyncScheduler`'s `enumerationSweep` and its leg of the maintenance pass; `backfillContentDeclarations()`
  now runs the backfill alone. ⇒ `MaintenancePassReceiver` (Android debug) frees nothing; only its KDoc
  changed, because its reclamation leg *was* that call.
- `:file-service`: `ContentReclamationCandidates` and `ContentReclamationRecorder`. ⚠ The backfill's
  marker lived on the candidates object, so it moved to **`ContentBackfillMarker`** with its preference id
  **unchanged** (`file.reclamation.backfillComplete`) and `BACKFILL_FORMAT` still `v8-repair-manifestless`:
  renaming the id would read every latched device as never having run.
- `:file-service-proxy`: `ProxyContentReclamationRecorder` (`reclamationRecorderFactory`) and the
  candidates' read/write/stamp. `ProxyReclamationCandidates.kt` became `ProxyDeviceSettings.kt`, keeping
  the two device-setting helpers four other markers share. The `reclamationRecorder` parameter is gone from
  `DeleteFile`, `ReplaceFile`, `UploadFile` (its overwrite record), the common / Android / iOS factories,
  `AndroidFileReplaceImplementation`, `IosReplaceSourceFileImplementation` and `createFileProxy`.
  `DeleteFile`'s walk no longer collects content ids.
- `ContentBlobStore.deleteChunks` / `orphanCandidates` / `reclaimContents`, their `RestContentBlobStore`
  bodies (and the 410-is-done shim from the B5 merge note) and `BackgroundUrlSessionContentBlobStore`'s
  delegations.
- The platform switches: `RECLAMATION_SWEEP_ENABLED` and `RECLAMATION_DRY_RUN_ENABLED` in all three
  `PlatformProxyModule`s, and the recorder wiring (two sites on Android). `SystemTestPlatformModule` follows.

### 7.2 Removed (relay)

`POST /sync/content/delete`, `POST /sync/content/reclaim`, and — because `ContentEnumerationAgeWindow` had
exactly two consumers and both existed to feed client-side freeing — **`GET /sync/content/orphan-candidates`
and `GET /sync/content/contents`** (N2), with their DTOs, caps and D3's `FreeingRefusal`. All four are an
unmatched 404 in every sweep mode. `GET /sync/content/inventory` keeps the shared `candidateContentIds`
predicate. ⚠ `RELAY_PROTOCOL_VERSION` stays 4: a route removed that no current client calls. The
registries' `forgetRefs` and `describedContentIdsAfter` have no production caller left; they are kept for
now to stay out of §1's way in the same interfaces.

### 7.3 Kept, as §2 required

- `onTickSettled`: `initSyncService` now passes `orphanedBytesSweep` straight through, so it is non-null
  exactly when that sweep is wired (desktop, behind `ORPHANED_BYTES_SWEEP_ENABLED`).
- `ReclamationSources` keeps `stampTombstoneSeq`, `declarableContent`, `readBackfillComplete` and
  `writeBackfillComplete` (the name kept so the wiring did not move). ⚠ **`stampTombstoneSeq` is now a seam
  with nothing behind it**: its only target was the candidate list, so no platform wires it and the
  scheduler's no-op default runs. `TombstoneSeqStampTest` still pins that the drain hands the seq over.
- `ContentDeclarationBackfill` and the schema-driven walk it shares; `LiveContentIds` and
  `ProxyLiveContentIds` (the tree walk's `children` contract, which `declarableContent` rests on). ⚠
  `liveContentIds()` itself now has no production caller (it was the veto's fallback); it stays because
  `DeclaredContentIsWalkableTest` pins §9.6's *declared ⇒ walkable* invariant through it.

### 7.4 Arming the deploy (D1)

`sync-relay-server/docker-compose.yml` defaults `CONTENT_SWEEP_MODE` to **`armed`**; `RelayConfig` keeps
`off`. Nothing under `deploy/` sets the mode (cloud-init writes `deploy.env` without it, and
`reset-test-data.sh` does not touch it), so the compose default is what both relays get. The B5 spec, the
`relay-content-reclaim.md` runbook, `RelayConfig`'s env table and `deploy/scaleway-relay-blobs/README.md`
now say so; the threat model carries the B6 amendment (§8.3), with T-R-13 and the CR-5 routes marked
retired; the OpenAPI file drops the four routes.

### 7.5 Tests

- Deleted with their subjects: the sweep, policy, barrier (×3), dry-run, enumeration-sweep, sweep-wiring,
  peer-cursor and REST-delete tests in `:sync-service-proxy`; the recorder tests in `:file-service-proxy`
  and `:control-shared-proxy`; `ContentReclamationCandidateSeqTest`; and the relay's
  `ContentDeleteMaintainsIndexTest`, `ContentListingRoutingTest`, `ContentReclaimRoutingTest` plus
  `ContentRoutingTest`'s `delete_*` cases.
- Kept by trimming: `LiveContentIdsTest` (was `ContentReclamationPlumbingTest`), `ReclamationChildrenTest`
  (was `ProxyReclamationSeamsTest`), `UploadOverwriteReclamationTest` / `ReplaceQuotaGateTest` (now assert
  the contentId instead of a recorder), `ContentBackfillWiringTest` (the two CR-5 cases go; the backfill
  still latches through the real wiring), `ContentSweepRoutingTest` (D3's two cases become "gone in every
  mode"), `ContentInventoryRoutingTest`.
- New: `OrphanedBytesTickHookTest` — the orphaned-bytes sweep alone still reaches a settled tick through
  `initSyncService`, and a throw costs a log line, not the next tick.
- `ContentSweepSystemTest` (B5) is unchanged apart from a comment, and still frees after T.

### 7.6 Open

- ✅ *Closed 2026-10-29 (pre-reset one-way doors):* `stampTombstoneSeq` (with `TombstoneSeqStampTest`),
  the relay's `forgetRefs` / `describedContentIdsAfter`, and the relay's `peerCursors` publication
  (`PeerCursor`, the `device_cursor` table — dropped at relay schema v5) are deleted. The wire part had
  to go before the reset: afterwards removing a response field is a `RELAY_PROTOCOL_VERSION` reshape.
- `liveContentIds()` stays: `DeclaredContentIsWalkableTest` pins §9.6's *declared ⇒ walkable*
  invariant through it (§7.3). It is device-local, so keeping it costs nothing at the reset.
- Old devices: a client from before this PR still runs ST-3b and calls `/sync/content/delete`, which now
  404s; the old `deleteChunks` reads a 404 as "not reclaimed" and keeps its candidate, so it retries every
  tick and frees nothing. Each retry also re-reads the deleted content's manifest, which takes it out of
  the relay's free queue (B5 spec §6, finding 2): a leak of that manifest, never a loss. Clients move in
  lockstep with this deploy (D2), so this is the reset window only.
