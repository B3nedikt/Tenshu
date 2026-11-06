# Folder Upload — Client-Orchestrated Walk, Inline Conflict Resolution, Ambient Status

**Status:** **Phase 1 + Overwrite + Phase 2 landed** (`master`, 2026-05-15).
**Phase 3a-wiring landed** (merged to `master`
2026-05-19) — see §5c. Phase 1 = client-side recursive walk reusing the
existing single-file upload op per file, inline Skip / Keep-both, non-blocking
ambient bar. **Overwrite** = an atomic `ReplaceFile` op (§9), not
delete-then-reupload. Phase 2 = foreground cancel + terminal summary.

**§11 added 2026-07-23 (P1 + P2 COMPLETE):** desktop cloud-backed source folders
(OneDrive / iCloud / Dropbox on `NSFileProvider`) hung the upload because a plain
`readBytes()` blocks on the on-demand fault-in of a dataless placeholder. Decision =
**transparent handling** (Option 3): detect online-only files, tell the user "these
download from OneDrive first, then upload", **default include**, easy **skip** opt-out
(off by default). **P1** (abandonable read timeout → per-file `Failed`, not a hang)
shipped. **P2a** (the `SF_DATALESS` `MacDatalessProbe` + `isOnlineOnly` wired
through enumeration to `UploadProgress`, **plus the per-file "Downloading from the
cloud…" label**) shipped. **P2b + P3** (the up-front pre-flight prompt — Continue /
Skip-online-only / Cancel, one dialog) shipped. §11 complete — see §11.5.

**Phase 3a** delivers the *buildable now* portion of survive-app-close,
decided with the maintainer (cancel-surface + loop-owner forks, §5c): the
folder loop is a long-running Layer-2 task off the UI lifecycle (app-scoped
`FolderUploadController`), per-file writes use new `BackgroundOperation`
upload-op variants so cancel reaches the **in-flight** per-file encrypt in the
Control proxy **cross-process** (Phase L2 runtime — *as landed*,
no new wire surface), and the Android SAF tree grant is persisted so it
outlives a transient teardown. **Boundary:** fully effective on **Desktop**
today (resident in-process proxy = L2 liveness for free). On **Android** the
process-liveness wrapper it depends on — `core-background-processing.md`
**Phase L2-LIVE** (Control-side `ForegroundTaskLiveness` driven by the
`ProxyGateway` in-flight registry) — **is now also on `master`**
(2026-05-19; Phase 5 likewise), so the runtime is complete; the only
remaining 3a item is **end-to-end Android verification** (no Android SDK in
the build env — verify in Android Studio, as with the L2 runtime). The Task-composite
unification the maintainer raised during this work
([`core-task-composite.md`](core-task-composite.md)) is a separate, deferred
`:core` proposal — 3a deliberately rides Phase L2 *as landed* rather than
blocking on it. **3b** (deferred/metered, survive Control death) still needs
Layer 3 (`HeadlessSafe` + `ScheduleService`, §3.2/§2.3/§7.1; Phase 5 landed,
so 3b is gated on the remaining Layer-3 platform glue, not the trait).

**Verification (2026-05-15):** all compilation green — `:drive`
(Android, transitively all `file-service*` androidMain incl. the new
`ReplaceSourceFile` wiring), `:drive-desktop`, `:file-service-proxy`
androidMain, `:drive-shared` desktop. `:drive-shared:desktopTest` green on a
forced re-run (ViewModel Skip/Keep-both/Overwrite + apply-to-all, desktop
enumeration, integration fakes). **`:file-service-proxy:jvmTest` now
67/67 green** including `ReplaceFileOperationTest` (both cases). It was
initially blocked by a pre-existing `createFileProxy(this)` test-job leak
(28/67 failing with `UncompletedCoroutinesError`); that was root-caused and
fixed as a **separate, scoped `:file-service-proxy` test-infra change**
(`runTest` scope → `backgroundScope`), merged separately — see
`docs/tech-debt.md` "`:file-service-proxy:jvmTest` — `createFileProxy(this)`
Leaked the Test Job". That fix is logically independent of this feature.

**Phase 1 deviations from §5/§5b** (recorded per House Rule):
- Keep-both is shared as a **top-level pure** `keepBothCandidateName(name,
  counter)` in `FolderUpload.kt` (not a private cross-layer helper) —
  consumed byte-identically by both `AddFileActionsViewModel.resolveConflictKeepBoth`
  and the repository folder loop. Cleaner than coupling the ViewModel and
  repository layers; satisfies the §5b intent.
- The ViewModel test lives in **`desktopTest`**, not `commonTest`: the
  `commonTest` source set has no `kotlinx-coroutines-test` (needed for
  `Dispatchers.setMain`), and every existing `:drive-shared` test is already
  `desktopTest`. Adding a coroutines-test dep to `commonTest` would be a
  wider build change than warranted (memory: minimal existing changes).
- `ensureFolder` treats a non-`AlreadyExists` `createFolder` failure as
  non-fatal and continues; `LogUnexpectedErrorUseCase` takes a `TError`, not
  a `Throwable`, so the failure isn't logged through it — it instead surfaces
  via the summary's `failed` count when the subsequent file uploads into the
  missing path fail. Acceptable for v1; revisit if folder-create failures
  prove common.
- Two pre-existing test doubles required the new signatures
  (`FakeFilesRepositoryPlatformDelegate` in `AppIntegrationTestUtils.kt`,
  the anonymous `FilesRepository` in `FilesScreenTest.kt`) — updated to
  return empty/no-op folder results.

**Scope:** `:drive-shared` (`data/FilesRepository.kt`,
`data/FilesRepositoryPlatformDelegate.kt` + Android/Desktop actuals,
`ui/common/addfileactions/*`, `ui/App.kt`), `:drive`
(`android/MainActivity.kt`), `:drive-desktop` (`Main.kt`). No `:file-service`,
`:file-service-proxy`, `:sync-*`, or relay changes — v1 reuses the existing
`uploadFile` / `uploadSourceFile` / `createFolder` operations unchanged. No
threat-model amendment required for v1 (§4); Phase 3 inherits the amendment
already scoped in `core-background-processing.md` §6.4.

`FilesRepository.uploadFolder(uriString, folderPath)` is a `TODO()` today
(`drive-shared/.../data/TenshuFilesRepository.kt:112`). What looks like a
one-line stub is actually an architecture question, because "upload a folder"
on Android cannot be expressed the way single-file upload is. This proposal
records why the design is forced to a particular shape, what v1 builds, and
where the genuinely-hard parts (background survival, cancel/restart) are
deferred to an existing proposal that already owns them.

---

## 1. Motivation

The FAB offers "Upload folder". On Android it fires `OpenDocumentTree` and
calls `filesRepository.uploadFolder(treeUri, path)` directly
(`drive/.../MainActivity.kt:44-53`); the repository throws. On Desktop the
folder picker resolves a path and `println`s "Not implemented yet"
(`drive-desktop/.../Main.kt:78-91`). The feature is half-wired and dead at
the repository.

The naive mental model — "pass the folder URI to the proxy, let it walk and
upload, exchange messages on conflicts" — does not work on Android, and
understanding why determines the entire design (§2.1).

---

## 2. Design Decisions

Locked in for the rest of the doc.

### 2.1 The directory walk is client-side, by necessity — not by preference

Single-file upload works because the Drive process holds the SAF grant and
spends it once, at open time:

```kotlin
// core-common/src/androidMain/.../transport/BinderTaskTransport.kt:87
override fun sendFile(uri: String) {
    val parsedUri = uri.toUri()
    context.contentResolver.openFileDescriptor(parsedUri, "r")?.use {
        proxyService?.sendFile(it, uri)   // already-opened FD crosses the Binder
    }
}
```

`openFileDescriptor` runs in the **Drive process**, which received the
`OpenDocument`/`OpenDocumentTree` grant. The resulting fd is permission-free
to read in **any** process, so the Control (proxy) process — a *separate app*,
`app.tenshu.control.android`, bound via `bindService` — reads the bytes off
the fd without needing the grant.

A folder has no equivalent. `OpenDocumentTree` returns a
`content://…/tree/…` URI backed by a (possibly virtual) DocumentsProvider.
"List a folder" is not a `read()` on a directory fd — it is a
`ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(…))`
into the provider, **gated by the URI permission on every call**. There is no
fd to hand across the Binder that yields an enumerable handle on the other
side. The Control process does not hold the tree grant and cannot enumerate
it.

The only Android mechanism that would let the proxy walk the tree is
transferring the grant (`grantUriPermission` / Intent flags). That is
rejected for v1: it is only reliably honoured by the AOSP
`ExternalStorageProvider`, it is a real expansion of the Drive↔Control trust
boundary (Control would gain direct read of a user-picked filesystem slice — a
threat-model question), and it reintroduces the proxy-side loop + bidirectional
conflict round-trip it was meant to avoid. Batching every child fd into one
bulk proxy call is also rejected: a single Binder transaction caps at ~1024
fds / ~1 MB, so large trees would need a streaming protocol with back-pressure
anyway.

**Decision:** the recursive walk runs in the Drive process (the only one that
can enumerate the tree). Each file reuses the existing single-file upload path
(`sendFile` + `uploadSourceFile` on Android, `uploadFile(bytes)` on Desktop)
exactly as a manual single-file upload does, N times. The persisted tree-URI
grant covers descendant document URIs derived via `DocumentFile`, so per-child
`sendFile` works unchanged.

### 2.2 Conflicts are inline per-file results, not a separate protocol

There is no paused-operation / await-user-decision primitive anywhere in the
platform (the established human-in-the-loop shapes are *fast-fail + client
retry*, today's `AddFileActionsViewModel.resolveConflictKeepBoth`, and
*separate op + client poll*, pairing). Because the walk is client-side,
nothing more is needed: a duplicate is just the per-file call returning
`TResult.Failure(AlreadyExistsError)` — the return value of a call the Drive
process already made in its loop. The loop suspends on a callback into the
ViewModel, the UI surfaces a dialog, the user's choice resumes the loop. One
loop, no wire stream, no new operation.

### 2.3 Conflict set: Skip + Keep both + Overwrite

`Skip` is trivial (don't upload, count it). `Keep both` reuses the existing
`" (n)"` suffix-retry (now factored into the pure `keepBothCandidateName`).
**Overwrite landed 2026-05-15** as a new atomic `ReplaceFile` op (§9), not
delete-then-reupload — see §9 for why the delete+recreate shape is
forbidden. v1 (Phase 1) shipped Skip + Keep both first; Overwrite followed
immediately as the scoped follow-up §6 anticipated.

### 2.4 `uploadFolder` becomes a ViewModel-routed suspend operation

Today `FilesRepository.uploadFolder` is non-suspend and Android calls it
directly off the activity result, bypassing `AddFileActionsViewModel` (Desktop
doesn't call it at all). For parity with `uploadFile` (suspend, routed through
the ViewModel's `viewModelScope`) and because the loop needs a coroutine plus
a place to hold observable status, the call path is unified through
`AddFileActionsViewModel.uploadFolder(...)`. The interface signature changes
(§3.1). This is a deliberate, small break flagged here per House Rule —
justified by the loop + status requirement and by removing the
activity-bypasses-ViewModel inconsistency.

### 2.5 Ambient status: indeterminate + "N of M", no percentage

Investigation of the real upload path: the Drive→Control hop itself is *not*
app-encrypted — bytes cross the Binder as a raw `ParcelFileDescriptor`
(Android) / `ByteArray` (Desktop) over local same-device IPC. Inside Control,
per file the operation reads the whole file into memory → serializes →
XChaCha20-Poly1305 AEAD-**encrypts the SyncBlock** → writes blob + entity to
the local DB → (Android) blocks on a synchronous Binder hop. That encryption
is the mandatory E2E step, not a transport/perf choice — it is the ciphertext
the relay and other paired devices see, governed by House Rule #1 and
threat-model asset A1 (the relay must never see plaintext); it cannot be
skipped to speed uploads. The relay push is **not** part of the operation
(encrypted blocks are queued locally; a background `SyncScheduler` drains
them later). Cost is therefore intrinsic to "the file must be E2E-encrypted
to sync at all."
Cost is CPU/IO-bound and **linear in file size**, and uploads are strictly
**serial** (no batching/parallelism anywhere). Per-file completion is
therefore lumpy and proportional to size, with no sub-file progress signal.

**Decision:** the status surface is a non-blocking bottom bar with an
**indeterminate** progress indicator, the current file name, and a smooth
**"N of M files"** count. No byte-percentage (it would jump and there is no
sub-file signal to drive it). The bar never blocks app use; navigation
continues underneath it.

---

## 3. Final Shape

### 3.1 Repository surface

`drive-shared/.../data/FilesRepository.kt`:

```kotlin
// before
fun uploadFolder(uriString: String, folderPath: String)

// after
suspend fun uploadFolder(
    uriString: String,
    folderPath: String,
    onProgress: (UploadProgress) -> Unit,
    onConflict: suspend (FileConflict) -> ConflictResolution,
): RepositoryResult<FolderUploadSummary>
```

```kotlin
// drive-shared/.../data/ (new file: FolderUpload.kt)
data class UploadProgress(val currentFileName: String, val completed: Int, val total: Int)

data class FileConflict(val fileName: String, val destinationFolder: String)

sealed interface ConflictResolution {
    data object Skip : ConflictResolution
    data object KeepBoth : ConflictResolution
    // applyToAll is remembered in the ViewModel, not encoded here — the
    // repository simply isn't called back once a blanket choice is set.
}

data class FolderUploadSummary(
    val total: Int,
    val uploaded: Int,
    val skipped: Int,
    val failed: Int,
)
```

The flow control stays exactly the shape of §2.2: the repository drives the
walk, calls `onProgress` before each file, and on `AlreadyExistsError`
suspends on `onConflict`; the ViewModel's `onConflict` awaits a
`CompletableDeferred` completed by the dialog buttons. `failed` is best-effort
(continue past per-file failures, aggregate into the summary) — the partial
failure default agreed up front.

### 3.2 Platform delegate gains enumeration only

`drive-shared/.../data/FilesRepositoryPlatformDelegate.kt` gains one method;
upload itself still goes through the existing `uploadPlatformFile`:

```kotlin
suspend fun listFolderEntries(uriString: String): FolderListing

data class FolderListing(
    val rootFolderName: String,                 // e.g. "Photos"
    val entries: List<FolderEntry>,             // depth-first, dirs before their files
)
data class FolderEntry(
    val relativePath: String,                   // POSIX, relative to rootFolderName, no leading slash
    val isDirectory: Boolean,
    val fileName: String,
    val leafUriString: String,                  // "" for directories
)
```

- **Android** (`AndroidFilesRepositoryPlatformDelegate`): walk
  `DocumentFile.fromTreeUri(context, uriString.toUri())` recursively. Each
  child's `DocumentFile.uri.toString()` is the `leafUriString` — it is a
  descendant document URI covered by the tree grant, so the existing
  `uploadSourceFile(uri = leafUriString)` → `sendFile` path works unchanged.
  Requires `androidx.documentfile:documentfile` on `:drive-shared` androidMain
  (flag in §5b).
- **Desktop** (`DesktopFilesRepositoryPlatformDelegate`):
  `java.io.File(uriString).walkTopDown()` on `Dispatchers.IO`; `leafUriString`
  = absolute path (consumed by the existing path-based `uploadFile`).

The repository composes these with operations it already calls:
`client.fileService.defaultFileSystem.createFolder(path)` for each directory
(idempotent — `AlreadyExistsError` treated as success), then
`delegate.uploadPlatformFile(...)` per file with
`parentPath = "$folderPath/$rootFolderName/${entry.relativePath.parent}"`.

### 3.3 ViewModel + UI

`AddFileActionsUiState` gains a nullable folder-upload block:

```kotlin
data class AddFileActionsUiState(
    val showAddFolder: Boolean = false,
    val showAddFileActions: Boolean = false,
    val uploadConflict: UploadConflict? = null,           // existing single-file path, unchanged
    val folderUpload: FolderUploadState? = null,          // new
)

data class FolderUploadState(
    val progress: UploadProgress,
    val conflict: FileConflict? = null,                   // non-null ⇒ show dialog
)
```

`AddFileActionsViewModel.uploadFolder(uriString, parentPath)` launches in
`viewModelScope`, holds a `var blanketChoice: ConflictResolution?` and a
`var pendingConflict: CompletableDeferred<ConflictResolution>?`, passes
`onProgress` (writes `_uiState`) and `onConflict` (if `blanketChoice != null`
return it immediately; else publish `conflict` into `_uiState` and await the
deferred). Dialog callbacks `resolveFolderConflict(resolution, applyToAll)`
complete the deferred and set `blanketChoice` when `applyToAll`. On completion
the summary is surfaced and `folderUpload` cleared.

The ambient bar + dialog render in `ui/App.kt` over the existing
`BottomSheetScaffold` (the natural single root), observing
`fabViewModel.uiState`. Suggested copy:

> **Bar:** "Uploading… {fileName}" · indeterminate · "{completed} of {total}"
>
> **Dialog title:** "Item already exists"
> **Body:** "\"{fileName}\" already exists in {destinationFolder}."
> **Subtext:** "Keep both adds it as \"{name} (1)\"."
> **Buttons:** `[Skip]` `[Keep both]`
> **Checkbox:** "Apply to all remaining conflicts"

### 3.4 Call sites

- `drive/.../MainActivity.kt`: `openDocumentTreeLauncher.onResult` calls
  `addFileActionsViewModel.uploadFolder(uri.toString(), path)` instead of
  `filesRepository.uploadFolder(...)`.
- `drive-desktop/.../Main.kt`: `onUploadFolder` replaces the `println` with
  `scope.launch { addFileActionsViewModel.uploadFolder(fullPath, parentPath) }`,
  mirroring its own `onUploadFile`.

---

## 4. Threat-Model Amendments

**None for v1.** Client-orchestrated upload is N invocations of the existing
single-file upload operation. The relay sees the same ciphertext SyncBlocks it
would for N manual uploads; block opacity (asset A1), the sync protocol, and
the Drive↔Control process boundary are unchanged. No A1–A4/A9 surface is
touched. This is stated explicitly per House Rule #2 rather than omitted.

Phase 3 (background survival) inherits the amendment **already scoped** in
[`core-background-processing.md`](core-background-processing.md) §6.4
(scheduled context cannot assume user presence; the deferred upload op is
`HeadlessSafe` and scheduled via `ScheduleService` — §3.2, the revised Layer 3
shape). Only **3b** touches that amendment; **3a** is Layer 2 only — the
foreground notification its liveness wrapper raises (§5.2) is UI surface, not
a threat-model surface (no A1–A4/A9 change). No new amendment is authored
here; Phase 3 lands under that proposal's umbrella.

---

## 5. Implementation Plan

### Phase 1 — Core feature _(v1, shippable)_

**Files modified:**
`drive-shared/.../data/FilesRepository.kt` (signature, §3.1),
`drive-shared/.../data/TenshuFilesRepository.kt` (implement the walk),
`drive-shared/.../data/FilesRepositoryPlatformDelegate.kt` (+`listFolderEntries`),
`drive-shared/.../data/AndroidFilesRepositoryPlatformDelegate.kt`,
`drive-shared/.../data/DesktopFilesRepositoryPlatformDelegate.kt`,
`drive-shared/.../ui/common/addfileactions/AddFileActionsUiState.kt`,
`drive-shared/.../ui/common/addfileactions/AddFileActionsViewModel.kt`,
`drive-shared/.../ui/App.kt`,
`drive/.../android/MainActivity.kt`,
`drive-desktop/.../Main.kt`.
**Files added:** `drive-shared/.../data/FolderUpload.kt` (the data types).
**Tests added:** `AddFileActionsViewModelFolderUploadTest` (fake
`FilesRepository`: progress sequence; Skip leaves a file out; Keep-both
suffixes; "apply to all" stops further `onConflict` calls; best-effort
summary). Desktop delegate enumeration test over a temp dir tree.
**Exit criteria:** uploading a nested folder on Desktop and Android lands the
full tree under `{folderPath}/{rootFolderName}/…`; conflicts surface the
dialog; bar shows N-of-M and never blocks navigation; summary reported.

### Phase 2 — Foreground polish _(landed 2026-05-15)_

In-foreground **Cancel** on the bar (cancels the `viewModelScope` child job;
`TenshuFilesRepository.uploadFolder` is now cancel-cooperative —
`currentCoroutineContext().ensureActive()` between files, so the in-flight
single-file op completes and the loop then stops; already-uploaded files stay,
which is correct). Terminal **summary** + **`failed`** surfacing
("Uploaded 42, skipped 3, 1 failed" / "Upload cancelled — N of M done" /
"Couldn't read that folder"). Cancel is *in-process only* and only meaningful
while the app is foreground; background survival is Phase 3.

**Deviation from the §5 "snackbar" wording (recorded per House Rule):** the
app has no `SnackbarHost` and Phase 2 must add *no new platform surface*, so
the terminal summary **reuses the existing ambient bar** — `FolderUploadState`
gains a terminal `outcome` (`FolderUploadOutcome.Completed/Cancelled/Failed`),
the bar renders it instead of progress, and the ViewModel auto-dismisses after
5 s (manual Dismiss also available). Consistent with §2.5's ambient-bar
design; a Snackbar would have meant introducing a host into `App.kt`.
Tests: `AddFileActionsViewModelFolderUploadTest` —
`completion_showsSummaryThenAutoDismisses`,
`cancel_setsCancelledOutcome_andStopsTheLoop`, `listingError_showsFailedOutcome`.

### Phase 3 — background survival _(split: 3a runtime-unblocked, 3b deferred)_

**Not designed in this proposal**; it is the file-upload instance of
[`core-background-processing.md`](core-background-processing.md). Phase L2
(`TaskHandle` + cross-process cancel) **landed**, which splits
this cleanly:

- **Phase 3a — survive-app-close (runtime-unblocked, liveness wrapper still
  outstanding).** Folder-upload as a long-running `BackgroundOperation`
  returning a `TaskHandle` (Layer 2, §2.2): the upload survives the Drive UI
  closing, with robust cross-process cancel/restart and progress that outlives
  the request. **Needs no Layer 3**, but it is *not* "pure Layer 2, done" — it
  needs two distinct pieces from `core-background-processing.md`: the L2
  `TaskHandle` **runtime** (landed) **and** the per-platform L2
  **process-liveness** wrapper that keeps the proxy from being OS-reclaimed
  while the upload runs after Drive is gone (that proposal's **Phase L2-LIVE**:
  an Android foreground service; desktop comes free with the resident daemon).
  L2-LIVE is **not built yet** — without it, on Android the upload still dies
  when the OS reclaims the backgrounded proxy, so 3a is *runtime-unblocked but
  not deliverable* until L2-LIVE lands. Also needs
  `takePersistableUriPermission` on the tree URI (Phase 1 deliberately does
  not persist — the grant only needs to outlive a foreground upload). This is
  the high-value 80%, gated now only on L2-LIVE (no Layer 3). **The 3a-wiring
  for this — chosen surface, controller, cross-process cancel, persisted
  grant, the honest Android boundary, and verification — landed; see §5c.
  L2-LIVE itself landed on `master` 2026-05-19, so the only remaining
  3a item is end-to-end Android verification in Studio.**
- **Phase 3b — constraint-deferred upload (still deferred, needs Layer 3).**
  "User picks 4 GB on metered → defer until `network = UNMETERED`" / survive
  Control/device death. This is a `HeadlessSafe` upload op scheduled via
  `ScheduleService` with a `ScheduleItem` (`ConstraintSpec.network = UNMETERED`,
  `dedupKey = "upload:<…>"`) — see `core-background-processing.md` §3.2 / §2.3
  / §7.1. Lands with Layer 3 (that proposal's Phase 5+), not here.

This section records the linkage + the persisted-grant prerequisite; 3a/3b
land under the relevant proposal/track, not this one.

---

## 5b. Implementation Notes for Claude Code

- **Signature break is intentional (§2.4).** Every `FilesRepository`
  implementation and the two call sites must change together; the only
  production impl is `TenshuFilesRepository`. There is no test double in the
  tree implementing `FilesRepository` today — add a fake in
  `:drive-shared` commonTest for the ViewModel test.
- **Reuse, don't reinvent, Keep-both.** The `" (n)"` counter loop in
  `AddFileActionsViewModel.resolveConflictKeepBoth:69-102` is the exact
  semantics for `ConflictResolution.KeepBoth` — factor it into a private
  helper the per-file folder path also calls, rather than duplicating.
- **`createFolder` is idempotent here.** `TenshuFilesRepository.createFolder`
  already maps `AlreadyExistsError → RepositoryError.AlreadyExists`; in the
  folder walk treat that specific error as success and continue (the folder
  pre-existing is normal on re-upload).
- **Android dependency:** `androidx.documentfile:documentfile` is likely not
  yet on `:drive-shared` androidMain. Check `drive-shared/build.gradle.kts`;
  add it under the androidMain source set if absent. `DocumentFile.fromTreeUri`
  + recursive `listFiles()` is the walk; do **not** try to enumerate from the
  Control side (§2.1).
- **Desktop walk on IO.** Mirror the existing dispatch discipline in
  `DesktopFilesRepositoryPlatformDelegate.uploadPlatformFile` (its comment at
  lines 24-37 explains the EDT hazard) — do the `walkTopDown()` inside
  `withContext(Dispatchers.IO)`.
- **`parentPath` assembly.** `uploadPlatformFile` takes a `parentPath`;
  build it as `folderPath` + `/` + `rootFolderName` + the entry's relative
  parent, normalising double slashes. `folderPath == "/"` is the common root
  case — match `getFiles`'s `"/"` handling style in `TenshuFilesRepository`.
- **Don't add a wire Stream or a new Operation.** The whole point of §2.1/§2.2
  is that v1 is pure client orchestration over existing ops. A `Stream`/sub-
  service surface is the *wrong* shape here and is explicitly out of scope.
- **UI placement:** the bar/dialog belong in `App.kt` over the existing
  `BottomSheetScaffold` root, observing `fabViewModel.uiState`, so it survives
  navigation between `entry<…>` destinations. Do not put it inside an
  individual screen.

---

## 5c. Phase 3a — what landed (`feat/folder-upload-p3a`, 2026-05-18)

The §5 Phase-3a description left two mechanics open; both were decided with the
maintainer before implementation (the locked decisions in §2 fix only that the
*walk* stays client-side — they explicitly defer 3a's internal shape here).

**Decision 1 — cancellable surface = Phase L2 as landed, via new
`BackgroundOperation` upload-op *variants*.** Robust cross-process cancel needs
the in-flight per-file op in Control to be wire-cancellable; per
`DefaultInteractorProvider`, that requires the op to carry `BackgroundOperation`
(→ `CancellableTaskInteractor` → `TaskHandle`), not `IoOperation`. Rather than
flip the existing `UploadFile` / `UploadSourceFile` / `ReplaceFile` /
`ReplaceSourceFile` ops (which would change `TResult`→`TaskHandle` for the
manual single-file path and every caller/test), four **variants** were added —
`UploadFileBg`, `ReplaceFileBg`, `UploadSourceFileBg`, `ReplaceSourceFileBg`
— each reusing the *existing op's* `Params` constants and the *existing
implementation class* verbatim (registered under the new ID in the proxy
factories). Only the trait set differs (`[Terminal, Background]`), so the proxy
behaviour is byte-identical and the manual single-file path is untouched. This
deliberately uses Phase L2 **as it landed** (no `uploadTaskId` param, no new
transport surface). A Stream-style "id-as-hidden-param + sibling cancel op"
generalisation was considered and is recorded as the separate, deferred
[`core-task-composite.md`](core-task-composite.md) proposal (it reopens the
Interactor model + Phase L2 + Stream and has two unresolved hard problems —
result-delivery / double round-trip, and cancel out-of-band-ness — so it must
not block 3a).

**Decision 2 — loop owner = app-scoped `FolderUploadController` singleton.**
The loop moved out of `AddFileActionsViewModel.viewModelScope` (died with the
Activity) into a Koin `single` `FolderUploadController` owning a
process-lifetime `CoroutineScope` + a `StateFlow<FolderUploadState?>`. The
ViewModel is now a thin mirror: it `collect`s the controller state into
`uiState.folderUpload` and forwards `uploadFolder`/`cancel`/`resolveConflict`/
`dismiss`. **The UI surface (App.kt / FolderUploadUi) is unchanged.** A
recreated ViewModel re-attaches to an in-flight upload via the mirror
collector. `FilesRepository.uploadFolder`'s **signature is unchanged**; only
its internals route per-file through the cancellable delegate methods.

**Cross-process cancel mechanic.** `FilesRepositoryPlatformDelegate` gained
`uploadPlatformFileCancellable` / `replacePlatformFileCancellable` returning
`TaskHandle<Data?>` (Android → `*Background` content-URI ops; Desktop → bytes).
`TenshuFilesRepository`'s per-file step awaits via the top-level `internal`
`TaskHandle<Data?>.awaitOrWireCancel()` (in `FolderUpload.kt`): on loop
cancellation the suspended `await()` throws, and the helper calls
`handle.cancel()` so the Phase-L2 wire `cancel(taskId)` reaches the proxy and
the in-flight encrypt is actually stopped — no longer "the in-flight file
completes" (Phase 2). Restart is cooperative: already-written files stay; a
re-upload re-enumerates and they surface as ordinary per-file conflicts.

**Persisted SAF grant.** `MainActivity` now calls
`takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` (guarded by
`runCatching` — the system does not offer a persistable grant for every picked
tree) before `uploadFolder`, so a backgrounded walk keeps read access.

**Deviations / notes (per House Rule "communicating across sessions"):**
- `FolderUploadState` / `FolderUploadOutcome` stay in the `addfileactions` UI
  package (the controller imports them) — moving them was higher churn than
  warranted; the controller is a UI-adjacent state holder, so the import
  direction is acceptable.
- The Phase-2 deviation note ("cancel sets the outcome synchronously") no
  longer holds verbatim: the controller sets it synchronously, the ViewModel
  mirrors it on the next scheduler tick. Functionally equivalent; tests updated.
- No threat-model amendment: 3a is still N invocations of the existing per-file
  upload core (same ciphertext blocks); the Bg variants add no wire shape. The
  Android foreground notification L2-LIVE will raise is UI surface, not an
  A1–A4/A9 surface (§4, unchanged).

**Verification (2026-05-18).** `:drive-shared:desktopTest` 30/30 green (new
`FolderUploadControllerTest` 8/8 — loop behaviour incl. cancel/restart;
`AwaitOrWireCancelTest` 1/1 — cross-process cancel propagation;
`AddFileActionsViewModelFolderUploadTest` 3/3 — VM mirror/delegate contract;
existing enumeration/integration/screen suites unregressed).
`:file-service-proxy:jvmTest` 67/67 (Bg ops reuse impls; no regression).
`:drive-desktop` + `:control-desktop` compile clean. The Phase-L2 wire/cancel
transport itself is already covered by `PhaseL2CancellationTest` /
`InProcessBackgroundCancelTest` (landed) — the Bg ops are `UploadFile` + the
Background trait, so no transport test is duplicated. **Android not locally
compilable** (no SDK in the build env, as with the L2 runtime): the four android-only
Bg op/RO files + factory/parser entries + `MainActivity` mirror the existing
.`UploadSourceFile*` wiring 1:1 — correctness-by-construction. Phase
L2-LIVE landed on `master` 2026-05-19.

> **Correction (2026-05-19): survive-app-close is NOT delivered despite
> L2-LIVE.** A code trace this session found L2-LIVE keeps the *Control*
> process alive but Control's own `onUnbind → ProxyGateway.unregisterAll()
> → ClientRegistry.clear() → sessionScope.cancel()` cancels the in-flight
> per-file `requestJob` (it is parented to the per-client session scope),
> the trigger fires on Back-press / `finish()` / **rotation** (Drive
> unbinds only in `MainActivity.onDestroy()`) not just OOM, and the
> enumerate+per-file loop runs in the *Drive* process which L2-LIVE does
> not keep alive. Canonical analysis + fix options + recommendation:
> [`core-background-processing.md`](core-background-processing.md) §"Phase
> L2-LIVE → Known gap — L2-LIVE is necessary but **not sufficient**".
> **Decision (2026-05-19, with maintainer): option A** — detach Layer-2
> `requestJob`s from `sessionScope`. **Update (2026-05-19): Option A
> landed on `master` (defects 1 & 2) and the defect-3 loop-locality
> anchor — the Drive-side FGS/notification — landed per §5d (i) below.**
> Both pieces are now in; the only remaining 3a item is the
> Android-Studio end-to-end smoke (no SDK in the build env).

### 5d. Drive-side upload notification — copy + interaction _(landed; the defect-3 loop-locality anchor)_

Per the notification-ownership decision recorded in
[`core-background-processing.md`](core-background-processing.md) §"Phase
L2-LIVE → Known gap → Notification ownership": rich, task-specific upload
UX is **Drive-owned, posted from the Drive process** — Control's generic
liveness notification (count / package-label / schema op-id only) never
renders client strings. This Drive-side notification *is also* the
defect-3 liveness anchor: a Drive foreground service for an in-flight
folder upload keeps the `FolderUploadController` loop process alive (the
loop + SAF enumeration run in **Drive**, which L2-LIVE — a *Control*-side
FGS — does not keep alive; §"Known gap" defect 3). One mechanism, two
jobs — not extra scope. **Option A (defects 1 & 2 — detach Layer-2
`requestJob`s from `sessionScope`) landed on `master`; this 5d
work is the remaining defect-3 piece.**

**Data source.** The notification is a thin observer of the existing
`FolderUploadController.state: StateFlow<FolderUploadState?>`
(`UploadProgress(currentFileName, completed, total)` + `outcome`) —
exactly as `AddFileActionsViewModel` observes it. Process-lifetime,
survives the ViewModel, all Drive-authoritative; no new data plumbing,
no proxy involvement.

**Channel.** Low-importance ("Uploads"), silent, vibration disabled;
ongoing while in flight.

**While uploading** (`progress` set, `outcome == null`):

- Line 1 (title): `Uploading file {completed + 1} of {total}` (1-based
  current; mirrors §2.5 "N of M, no percentage"). Before `total` is known
  (enumeration still running, `total == 0`): `Uploading files…`.
- Line 2 (text): the current file's leaf name
  (`progress.currentFileName`, last path segment for v1).
- `ongoing = true`.

**Tap (content intent).** Launch Drive `MainActivity` (singleTop, +
`NEW_TASK`, immutable `PendingIntent`) → the existing Phase-2 ambient
snackbar/bar (`AddFileActionsViewModel` observing
`FolderUploadController.state`) is where progress shows and **Cancel
already lives**. No notification action button in v1 — cancel stays a
single in-app surface (consistent with Phase 2 +
`FolderUploadController.cancel()`). *(Optional later: a "Cancel" action
calling `FolderUploadController.cancel()` directly — note, not v1.)*

**Terminal** (`outcome != null`) — show the summary, demote
(`stopForeground(STOP_FOREGROUND_DETACH)`) so it is dismissible while the
controller's ~5 s `scheduleDismiss` window elapses; the controller then
clears `state` → the observer's clear-edge `stopForeground(REMOVE)` +
`stopSelf()` removes it. Copy:

- `Completed(n)`: `Uploaded {uploaded} files` (append ` · {failed}
  failed` when `failed > 0`).
- `Cancelled(progress)`: `Upload cancelled — {completed} of {total}
  uploaded`.
- `Failed`: `Upload finished — {completed} of {total} uploaded,
  {failed} failed` (no summary on `Failed`, so `failed` is derived as
  `total - completed`, clamped ≥ 0; `0 of 0` when it failed during
  enumeration).

**Isolation invariant.** Everything above is rendered by Drive, in the
Drive process, from Drive-owned `FolderUploadController.state` only —
zero references to Control / the proxy / `ProxyService`. Control's
separate generic FGS notification is unchanged and carries no
filename/progress.

#### 5d (i) — what landed (`feat/drive-folder-upload-notification`, 2026-05-19)

**Decision 1 — FGS-gating: run whenever an upload is *in flight*, not
"while backgrounded".** The spec draft said "promoted to a Drive FGS
while backgrounded". Reliable foreground/background gating is fiddly and
risks the Drive process being OS-reclaimed in the detection gap (the
exact failure this is meant to prevent). **Decided:** the FGS runs
whenever `state != null && outcome == null`, regardless of
foreground/background — robust, mirrors Control's registry-driven
`ForegroundTaskLiveness` pattern (gate on the work, not on lifecycle),
and has no lifecycle-race window. The notification briefly coexisting
with the in-app ambient bar while Drive is foregrounded is accepted for
v1 (the bar is the interactive surface; the notification is the liveness
anchor that also happens to be visible).

**Decision 2 — the commonMain → Android start/stop seam.** Mirrors
Control's `ProxyService` + `ForegroundTaskLiveness` 1:1, adapted for the
fact that Drive's service is *started* (Drive has no always-bound
service like Control's `ProxyService`):

- Android-free edge logic + copy in `:drive-shared` commonMain:
  `folderUploadNotificationContent(state) → FolderUploadNotificationContent`
  (the pure title/text/phase mapper, desktop-unit-tested) and
  `FolderUploadLiveness` (the idle ⇄ in-flight ⇄ terminal state machine
  driving a `FolderUploadNotificationHandle` interface — the analogue of
  `ForegroundTaskLiveness`/`TaskLivenessHandle`). Two flags, not one:
  `active` (FGS promoted) and `shown` (notification posted) differ
  precisely because the terminal summary demotes the FGS but keeps the
  notification visible for ~5 s — a single flag swallowed the final
  clear→remove edge (caught by `FolderUploadLivenessTest`).
- `:drive` (Android-only): `FolderUploadForegroundService` (the
  `dataSync` FGS implementing the handle, observing the same
  Koin-`single` `FolderUploadController` the ViewModel observes) and
  `FolderUploadServiceLauncher` (a tiny always-on observer created in
  `DriveApplication.onCreate`, resolved from the Koin container — the
  same `single` instance, not a test fixture — that
  `startForegroundService`s the service on the idle→in-flight rising
  edge; the service then self-manages promote/refresh/demote/stop).
  Manifest gains `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` /
  `POST_NOTIFICATIONS` + the `dataSync` service element.

**Verification.** `:drive-shared:desktopTest` 42/42 green (was 30; +9
`FolderUploadNotificationTest` covering every §5d copy case incl. the
1-based in-flight form, unknown-total, Completed ±failures, Cancelled,
Failed ±enumeration; +3 `FolderUploadLivenessTest` covering the edge
machine). `:drive-desktop` + `:control-desktop` compile clean (the new
commonMain code is platform-neutral). **Android not locally compilable**
(no SDK in the build env, as with the earlier Android slices): the
`FolderUploadForegroundService`/`FolderUploadServiceLauncher` +
manifest/string changes mirror `:control`'s `ProxyService` /
`ForegroundTaskLiveness` / manifest 1:1 — correctness-by-construction.
An Android-Studio smoke (start a folder upload, background/swipe Drive,
confirm the FGS keeps the loop alive and the notification
copy/tap/terminal behave) is required before this is considered
delivered end-to-end on Android.

---

## 6. Out of Scope

- ~~**Overwrite conflict option.**~~ **Landed 2026-05-15** as an atomic
  `ReplaceFile` op — see §9.
- **Background survival, deferred/metered upload, cross-process cancel.**
  Owned by `core-background-processing.md` (Phase 3 linkage in §5).
- **Streamable `UploadFile` / per-file byte progress.** Same — Layer 2 in that
  proposal; v1's indeterminate indicator is the deliberate stand-in (§2.5).
- **Parallel uploads / concurrency tuning.** The serial path is a deliberate
  v1 constraint; any parallelism is a separate performance proposal and would
  interact with the `PENDING_BLOCKS` unbounded-queue note in
  `core-background-processing.md` §4.4.
- **Renaming/persisting the SAF grant.** Only needed for Phase 3.

---

## 7. Test Plan

- **ViewModel (commonTest, fake repo):** progress emission order; Skip
  excludes a file from `uploaded`; Keep-both produces a suffixed name;
  `applyToAll` suppresses subsequent `onConflict`; a per-file failure is
  counted and does not abort (best-effort); summary correctness.
- **Desktop delegate (jvmTest):** enumerate a temp nested tree; assert
  `entries` order (dirs before their files), relative paths, and
  `rootFolderName`.
- **Android delegate:** instrumented test over a `DocumentFile`-backed temp
  tree if the harness supports it; otherwise a unit test around the
  relative-path/parentPath assembly logic (the SAF walk itself is thin).
- **No proxy/sync tests needed** — v1 adds no operation or sync surface
  (§4). Existing single-file upload coverage already exercises the per-file
  path the loop reuses.

---

## 8. Sequencing

Phase 1 is self-contained and ships the user-visible feature. Phase 2 is
incremental polish on top, no dependencies. Phase 3 splits: **3a**
(survive-app-close) is *runtime-unblocked* — `core-background-processing.md`
Layer 2 `TaskHandle` runtime landed — but still gated on that
proposal's **Phase L2-LIVE** (the per-platform process-liveness wrapper:
Android foreground service; desktop free with the daemon), which is not built;
sequenced in that proposal/track, not here. **3b** (constraint-deferred /
metered) stays gated on that proposal's Layer 3 (Phase 5+).

---

## 9. Overwrite — atomic `ReplaceFile` _(landed 2026-05-15)_

The third conflict choice. Implemented as a new operation, **not** as
`deleteFile` + `uploadFile`.

### 9.1 Why not delete-then-reupload

A file's `dataId` is path-derived, so re-uploading to the same path yields
the **same entity id** as the one just deleted. `deleteFile` goes through the
tombstone system ([`docs/sync/design.md`](../sync/design.md) §2.6): it emits a `DeleteBlock`, registers
a tombstone for that id, and `SyncProcessor` short-circuits incoming blocks
for tombstoned ids; tombstones are retained ~30 days specifically to prevent
resurrection. Delete-then-recreate is therefore "tombstone X, then recreate
X" — on a paired device the outcome depends on HLC ordering and block
arrival: if the `DeleteBlock` applies after the recreate's blocks, the
tombstone eats the overwrite and the file silently vanishes on the peer.
There is also a non-atomic window (crash between delete and upload destroys
data the user asked to *overwrite*, not delete).

### 9.2 What landed

`ReplaceFile` (+ the by-reference `ReplaceSourceFile`) — same surface as
`UploadFile`. The shared core is `UploadFileImplementation.uploadFile(...,
replaceExisting: Boolean = false)`: `replaceExisting = true` skips **only**
the `AlreadyExists` rejection. Everything else is identical — same `dataId`,
overwrite the `FileDatabase` blob, `saveData` the entity (an in-place update;
metadata syncs eagerly via `SyncObserver.onDataSaved`), and one
`emitPropertyBlock` for the new bytes. **No `DeleteFile`, no tombstone.** The
default `false` preserves `UploadFile` / `UploadSourceFile` behaviour
exactly, so the only new code paths are the thin op + RO + the
`replaceExisting = true` delegations.

Wiring (mirrors `UploadFile` exactly): op + DSL in `:file-service`
(both common); `ReplaceFileRo` / `ReplaceSourceFileRo` in
`:file-service-client` (+ both module registrations); `ReplaceFileImplementation`
/ `AndroidFileReplaceImplementation` in `:file-service-proxy` (+ both
factories); schema registered in `FileServiceCommonModule` /
`SourceFileServiceCommonModule`. `FilesRepositoryPlatformDelegate` gains
`replacePlatformFile`; `TenshuFilesRepository` maps
`ConflictResolution.Overwrite` to it (success counts as `uploaded`);
`FolderUploadConflictDialog` gains a third (error-tinted) action.

### 9.3 Why no threat-model amendment

`ReplaceFile` introduces **no new wire shape**: it emits the same
property-block + entity-metadata blocks `UploadFile` already emits, through
the existing `SyncBlockEmitterHolder`. No new block type, no relay change, no
sync-protocol change, and it touches none of assets A1–A4/A9 — so House Rule
#2's amendment trigger does not fire. It is, if anything, *more* conservative
than the delete-then-reupload alternative (which *would* have warranted a
note, because it weaponises tombstone retention). The load-bearing invariant
is recorded here and in the `replaceExisting` KDoc on
`UploadFileImplementation`: **Overwrite MUST be in-place; delete-then-recreate
of a path-derived `dataId` is forbidden** (races the tombstone retention
window). Recorded per the "communicating across sessions" rule.

### 9.4 Tests

`ReplaceFileOperationTest` (`:file-service-proxy` commonTest): replace over
an existing file succeeds (no `AlreadyExistsError`) and the bytes are the
replacement; replace of an absent path upserts. ViewModel
`overwriteResolution_isPlumbedThroughToRepository` asserts the
`ConflictResolution.Overwrite` round-trip + blanket apply-to-all. The Android
`ReplaceFile` impl is a 5-line FD-read delegation to the same shared core the
common test covers; the existing `UploadSourceFile` test already exercises
the FD-read path, so no separate Android replace test was added (noted
deviation from §7's "parallel test" expectation).

`ReplaceFileOperationTest` **passes** (2/2) as part of the full
`:file-service-proxy:jvmTest` 67/67 green run, once the unrelated
`createFileProxy(this)` fixture leak was fixed separately (Status header;
`docs/tech-debt.md`). It confirms the atomic in-place semantics: replace over
an existing file succeeds without `AlreadyExistsError` and the bytes are the
replacement; replace of an absent path upserts.

---

## 10. Known limitations — SAF picker won't grant every folder

Reported in testing: picking the **Download** folder (and a few others) in
the system folder picker shows *"Can't use this folder"* / no "Use this
folder" button. **This is an OS policy, not a Drive bug.** `OpenDocumentTree`
(Android 11+/API 30, scoped storage) refuses to grant a persistable tree URI
for sensitive roots — `DocumentsUI` blocks selection of:

- the **root** of a storage volume (internal storage / "Internal Storage"),
- the **`Download`** root,
- **`Android/data`** and **`Android/obb`**.

Subfolders of these (e.g. a folder the user created inside `Download`, or
`Documents`) are still selectable. There is nothing in Drive's
client-orchestrated walk (§2.1) that can override this — the grant is denied
by the system *before* Drive ever sees a tree URI; `MainActivity`'s
`onResult` simply gets `null`/no grant and the upload no-ops.

The only way to ingest from a blocked root (notably `Download`) is a
**different mechanism** — `MediaStore.Downloads` (the Downloads collection
API) rather than a tree grant. That is a separate feature, not a fix to this
one, and is **out of scope** here; recorded so the next person doesn't chase
it as a folder-upload bug. If "upload my Downloads" becomes a requirement it
needs its own small proposal (MediaStore query → the existing per-file upload
path; the §2.1 client-orchestration shape still holds, only the enumeration
source changes).

## 11. Desktop cloud-backed folders — OneDrive / iCloud "on-demand" files

**Reported in testing (2026-07-23):** on desktop (macOS), a folder upload that
picks a folder living inside **OneDrive** *"instantly gets stuck"* — the progress
sits at "0 of N" forever. The same upload from a **local** folder (e.g.
`~/Downloads`, real bytes on disk) works perfectly. Instrumenting the loop
(`TENSHU-DBG` breadcrumbs) showed enumeration, `checkCapacity`, and folder-create
all succeed; the very **first per-file byte read never returns**.

### 11.1 Root cause — dataless placeholders + a naive read

OneDrive on macOS — like **iCloud Drive, Dropbox, and Google Drive** — exposes files
through **`NSFileProvider` ("Files On-Demand")**. Many files are **dataless
placeholders**: name / size / mtime are on disk, but the *bytes are not* — they live
in the provider's cloud. The instant any process reads the bytes, the kernel **faults
the file in**: it synchronously calls the provider's File Provider extension to
download the file over the network and **blocks the read until the download
completes** — with no timeout.

Our desktop read is a plain, whole-file, on-disk-assuming read
(`DesktopFilesRepositoryPlatformDelegate.readFileBytes` →
`java.io.File(uriString).readBytes()`). So on the first online-only file it parks on
the fault-in; if OneDrive is paused, signed-out, offline, rate-limited, or just slow,
it parks indefinitely. Because the per-file loop (§2.1) is **serial**, one stuck file
freezes the whole job at "0 of N". Enumeration is unaffected —
`walkTopDown` + `File.length()` only touch metadata, which placeholders carry.

**Half OS behavior, half our code.** macOS does not *forbid* reading these files; it
makes the read a blocking, network-backed operation, and we treat it like a local
read — no dataless detection, no timeout, no per-file failure isolation. Not a
regression: the naive read has always been there; cloud-backed source folders just
expose it.

### 11.2 Decision — Option 3, transparent by default (not a silent skip)

The non-obvious truth is that **"upload this OneDrive folder" actually means "download
a ton of files to this Mac first, then re-upload them"** — with the bandwidth, disk,
and time that implies. Hiding that (either by silently downloading, or by silently
skipping the cloud files) is the wrong call. So we take the **transparent** option:

- **Detect** online-only files during the client-side walk.
- **Tell the user up-front**, before any work: e.g. *"12 of these files (3.4 GB) are
  stored only in OneDrive. They'll be downloaded to this Mac first, then uploaded to
  Drive."* — so the download-then-reupload reality is explicit.
- **Default: include them** (download + upload). This is the least-surprising default —
  the user asked to upload the folder, so by default they get the whole folder.
- **Easy opt-out: "Skip files stored only in the cloud"** — a single toggle that lets a
  user who only wanted the already-local files avoid the download entirely. **Off by
  default** (i.e. do *not* skip).
- **Per-file materialize-with-progress + failure isolation:** an online-only file shows
  a *"Downloading from OneDrive…"* state while it faults in; the read runs under a
  cancellable timeout so a genuinely stuck file becomes `FileOutcome.Failed` (already a
  loop outcome, §2.3) and the job moves on — one bad cloud file never freezes the
  whole upload again.

Rejected alternatives: **(1)** a bare `withTimeout` with no UI — stops the hang but
still silent about the download cost, and risks false-failing a large file that
legitimately needs a minute to download; **(2)** silently skipping online-only files —
data-losing surprise (the user thinks the folder uploaded, but cloud files silently
didn't).

### 11.3 Detection — `SF_DATALESS` via `stat` (`MacDatalessProbe`)

macOS marks a dataless placeholder with the BSD file flag **`SF_DATALESS`** (`st_flags`;
`ls -lO` shows it as `dataless`, and such files have `st_blocks == 0` with `st_size > 0`).
Java's `java.io.File` / `java.nio` do **not** surface `st_flags`, so `MacDatalessProbe`
(`:macos-helpers`, JDK-25 FFM but JVM-17 bytecode so `drive-shared-ui` can call it) reads
it natively — **metadata only, no fault-in**. `listFolderEntries` sets `isOnlineOnly` per
`FolderEntry`; everything downstream keys off that.

⚠ **Implemented with `stat`, not `getattrlist` (bug lesson, 2026-07-23).** The first cut
used `getattrlist(ATTR_CMN_FLAGS)`, but read the wrong offset out of its packed attribute
buffer → garbage flags → **every cloud file reported as local** (`isOnlineOnly=false`, no
pre-flight). The unit test only checked the *negative* direction (a real local file → not
dataless), which an always-false probe passes — so it shipped and only surfaced on
a real OneDrive folder (`~/Library/CloudStorage/OneDrive-Personal/…`, `blocks=0`, reads
`IOException: Operation timed out`). Fix: plain `stat`, read `st_flags` at its fixed
`struct stat` offset (116 on arm64/modern macOS). The test now **pins that offset** against
a known `chflags uchg` (`UF_IMMUTABLE`) bit, so a wrong-offset regression fails loudly.

### 11.4 Scope

- **macOS desktop is where this bites today** (Control is the desktop proxy; OneDrive /
  iCloud / Dropbox / Google Drive all ride `NSFileProvider`). The same shape recurs on
  **Windows** when desktop lands there (`FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` / cloud
  placeholder files) — the detection primitive differs, the design does not.
- **Android / iOS** ingest cloud files through their own document pickers, which
  materialize on read via SAF / `UIDocumentPicker`; the transparency copy is worth
  mirroring there eventually, but the hang is desktop-specific (the blocking
  `java.io` read).
- **No threat-model amendment** — this is entirely local read behavior on the client;
  no relay, wire, or crypto surface changes. Bytes still flow app→Control→relay exactly
  as before once a file is materialized.

### 11.5 Phasing

- **P1 — stop the hang (no native code) — SHIPPED:** wrap the per-file read in
  a cancellable timeout so a stuck online-only file fails *that file*
  (`FileOutcome.Failed`) instead of freezing the job. Removes the reported symptom; the
  UX is still terse.
- **P2a — detection + the "Downloading from the cloud…" label — SHIPPED:** the
  `SF_DATALESS` probe (`MacDatalessProbe` in `:macos-helpers`, FFM `getattrlist`,
  metadata-only, fail-safe to `false`) → `isOnlineOnly` on `FolderEntry`, set during the
  desktop enumeration walk → carried to `UploadProgress.isOnlineOnly` → the per-file
  progress label reads *"Downloading from the cloud… <name>"* for an online-only file,
  so the pause while a placeholder materialises no longer reads as a freeze.
- **P2b + P3 — pre-flight prompt + skip opt-out — SHIPPED (one dialog):** after
  enumeration, if the folder holds any online-only files, `uploadFolder` calls a new
  `onCloudPreflight(CloudPreflight(count, bytes))` suspend callback **before** any work.
  The `FolderUploadController` surfaces it as a `FolderUploadState.cloudPreflight` prompt
  — *"N file(s) (X GB) are stored only in the cloud… they'll download first, then
  upload"* — with **Continue** (default = Include), a **"Skip these"** checkbox (P3,
  default off → `CloudPreflightDecision.Skip`, online-only entries counted `Skipped`
  with no read/download), and **Cancel** (`CloudPreflightDecision.Cancel` → dismiss, no
  upload). One `AppDialog`, mirroring the conflict dialog; the callback defaults to
  `Include` so every other caller/test is unchanged.

- **P4 — distinct "couldn't download from the cloud" summary — SHIPPED:** an
  *online-only* file that fails is attributed to `FolderUploadSummary.cloudUnavailable`
  (not `failed`), so the terminal bar reads *"Uploaded 2, 141 couldn't download from the
  cloud"* instead of a bare "141 failed" — it's a provider-delivery problem, not a Drive
  failure. Attribution is by `entry.isOnlineOnly` (platform-agnostic; no coupling to the
  desktop read-exception type). ⚠ Observed real cause: OneDrive's File Provider
  *fast-fails* the on-read fault-in (`cat` on the file → `Operation timed out` in ~0.6 s,
  `st_blocks` stays 0) — so even Include can't retrieve them until OneDrive is healthy /
  the files are pre-downloaded ("Always keep on this device"). Detection ≠ delivery.

P1 removed the "stuck" symptom; P2a landed the detection **and** the live "Downloading…"
transparency; P2b/P3 add the up-front summary + the easy opt-out; P4 makes the cloud-failure
summary honest — the full Option-3 UX.
