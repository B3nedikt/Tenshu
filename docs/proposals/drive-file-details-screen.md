# Drive — FileDetailsScreen ("Details & Activity")

**Status:** Phases 1–3 implemented + merged to master (2026-05-24): Phase 1 (nav
+ entry-point wiring + scaffold), the file-service primitives (createdAt +
modifications accessor), Phase 2 (metadata), Phase 3 (activity list
— **without device names**, see §6). Phase 4 (access)
stays deferred to the sharing proposal. See §6.
Author: Benedikt + Claude (post-file-history discussion, 2026-05-22).

**Scope:** `:drive-shared` UI —
`ui/navigation/Destination.kt` (new `FileDetails(path)` destination),
new `ui/screens/filedetails/` package (`FileDetailsScreen`,
`FileDetailsViewModel`, `FileDetailsUiState`),
`ui/common/details/FileActionsViewModel.kt` (`onInfoClicked` → navigate),
`ui/common/details/FileActionsBottomSheet.kt` (unchanged — the
"Details & Activity" row already exists),
`ui/common/preview/ImagePreviewScreen.kt` (wire the `MoreVert` three-dots to
open the actions sheet),
`ui/App.kt` (register the destination + render the actions sheet over the
preview), `data/FilesRepository.kt` (new `fileDetails(path)` read).

**Depends on:** [`file-service-history.md`](file-service-history.md) for the
`history` relation + the `size` / `createdAt` / `lastModifiedAt` metadata, and
transitively on [`core-chain-sort.md`](core-chain-sort.md). The Access section
depends on the (not-yet-written) sharing proposal. This is a **consumer**
proposal — it ships the screen once the data exists.

---

## 1. Motivation

The file-history work lands a per-file modification log and clean
size/created/modified metadata, but nothing in Drive renders it. Today:

- `FileActionsBottomSheet` already has a **"Details & Activity"** row
  (`ui/common/details/FileActionsBottomSheet.kt:152`), but it routes to
  `FileActionsViewModel.onInfoClicked()` which is `TODO("Not yet implemented")`
  (`FileActionsViewModel.kt:56`).
- `ImagePreviewScreen`'s top bar has a **`MoreVert` three-dots** icon that is a
  no-op `Modifier.clickable { }` (`ImagePreviewScreen.kt:88–92`).

So both entry points already exist in the UI; neither is wired. This proposal
fills in the destination they should lead to: a **FileDetailsScreen** showing
the file's preview, metadata, (future) access, and activity history.

---

## 2. Entry points & navigation

There are two three-dots affordances, and they should converge on one menu:

1. **File row three-dots** (Home / Files / Folder lists) → opens
   `FileActionsBottomSheet` (already wired, gated on
   `uiState.showFileActionsWithPath` via
   `LocalFileActionsViewModelFactory.current(path)`) → tapping
   **"Details & Activity"** navigates to `Destination.FileDetails(path)`.
2. **ImagePreviewScreen three-dots** (`MoreVert`) → opens the **same**
   `FileActionsBottomSheet` (so all file actions — download, share, move,
   rename, delete, details — are reachable from the preview, consistent with
   the list rows) → **"Details & Activity"** → `Destination.FileDetails(path)`.

**Interpretation note.** The request was "this same bottom sheet should also
get opened through the three dots button in the ImagePreviewScreen." Read as:
the *actions sheet* (the three-dots menu) opens from the preview too; the
**FileDetailsScreen itself is a full-screen destination**, not a bottom sheet
— it matches the existing `FilePreview` / `Folder` full-screen destinations and
the "screen" in its name. (Alternative — render details as an expanding bottom
sheet — is noted in §7.)

### 2.1 Wiring

- `FileActionsViewModel.onInfoClicked()` stops being a `TODO` and instead
  surfaces a navigation intent. Following the existing pattern, the sheet's
  host (HomeScreen / FilesScreen / FolderScreen, and now the preview) passes a
  callback; `FileActionsBottomSheet` already threads `onActionCompleted` +
  `onRenameRequested` — add an `onDetailsRequested: (path) -> Unit` in the same
  shape, and the host does `backStack.add(Destination.FileDetails(path))`.
- `Destination.FileDetails(path: String)` — new `@Serializable data class`
  alongside `FilePreview` / `Folder` in `Destination.kt`.
- `App.kt` gains an `entry<Destination.FileDetails> { … }` rendering
  `FileDetailsScreen`, mirroring the `entry<Destination.FilePreview>` block.
- `ImagePreviewScreen` gains a `showFileActions: () -> Unit` callback; the
  `MoreVert` `clickable { }` calls it. App.kt renders the shared
  `FileActionsBottomSheet` over the preview the same way the list screens do
  (lift the `showFileActionsWithPath` state into the preview's host, or give
  the preview its own small state — decide in Phase 1).

---

## 3. Screen layout

Top-to-bottom sections:

1. **Preview thumbnail** — the file's image, **smaller than
   `ImagePreviewScreen`** (a header thumbnail, not a full-bleed zoomable
   image). Reuses the existing preview byte path
   (`FilesRepository.getPreview(path, w, h)`); for non-image files, the
   category icon (`FileUiModel.previewIcon`).
2. **Metadata** — labelled rows:
   - **Size** — from the `size` property (file bytes / folder aggregate).
   - **Created** — from the `createdAt` field.
   - **Last modified** — from the `lastModifiedAt` property.
   (All three are the §3.3 file-service-history additions.)
3. **Who has access** — **deferred**. Placeholder section, hidden until the
   sharing proposal lands. When it does, this renders realm/member access.
4. **Activity** — the file history: a reverse-chronological list of
   `FileModification` entries. Each row renders per subtype:
   - `CreatedFileModification` → "Created"
   - `ContentChangedFileModification` → "Modified"
   - `RenamedFileModification` → "Renamed *old* → *new*"
   - `MovedFileModification` → "Moved to *newParent*"
   …each with a relative timestamp and the **authoring device name** (join
   `FileModification.deviceId` → `Device` composite for the display name —
   "on iPhone").

---

## 4. Data plumbing

A new repository read surfaces everything the screen needs in one stream:

```kotlin
// data/FilesRepository.kt
suspend fun fileDetails(path: String): Flow<FileDetails>
```

```kotlin
// new UI-layer model
data class FileDetails(
    val file: FileUiModel,                 // preview + name + icon
    val size: Long,
    val createdAt: Instant,
    val lastModifiedAt: Instant,
    val activity: List<FileActivityUiModel>,   // mapped from history
    // access: deferred
)

data class FileActivityUiModel(
    val kind: Kind,                        // Created / Modified / Renamed / Moved
    val timestamp: Instant,
    val deviceName: String,
    val detail: String?,                   // "old → new" for rename, target for move
)
```

The repository builds `FileDetails` from a chain over the file-service `File`
entity: project the metadata properties + the `History` relation, map each
`FileModification` subtype to a `FileActivityUiModel`. `activity` is the
history sorted reverse-chronologically (`sortByDescending { it.timestamp }` —
the same chain step from `core-chain-sort.md`).

Today `FileModel` / `FileUiModel` carry only path/name/subLine/icon/preview —
none of the metadata — so the metadata fields are new plumbing, not a remap of
existing UI models.

---

## 5. Dependencies

| Needs                                              | From                                            |
|----------------------------------------------------|-------------------------------------------------|
| `history` relation + `FileModification` subtypes   | [`file-service-history.md`](file-service-history.md) |
| `size` / `createdAt` / `lastModifiedAt`            | file-service-history §3.3                        |
| `sortBy` chain step (activity ordering)            | [`core-chain-sort.md`](core-chain-sort.md)       |
| Device display name (`deviceId` → name)            | landed device-management ([ADR 0007](../adr/0007-sync-device-management.md)) |
| "Who has access" section                           | sharing proposal (not yet written) — deferred    |

The screen is buildable as soon as file-history + chain-sort land. The Access
section is additive and ships later without reworking the screen.

---

## 6. Phases

1. **Navigation + entry points (no data) — ✅ DONE (2026-05-24).**
   `Destination.FileDetails(path)`; `FileActionsBottomSheet` gains an
   `onDetailsRequested` callback (the `onInfoClicked` `TODO` is removed and the
   row now routes the path out to the host); each list-screen host + the
   preview thread a `showFileDetails` navigation lambda; `ImagePreviewScreen`'s
   `MoreVert` gains a `showFileActions` callback and App.kt hosts the shared
   actions sheet (with rename dialog) over the preview using entry-local state
   (resolves §7.2); App.kt registers `entry<Destination.FileDetails>` +
   `FileDetailsViewModel` is added to `viewModelsModule`. The stub
   `FileDetailsScreen` renders the header (category icon + name) and placeholder
   **Details** (Size / Created / Last modified = "—") + **Activity** ("No
   activity yet") sections. Tests: `FileActionsBottomSheetTest` (Details routes
   the path) + `FileDetailsScreenTest` (stub renders). The screen opens for
   **both files and folders** (resolves §7.4) — the actions sheet already
   appears on folder rows, so no gating; folder-specific data rendering is a
   Phase 2/3 concern.
2. **Metadata section — ✅ DONE (2026-05-24).** `File.createdAt` was re-added as
   an op-written value property (file-service PR, file-service-history.md §3.3).
   `FilesRepository.fileDetails(path): Flow<FileDetailsModel?>` projects
   `Category` / `Size` / `CreatedAt` / `LastModifiedAt` off the parent listing
   (`navigateTo(parent)` + match by path) and `FileMapper.mapDetails` formats
   them (reusing the listing's `formatFileSize` + `TimestampUtil`);
   `FileDetailsViewModel` streams it into `size` / `created` / `lastModified`
   (dash placeholder until the first emit / for pre-`createdAt` files). Works for
   files and folders. Tests: `FileDetailsScreenTest` (renders the metadata) +
   `FileDetailsIntegrationTest` (real chain; a fresh entry has
   `createdAt == lastModifiedAt`).
3. **Activity section — ✅ DONE (2026-05-24; device names 2026-05-25).**
   `FilesRepository.fileActivity(path): Flow<List<FileActivity>>` streams
   `modificationsForFile(path)` (file-service-history.md §5.1, the chain-filter
   form — not the §3.2 reverse-join relation, still blocked on reverse-join chain
   support §9.4), and `FileMapper.mapActivity` maps each `FileModification` to a
   row: kind (Created / Modified / Renamed / Moved, from the type id) + detail
   (`"old → new"` / `"to /newParent"`) + timestamp, sorted newest-first
   client-side (history is `RETENTION_CAP`-bounded). `FileDetailsViewModel`
   collects it on a second stream; the screen renders rows or "No activity yet".
   Tests: `FileDetailsScreenTest` (rows render) + `FileDetailsIntegrationTest`
   (real chain — Created end-to-end + Renamed with detail; the harness now wires a
   device id so the emit hooks fire).
   **Device names — ✅ DONE (2026-05-25).** The north star landed: a
   `FileModification.Device` *reference value property* resolves the authoring
   `Device` from `deviceId` via `select` — the platform primitive in
   [`core-reference-property.md`](core-reference-property.md). `:drive-shared`
   opts into identity-service (`implementation`, non-transitive), the activity
   chain projects `relations(FileModification.Device)`, and a row renders
   "on <name>", falling back to the raw `deviceId` when the device is unknown
   locally.
4. **Access section:** when the sharing proposal lands. Out of scope here.

Phase 1 landed as a pure-UI PR ahead of the file-service work; the file-service
primitives (createdAt + the modifications accessor) landed as their own PR;
Phases 2–3 consume them. The track is complete bar the access section (deferred
to the sharing proposal); device names landed 2026-05-25 via
[`core-reference-property.md`](core-reference-property.md).

---

## 7. Open questions

1. **Full screen vs. bottom sheet.** ✅ **Resolved: full-screen destination**
   (Phase 1) — matches the name + the existing `FilePreview` / `Folder` nav.
2. **ImagePreview actions-sheet state ownership.** ✅ **Resolved: the preview
   entry owns its own minimal state** (Phase 1). `entry<Destination.FilePreview>`
   in App.kt holds entry-local `actionsForPath` / `renameForPath` and renders
   the shared `FileActionsBottomSheet` + `RenameFileDialog` — the preview has no
   other use for the state, so it isn't lifted into a VM.
3. **Thumbnail size / aspect.** Concrete dp + crop behaviour for the header
   thumbnail — design round. Phase 1 stub uses a 96.dp category icon.
4. **Folder details.** ✅ **Resolved: opens for both files and folders**
   (Phase 1). The actions sheet already appears on folder rows, so "Details &
   Activity" navigates for folders too; folder-specific data (size = child
   count, activity = folder modifications) lands with Phases 2–3.
5. **Relative vs absolute timestamps** in the Activity list, and grouping
   ("Today / Yesterday / earlier") — design polish, not v1-blocking.

---

## 8. Non-goals

- **Editing metadata** from this screen — it's read-only (rename/move/delete
  stay in the actions sheet).
- **Version restore** from an activity entry — needs content-version retention
  (blob-channel conversation), not this screen.
- **Per-modification diffs** — the activity list shows *what kind* of change,
  not a content diff.
- **The history data model itself** — owned by
  [`file-service-history.md`](file-service-history.md); this proposal only
  renders it.
