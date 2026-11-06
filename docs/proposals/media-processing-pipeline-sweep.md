# Media processing after upload — an optimisation sweep

**Status:** v0.13 — **living doc: D1–D14 decided; P0f-a merged (`18e510e84`); P0f-b designed in
§6.4, its five decisions P0fb-D1–D5 DECIDED by the maintainer** (2026-10-03, §6.4.9); §6.3's findings
1 and 3 are closed in design there, finding 2 stays P10's. **Merged as a living doc 2026-10-04**: four review findings on S2/S3/S4 are open as hard constraints on PR-3–PR-5 (§6.4.11). On 2026-10-02
the maintainer accepted D1–D12 as written and decided D13 and D14 (§5). Dispatched to cloud sessions (§6):
P0, P1a, P3, P4 and P0f-a. Two of them are open PRs that this doc was read against: P0f-a (its first build,
since superseded by a second — the P0f-a row's as-built note) and P4. §6 says where the code and the design differ. P4's retry
mechanism was decided the same day: option B, as built in P4 (§6.2). No decision is open: P0f-b's
five were decided on 2026-10-03 (§6.4.9).

⚠⚠ **F0 is a privacy defect live on master today.** A deleted photo's face template is never
removed (§3). P0f-a's mechanisms merged inert: until P0f-b's PR-1, nothing a user can observe has
changed (§6.4.0). Phases **P0f-a** (☁) and **P0f-b** (📱 smoke) ship before everything else.

Revision history:
- **v0.1** (2026-10-01): the sweep and the proposal.
- **v0.2:** decisions recorded. P9 gains hard constraints C1/C2 (§4.4).
- **v0.3:** F0 and P0f added. P10 extended to synced labels and to faces.
- **v0.4:** P0f split by HR 8. Every phase's HR 8 mark re-checked.
- **v0.5:**
  - ⚠ P4's retry completion uses **process-local generations**. v0.4's `RUNNING`/`DataId` argument
    was wrong: `ScheduleItem`'s id is its dedup key alone.
  - The reconcile runs only on a typed **complete** walk.
  - User labels are deleted with their file on permanent delete (D13 amended).
  - New finding F12, with fix phase P0g.
- **v0.6:** the reconcile covers vectorless visits and refuses a silently short walk; P4's
  generation is a non-suspending atomic, bumped before the row write; a typed name does not survive
  a permanent delete in any row or test; P4's build and P0f-a's first build read against the design.
- **v0.7:** P4 moves to its own section with its two retry designs side by side; the
  schedule write must be one non-destructive call, because `Database.transaction` has no rollback;
  typed names are deleted by the purge hook only, through a `TrashOrigins` seam wired in Control.
- **v0.8:** §6.1 is one table of every way a path stops resolving and what happens to each kind
  of derived row. The purge hook receives every leaf of a folder delete; store removal is one
  dead-path observer that also covers peers and moves; typed names on a move are OPEN. P4's retry
  mechanism is **decided: option B** (`ScheduledRunDeferred`, P4's build); option A is kept only as a
  rejected alternative, and §6.2 lists what P4 owes before it merges.
- **v0.9:** a label delete reaches peers only through an emitted tombstone: the purge and replace
  hooks emit, the pass-time backstop deletes locally and never emits (§6.1). P11 forces a video
  through its poster and refuses a poster under 512 px.
- **v0.10:** **D14 decided: typed names follow a move.** A new move hook S4 re-keys them and
  tombstones the old path's rows; `TrashOrigins` is dropped, and the purge hook acts on the path it
  is handed. Rows 3–7 and 11 of the event table changed.
- **v0.11:** §6.2 reconciled with P4's build at `7d8c1fc58`, where the guard is now built. Two of this
  doc's guard rules were wrong: an in-place save is **not** atomic (a row is several store
  writes), and the tick does **not** read generation and row in one transaction (it would enqueue
  a commit barrier at 1 Hz). On Android the retry interval grows 15, 30, 45 … min, by design.
- **v0.12:** merged as a **living doc** (maintainer, 2026-10-02) after eleven review rounds. The
  three findings open at merge are in §6.3, each with the phase that must close it; later findings
  are taken in the phase PRs, and this doc is amended with them.
- **v0.12, amended with P4 (review round 2):** §6.2 *Timing after a stand-down* rewritten — a
  stood-down run now leaves no timing behind, which closes both inferred items v0.11 listed there
  and changes one behaviour (a periodic key re-registered during its run fires once more at once)
  — and *Stopping* added. **Round 3:** *Stopping* rewritten around one invariant (a run is a
  child of the supervisor `stop()` cancels); the round-2 stop count is gone. ⚠ Recorded there: a
  cancelled run abandons its op, it does not cancel it.
- **v0.12, amended with P0f-a as built:** the reconcile removes by **striking and
  scrubbing records in place**, not by rewriting the log, so there is no staged copy and no
  recovery protocol; a path is dropped only on **two proofs** (absent from a complete walk, and no
  live row on a point read), because a walk is not a snapshot; a folder list that is missing or
  names more members than resolve makes the walk incomplete. §6's P0f-a and P0f-b rows and §6.1's
  B1 row changed.
- **v0.13 (2026-10-03): P0f-b designed (§6.4), read against `18e510e84`.** Code findings that change
  the plan: the reconcile is inert in production too (Control's source never reports a complete
  walk), so P0f-b's first PR wires it; Photos' Things and People already drop dead and trashed
  labels by joining against the feed, and only `searchByLabel` leaks; A40 contradicts D13/D14 in two
  sentences; a label tombstone shows its entity type to the relay; on iOS the inference logs are one
  App-Group file, so S1 is not wired there. §6.3 finding 1 closed by collecting the leaf set before
  the first mutation and purging in a `finally` (plus a durable intent, P0fb-D1); finding 3 by an
  operation-scoped `MoveScope`. P0f-b is five PRs, and **S2 and S4 no longer ship in one PR** — S2
  first (§6.4.8).
- **v0.13, amended with the P0f-b design:**
  - **The maintainer decided P0fb-D1–D5 (§6.4.9).** D4 is re-scoped: every row delete already
    exposes its type to the relay, so that is the relay-metadata track and not a P0f-b cost.
  - **Codex round 4 (one P1, valid): no version at all.** A row's version lives only in
    `SyncMetadata` and `LocalWriteMetadata`, and both are keyed by the HMAC wire id. So it cannot
    be read without the realm key, from the store or from the emitter: a cold-start capture could
    only have recorded "unknown", and the replay would have skipped and cleared it.
    - Rule (b) is now "delete locally at the event, under the store lock, labels before the
      covered file write; owe only tombstones by `DataId`; a row that exists at emit time was
      written after the event and is skipped". This is `DataIdMigrator`'s phase 2.
    - The capture needs no realm key.
    - `lastWriteOf` is dropped.
    - A cold-start capture → replay-after-init test is in PR-3, PR-4 and PR-5.
  - **Codex round 3 (two P1s and a P2, all valid): one rule instead of three patches** (§6.4.3,
    *The retirement-intent rule*). Every label retirement — S2, S3 and S4 alike — is a durable
    intent that is bound to its realm, version-checked and written before the first mutation:
    - **The owner check is moved inside the emitter.** It becomes a new
      `emitAll(…, expectedOwnerId)`, checked on the same resolved snapshot that names the rows,
      and intents sit in owner-named directories (the `DataIdMigrator` structure). That closes
      adoption between check and emit.
    - **Each row carries its captured version** (superseded in round 4, below). That is the HLC
      `processDeleteBlock` compares, exposed as `lastWriteOf`, and it is re-checked, emitted and
      deleted in one `database.transaction`. That closes a fresh row reusing the recorded key.
    - **S3 and S4 get the same durable intent**, closing the lost `RETRY`.
    - Round 1's "discard a foreign intent" is replaced by the namespacing.
  - **Codex round 2 (two P1s, both valid), folded into §6.4.3:**
    - the intent's owner comes from `SyncManager.residentOwnerId`, the persisted slot, never from
      the emitter (which is `null` during cold start and would read as "no realm"); an unknown
      owner writes no intent;
    - the intent records each leaf's `(contentId, createdAt)` and the label keys found before the
      first mutation, so a path reused before the replay neither escapes the purge nor loses the
      new photo's labels.
  - **PIO-4 slice 4 merged:** the `searchByLabel` trash filter is on master; PR-1 and PR-4 no longer
    depend on it.
  - **Codex round 1 (three P1s, all valid), folded into §6.4.3:**
    - the purge intent records its realm's `residentOwnerId`, and an intent from another realm is
      discarded;
    - the hook returns `COMPLETE`/`RETRY`, and only `COMPLETE` clears the intent;
    - the replay is a maintenance hook, so it also runs on a device whose sync never enables.

Every review round's findings are written into the phase rows as hard constraints.

This doc maps what happens to a photo or video after upload on Android, desktop and iOS route 1:
thumbnail and preview rungs, the video poster and MOTION clip, metadata, the content emit, the
thumbnail backfill and the AI index pass (vectors, faces, labels). It ranks the findings and sets a
target shape. The phases in §6 are cut so that cloud agents (no emulator, no device) can take the ☁
ones. v0.1 (2026-10-01) was the sweep and the proposal.

⚠⚠ **Headline.** Every photo upload decodes the full-resolution original **four times**, up to twelve
when the JPEG quality step-down fires, with no subsampling. This runs inline, before the row is
saved, on `Dispatchers.Default` inside Control, and the next file waits for all of it plus this
file's network upload.

The index pass then reads the original whole again and decodes it twice more (vectors, faces). Its
scheduled path is effectively dead on Android:
- the in-process loop defers once and waits 12 h;
- the WorkManager job needs idle + charging + screen-off and, it turns out, a network connection.

Nothing in the pipeline has a CPU budget, a thermal backoff or a thread cap.

**Scope.** Post-upload media processing only. ⚠ It is **not** the place for model choice
(`inference-service.md`), face policy (`photos-people-and-pets.md`), rung sizes
(`drive-xxlarge-thumbnail-rung.md`) or iOS Photos UI (`photos-ios.md`). This doc defers to those and
proposes only the plumbing between them.

**Evidence tags.** Every claim carries one:
- **[M]** measured in this sweep;
- **[C]** read in code at the cited line;
- **[D]** a claim made in another doc;
- **[I]** an inference — reasoned, not run.

An [I] that a later PR depends on is listed as a verification step in §6.

**Path abbreviations.**

| Short | Path |
|---|---|
| `UFI` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/UploadFileImplementation.kt` |
| `TCM` | `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/ThumbnailClassMinter.kt` |
| `AGP` | `file-service-proxy/src/androidMain/kotlin/org/tenshu/core/common/proxy/file/AndroidGetPreviewImplementation.kt` |
| `JGP` | `file-service-proxy/src/jvmMain/kotlin/org/tenshu/file/proxy/jvm/JvmGetPreviewImplementation.kt` |
| `IGP` | `file-service-proxy/src/iosMain/kotlin/org/tenshu/file/proxy/ios/IosGetPreviewImplementation.kt` |
| `AFU` | `file-service-proxy/src/androidMain/kotlin/org/tenshu/core/common/proxy/file/AndroidFileUploadImplementation.kt` |
| `MI` | `inference-service-proxy/src/commonMain/kotlin/org/tenshu/inference/proxy/MediaIndexer.kt` |
| `MIR` | `inference-service-proxy/src/commonMain/kotlin/org/tenshu/inference/proxy/MediaIndexRunner.kt` |
| `MPF` | `inference-service-proxy/src/androidMain/kotlin/org/tenshu/inference/proxy/onnx/MediaPipeFaceBackend.kt` |
| `SDL` | `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/ScheduleDrainLoop.kt` |
| `WMS` | `sync-service-proxy/src/androidMain/kotlin/org/tenshu/sync/proxy/WorkManagerScheduler.kt` |
| `SFC` | `app-ingest/src/commonMain/kotlin/app/tenshu/ingest/backup/SyncedFolderController.kt` |
| `PPM-A` / `PPM-D` / `PPM-I` | `control-shared-proxy/src/{androidMain,desktopMain,iosMain}/kotlin/app/tenshu/control/di/PlatformProxyModule.kt` |

---

## 1. The pipeline in one picture (as-is)

```
 Drive app (Android) / FolderWatcher (desktop) / Drive app in-process (iOS route 1)
   discover ─► [dedup hash: full read] ─► hand file to Control
                                              │  (Android: binder thread parked in runBlocking for the whole op)
 Control proxy, Dispatchers.Default, ONE FILE AT A TIME ──────────────────────────────────────────┐
   read whole file ─► hash ─► quota ─► local write ─► 4× { GetData re-read ─► SHA-256 ─►          │
   FULL decode ─► scale ─► JPEG (×3 on step-down) } ─► EXIF parse ─► row save ─► journal ─►      │
   chunk+encrypt+PUT inline (N=4 chunks) ─► emit 4 rung blobs ─► [video, Android: MOTION clip]   │
 ◄── op returns; the NEXT file starts ────────────────────────────────────────────────────────────┘

 Later, separately (no hook from upload):
   thumbnail backfill   24 h, WM: unmetered+charging+idle+batt   XLARGE/XXLARGE/MOTION only, serial, 250 files/run
   media index pass     12 h, WM: charging+idle+batt+CONNECTED   whole-library walk, serial, per photo:
                        read original whole (else XLARGE/XXLARGE preview) ─► decode #1 → SigLIP 256
                                                                          ─► decode #2 → faces (5 BlazeFace runs + N landmark/embeds)
   face clustering      on People open (FaceGroups op), not in the pass
```

---

## 2. As-is — stage by stage

### 2.1 The stage table

| # | Stage | Trigger | Gate | Thread / dispatcher | Concurrency | Input | Android | Desktop | iOS route 1 |
|---|---|---|---|---|---|---|---|---|---|
| S0 | Discovery | **Android:** `ContentObserver` on MediaStore Images+Video → conflated channel (`AndroidMediaSourceProvider.kt:42-59`, `SFC:117,191-215`); Drive `BackupWorker` 6 h + content-URI trigger (`drive-shared-ui/.../BackupWorker.kt:84-126`). **Desktop:** Control `FolderWatcher` polls every 30 s (`control-shared/src/desktopMain/.../FolderWatcher.kt:52-69`). **iOS:** no change observer (`IosMediaSourceProvider.kt:160` `emptyFlow()`); *Back up now*, `BGAppRefreshTask`, settings changes (`drive-ios/Sources/BackgroundSync.swift:12-53`) | **Android:** enabled / paused / storage / network / charging-only (`SFC:970-988`). **Desktop:** none (`FolderWatcher.kt:42-43`). **iOS:** as Android | **Android:** Drive process, `Default` scope (`SFC:91-92`), MediaStore query on IO. **Desktop:** IO scope. **iOS:** `Default` | One drain at a time (`drainMutex`, `SFC:125,397`) | MediaStore / dir listing / PhotoKit | [C] | [C] | [C] |
| S1 | Dedup hash (optional) | Per candidate | — | Drive, IO | serial | **full stream read** (Android, 2 MiB folds, `AndroidFilesRepositoryPlatformDelegate.kt:98-104`); **full `readBytes`** on iOS (`IosFilesRepositoryPlatformDelegate.kt:108-124`) | [C] | n/a | [C] |
| S2 | Hand-off + read | Per item, awaited (`SFC:606,686`) | Gate re-read per item (`SFC:612-629`) | **Android:** binder thread parked in `runBlocking` for the whole op (`control/.../ProxyService.kt:304-313`); the op runs on `serviceScope` = `Dispatchers.Default` (`control-shared-proxy/.../ProxyModule.kt:502-503`) | **files: 1** | **Whole file into the Java heap**: `FileInputStream.readBytes` (`AFU:84-86`); desktop `File.readBytes()` (`FolderWatcher.kt:112`); iOS `NSMutableData` → `ByteArray`, 2× resident (`IosPhotoLibrary.kt:68-97`) | [C] | [C] | [C] |
| S3 | Content hash, quota, local write | Inline | Quota pre-flight (`UFI:255`) | as S2 | — | in-memory bytes (`UFI:203,301`) | [C] | [C] | [C] |
| S4 | **Still rungs** LIST 100 / LARGE 256 / XLARGE 1024 / XXLARGE 2048 (`ThumbnailClasses.kt:94-162`) | Inline, **before the row save** (`UFI:333-339`) | none; a failure leaves the class unset (`UFI:658-665`) | as S2; each rung is a synchronous `GetPreview` op (`TCM:104-108`) | rungs serial; **each rung = GetData re-read + SHA-256 + full decode + scale + JPEG**; step-down `[null,65,50]` re-renders from the original (`TCM:71,226`) | original | `BitmapFactory.decodeByteArray` **without options** (`AGP:196`) → `Bitmap.scale` | `ImageIO.read` full size (`JGP:380`) + bilinear `drawImage` | `UIImage.imageWithData` + `drawInRect` (`IGP:226,284-290`); video: whole video to a temp file **per rung**, semaphore-blocked `AVAssetImageGenerator`, ≤1024 px (`IGP:347-386`) |
| S5 | Metadata (dims, orientation, camera, duration, GPS) | Inline, once (`UFI:347`) | — | as S2 | — | same bytes; common parser (`file-service/.../MediaMetadata.kt:97-106`). Capture time is client-supplied (`ContentProviderUtil.kt:28-57`) | [C] | [C] | [C] |
| S6 | Row save + journal | Inline (`UFI:401,488`; `SyncObserver.kt:1919`) | — | as S2 | — | — | [C] | [C] | [C] |
| S7 | **Content emit** (encrypt + chunk PUT) | **Inline when the gate is open** (`SyncObserver.kt:1925-1947`), else journalled for the drain | metered gate, storage-rejected latch | as S2 | chunks N=4 (`BoundedConcurrency.kt:34`, `mapChunksBounded`) | the in-memory bytes | [C] | [C] | [C] (iOS PUT = stage to a background `URLSession`, [D] `ios-drive-parity.md` §16) |
| S8 | Rung blob emit | After S7 (`UFI:539-546`) | as S7 | as S2 | serial | minted bytes | [C] | [C] | [C] |
| S9 | **MOTION** clip (video) | Inline, after S7 (`UFI:556-569`) | none | as S2, blocking MediaCodec, decode 30 s / encode 15 s budgets (`AndroidMotionThumbnailRenderer.kt:486-487`) | — | **whole video in memory** (`MotionThumbnailRenderer.kt:30`) | [C] built | none | none (M6, `photos-video-support.md`) |
| S10 | Thumbnail backfill (XLARGE, XXLARGE, MOTION if some device wants them) | Schedule, 24 h, 20 min initial delay (`ThumbnailBackfillSchedule.kt:20-23,40-82`) | unmetered + charging + idle + battery-not-low (`:71-77`) | `ScheduleDrainLoop` / `TenshuOperationWorker` | **serial**; ≤250 files / 32 MiB per run, both "unmeasured" (`ThumbnailClassBackfill.kt:161-196,244-247`) | original | WM enforces [C][M] | **in-process, constraints NOT enforced** (`SDL:37-39`) | **not registered** (`PPM-I:728`) |
| S11 | Mint on view (X-4) | A viewer asks for a still class | quota, metered, ≤64 MiB original (`EnsureThumbnailClassImplementation.kt:146-166,316`) | IPC op | no single flight (`tech-debt.md:120-124`) | original | [C] | [C] | [C] |
| S12 | **Media index pass** | Schedule, 12 h (`MediaIndexSchedule.kt:24,44-89`) **or** *Index now* (`StartMediaIndexPassImplementation.kt:41-83`). **No upload hook.** | In-pass `IndexingConditions`: charging + battery > 15 % + screen off; *Index now*: battery-not-low only (`AndroidIndexingConditions.kt:20-45`; `inference-service.md` §32.9, §34.2) | runner scope `SupervisorJob + Dispatchers.Default` (`control-shared-proxy/.../ProxyModule.kt:357`); single flight (`MIR:75-122`) | **photos: 1** (`MI:323`); ORT default `SessionOptions()` (`OnnxInferenceBackend.kt:185,218`; `MPF:385`) | original read whole (`MI:399-408`), else stored XLARGE → XXLARGE → relay XLARGE (`file-service-proxy/.../IndexPreview.kt:38-84`) | SigLIP + faces (`PPM-A:698-721`) | SigLIP only (`PPM-D:729-741`); macOS conditions only, **Windows/Linux never index** | **no backend, conditions `Never`** (`PPM-I:737`) |
| S12a | ├ SigLIP vector | per photo | consent per half (`MI:349-368`) | `withContext(Default)` (`OnnxInferenceBackend.kt:115`) | — | **decode #1**: bounds + `inSampleSize` decode to ≥512 short edge, squash to 256 (`AndroidImageDecoder.kt:17-47`). ⚠ **EXIF orientation not applied** | [C] | `ImageIO.read` + `SCALE_SMOOTH` (`AwtImageDecoder.kt:18-41`), no HEIC | — |
| S12b | ├ Faces | per photo | consent; withdrawal deletes templates (`MI:257-261,363-367`) | `Default` under one `Mutex` (`MPF:97,123-149`) | — | **decode #2**: bounds + decode to ≤2048 long edge + EXIF rotate (`MPF:439-508`); whole image + 4 tiles through BlazeFace, landmarker per box, AuraFace per face (`MPF:210-294`) | [C] | none (F2d) | none (F5) |
| S12c | └ Labels | per photo | — | — | — | raw bytes again (`MI:542`) | **inert**: no backend claims tagging, empty vocabulary (`OnnxInferenceBackend.kt:103-104,166`; `MI:169,265`) | inert | — |
| S13 | Face clustering | People screen opens (`FaceGroupsImplementation.kt:126`) | — | IPC op | — | template store | [C] | n/a | n/a |
| — | OCR (IN-5), pets (P1), video indexing | — | — | — | — | — | **not built**: `recognizeText` returns empty (`OnnxInferenceBackend.kt:168`); the walk keeps `FileCategory.Image` only (`ImageLibraryWalk.kt:63`) | | |

### 2.2 The schedulers, as configured on the shared emulator

**[M]** `androidx.work.workdb` pulled read-only from Control on emulator-5554 (`run-as`, 2026-10-01).
Only PENDING and RUNNING specs are shown. Network type: 1 = `CONNECTED`, 2 = `UNMETERED`.

| Op | Network | Charging + idle | Period |
|---|---|---|---|
| `tenshu/inference/system/mediaIndexPass` | **1 = CONNECTED** | ✔ | 12 h |
| `tenshu/file/system/backfillThumbnailClasses` | 2 = UNMETERED | ✔ | 24 h |
| `tenshu/sync/service/writeSliceSnapshots` | 2 | ✔ | 24 h |
| `tenshu/sync/service/backfillContentDeclarations` | 2 | ✔ | 24 h |
| `tenshu/file/system/offlineKeeper` | 2 | — | 6 h |
| `tenshu/sync/service/drainSync` | 2 | — | 1 h (+ one-shot) |
| `tenshu/file/system/replayPendingMoves` | 1 | — | 15 min |
| `tenshu/calendar/service/refreshSubscriptions` | 2 | — | 6 h |

`dumpsys jobscheduler` agrees. The four idle + charging jobs carry `CONNECTIVITY` in their required
constraints, and at the time of reading `CHARGING IDLE` were unsatisfied on all four.

### 2.3 Platform summary

- **Android.** Everything runs in Control: minting, MOTION, backfill, index pass. Discovery and the
  dedup hash run in Drive.
  - The only real OS scheduling is WorkManager.
  - The in-process `ScheduleDrainLoop` also dispatches every item, but enforces no constraint
    (`SDL:37-39`).
- **Desktop.** Same proxy code, but `NoOpPlatformScheduler`, so **every scheduled constraint is
  advisory**.
  - The thumbnail backfill runs on battery.
  - Indexing is gated in-pass by `MacPowerProbe` on macOS, and never runs on Windows or Linux.
  - Synced Folders is Control's 30 s `FolderWatcher`, which has no gate at all.
- **iOS route 1.** Drive runs the proxy in-process.
  - It mints stills at upload, including the per-rung temp-file video poster.
  - No MOTION, no backfill, no indexing, no `BGProcessingTask`.
  - `photos-ios.md` PIO-6/PIO-7 own the plan for MOTION and indexing. This doc only asks that what
    it proposes stays in `commonMain`, so iOS inherits it (§6).
- **Web.** Out of scope. The browser mints images only (`FirstPartyPlugins.kt:74`) and indexes nothing.

---

## 3. Findings, ranked by impact × effort

**Impact:** H = user-visible throughput, battery or crash; M = efficiency; L = hygiene.
**Effort:** S = one small PR; M = a few PRs or a new seam; L = a track.

### F0 (H, privacy × S) — ⚠⚠ A deleted photo's face template stays on disk and keeps clustering — on master, today

This is independent of every plan in this doc.

- **[C] Nothing in production ever removes a face template.**
  - `FaceTemplateStore.remove(filePath)` exists (`FaceTemplateStore.kt:47`, `DefaultFaceTemplateStore.kt:93`).
  - Repo-wide, its only callers are tests. No delete, trash, replace or sync-apply path calls it, and
    neither does any `control-shared-proxy` wiring.
  - Its only bulk removals are `clear()`: on consent withdrawal (`MI:257-261,363-367`), on a model
    change (`MI:270-278`), and on a realm wipe (`inference-service.md` §34.6).
- **[C] Clustering reads every stored row, with no liveness check.**
  - `FaceGroupsImplementation` clusters `faces.rows()` as stored (`FaceGroupsImplementation.kt:102,124`).
  - `photos-shared-logic/.../model/People.kt` does no existence filtering that I could find.
  - The index walk skips trashed paths (`ImageLibraryWalk.kt:62`), but skipping means *not adding*.
    It never removes what an earlier pass wrote.
- **[I] Consequence.** After a user deletes or trashes a photograph of a person:
  - that person's 512-d biometric template stays in Control's device-local store;
  - it still pulls the person's cluster centroid and can still be a group's cover or member;
  - it survives until consent is withdrawn, the model changes, or the realm is wiped.

  The same holds for a template from a photo that was *replaced* at the same path, and for a delete
  that arrived **by sync** from another device. Threat-model A40 treats templates as device-local
  biometric data that the user controls; a deletion that leaves one behind contradicts that intent.
  ⚠ Owed: a check on the threat model's A40 wording in the P0f-b PR. P0f-a already edits A40;
  P0f-b re-checks that wording against the delete hook.
- **[C] The same leak in the vector store, and it is user-visible.**
  - `EmbeddingStore.remove` is never called either (`inference-service.md` §32.8), so a deleted
    photo keeps its SigLIP vector.
  - `PhotosLikeImplementation` ranks the stored vectors with no file-existence check
    (`PhotosLikeImplementation.kt:114-119`). So a deleted photo can still come back as a search
    hit (F12).
  - v0.3 said `photosLike` *"cannot surface it"*. That was an inference, and the code falsifies it.
- **📱 Verification owed** (cheap, not yet run): on a device with the face model, index, delete one
  face photo, reopen People. The deleted photo must still count toward its group if F0 is right.
- **Fix:** phases **P0f-a** (☁) and **P0f-b** (📱 smoke) (§6). They ship before everything else and do not wait on P10.

### F1 (H × S–M) — Four full decodes per photo at upload, up to twelve

- **[C]** Each still rung is its own `GetPreview` generation call (`UFI:333-339`, `TCM:104-108`).
  Each call does all of the following:
  - re-reads the whole file through `GetData`, although the upload already holds it
    (`GetDataImplementation.kt:63`);
  - SHA-256s it for a cache key, although `UFI:203` already computed the content hash
    (`AGP:173`, `JGP:181`, `IGP:208`);
  - decodes at full resolution, with no `inSampleSize`, no ImageIO subsampling and no
    `CGImageSourceCreateThumbnailAtIndex` (`AGP:196`, `JGP:380`, `IGP:226`);
  - scales and encodes.
- **[C]** The quality step-down re-renders from the original (`TCM:71`). Quality is in the cache key
  (`AGP:354-358`), so the cache never helps.
- **[M]** JVM, this Mac, synthetic 12 MP JPEG (4000×3000, 2.5 MB), 12 runs, median of the last 10.
  The machine was under load (load average ~13), so read the ratios, not the absolute times.

  | Variant | Median | Min |
  |---|---|---|
  | **Today's shape:** 4 × (SHA-256 + full decode + scale + encode) | **393 ms** | 378 ms |
  | One full decode, then a cascading 2048 → 1024 → 256 → 100 ladder | **157 ms** (2.5×) | 153 ms |
  | One 2×-subsampled decode, then the same cascade | **128 ms** (3.1×) | 124 ms |
  | One bare full decode | 79 ms | 74 ms |

  Harness: `Bench.java`, scratch only, not committed. ⚠ The JVM is desktop's path. For Android:
  - **[D]** `drive-xxlarge-thumbnail-rung.md` open item 10 records 119–212 ms *per render* on
    device, ×3 on step-down.
  - **[I]** The ratio carries to Android.
- **Why it matters.** This sits on the critical path of every backup item (F4). It is also CPU,
  battery and native memory spent four times for one result.

### F2 (H × S) — A large video is read whole into Control's Java heap — likely OOM

- **[C]** `AFU:84-86` reads the descriptor with `FileInputStream.readBytes`. `UFI` holds that array
  for the whole op. Each poster rung re-reads it through `GetData` (S4), and MOTION takes the
  whole array (`MotionThumbnailRenderer.kt:30`).
- **[C]** Control declares no `largeHeap` (no match in any `AndroidManifest.xml`).
- **[M]** The emulator's `dalvik.vm.heapgrowthlimit` is 192 MB.
- **[I]** A video above roughly 80–90 MB holds two copies at once: the op's array plus one
  `GetData` copy. That exceeds the heap and should throw `OutOfMemoryError` in Control.
  - The `serviceScope` handler only logs it (`ProxyModule.kt:502-506`).
  - A 4K phone video is commonly 300 MB+ a minute.
- **[D] contradicts [C].** `drive-synced-folders.md` §7 item 4 says *"memory is NOT the blocker …
  peak heap `O(CHUNK)`"*. That is true of the **emit** (S7) only. The op reads the whole file before
  it gets there. `UFI:459-462`'s comment has the same caveat.
- **Image bitmaps are a separate question.** They live in native memory on API 26+, so a
  50 MP × 4 decode is an RSS/LMK risk, not a Java OOM **[I]**.
- **Verification owed (📱):** upload a 200 MB video through Synced Folders on the emulator, using a
  throwaway realm, never the shared one.

### F3 (H × S–M) — The scheduled index pass barely gets to run on Android or desktop

Four independent causes:

1. **[C] The in-process loop wastes its only attempt.**
   - `ScheduleDrainLoop` fires a periodic item on its first tick (initial delay 0).
   - The runner then declines, because the screen is on (`MIR:119`).
   - The loop resets the item's accumulator anyway (`SDL:114-115`), so the next attempt is 12 h of
     **process uptime** later.
   - Accumulators are in memory, so every Control restart repeats this.
   - [D] `inference-service.md` §32.9 already notes this for desktop.
2. **[M] WorkManager requires a network the pass does not need.**
   - The job is stored with `required_network_type = CONNECTED` (§2.2).
   - `registerMediaIndexSchedule` passes no `network`, so the default is `NETWORK_ANY`
     (`sync-service/src/commonMain/kotlin/org/tenshu/sync/ConstraintSpec.kt:84`), which `WMS:118-123` folds to `CONNECTED`.
   - The KDoc (`MediaIndexSchedule.kt:39-41`) and `inference-service.md` §26.2 both say *"No network
     constraint"*.
   - ⚠ Part of the pass *does* use the network: relay XLARGE previews (`IndexPreview.kt`), metered-gated.
     That is D7.
3. **[C] Stacked idle gates.** WorkManager's `setRequiresDeviceIdle` is Android's maintenance-window
   idle, and the pass adds its own screen-off check. Both must hold, on a charger, with the battery
   above 15 %.
4. **[I] A ~10-minute execution cap.**
   - The worker never calls `setForeground` (`TenshuOperationWorker.kt:71-77` only serves expedited
     work), so JobScheduler's per-job execution limit applies.
   - On stop, `runToCompletion` cancels the scheduled pass (`MIR:130-131`).
   - At the measured per-photo cost — SigLIP ~246 ms warm including decode, tiled face detection
     481–546 ms, plus ~60 ms per face [D] (`inference-service.md` §15.12; `photos-people-and-pets.md`
     §12.8b, §12.14) — one window covers roughly **700–900 photos** [I].
   - Resumability makes each window productive (per-store `visited()`). The open questions are how
     often WorkManager re-runs a stopped periodic job overnight, and whether `BackgroundLiveness`'s
     foreground notification changes the cap. **Verification owed (📱).**

**Net [I]:** a library indexes through *Index now* or not at all. That matches the maintainer's
own runs: [D] every measured library in `photos-people-and-pets.md` §12 came from harnesses or
*Index now*.

### F4 (H × M) — Uploads are fully serial, and each waits for its own CPU work and its own network

- **[C]** The drain awaits each item (`SFC:606,686`). The op returns only after:
  - S4 (4–12 renders);
  - S7, the inline chunk upload when the gate is open (`SyncObserver.kt:1925-1947`);
  - S8;
  - S9 (MOTION).

  `FolderWatcher` is serial too (`FolderWatcher.kt:101`). The only parallelism is the 4 chunk
  transfers inside one file.
- **[C]** The *intent* is journalled before the inline attempt (`SyncObserver.kt:1919`), and the
  journal is the progress source of truth (its own comment, `:1905-1918`). So letting the op return
  after the journal, and leaving S7/S8 to the drain, is already crash-safe.
- **[I]** CPU (S4, S9) and network (S7) never overlap across files. Pipelining file *n+1*'s CPU
  stage under file *n*'s network stage hides most of F1's remaining cost behind the network for free.
- **[D] contradicts [C].** `SFC:784-789` says the relay upload happens *"afterwards, on the proxy's
  scheduler"*. With the gate open, it is inline.

### F5 (H × M) — No on-upload indexing, and the runner can only walk the whole library

- **[C]** The only callers of the index pass are the schedule and *Index now*
  (`MediaIndexSchedule.kt:73`, `ModelsRepository.kt:316`). Nothing in `file-service-proxy` or
  `app-ingest` touches inference.
- **[C]** `MediaIndexer.index` already takes any `List<IndexableMedia>` (`MI:225-236`), but
  `MIR.launchPass` always passes `source.media()`, the whole-realm walk (`MIR:165`;
  `ImageLibraryWalk.kt:41-46`).
- **[C]** Freshness is keyed by **path**, and nothing calls `EmbeddingStore.remove`, so a replaced
  file keeps its old vector. [D] `tech-debt.md` (stale index entries), `inference-service.md` §32.8.
  On-upload indexing makes overwrites frequent enough to matter.
- **[D]** Why on-upload matters beyond latency: the maintainer's library was indexed **mostly from
  1024 px previews** (1,203 of ~1,290; `photos-people-and-pets.md` §12.15). There, face quality is
  measurably worse: the norm gate failed on previews and worked on originals.
  - **[I]** The uploading device holds the original exactly once, at upload. Indexing then is both
    cheaper (no relay fetch) and better.
  - ⚠ Vectors and templates are device-local (IN-D5), so this helps the uploading device only.
    Other devices still index from previews.

### F6 (M × S–M) — The index pass decodes each photo twice, and SigLIP never sees it upright

- **[C]** The same `ByteArray` goes to every half, but each backend decodes it separately:
  - SigLIP decodes to ≥512 short edge (`AndroidImageDecoder.kt:17-30`);
  - faces decode to ≤2048 long edge, then EXIF-rotate (`MPF:439-508`).

  Two bounds passes, two full decodes and one EXIF parse per photo, on top of the whole-file read.
  Neither reuses the XXLARGE rung that S4 minted from the same original.
- **[C]** `AndroidImageDecoder` and `AwtImageDecoder` apply **no EXIF orientation**.
  `InferenceBackend.embedImage(bytes)` has no orientation parameter (`InferenceBackend.kt:109`), and
  previews pass orientation to faces only (`MI:409-410`).
  - **[I]** Portrait photos stored rotated are embedded sideways. SigLIP is fairly
    rotation-tolerant, but this is a free quality loss.
  - Fixing it changes vectors, so it is a provenance bump and a re-index (§6 P7).

### F7 (H × S) — No CPU discipline: thread counts, priority, thermal

- **[C]** Every ORT session is built from a default `SessionOptions()` (`OnnxInferenceBackend.kt:185,218`;
  `MPF:385`). MediaPipe uses default `BaseOptions` (`MPF:322,363`).
  - **[I]** ORT's defaults are an intra-op pool of one thread per core with spin-waiting enabled. A
    pass therefore saturates every core and burns CPU between ops.
  - [D] IN-3m measured on-device throttling: the last 50 images ran 1.65× slower, and the phone went
    from 37.7 °C to 44.1 °C (`inference-service.md` §15.12).
- **[C]** All media work runs on `Dispatchers.Default`, which Control's IPC handling also uses
  (`ProxyModule.kt:502-503`). There is no `limitedParallelism`, no lower-priority dispatcher and no
  `Process.THREAD_PRIORITY_BACKGROUND` for media work.
- **[C]** No thermal or power-save handling anywhere: no match for `thermal`,
  `getCurrentThermalStatus`, `thermalState` or power-save outside a test harness.
- **[I] The risk is *Index now*.** It runs on battery while the user holds the phone (by design,
  §34.2), with every core busy, inside the process that serves every app's IPC.
  - [D] `tech-debt.md` *"Control's UI thread can starve behind the store lock"* is the same process
    under a different load.

### F8 (M × S) — A long scheduled item blocks every other in-process scheduled item

- **[C]** `SDL.tick` dispatches items **serially and awaits each** (`SDL:71-113`).
  `MediaIndexPassImplementation` awaits `runToCompletion` (`:63`), and the backfill walks up to 250
  files.
- **[I]** While a pass runs in-process, `drainSync`, `replayPendingMoves` and the offline keeper do
  not tick.
  - On Android, WorkManager also dispatches those, which hides it.
  - On desktop and iOS, the in-process loop is the only scheduler.

### F9 (M × S) — Desktop: constraints are advisory and Synced Folders is ungated

- **[C]** Desktop's backfill ignores charging, idle and unmetered (`ThumbnailBackfillSchedule.kt:35-39`,
  `SDL:37-39`).
- **[C]** `FolderWatcher` has no gate and reads every file whole (`FolderWatcher.kt:42-43,112`).
- **[C] Incidental, likely bug, unverified.**
  - `FolderWatcher` never creates `/Backup/<device>/<bucket>`, yet `UFI:196` `requireParentFolder`
    throws `NotFound` for a missing parent.
  - The failure is swallowed by `runCatching` (`FolderWatcher.kt:107-113`), and the watermark
    advances anyway (`:115-117`).
  - **[I]** First-time desktop backups into a new bucket fail silently. Owed a JVM test (☁).

### F10 (M × S) — Smaller waste on the mint path

- **[C]** `previewCache.put` persists every rendered rung ≤256 KiB to `.previews/` (`AGP:253`,
  `PreviewCache.kt:88`). The same bytes are already stored as the class blob, so every upload writes
  its small rungs twice.
- **[C]** On iOS, each video rung and each quality attempt writes the **whole video** to a new temp
  file (`IGP:347-351`), up to 12 whole-file writes per video.
- **[D]** A source under 2048 px mints identical XLARGE and XXLARGE blobs
  (`drive-xxlarge-thumbnail-rung.md` open item 9).

### F11 (M × M) — Faces cost the most per photo, and pets and video are absent

- **[D]** Tiled two-stage detection costs 481–546 ms per photo, ~3.5× single stage
  (`photos-people-and-pets.md` §12.14). The embedder adds ~60 ms per face.
  - **[I]** At 20 k photos, that is ~3 h of detection alone.
- **[C]** Labels are inert. Pets (P1), OCR (IN-5) and video are absent (S12c and the last row of
  §2.1).
- **[D]** Non-human faces form "people" again, since the norm gate was switched off on 2026-10-01
  (`tech-debt.md`, *a non-human face can form a person*).

### F12 (H × S) — Mia and `photosLike` cite photographs that no longer exist

- **[C] Mia.** `AskMiaImplementation` turns a `MediaLabel` row into a `File` target straight from
  `row.filePath`, with no existence check (`mia-service-proxy/.../AskMiaImplementation.kt:855-860`).
  A `SimilarPhoto` hit is converted the same way (`:866-872`).
- **[C] `photosLike`** returns stored paths without one either (F0).
- `MediaLabel` rows **sync** and survive the file today: nothing deletes them on delete (F0, P10).
  **[I]** So a deleted photograph stays searchable on every device, and its hit opens a dead
  cross-app target.
- This applies to the user's own typed names too: "photos of Anna" can cite a photo they
  deleted.
- **Fix:**
  - **P0g**, a read-side existence filter in Mia (☁) and `photosLike` (its wiring rides P0f-b, 📱).
  - **The label-deletion rule** (D13 as amended), executed by P0f-b's purge hook. P10 backstops the
    model-produced rows only.

### Not findings — checked and fine

- **[C]** Minting never fails an upload: renders become `null`, the store step runs under `runCatching`,
  and cancellation is rethrown (`UFI:658-665`, `TCM:116-125,188-193`).
- **[C]** Index halves fail independently. A model failure latches only that half, for this pass only
  (the vector half only after three failures in a row, since 2026-10-06 — `inference-service.md` §32.3);
  an undecodable file is recorded `Unreadable` and skipped (`MI:302-308,428-441,491-527`).
- **[C]** Every derived store is resumable: append-only logs, a torn tail is dropped, writes are
  `Mutex`-serialised (`DefaultEmbeddingStore.kt:21,139-142,211`).
- **[C]** The emit's residency is bounded at O(N × CHUNK) (`content-plane-transfer-concurrency.md`).
- **[C]** The SigLIP decoder already subsamples (`AndroidImageDecoder.kt:9-30`). It is the model for
  what F1's mint decode should do.

---

## 4. Target shape

Four principles. Each phase in §6 implements one of them and none needs the others to land first.

### 4.1 One bounded decode per photo per visit

There are two kinds of visit.

**The upload visit (S4).**
- Decode once, **bounded**:
  - Android: `inJustDecodeBounds`, then `inSampleSize` chosen so the long edge stays ≥2048, or
    `ImageDecoder.setTargetSize` on API 28+.
  - JVM: `ImageReadParam.setSourceSubsampling`.
  - iOS: `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceThumbnailMaxPixelSize = 2048` and
    `kCGImageSourceCreateThumbnailWithTransform`.
- Then **cascade** the ladder 2048 → 1024 → 256 → 100 from that one bitmap.
- The quality step-down **re-encodes the scaled bitmap**; it never re-decodes.
- The content hash comes from `UFI:203`; no per-rung SHA-256.
- Video: one poster extraction feeds all four rungs.

  This is a **platform-internal seam** (`ThumbnailLadderRenderer`, injected like
  `MotionThumbnailRenderer`), **not** a new `GetPreview` param: an op-param change is a wire reshape
  under HR 9. Expected gain is ~2.5–3× [M, JVM]. The ≥2048 bound also caps a 50 MP decode at ~4000 px.

**The index visit (S12).**
- One decode feeds every half. An `IndexImage` (upright, ≤2048 long edge) is produced once per
  photo and handed to SigLIP (squash to 256), the face detector (as today, at 2048) and any future
  half: pets, OCR.
- The `InferenceBackend` members that take `bytes` keep working through defaulted overloads. That is
  how `detectFaces(bytes, orientation)` was added (`InferenceBackend.kt:211-220`).

### 4.2 Stages decoupled by durable, idempotent, device-local markers

The markers mostly exist already. What is missing is a **queue** and a **subset runner**.

| Stage | "Done" marker today | Change |
|---|---|---|
| Rungs | Blob presence + `.declined` sentinel with `MINT_FORMAT` (`BackfillThumbnailClassesImplementation.kt:138-249`) | none |
| MOTION | same | moves out of the upload op into the derive drain (D8) |
| Vector | `EmbeddingStore.visited(path)` + provenance stamp. ⚠ `visited` is true for a vector **or** a vectorless visit (`DefaultEmbeddingStore.kt:71-72`) | key by **content hash** as well as path, so a replace re-indexes (F5, P10). Both kinds of visit are keyed and reconciled alike (P0f-a) |
| Faces | `FaceTemplateStore.visited(path)` + pipeline stamp | same |
| Labels | synced `__indexed` marker with provenance | no reshape. The marker and the model-produced rows are **deleted** on replace and when their path leaves the library (P10). Every row, typed names included, is deleted on permanent delete, by the purge hook only (D13, P0f-b) |
| Pets (new) | its own store's `visited()` | new, same pattern |

**The derive queue** is a device-local, append-only log in the same shape as `DefaultEmbeddingStore`.
It is **not** realm rows, so it is not a schema type and HR 9 does not apply. Each entry is
`(path, contentHash, wantedStages)`.
- `UFI` appends one entry after the row save (S6).
- ⚠⚠ **A subset run never reconciles.** The pass carries a typed scope: `COMPLETE_LIBRARY` only when
  its list came from `source.media()` and that walk returned; `SUBSET` for `enqueue(paths)`. The
  P0f-a reconcile runs **only** on `COMPLETE_LIBRARY`. Otherwise each upload's one-photo subset
  would be taken for the whole library and wipe the index. A walk that cannot read the library must
  throw, not return a short list (P0f-a, hard constraint 3).
- The `MediaIndexRunner` grows `enqueue(paths)` and drains the queue **before** falling back to the
  whole-library walk.
- The walk stays the backstop for anything the queue missed: crashes, older builds, files that
  arrived by sync.
- A stage failing never blocks another, because each consults only its own marker. This is already
  true inside the index pass (§3, *Not findings*).

### 4.3 One media work budget for the process

`MediaWorkBudget`, a `commonMain` object injected into the minter, the derive drain, the index pass
and the backfill:
- **One CPU lane.** A dedicated `Dispatchers.Default.limitedParallelism(k)` for all decode, encode
  and inference work, with k = 2 (D4). ORT sessions get `intraOpNumThreads = 2` and spinning off. The
  IPC-serving `serviceScope` stops competing with media work for every core.
- **Priority.** User-visible upload minting first; on-upload derive next; backlog index and backfill
  last. The drain checks the budget between items, never mid-item.
- **Conditions.** One policy, `mediaWorkAllowed(tier)`, generalising `indexingConditionsAllow` (§32.9),
  with thermal and power-save inputs (D5):
  - Android: `PowerManager.getCurrentThermalStatus` / `addThermalStatusListener`, `isPowerSaveMode`.
  - iOS: `ProcessInfo.thermalState`, `isLowPowerModeEnabled`.
  - macOS: the existing `MacPowerProbe`, plus thermal pressure.
  - Unknown means no, as today, for background tiers only.
- **Cancellation.** Background tiers re-check per item. A transition (thermal up, screen on for a
  scheduled pass) stops at the next item, as the pass's `deferred` does today (`MI:338-344`).

### 4.4 Upload as a two-stage pipeline

The op returns early, and only the **network** half of S7/S8 moves out: chunk encrypt + `PUT`,
done by the content drain. MOTION (S9) moves to the derive drain. That gives **pipeline depth 2**:
file *n+1*'s CPU stage overlaps file *n*'s network stage. The journal is already the progress source
of truth, so the banner's figures do not move (D2). There is no change to the relay, the wire, or
what the relay sees, so threat-model assets A1–A4 and A9 are untouched (HR 2).

⚠⚠ **Two hard constraints decide *where* the op may return.** Both were checked against the code at
`4ac462e2d`. P9 is not done until each has a test that fails without it.

**C1 — Entity before content: an ordering barrier, owned by the op.**
- **[C]** `saveData(fileEntity)` only *enqueues* the entity block on the async emit consumer. The
  byte emit after it runs inline. `UFI:480-488` documents this, and `awaitEmitIdle()` (`UFI:488`) is
  the barrier that puts the entity block, HLC-ordered, ahead of the content manifest.
- Without that barrier a peer receives the bytes before their parent `File`. Then
  `FileBytesPropertyWriter` drops them, and the proactive push never retries.
- **Rule:** the op must call `awaitEmitIdle()` **before** any content intent becomes drainable. The
  op returns only after that call.
- Either way satisfies the rule:
  - journal the intent as non-drainable and flip it after the barrier; or
  - journal it after the barrier.
- ⚠ The content drain may never be the stage that "remembers" the barrier: by the time it runs, it
  cannot know whether the entity block went out.
- The barrier is the cheap part (an async-queue drain, no network), so keeping it in the op costs
  nothing that P9 wanted to save.

**C2 — Every still rung is persisted and journalled before the op returns.**
- **[C]** Today `emitThumbnailClass` → `ThumbnailClassMinter.store` writes each rung's blob to
  `FileDatabase` and creates its content intent only **after** the `File.Data` emit
  (`UFI:535-546`, `TCM:171-200`).
- If the op returned after S6 with the rungs still in memory, a process death would lose them.
  **[C]** LIST and LARGE are `backfilled = false` (`ThumbnailClasses.kt:101,110`), so nothing would
  ever re-mint them, and peers would lose them **permanently**.
- **Rule:** before the op returns, each minted still is written to `FileDatabase` and its intent
  journalled, under the same C1 barrier as `File.Data`. Only the network `PUT` is deferred.
- In practice C1 and C2 put the boundary right after today's `emitFileContent` + `emitThumbnailClass`
  calls, with their *network* half deferred. They do **not** put it after S6.
- MOTION is exempt from C2. It is `backfilled = true`, and under D8 it is a derive-drain job with its
  own durable queue entry (§4.2), which the backfill also covers.

### 4.5 Where on-upload indexing and pets slot in

```
 upload op (S2–S6, one bounded decode → 4 rungs) ──► journal ──► content drain (S7/S8)
                    │
                    └─► derive queue (device-local) ──► derive drain, under MediaWorkBudget + mediaWorkAllowed(tier)
                                                        ├─ MOTION (video)
                                                        └─ index visit: one IndexImage
                                                             ├─ SigLIP vector       (consent: SEMANTIC_IMAGE_SEARCH)
                                                             ├─ faces               (consent: FACE_CLUSTERING)
                                                             ├─ pets  ◄── NEW half  (consent: new capability, D9)
                                                             └─ OCR   ◄── IN-5 slot (consent: TEXT_IN_IMAGES)
 scheduled walk (12 h) ──► same index visit, for whatever the queue missed
```

**Pets plug in at two depths.**
- **P1a, coarse ("has a dog / cat").** No new image pass at all. Text-embed a handful of prompts and
  score them against the vectors already stored. This is the §35 relevance-floor path that `photosLike`
  uses, so it needs calibration, not a model. The output is a `MediaLabel`, which already syncs
  (IN-D5).
- **P1b, identity ("which dog").** A fourth half in `MediaIndexer`, built like the face half
  (`MI:445-529`). It needs:
  - an animal detector (for example a COCO-class detector with cat/dog boxes);
  - a crop embedder (SigLIP on the crop is the free baseline to measure first);
  - its own store, stamp, `InferenceCapability`, consent and `visited()`;
  - a `petGroups` read op beside `FaceGroups`.

  It consumes the shared `IndexImage`, so it adds a detector run and N crop embeds per photo, not a
  decode. ⚠ `InferenceCapability` is a Kotlin enum (`InferenceCapability.kt:25`); the synced label
  carries its `id` string. Run `SchemaShapeBaselineEnforcementTest` to confirm a new member is not a
  reshape. ⚠ The face detector's non-human hits (§12.14) are *not* a pet detector; its score cannot
  separate them.

---

## 5. Decisions — ✅ D1–D12 accepted as written, D13 and D14 decided, 2026-10-02

Each recommendation below is now the decision: **D1–D12 are DECIDED (maintainer, 2026-10-02)**.
D13 was added and decided the same day, then amended in v0.5: a typed name survives a replace and
the trash, and is deleted with its file on permanent delete. D14 followed, also the same day: a
typed name follows its photo when the photo is moved.
The C1/C2 constraints (§4.4) and the P4 retry contract (§6.2) arrived from review the same day. They
refine *how* D2, D8 and F3 are built, and none of them changes a decision.
- **D2** still returns early, but at the C1/C2 boundary, not at S6.
- **D8** keeps all four stills in the op. Persisting them there is now a requirement as well as a
  recommendation.

| # | Decision | Options | Decided (2026-10-02) |
|---|---|---|---|
| **D1** | **Index input on the uploading device** | (a) the original; (b) the local XXLARGE rung | ✅ **(a) when present, which is always at upload; else XXLARGE, then XLARGE, as today.** Originals measurably help faces (§12.15). Measure faces on XXLARGE before demoting anything (P5). |
| **D2** | **Upload op returns before the content emit** | (a) keep inline; (b) return after the journal, pipeline depth 2 | ✅ **(b), at the §4.4 C1/C2 boundary.** The op still owns the entity barrier (`awaitEmitIdle`) and the durable write and journalling of every still rung; only the network `PUT` leaves. The journal already makes it crash-safe and is the progress source. ⚠ A *manual single-file* upload may keep the inline path if a "done means on the relay" UX depends on it. Check `drive-upload-progress.md` first. |
| **D3** | **When on-upload indexing runs** | (a) only when charging; (b) SigLIP immediately within budget, faces/pets when charging; (c) everything immediately | ✅ **(b).** A camera shot costs ~250 ms of SigLIP, which is invisible. Faces (~0.6–1 s) wait for the charger or *Index now*. Both still respect `mediaWorkAllowed`. |
| **D4** | **CPU budget** | k for the media lane; ORT intra-op threads; spinning | ✅ **k = 2, `intraOpNumThreads = 2`, spinning off.** Re-measure IN-3m's ms/img (P3). If it regresses by more than 30 %, raise threads for *Index now* only. |
| **D5** | **Thermal / power-save backoff** | thresholds per tier | ✅ **Background tiers pause at thermal ≥ MODERATE or power-save. Upload minting pauses only at ≥ SEVERE.** iOS: `.serious` / `.critical`. |
| **D6** | **Long index runs on Android** | (a) WorkManager windows (~10 min) + resumability; (b) WorkManager `setForeground` (`dataSync`; `mediaProcessing` on API 35+); (c) both | ✅ **(a) first, measured (P5).** (b) only if a charging night cannot finish a 5 k library. ⚠ FGS types carry daily time limits and Play-policy weight. |
| **D7** | **Index job's network constraint** | (a) `NETWORK_NONE`; (b) keep `CONNECTED` | ✅ **(a).** The pass already degrades, counting `notOnDevice` when the relay preview is unavailable. Fix the doc claim in the same PR. |
| **D8** | **What stays in the upload op** | all 4 rungs + MOTION inline; or LIST + LARGE inline, the rest deferred | ✅ **All 4 stills inline once P1 lands** (≈ one decode), **written and journalled before the op returns** (§4.4 C2: LIST/LARGE are never backfilled, so an in-memory rung lost to a process death is lost for good). Peers want XLARGE early. **MOTION moves to the derive drain.** It is the slow one (0.55–1.3 s at 1080p [D]), needs the whole video, and is backfillable. |
| **D9** | **Pets scope** | P1a coarse labels via SigLIP prompts; P1b identity clustering | ✅ **P1a first.** It needs no model and no image pass, only calibration. P1b gets its own design doc and a threat-model note: device-local templates, user-typed pet names syncing as labels, like A40. |
| **D10** | **Videos in the index** | none; poster rung only; sampled frames | ✅ **Poster rung (XLARGE) only**, through the image path, so the `IndexImage` path covers it. P11 says how the poster is forced and what resolution it can count on. |
| **D11** | **Freshness key** | path; path + content hash | ✅ **Path + content hash.** On-upload indexing makes replace-in-place common, and `File.ContentHash` already exists. ⚠ A vector/template store format bump; device-local, so not HR 9. ⚠⚠ It also covers the **synced** marker and the model-produced labels, which are invalidated by deleting rows rather than re-keyed, so no `MediaLabel` reshape (P10). |
| **D12** | **Shared decode across index halves** | (a) `IndexImage` overloads on `InferenceBackend`; (b) a per-visit decode cache keyed by array identity (the face backend already does this, `MPF:420-431`) | ✅ **(a).** It is explicit, testable with the fake, and gives one place to apply EXIF (F6). (b) hides the contract. |
| **D13** — ✅ **decided 2026-10-02** (added after D1–D12 were accepted) | **User-typed person names when their file changes** | on replace: keep / delete / flag; on delete: keep / delete | ✅ **Replace: keep** (maintainer, 2026-10-02). A typed name is user data (IN-D5), and deleting it is the irreversible direction. **Trash: keep** (restorable); since D14 the name travels to the trash path with the photo. **Permanent delete (purge): delete with the file**, together with every other `MediaLabel` row for the path. A synced name for a file that is gone is a dead search hit on every device (F12). Executed by P0f-b's purge hook **alone**, which tombstones each row so peers drop it too (v0.9). P10's backstop removes only the re-derivable rows, because a path with no `File` row is not proof of a delete (v0.7, §6.1). |
| **D14** — ✅ **decided 2026-10-02** | **Typed names when their photo is moved** | stay at the old path (dead row) / follow the photo | ✅ **They follow the move** (maintainer, 2026-10-02). On a move or rename of a photo or a folder, and so on trash and restore, which are moves, the typed-name rows keyed by the old path are re-keyed to the new one, as `MoveFile` already does for history rows. **`TrashOrigins` is dropped:** a trashed photo's names sit at its trash path, so a purge deletes labels at the path it is handed. Model-produced rows and the marker are **not** re-keyed: the moving device tombstones them and a pass derives them again. Built by P0f-b as seam S4 (§6.1). |

---

## 6. Phased plan

Legend:
- ☁ — pure `commonMain` / JVM; a cloud agent can do it end to end with `scripts/run-gate.sh`.
- 📱 — needs the emulator or an Android device: an HR 8 smoke run, or a measurement.
- 🖥 — needs a Mac: Swift, `iosMain` compile, or the simulator.

Each phase is one PR unless split as a/b. "Gate" means `scripts/run-gate.sh`, never `./gradlew test`.

| Phase | What | Where | Notes |
|---|---|---|---|
| **P0f-a** — ⚠⚠ **privacy, ships first** (F0) — 🚀 dispatched 2026-10-02 to a cloud session, taken over locally (`fix/media-p0f-a-reconcile`) | **Reconcile at the start of every pass, inside `MediaIndexer`; plus the read-side filter *mechanism*, unwired.** (1) In `MediaIndexer.index`/`indexPass`, after the runner has handed it the walk's list (`MIR:165` → `MI:225`), drop from both stores **every path the store remembers** that is not in that list. The walk already excludes trashed paths (`ImageLibraryWalk.kt:62`). This catches local deletes, sync-applied deletes and trash. ⚠⚠ **Hard constraint 0 — vectorless visits are reconciled too (v0.6).** **[C]** `EmbeddingStore.entries()` returns only paths that hold a vector (`DefaultEmbeddingStore.kt:101-102`). A photo that could not be decoded is remembered by `markVisited` in the private `withoutVector` set (`:40`, `:74-86`; `MI:428`), and `visited()` answers true for it (`:71-72`). A reconcile built on `entries()` + `remove` therefore cannot see such a visit. It outlives its file, and whatever sits at that path later is skipped for as long as the model stays: the same photo restored from trash, or a different file. The face store has no such blind spot: an unreadable or faceless photo is an empty row (`MI:516`), and `rows()` returns every row (`DefaultFaceTemplateStore.kt:86-89`). **Rule:** the reconcile is a store-side operation that takes the live set and drops everything else the store remembers: vectors, vectorless visits, face rows and empty face rows. No caller enumerates. ⚠ **No DI change**: `MediaIndexer` already holds both stores and receives the list. Checked: `ProxyModule.kt:331-345` builds it, and `:382-410` builds the `MediaSource`. ⚠⚠ **Hard constraint 1:** reconcile only against a walk that **returned**. A walk that throws reconciles nothing, so a transient store error can never be read as *"the library is empty"* and wipe every template. ⚠⚠ **Hard constraint 2: completeness is a typed property of the pass, never an inference.** `index()` takes an explicit scope (`COMPLETE_LIBRARY` / `SUBSET`). `MediaIndexRunner.launchPass` passes `COMPLETE_LIBRARY` only for its `source.media()` call (`MIR:165`), and the reconcile runs only under it. Once P6 adds `enqueue(paths)`, a subset pass would otherwise reconcile every other photo out of the index. ⚠⚠ **Hard constraint 3 — a walk that returned is not yet a complete walk (v0.6).** **[C]** `collectImagePaths` swallows three failures without throwing: a row that does not read yields nothing, **the root included** (`ImageLibraryWalk.kt:49`); a folder whose child list does not resolve yields no children (`:55`); a path read that throws is skipped (`:60-61`). **[I]** So a store that is locked, not yet hydrated or mid-wipe can hand back an empty or short list that *returned*, and the reconcile would drop every vector and template. They are re-derivable, so the cost is a full re-index and not data loss, but it is the wipe that constraint 1 exists to prevent. **Rule:** the walk throws when it cannot read the root or resolve a folder's child list. That is a `file-service-proxy` `commonMain` change and needs no `MediaSource` signature change, which would be Control DI. ⚠ Owed a check [I]: whether a dangling child reference is a normal state. If it is, a single unreadable *file* row must not void the walk. ⚠ **Review bar:** the P0f-a PR was dispatched before subset runs exist. At review it must carry the typed scope, or at least a test that a non-complete list reconciles nothing, which P6a then extends. ⚠ **P0f-a deletes no `MediaLabel` row.** Label deletion on a permanent delete is P0f-b's hook (D13). Until P0f-b lands, a typed name outlives a purged photo. Runs for scheduled passes and *Index now* alike. ⚠ It therefore only acts when a pass runs, which F3 says is rare until P0/P4 land, but it does act on *Index now*. (2) The read-side filter as a **seam**: `FaceGroupingComponents` gains a `livePaths` predicate, and `FaceGroupsImplementation` drops rows whose path fails it before clustering. ⚠ It cannot be self-contained: **[C]** `:inference-service-proxy` depends on no file service (`inference-service-proxy/build.gradle.kts:95-101`; `inference-service.md` §26.4), and `isTrashedPath` lives in `:file-service`. So the predicate must be injected, and **injecting it is P0f-b's wiring line**. ⚠⚠ Built here unwired, so its tests must pass a real predicate. A null-default seam tested only with null is an inert harness. Tests: a deleted path's template and vector are gone after the next pass; **a deleted path's vectorless visit is gone too, and an unreadable photo that is trashed and restored is visited again**; a throwing walk removes nothing; **a walk whose root does not read removes nothing**; trash → removed; restore → re-indexed; the filter excludes a dead path when given a predicate. **As built (the second build, which supersedes the first) [C]:** (i) **Constraint 0.** Both stores gain `remembered()` — every path the store knows anything about: a vector, a vectorless visit, a face row, an empty face row — and `forget(paths)`. The reconcile enumerates `remembered()`, so a visit cannot hide from it. `aDeletedPathHoldingOnlyAVisitIsGone` and `anUnreadablePhotoTrashedAndRestoredIsVisitedAgain` pin it. (ii) **Constraint 2** is a type, not a Boolean: `MediaWalk.complete(media, stillLive)` is the only way to build a whole-library walk and `index()` takes the resulting `WholeLibrary?`; `MediaSource.walk` defaults to `MediaWalk.partial`. `aWalkThatThrowsRemovesNothing`, `aPartialWalkRemovesNothing`, `aSourceThatCannotVouchForCompletenessRemovesNothing` and `aPartialListNeverReconciles` pin it. (iii) **Constraint 3.** `walkImageLibrary()` returns `complete = false` rather than throwing, so a pass still indexes what it could read: any unreadable row (the root included), an unresolved child, an unreadable path, **and** a folder whose stored list is missing or names more members than resolve — `resolveList` hides both, so the walk counts the list's raw member hashes (`ImageLibraryWalkTest`). ⚠ That last check answers the *owed* question: a dangling child reference makes the walk incomplete; it is not treated as normal. (iv) ⚠⚠ **A complete walk is still not a snapshot, so absence needs a second proof.** `MoveFile` unlinks its source before it saves its destination, and the store has no revision to compare — nor would one help, since a move that spans the whole walk changes nothing during it. The reconcile therefore drops a path only when it is absent from a complete walk **and** a point read (`WholeLibrary.stillLive`, i.e. `livePathsAmong`) finds no live file there; a failed or missing point read drops nothing that pass. (v) ⚠⚠ **Removal is a strike and a scrub inside the one log — never a rewrite** (`LogStrikes.kt`). The first build rewrote each log and swapped it in, which needs an atomic replace `FileDatabase` does not have; eleven review rounds of staging markers followed, the worst state being a recovery copy that could resurrect a wiped store. Now: the record's flag byte gains `STRUCK` (the removal, untearable), then its content is overwritten with zeros (the erasure). No second file exists, so there is no recovery step and nothing for a wipe to miss; `remove` does the same, so no tombstone is written any more (legacy `REMOVE` records still replay). The crash-point table is `LogStrikesCrashPointsTest`. ⚠ Cost, accepted: a removed row's length stays in the log as zeros until a wipe or a model change — there is no compaction. (vi) It also carries `livePathsAmong` in `file-service-proxy`, the `faceGroups` and `photosLike` filter mechanism (P0g's half; a store holding only dead vectors answers `NOT_INDEXED`, not an empty success) and the A40 amendment. ⚠ It deletes no `MediaLabel` row (`theReconcileDeletesNoRealmRowNotEvenAModelProducedLabel`). | ☁ **cloud-safe**: `inference-service-proxy` `commonMain`, plus the walk and a liveness helper in `file-service-proxy` `commonMain`. No Control wiring, no `androidMain` | Independent of every other phase. No schema change. |
| **P0f-b** — ⚠⚠ privacy, right after P0f-a — stays **local** (not dispatched: smoke-gated) | **Control wiring. The rules for every event are the table in §6.1; the design, the PR split and the open decisions are §6.4 (v0.13), which supersedes this row where they differ.** (1) `ProxyModule` passes a `livePaths` predicate into `FaceGroupingComponents` (`ProxyModule.kt:261`): *the `File` row exists and `!isTrashedPath`*. People then stops counting a deleted photo on the very next read, with no pass needed. (2) **Seam S1, the dead-path observer:** removes a photo's templates, vector and visit as its `File` row dies, for every leaf, on this device and on a peer (§6.1). ⭐ **`remove` erases, so S1 needs nothing special** (as built in P0f-a): it strikes the path's records and zeroes their bytes in place, exactly as the reconcile's `forget` does — there is no tombstone left to clean up after. (3) **Seam S2, the purge hook:** deletes every `MediaLabel` row keyed by a permanently deleted path, typed names included, for every leaf of a deleted folder, **and emits a tombstone for each row** so peers drop them too (D13, §6.1). (4) **Seam S4, the move hook (D14):** a typed name follows its photo on a move, a rename, a trash and a restore; the old path's model-produced rows and marker are tombstoned (§6.1). ⚠ ~~S2 and S4 ship together~~ (v0.13): S2 finds a trashed photo's names only because S4 moved them to the trash path, so **S4 never merges before S2**; S2 alone is consistent (§6.4.8). ⚠ P0g's filter for Photos' own label reader ships with them (§6.1). ⚠ Name the A40 duty in `threat-model.md` if its wording does not already cover deletion. Tests: a wiring test pins that the production module supplies the predicate, S1, S2, S4 and the tombstone emitter; the peer-ingest test (§6.1 row 9); S2 deletes nothing when `emitAll` answers `null`; the tests named in §6.1's owner column; after S1 the log's bytes no longer name the path. | 📱 **smoke-gated** (HR 8): it changes Control DI, which composes the file, photo and inference services. `scripts/android-smoke.sh` before merge, plus the F0 repro on a device with the face model | Needs P0f-a. |
| **P0g** — F12 | **Read-side existence filter for cited photos.** Mia's `MediaLabel` and `SimilarPhoto` hits (`AskMiaImplementation.kt:855-872`) and `photosLike`'s results drop any path with no live `File` row, or under trash, before they are returned. ⚠ `photosLike` is in `:inference-service-proxy`, which has no file-service dependency (P0f-a), so it filters through the same `livePaths` seam. Its wiring is P0f-b's line. Mia's filter is self-contained: `:mia-service-proxy` already reads `File` rows. ⚠ P0f-a already builds `photosLike`'s filter mechanism against that seam, unwired. ⚠⚠ **A third reader (v0.9, D14):** Photos reads every `MediaLabel` row unfiltered (`photos-shared-logic/.../TenshuPhotosRepository.kt:1204-1231`). Since D14 a trashed photo's name is a row at a trash path, so that read must drop `isTrashedPath(filePath)` rows at least; the module already imports the trash root (`:69`). **[I]** Dropping rows for paths that are dead but not in trash needs a liveness read on the client and is not designed. Tests: a deleted photo's label is not cited; a trashed photo's label is not cited until restore; Photos' label read returns no row at a trash path. | ☁ for Mia (`mia-service-proxy` `commonMain`, no Control wiring) and for Photos' reader (`photos-shared-logic` `commonMain`); `photosLike`'s half rides P0f-b (📱 smoke) | Independent; ships with or before P0f-b. |
| **P0** — 🚀 dispatched 2026-10-02, `perf/media-p0-index-job-network` | Index schedule → `NETWORK_NONE` (D7). Correct `MediaIndexSchedule` KDoc and `inference-service.md` §26.2. Correct `drive-synced-folders.md` §7.4's `O(CHUNK)` claim (F2). | ☁ code + docs; 📱 one `dumpsys jobscheduler` check | `ExistingPeriodicWorkPolicy.UPDATE` re-applies constraints on next start. No schema change. |
| **P1a** — 🚀 dispatched 2026-10-02, `perf/media-p1a-single-decode-ladder` | `ThumbnailLadderRenderer` seam in `commonMain` + JVM impl (subsampled read, cascade, re-encode-only step-down, contentHash instead of SHA-256), behind the existing `GetPreview` fallback. Bench test pins "1 decode per upload" with a counting decoder. | ☁ | Desktop gains directly. No op/wire change (HR 9 untouched). |
| **P1b** | Android impl: bounded decode, cascade, one poster extraction per video. Drop the `.previews/` double write for minted rungs (F10). | ☁ writes; 📱 HR 8 smoke + on-device timing of 20 uploads before/after | `androidMain` ⇒ smoke gate. |
| **P1c** | iOS impl: `CGImageSourceCreateThumbnailAtIndex`; one temp file per video, not per rung (F10). | 🖥 + 📱 | Hand off per `photos-ios.md` §6. |
| **P2** | Android upload reads ≤ a bounded size into memory; above it, stream from the descriptor to `FileDatabase` and hand later stages a path (F2). | ☁ design + commonMain; 📱 a 200 MB video on a **throwaway** emulator realm | Verify F2's OOM first. If it does not reproduce, downgrade F2 and stop here. |
| **P3** — 🚀 dispatched 2026-10-02, `perf/media-p3-cpu-budget` | `MediaWorkBudget` lane (k = 2) + ORT `SessionOptions` (`intraOpNumThreads`, spinning off) in `onnxMain`. | ⚠ **Split by HR 8.** **P3-a ☁**: the `MediaWorkBudget` type and policy in `commonMain`, unwired, with JVM tests pinning the lane bound. **P3-b 📱 smoke-gated**: ORT `SessionOptions` in `onnxMain`, which compiles into the Android backend, plus injecting the budget through Control's `ProxyModule` / `PlatformProxyModule`. Smoke gate + the IN-3m re-run (D4). ⚠ The dispatched session was briefed as one phase; at review its PR must either stop at P3-a or carry a smoke run. | `onnxMain` is shared JVM/Android. |
| **P4** — 🚀 dispatched 2026-10-02, `perf/media-p4-scheduler-retry` | `ScheduleDrainLoop` runs each due item in its own child job with per-key single flight, so a long pass no longer blocks the loop (F8). A scheduled pass that was deferred is retried soon, not after 12 h (F3.1): the op throws `ScheduledRunDeferred`, and the same row fires again. In-process that is a fixed 15 min. ⚠ On Android it is 15, 30, 45 … min, by design. ✅ **Decided 2026-10-02: option B, as built in P4.** The generation guard and the three review fixes are built at `7d8c1fc58`. ⚠⚠ Still owed before it merges: re-review, the smoke gate, and any device verification. All of it is in **§6.2**. | ⚠ **Split by HR 8.** **☁** for the loop, the signal, the guard and the retry accounting: `sync-service` and `sync-service-proxy` `commonMain`, JVM tests. **📱 smoke-gated** for the Android half: P4 edits `androidMain` (the worker, the WorkManager backoff and the deferral-count store). | ⚠ Keep the `HeadlessSafe` re-check. No stored or wire shape changes. |
| **P5** | **Measurement only:** on a charging, screen-off emulator with a fixture model (or the real one on a maintainer device), count photos indexed per WorkManager window and windows per night. Record job stop reasons from `dumpsys jobscheduler`. Settles D6. Also: faces from XXLARGE vs original on the verified 400-photo set (D1). | 📱 | ⚠ `inference-service.md` §15.1: emulator timings are not phone timings. Use the emulator for *scheduling* facts only. |
| **P6a** | Derive queue (device-local log) + `MediaIndexRunner.enqueue(paths)` subset runs + `mediaWorkAllowed(tier)` policy in `commonMain` with fake probes. | ☁ | Same single-owner rule as `EmbeddingStore` on iOS (`photos-ios.md` PIO-D10). |
| **P6b** | Hook: `UFI` appends to the queue after the row save. Control wires the drain (D3). | ☁ code; 📱 HR 8 smoke + "a fresh camera shot is searchable within a minute" | Touches Control DI ⇒ smoke gate. |
| **P7** | `IndexImage`: one upright bounded decode per visit, fed to every half (D12). SigLIP gains EXIF orientation (F6) ⇒ provenance `labelSetVersion` bump ⇒ one re-index. | ☁ seam + fake; 📱 `FaceBackendParityTest` must stay identical | Face output must not move. If it does, the shared decode differs from `MPF`'s and that is a bug. |
| **P8** | Platform probes for thermal and power-save (Android, macOS) + the D5 thresholds. | ☁ policy; 📱 Android probe; 🖥 macOS probe + iOS stub | iOS wiring rides `photos-ios.md` PIO-7. |
| **P9** | Upload pipeline (D2): the op returns at the §4.4 C1/C2 boundary; the content drain does the network `PUT`s with file-level depth 2; MOTION moves to the derive drain (D8). ⚠⚠ **Hard constraints, each pinned by a test that fails without it:** **(C1)** the op calls `awaitEmitIdle()` (`UFI:488`) before any `File.Data` or rung intent becomes drainable — the entity-before-bytes barrier is owned by the op, never by the drain. Test: a joiner fixture must never receive a manifest whose parent `File` row it has not applied. **(C2)** every minted still (LIST, LARGE, XLARGE, XXLARGE) is written to `FileDatabase` and its intent journalled **before** the op returns. Only the `PUT` is deferred. LIST/LARGE are `backfilled = false`, so a rung lost in memory is lost to every peer for good. Test: kill the process (cancel the scope) right after the op returns; the next drain must publish all four rungs. | ☁ commonMain + JVM tests (both constraint tests are JVM); 📱 backup soak (200 photos + 5 videos, Wi-Fi on/off, one forced Control kill mid-backlog) | No relay or wire change ⇒ no threat-model amendment. Re-read `drive-upload-progress.md` P4/P7 before starting. |
| **P10** | ✅ **Narrowed 2026-10-03 (P0fb-D3): S3 moved to P0f-b PR-5 (§6.4.5); P10 is the content-hash key and B2.** Content-keyed freshness and invalidation for **every** derived artifact (D11). ⚠⚠ **Hard constraints:** **(a) Device-local stores:** `EmbeddingStore` **and** `FaceTemplateStore` are both invalidated on replace and delete. P0f-a's reconcile already covers delete and trash; P10 adds replace, by keying `visited()` on path + `File.ContentHash`, for a vector, a vectorless visit and a face row alike. **(b) Synced labels.** **[C]** `MediaIndexer.isCurrent` probes the marker row keyed `(path, capability, __indexed)` and compares only model provenance (`MI:617-638`; `MediaLabel.labelKey`, `inference-service/.../MediaLabel.kt:47-66`). So a file replaced at the same path is judged current, and its old model-produced `MediaLabel` rows survive: a *dog* label on what is now a receipt. On **replace**, P10 **deletes the marker and every model-produced label row for that path**: rows whose `producedBy` parses as `ModelProvenance`, under a model-produced capability. The next pass re-derives them. ⚠⚠ **Who deletes, and whether peers see it (v0.9, §6.1).** A row delete does not sync by itself; only an emitted tombstone does. **The replace hook (S3)**, on the device that performs the overwrite, deletes and **tombstones** those rows. **The pass**, on any device, deletes them **locally only**: when it finds its own visit for the path recorded under a different content hash, and when a path is absent from a complete walk (B2). It then derives the labels again, and those saves sync as ordinary rows. ⚠ A path this device has never visited invalidates nothing. Otherwise a newly paired device would delete every label it had just received. ⚠ **HR 9: choose invalidation, not content-keying.** Deleting `MediaLabel` rows, with a tombstone where §6.1 says so, changes no stored shape, so no bump. Content-keying the marker would mean adding a field to `MediaLabel`, a service-owned synced composite in `:inference-service`. That is a reshape: `inference-service` version bump + `SchemaMigration`, and every client in lockstep. Rejected for that cost. ⚠⚠ **User-typed person names** (`capability = faceClustering` rows the user typed, IN-D5) are **not** model-produced. A replace **keeps** them (**D13, decided 2026-10-02**), so P10's replace path never deletes something a person typed. A **permanent delete** removes them with the file (D13 as amended), and **only P0f-b's purge hook does that**. ⚠⚠ **P10's backstop never deletes a typed name (v0.7; round 7).** v0.6 had the reconcile delete every label row whose path had *no `File` row and no trash record*. The indexer cannot evaluate that: **[C]** `:inference-service-proxy` has no `:photo-service` dependency (`inference-service-proxy/build.gradle.kts:95-101`). And *"the path has no `File` row"* is not *"the photo was deleted"*. Since D14 a moved photo's name follows it, but only when the move was made by a build that has S4. **[I]** A move from an older build, or one whose label blocks have not arrived yet, leaves a name at a path with no row while the photo lives on elsewhere. Reading that as *purged* would delete a name a person typed. **Rule:** the backstop deletes only what can be re-derived: the marker and the model-produced rows, for paths absent from a **complete** walk (P0f-a's constraints 1–3 apply). A false *gone* then costs a re-derivation and never user data. That needs no seam, so the indexer half stays ☁. A purge on another device arrives as the tombstones its S2 emitted. The backstop emits none. **Cost, accepted:** if the purging device's hook failed, or it runs an older build, the typed name stays as a dead row. P0g's read filter keeps it from being cited. Tests: replace-at-same-path re-indexes, **tombstones** the old model-produced labels through S3 and **keeps** a typed name; **a peer that ingests those tombstones holds no old model-produced row**; **the pass's own invalidation emits no tombstone** (a recording emitter stays empty); a never-visited path invalidates nothing; a path absent from a complete walk loses its marker and model-produced rows locally and **keeps** its typed name; a typed name at a path with no `File` row survives a complete pass; a subset pass deletes no label. The purge tests are P0f-b's. | ⚠ **Split by HR 8.** **☁** for the indexer half: content-hash-keyed `visited()`, and the pass's **local** marker/label invalidation (B2 and the changed-hash check). It needs no emitter. **📱 smoke-gated** ~~for the replace hook S3, whose consumer and tombstone emitter are composed in Control, and~~ (S3 is P0f-b PR-5 since v0.13) for threading `File.ContentHash` through the `MediaSource` built in `ProxyModule.kt:382-410` (Control DI). The delete hook S2 and the move hook S4 are P0f-b's; the per-event rules are §6.1. | Device-local store-format change for the content-hash key; write the rebase or migration. No synced reshape by construction. |
| **P11** | Videos indexed through the still poster (D10). ⚠⚠ **Hard constraint — a video's source never offers its original (v0.9; review round 9).** **[C]** `MediaIndexer` reads `item.bytes()` first and asks for the preview only when that is `null` (`MI:404-408`). Widening the walk filter alone would hand a locally stored video's container to the image backend. It would be judged unreadable, recorded as visited (`MI:428`) and stored as an empty face row (`MI:516`), so the poster would never be read, on any later pass. **Rule:** the walk tags each entry image or video. For a video the `IndexableMedia` is built with `bytes = { null }` and `preview = previewForIndex(…)`, so the existing fallback is the only path. ⚠ **Which poster.** `previewForIndex` reads the stored `XLARGE` rung file, then `XXLARGE`, then pulls `XLARGE` from the relay when unmetered (`IndexPreview.kt:38-84`). It reads rung files directly. It never calls `GetPreview` and never touches the `MOTION` class, so it stays inside the standing rule that `MOTION` is not part of `GetPreview`'s ladder (`photos-video-support.md:184`). ⚠⚠ **The indexer cannot count on 1024 px. [D]** `drive-synced-thumbnails.md` §18: a `GENERATE_FRESH` render for a local video with only a `LARGE` poster stored returned those 256 px bytes, and they were stored under the class that was asked for. Rows minted before that fix can hold a 256 px image named `XLARGE`. iOS extracts video posters at ≤1024 px (`IGP:347-386`), and the JVM has no video frame decoder, so desktop holds a poster only when another device minted it. **Rule:** read the poster's bounds first. Below a floor (512 px on the short edge, the size the SigLIP decoder already works at, `AndroidImageDecoder.kt:17-30`), count it as `notOnDevice` and **do not** mark it visited, so a later, better rung is indexed. **Owed (📱):** how many video `XLARGE` rungs in a real library are under 1024 px. Tests: a locally resident video is indexed from its poster and its original is never read; a video with no poster is counted `notOnDevice` and not visited; a 256 px poster is not embedded and not visited. | ⚠ **Split by HR 8.** **☁** for the walk's tag, the bounds floor and the tests, with a fake source. **📱 smoke-gated** for the line that builds a video's `IndexableMedia`: it is in the `MediaSource` of `ProxyModule.kt:382-410` (Control DI). | Walk filter at `ImageLibraryWalk.kt:63`. Faces on a poster are subject to D1's measurement (P5). |
| **P12a** | Pets P1a: prompt set + calibration harness over stored vectors; `MediaLabel` output under a consent. | ☁ logic; 📱 calibration on a real library | §35 floor discipline: probability, never cosine. |
| **P12b** | Pets P1b: own design doc first (detector, crop embedder, store, consent, A40-style threat-model note). | design ☁; build 📱 | Not started by this doc. |
| **P13** | Desktop: `FolderWatcher` creates its destination chain and stops advancing the watermark on failure (F9). Desktop backfill honours `mediaWorkAllowed`. | ☁ (JVM test first: reproduce the `NotFound`) | Incidental to this sweep. |

### 6.1 Every way a photo's path stops resolving

Review rounds 3 to 8 each found one more hole in the delete, trash and purge rules. This table is
the whole rule set. **Where another sentence in this doc disagrees with it, the table is right.**

**What the code does today [C].**
- **Every `File` row dies through one call.** `ExecutionContext.deleteData` removes the row and
  fans out to every `DataObserver.onDataDeleted`, inside the store lock
  (`core-common/.../proxy/ExecutionContext.kt:663-668`). It is the only place every leaf passes
  through, on the acting device and on a peer:
  - a local delete walks the tree and deletes each entity (`DeleteFileImplementation.kt:158-205`,
    the call at `:203`);
  - a peer gets **one** tombstone for the root (ADR 0006 decision 7), applies it with `deleteData`
    (`SyncProcessor.kt:265`), and `DeletedFolderSubtreeReaper` then deletes each descendant through
    `deleteData` again (`file-service-proxy/.../FileServiceProxyInitialization.kt:192-221`);
  - a move, a rename, a trash and a restore save a row at the new path and retire the old one
    through `DataIdMigrator`, which calls `deleteData` (`sync-service-common/.../DataIdMigrator.kt:315`).
    A folder move recurses, so each descendant is retired by its own move
    (`MoveFileImplementation.kt:353`).
- **So the observer cannot tell a delete from a move.** On a peer both arrive as a tombstone for the
  old id, and `SyncProcessor` does not surface the successor. Anything that must not happen on a
  move cannot hang off the observer.
- **Only the delete op knows a delete is permanent,** and only its walk sees every leaf's path
  before the rows go. It already collects per-leaf facts there for the reclamation recorder
  (`DeleteFileImplementation.kt:98-103,188-202`).
- **A replace kills no row.** An overwrite is an in-place property merge (`UFI:132-140`), so no
  observer fires.
- **Trash is Photos' own.** It is a `MoveFile` to `/Trash/<contentId>-<name>` and then a separate
  `recordTrashedPhoto` (`photos-shared-logic/.../TenshuPhotosRepository.kt:888-897`). A purge is
  `deleteFile(trashPath)` and then `clearTrashedPhoto` (`:909-917`). *Empty trash* is one purge per
  item (`TrashViewModel.kt:93-98`). No `drive-*` module references `TRASH_ROOT`, so a Drive delete
  is permanent at the file's own path.
- ⚠⚠ **Deleting a synced row does not delete it on a peer** (v0.9; review round 9). **[C]**
  `SyncObserver.onDataDeleted` is a deliberate no-op (`SyncObserver.kt:1415-1419`). A delete reaches
  a peer only when the op that made it also emits a tombstone, as `DeleteWorkout` does: `deleteData`,
  then `tombstoneEmitter.emit` (`health-service-proxy/.../DeleteWorkoutImplementation.kt:59-72`). A
  batch goes through `emitAll`: one queue write and one wake (`TombstoneEmitter.kt:102`). The peer
  applies it in `processDeleteBlock` (`SyncProcessor.kt:213-266`): the row is deleted unless it was
  written after the tombstone, and a later save recreates it.
  - **Nothing in `:inference-service-proxy` deletes a `MediaLabel` row or emits a tombstone today.**
  - A local delete with no tombstone stays deleted on that device: `TombstoneRegistry` stamps a
    local short-circuit (`TombstoneRegistry.kt:253-284`). Peers keep the row.
  - `DeleteBlock` carries no successor (`sync-service/.../DeleteBlock.kt:71-76`), so a peer cannot
    tell a move's retiring tombstone from a delete's.
- **A label is keyed by the path the photo had when it was indexed** (`MediaLabel.labelKey`), and
  nothing in `:file-service-proxy` or `:inference-service-proxy` re-keys it on a move today. Its
  `DataId` is derived from that key, so a label cannot be renamed in place: following a move means a
  new row and the death of the old one.
- **`MoveFile` already makes one kind of path-keyed row follow the file.** `rekeyHistory` saves
  each history row again under the new path, before the old `File` row is retired
  (`MoveFileImplementation.kt:451,560-580`). Each save is an ordinary synced block. It runs in every
  `moveFile` call, and a folder move calls `moveFile` once per descendant (`:367`).

**The two seams P0f-b builds, and the two backstops.**

| | What | Owner, provider, injection | Sees |
|---|---|---|---|
| **S1** — dead-path observer | A `DataObserver` that forwards the path of every deleted `File` row. The consumer removes that path from both stores. ⚠ It runs inside the store lock and inside sync ingest's fan-out, where a throw takes down the tick. So it only enqueues the path; a coroutine calls the suspending `remove`. | The observer lives in `:file-service-proxy`, which owns `File.ID` and already registers `DeletedFolderSubtreeReaper` (`FileServiceProxyInitialization.kt:115`). It forwards to a `fun interface` passed into `initFileService`. Control supplies the inference-side consumer (`PlatformProxyModule`, e.g. Android `:872`). | Every leaf, local and peer: delete, folder delete, move, rename, trash, restore. **Not** a replace. |
| **S2** — purge hook | `DeleteFileImplementation` collects each leaf's path ~~during its walk and hands the list to an injected hook after the walk~~ **before its first mutation and hands the dead ones to the hook in a `finally`** (v0.13, §6.4.3: closes §6.3 finding 1). The consumer deletes every `MediaLabel` row keyed by each path **and tombstones each one**, in one `emitAll` batch. **It acts on the path it is handed and maps nothing** (D14): a trashed photo's names are at its trash path already. ⚠ `emitAll` answering `null` means the rows could not be named for the wire (realm locked): the consumer then deletes **nothing**, per that method's contract. | The hook parameter is `:file-service-proxy`'s. The consumer is `:inference-service-proxy`'s. The emitter is the `TombstoneEmitterHolder` Control already hands to the file and health services; `:inference-service-proxy` already depends on `:sync-service-common` (`build.gradle.kts:101`). Control's `ProxyModule` composes them. | Every leaf of a **permanent delete made on this device**. Peers get its tombstones. Nothing on a move. |
| **S4** — move hook (D14) | `MoveFileImplementation` calls an injected hook with `(oldPath, newPath)` for every row it moves, at the point where it re-keys history: **before** the old row is retired (`MoveFileImplementation.kt:451`). **v0.13:** through an operation-scoped `MoveScope`, so the consumer builds one label index per operation and tombstones once (§6.4.4: closes §6.3 finding 3). A folder move recurses, so every descendant passes through it by its own `moveFile` call (`:367`). The consumer does three things for the old path, in this order: **(1)** saves each typed name again under the new path; **(2)** deletes the old typed-name rows; **(3)** deletes the old model-produced rows and the marker. (2) and (3) are tombstoned in one `emitAll` batch. | As S2: the hook parameter is `:file-service-proxy`'s, a lambda like `moveFence` (`:72`). The consumer and the emitter are composed in Control. | Every row of a **move, rename, trash or restore made on this device**. Peers get its saves and its tombstones, and run no re-key of their own. |
| **B1** — the reconcile (P0f-a) | At a complete pass, drop every path the stores remember that the walk no longer offers **and that a point read confirms has no live file** (a walk is not a snapshot: a move in flight unlinks its source first). | `:inference-service-proxy`, no seam. | Whatever S1 missed. Only when a pass runs. |
| **S3** — replace hook (P10's hook half) | The overwrite path (`UploadFile` with `replaceExisting`, `UFI:132-160`) reports the path whose bytes changed. The consumer calls `remove` on both stores, deletes the marker and the model-produced rows, and tombstones them through `emitAll`. **Never a typed name.** | As S2: a hook parameter in `:file-service-proxy`, the consumer and the emitter composed in Control. | A replace made on this device. Peers get its tombstones. |
| **B2** — the label backstop (P10) | At a complete pass, delete **locally** the marker and the model-produced rows of paths the walk no longer offers. **Never a typed name, and never a tombstone.** | `:inference-service-proxy`, no seam and no emitter. | Whatever S2 and S3 missed, for re-derivable rows, **on this device only**. |

**The rule for label deletes: the op that performs the event tombstones; a pass only deletes
locally.**

| Who deletes | Rows | Reaches peers? | Why |
|---|---|---|---|
| **S2**, on a permanent delete | every row: typed names, model-produced rows, marker | **Tombstoned**, one `emitAll` batch | The op knows the delete is real, and exactly one device emits. A typed name has no other way to leave a peer. |
| **S3**, on a replace | marker + model-produced rows | **Tombstoned**, one `emitAll` batch | Same. Without it a peer that never indexes keeps the old *dog* label for ever. |
| **S4**, on a move | the old path's typed names (after saving them at the new path), model-produced rows and marker | **Tombstoned**, one `emitAll` batch. The new typed-name rows sync as ordinary saves. | Same. A typed name's id is derived from its path, so following a move is a save at a new id and a delete at the old one. |
| **B2**, at a pass | marker + model-produced rows | **Local only** | See below. |

**Why B2 does not emit.**
- **Its trigger is a view, not an event.** *"Absent from my walk"* is also true of a trashed photo
  and of a moved one, and a device that lags sees it late. **[I]** A tombstone from a lagging device
  carries a later HLC than labels a peer has just re-derived after a restore, so it would delete
  fresh rows on every device.
- **Every indexing device would emit the same tombstones.** The relay keeps `DeleteBlock`s
  indefinitely (ADR 0006 decision 6). A renamed folder of 5,000 photos would leave that many
  tombstones per device, for rows that can be derived again.
- **Local-only is stable. [C]** The local short-circuit keeps the row from coming back from an older
  block, and a peer's newer save is still accepted.
- **Cost, accepted.** Where B2 is the only thing that acts (rows 13 and 16), a device that never
  runs a pass keeps the dead model-produced rows: iOS today, desktop on Windows and Linux, the web. P0g hides them from Mia and `photosLike`. ⚠ **[C]** Photos
  reads every `MediaLabel` row unfiltered (`TenshuPhotosRepository.kt:1204-1231`). **[I]** So its
  label search can show a hit for a dead path on such a device. **Owner: P0g**, which owes that
  third reader the same filter.

**What tombstoning costs, stated.**
- **Volume.** S2 emits one `DeleteBlock` per label row, and the relay keeps them. Deleting a folder
  of N indexed photos is at least N marker tombstones per capability, against one tombstone for the
  files themselves. Labels are inert today, so that is the whole of it. **Owed (📱 or a JVM count):**
  the number for the maintainer's library before P12a adds real labels.
- **A recreate race. [I]** A peer that has not yet applied the file's delete can run a pass and save
  model-produced rows after S2's tombstone. Those rows are newer, so they win and return on every
  device. That peer's own B2 removes them locally once the delete arrives; a device that never runs
  a pass keeps them. A typed name cannot come back this way: no pass writes one.

**The table.** "Stores" is (a) face templates and (b) vectors and vectorless visits. They are
treated the same in every row. "Peer" means a device that applies the event from sync. In (c) and (d), **tombstoned** means
deleted here and on every peer; **local** means deleted on the device that ran the pass, and nowhere else.

| # | Event | (a)+(b) Stores | (c) Model-produced labels + marker | (d) Typed names | Owner · test |
|---|---|---|---|---|---|
| 1 | **Delete one photo** (Drive; permanent) | Removed at once by S1. Else by B1. | **Tombstoned** by S2. If S2 did not run: local, by B2. | **Tombstoned by S2.** | P0f-b · *a Drive delete deletes the labels at its own path, and both stores forget it* |
| 2 | **Delete a folder** (Drive; permanent) | Removed per leaf by S1. | **Tombstoned** per leaf by S2, in one batch. | **Tombstoned per leaf by S2.** | P0f-b · *deleting a folder that holds two named photos leaves no label and no store row for either, and emits a tombstone for each label row* |
| 3 | **Trash** (Photos) | Removed by S1: the old path dies in the move. Else by B1, since the walk skips trash. | **Tombstoned** at the old path by S4. Nothing derives them at a trash path: the walk skips it. | **Re-keyed to the trash path by S4** (D14): saved there, tombstoned at the old path. Hidden from every reader while trashed (below). | P0f-b · *trashing a named photo leaves its name at the trash path and none at the old one* |
| 4 | **Restore** | Nothing is held for the path, so the next pass derives it again. | Derived again by the next pass on an indexing device; the saves sync as ordinary rows. | **Re-keyed back by S4.** | P0f-b · *trash, then restore: the name is at the original path again, and the photo is re-indexed* |
| 5 | **Purge from trash; empty trash** | Nothing is held for a trash path. | None exists at a trash path. S2 tombstones any that does. | **Tombstoned at the trash path by S2**, where D14 put it. | P0f-b · *purging a trashed photo leaves no label row for it on this device or on a peer* |
| 6 | **Move or rename a photo** | Old path removed by S1. The new path is derived again by the next pass. ⚠ **Not carried over**: that is one re-inference per moved photo. | Old rows **tombstoned** by S4. New rows are derived by the pass that re-indexes the photo anyway, and sync. | **Re-keyed by S4** (D14): saved at the new path, tombstoned at the old one. | P0f-b · *a moved photo keeps its name at the new path, and no label row remains at the old one* |
| 7 | **Move or rename a folder** | As 6, for every descendant. A renamed folder is re-indexed whole. | As 6, per descendant. | **Re-keyed per descendant by S4.** | P0f-b · *renaming a folder that holds two named photos keeps both names, at the new paths* |
| 8 | **Replace at the same path** | Removed by S3 on the replacing device. ⚠ Without S3, stale until P10's content-hash key: no row dies, so neither S1 nor B1 sees it. | **Tombstoned** by S3. The marker is gone, so the next pass on any indexing device derives them again. | **Kept** (D13). | P10 · *replace-at-same-path re-indexes, tombstones the old model-produced labels and keeps a typed name* |
| 9 | **Peer: a photo delete arrives** | Removed by S1 on the peer. | Deleted when the deleting device's S2 tombstones are ingested. The peer emits nothing. | Deleted when S2's tombstones are ingested. **The peer runs no purge logic of its own.** | P0f-b · *a delete that arrived by sync removes the path from both stores with no pass*; **the peer-ingest test:** *a peer that ingests a purge's label tombstones holds no label row for the path, typed name included* |
| 10 | **Peer: a folder delete arrives** | Removed per leaf by S1, through the reaper's cascade. ⚠ If the reaper degrades (unreadable child array), the subtree is stranded until the orphan sweep deletes it (`OrphanedFileSweeper.kt:157`), which fires S1 then. B1 covers the gap. | As 9. | As 9. | P0f-b · *a folder tombstone removes every descendant's store rows on the peer* |
| 11 | **Peer: a move, trash or restore arrives** | As 3, 4, 6 and 7: the old id's tombstone fires S1. | Old rows deleted when S4's tombstones are ingested. | Arrives as the mover's save at the new path and its tombstone at the old one, in either order (below). **The peer runs no re-key.** ⚠ This is why S1 must never delete a label. | P0f-b · *a retiring tombstone deletes no label*; *a peer that ingests a move's label blocks holds the name at the new path only, whichever arrives first* |
| 12 | **Peer: a replace arrives** | Stale until the peer's next pass, where P10's content-hash key finds the visit out of date. | Deleted when S3's tombstones are ingested; derived again by whichever indexing device runs a pass first. | Kept. | P10 · *a peer that ingests a replace's tombstones holds no old model-produced row* |
| 13 | **Delete, purge, replace or move made where S2 / S3 / S4 do not run**: an older build, or any proxy without the hooks | On this device: S1 when the delete or move syncs in. A replace: P10's content-hash key at the next pass. | No tombstone exists. **Local**, by B2 or by P10's hash check, on devices that run a pass. ⚠ Other devices keep the dead or stale rows. | ⚠⚠ **NOT HANDLED, accepted.** No tombstone and no re-key exists. After a delete the name stays as a dead row on every device. After a move it is stranded at the old path and the photo is unnamed at the new one. P0g keeps a dead name from being cited. | No owner. Revisit only if dead rows are seen to accumulate. |
| 14 | **S1's queue is lost** (process death), or a store write fails | B1 at the next complete pass. | — | — | P0f-a |
| 15 | **Realm wipe** | Both stores `clear()` (`inference-service.md` §34.6). | Go with the store. | Go with the store. | Built. No phase. |
| 16 | **Rows written before D14 ships**, for a photo that was already moved, or is in the trash when it ships | — | Dead rows at the old path: **local**, by B2. | ⚠ **NOT HANDLED, accepted.** A name whose photo was moved earlier stays stranded at the old path. A photo already in the trash has its name at the original path: a restore finds it there, and a purge leaves it as a dead row. Nothing cleans typed names: B2 never deletes one. | No owner. No migration: S4 has no way to know where a photo moved before it existed. |

**What the table changed from earlier versions.**
- v0.7 gave S2 one path. **It receives every leaf** (rows 2 and 5). This is review round 8's finding,
  and it was valid: `deleteRecursively` removes every descendant, and B2 never deletes a typed name.
- v0.7 put the store removal on *"Control's delete and trash paths"* and on a `MoveFile` into trash.
  **It is one observer, S1**, which also covers folder deletes, peers and plain moves.
- **S1 never deletes a label and S2 never runs on a move.** Rows 3, 6, 7 and 11 depend on it.
- **v0.10, D14: typed names follow a move.** Rows 3, 4, 5, 6, 7 and 11 changed, row 16 is new, and
  `TrashOrigins` is gone. v0.9 had rows 6 and 7 as *not handled* and their old model-produced rows
  as *local*. They are now re-keyed and tombstoned by S4.
- v0.8 called S2's and P10's deletes *"synced deletes"*. **They are not, unless the op emits a
  tombstone** (review round 9, valid). S2, S3 and S4 emit; B2 deliberately does not. Rows 1 to 12
  say *tombstoned*; rows 13 and 16 say *local*.

**D14 in detail: how a typed name follows a move (seam S4, built by P0f-b).**

*Which rows move.*
- **Typed names: re-keyed.** They cannot be derived again.
- **Model-produced rows and the marker: tombstoned at the old path, not re-keyed.** Two reasons.
  - The photo is re-indexed at its new path anyway, because the stores are keyed by path and S1
    removed them. Deriving its labels in that same visit costs little.
  - **[I]** Re-keying could carry a stale label across a replace. Device A replaces the bytes while
    device B, which has not seen that yet, moves the photo. B would copy the old *dog* label and its
    marker to the new path. A's tombstones name the old keys, so they would miss the copies, and the
    marker would call them current for good. Tombstoning has no such case.
- ⚠ The cost is the first column of rows 6 and 7: a moved photo is inferred again. **[I]** Carrying
  the stores over belongs to P10's content-hash key and is not designed.

*What the moving device emits, in order.*
1. **A save per typed name at the new path** (`saveData`, an ordinary synced block, as
   `rekeyHistory` does).
2. **One `emitAll` batch** of tombstones for the old path's rows: the typed names just copied, the
   model-produced rows and the marker. The local rows are deleted only if `emitAll` did not answer
   `null`.

Save first, tombstone second. A crash in between leaves the name at both paths, and the old one is
a dead row. It never leaves it at neither.

*What a peer holds.* **[C]** `DeleteBlock` has no successor field, and a label row does not depend
on a `File` row on the wire. So the label blocks and the file's blocks arrive in any order.
- **File move first, label re-key later.** For a while the name is still at the old path, whose
  `File` row is gone. The read filter hides it, and the photo shows unnamed at its new path. Then
  the save and the tombstone land.
- **Label re-key first, file move later.** For a while the name is at the new path, which has no
  `File` row yet. The read filter hides it. Then the file arrives.
- Either way the peer ends with the name at the new path only. The gap shows a missing name, never
  a wrong one, and S1 deletes no label in between.
- **[I]** A pass on the peer during the gap can delete the model-produced rows locally (B2). They are
  derived again. It cannot touch the typed name.

*Idempotence.* **[C]** A half-applied folder move is resumed by `resumeMove`
(`MoveFileImplementation.kt:126-134`), which walks what is left under the source. S4 must tolerate
a second call for a path it already handled: the old rows are gone, so it does nothing.

*Cost.* **[C]** `rekeyHistory` finds its rows by a linear scan of the realm-wide history log, once
per moved file, because the op layer has no predicate query (`:552-580`). **[I]** S4 doing the same
over every `MediaLabel` row makes a folder move of N photos cost N scans. **Rule:** one index of
labels by path per move *operation*, not per descendant. Tombstone volume is the same order as the
file move itself, which already emits one retiring tombstone per descendant.

*HR 9.* **No stored or wire shape changes.** Rows are saved and deleted under the existing
`MediaLabel` schema, and the hook is a constructor parameter, not an op parameter. No version bump.

*A label at a trash path must stay hidden.*
- **Mia and `photosLike`:** P0g's filter drops a path with no live `File` row **or under trash**.
  A name re-keyed to `/Trash/…` has a trash path as its own `filePath`, so that test covers it.
  **[C]** P0f-a's `livePathsAmong` is `!isTrashedPath(path) && contains(…)`.
- ⚠ **Photos' own label read** returns every `MediaLabel` row unfiltered
  (`TenshuPhotosRepository.kt:1204-1231`). **[I]** With D14 a trashed photo's name is a row at a
  trash path, so that reader can count or show it. **v0.13 [C]:** narrower than that: Things and
  People join against the feed, which excludes trash, so only `searchByLabel` leaks (§6.4.0 item 3). **Owner: P0g**, which owes that reader the same
  filter. D14 makes it a requirement of P0f-b's merge, not a follow-up.

**Two limits of the read-side filter, stated.**
- `livePaths` is *"the `File` row exists and is not in trash"* (P0f-a's `livePathsAmong`). A subtree
  stranded by a degraded reaper (row 10) still has its rows, so the filter calls it live until the
  orphan sweep runs. B1 does not have this limit: it works from the walk.
- P0g hides a dead label from Mia and `photosLike`. It does not delete it, and it does not yet
  cover Photos' own label reader (above).

### 6.2 P4 in detail

**Fixed part (F8).** `ScheduleDrainLoop` dispatches each due item in its own child job, with per-key
single flight. A long pass no longer blocks the loop.

**The retry contract (F3.1): the problem.**
- **[C]** `MediaIndexPassImplementation.execute` drops `IndexPassResult.deferred` and returns `null`
  (`MediaIndexPassImplementation.kt:63,85`).
- **[C]** `ScheduleDrainLoop` sees only *threw / did not throw* (`SDL:98-111`) and resets every
  periodic accumulator either way (`SDL:114-115`).
- So "re-check a deferred item sooner" needs a signal.

#### ✅ DECIDED 2026-10-02 (maintainer): option B, `ScheduledRunDeferred`, as built in P4

**The mechanism [C at `7d8c1fc58`].**
- A scheduled pass that was deferred by device state throws a Kotlin-only
  `ScheduledRunDeferred(retryAfterMs)` (`sync-service/.../ScheduledRunDeferred.kt:35`, thrown at
  `MediaIndexPassImplementation.kt:92` on the branch), with `retryAfterMs` = 15 min.
- The in-process loop fires the **same** row again after that delay. The row is untouched: still
  `PENDING`, `attempts` unchanged.
- The Android worker returns `Result.retry()`. The wait that follows is WorkManager's, not
  `retryAfterMs` (below).
- A pass that completes, is paused, or has no consent or no backend returns normally, so the 12 h
  period stands. A retry cannot change any of those.
- **There is no second `ScheduleItem`, no retry dedup key and no re-registration during a run.**
- It is a new, general third outcome for any scheduled op. It never crosses a wire and changes no
  stored or declared shape, so HR 9 does not apply. ⚠ v0.2–v0.6 rejected a general signal on HR 9
  grounds. That reasoning covered a typed *result* or a new `TError`, not a plain exception.

**Option A, rejected: a one-shot retry item** (this doc's design in v0.2–v0.7). A deferred pass
would have armed a second persisted `ScheduleItem` under its own key. It would have given a fixed
15 min on Android too. Rejected because:
- **Two drivers [C].** `ScheduleImplementation` persists a row and also hands it to WorkManager
  (`ScheduleImplementation.kt:86,94`). `TenshuOperationWorker.doWork` dispatches and returns
  (`:46,54`) and never completes the row. So after WorkManager had run the retry, the row would
  still be `PENDING` and the in-process loop would run it again.
- **A schedule write on every deferral [C].** Each re-arm would go through the schedule writer. On
  master that removed the old row before it added the new one, under a lock that has no rollback.

B has neither: it adds no row and runs no writer.

**Android: the retry interval is not fixed, by design (accepted under option B).**
- **[C]** `Result.retry()` carries no delay. WorkManager computes the wait from the request's backoff
  and the attempt count: linear is `backoffDelayDuration * runAttemptCount`, capped at
  `MAX_BACKOFF_MILLIS` (`WorkSpec.calculateNextRunTime`, work-runtime 2.11.2, the version
  `gradle/libs.versions.toml` pins).
- So consecutive deferrals on a device wait **15, 30, 45, 60 … min**, up to the 5 h cap, each still
  under the idle + charging constraints, and back to 15 once a pass completes.
- **In-process the loop retries at a fixed 15 min** whenever Control is alive.
- A fixed interval on Android would need the worker to re-enqueue itself. P4 deliberately does
  not. This is the accepted behaviour, not an open finding.

**What P4 still owes before it can merge — its review bar** (at `7d8c1fc58`).
1. **Re-review.** The generation guard and Codex's three findings are built in `7d8c1fc58` (below).
   Codex re-reviewed that head and left two P2s — a deferral bypassing the generation compare, and
   a run escaping `stopAndAwait()` — both fixed in the PR's round 2 (*Timing after a stand-down*
   and *Stopping*, below). Codex's review of round 2 found the stop count could be adopted by a
   tick; round 3 replaced it with the supervisor invariant. Round 3 has not been re-reviewed.
2. **The Android smoke gate** (HR 8). It edits `androidMain` (`TenshuOperationWorker.kt`,
   `WorkManagerScheduler.kt`, `SharedPreferencesDeferralCountStore.kt`), and the gate has not been
   run on it.
3. **Device behaviour is unverified.** WorkManager's actual waits, the `SharedPreferences`-backed
   deferral count and the worker's branches have only been exercised as extracted JVM logic.

**How the three Codex findings on P4 were settled [C at `7d8c1fc58`].**
- **A stale snapshot could fire a one-shot twice: fixed.** `tick()` now reads the rows under the
  loop's lock (`SDL:175-186` on the branch).
- **Deferrals spent the failure budget: fixed, in extracted logic.** `ScheduledRetryAccounting`
  counts deferrals apart from failures, in a device-local `SharedPreferences` file keyed by work id.
  A failure is retried while `runAttemptCount − deferrals < 3`. The stored count is clamped to the
  attempt count and dropped at attempt 0. A proxy that is not ready yet now counts as a deferral too.
- ⚠ **Codex's *"three deferrals could terminate the periodic media job"* was wrong for periodic
  work. [C]** A final `Result.failure()` on periodic work resets the period exactly as a success
  does (`WorkerWrapper.handleResult` → `resetPeriodic()`, work-runtime 2.11.2). The media job was
  only ever at risk of waiting one 12 h period. A **one-shot** does fail for good.
- **The growing interval: kept, and the claim corrected** (above).

**Acceptance tests for P4.**

| Test (JVM, virtual time unless marked) | State at `7d8c1fc58` |
|---|---|
| A long item does not block another | built |
| An item never runs twice at once | built |
| A deferred run is retried after ~15 min, not 12 h, in-process | built |
| A completed pass, no consent, no backend and paused throw nothing, so the 12 h period stands | built |
| A run that throws anything else still waits the full period | built |
| A one-shot that completed while a tick held its snapshot does not fire again | built (`ScheduleDrainLoopSnapshotRaceTest`) |
| A real error after three deferrals is still retried | built for the extracted logic (`ScheduledRetryAccountingTest`). 📱 The worker itself is device-unverified |
| ~~Three consecutive deferrals are retried 15 min apart on Android~~ | **Not a requirement.** False by design: 15, 30, 45 … min (above) |
| Three consecutive deferrals are retried 15 min apart in-process | **[I]** not built as its own test; one deferral is pinned |
| The guard tests below | built (`ScheduleRunGuardTest`, 15 tests; `ScheduleDrainLoopSnapshotRaceTest`, 12) |
| 📱 One smoke run, and one `dumpsys jobscheduler` look at a deferred media job's waits | owed |

#### The generation guard — as built in P4 at `7d8c1fc58`

The guard protects a **registration or a cancel that lands while that key's run is in flight**.
The retry itself never does that under option B. Any other caller can: a schedule that is
re-registered at startup, or cancelled, while its item runs.

**What the real id provider does [C].** `ScheduleItemDataIdProviderImpl` derives a row's `DataId`
from `DEDUP_KEY` **alone** (`sync-service-common/.../ScheduleItemDataIdProviderImpl.kt:23-25`). Every
row for a key has the same id, whatever its state. So, on master:
- The loop's completion `replace` (`SDL:132-155`) removes by that id, so it erases a row that was
  re-registered during the run, and puts back a row that was cancelled during it.
- The op cannot tell generations apart: `params = null` (`SDL:103-106`), and the worker sends only
  `dispatch(opId)` (`TenshuOperationWorker.kt:46`).

**Two pieces of state, with different owners.**
- **(a) `inFlight: Set<key>` is the loop's own**, under the loop's coroutine `Mutex`. The tick never
  fires a key that is still running, and the key is cleared in a `finally`.
- **(b) A generation per key, shared with the two writers.** It lives in `ScheduleRunState`
  (`sync-service-proxy` `commonMain`) and is bumped by `ScheduleImplementation` on every
  registration and by `CancelScheduled` on every cancel, even one that matches no row.

⚠⚠ **The generation is an atomic, never a coroutine `Mutex`.** **[C]** Both writers are plain
non-suspend `execute`s of `SyncOperationImplementation` and must stay so (HR 3). `ScheduleRunState`
holds an `AtomicReference<Map<String, Long>>` updated by compare-and-set. Mutual exclusion with the
row comes from `Database.transaction`, the blocking re-entrant store lock both writers already open
(`core-common/.../proxy/Database.kt:243-257`).

⚠⚠ **`Database.transaction` is a lock, not a transaction.** **[C]** It has no rollback, and it ends
by calling `objectStore.flushBarrier()` (`db/DatabaseImpl.kt:80-87`). Both facts shaped what was
built.

**The rules as built. v0.7–v0.10 stated four; two of them were wrong.**

- **(i) The row is written in place, and the key always keeps a row.** `writeScheduleItem` saves
  over the existing row when the key has one, and appends only when it has none
  (`ScheduleStore.kt:40-53` on the branch). Master's remove-then-add is gone from both the writer
  and the completion.
  - ⚠⚠ **v0.7 claimed more than is true: *"one `saveData` is atomic; if it throws the old row is
    whole."* That is FALSE.** A `ScheduleItem` is several store writes: its three nested specs
    first, each an entry of its own, then the row's entry. A failure before the first write leaves
    the previous row whole. A failure part-way can leave **newer specs under the previous entry**
    (its state, attempts and op id). Read in the branch's KDoc (`ScheduleStore.kt:35-38`) and its
    test; not traced through `DbEntryMapper` here.
  - **What does hold:** the key keeps a row that runs, whether or not the write succeeds.
  - **[C]** The in-place save also tells array listeners the member changed
    (`notifyArrayMemberUpdated`). The check v0.7 marked as owed is done: nothing in the repo names
    the `scheduledItems` array except the loop, the two writers and `ListScheduled`.
- **(ii) A writer bumps inside its transaction, before it touches the row.** Built as stated.
- **(iii) The tick does NOT read generation and row inside one transaction.** v0.6–v0.10 required
  that. **It was deliberately not built.** `Database.transaction` enqueues a commit barrier on the
  SQLite store, and a 1 Hz tick must not do that. As built (`SDL:184-194` on the branch): read
  every generation, read the rows, read the generations again. **A key whose generation moved
  across the read is skipped until the next tick.** That pairs each row with the generation it was
  written under, with no barrier.
- **(iv) The completion compares and writes inside one transaction**, and stands down on a
  mismatch. Built as stated (`SDL:354-380`).
- **(v) A completion never adds a row** (new; not in this doc before). If the key has no row when
  the run completes, the completion writes nothing, whatever the generation says. That alone stops
  a cancelled row being resurrected. So the cancel-side bump is belt and braces, and (v) also
  covers a removal that bumps nothing.

**Why bump first.** The store lock already makes bump and write one step against the completion.
The order matters when the write fails.
- Bump, then the write throws. The key still has a row (rule i). The in-flight completion stands
  down, and the item runs once more than needed. The caller got the exception. ⚠ If the write
  failed part-way, that row may carry newer specs than its entry.
- Write, then no bump. The old completion would pass its compare and overwrite the new row through
  their shared id: a lost registration.

**Timing after a stand-down** (amended in P4's review round 2; v0.11 described the first build)
**[C]** (`SDL:255-303`).
- **Every outcome asks whether the run's row is still the key's row**: a completion through
  `complete()`, and a **deferral** through a read-only `isCurrent()` (generation unmoved, row
  present). The first build skipped the question for a deferral, because a deferral writes nothing.
- **Stood down ⇒ the key's timing is forgotten, whole.** Not the period, not the retry delay, not
  "has fired once", and not the old item's **kind** carries over. A row that is there afterwards is
  a registration that has never run, and starts from its own `initialDelayMs`.
  - A one-shot re-registered during its run fires after its own `initialDelayMs` (as before).
  - A key that came back as a **one-shot during its periodic run** waits its own `initialDelayMs`.
    The first build decided from the old item's kind and would have fired it on the next tick.
  - A deferred run's `retryAfterMs` is **not** inherited by a registration made during it, nor by a
    later registration reusing a key cancelled during it.
  - ⚠ **Changed from the first build:** a **periodic** key re-registered during its run no longer
    counts its period from the end of that run. With no `initialDelayMs` it fires on the next tick:
    one extra run. Accepted as the cheap error — the alternative is a registration that waits out a
    period measured from a run that was not its own, which for a deferred startup pass is the 12 h
    wait F3.1 exists to remove.
- ⚠ A registration that lands just *after* the check is one that arrived after the run: the same as
  re-registering an idle key, which keeps the key's timing, as on master.

**Stopping** (review rounds 2–3; the round-2 stop *count* is gone) **[C]** (`ScheduleDrainLoop.kt`,
the KDoc on `runs`, `stop()` and `stopAndAwait()`).

⚠⚠ **The invariant: a run is a child of the supervisor `stop()` cancels, and a coroutine whose job
is cancelled before its body starts never runs its body.** So once `stop()` has returned, no run
begins, whenever it is launched. `Job` is the one piece of state both sides go through, and it
decides attach-versus-cancel atomically: a run launched before the cancel is one of the
supervisor's children and is reached by it; a run launched after is born cancelled.

- **Why nothing a tick reads can do this.** `stop()` does not suspend, so it cannot take the loop's
  lock, and a tick already mid-pass can launch after `stop()` has cancelled every run it could see.
  Round 2 answered with a stop count that the tick read and each run re-checked. Round 3's finding:
  a tick that reads the count *after* the bump adopts the new value, and its run passes the check.
  Any flag or epoch read on stop's behalf has that hole. A tick now reads nothing; it only launches
  into a parent.
- **The interleavings, each forced by a test** (`ScheduleDrainLoopSnapshotRaceTest`): `stop()`
  (1) before the tick takes the lock, (2) between the lock and the row read, (3) between the row
  read and the launch, (4) between the launch and the run's first statement, (5) after the op was
  dispatched; and (1)–(3) again through the running loop's own tick. In (1)–(4) no op starts, the
  row is untouched and the key is freed. Round 2's loop fails (1).
- ⚠⚠ **(5) is cancelled, not prevented — and what is cancelled is the run, not the op.**
  `ExecutionContext.executeAsync` starts a suspending op on the **session's** scope and the run
  only awaits it. `stop()` makes the run stop waiting: nothing is recorded, the row keeps its state
  and `attempts`, the key is freed. **The op carries on** until it ends or its session is
  cancelled. An op that never suspends runs inline on the run's coroutine and cannot be
  interrupted; it runs to its end and its outcome may still be written. Neither is new: master's
  loop awaited the op the same way. Pinned by test (5) as it is.
- **The key is freed by the run's completion handler**, not by a `finally` in its body: a run that
  never begins has no body to run one. `inFlight` is therefore an atomic map (key → run), not state
  under the loop's `Mutex`. Without the handler the item would be skipped for the rest of the
  loop's life.
- **`stopAndAwait()` joins the loop first and the runs after**, then returns only when every
  launched run has finished — including one that never began, and one still on its thread. It does
  **not** wait for an op that a cancelled run abandoned (above).
- **Restart.** A cancelled supervisor cannot be revived, so `start()` swaps in a fresh one, and the
  loop it launches keeps the supervisor it was started with. ⚠ No production code calls `stop()`
  or restarts the loop today; the loop ends with its scope. The capture is not pinned by a test of
  its own.

**Also fixed in P4: a completion write that throws no longer escapes. [C]** The run is a child
of a bare `SupervisorJob` with no handler, so a throwing store write would have reached the
thread's uncaught handler, which on Android kills Control. It is now caught (`SDL:269-278`); the
row stays as it was and the item runs again.

**Other properties.**
- On JS the store lock is a pass-through (`NoOpDatabaseLock`, `db/DatabaseLock.kt:32-34`). That is
  sound there: a non-suspending block cannot be interleaved on one thread.
- **Restart.** Generations start at zero and no row is ever persisted as `RUNNING`. A process that
  dies mid-run leaves the row `PENDING`, and it fires once on the next start. A completion from a
  dead process cannot exist.
- **HR 9: none.** No stored field or constant changes.
- **Wiring.** `ScheduleRunState.Default` is the process's one instance, a default parameter of both
  writers and of the loop. It is not a Control DI binding, so the guard adds no wiring.
- **Limit: iOS route 1.** Several app processes share one App-Group store, so one process cannot
  see another's bumps. The media retry never runs there (no backend, `IndexingConditions.Never`).
  If cross-process scheduling ever matters, the fallback is a persisted generation token on
  `ScheduleItem`: an HR 9 reshape with a version bump and a `SchemaMigration`. Not needed now.
- **Limit: one JVM hosting several proxies.** `Default` is process-global and keyed by dedup key
  only. A registration on one proxy makes another proxy's in-flight run of the same key stand down.
  Production has one store per process, so this is a test-environment caveat: a test that hosts two
  proxies passes its own `ScheduleRunState`.
- **Limit: WorkManager.** The Android worker never reads or writes `ScheduleItem` rows, so the guard
  covers the in-process loop only.

**The guard tests, as built** (`ScheduleRunGuardTest`; 9 and 15–17 are in `ScheduleDrainLoopSnapshotRaceTest`):
1. A registration during a run survives that run's completion.
2. A one-shot re-registered during its run stays `PENDING` and fires once more.
3. A cancel during a run stays cancelled.
4. A row removed behind the loop is not resurrected (rule v).
5. A registration whose row write fails leaves the previous row intact. ⚠ **Only when the failure
   hits before the row's first store write.** A sixth test pins the part-way case: the key still
   has a row.
6. A failed registration during a run leaves the previous row `PENDING`, and it fires once more.
7. A completion whose row write fails leaves the row and does not fail the run.
8. After a restart, a `PENDING` row fires once.
9. A key registered across the tick's row read is left for the next tick (rule iii).
10. A key re-registered as a one-shot during its periodic run waits its own initial delay.
11. A periodic key re-registered during its run starts its timing afresh.
12. A deferred run's retry delay is not inherited by a registration made during it.
13. A key cancelled during a deferred run starts clean when it is registered again.
14. A deferred run nothing replaced is retried after its own delay.
15. `stop()` at each of five points relative to a tick (*Stopping*, above), and through the
    running loop's own tick.
16. A run launched across `stopAndAwait()` does not outlive it; `stopAndAwait()` waits for a run
    launched after it was called, and for one still on its thread.
17. A restarted loop dispatches again.

**Order.** ⚠⚠ **P0f-a first** (🚀 dispatched 2026-10-02): it fixes a privacy defect on master today (F0), is ☁-safe and depends on nothing. **P0f-b** (the Control wiring that makes People drop a deleted photo on the next read, smoke-gated) follows as soon as a device or emulator is free. **P0g**'s Mia half (☁) can ship alongside P0f-a; its `photosLike` half rides P0f-b. Then P0, P1a, P3 and P4, which are independent and can run in parallel — ⭐ **all four were dispatched to cloud sessions on 2026-10-02** (branches in their rows). ⚠ Only P0 and P1a are ☁ end to end: P3 and P4 each have a smoke-gated half (their rows, §6.2). P1b and P5 are the
first 📱 pass. P6a → P6b → P7 is the on-upload indexing chain. P9 is the largest behavioural change
and goes last, after P1 has made the CPU stage cheap enough that pipelining is about the network.

**HR 8 rule for the marks above.** A change to `androidMain`, `:control`, or Control's DI (`control-shared-proxy` `ProxyModule` / `PlatformProxyModule`, which compose the services) needs `scripts/android-smoke.sh` before merge. That holds even when the code is `commonMain`, and `onnxMain` counts too, because it compiles into Android. Checked row by row on 2026-10-02: P0, P1a, P0f-a, P0g (Mia half), P3-a, P4's loop and guard, P6a, P10's indexer half, P11's walk and floor, P12a and P13 (desktop only) touch none of these. P0f-b, P0g's `photosLike` half, P1b, P2, P3-b, P4's Android half (it edits `androidMain`, §6.2), P6b, P7, P8, P9, P10's hook half and P11's source line do.

**What a cloud agent cannot claim.** P0f-b, P0g's `photosLike` half, P1b, P2, P3-b, P4's Android half, P5, P6b, P7,
P8, P9, P10's hook half and P11's source line change behaviour that only an emulator or device exercises. A ☁ PR for any of them must say *"📱 owed"* in its description and not
cite the gate as verification (AGENTS.md *Build & test*).

### 6.3 Open at merge — three review findings, each a hard constraint on its phase

Raised in this doc's review at `da559a1ca` and **not designed here**. The doc merged with them open so the
phases could start; a phase PR that touches one of these seams closes it, or says why not.

1. ⚠⚠ **A folder delete that fails part-way strands typed names** — owner **P0f-b** (S2). S2 runs
   after `deleteRecursively`'s walk. If the walk throws once some children are gone, the hook never
   runs, and a retry cannot collect those children: their `File` rows no longer exist. S1 removes
   their stores, and B2 never touches a typed name, so the names stay synced for good. The leaf set
   has to be collected (or persisted) before the first mutation, or S2 has to run for the leaves
   already removed when the walk exits exceptionally.
2. ⚠⚠ **The changed-hash backstop cannot tell a stale label from a fresh one a peer derived** —
   owner **P10** (B2 / the content-hash key). The label marker is synced and carries neither a
   content hash nor a device. If device B replaces and re-labels a path before device A's next pass,
   A still holds the old local hash and deletes B's fresh model rows; where A has no tagger, consent
   or usable bytes they stay gone there. It needs a device-local, per-capability label visit keyed
   by content hash, or another signal that separates stale rows from ones already derived for the
   replacement.
3. ⚠ **S4 cannot build one label index per move** — owner **P0f-b** (S4). The hook receives one
   `(oldPath, newPath)` pair per recursive `moveFile` call and no operation boundary, so §6.1's
   "one label index per move operation" is not implementable without a process-global cache; the
   straightforward consumer rescans the label relation per descendant — O(descendants × labels) for
   a folder move. Pass the whole path mapping once, or give the hook an operation-scoped lifecycle.

**Closed in design (v0.13):** finding 1 by §6.4.3, finding 3 by §6.4.4. Finding 2 stays P10's;
nothing in §6.4 depends on how it is closed (§6.4.5).

### 6.4 P0f-b in detail — the design (v0.13, 2026-10-03, read against `18e510e84`)

P0f-a merged the mechanisms. This section is what P0f-b builds on top of them and how it
splits into PRs. Every **[C]** was read at `18e510e84`. Where it disagrees with §6.1's seam rows
or with the P0f-b row, this section is newer, and those rows now point here.

#### 6.4.0 What the code says that changes the plan

1. ⚠⚠ **The reconcile (B1) is inert in production, not just the read filter. [C]** Control's
   `MediaSource` overrides only `media()` (`ProxyModule.kt:396-428`, the walk at `:402` is
   `collectImagePaths()`). `MediaSource.walk` therefore takes its default, `MediaWalk.partial(…)`
   (`MediaIndexPassImplementation.kt:41-42`), so no pass reconciles anything. The KDoc there says
   so (`:37-39`), as does A40 (`threat-model.md:1764`). **Wiring B1 is part of P0f-b's first PR**,
   and S1 depends on it: §6.1 row 14 says a lost S1 queue is caught by B1, and today nothing would
   catch it.
2. **The read filter is one line for all three platforms. [C]** `FaceGroupingComponents` is one
   `single` in the common `ProxyModule` (`:264`). Every `PlatformProxyModule` passes it as
   `faceGrouping = get()` (`PPM-A:1482`, `PPM-D:1433`, `PPM-I:1257`). `photosLike` takes the same
   predicate by default (`InferenceServiceProxyImplementationFactory.kt:78`).
3. **Photos' label read is narrower than §6.1 says. [C]** Things and People join every label row
   against the feed (`groupPhotosByLabel`, `PhotoLabel.kt:89`; People at
   `CollectionsViewModel.kt:747-760`). The feed excludes trash inside the chain
   (`TenshuPhotosRepository.kt:296`), so a label at a trash path or a dead path never reaches
   either section on master. **The one reader that leaked was `searchByLabel`**: it capped rows
   *before* the join, and its join `byPath` finds a trashed file at its trash path. ✅ **Closed on
   master by PIO-4 slice 4 (merged). [C]** `searchByLabelRead` now filters through
   `citableLabelMatches`, which drops `isTrashedPath` rows before the cap
   (`TenshuPhotosRepository.kt:1489-1500,1860-1868`). **So D14's requirement on "Photos' own label
   read" is met on master, and PR-4 no longer waits on it** (§6.4.8).
4. ⚠⚠ **A40 contradicts D13 and D14 as written, so two PRs owe HR 2 amendments. [C]** A40 says
   "*its template and its vector go with it; person labels stay*" (`threat-model.md:1718-1719`).
   It also says "*`nameFaceGroup` is the **only** operation in this feature that writes realm
   data*" (`:1683-1684`). S2 deletes person labels on every device, and S4 writes them at a new
   path. Both sentences change, in the PR that makes them false.
5. **Label deletes expose their type and timing to the relay exactly as every other row delete
   does today. [C]** Every `DeleteBlock` carries `targetEntityType` in the clear, for every type:
   files, workouts, places, devices (`DeleteBlock.kt:42`; `threat-model.md:2801`). The relay reads
   it only to recognise `Device` tombstones (`SyncRouting.kt:368`, `SyncMailbox.kt:353-364`,
   `SqliteSyncMailbox.deviceTombstoneCount` at `:313`). So S2 and S4 add `MediaLabel` to a channel
   that already exists. A relay can count *k* label tombstones at time *t* beside a file
   tombstone, never a name, a path or which rows were typed. ⚠ **This is not a P0f-b property, and
   P0f-b does not fix it.** Closing it for all types is the separate relay-metadata track
   (maintainer, 2026-10-03, P0fb-D4; its own doc).
6. ⚠⚠ **On iOS the inference logs are one file shared by every route-1 process. [C]**
   `IosFileDatabase` roots in the shared App-Group container (`IosFileDatabase.kt:53-58,76-78`),
   and every route-1 app runs the full `proxyModule` + `platformProxyModule` in-process
   (`DriveLocalRuntime.kt:41-42`, `PhotosLocalRuntime.kt:25-28`). So a `get<FaceTemplateStore>()`
   in Drive's process opens the same `/inference/faces.log` that PIO-7 will make Photos' alone
   (PIO-D10). **[C]** The realm-wipe `clear()` in `PPM-I:356-363` already does this, harmlessly,
   because no iOS store holds anything yet: no backend is bound (`PhotosLocalRuntime.kt:35-37`).
   S1 would be the first iOS *writer* outside the owner, so it is not wired there (§6.4.2).
   ⚠⚠ **B1 is a store writer too (PR-1).** PR-1's wiring is in the common `ProxyModule`,
   so it compiles into every iOS route-1 process. It is unreachable today:
   - no inference backend is bound, so `faceGroups` and `photosLike` answer `NO_BACKEND`;
   - no `IndexingConditions` are contributed, so a pass defers and never reaches the reconcile.

   **Once PIO-7 binds a backend on iOS, PIO-D10 must keep backends *and* indexing conditions out
   of every route-1 process except Photos.** Otherwise Drive's process, for example, would run a
   pass whose reconcile strikes the shared App-Group logs.
7. **A replace with identical bytes returns before it changes anything. [C]**
   `uploadFile(…, replaceExisting = true)` returns at `UploadFileImplementation.kt:238-246` when
   the content it already holds matches. S3 sits after that return (§6.4.5).
8. **There is no label-by-path lookup. [C]** A typed name's `DataId` is derived from
   `"$path/$capability/$name"` (`MediaLabel.kt` `labelKey`), so no point read finds it without
   the name. The only enumeration is the `LABELS` relation (`InferenceService.kt:38-42`), a
   realm-wide array, the same shape `rekeyHistory` scans for history
   (`MoveFileImplementation.kt:560-580`). Only the marker is point-readable (`MediaIndexer.kt:736-741`).
9. ⚠ **Pre-existing, not P0f-b's: a delete whose walk throws emits no file tombstone. [C]**
   `DeleteFileImplementation` detaches the root first (`:91`), walks (`:103`), and only then emits
   the one tombstone with its cascade list (`:132`). If the walk throws, peers keep the whole tree.
   The walk deletes the root row last (`:203`), so a retry finds it and emits. But the retry's
   `removedEntities` and `orphanedContent` name only what was left, so the content edges of the
   leaves the first attempt removed are never undeclared: a relay leak, never a loss. Recorded
   here because S2 shares the walk. S2's fix (§6.4.3) does not depend on it.

#### 6.4.1 The read side and B1 — wiring only (PR-1)

- **The predicate.** One `LivePaths` built in `ProxyModule` from `livePathsAmong`
  (`LivePhotoPaths.kt:26-30`), passed as `FaceGroupingComponents(…, livePaths = …)` at
  `ProxyModule.kt:264`. `faceGroups` and `photosLike` both filter with it, on all three platforms.
  **[C]** `livePathsAmong` is in-process point reads, one per path. `LivePaths.liveAmong` hands it
  at most 200 paths at a time (`LivePaths.kt:30-41`), the `photos-app.md` §39 chunk size, so no
  reply grows with the library. Nothing crosses IPC.
- **B1.** Control's `MediaSource` gains a `walk` override built on `walkImageLibrary()`
  (`ImageLibraryWalk.kt:81-86`). A walk that reports `complete` becomes
  `MediaWalk.complete(media, livePaths)`, with the same predicate as its second proof. Anything else
  stays `MediaWalk.partial`. ⚠ The source moves into a named top-level function, so a test can build
  the production source rather than a copy of it.
- **Photos' search needs nothing.** PIO-4 slice 4 (merged) drops `isTrashedPath` rows before the cap.
  Things and People join against the feed (§6.4.0 item 3).
- ✅ **Realm context: settled in PR-1. [C]**
  - `ClientRegistry.mintSession` (`ClientRegistry.kt:334`) builds every session over the proxy's one
    `database` and `baseContext`. So Photos' `faceGroups`, Mia's `photosLike` and Control's pass all
    read the same personal `File` rows.
  - A shared realm's files are `SharedFile` rows under `"$realmId::"` keys, which the walk (rooted
    at `/`) never reaches.
  - Pinned by `MediaLibrarySeamsTest`.
- **The failed-read rule (PR-1). [C]**
  - `livePathsAmong` throws `LivenessUnanswered` when the realm root `/` does not read. `/` is
    created by `initFileService` and never deleted, so a store that cannot read it can vouch for
    no path's absence.
  - It is a plain `IllegalStateException`, deliberately untyped: a typed `TConstant` would be a
    `SchemaShapeBaseline` change.
  - People and search then **fail visibly** (Photos maps the failure to `Failed`). They never
    answer "nobody here", and never serve unfiltered rows.
  - The reconcile's `dropTheDead` catches it (`MediaIndexer.kt:328-351`) and drops nothing that
    pass.
  - ⚠ **Limit:** it cannot catch a store that reads the root and answers "absent" for some rows.
    The read filter would then hide live photos for that read, and the reconcile's walk-side proof
    is the only guard against erasing them.
- **As built in PR-1:**
  - `fileLivePaths` (the `LivePaths` over `livePathsAmong`) and `controlMediaSource` (the
    production source, reporting `MediaWalk.complete` only on a complete `walkImageLibrary()`), in
    `control-shared-proxy/…/di/MediaLibrarySeams.kt`;
  - tests: `MediaLibrarySeamsTest` (behaviour) and `MediaLibraryWiringTest` (the source-text audit);
  - **no `PlatformProxyModule` change**: all three already pass `faceGrouping = get()` and
    `mediaSource = get()`.
  - No `:system-test-utils` scenario could be added in PR-1, because that module has no
    `:inference-service-proxy` dependency. PR-3 adds it (§6.4.6).
- **Effect.** People and search stop using a deleted or trashed photo's data **on the next read**,
  with no pass. The next complete pass then erases it from disk, so §6.1 rows 1–3 and 9–11, column
  (a)+(b), hold even before S1.

#### 6.4.2 S1 — the dead-path observer (PR-2)

- **Producer (`:file-service-proxy`).** A `DataObserver`, `DeadFileRowObserver`, with the
  `IngestedDeleteRecorder` shape (`IngestedDeleteRecorder.kt:33-50`):
  - it accepts a `StringDataId` of type `File.ID` and reads the path from its key;
  - it forwards *every* origin (USER, SYNC_INGEST, DERIVED), because a move, a peer delete and a
    reaper cascade all need it (§6.1);
  - it calls a non-suspending `fun interface DeadFilePaths { fun dead(path: String) }` inside
    `runCatching`;
  - it is registered in `initFileService(…, deadFilePaths = null)` next to the reaper
    (`FileServiceProxyInitialization.kt:115-120`). `null` registers nothing.
- **Why only enqueue. [C]** `onDataDeleted` runs inside `database.transaction` in two places: in
  `ExecutionContext.deleteData` (`ExecutionContext.kt:663-668`), and around all of
  `processDeleteBlock` on a peer (`SyncProcessor.kt:213`, delete at `:265`). The stores' removal
  is `suspend` and takes their own `Mutex` (`DefaultFaceTemplateStore.kt:156-163`). It can throw
  `LogOutOfSync`, and it writes and reads back the log. Running it there would hold the realm lock
  across file IO, and a throw would take down the sync tick, which is why the reaper and the
  recorder degrade rather than throw. So `dead(path)` does one non-blocking `trySend` into an
  unbounded channel and returns.
- **Consumer (`:inference-service-proxy`).** `DerivedStoreEraser(faces, embeddings, scope)`. One
  coroutine on a process-lifetime scope (the `MediaIndexRunner` precedent, `ProxyModule.kt:361-366`)
  drains the channel:
  - It **coalesces.** It takes everything queued, as a set, and calls `forget(batch)` once per
    store. A folder delete, a peer's folder tombstone, a realm-data reset or a pairing discard
    (`PairingKeyInstaller.kt:60` retires rows through `DataIdMigrator`) fires the observer once
    per row, and N single-path `remove` calls would be N strikes, N scrubs and N log re-reads.
  - It **reads the whole `Removal`.** `forget` reports rather than throws for refusals and owed
    erasure (`LogStrikes.kt:240-262`). An incomplete removal is logged with `Removal.toString()` and
    not retried in a loop: refused rows stay readable only through the read filter (which hides
    them) until B1, and owed erasure is retried by every later removal and by the next open
    (A40). A throw (`LogOutOfSync`) is logged the same way. `CancellationException` is rethrown.
  - **It removes unconditionally.** A path re-created between the delete and the drain (a
    delete-then-re-upload) loses whatever was indexed for the new file. That is correct: it makes
    the new file un-visited, so the next pass indexes it.
- **Process death: the queue is not persisted, on purpose.** The stores are re-derivable, the
  read filter hides a dead path from the moment its row dies, and B1 (wired in PR-1) erases it at
  the next complete pass. §6.1 row 14 is that, and it holds only because PR-1 lands first.
- **[I] A pass that is already reading a photo can write its template back after S1 forgot it.**
  The pass read the bytes before the delete and `put`s after the drain. The read filter hides the
  row and the next B1 removes it. Closing that window would take a dead-path fence inside the
  store, which is not worth it for re-derivable rows. Stated, not built.
- **Wired on Android and desktop only.** It is passed at `PPM-A:893` and `PPM-D:905`, and
  **not** at `PPM-I:743`, because of §6.4.0 item 6. The stores there are empty today (no backend).
  When PIO-7 makes Photos the owner, Photos' runtime is the only place that may wire S1, and B1
  covers what other route-1 processes delete. The wiring test asserts the iOS absence, so it is
  not "fixed" by symmetry.
- **No label is touched.** The observer cannot tell a delete from a move (§6.1), so S1 only ever
  erases device-local rows. That is row 11's requirement.

#### 6.4.3 The retirement-intent rule, and S2 — the purge hook (§6.3 finding 1 closed) (PR-3)

##### The rule — one rule for S2, S3 and S4 (v0.13, Codex rounds 1–4)

Three review rounds found holes in one mechanism: a durable intent replayed later, against a
store or a realm that may have moved on since. So the rule is stated once, here, and S2 (below),
S4 (§6.4.4) and S3 (§6.4.5) each apply it whole.

> **A label retirement is a durable, realm-bound intent that owes only tombstones.**
>
> - **(a) Bound to one realm, and it emits only through that realm, atomically.** The intent is
>   written under the realm it was captured in. It can emit only through an emitter that proves
>   that same realm *at emit time*, in the same read that names the rows.
>   A realm switch therefore makes the emit refuse; it never re-addresses the intent.
> - **(b) The local rows go at the event, and the intent owes only tombstones, by `DataId`.**
>   - Each covered label row is deleted locally under the store lock, *immediately before* the
>     covered file-row write: before each leaf's delete, before a moved row's retirement, before a
>     replace's row write.
>   - So when a tombstone is due, **a row that exists at an owed `DataId` was written after the
>     event**, by this device or by a peer, and is skipped. Its own newer save reaches the peers by
>     last-writer-wins.
>   - A row that is absent gets its tombstone.
>   - No version is captured, compared or guessed, so there is no "unknown" state for a later read
>     to misinterpret.
> - **(c) It is written before the first mutation it covers.** It is cleared as `COMPLETE` only
>   when every owed `DataId` is either tombstoned, or provably skipped because it exists after
>   (b)'s local delete. Leaves the op has not reached hold the intent open.
> - **(d) It is replayed by the same maintenance hook**, whatever seam wrote it.

**(a) What the code offers, and what PR-3 must add. [C]**
- `SyncObserver.emitAll` already names each target and the realm it is stamped for in **one**
  read: `wireIds.resolve(...)` returns `Resolved(realm, wireName)` (`WireIdResolver.kt:90-100`;
  `SyncObserver.kt:1613-1629`). So a block can never pair realm B with a name derived under A.
- But the interface takes **no expected owner** (`TombstoneEmitter.kt:102`), so a caller can only
  compare owners *before* the call. `DataIdMigrator` does exactly that (`DataIdMigrator.kt:328-338`)
  and says why it is not enough on its own (`:76-86`): an adoption that lands between the
  comparison and the emit produces a self-consistent snapshot of the wrong realm. Every guard
  inside then passes, and the tombstone deletes an unrelated row with the same key on every one of
  B's peers.
- ⚠⚠ **PR-3 therefore owes an API addition in `:sync-service-common` and `:sync-service-proxy`:**
  `emitAll(targets, expectedOwnerId: String?)`.
  - `SyncObserver` checks every `Resolved.realm.ownerId`, and the batch owner it stamps
    (`:1637`), against `expectedOwnerId`, on the same snapshot it names from.
  - Any mismatch answers `null` and queues nothing: the all-or-nothing rule that already exists.
  - `expectedOwnerId = null` means "only if this device is in no realm"; it answers
    `emptyList()` there and `null` anywhere else.
  - The default body keeps today's behaviour for test doubles.
  - It is a Kotlin interface method, not an op or a wire field, so HR 9 is untouched.
- **Plus the `DataIdMigrator` structure on disk.** Intents live under a directory named for their
  owner (`…/label-retirements/<ownerId>/…`, or `…/no-realm/…`), exactly as
  `PENDING_REKEY_TOMBSTONES + "/" + owner` does (`DataIdMigrator.kt:90-92`).
  - A replay reads only the directory of the current `residentOwnerId`, so it cannot even *see*
    another realm's intents.
  - The check inside `emitAll` covers the adoption that lands mid-replay. The two together replace
    v0.13 round 1's "discard on mismatch".
  - The local-store wipe deletes every intent directory (`onLocalStoreWiped`, `PPM-A:355`,
    `PPM-D:410`, `PPM-I:350`).
- The owner recorded is `SyncManager.residentOwnerId`, the persisted slot (`SyncManager.kt:29-47`;
  `SyncManagerImpl.kt:106`). It is never the emitter's view, which is `null` during the cold-start
  window (`PPM-A:1177`) and would misfile the intent as "no realm". An owner source that is
  missing or throws writes no intent (below).

**(b) Why there is no version, and what the code offers instead. [C]** (v0.13 round 4: Codex P1,
*capture versions without requiring the live emitter*)
- **Round 3 captured each row's version, and that cannot work.** The version was the HLC
  `processDeleteBlock` compares (`SyncProcessor.kt:247-263`), and it lives in two store records,
  `SyncMetadata` and `LocalWriteMetadata`. **Both are keyed by the wire id:**
  - `SyncProcessor` reads `SyncMetadata` under `wireIds.of(dataId)` (`:242-247`);
  - `TombstoneRegistry` reads and writes `LocalWriteMetadata` under `wireIdFor(dataId)`
    (`TombstoneRegistry.kt:161,243-251`).
- The wire id is `HMAC(K_entity, identity)`, so neither record can be read without the realm key,
  *directly from the store or through the emitter alike*.
  - In the cold-start window, a locked realm or a sealed route-1 process, capture could only have
    recorded "unknown". The replay would then have read the row's real HLC as a post-capture
    rewrite, skipped it and cleared the intent: the label stays on every peer.
  - **[C]** A local save made in that window is not stamped at all: `onDataSaved` returns when
    `wireIdFor` answers `null` (`:160-161`). So a version cannot even be relied on for writes
    *after* the capture.
  - A key-independent stand-in fails too. A capture-time HLC tick can sit below rows a skewed peer
    stamped earlier, and the clock is seeded from its watermark only once sync starts
    (`SyncScheduler.kt:2440`). Rows written *before* the capture would then read as newer, and be
    skipped.
- **So the rule needs no version: it deletes locally at the event, under the lock, and owes only
  tombstones.** This is `DataIdMigrator`'s phase 2, "the local retirement is done and only the
  tombstone is owed — emit, never delete" (`DataIdMigrator.kt:62-64`).
  - **[C]** It is safe to delete first because the wire name derives from the `DataId` and the realm
    key, never from the row, so a tombstone can still be named after the row is gone (`:326-327`).
  - The intent durably holds those `DataId`s.
  - Round 2's emit-before-delete ordering existed because nothing else held the ids, and it no
    longer applies.
- **Why "exists at emit time" proves "written after the event."**
  - The local delete and the covered file-row write share one `database.transaction`. **[C]**
    `transaction` and `saveData` take the same reentrant lock (`DatabaseImpl.kt:80,116`); ingest
    applies under it too (`SyncProcessor.kt:213`), and the lock is reentrant
    (`ExecutionContext.kt:659-661`).
  - Labels go first, then the file row. ⚠ `Database.transaction` has no rollback, so a kill
    between the two writes is possible. In that order it leaves the labels gone and the file row
    in place. The leaf rule then reads the leaf as not reached, so nothing is emitted, and the
    intent stays open.
  - If the user's retry deletes the file, the same intent's leaf becomes "reached", and its owed
    tombstones go out.
  - The reverse order would leave old rows present after the event, which (b) would then misread
    as later writes.
  - **[I] Cost:** that kill window, or a covered write that throws, loses a still-existing file's
    labels **locally** (peers keep them, untombstoned). It is two adjacent writes under one lock.
- **The emit step**, per chunk of 500 targets, in one `database.transaction`:
  - drop the `DataId`s whose leaf is not reached (the leaf rule);
  - drop those that exist now, which is the provable skip;
  - call `emitAll(rest, expectedOwnerId)`;
  - on `null`, answer `RETRY` and keep the intent.
- **This closes Codex round 3's P2 without a version.** A replacement photo given the identical
  label at the identical path re-creates the owed `DataId` after the local delete. It exists at
  emit time, so it is skipped, and its own save overwrites the old row on peers.
- **It closes round 4 by construction.** Capture records only keys and leaf identities, which
  need no realm key, so a cold-start, locked or sealed capture is complete.
- ⚠ **PR-3 owes one emitter API addition, not two.** `emitAll(…, expectedOwnerId)` stays.
  Round 3's `lastWriteOf` is dropped.

**The leaf rule** (S2 and S4; S3 is the same with one leaf):
- The intent also records each covered `File` leaf's `(contentId, createdAt)`, from a read-only
  pre-walk before the first mutation.
- A leaf's labels are retired only once its path **no longer holds that identity**: no row, or a
  different file there.
- So an unreached leaf of a crashed delete keeps its labels, and an unmoved leaf of a failed move
  keeps its labels.
- **[I] The one misfire.** A leaf the op never reached, then *replaced in place* after a crash and
  before replay, reads as reached, and its typed names are retired. It needs a crash in the middle
  of the op plus an overwrite inside the subtree being deleted or moved. Accepted.
- ⚠ `File.ReferenceRoot` would be the cleaner identity, because it survives a replace and a move,
  but it is due to be retired in CR-2 B6.

**(c)–(d) Lifecycle.**
- Write before the first mutation.
- Retire in the op's `finally`, through the shared `MediaLabelRetirement.retire(…)`, which answers
  `COMPLETE` or `RETRY`.
- Clear the intent only on `COMPLETE`.
- Replay every intent in the current owner's directory from `addMaintenanceHook`, plus at the
  start of the next `DeleteFile`, `MoveFile` or replace. **[C]** `addSyncCycleHook` never runs while
  `isEnabled()` is false (`SyncScheduler.kt:676-695`); the orphan sweep is the precedent
  (`PPM-A:1156-1169`).
- What each state answers:
  - in no realm: `emitAll` answers `emptyList()`, and the rows are deleted locally;
  - a locked realm, cold start or a null holder: `RETRY`;
  - a realm switched under the op: `null` from the owner check, so `RETRY`, and the intent stays in
    the old realm's directory.
- Idempotent: a replay emits only owed `DataId`s whose leaf is reached and whose row is still absent.
- ⚠ A replay skips intents whose op is still running in this process (a process-local set of open
  intents). Otherwise it could read a leaf the walk has not yet reached as "never reached" (S3's
  clear-on-not-reached rule) mid-op.
- **Why each seam needs it.**
  - **S2** has no other way to remove a typed name from a peer (B2 never deletes one).
  - **S4** leaves old-path rows on every peer for good if its batch answers `RETRY` and nothing
    keeps it. B2 deletes only locally, and only re-derivable rows (Codex round 3, P1).
  - **S3** has the same exposure for model rows. B2 removes them locally on devices that run a
    pass, but never on a device that does not.
  - **So none of the three is exempt.**
- **No owner source** (a harness, or a runtime without a `SyncManager`): no intent is written. The
  op then has the in-memory `finally` only: the exception case is closed, a kill is not. An intent
  whose realm is unknown would be unsafe to replay and wrong to discard.

##### S2 — the purge hook

**Contract.** `DeleteFileImplementation` gains `permanentDeleteHook: PermanentDeleteHook?`, a
`fun interface` in `:file-service-proxy` with two calls:
- `suspend fun capture(executionContext, leaves): Intent?` — the rule's capture, which the op
  persists;
- `suspend fun retire(executionContext, intent): RetireOutcome` — `COMPLETE` or `RETRY`.

It takes the op's own context, for the reason `reclamationRecorder` is a factory
(`DeleteFileImplementation.kt` KDoc): the delete knows its realm and the DI graph does not. It is
reached only from the `DeleteFile` op. Nothing else permanently deletes a `File` row on purpose,
and peers never run it, so S2 runs **only for a permanent delete made on this device** by
construction (§6.1 row 9).

**Finding 1: capture before the first mutation, retire in a `finally`.**
1. **Before `removeFromArray` (`:91`)**, and only when a hook is wired: a read-only walk of the
   subtree, using `deleteRecursively`'s `resolveList` traversal (`:158-205`), collects each leaf's
   path, `contentId` and `createdAt`.
2. `capture` runs one `LABELS` scan (§6.4.0 item 8), keeping every row at those paths: typed
   names, model-produced rows and the marker, i.e. D13's "every row". It returns the intent: the
   owner, the leaves, and the owed label `DataId`s per leaf. No version, no realm key needed.
3. The op persists it under the owner's directory (✅ **P0fb-D1**).
4. Run the delete, with one change in `deleteRecursively`: each leaf's `deleteData` (`:203`) runs
   inside a `database.transaction` that first deletes that leaf's captured label rows locally
   (`hook.retireLocally(ctx, path)`, point deletes only).
5. **In a `finally`**, whether the walk returned or threw, call `retire`: the emit step. A walk
   that died part-way emits exactly for the leaves it removed, by the leaf rule. Clear the intent on
   `COMPLETE`.

**Why capture first, and not per leaf as each goes.**
- The walk already accumulates `removedEntities` as each `deleteData` succeeds (`:203-204`). A
  `finally` over that list would close the *exception* case, but the list lives in memory, so a
  kill mid-walk loses it.
- A typed name has no other backstop. B2 never deletes one, and v0.7 rejected "no `File` row ⇒
  purged" because a move from an older build produces the same state.
- Retiring the *whole* set **before** the mutation was rejected: a delete that then failed would
  have removed the names of every file it did not reach (D13's irreversible direction). (b)'s
  per-leaf local delete narrows that to one leaf, and only on a throw or kill between two adjacent
  writes.
- The cost is one read-only subtree walk and one `LABELS` scan per delete, paid only when the hook
  is wired.

**`retire` (`:inference-service-proxy`, `MediaLabelRetirement`, shared by S2, S3 and S4):**
1. If `TombstoneEmitterHolder.emitter` is `null`, answer `RETRY`.
2. Otherwise, per chunk of 500 targets (the realm-reset pacing), run (b)'s emit step. Chunks are
   all-or-nothing, per `emitAll`'s contract (`TombstoneEmitter.kt:80-101`).
3. It never deletes: the rows went at the event. A `null` therefore costs nothing but the wait.

**Tests (§6.1 rows 1, 2, 5, 9, finding 1, and every case the rule implies):**
- **Deletes.**
  - deleting a folder with two named photos leaves no label row for either, and emits one target
    per row, in one `emitAll` per chunk;
  - **a walk that throws after k leaves retires exactly those k**, and the rest keep their labels;
  - **a process killed mid-walk**, intent written but never retired, is completed by the next
    replay.
- **Retry.**
  - `emitAll` answering `null`, and a null holder, delete nothing, answer `RETRY` and keep the
    intent, which a later replay completes;
  - an intent captured during cold start (emitter still `null`) is filed under the durable owner,
    and completes once the emitter is set;
  - a device in no realm, whose sync cycle never passes `isEnabled()`, replays from the
    maintenance hook and deletes locally.
- **Realm.**
  - a replay in realm B never reads realm A's directory;
  - **a realm adoption between capture and emit**, with the emitter's realm switched before
    `emitAll` runs: `emitAll(…, expectedOwnerId = A)` answers `null`, nothing is queued or
    deleted, and the intent stays in A's directory;
  - the same adoption on the immediate `finally` path;
  - a local-store wipe deletes every intent directory.
- **Rewrites and cold start (the round-3 P2 and round-4 P1 cases).**
  - **a row re-created at an owed `DataId` after the event** (a re-upload at the same path, named
    the same) exists at emit time, and is neither tombstoned nor deleted;
  - **a delete captured during cold start** (emitter `null`, realm keys not loaded): the capture
    is complete, the local rows go at the event, the replay after init tombstones every owed
    `DataId`, and **no owed `DataId` is cleared as `COMPLETE` without its tombstone, or without
    existing at emit time**;
  - a kill between a leaf's label delete and its file delete keeps the intent open, and the user's
    retry of the delete then emits its tombstones;
  - **a deleted path reused before replay**: the new photo keeps its own labels, and the deleted
    photo's typed name is tombstoned;
  - a leaf the crashed walk never reached, still holding the same `contentId` and `createdAt`,
    keeps its labels.
- **No purge.**
  - a move fires no purge;
  - with no owner source, no intent is written and the `finally` still retires.
- **API (PR-3).**
  - `emitAll` with an expected owner that does not match the resolved realm answers `null` and
    queues nothing;
  - (round 3's `lastWriteOf` is dropped; no version API is added).

**System tests:** a two-device delete (the peer ends with no label row for the path, typed name
included: §6.1 row 9's peer-ingest test); a folder delete; a purge from Photos' trash, which needs
S4 (PR-4).

#### 6.4.4 S4 — the move hook, operation-scoped, and §6.3 finding 3 closed (PR-4)

**Finding 3: an operation-scoped lifecycle, not a whole-mapping call after the move.**
`MoveFileImplementation` gains `moveHook: MoveHook?` (`:file-service-proxy`, a constructor
parameter like `moveFence` at `:71`):

```kotlin
fun interface MoveHook { suspend fun begin(executionContext: ExecutionContext, sourceRoot: String): MoveScope }
interface MoveScope {
    suspend fun moved(oldPath: String, newPath: String)  // per row, at the rekeyHistory point
    suspend fun finish(): RetireOutcome                  // once, in a finally; the §6.4.3 rule
}
```

What `MoveFileImplementation` must expose:
- **`execute` (`:99`) and `resumeMove` (`:126-134`)** each open one scope for the operation and
  thread it through the recursive `moveFile` as a parameter. The recursive call is at `:367`.
- **`finish()` runs in a `finally`** around the top-level call, so a failed or aborted cascade
  still flushes. A child `Failure` aborts at `:368-370`.
- **`moved` is called once per moved row, right after `rekeyHistory` (`:451`).** At that point the
  new row is saved (`:350` / `:435`) and the old one is not yet retired (`DataIdMigrator` at
  `:510`).
- **A deferred move (`moveFence` answers true, `:95-97`) opens no scope**: nothing moved. **[C]**
  The replay later applies it through the same factory instance
  (`ReplayPendingMovesImplementation.kt:44-61`), so it gets a scope then.

**Why not one `(mapping)` call after the move.** A mapping handed over after the walk would put the
typed-name saves after the last row moved. A kill mid-folder-move would then leave every name at
the old path while the photos sit at the new one: stranded, and not caught by anything. The scope
keeps the per-row save where §6.1 put it, so a kill leaves a name at both paths, never at neither.
It also gives the consumer the operation boundary it needs to build **one** label index.

**What the scope does — the §6.4.3 rule, applied whole** (v0.13 round 3: Codex P1, *persist
failed move tombstones*). `finish()` returned `Unit` in round 2, so a move made while the realm was
locked or still in cold start left the old-path labels on every peer for good. B2 deletes only
locally, and only re-derivable rows.
1. **`begin`**, called in `execute`/`resumeMove` before `moveFile`'s first mutation
   (`removeFromArray`, `:256`), captures the operation:
   - a read-only walk of the source subtree, for each leaf's `(contentId, createdAt)`;
   - **one** `LABELS` scan for the rows at `sourceRoot` or under `sourceRoot/`, giving the owed
     `DataId`s per old path. That is one scan per operation, closing finding 3: O(labels) once,
     not O(descendants × labels).
   - the owner;

   and **persists the intent** under the owner's directory. With no owner source it writes none
   and works in memory, per the rule.
2. **Each `moved(old, new)`** saves each captured **typed name** at `old` again at `new`: same
   capability, label, confidence and `producedBy = User`, an ordinary synced `saveData`, as
   `rekeyHistory` does. It then deletes `old`'s captured rows locally, inside the
   `database.transaction` that retires the old `File` row through `DataIdMigrator` (`:510`), with
   the labels first. That is the rule's (b).
3. **`finish()`** is `retire(intent)`, the shared path. The leaf rule retires an old path's rows
   only once that path no longer holds the captured identity, i.e. the file really left. A cascade
   that failed after `moved` but before the migrator retired the source keeps its names where the
   file still is. Rows that exist again at an owed `DataId` are skipped, and the owner check is
   inside `emitAll`. The intent is cleared on `COMPLETE`; on `RETRY` it waits for the
   maintenance-hook replay.

**What a peer sees. [C]** `DeleteBlock` has no successor field (`DeleteBlock.kt:20-61`), and a
label row has no wire dependency on a `File` row. So the name's save at the new path, its
tombstone at the old one, the file's own blocks and the retiring tombstone arrive in any order.
§6.1's *What a peer holds* stands: the gap shows a missing name, never a wrong one. **Trash then
restore** re-saves a name at the exact `DataId` the trash tombstoned. **[C]** On a peer the newer
save wins in either order: `processDeleteBlock` skips a tombstone older than the entity's last
write (`SyncProcessor.kt:247-263`), and a save after a tombstone recreates.

**Idempotence and crash points:**
- A resumed folder move walks only what is left under the source (`:121-125`), so a second scope
  sees only rows not yet handled.
- **A kill between the per-row saves and `finish()`** is now covered. The intent was written in
  `begin`, so the replay retires the old-path rows of every leaf that left. (v0.13 round 2 had
  this as an accepted dead-row case.)
- **A kill after a row's file save but before its `moved`** leaves the old row unretired. The old
  path still holds the identity, so its names are kept there: never at neither.
- **Trash, then restore, with the trash's intent still pending** (a locked realm): the restored
  file is back at the original path with its identity, which a move preserves, and its re-saved
  name exists at the owed `DataId`. So the late replay emits nothing there: by the leaf rule, and
  by existence.
- **[I] A pass that derives model-produced rows for an old path after the capture** leaves them
  behind locally, because they are not in the intent. B2 removes them where it runs. This is §6.1
  row 13's class: stated, not built.

**Tests (§6.1 rows 3, 4, 6, 7, 11, plus finding 3):**
- a moved photo keeps its name at the new path only;
- renaming a folder that holds two named photos keeps both names;
- trash, then restore, puts the name back at the original path;
- **a folder move of N photos scans `LABELS` once** (a counting relation reader);
- a retiring tombstone deletes no label;
- a crash between the save and `finish()` leaves the name at both paths, never at neither, and
  **the replay then retires the old path**;
- **a move made while the realm is locked, or in cold start** (`emitAll` → `null`, or a null
  holder): `begin`'s capture is complete with no realm key, the old rows go locally at the event,
  `finish()` answers `RETRY`, the intent stays, and **the replay after init** tombstones every
  owed old-path `DataId`. None is cleared without its tombstone, or without existing at emit time;
- **a realm adoption between `begin` and `finish()`**: `emitAll(…, expectedOwnerId)` answers
  `null`, and nothing is tombstoned in the new realm;
- trash then restore with the trash intent still pending: the late replay retires nothing at the
  original path;
- a deferred move re-keys nothing, and writes no intent.

**System tests:** a two-device move (the peer holds the name at the new path only, whichever block
arrives first); a trash and restore on a peer, in both block orders.

#### 6.4.5 S3 — the replace hook (PR-5; ✅ P0fb-D3: in P0f-b, not P10)

- **Where.** `uploadFile` is the one funnel for every replace: the common `ReplaceFile` and
  `ReplaceFileBg`, Android's descriptor replace (`AndroidFileReplaceImplementation.kt:60-69`) and
  iOS's source replace (`IosSourceFileImplementations.kt:105`). All call it with
  `replaceExisting = true`.
- **When — the §6.4.3 rule.** v0.13 round 3 (Codex P1): S3's `RETRY` used to be lost, and B2
  never deletes on a device that runs no pass.
  - **Capture** happens after the identical-content early return
    (`UploadFileImplementation.kt:238-246`) and before the new row is written. It records the
    path's old `(contentId, createdAt)` (one leaf), the owed `DataId`s of the marker and the
    model-produced rows, and the owner. It persists the intent.
  - **The local delete** of those rows and the new row's save share one `database.transaction`,
    labels first.
  - **`retire`** (the emit step) runs in a `finally` after the save.
  - The leaf rule emits only once the path no longer holds the old `contentId`.
  - **A replace that died between the two writes** leaves the old content with its model rows
    gone locally. They are re-derivable, and peers rightly keep them for content that did not
    change. So for S3 alone, a replay that finds the leaf not reached clears the intent as
    `COMPLETE`.
  - A row re-derived after the event exists at emit time and is skipped. ⚠ Every
  construction of `UploadFileImplementation` must receive it, including the Android and iOS ones,
  which are not built by `fileServiceProxyImplementationFactory`.
- **Consumer.**
  - It enqueues the path on S1's eraser (the same drain; on iOS there is none, per §6.4.2).
  - It retires the **marker and the model-produced rows** through the shared `retire`.
    "Model-produced" means `producedBy` parses as a model `ModelProvenance` and is not `User`.
  - **Never a typed name** (D13 replace = keep).
- **Finding 2 is not touched.** S3 is an event, made on this device, which tombstones. Finding 2 is
  about B2's *pass-time* check against a marker a peer re-derived. S3 neither reads nor writes a
  content hash, so whatever P10 builds for finding 2 (a device-local per-capability label visit
  keyed by hash) composes with it.
- **Tests:**
  - §6.1 row 8, and row 12's peer half;
  - a replace with identical bytes captures nothing;
  - **a replace while the realm is locked or in cold start** answers `RETRY`, and the replay
    tombstones the old model rows once the realm opens;
  - a realm adoption between capture and retire tombstones nothing;
  - a model row re-derived after the event, with the identical key, is kept;
  - **a replace captured during cold start** is replayed after init, and tombstones its owed
    `DataId`s;
  - a replace killed before its row write retires nothing, and its intent clears;
  - a typed name is never captured.

#### 6.4.6 Ownership, module direction and the DI lines

**[C]** `:inference-service-proxy` depends on no file service (`build.gradle.kts:93-101`), but it
does depend on `:sync-service-common` (`:101`), which holds `TombstoneEmitter`. So each seam is the
`LivePaths` pattern: the parameter type is in the module that fires, the consumer is in the module
that knows labels and stores, and Control adapts one to the other in a lambda.

| Seam | Parameter (fires) | Consumer | Control line(s) |
|---|---|---|---|
| Read filter | `LivePaths` (`:inference-service-proxy`, exists) | `livePathsAmong` (`:file-service-proxy`, exists) | `ProxyModule.kt:264`, all platforms. As built (PR-1): `fileLivePaths` in `MediaLibrarySeams.kt`; no `PlatformProxyModule` change (all three already pass `faceGrouping = get()`) |
| B1 | `MediaSource.walk` (exists) | `walkImageLibrary` + the same `LivePaths` | `ProxyModule.kt:396-428`, all platforms. As built (PR-1): `controlMediaSource` in `MediaLibrarySeams.kt`; no `PlatformProxyModule` change (all three already pass `mediaSource = get()`) |
| S1 | `DeadFilePaths` on `initFileService` | `DerivedStoreEraser` | `PPM-A:893`, `PPM-D:905`; **not** `PPM-I:743` |
| S2 | `PermanentDeleteHook` on `DeleteFileImplementation`, via `fileServiceProxyImplementationFactory` | `MediaLabelRetirement` + `TombstoneEmitterHolder` | factory calls at `PPM-A:1366`, `PPM-D:1323`, `PPM-I:1132`; the replay at each `addMaintenanceHook` site (`PPM-A:1160`; not `addSyncCycleHook`, §6.4.3) |
| S4 | `MoveHook` on `MoveFileImplementation`, via the same factory | `MediaLabelRetirement` | the same three factory calls |
| S3 | `ReplaceHook` on `UploadFileImplementation` | `DerivedStoreEraser` + `MediaLabelRetirement` | the three common factory calls **plus** `PPM-A:1352` (`androidFileServiceProxyImplementationFactory`) and `PPM-I:1123` (`iosFileServiceProxyImplementationFactory`) |

- ⚠⚠ **S2 and S4 run on iOS**, unlike S1. They write realm rows through the emitter, which every
  route-1 process has (`PPM-I:1010`), and a delete or a move made in Drive-on-iOS is exactly the
  event that must carry its names. Only the device-local stores have the single-owner rule.
- ⚠ **Every parameter defaults to `null`**, and a null seam is silent. Each PR adds a source-text
  wiring audit to `control-shared-proxy/src/desktopTest/…/di/`, in the `ReclamationRecorderWiringTest`
  shape, with its anti-vacuity check. The audit pins presence on the platforms that must wire a
  seam, and absence of S1 on iOS.
- **[C]** `:system-test-utils` has `:inference-service-common` and `-client` but not `-proxy`
  (`system-test-utils/build.gradle.kts:76-82`). Its `SystemTestPlatformModule` mirrors
  production's file wiring (`:355`, `:583`). PR-3 adds the dependency and wires the same adapters
  there. A system test with a hand-rolled consumer would pass on a wiring production does not have.

#### 6.4.7 Skew, lockstep and the threat model

- **HR 9: no stored or wire shape changes, so no version bump.**
  - Labels are saved and deleted under the existing `MediaLabel` schema.
  - Tombstones are ordinary `DeleteBlock`s.
  - The `TombstoneEmitter` addition (PR-3, `emitAll(…, expectedOwnerId)`) is a Kotlin interface
    method inside the proxy process, not an op parameter and not a wire field, so it is not a
    reshape either.
  - Every seam is a constructor parameter, never an op parameter: an optional op parameter would
    be a reshape.
  - The purge intent (P0fb-D1) is a device-local file, not a realm row, so it has no
    `SchemaShapeBaseline` entry.
  - ⚠ If the maintainer prefers the intent as a realm row, that is a new composite, and so a
    bump and a migration: P0fb-D1's cost column.
- **A peer on an older build (§6.1 row 13).** It applies every save and tombstone these seams emit,
  because they are ordinary blocks of a type it knows. For its own deletes, moves and replaces it
  runs no hook, so its events leave dead or stranded rows: row 13, accepted.
  - **[I]** An older Photos on a device that receives an S4 re-key into the trash can show that
    trashed name in `searchByLabel` until it updates, because the trash filter is client-side.
    First-party clients are not fenced by this change (nothing bumps), so this lasts until the app
    updates.
- **HR 2: three A40 amendments, each in the PR that makes it true.**
  - PR-1 rewrites A40's two "inert / not yet wired" sentences (`threat-model.md:1764-1770`): the
    predicate is wired and the source reports completeness.
  - PR-3 replaces "*person labels stay*" (`:1718-1719`): a permanent delete made on a device with
    S2 deletes and tombstones every label row of every leaf, typed names included. A peer drops
    them on ingest. An older build, or a hook that could not name the rows, leaves dead rows that
    no reader cites. It also says that label deletes expose their type and timing to the relay
    exactly as every other row delete does today, and that closing this for all types is the
    relay-metadata track (§6.4.0 item 5).
  - PR-4 widens "*`nameFaceGroup` is the only operation that writes realm data*" (`:1683-1684`): a
    move re-saves the user's existing names at the new path. That is no new content, only a new key.
  - ⚖️ A40's label half is already a counsel question (`policy/in6-face-clustering-counsel-packet.md`
    §6); deletion on purge goes into that packet.
- **No sync-protocol change**, so A1–A4 and A9 are untouched.

#### 6.4.8 The PR plan

Each PR leaves master consistent on its own and carries its own tests. The order is fixed by
dependency, not convenience.

| PR | What | Files (main) | Owes | Tests (§6.1 rows) | After |
|---|---|---|---|---|---|
| **PR-1** — read filter + B1 | `LivePaths` into `FaceGroupingComponents`; the production `MediaSource` reports completeness; A40 "inert" sentences | `ProxyModule.kt`; `threat-model.md`; a new desktopTest wiring audit | 📱 `scripts/android-smoke.sh` (Control DI); ⚠ `:control:mergeLibDexDebug` (a commonMain DI change has broken D8 with the gate green before); iOS lane (`ProxyModule` is commonMain and compiles into iOS); the F0 repro on a device with the face model (§3) | the production source's walk reconciles only when complete, and a short folder list reconciles nothing; `faceGroups` with the production predicate drops a deleted and a trashed path; `photosLike` likewise; rows 1–3 and 9–11 column (a)+(b) at the next read | — |
| **PR-2** — S1 | `DeadFileRowObserver` + `DeadFilePaths`; `DerivedStoreEraser`; wired on Android and desktop | `file-service-proxy` commonMain; `inference-service-proxy` commonMain; `PPM-A`, `PPM-D`; wiring audit (asserts iOS absence) | 📱 smoke; iOS lane (commonMain) | the observer only enqueues and never throws inside a transaction; a peer `processDeleteBlock` empties both stores with no pass (row 9); a peer folder tombstone empties every descendant via the reaper (row 10); a move empties the old path (rows 6, 7, 11); a reset of N files is a bounded number of `forget` calls; after S1 the log bytes no longer name the path; an incomplete `Removal` is logged, not looped | PR-1 (row 14 needs B1) |
| **PR-3** — S2 + the retirement-intent rule | `PermanentDeleteHook` + the leaf pre-walk + the `finally`; the shared rule (§6.4.3): owner-namespaced durable intents, the maintenance-hook replay, `MediaLabelRetirement.retire` (the emit step: leaf rule + existence check + `emitAll`; the local deletes happen at the event, §6.4.3 (b)); ⚠⚠ **one `TombstoneEmitter` API addition**, `emitAll(targets, expectedOwnerId)` checked against the resolved realm inside `SyncObserver` (round 3's `lastWriteOf` dropped in round 4); the per-leaf local label delete inside `deleteRecursively`'s leaf transaction; A40 "labels stay" (+ the item-5 sentence) | `DeleteFileImplementation.kt`; `fileServiceProxyImplementationFactory`; `inference-service-proxy`; **`sync-service-common/…/TombstoneEmitter.kt` + `sync-service-proxy/…/SyncObserver.kt`**; all three `PPM`s; ⚠ **`system-test-utils`: the `:inference-service-proxy` dependency (it has none, so PR-1 could add no scenario) + `SystemTestPlatformModule` wiring mirroring production**; `threat-model.md` | 📱 smoke; iOS lane **on the Mac** (`PPM-I` is iosMain); system tests | §6.4.3's list (realm switch between capture and emit, on the replay and on the `finally`; a re-creation at an owed `DataId`; **cold-start capture → replay after init**; a kill between label and leaf delete; no-realm replay; the API test); system (owed here, since PR-1 could not): two-device delete, folder delete, and PR-1's read-side and B1 scenarios on a peer that ingests a delete | PR-1. Independent of PR-2 |
| **PR-4** — S4 / D14 | `MoveHook` / `MoveScope` threaded through `moveFile` and `resumeMove`; `begin` captures a durable intent under the rule (source pre-walk + one `LABELS` scan); `finish()` returns `RetireOutcome`; the re-key consumer; A40 "only op that writes" | `MoveFileImplementation.kt`; factory; `inference-service-proxy`; three `PPM`s; `threat-model.md` | 📱 smoke; iOS lane on the Mac; system tests | §6.4.4's list (**a move captured in cold start → replay after init**; a move under a locked realm retried by replay; a realm switch between `begin` and `finish`; a crash before `finish` completed by replay; trash and restore with a pending intent); system: two-device move, trash and restore in both orders, purge from trash | PR-3 (shares the consumer). The `searchByLabel` trash filter it also needs is on master (PIO-4 slice 4, §6.4.0 item 3) |
| **PR-5** — S3 | `ReplaceHook` on `uploadFile`, passed to every construction; capture before the row write and retire in a `finally`, under the rule | `UploadFileImplementation.kt`; the common, Android and iOS file factories; three `PPM`s | 📱 smoke; iOS lane on the Mac (`iosMain` source impls); Android `androidMain` ⇒ HR 8 | §6.4.5's list (**a replace captured in cold start → replay after init**; a replace under a locked realm retried by replay; a realm switch; a re-derived row with an identical key kept; a replace killed before its write) | PR-2 and PR-3 |

- **No PR owes Swift.** No `UiState`, no surface, no SKIE-exported type changes. No PR owes
  schema lockstep, because nothing reshapes (§6.4.7).
- ⚠ **This changes one rule in the P0f-b row: S2 and S4 no longer ship in one PR.**
  - **S2 alone is consistent.** A Drive delete purges its names. A Photos purge from trash finds
    no names at the trash path, so they stay at the original path. That is what master does
    today, and those rows are hidden, because the original path has no `File` row and every reader
    joins by path.
  - **S4 alone is not.** It moves names into the trash, and a purge would then leave them there
    with nothing to delete them. So S4 merges after S2, never before it.
- **Gate per PR:** `scripts/run-gate.sh` on a tree merged with master, plus the column above.

#### 6.4.9 Decisions — ✅ DECIDED 2026-10-03 (maintainer)

Settled by the code, and so not decisions:
- S1 enqueues only.
- S1 is not wired on iOS (PIO-D10, §6.4.0 item 6).
- A null emitter deletes nothing and retries.
- Local delete at the event, tombstones owed by `DataId` (round 4; supersedes "emit before delete").
- The operation-scoped S4.
- No version bump.

Nothing in §6.4 needs a version bump or a protocol change.

| # | Choice | ✅ Decided | What it costs / what follows |
|---|---|---|---|
| **P0fb-D1** | Does S2's leaf set survive a process death? | ✅ **Yes**, as a device-local `FileDatabase` file, written before the first mutation. It holds each leaf's identity and its label keys, and it is bound to the realm through `SyncManager.residentOwnerId`. It is replayed from a maintenance hook and cleared only on `COMPLETE` (§6.4.3). | One extra read-only subtree walk per delete, a small file format, a replay hook and its tests. Rejected: a `finally` only, under which a killed folder delete strands typed names on every device; and a realm-row intent, which is a new composite and so an HR 9 bump. |
| **P0fb-D2** | S2 and S4 in one PR, or two? | ✅ **Two, S2 first; S4 never merges before it** (§6.4.8). | The `searchByLabel` trash filter it also needed is on master (PIO-4 slice 4, merged). |
| **P0fb-D3** | S3 in P0f-b, or left to P10? | ✅ **In P0f-b, as PR-5.** P10 shrinks to the content-hash key and B2. | One more smoke-gated PR, the widest of the five (`androidMain` + `iosMain`). The P10 row is narrowed accordingly. |
| **P0fb-D4** | The relay seeing label-tombstone counts | ✅ **Re-scoped: not a P0f-b property.** Every row delete already sends `targetEntityType` in the clear, for every type (§6.4.0 item 5). P0f-b proceeds as designed. Hiding the entity type for all types is a **separate relay-metadata track**, started in its own doc. | A40 and §6.4 say "label deletes expose their type and timing to the relay exactly as every other row delete does today; closing that for all types is the relay-metadata track", and neither calls it a P0f-b cost or something P0f-b must fix. |
| **P0fb-D5** | A Finder or File Provider delete on macOS (`ControlFileBridge.kt:189`) runs S2 like any delete. **[I]** An editor's atomic save might reach it as delete + create and take a typed name with it. | ✅ **Accepted for now.** | ⚠ **Owed:** one atomic save from Preview on a named photo; who and when not yet assigned. Exempting bridge deletes would need an op parameter, which is an HR 9 bump, and is not paid before that measurement says it happens. |

#### 6.4.10 Not settled from the code, and inferred

- ✅ ~~Which realm context the People and search reads run in~~: settled by PR-1 (§6.4.1).
- **[I]** The cost of one `LABELS` scan per delete or move operation on a large labelled library.
  Labels are inert today (no calibrated vocabulary), so nothing measurable exists yet. Owed
  alongside §6.1's tombstone-volume count.
- **[I]** The pass-writes-after-S1 race (§6.4.2) and the two S4 crash points (§6.4.4).
- **[I]** P0fb-D5's atomic-save path: owed as a measurement (§6.4.9), owner and date unassigned.
- **[I]** Whether each iOS route-1 process applies the sync ingest that kills a row. It does not
  matter for S1 (not wired there). S2 and S4 run in the process that made the event.
- **[C], recorded, not P0f-b's:** §6.4.0 item 9, the relay-side content leak on a delete whose
  walk throws.

#### 6.4.11 Open at merge — four review findings, each a hard constraint on its PR

§6.4 merged as a **living doc** (maintainer, 2026-10-04) after five review rounds. Codex raised these
on `8bd3216f4` and `d831365de`; they are **not designed here**. The PR that owns each seam closes it,
or says why not, before it merges. PR-1 is unaffected.

1. ⚠⚠ **A late index write after a replace looks fresh** — owner **PR-5** (S3). A pass that read the
   old bytes before a replace but finishes inference after it writes its marker and model labels
   after the event (`MediaIndexer.kt:515-519,647-669`). §6.4.3(b) treats any row that exists after the
   event as fresh, so S3 would skip their tombstones and leave labels derived from the old photo on
   the replacement; S1 removes no realm rows and P10's content-hash key has not shipped. The index
   write must revalidate the file's identity, or carry a generation fence, rather than every
   post-event row being preserved.
2. ⚠⚠ **A label saved during a long subtree operation escapes the capture** — owners **PR-3** (S2) and
   **PR-4** (S4). `NameFaceGroup` or the indexer can save a label after the pre-operation `LABELS`
   scan but before that leaf's transaction: it is in neither the captured `DataId` set nor the point
   deletes, so for S2 it syncs and survives a permanent delete, and for S4 a newly typed name stays at
   the old path. Concurrent label writes need a fence, or the applicable rows must be enumerated and
   deleted atomically with each file-row mutation instead of from a snapshot taken before the walk.
3. ⚠⚠ **Local absence does not prove causality across devices** — owners **PR-3**, **PR-4**, **PR-5**
   (every replayed intent). If another device re-creates a label at an owed `DataId` after this
   device's delete but before its delayed replay, and this device has not pulled that save, the point
   read reports the row absent and the replay emits a tombstone with a later replay-time HLC — which
   can delete the genuinely new label on the other device. §6.4.3(b)'s "a row that exists was written
   after the event" holds only for writes this device has seen. The intent needs a causal fence (e.g.
   a tombstone stamped at the event's HLC, not the replay's, so a later peer save wins by LWW), or
   another mechanism that protects peer-only rewrites.
4. ⚠⚠ **The realm check must hold through queueing, not only through resolution** — owner **PR-3**
   (the `emitAll(targets, expectedOwnerId)` addition). An adoption can complete after the owner
   comparisons but before the blocks reach the outbound queue, so A-addressed tombstones drain under
   B's credentials; `emitAll` reports success and the intent is cleared although A's peers never get
   the deletion. `SyncObserver.emitAll`'s steps (`SyncObserver.kt:1629-1664`) are not serialised with
   realm transitions: the owner check must share a lock with queueing, or write into a realm-scoped
   queue.

---

## 7. What this does not do

- No model choice, threshold or vocabulary. Those belong to `inference-service.md` and
  `photos-people-and-pets.md`.
- No rung sizes or caps (`drive-xxlarge-thumbnail-rung.md`), and no change to *which* device mints
  (D-1 of `drive-synced-thumbnails.md` §12.5 stands: the device holding the bytes).
- No relay, wire or sync-protocol change. Every proposal is device-local scheduling and decoding.
  HR 1 and HR 2 are untouched; HR 3 holds, since every new seam is internal and no `Operation`
  interface gains `suspend`.
- iOS MOTION and iOS indexing are owned by `photos-ios.md` PIO-6/PIO-7. This doc only asks that the
  queue, budget and policy land in `commonMain` so those phases inherit them.

## 8. Pointers

- `inference-service.md` — §15.12 (IN-3m numbers), §26 (the pass), §32.9 (conditions), §34 (*Index now*, runner)
- `photos-people-and-pets.md` — §12.12–§12.15 (face backend, tiled detection, previews vs originals), §6 P1 (pets)
- `drive-synced-thumbnails.md` §15 (backfill); `drive-xxlarge-thumbnail-rung.md` open items 8–14
- `photos-video-support.md` (MOTION); `drive-content-preview.md` (poster, PDF)
- `core-background-processing.md` §10.6; `core-task-composite.md`; `content-plane-transfer-concurrency.md`
- `photos-ios.md` §3 (iOS AI tiers, single store owner)
- `tech-debt.md` — *Control's UI thread can starve behind the store lock*; *Sample-Size-Aware Image Decoding for List Thumbnails*
  (UI only — F1 is the mint-side twin and has no entry)
