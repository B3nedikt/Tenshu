# CR-2 B2 audit (a) + (b): can every content-bearing writer name its edges at push, and do content-bearing properties converge by LWW?

**Status:** audit delivered 2026-10-01; **did not come back clean** (eleven gaps, §4). Eight questions went to the maintainer (§4 iii); ⭐ **Q1–Q4 and Q6–Q8 are answered** (2026-10-01:
`content-plane-cr2-quorumless.md` §10.11 and §10.12); Q5 was answered on 2026-10-02. (c), choosing T, was decided on 2026-10-02 (24 h, held bytes not charged; `content-plane-cr2-quorumless.md` §10.14); §5 lists the facts that bore on it.
B3 is gated on this document (`content-plane-cr2-quorumless.md` §10.7).

**What this is:** phase B2 of candidate B, an analysis with no code change. Terms are those of
`content-plane-cr2-quorumless.md` §10.2 (G1–G4), §10.5, §10.7 and §10.10, and
`content-plane-refcount.md` §9.3 and §9.9a.

**How far to trust it:**
- Read-only, by a delegated agent, against commit `f11141743` (master plus doc-only changes). ⚠ Line
  numbers are that commit's and will drift.
- **Nothing was compiled or run.** Every claim cites code that was read; anything inferred is marked
  **UNVERIFIED**, and §6 lists what was not looked at.
- Four citations were re-read independently before this was filed and matched: the row saved before
  its bytes are emitted (`UF:401`, `488-492`), the in-memory cascade (`TombstoneCascades.kt:16-17`), the
  bare-composite summary workout (`RW:84-91`), and the ingest comparison (`SP:686-700`). The rest is as
  the audit left it.

Abbreviations for the most-cited files:

| short | path |
|---|---|
| `SO` | `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncObserver.kt` |
| `SP` | `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncProcessor.kt` |
| `SS` | `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncScheduler.kt` |
| `TR` | `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/TombstoneRegistry.kt` |
| `FBW` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/proxy/FileBytesPropertyWriter.kt` |
| `UF` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/UploadFileImplementation.kt` |
| `MF` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/MoveFileImplementation.kt` |
| `DF` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/DeleteFileImplementation.kt` |
| `FSPI` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/proxy/FileServiceProxyInitialization.kt` |
| `RW` | `health-service-proxy/src/commonMain/kotlin/org/tenshu/health/proxy/executor/RecordWorkoutImplementation.kt` |

---

## 0. The two findings that change how B3 should be read

1. **A row's block is built and pushed BEFORE its bytes exist on the relay, not after.** §10.5 ("Uploads before their metadata") and §10.2 G2 ("the push's presence check") assume the opposite order. In the code the row is saved, its block is queued, and only then are chunks uploaded: `UF:401` (`saveData`) → `UF:488` (`awaitEmitIdle`, block now in `pendingBlocks`) → `UF:492` (`emitFileContent`). Same for workouts: `RW:96` → `RW:114` → `RW:121`. The upload can then be parked for an unbounded time behind the metered gate (`SO:1921`), a `507` latch (`SO:1935`) or a locked realm (`SO:2316-2318`), while the metadata block drains normally. So at push time the edge is nameable, but the content it names is very often **not on the relay yet and not described**. A push-time check must therefore be "is this content marked freed", never "are its bytes present".
2. **The §10.10 reaper finding is real, still matches the code, and does not need clock skew.** See §3.4. A third un-tombstoned remover exists that the design does not name (`OrphanedFileSweeper`).

---

## 1. Enumeration and cross-check

### 1.1 Schema side: every content-bearing property

`grep 'SyncStrategy.OnDemand'` over all `.kt/.ts/.swift` outside `build/` gives exactly seven registered properties:

| property | declared at | receiver |
|---|---|---|
| `File.Data` | `file-service/src/commonMain/kotlin/org/tenshu/core/file/File.kt:514-518` | `File` |
| `File.Thumbnail` (LIST) | `File.kt:660-664` | `File` |
| `File.ThumbnailLarge` | `File.kt:698-702` | `File` |
| `File.ThumbnailXLarge` | `File.kt:732-736` | `File` |
| `File.ThumbnailXXLarge` | `File.kt:760-764` | `File` |
| `File.ThumbnailMotion` | `File.kt:788-792` | `File` |
| `Workout.Streams` | `health-service/src/commonMain/kotlin/org/tenshu/core/health/Workout.kt:203-207` | `Workout` |

Plus one **unregistered** content slot: `SharedFile.DATA_PROPERTY_ID`, a bare `const` (`file-service/src/commonMain/kotlin/org/tenshu/core/file/shared/SharedFile.kt:143`), which `FBW:56-59` supports and answers a content id for (`FBW:159`).

The row-side value that names the content:

| type | where the content id lives | LWW unit |
|---|---|---|
| `File` | value property `File.ContentId` (`File.kt:172-173`); thumbnails derive from it (`FBW:152-156`, `FBW:243-244`) | whole entity |
| `Workout` | value property `Workout.ContentId` (`Workout.kt:273-274`) | whole entity |
| `SharedFile` | composite field `CONTENT_ID` (`SharedFile.kt:218`, read at `FBW:159`) | whole entity |

Value properties carry no `Syncable` trait (`core/src/commonMain/kotlin/org/tenshu/core/relation/Property.kt:163-170`), so they never get their own block; they ride inside the entity block (`SO:1381-1409`; parsed back at `core-common/.../parser/BinaryParser.kt:508-513`). **UNVERIFIED:** I read the parser, not `BinarySerializer`'s entity branch.

### 1.2 Writer side: three independent greps

- **Implementations of the port:** `FileBytesPropertyWriter` (`FBW:52`), `WorkoutStreamsPropertyWriter` (`health-service-proxy/.../WorkoutStreamsPropertyWriter.kt:34`), plus the delegating fan-out and the JS forwarding wrapper. Registered on Android/iOS/desktop as File + Workout (`control-shared-proxy/src/{androidMain,iosMain,desktopMain}/.../PlatformProxyModule.kt:836-838 / 678-680 / 823-825`), on web as File only (`tenshu-sdk-js/.../JsRuntime.kt:640-667`).
- **Everything that PUTs to the content plane** (`putChunk(` on a `ContentBlobStore`): `SO:2482`, `SO:2625` (emit), `SO:2826`, `SO:2855` (cross-realm share), `SliceSnapshotWriter.kt:85`. Nothing else. `ShareLinkImplementation.kt:229` uses `ShareRelayClient` (the Mode-A share store), not the content plane.
- **Everything that creates or saves a `File` / `Workout` / `SharedFile` row:** `UF:353-401`, `MF:404-435`, `FileSliceSnapshotProducer.kt:195-203` (ingest origin), `ShareFolderIntoRealmImplementation.kt:120-131, 269-291`, `RW:85-96`, `RecordImportedHealthImplementation.kt:39` (bare `Workout`s), and one indirect path: a Folder block's embedded members (`core-common/.../proxy/db/DbEntryMapper.kt:630-636`).

⚠ **One more client path reaches these routes, and the audit's grep missed it**. On iOS route-1 Drive with the background bridge installed, `BackgroundUrlSessionContentBlobStore.putChunk` (`tenshu-sdk-ios/src/iosMain/kotlin/org/tenshu/sdk/ios/BackgroundUrlSessionContentBlobStore.kt:30-31`) only stages the chunk and enqueues it through `BackgroundUploadManager.stageAndEnqueue`. It returns success then, and `nsurlsessiond` performs the `PUT /sync/content/{ref}` later. It is not a new edge writer, so (a)'s table stands, but it bears on B4: "re-upload, then retry with REVIVE" can retry while the relay still lacks the refs the atomic revive verifies. B4 needs a background-upload completion barrier or an explicit retry policy.

Apart from that, no other module talks to `/sync/content/*`: grep over `web/`, `hub/`, `commerce-service/`, `inference-service*`, `drive-ios/` etc. found only `RestContentBlobStore.kt`. Inference models, embeddings, face templates and map regions are device-local `FileDatabase` stores (e.g. `inference-service-proxy/.../DefaultEmbeddingStore.kt:12`), not content-plane content.

**What this method could miss:** a writer that mutates `File.ContentId` through a raw `Database.saveData` (no observer) in a platform source set. I grepped `Entity.create<File|Workout|SharedFile>` and `set(…ContentId` across all source sets and found only the sites above; the Android/iOS upload variants delegate to `UploadFileImplementation.uploadFile` (`AndroidFileUploadImplementation.kt:101-107`, `AndroidFileReplaceImplementation.kt:60-64`, `IosSourceFileImplementations.kt:56-58, 97-100`). There is no copy operation (`file-service/src/commonMain/kotlin/org/tenshu/core/file/system/` has none).

---

## 2. Audit (a): writer by writer

How a block is built today, which applies to every row writer below:

- Standalone: `SO:1665-1736`. Holder = `wireIds.of(targetEntityId)` (`SO:1704`), HLC = `hlcManager.tick()` at build (`SO:1702`), payload = the entity in hand including its value properties. Built on the async emit consumer (`SO:372-383`), not on the saving thread (`SO:468-469`).
- Batch: a save inside an **opened** task lane is staged as a `SyncDelta` with its own HLC (`SO:1276-1284`) and sealed into a `DeltaBatchPayload` block whose target hash is null (`SO:1353-1367`).
- Tombstone: `SO:1481-1550`. Holder = `resolved.wireName`, HLC = tick (`SO:1516`). The cascade goes only to an in-memory map (`SO:1528`).

The block builder is generic and today knows nothing about content. The existing lookup that would tell it is `onDemandPropertiesByReceiver()` (`SchemaDrivenContentWalk.kt:219-233`) plus `ExternalPropertyWriter.resolveContentId`.

### W1. Upload / replace a file → `File` row + `File.Data`

- **What:** `File` row with `File.ContentId`; bytes for `File.Data`. Module `:file-service-proxy`. `ReplaceFile` and the Android/iOS source variants delegate here (`ReplaceFileImplementation.kt:39-50`).
- **Content id:** fresh random per content write, `UF:310`, set on the entity `UF:360`. Never null for a new write. An unchanged overwrite keeps the old id and returns without a row write (`UF:228-237`).
- **At block build:** holder ✅, property ✅, contentId ✅ (in the entity in hand), HLC ✅. **Not in hand:** the ref set (chunks do not exist yet) and whether the bytes will ever land.
- **Delete:** `DF:131-135` one tombstone + cascade; after the push the drain calls `undeclareHolders` best-effort (`SS:1961-1976`, `SS:1893-1906`); candidates recorded (`DF:141-149`).
- **Overwrite:** new id; the emit's declare upserts `(holder, File.Data)` (`SO:2704-2718`); superseded id recorded (`UF:576-580`); parked intents retired (`UF:522-533`).
- **Move/rename:** W4. **Copy:** no such operation.
- **Verdict: clean**, with the two caveats in §2.1.

### W2. Thumbnail rungs minted at upload (five properties)

- **What:** `File.Thumbnail*` content under derived ids, via `ThumbnailClassMinter.store(freshContent = true)` (`UF:539-569`, `UF:659-670`, `ThumbnailClassMinter.kt:172-213`).
- **Content id:** `sha256("thumbnail:$fileContentId:$class")` (`FBW:243-244`). Null only when the file has no content id (`FBW:153-155`).
- **At block build:** the row's block is already queued before any rung is rendered or emitted. The five ids are **derivable** from `File.ContentId` at build time, but **whether a rung will exist is not row state**: a non-image has none, an over-cap render is declined (`ThumbnailClassMinter.kt:109-115`), MOTION exists only for video on a platform with a renderer (`ThumbnailClassMinter.kt:142-150`).
- **Overwrite today:** a rung's edge is replaced only if that rung is re-emitted. There is no per-property undeclare (`SqliteContentEdgeStore.kt:40-49` deletes by holder only), so an overwrite that mints fewer rungs leaves the old rungs' edges standing: a leak today. This is the "removals are explicit" case of §10.2 in existing code.
- **Verdict: nameable only by naming all six derived ids unconditionally** (see Q1). Precedent: the backfill already declares all six whether or not the rung exists, measured "14 of 55" edges for content that never existed (`ContentDeclarationBackfill.kt:235-246`).

### W3. Thumbnail rungs minted later, with no row write

- **What:** mint-on-view (`EnsureThumbnailClassImplementation.kt`), the backfill (`BackfillThumbnailClassesImplementation.kt:186`), both through `ThumbnailClassMinter.store(freshContent = false)` → `ensureContentOnRelay` (`ThumbnailClassMinter.kt:205-210`). Often a **different device** from the one that wrote the row, and arbitrarily later.
- **At push:** there is no push. This is a content-plane write with no metadata delta. Today its edge comes from the emit's own declare (`SO:2704`) or `declareResolvedHolder` (`SO:2042-2068`).
- **Verdict: cannot name an edge at push time, because it has none.** Works under B only if W1/W4's row blocks named the rung edges up front (Q1).

### W4. Move / rename (file, folder recursion, resume, deferred replay)

- **What:** new `File` row at the new path carrying every stored value property via `include(sourceProps)` (`MF:234-237`, `MF:404-412`), then `DataIdMigrator.migrate(..., successor = target)` (`MF:510-531`) → local delete of the old row + `emitRetiring` tombstone (`DataIdMigrator.kt:315`, `339-346`).
- **Content id:** unchanged, carried in the entity in hand. Null only if the source row had none.
- **At block build:** holder (new path) ✅, properties ✅, contentId ✅, HLC ✅. The tombstone names the old holder ✅.
- **Today:** the drain declares the successor per property under emit ownership, then undeclares the old holder; on failure it keeps the old edge (`SS:1929-1958`, `SuccessorDeclaration.kt:46-68`). A runtime with no declare wiring (web) keeps the old edge (`SS:1946-1950`).
- A folder move tombstones each descendant through its own recursive call (`MF:353-371`, `MF:486-531`), so no cascade is involved.
- **Verdict: clean.** The successor record becomes unnecessary, as §10.4 says.

### W5. Delete a file or folder → tombstone + cascade

- **What:** `DF:102` deletes the subtree locally, `DF:131-135` emits one tombstone with `cascadedEntityIds`.
- **At push:** root holder ✅, HLC ✅. **Cascade ❌ after a restart:** the names are resolved at emit (`SO:1571`) and recorded only in `TombstoneCascades`, which is in memory (`TombstoneCascades.kt:16-17`: "a process death before the push loses the names"). `DeleteBlock` has no field for them (`SO:1512-1520`), while the block itself is durable in `PENDING_TOMBSTONES` (`SO:1534-1543`). So a tombstone pushed after a process restart cannot name its cascade.
- **Verdict: gap.** Needs the cascade persisted with the pending tombstone (a `DeleteBlock` reshape or a sidecar keyed by `blockId`).

### W6. Row removals that emit nothing

Three sites delete `File` rows with no tombstone and no block. All go through `executionContext.deleteData` at the default origin, which stamps only an in-memory marker (`TR:283-287`, `TR:299-313`, map declared `TR:97`); `SyncObserver.onDataDeleted` is a no-op (`SO:1411-1415`).

| site | where |
|---|---|
| `DeletedFolderSubtreeReaper` | `FSPI:205-227` |
| `OrphanedFileSweeper` | `file-service-proxy/.../OrphanedFileSweeper.kt:151-165` |
| `DeleteFile`'s own recursion (covered by W5's cascade) | `DF:154-201` |

- **Verdict: gap, known in §10.2 for the reaper.** `OrphanedFileSweeper` is not named there and needs the same per-reap removal. See §3.4 for why the HLC of that removal is the hard part.
- `DeleteRealmData` is clean: it emits one tombstone per row (`realmdata/DeleteRealmDataImplementation.kt:191-198`), and the drain undeclares every pushed target (`SS:1961`).

### W7. Record a workout with streams → `Workout` row + `Workout.Streams`

- **Content id:** fresh random per call, minted before the save (`RW:84-88`). Null is impossible on this branch.
- **At block build:** holder ✅, property ✅, contentId ✅ (entity in hand), HLC ✅. Bytes follow (`RW:114-125`).
- **Delete:** `DeleteWorkoutImplementation.kt:60-75`, plain tombstone, drain undeclares. No reclamation candidate is recorded here; the enumeration sweep is what reaches it.
- **Overwrite with streams:** new id, edge upsert at emit. **Move/copy:** none (`DataId` is `workoutId`).
- **Verdict: clean.**

### W8. Record a workout WITHOUT streams over one that has them

- **What:** `RW:89-91` saves the bare `Workout` composite; `RecordImportedHealthImplementation.kt:39` does the same for every imported summary workout.
- **Effect on the row:** a save replaces the whole stored entry, and a bare composite carries an empty property map (`DbEntryMapper.kt:451-475`: `relations = emptyMap()` unless `data is Entity`). So the stored `Workout.ContentId` and `ReferenceRoot` are wiped, on the writer and (by whole-row LWW) on every device. **UNVERIFIED** below the mapper: I did not read `ObjectStore.storeObject`.
- **Content plane today:** nothing. No undeclare, no candidate. The old streams' edge stands: a leak.
- **At block build under B:** the block must carry `(Workout.Streams, none)`. That is derivable (schema says the type has the property, the payload in hand has no content id), but it means **every `Workout` block is a content-bearing delta**, including the thousands of summary rows a Fit import writes.
- **Verdict: nameable, but see Q5 (the wipe was not intended and is fixed generically) and W11 (these rows ride lanes today).**

### W9. Share a folder into a realm → `SharedFile` rows + cross-realm content copy

- **What:** `SharedFile` rows with the personal file's content id (`ShareFolderIntoRealmImplementation.kt:266-291`), then `shareContentToRealm` copies chunk blobs and PUTs a re-keyed manifest into the realm's store (`SO:2771-2861`).
- **Content id:** non-empty for files (the share is refused otherwise, `ShareFolderIntoRealmImplementation.kt:104`, `181-209`); empty for folders.
- **Content plane today:** `shareContentToRealm` declares **nothing**: there is no `declareContent` call in `SO:2771-2861`. The in-code note says the same (`SharedFile.kt:266-279`). The only path that would ever declare a `SharedFile` edge is `ContentRepair` (`ContentRepair.kt:113-118`).
- **At block build:** holder, property (a const), contentId (a composite field) and HLC are all in the entity in hand. But:
  1. `SharedFile.DATA_PROPERTY_ID` is not a registered relation, so `onDemandPropertiesByReceiver()` does not list it (`SchemaDrivenContentWalk.kt:219-233`); a schema-driven edge builder would emit nothing for `SharedFile`.
  2. The block goes to the shared realm's own queue and relay (`SO:1711-1720`), so B3's push change must cover the per-realm drain.
  3. Shared-realm tombstones have no correct push path at all (`SS:1855-1863`: "shared-realm tombstones have **no correct path today**"; `SO:1497-1509`), and I found no `SharedFile` delete operation (grep for delete/tombstone/unshare on `SharedFile` returned only `FileRelationMembership.kt:98`).
- **Verdict: gap.** Never declared today, invisible to the schema-driven lookup, and its edges could never be removed. Leak direction only.

### W10. Relation-slice snapshots

- `SliceSnapshotWriter.kt:85-88` PUTs one sealed blob per slice at a deterministic ref. No holder, no property, no content id, no declare. Never had an edge, so G4 never frees it.
- **Verdict: not an edge writer. Clean for B's loss direction**; it stays in the undescribed population the B0 reclaim must exclude (`sync-relay-server/.../mailbox/ContentInventory.kt:22`).

### W11. Rows written inside a task lane (batch blocks)

- A save tagged with an opened lane is staged, not emitted (`SO:1230-1257`, `SO:1259-1303`), and sealed into a null-target batch (`SO:1353-1367`).
- Users today: Drive bulk upload (`drive-shared-logic/.../TenshuFilesRepository.kt:428`), Fit import (`fit-shared-data/.../FitProjectionRepository.kt:1088`), Takeout photo import (`timeline-shared-data/.../TakeoutPhotoImporter.kt:82`), timeline file import (`FileTimelineImporter.kt:131`). So `File` and `Workout` rows do ride batches.
- Each delta has holder hash, property and its own HLC in hand (`SO:1277-1284`), so option (ii) is mechanically a new fallback beside the existing ones at `SO:1271-1274`.
- **Verdict: gap until diverted.** Cost fact for the maintainer: for a Drive bulk upload the `File` rows are the bulk of the lane's deltas, and for a Fit import every `Workout` row is content-bearing by type (W8), so option (ii) removes most of what those two lanes batch.

### W12. Content re-emits with no row write

`ContentRepair` (`ContentRepair.kt:71-103` → `SO:1866-1869`), the deferred-content drain (`SO:620-690`, `715-865`), `ensureContentOnRelay`'s forced branch (`SO:1991-1995`). All are content-plane writes under an **existing** content id with no metadata block. Under B they add no edge and need none, but they are also the only way bytes come back after the relay lost them, and under G3 "a chunk PUT alone never clears anything". **Verdict: not an edge writer; B4 must decide what these do when the content is in the freed state.**

### W13. Legacy entity-keyed emit (null content id)

- `SO:2730-2762`: no content id ⇒ chunks on the content plane, manifest as a mailbox block, no edge.
- No current writer reaches it for new content: W1 and W7 always mint, W9 refuses an empty id, W2/W3 take the file's id as a parameter. It is reached only for rows that predate content ids (`drainLegacy`, `SO:880-916`) or when `resolveContentId` fails transiently and swallows the failure (`FBW:191-193`, noted at `SO:730-731`).
- **Verdict: cannot be named, by definition.** Leak only (G4).

### W14. Rows materialised locally from someone else's data (never pushed)

- Slice rows: `FileSliceSnapshotProducer.kt:147-166`, saved with `SYNC_INGEST`, create-only, no `SyncMetadata`, content id copied from the snapshot.
- Folder-embedded members: a child row absent locally is created from the parent block's embedded member, as a bare composite with **no value properties and no HLC** (`DbEntryMapper.kt:598-636`).
- Neither is pushed, so neither needs an edge. They matter for (b), §3.5.

### 2.1 What "clean" still needs in B3

- **Derive the edge from the payload in hand, not through the port.** `resolveContentId` reads the **stored** row (`FBW:157`, `FBW:190`; `WorkoutStreamsPropertyWriter.kt:78-80`), and blocks are built on an async consumer, so the stored row can be a later save, or already retired by a move. The port takes a `DataId`, not the data, so this needs a new port method or a schema-declared "content id lives here" binding. ⭐ **Decided: the binding**, as `ContentReferencing`'s `CONTENT_ID` plus per-property derivations, and a pure `contentEdgesOf(schema, payload)` (quorumless §10.11, B3a).
- **Every block of a content-bearing type must carry the complete set**, including `none`. Rows are replaced whole (§3.1), so a block that omits a property means the row no longer has it.
- **Saves made while the realm is locked** are held in an in-memory channel and re-emitted at unlock with a fresh HLC (`SO:464-467`, `SO:495-499`). The edge is nameable then; a process death before unlock loses the block (pre-existing, stated at `SO:460-463`).

### 2.2 Writers that cannot name their edge at push time today

| writer | what is missing | what it needs |
|---|---|---|
| W5 delete cascade | the cascade list after a restart (memory only) | persist it with the pending tombstone |
| W6 reaper, `OrphanedFileSweeper` | any block at all; and an HLC a later recreate can observe | a relay-visible removal per reap, with the ordering fix of §10.10 |
| W3 late thumbnail mint | any block at all | the row's block naming all six derived ids up front (Q1) |
| W9 `SharedFile` | a registered property, a declare, a per-realm push with edges, a tombstone path | a scoping decision first (Q6) |
| W11 lane deltas | per-delta edges on a null-target batch | divert content-bearing types to standalone (option ii) |
| W13 legacy rows | a content id | nothing; G4 leak, owned by the heal |

---

## 3. Audit (b): convergence

### 3.1 The mechanism, for all three content-bearing types

- `File`, `SharedFile` and `Workout` are all `Syncable(SyncStrategy.Eager)` with the default `ConflictStrategy.LastWriterWins` (`File.kt:60-62`, `SharedFile.kt:157-159`, `Workout.kt:116-118`; default at `core/src/commonMain/kotlin/org/tenshu/core/trait/Syncable.kt:146-148`).
- The merge branch runs only for `ConflictStrategy.Merge` (`SP:701-714`); everything else is a whole-row overwrite (`SP:715-718`).
- No `SemanticMerger` is registered for any of the three. The eleven mergers are `Folder`, `DailyActivity`, `Event`, `ReminderState`, `Moment`, `Trip`, `Place`, `PlaceMerge`, `PlaceNetworkFingerprint`, `PlaceRole`, `RealmSchemaEpoch` (type ids via `override val typeId` in each; registrations at `PlatformProxyModule.kt` android `359-367`, `TimelineProxyInitialization.kt:44-45`, `PlaceProxyInitialization.kt:62-69`, `SyncProxyInitialization.kt:438`). The trait-based design in `sync-merge-strategies.md` is "Spec'd; not started", so there is no second merge mechanism.
- The content id is part of the row (§1.1), and the row is replaced whole, on ingest (`SP:717`) and on local save (`DbEntryMapper.kt:451-475`).

⇒ **For every content-bearing property, two devices' concurrent writes resolve by whole-entity LWW on the block's HLC. No merger can produce a value that is neither side's, and none keeps the lower-HLC side.** The "re-emit a higher-HLC block" requirement of G1 is not triggered by any content-bearing type today.

`Folder` is the one `Merge` type that shares the `File` DataId space. `FolderMerger` rebuilds the row with three value properties and never a content id (`FolderMerger.kt:46-60`), and it runs only when the incoming block is newer (`SP:700`), so a file-vs-folder collision at one path still resolves in HLC order.

### 3.2 What the LWW comparison actually compares (three caveats)

1. **It compares against the last INGESTED write only.** `isNewer = existingMetadata == null || hlc > existingMetadata.hlc` (`SP:686-689`), and `SyncMetadata` is written only on ingest (`SP:722-730`). A device's own un-echoed local write is invisible to it, unlike the tombstone path, which also reads `LocalWriteMetadata` (`SP:256-263`). So an older block from a peer **overwrites a newer local write** on that device.
2. **Convergence then depends on the device re-ingesting its own block.** The relay serves every block of the owner past the cursor with no device filter (`sync-relay-server/.../storage/SqliteSyncMailbox.kt:77`, `180-184`), and I found no client-side own-device filter (grep for `hlc.deviceId` in `sync-service-proxy` shows only `DeviceLastSeenRecorder.kt:63`). The code comments rely on this echo (`SP:238-246`, `TR:86-88`). With the echo, both devices end on the higher-HLC row. **UNVERIFIED by test**; I did not find or run one that pins it.
3. **A row that is absent locally accepts any non-tombstoned block, whatever its HLC** (`SP:700`: `existingData == null || isNewer`), and the save then lowers `SyncMetadata` to that block's HLC.

With one exception, none of these makes clients converge on X while the highest pushed edge names Y, as long as every device eventually ingests every block. They do mean the intermediate state on one device can be the lower-HLC row.

⚠⚠ **The exception is caveat 3 after a reap**. A subtree reaper deletes a child; its marker is in memory only (§3.4). The process exits before a lower-HLC block for that child is pulled. After the restart the marker is gone, the row is absent, and `existingData == null` accepts the stale block. Under B the reap's removal (or the folder tombstone's cascade) still holds the higher HLC at the relay, so the block's edge is rejected there: **the client shows a row whose content has no live edge, and G2 frees its bytes after T.** This is a final state, not an intermediate one, and it is a loss. B3 has to persist a per-child deletion watermark and apply it on ingest, rather than rely on the in-memory marker.

### 3.3 The local stamps are not the block's HLC

A local save ticks once for `LocalWriteMetadata` on the saving thread (`TR:188-191`) and again, later, for the block on the emit consumer (`SO:1702`). The relay will order by the block's HLC; the saving device's own tombstone guard uses the earlier stamp (`SP:257-263`). The two can be far apart when the realm was locked (`SO:495-499`). A tombstone that falls between them deletes the row locally while peers and the relay keep it; the echo then restores it locally (absent row ⇒ applied, `SP:700`). Consistent in the end, noted because B's registers and the client's local guard are stamped differently.

### 3.4 The §10.10 case: does the code still match? Yes, and it is wider

What §10.10 says, checked line by line:

| claim | code |
|---|---|
| `recordLocalDelete` keeps the removal in a local map | `TR:97` (map), `TR:299-313` (write). In memory only; lost on restart (`TR:93-95`) |
| `onDataDeleted` emits no block | `SO:1411-1415` |
| so a device recreating the child never observes the reap's HLC | a peer's clock advances only on ingested blocks and tombstones (`SP:183-186`, `214`, `283`) |
| clients keep a lower-HLC recreate | a peer has no tombstone for the child, so `SP:662-663` passes and the row is saved |

Additional facts the finding does not state:

1. **The reap's HLC is "now on the reaping device"**, taken as `max(tick, SyncMetadata.hlc)` (`TR:305-307`, wired to `tick()` at `SyncProxyInitialization.kt:468-472`). It is not related to the folder tombstone's HLC.
2. **No clock skew is needed.** Blocks and tombstones are pulled on separate cursors (`SS:2282-2286`; within one batch blocks are applied first, `SS:2201-2202`, `2460-2461`). A device that comes back late and applies the folder's tombstone before the block lane has delivered a legitimate, already-pushed recreate of a child reaps that child with a fresh HLC far above the recreate's. Under B its removal would beat the recreate's edge at the relay while the other devices hold the live row.
3. **The same device already diverges today.** For the rest of that session it drops the recreate block (`SP:662-663`, because `TR:358-368` includes the local marker), and its cursor moves past it. After a restart the marker is gone and the same block would be accepted. So "clients keep the row" is true of the other devices, not of the reaper.
4. **§10.2's "a delayed child write lands, is reaped by the next device that ingests it" is not what the code does.** `DeletedFolderSubtreeReaper` fires only on a folder row's deletion (`FSPI:205-216`). A child block arriving after the folder is gone is simply saved (no tombstone names it). Its only collector is `OrphanedFileSweeper`, which is latched `SWEEP_COMPLETE` unless re-armed (`OrphanedFileSweeper.kt:117-129`, re-arm at `FSPI:126-135`, `258`). That direction is a leak (row and edge both stay), not a loss.

### 3.5 Other paths where a device's row and the pushed history can disagree

| path | effect | direction under B |
|---|---|---|
| Summary re-record of a workout (W8) | every device converges on "no content id"; the old edge is never cleared today | leak unless the block carries `(Streams, none)`; then consistent |
| Slice-snapshot rows (W14) | a joiner holds a row naming the content id as of the snapshot's `asOfSeq`, with no `SyncMetadata`; it loses to the first real block (`FileSliceSnapshotProducer.kt:119-131`) | read-side only, unless the joiner **moves** that row before catching up: the move pushes a new holder naming the stale id at a fresh HLC, and every client then follows it. G3 refuses it if the id is already freed. **UNVERIFIED** that nothing blocks a move on a provisional row |
| Folder-embedded placeholder rows (W14) | a `File` row with no content id and no HLC (`DbEntryMapper.kt:630-636`); a folder move over it would push a new holder with `none` and tombstone the old holder | the content would lose its last edge. Rare: every current folder writer saves `files = emptyList()` (`MF:327-331`, `CreateFolderImplementation.kt:75`, `FSPI:103-105`), the resume path being the exception (`MF:350`). **UNVERIFIED** end to end |
| Owed-tombstone replay after unlock | emits the old key's tombstone with a fresh HLC, guarded only by a **local** existence check (`DataIdMigrator.kt:239-250`); a peer's recreate at that path with a lower HLC is deleted everywhere | clients and relay agree (both follow the tombstone); B then frees the bytes after T where today they linger |
| Save while locked, then process death | the row exists only on that device (`SO:460-467`); its content upload is journalled and still happens (`SO:1915`) | content with no edge ever: G4 leak |

### 3.6 Side observation (not a B blocker)

`DataIdMigrator.retire` sweeps `SyncMetadata` by the **local** hash (`DataIdMigrator.kt:297`, `306-307`, `395-402`), while `SyncMetadata` is keyed by the wire name (`SP:657`, `680-684`; wire name is an HMAC, `WireEntityId.kt:75-76`). The sweep therefore looks like a no-op since EI-1. I found no path where the leftover row breaks convergence (an absent row ignores it, `SP:700`), so I list it only for completeness. **UNVERIFIED.**

---

## 4. Summary by category

### (i) Confirmed clean

- W1 upload/replace, W4 move/rename, W7 workout with streams, W9's row shape, `DeleteWorkout`, `DeleteRealmData`: holder, property, content id and HLC are all in hand when the block or tombstone is built.
- New content never takes the null-content-id branch (W13).
- All three content-bearing types are whole-entity LWW with no semantic merger (§3.1).
- The relay can use the client's comparison as shared code: `HlcStamp` / `compareHlc` already exist for that purpose (`core-common/.../util/HybridLogicalClockUtil.kt:14-40`).
- `content_edge` today has `declared_at` and no HLC (`SqliteContentEdgeStore.kt:22-28`), matching B3's "every existing row starts UNSTAMPED".

### (ii) Confirmed gaps

1. Row-before-bytes ordering contradicts the design's "presence check" wording (§0.1).
2. The delete cascade is memory-only and is not on the tombstone (W5).
3. Reaper removals: code matches §10.10, no skew needed, and the reaping device already diverges (§3.4).
4. `OrphanedFileSweeper` is an un-tombstoned remover the design does not name (W6).
5. `SharedFile` content is never declared, is invisible to the schema-driven lookup, and has no tombstone path (W9).
6. Lane batches carry `File` and `Workout` rows today (W11).
7. Thumbnail rungs minted later have no push; an overwrite that mints fewer rungs leaves stale rung edges today (W2, W3).
8. A summary workout save wipes `Workout.ContentId` with nothing told to the content plane (W8).
9. The edge must be derived from the payload in hand; the existing port reads the stored row (§2.1).
10. Client LWW ignores un-echoed local writes and relies on the self-echo (§3.2).
11. **A reaped child comes back after a restart, without its edge** (§3.2, the exception): the reap marker is in memory, and an absent row accepts any block. The one confirmed path here where clients and the relay's registers end in disagreement, in the loss direction.

### (iii) Open questions for the maintainer

- **Q1.** May a `File` row's block name all six edges unconditionally (file id plus five derived rung ids), including rungs that will never be minted? It is the only way W3 is covered without a new push type, and the backfill already does it.
  - ⭐ **Answered 2026-10-01 (maintainer): yes, and for every type, not only thumbnails.** Every content a row references must be freeable, so the edge set becomes a schema declaration: `ContentReferencing` is repurposed to carry a `CONTENT_ID` requirement and, per OnDemand property, `Primary` / `Derived` / `regenerable`; one pure `contentEdgesOf(schema, payload)` returns the complete set, `none` included, from the payload in hand. Regenerable rungs never trigger `content_freed` and are never part of a REVIVE's check. `Sliceable` stays separate, with two small items for provisional snapshot rows and dead snapshot blobs. Full design and constraints: `content-plane-cr2-quorumless.md` §10.11; it lands as phase B3a, before B3. It also closes §2.1's first bullet, the W2 overwrite leak, and gives W8 its `(Streams, none)`.
- **Q2.** Confirm G3 means "refuse only content marked freed". A bytes-presence check would refuse almost every upload's row (§0.1). What state does content have between "edge exists" and "first described"?
  - ⭐ **Answered 2026-10-01 (maintainer):** yes, freed only. The states are PENDING (edge, not described), LIVE, UNREFERENCED, FREED, and a free marks the id freed even with no refs. See `content-plane-cr2-quorumless.md` §10.12.
- **Q3.** Where does the ref set (`content_ref`) come from under B? It cannot ride the row's push (chunks do not exist yet). Today it rides the edge form's `refs` or the `contents` form, which requires a `referenceId` (`ContentRouting.kt:426-428`), and B6 retires handles.
  - ⭐ **Answered 2026-10-01 (maintainer):** the `contents` describe, sent after the chunks land; `referenceId` becomes optional in B3; on iOS it waits for the background upload's completion. See `content-plane-cr2-quorumless.md` §10.12.
- **Q4.** For the reap removal: which ordering token? The reap's own HLC is unusable as built (§3.4, points 1-2).
  - ⭐ **Answered 2026-10-01 (maintainer):** a conditional removal at h = max(folder tombstone HLC, the reaped row's last known HLC). The client persists the same h as a deletion watermark, which also closes gap 11. ⚠⚠ **Revised 2026-10-02, ⭐ accepted the same day and built in B3b:** h differs per device, so that rule lets devices and the relay disagree. The proposal is the folder tombstone's HLC T as the one ordering point everywhere, with the reaper keeping descendants newer than T (quorumless §10.12). See `content-plane-cr2-quorumless.md` §10.12.
- **Q5.** Is wiping `Workout.ContentId` on a summary re-record intended? If not, that is a health-service fix independent of B.
  - ⭐ **Answered 2026-10-02 (maintainer), built:** not intended. A bare composite never clears content: `ExecutionContext.saveData` keeps the stored row's content-id and reference-root slots across a non-ingest save of a bare composite whose type declares content (same type only; an `Entity` save still replaces). Generic over every content-referencing type, not a health-service fix. See `content-plane-cr2-quorumless.md` §10.12 Q5.
- **Q6.** Is shared-realm content in B's scope, or explicitly left as a G4 population until `SharedFile` has a registered property and a tombstone path?
  - ⭐ **Answered 2026-10-01 (maintainer):** out of scope, as a G4 population. `SharedFile` emits no edges, and B5's arming seed must not reach shared copies. See `content-plane-cr2-quorumless.md` §10.12.
- **Q7.** Is "content-bearing delta" decided per type or per row? Per type, option (ii) removes most of what the Drive-upload and Fit-import lanes batch (W11).
  - ⭐ **Answered 2026-10-01 (maintainer):** per row, by the payload. Naming a content id ⇒ standalone; naming none ⇒ may batch. See `content-plane-cr2-quorumless.md` §10.12.
- **Q8.** Should B3 add a test that pins the self-echo convergence of §3.2, since the registers assume clients end on the highest pushed HLC?
  - ⭐ **Answered 2026-10-01 (maintainer):** yes, plus a test for gap 11. Variant (b), fixing the cause, is recorded as a follow-up in quorumless §10.13. See `content-plane-cr2-quorumless.md` §10.12.

---

## 5. Facts that bear on T (no recommendation; decided 2026-10-02 as 24 h, quorumless §10.14)

- **References can sit unpushed for an unbounded time.** Deferred moves are parked durably until the device is back online (`PendingMoveStore.kt`, `MF:95-97`); pushes are refused and retried while the relay answers `402` (`SS:212-217`, `tickWriteRefused` at `SS:421`); a locked realm holds saves in memory (`SO:464-467`).
- **Uploads lag their row without bound** (metered gate, `507`, lock: `SO:1921`, `1935`, `2316`). This does not consume T, because the row's edge is already at the relay, but it means "content with an edge and no bytes" is a normal long-lived state.
- **Only devices holding plaintext can revive.** Other devices are designed to drop plaintext one hour after applying a tombstone (`OrphanedBytesPolicy.DEFAULT_SETTLE_MS`, `file-service/.../sync/OrphanedBytesPolicy.kt:32`).
- **Existing windows on the relay:** tombstone-body retention 30 days (`RelayConfig.kt:312`), account-closure grace 30 days (`RelayConfig.kt:319`), enumeration minimum blob age 24 h (`ContentEnumerationAgeWindow.kt:54`), content time-sweep disabled (`RelayConfig.kt:348`), sweep cadence 24 h (`RelayConfig.kt:366`).
- **A late device's reaper is the T-independent hazard** (§3.4): its removal is stamped when it comes back, however long that was.
- **Slice snapshots are refreshed at most on the idle+charging maintenance path** (`SliceSnapshotWriter.kt:20-22`), so a joiner's provisional rows can name content ids older than any T.

---

## 6. What I did not check

- Nothing was compiled or run. No test was executed, including any that might already pin §3.2.
- `BinarySerializer`'s entity encoding and `ObjectStore.storeObject` (I read the parser and the mapper around them).
- The scheduler's pull and drain beyond the cited lines; the lane-cursor behaviour in §3.4 point 2 rests on `SS:2201-2202`, `2282-2286`, `2460-2461` and the comment at `SP:226-229`.
- The relay's `/sync/content/declare` body beyond `ContentRouting.kt:396-470`, and the reclaim/delete routes.
- iOS route-1 runtimes in `tenshu-sdk-ios` (I did not confirm which `ExternalPropertyWriter` they register), Swift code, the TypeScript web app, `:hub`, and the EPC/third-party path.
- `MoveFencePreflight`, `ReplayPendingMovesImplementation` and `OfflineKeeperImplementation` beyond their call sites.
- `sync-merge-strategies.md` and ADR 0024 were skimmed for status only.
- (c), choosing T, by instruction.
