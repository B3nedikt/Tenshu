# File Service: Knowledge & Conventions

This service manages the virtual file system, its content, and sharing. It is the platform's
largest and hottest service — treat `File` as a composite that many other services key off
(`:photo-service`, `:timeline-service`, `:inference-service` all store statements *about* a file
rather than fields *on* it).

## 1. Data Model

* **Entities**: `File` and `Folder`. Both root their `DataId`s at `File.ID` — see
  [Identity Rooting](../docs/agent.md#15-identity-rooting).
* **Paths**: always use the `FilePath` value type for path fields; `FileName` for names.
* **Categories**: `FileCategory` defines specialized traits — Image, Video, etc. Resolve one with
  the `fileCategoryForName(name)` / `FileCategoryOf` helpers (⚠ lowercase function, not a type).
* **Media**: `MediaMetadata`, `CapturedPhoto`, and the internal EXIF readers — `ExifTags` /
  `readExifTags` in `ExifTiff.kt`, plus `ExifGps`. ⚠ The filenames and the symbols differ here.
* **Trash**: `TRASH_ROOT` (`/Trash`) in `TrashRoot.kt`. ⚠⚠ Trash is a **realm folder, not app
  private state**, which is why deleting is a *move*: the file keeps its bytes, its `contentId`
  and its history, so restoring is another move rather than a re-upload. Three components must
  agree on the constant — read its KDoc before touching it.

Subpackages, each with its own concern:

| Package         | Holds                                                                        |
|-----------------|------------------------------------------------------------------------------|
| `system/`       | The operations (see §2) plus `FileSystem` and `AlreadyExistsError`.          |
| `share/`        | Link sharing: `Share`, `ShareLink`, `ShareLinkResult`, `RevokeShareLink`.    |
| `shared/`       | `SharedFile` — content inside a shared realm.                                |
| `placement/`    | `Placement` — the per-member mount of a shared file.                         |
| `sync/`         | `SyncPolicy`, `FileSyncPreferences`/`FileSyncSetting`, `LiveContentIds`, `DeclarableContent`/`ContentBackfillMarker`, the orphaned-bytes pair, path-sync overrides. |
| `notifications/`| `FileNotification` and its family, plus `FileAddedBurst`.                    |

⚠⚠ **`SharedFile` and `Placement` are two different links and are easy to conflate.**
`SharedFile` is the content *inside* a realm; `Placement` is the per-member mount of it. Their
read surface is **dedicated operations, not the chain DSL**
([`drive-shared-realm-file-model.md`](../docs/proposals/drive-shared-realm-file-model.md) §6).

## 2. Operations

In `system/` unless noted:

* **Mutation**: `CreateFolder` (emits a `FileAddedNotification`), `DeleteFile`, `MoveFile`,
  `ReplaceFile` / `ReplaceFileBg`, `UploadFile` / `UploadFileBg`.
* **Content**: `GetData`, `GetPreview`, `GetStoredThumbnail`, `HasLocalData`, `EvictFileBytes`,
  `OfflineKeeper`, `BackfillThumbnailClasses` (headless, scheduler-only).
* **Sharing**: `ShareFolderIntoRealm`, `MountSharedRealm`, `ListSharedFolder`, `ReadSharedFile`,
  `ListPlacements`; link sharing lives in `share/`.
* **Photo reads**: `PhotosCapturedBetween`, `PhotosNear` — ⚠ both must exclude trashed files, or
  Timeline and Mia serve a deleted photo.

**`GetPreview` is a per-platform seam with three actuals** — `AndroidGetPreviewImplementation`,
`JvmGetPreviewImplementation` and `IosGetPreviewImplementation` in `:file-service-proxy`.
Image, PDF page and video poster all reduce to an `ImageBitmap`; playback is the one thing that breaks the pattern. See
[`drive-content-preview.md`](../docs/proposals/drive-content-preview.md).

⚠ **The `MOTION` class (`File.ThumbnailMotion`) is not minted through `GetPreview`** — a clip is not
a bitmap, and `MotionThumbnailClassTest` pins the still ladder at three. It goes through the
`MotionThumbnailRenderer` seam in `:file-service-proxy`, whose only actual is Android's; every
other platform serves the class and never mints it. The window/bitrate/scaling rules are pure
Kotlin in `proxy/motion/` so they are tested on the JVM. See
[`photos-video-support.md`](../docs/proposals/photos-video-support.md) §14.

⚠ **Every stored class is minted by one code path** — `ThumbnailClassMinter` in `impl/` (render,
write, `emitFileContent`), which the upload delegates to and the backfill
(`BackfillThumbnailClasses` → `ThumbnailClassBackfill`) reuses. Do not add a second render or a
second emit for a thumbnail: the emit is what declares the blob's content edge, and a
bespoke emit mints the undeclared population the reclamation sweep cannot name. Which classes the
backfill mints is decided by `ThumbnailPreferences.WantedClasses` rows — written by the proxy from the
OnDemand *pull* (`WantedThumbnailClasses`), never from `GetStoredThumbnail`, whose reads `GetPreview`'s
ladder makes on every render — **and** by an app at launch (`declareWantedThumbnailClassesAtLaunch`
in `:file-service-client`), because a class rendered from a warm cache is pulled never. A
render that declines is recorded beside the blob (`thumbnailDeclinePath`) and never asked again
under the same `MINT_FORMAT`. See
[`drive-synced-thumbnails.md`](../docs/proposals/drive-synced-thumbnails.md) §15.9–§15.11.

## 3. Relations

Four, on `FileService.Schema`:

* `DEFAULT_FILE_SYSTEM` · `MODIFICATIONS` (`FileModification`) · `SHARES` (`Share`) ·
  `PLACEMENTS` (`Placement`).

`PLACEMENTS` is read through its dedicated op rather than the chain DSL — the same rationale as
identity's `MEMBERS`.

## 4. Content addressing

File content is **content-addressed**: move, rename and copy are pure metadata operations, and
the generic `DataId`-change → reference-migration primitive lives alongside them
([`file-content-addressing.md`](../docs/proposals/file-content-addressing.md)).

⚠ **Reclamation is not this module's job.** A relay-*derived* refcount is House-Rule-1-impossible;
[`retention-quota-model.md`](../docs/proposals/retention-quota-model.md) owns retention and
[`content-plane-refcount.md`](../docs/proposals/content-plane-refcount.md) the client-declared
content refcount. ⚠⚠ The former's §2 three-tier taxonomy is load-bearing: conflating durable
content with the delivery log deletes user data.

## 5. Dependency on Notification Service

* The `FileService` depends on the `NotificationService` to broadcast state changes (additions,
  deletions).
* `fileServiceCommonModule` explicitly `include`s `notificationServiceCommonModule`.
* ⚠ The `FileNotification` subtypes and their DTOs live **here** (`:file-service` /
  `:file-service-common`), not in the notification service — see
  [`notification-agent.md`](../notification-service/notification-agent.md) §1.

## 6. Merging

`FolderMerger` (`:file-service-proxy`) is the platform's first `SemanticMerger`, and the one
that surfaced the lazy-vs-materialised data seam. That seam is **settled**:
[ADR 0024](../docs/adr/0024-merger-field-resolution.md) — a merger reads a collection field
with `resolveListField`, never `getField`. `FolderMerger` is one of its three reference
shapes; see also [`sync-agent.md`](../sync-service/sync-agent.md).
