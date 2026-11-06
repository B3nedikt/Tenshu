# Synced Folders — automatic per-folder upload (OneDrive / Google-Photos-style)

- **Status:** **Phase 1 MERGED** (in-process auto-backup, Android +
  Desktop). commonMain seams
  (`MediaSourceProvider`, `BackupEnvironment`, `BackupPreferences`,
  `SyncedFolderController`) + desktop actuals are desktop-unit-tested
  (`:drive-shared:desktopTest` green); the gallery `MediaStore` actual + the
  Android FGS are correctness-by-construction (no SDK in the build env) pending
  Studio / on-device verification. v1 covers images by default + a video opt-in
  toggle (open-Q4). **Phase 2 is re-scoped — see
  [`drive-synced-folders-phase2.md`](drive-synced-folders-phase2.md).** ⚠ §§3.3,
  3.4, 4.4 below describe a **Control-owned scheduler with a Control→app reverse
  trigger**; that framing is **superseded** — the maintainer decision
  ([`milestones.md`](../milestones.md) "Background-service story") is
  *capture app-side, policy Control-side*, which **kills the reverse trigger**.
  Read those sections as historical context; the Phase-2 doc is authoritative.
  Phases 3–4 (SAF document-tree source; iOS) remain. First proposal for the "folder-watching"
  milestone dependency named under *Photos* in
  [`milestones.md`](../milestones.md) ("folder-watching for new photos must
  work without the user opening Photos"; "Per-platform gallery integration via
  the platform-delegate pattern"). Supersedes the throwaway "upload my
  Downloads needs its own small proposal" note in
  [`drive-folder-upload.md`](drive-folder-upload.md) §10.
- **Date:** 2026-06-08
- **Owner:** Benedikt
- **Related:** [`drive-folder-upload.md`](drive-folder-upload.md) (the per-file
  upload path this reuses) · [`core-background-processing.md`](core-background-processing.md)
  (Layer 2/3, `HeadlessSafe`, `ScheduleService`, WorkManager) ·
  [`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md) (`ConstraintSpec`,
  `NetworkStateProvider`, the preference→constraint resolver) ·
  [`core-preference-service.md`](core-preference-service.md) (device-scoped
  settings) · [`drive-connectivity-service.md`](drive-connectivity-service.md)
  (`StorageStatus` seam, the upload-gate signal) · ADR 0019 (storage gating).

> This proposal designs the **control plane** of automatic upload — what the
> user selects, how change is detected, when it's allowed to run, and where the
> bytes land. It deliberately reuses the *data plane* (per-file encrypt +
> upload) already shipped by `drive-folder-upload.md` unchanged. The genuinely
> new surface is a per-app **ingest-source seam** (an app-resident enumerator)
> and a **Control-owned generic scheduler** that triggers it; everything
> downstream of "here is a file to upload" is existing code.
>
> **Architecture decided 2026-06-08 (with the maintainer):** scheduling is
> **Control-owned and generic**, not Drive-specific. One scheduler, one
> constraint/Wi-Fi/charging/storage policy, shared across every app that ingests
> device data — Drive-photos now, Timeline location capture and Health vitals
> later (both named in [`milestones.md`](../milestones.md) as needing the same
> "runs without the app open" story). Each app contributes a *source adapter*
> that runs in its **own** process (enumeration + opening FDs are per-app OS
> permissions and cannot move to Control) and its **own** settings UI; the
> engine, constraints, and storage gate are shared. See §3.3.

---

## 1. Motivation

The user picks a folder once ("my camera roll", "the WhatsApp Images folder")
and from then on anything that lands in it is uploaded to their realm
automatically, with no further interaction — the table-stakes behaviour of
Google Photos backup, OneDrive camera upload, and Dropbox camera upload.
Tenshu has every downstream piece (per-file E2E upload, conflict handling,
deferred/constrained scheduling) but nothing that (a) lets the user *choose*
source folders, (b) *notices* new content, or (c) *triggers* an upload without
the app being open. This proposal adds exactly those three.

It is also the unblocker the *Photos* milestone names: Photos' ingestion is
this feature plus EXIF indexing. Building it Drive-side first means Photos
inherits a working ingestion pipeline rather than reinventing one.

---

## 2. How comparable apps do it (and what we borrow)

| App | Source selection UX | Default | Change detection | Gating | Where it runs |
|---|---|---|---|---|---|
| **Google Photos** | Per-folder toggle list with thumbnails + counts; a master *Back up all device folders* switch | Backup **opt-in**; once on, camera on, other folders user-toggled; "all device folders" optional | `MediaStore` content observer + WorkManager | Wi-Fi-only, charging, per-folder | Background worker, survives close |
| **OneDrive** | Single *Camera upload* toggle (no per-folder granularity) | Opt-in | `MediaStore` | Wi-Fi-only, *upload only while charging* | Background; **stops if app is swiped away** (a known weakness) |
| **Dropbox** | Single *Camera uploads* toggle | Opt-in | `MediaStore` | Wi-Fi-only, charging | Background worker |
| **Syncthing** | Folder-by-folder by absolute path | Opt-in per folder | `inotify` / `FileObserver` + periodic scan | Generic | Resident service |

What we take: **Google Photos' per-folder toggle list** (it matches the user's
"all image folders selected by default, unselect individually" plan exactly),
**OneDrive's Wi-Fi-only + charging-only** policy switches, and the
universal **`MediaStore` content-observer + WorkManager** change-detection
mechanism on Android. What we explicitly do *better* than OneDrive: survive the
app being closed/swiped (we already solved this for folder-upload — §5d Drive
foreground service + WorkManager content-URI triggers; OneDrive's "must be
running" caveat is the failure mode we avoid).

---

## 3. Design decisions

Locked for the rest of the doc.

### 3.1 The folder selection is device-scoped, not synced

A `MediaStore` bucket id, a SAF tree URI, and a desktop absolute path are all
**device-local identities** — "bucket 42" on the phone is meaningless on the
laptop. So the watched-folder set is a **`PreferenceScope.DEVICE`** preference,
read/written via the existing `preferenceService.readForDevice(pref, deviceId)`
/ `setForDevice(...)` path — *exactly* the shape `DrivePreferences.OfflineFiles`
(the offline-keeper selective-sync list) already uses in
`TenshuOfflinePreferences`. No new preference machinery; one new
`DrivePreferences` entry. (The *uploaded content* is synced as normal; only the
*selection policy* is device-local.)

**Accepted downsides** (all mild, all shared with Google Photos): the selection
**doesn't roam** — a new device re-picks its folders; the per-bucket **cursor is
lost on reinstall** (→ a re-scan whose re-uploads the dedup backstop §3.5
absorbs at bandwidth cost); list-valued device prefs ride as `TByteArray` blobs
(the preference-service noted limit), so the selection + cursor map are blobs,
not queryable rows. One subtlety in our favour: `DeviceSetting`s are
device-*scoped* but still **synced**, so another device can read this device's
selected-folder *names* — same trust domain (fine), and it is exactly what a
future cross-device "manage all my devices' backup" view would read, for free.

### 3.2 Sources are per-app adapters; Photos brings the gallery, Files brings SAF

A source adapter answers two questions for one *kind* of device data: "what are
the candidate folders?" and "what's new in them?". The **engine doesn't care**
what kind — so photo-sync and file-sync are **different adapters with different
UIs sharing one scheduler**, not one generic "synced folders" screen straining
to cover both (the maintainer's point: documents make sense in Files, not in a
photo backup; the gallery makes sense in Photos, not in Files). Concretely:

- **Gallery source (Photos / v1):** Android `MediaStore` image **buckets**
  (`DCIM/Camera`, `Screenshots`, `WhatsApp Images`, …). They *are* the device's
  photo folders, enumerable with just `READ_MEDIA_IMAGES` and **no** SAF tree
  grant, sidestepping the [`drive-folder-upload.md`](drive-folder-upload.md) §10
  "can't grant the Download root" limitation entirely. The user's "all image
  folders, default on" maps onto this directly, and it is precisely the *Photos*
  milestone's "Android `MediaStore`" integration — v1 builds the thing Photos
  needs anyway. v1's settings screen (§5) is therefore the seed of **Photos'
  Settings tab**.
- **Document-tree source (Files / later):** persisted SAF tree grants +
  `FileObserver`/periodic re-scan (no `MediaStore`), surfacing arbitrary folders
  including non-media (the WhatsApp *Documents* case, a `Download` subfolder of
  PDFs). A separate adapter, a separate Files-side surface, the **same** engine.

The user's headline example — *WhatsApp Images* auto-upload — is fully covered
by the v1 gallery source (those images live in `MediaStore`). WhatsApp
*documents* are the document-tree source, a later phase.

### 3.3 Scheduling is Control-owned and generic; enumeration stays app-side

The decisive permission fact: **enumerating a source and opening its file FDs
are per-app OS permissions** (gallery `READ_MEDIA_IMAGES`; a SAF tree grant) and
cannot move to Control — Control can't read the gallery any more than it could
enumerate a SAF tree ([`drive-folder-upload.md`](drive-folder-upload.md) §2.1).
But *everything after an opened FD already is Control's* — it encrypts the
chunks and `PUT`s them to the content plane (ADR 0017). So the split is:

- **Control owns the engine** (generic, one of a kind for the whole platform):
  `ScheduleService` + `ConstraintSpec` + the storage gate + an **ingest-source
  registry**. Each app registers a source descriptor (`id`, `ConstraintSpec`,
  enabled-state). One scheduler, one Wi-Fi/charging/storage policy, applied
  uniformly to Drive-photos **and** the future Timeline location capture and
  Health vitals stream (both named in [`milestones.md`](../milestones.md) as
  needing the identical "runs without the app open" background story). This is
  the bgproc Layer-3 machinery ([`core-background-processing.md`](core-background-processing.md)
  §3.2 — `ScheduleService`/`ConstraintSpec`, landed Phase 5) used as intended.
- **The app owns the source adapter**, running in its own process. On a trigger
  it enumerates, diffs against its cursor, opens FDs, and submits each file
  through the **existing** per-file `uploadPlatformFileCancellable` →
  `uploadSourceFileBackground` (`*Bg` Layer-2 ops) path — byte-for-byte the
  data plane folder-upload already ships (*N invocations of the existing op*, no
  new upload/sync/relay surface, §6).

**The one genuinely new IPC surface — and it is narrow.** Bytes still flow
**app→Control** (unchanged). What's new is a **one-way trigger Control→app**
("ingest now, here are your constraints") plus the ability to **wake a dead app
process** when Control's scheduled tick fires while the app is swiped away.
Today's IPC is strictly app→Control (app = client, Control = proxy/server), so
the reverse *trigger* channel + cross-app process-start is new — but it carries
a tick, **not** a data stream, which is far smaller than a reverse byte plane
and is the same mechanism Timeline/Health will need, so it's worth building once
generically. See §4.4.

**One model divergence worth recording:** an ingest source is **not** a pure
Control-resident `HeadlessSafe` op (the bgproc op model assumes ops run *in* the
proxy). It is an **app-resident callback Control triggers**, precisely because
enumeration can't leave the app's permission context. The `HeadlessSafe` /
§6.4 no-user-presence invariant still applies (uploads run headless, no UI), and
the registry/constraints/storage-gate are Control's; only the *enumerate+open*
step is delegated back to the app.

### 3.4 Two trigger tiers, mirroring the bgproc layer model

- **App alive (Layer 2) — no reverse-IPC needed, ships first.** An in-process
  `MediaStore` `ContentObserver` (Android) / `WatchService` (desktop) in the app
  process drives the ingest loop immediately, reusing the Drive
  foreground-service liveness from
  [`drive-folder-upload.md`](drive-folder-upload.md) §5d so an in-flight backup
  survives the user leaving the app. This tier needs nothing from Control beyond
  the existing per-file upload path, so it is **Phase 1** and unblocked today.
- **App closed / after reboot (Layer 3, Android) — the Control-owned engine.**
  Control's `PlatformScheduler` registers a WorkManager `PeriodicWorkRequest`
  **plus** the content-URI trigger declared in the gallery source's descriptor
  (`addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)`) —
  registering a content-URI trigger does **not** require `READ_MEDIA_IMAGES`
  (the system observes; only *reading* needs the grant), so Control can own the
  trigger without the gallery permission. On fire, Control's worker (a) re-checks
  constraints + storage gate, then (b) sends the **one-way "ingest now" trigger**
  to the app, **starting the app's ingest service if its process is dead**; the
  app enumerates with its own permission and uploads via the normal app→Control
  path. **Desktop** has no killed-process wake; the resident daemon
  ([`core-background-processing.md`](core-background-processing.md) §5.3) keeps
  the in-process `WatchService` loop alive (desktop is assumed always-on), so
  desktop needs no reverse trigger. iOS = `PHPhotoLibrary` observer, deferred.

### 3.5 Idempotency: per-bucket cursor + destination-existence backstop

To avoid re-uploading, each watched bucket keeps a **cursor** (highest
`MediaStore` `_ID` / `date_added` seen, or desktop mtime watermark) as
device-local bookkeeping. The loop uploads only items past the cursor, then
advances it. Backstop for the cursor's blind spots (edited-in-place,
clock skew, restored backup): the per-file destination path is content-derived,
so a file already present at its destination short-circuits to an **auto-Skip**
(the same `AlreadyExistsError` the folder-upload loop already handles — here it
is resolved silently as Skip, never a dialog, because there is no user present).
The cursor is **not** synced (it is pure local progress); re-deriving it from
realm contents is the principled-but-expensive alternative, rejected for v1.

**As-built note (2026-07-02) — destination-chain ensure cadence.** The
`/Backup/<device>/<bucket>` chain is ensured (idempotent `createFolder` per
segment, `AlreadyExists` swallowed) **once per bucket per drain**
(`FilesRepository.ensureBackupDestination`, called from the drain loop), not
per uploaded item as originally built — per-item re-ensuring cost three
expected-`AlreadyExists` IPC round trips per file and spammed the client-side
IPC failure log. Not memoized for the whole session either, deliberately:
`UploadFile` does **not** check that ancestor folders exist, so a chain deleted
mid-session (any device) would silently orphan every subsequent backup upload
at an unlisted path — re-ensuring every drain keeps the self-heal. The residual
ensure→upload race window (chain deleted mid-drain) is the same one the
interactive folder-upload flow already accepts.

### 3.6 Storage availability is a hard gate

Per the user's requirement and ADR 0019: auto-upload **must not run** unless
`storageService.status().hasStorage == true` (a bound storage provider /
subscription). Checked in two places: the **settings screen** (no storage →
the feature renders inert with a "Get storage to enable backup" CTA, never a
live toggle that silently does nothing), and the **worker/loop** (re-checks
before each drain; no capacity → defer, don't spin). If `usedBytes >=
limitBytes` the loop pauses and surfaces an "out of space" state rather than
hammering failing uploads. This reuses the `StorageStatus` reactive relation
verbatim — no new signal.

### 3.7 Feature is opt-in; within it, image buckets default-on

Auto-upload moves user content off-device automatically, so the **master
switch defaults OFF** — the user explicitly enables "Automatically back up
folders" (matches Google Photos / OneDrive / Dropbox, all opt-in, and is the
consent-correct default for a privacy product). *Once enabled*, all detected
image buckets default **selected**, and a "Back up new folders automatically"
toggle (Google Photos parity) controls whether buckets appearing later are
auto-included. This honours the user's "all image folders selected by default,
unselect individually" plan while keeping the *first* step a deliberate opt-in.
The reasoning is recorded here because the default is a consent decision, not a
UI preference.

### 3.8 Network / charging policy reuses the `ConstraintSpec` vocabulary

"Back up over Wi-Fi only" and "Only while charging" are two new **device-scoped**
`DrivePreferences` that resolve into a `ConstraintSpec` (`network = UNMETERED`,
`power = CHARGING`) — the same resolver shape as `backgroundSyncConstraints()`
in `:control-shared`. On Android they map natively onto the WorkManager
`Constraints` of the §3.4 worker (`setRequiredNetworkType(UNMETERED)`,
`setRequiresCharging(true)`); while the app is alive they are checked in-process
via the existing `NetworkStateProvider` seam before each upload, exactly like
`ConstraintSyncGate`. No new constraint primitives.

### 3.9 Deleting a backup destination (2026-08-13)

Reported on the maintainer's own devices: a backup destination deleted on the iPhone
still listed on the Android phone, **empty**. Diagnosing it turned up three separate
things, and only the third is the one the report was actually about.

**What was already correct.** `Folder.FILES` is a declared relation, and the generic
`RelationMaintainer.onDataDeleted` unlinks a deleted child from its parent's array with no
origin filter — so a peer applying a tombstone does drop the row from its listing. The
"dangling parent reference" this looked like from the outside does not exist. ⚠ Worth
knowing before fixing anything here: the obvious fix is a fix to something that works.

**Defect 1 — a union merge has no removal semantics (fixed).** `FolderMerger` merges a
folder's children as a union by path. That can express "both sides contribute members"; it
cannot express "this one was deleted", because a member missing from one side is
indistinguishable from one the other side has not learned about yet. So a device that
writes to a folder *before* it learns a child was deleted emits a legitimately-newer block
that still lists the child, and the union puts it back — pointing at an entity every
device has already deleted. **That is the empty folder in the report**: a row that lists
and cannot be opened. Fixed by `SemanticMerger.purgeDeadMembers`, a defaulted hook the
ingest path calls with its `TombstoneRegistry`.

⚠ **Dead means tombstoned *and* absent from the store, never merely tombstoned.** A
tombstone does not retire an id — a higher-HLC create legitimately resurrects it, and this
very feature does that every time `ensureBackupDestination` rebuilds a chain a peer
deleted. Purging on the tombstone alone hides live rows instead of dead ones;
`FolderTombstonedMemberPurgeTest` pins both halves, and the second test is the one that
fails if someone "simplifies" the predicate.

**Defect 2 — one tombstone per delete *operation* (fixed).** `DeleteFileImplementation`
emits a single tombstone for the delete's root and cascades to descendants only in its own
store. A peer therefore deleted the folder row and nothing beneath it: every descendant
entity, and every descendant's plaintext, stayed on disk forever, reachable from nothing —
for a backup destination, an entire photo bucket on a device whose user deleted it. Fixed
by `DeletedFolderSubtreeReaper`, a `file-service-proxy` observer that reproduces the
originator's cascade wherever a folder row dies. It reads the `FILES` **array object**
rather than the entity's field (by observer time the entity is gone, the array outlives
it), which is also what keeps it inert on the local path — `DeleteFileImplementation`
deletes the array *first*, so the read finds nothing and the cascade is not repeated.

⚠ Cost note, unmeasured: the cascade is symmetric with what the originating device already
does inside `DeleteFile`, but on a peer it runs **inside the sync tick** rather than inside
an op. Deleting a bucket with thousands of photos is thousands of `deleteData` calls, each
fanning out to four observers. Nobody has timed it against a real bucket, and iOS is where
it would bite first (`ios-drive-parity.md` — the launch watchdog). If it needs bounding,
the fix is to chunk the cascade, not to skip it.

**Defect 3 — none of this is what the user expected anyway (dialog shipped).** Both fixes
above make the delete *correct*; they do not make it *predictable*. Synced Folders is
one-way ingest (§3.3), so the honest model is Google Photos', not OneDrive's: deleting the
cloud copy never touches the original and never re-uploads — the cursor is a monotonic
watermark, never re-derived from realm contents (§3.5). Users get **both** halves wrong,
in opposite directions: they fear the delete will reach back and wipe the phone's gallery,
and they do not expect the folder to keep filling afterwards. `BackupDeleteDialog` says
both, naming the source device, and is raised only for `/Backup/<device>` and
`/Backup/<device>/<bucket>` — never for a file inside one, because warning on every photo
trains the user to dismiss the warning that matters.

⚠ **There is deliberately no "and stop backing up" action, and that is a data limitation,
not a design choice.** `IngestSource` is synced and carries `deviceId` / `displayName` /
`sourceKind` / `watchedPaths`, but on mobile `watchedPaths` holds `MediaStore` bucket
**ids** while the destination folder is named from the bucket's **display name**, and the
per-bucket selection never leaves the device that made it (a local preference blob, §3.5).
So **no synced row joins a backup folder to the bucket that feeds it** — the deleting
device can name the folder but cannot switch it off. The dialog routes to *Synced Folders*
rather than offering a button that could only guess.

The follow-on slice, if the two-option dialog is wanted: put the destination path on the
synced source row (or mint a per-bucket row, the same move `fit-data-sources-restructure.md`
DS-1 made for imports), which owes a service version bump + `SchemaMigration` under House
Rule 9. ⚠ Not yet scheduled.

**Ticking a folder forgets its cursor (2026-08-13).** The sentence that stood here — that a
delete staying permanent is *"correct under the one-way model, not a bug"* — was half right and
shipped a dead end. The model is fine; what was missing was any way to **ask again**. Measured on
the maintainer's phone hours later: bucket `-1829889111` selected, its cursor at `1000015966`, all
787 files below it, the destination folder deleted from another device — so every drain enumerated
zero new items, reported `UP_TO_DATE`, and uploaded nothing, permanently. Re-ticking the folder did
nothing at all, which reads as a dead feature and was reported as one.

So `setBucketSelected(bucketId, selected = true)` now calls `BackupPreferences.clearCursor(...)`
first: ticking a folder means "back this folder up", and it is the only unambiguous request to
re-scan a user can make.

- ⚠ **A reset is not a re-upload.** `uploadForBackup` resolves a destination collision to
  `Skipped` (§3.5's backstop), so re-scanning costs one round trip per item and re-uploads only
  what is genuinely missing.
- ⚠⚠ **The reset must stay at the tap, and must not move into `SyncedFolderController`'s
  `selectedBuckets` collector.** That flow re-emits the stored selection on every process start and
  the collector cannot tell that first emission from a user's tick — it would wipe every cursor at
  each launch and re-enumerate every bucket forever.
- ⚠ **Order is load-bearing:** clear *before* writing the selection. Writing the selection is
  itself a drain trigger, so the reverse order races a drain that reads the old cursor, finds
  nothing, and parks the source at `UP_TO_DATE` with nothing left to re-trigger it.
  `BucketReselectResetsCursorTest` pins the order, not merely the end state.
- Unticking deliberately leaves the cursor alone — that is "stop", not "redo".

---

## 4. Final shape

### 4.1 The app-side seam — `IngestSource` (registered with Control's engine)

The generic engine side is a Control-owned `IngestSourceRegistry` — apps call
`register(descriptor)` where the descriptor carries `id`, the resolved
`ConstraintSpec`, enabled-state, and the Android content-URI trigger hints
(§3.4). The app side is a source adapter that mirrors
`FilesRepositoryPlatformDelegate` (interface in the app's `-shared`
commonMain; Android/Desktop actuals; wired by Koin `get()`). For the gallery
source it is `MediaSourceProvider`, with two concerns — enumeration (for the
settings UI) and observation (the in-process Layer-2 trigger); the Layer-3
trigger arrives from Control (§4.4) and invokes the same `listNewItems` loop:

```kotlin
// :drive-shared commonMain — data/MediaSourceProvider.kt
data class MediaBucket(
    val id: String,            // MediaStore bucket id / desktop dir path
    val displayName: String,   // "Camera", "WhatsApp Images", "Screenshots"
    val itemCount: Int,
    val sampleThumbnailUri: String?,  // for the settings list
)

data class MediaItem(val uri: String, val fileName: String, val addedAtMillis: Long)

interface MediaSourceProvider {
    /** Settings UI: enumerate candidate source folders. */
    suspend fun listBuckets(): List<MediaBucket>
    /** Trigger (app alive): emits when any watched bucket gains an item. */
    fun observeChanges(watched: Set<String>): Flow<Unit>
    /** Loop: items in [bucketId] newer than [sinceCursor], oldest-first. */
    suspend fun listNewItems(bucketId: String, sinceCursor: String?): List<MediaItem>
}
```

- **Android** (`AndroidMediaSourceProvider`): `MediaStore.Images`/`Video`
  `ContentResolver` queries grouped by `BUCKET_ID` / `BUCKET_DISPLAY_NAME`;
  `observeChanges` registers a `ContentObserver` on the collection URIs.
  Requires `READ_MEDIA_IMAGES` (+ `READ_MEDIA_VIDEO`) — held by the **Drive**
  process.
- **Desktop** (`DesktopMediaSourceProvider`): buckets are user-added directories
  (no OS gallery); `observeChanges` uses `java.nio.file.WatchService`;
  `listNewItems` is an mtime-watermarked `walkTopDown`. Desktop has no default
  buckets, so §3.7's "default-on" is Android/iOS only — desktop users add
  directories explicitly (the Syncthing model).

### 4.2 Config — new `DrivePreferences` (all `PreferenceScope.DEVICE`)

```kotlin
object DrivePreferences {
    // ... existing OfflineFiles ...
    val AutoBackupEnabled    = Preference("drive.backup.enabled",    DEVICE, false, BooleanAdapter)
    val BackupBuckets        = Preference("drive.backup.buckets",    DEVICE, emptyList(), StringListAdapter)
    val BackupNewFolders     = Preference("drive.backup.newFolders", DEVICE, true,  BooleanAdapter)
    val BackupWifiOnly       = Preference("drive.backup.wifiOnly",   DEVICE, true,  BooleanAdapter)
    val BackupChargingOnly   = Preference("drive.backup.chargingOnly", DEVICE, false, BooleanAdapter)
}
```

A `BackupPreferences` data class (mirroring `OfflinePreferences` /
`SyncRulesPreferences`) wraps reads/writes; per-bucket cursors are a separate
device-local store (a small serialized `Map<bucketId, cursor>` preference blob,
or — if it grows — a non-`Syncable` local relation; preference blob for v1).

### 4.3 App-side orchestration — `SyncedFolderController`

A Koin `single` (app-scoped, like `FolderUploadController`) in the app process
that:
1. collects `AutoBackupEnabled` + `BackupBuckets` + `storageService.status()` +
   the constraint state, and **registers/updates the source descriptor** with
   Control's `IngestSourceRegistry` (§4.1) so the Control engine knows this
   source's constraints + enabled-state + content-URI trigger hints;
2. while the app is alive (Layer 2), subscribes `mediaSource.observeChanges(buckets)`
   and, per change — when enabled **and** storage available **and** constraints
   satisfied — runs the per-bucket `listNewItems` → each file through the
   existing `uploadPlatformFileCancellable` path → advances the cursor;
3. exposes an **`ingestNow()`** entry the Control trigger invokes (Layer 3,
   §4.4) running the identical loop;
4. publishes a `StateFlow<BackupState>` consumed by the settings screen, the
   ambient bar, and the Drive upload notification (§5d reuse).

It is the auto-driven sibling of `FolderUploadController` and shares its
liveness anchor (the app's `dataSync` foreground service from
[`drive-folder-upload.md`](drive-folder-upload.md) §5d).

### 4.4 Layer-3 trigger — Control-owned WorkManager + one-way wake (the new IPC)

Control's `PlatformScheduler` (bgproc Phase 6/7) registers, **per registered
source**, a WorkManager `PeriodicWorkRequest` + the source's content-URI
trigger + the source's `Constraints` (§3.8). On fire, Control's worker
re-checks constraints + storage, then sends the **one-way "ingest now" signal**
to the source's app, **starting that app's exported ingest service if its
process is dead** (Android: an explicit intent / bound-service start the app
exports for this; the desktop daemon has no dead-process case). The app's
`SyncedFolderController.ingestNow()` then enumerates + uploads via the normal
app→Control path. **Bytes never flow Control→app** — only the tick does. This
is the new IPC surface §3.3 names (reverse trigger + cross-app process-start),
shared verbatim by Timeline/Health when they land. It is the part **gated on
bgproc Phase 6/7 + the reverse-trigger channel**; Phase 1 (in-process, §3.4)
ships without it.

### 4.5 Destination mapping

Backed-up files land under a fixed, configurable realm root —
default `"/Backup/<deviceName>/<bucketDisplayName>/<fileName>"`. Keeps device
sources from colliding (two phones' "Camera" don't merge) and is greppable.
Revisited when Photos lands its own library view (which will read these files
by EXIF, not path).

---

## 5. UI — the "Synced Folders" screen

Reached from a new **"Synced Folders"** row in the existing
`SyncPreferencesScreen` (`:drive-shared` `ui/screens/settings/`), opening a
dedicated `SyncedFoldersScreen` + `SyncedFoldersViewModel` (the folder list is
too large to inline under the existing toggles). Layout, top to bottom:

1. **Master toggle** — "Automatically back up folders" (`AutoBackupEnabled`).
   When off, the rest is collapsed/disabled.
2. **Storage gate banner** — if `!hasStorage`: an inert card "Back up needs
   storage" + CTA to the storage/subscription screen (§3.6); the master toggle
   is disabled until storage exists.
3. **Policy rows** — "Back up over Wi-Fi only" (`BackupWifiOnly`, default on),
   "Only while charging" (`BackupChargingOnly`, default off).
4. **"Back up new folders automatically"** (`BackupNewFolders`) — Google Photos
   parity.
5. **Folder list** — one row per `MediaBucket`: thumbnail, name, item count, a
   trailing switch. All image buckets default selected (§3.7). This is the
   Google-Photos per-folder model the user asked for.
6. **Status footer** — current backup state from `SyncedFolderController.state`
   ("Backing up 12 of 40…", "Up to date", "Paused — on cellular", "Out of
   space"), reusing the §5d ambient surfaces.

The ViewModel reads buckets via `MediaSourceProvider.listBuckets()` and the
selection/policy via the new `BackupPreferences`, identical in shape to how
`SyncPreferencesViewModel` consumes `OfflinePreferences` / `SyncRulesPreferences`.

**Change-detection UX (the open question):** the user never sees "watching" as
a concept — they see folders with switches (like Google Photos), and a quiet
status line. Detection is invisible; the only visible artifacts are (a) the
per-folder toggle list and (b) the ambient progress/notification when an upload
actually runs. This matches every comparable app — none surface "I am observing
the gallery" as UI; they surface *folders* and *progress*.

---

## 6. Threat-model considerations

**No relay/crypto/protocol surface changes.** Auto-upload is N invocations of
the existing per-file upload op (§3.3); the relay sees the same ciphertext
SyncBlocks as a manual upload; assets A1–A4/A9, block opacity, and the
Drive↔Control boundary are untouched — same conclusion as
[`drive-folder-upload.md`](drive-folder-upload.md) §4.

**One genuinely new property worth recording (House Rule #2 — considered):**
this is the first feature where user content leaves the device **automatically,
on a stored policy, with no per-file user action**. That is a consent /
expectation surface even though it touches no cryptographic asset. The
mitigations are design-level and belong in the threat model as a note: the
feature is **opt-in** (§3.7), the selection is **device-local and
user-revocable** (§3.1), uploads honour the **storage gate** (§3.6) and the
**Wi-Fi/charging constraints** (§3.8), and a **device revocation** must stop the
scheduled ingest (the `PlatformScheduler.cancelAll` / WorkManager `cancelAllWork`
lever already named in [`core-background-processing.md`](core-background-processing.md)
§6.2 applies). Whether this is a new threat-model *row* or a note under the
existing upload asset is a maintainer call to settle when the code lands.

**The reverse trigger (§4.4) is a new IPC direction — assess it when built.**
Today IPC is strictly app→Control; the Control→app "ingest now" tick + the
cross-app process-start are new. The tick carries **no user data** (just "go"),
and which apps may be triggered is already governed by app registration (A18 /
T-C-14), so this is expected to be a small surface — but a Control→app channel
that can *start* an app process deserves its own threat-model line when the
reverse-trigger lands (it is the shared Timeline/Health mechanism, so the line
is written once and reused). Flagged per House Rule #2.

---

## 7. Phased plan

**Phase 1 — In-process auto-backup, Android + Desktop (app-alive). No
reverse-IPC; ships standalone.** `MediaSourceProvider` (both actuals),
`BackupPreferences` + the new `DrivePreferences`, `SyncedFolderController`, the
`SyncedFoldersScreen`/VM, the storage gate, the cursor store. Trigger is the
in-process `ContentObserver` / `WatchService` only; reuses the §5d foreground
service for liveness; per-file uploads go through the landed folder-upload
Layer-2 path. Ships the **whole user-visible feature** *while the app (or the
desktop daemon) is alive* — not blocked on any bgproc Layer-3 work. Exit:
enabling the feature, then adding a photo to a selected bucket, uploads it
within seconds under `/Backup/...`; disabling/​unselecting stops it;
metered/no-storage states gate correctly.

**Phase 2 — The Control-owned generic engine + reverse trigger (the
unification).** Control's `IngestSourceRegistry` + the one-way "ingest now"
channel + cross-app process-start (§4.4), with the gallery source as its first
client. Gated on **bgproc Phase 6/7** (`PlatformScheduler` + Android
WorkManager) plus the new reverse-trigger IPC. Closes the OneDrive gap (works
after swipe-away / reboot) **and** lands the mechanism Timeline location capture
+ Health will reuse. Device-gated verification (no Android SDK in the build
env). This is the phase that earns "Control owns it" — Phase 1 deliberately
ships value first without it.

**Phase 3 — Document-tree source via SAF (Files-side).** Persisted tree grant +
`FileObserver`/periodic re-scan as a *second* source adapter behind the same
registry, with its own Files-side UI (§3.2). Covers WhatsApp *Documents*, a
`Download` subfolder, etc. Inherits the §10 SAF-grant limitations from
folder-upload.

**Phase 4 — iOS (`PHPhotoLibrary`) + Photos handoff.** Lands with the iOS app
architecture; Photos consumes the gallery source for its ingestion + EXIF
indexing, and the v1 settings screen graduates into Photos' Settings tab.

---

## 8. Open questions

1. **Control-owned scheduler — DECIDED (2026-06-08, with maintainer): yes.**
   Control owns the generic engine; the app owns the source adapter (§3.3). The
   remaining sub-question is the **reverse-trigger transport** — Android explicit
   intent / exported bound-service vs a binder callback registered at bind time;
   and whether cross-app *process-start* needs a manifest-declared exported
   service on each ingesting app. Settle when Phase 2's IPC is specced (it's the
   protocol-spec surface — [`protocol/desktop-ipc.md`](../protocol/desktop-ipc.md)
   / `ios-ipc.md` would gain a reverse-trigger section).
2. **Cursor storage** — preference blob vs a small non-`Syncable` local
   relation. Blob for v1; revisit if per-bucket state grows (e.g. per-item
   retry counts).
3. **Destination layout (§4.5)** — `/Backup/<device>/<bucket>/` vs a flat
   library vs mirroring the source tree. Interacts with what Photos wants;
   maybe Photos reads by EXIF and the path is irrelevant — decide with the
   Photos milestone.
4. **Videos / large files — the *emit* is not the memory blocker (ADR 0017).**
   The relay emit was migrated to chunking + manifest (peak heap `O(CHUNK)` for
   the emit, 2026-05-20). ⚠ **That bound covers the emit only, not the upload
   op:** on Android the op still reads the whole file into the Java heap before
   the emit (`FileInputStream.readBytes`), and the poster rungs and MOTION take
   that array again — `media-processing-pipeline-sweep.md` F2 (OOM inferred for
   a large video, not yet reproduced; P2 owns it). Beyond that, the reason to
   keep video behind its own toggle is **bandwidth + storage quota +
   metered-data cost** (a multi-GB video against the user's plan / on
   cellular), not emit stability. Recommendation: images default-on, video an
   explicit opt-in toggle, framed as a cost choice.
5. **Cross-device content dedup — agreed follow-up (later).** Two phones backing
   up the same WhatsApp image produce two realm files at different
   `/Backup/<device>/` paths (today dedup is **path-derived** via `dataId`).
   Collapsing same-content-different-path needs a **client-side content-hash
   index above the realm** — and it **must** be client-side: ADR 0017 deliberately
   makes content refs *ciphertext-derived, never plaintext-hash* (a plaintext
   hash would be a dedup oracle for the relay, alternative (v) rejected), so you
   cannot lean on relay ref-collision for dedup. Benefits Photos most; its own
   slice, not this feature's.

---

## 8b. ⚠⚠ A pause the user cannot see — the gate-visibility problem (2026-08-16, design only)

**Reported as:** *"I check WhatsApp Images in Synced Folders and no upload starts."* Investigated
end to end on the device; the engine was behaving exactly as specified, and the report was still
correct. This section is the concept for closing that gap. **No code — design only.**

### 8b.1 What actually happened

The realm was **over quota**: `tenshu/storage/status` read `usedBytes = 11,684,489,630` (10.88 GiB)
against `limitBytes = 5,368,709,120` (5 GiB) on plan `tenshu.drive.free`. So
`remainingBytes = 0` → `isFull` → `evaluateBackupGate` returns `Paused(OUT_OF_SPACE)`, correctly.

What made it unreadable is the **order inside `drainSourceInner`**: enumerate → `ensureBackupDestination`
→ *then* the per-item loop, whose first action re-reads the gate and returns. So an over-quota device
still does visible work and then stops:

- the destination folder row **is** created — Drive shows `WhatsApp Images · 0 files`;
- **no cursor is written**, nothing uploads, and **no error is logged**.

⚠ For diagnosis, the absent cursor is the discriminator. Only three branches write nothing — gate
not `Allowed`, bucket deselected, `Failed`. Both `Skipped` and `Uploaded` call `setCursor`. A
destination folder that was just created next to a bucket with no cursor means the loop died on
item #1, which rules out the whole upload path.

### 8b.2 The actual defect: the state exists and has nowhere to appear

`BackupState.Paused(OUT_OF_SPACE)` already renders as *"Paused — storage full"*. The engine is not
lying — but that string only exists on the **Synced Folders screen**, and the user who just ticked a
folder goes back to Home, where there is no banner and no badge. The gesture ("back this folder up")
and its refusal ("I can't, you're out of space") are on two different screens, so the refusal is
invisible at the moment it is earned.

This is not specific to quota. Every non-`Allowed` gate has the same problem: `NO_NETWORK`,
`METERED_NETWORK`, `NOT_CHARGING`, `PausedByUser` (ST-2b) — each is a *reason nothing is happening*
that the user can only find by navigating to the screen they just left.

### 8b.3 Options

| | Option | What it costs | What it misses |
|---|---|---|---|
| A | **Acknowledge at the gesture** — when a tick is made while the gate is not `Allowed`, say so inline on the Synced Folders row itself | Smallest. No new surface | Only covers the moment of ticking; a gate that closes *later* is still silent |
| B | **A Home banner for a blocked backup**, reusing the existing sync-health banner slot | Medium; one new state on a surface that already exists (`SyncHealthStatusModel` already drives a 402/entitlement banner) | Needs a rule for which gates are worth interrupting for |
| C | **A notification** when a run is blocked | Largest; needs POST_NOTIFICATIONS (currently `granted=false` on the test device) and a re-notify policy | Easy to make annoying; a metered-network pause is not notification-worthy |

### 8b.4 Recommendation

**A + B, and not C.**

A is the honest minimum: a refusal belongs at the gesture that earned it, and it is the only one of
the three that fires at the exact moment the user is looking. B is what covers the *later* closure —
the gate that shuts while the app is backgrounded — and it should reuse the sync-health banner slot
rather than invent a surface, because Drive already has a banner there for the 402/entitlement case,
which is the *same underlying condition* reached through a different route.

⚠⚠ **Not all gates deserve the same volume, and that is the design decision B actually turns on.**
Split them by whether the user can act:

- **Actionable, and the user is stuck until they act** — `OUT_OF_SPACE`, `NO_STORAGE`. Banner.
  These do not resolve by waiting, which is exactly what distinguishes them.
- **Self-resolving** — `NO_NETWORK`, `METERED_NETWORK`, `NOT_CHARGING`. No banner; the Synced
  Folders row is enough. Interrupting Home because the user walked out of Wi-Fi range would train
  them to ignore the banner, and the banner's whole value is that it is rare.
- **Self-inflicted and already known** — `PausedByUser`. No banner: the user pressed Stop, and
  telling them they pressed Stop is noise. ⚠ It is still worth a *quiet* affordance, because
  "paused" and "off" are different sentences (ST-2b D1) and a pause with no visible resume is how a
  user concludes the feature is broken.

⚠ Quota deserves one refinement the others don't: the banner should name the number
(*"10.9 GB of 5 GB used"*), because "storage full" is indistinguishable from a bug when the user
believes they have space. The figures are already in `StorageStatusModel`.

### 8b.5a DECIDED (2026-08-24, with maintainer) — link to Control → Services, and it belongs everywhere

**The upgrade CTA question is settled: link, never an inline purchase.** `[Manage storage]` →
`StorageSetupLauncher.openStorageSetup()` → Control's **Services** screen, which already renders
*"the real plan + the real fill level, read off the synced entitlement"* plus signup and checkout.

Three reasons it is the link and not a buy button:

1. **Precedent.** `retention-quota-model.md` P1b-1 already ships the 80 % soft-limit banner in
   Control with `[See plans]` → `Destination.Services`. A second purchase entry point would be a
   second thing to keep honest.
2. **No purchase rail on iOS.** `commerce-accounts-and-sessions.md` Ph4b (IAP) is unbuilt and the
   current targets are "Android + Desktop", so an inline CTA would be dead or a compliance risk on
   one of three platforms.
3. **Buying is not the only fix.** A user over quota may want to delete rather than pay. Services
   shows the position and lets them choose; a "buy more" button at the moment their feature breaks
   reads as leverage — especially since they already declined once at 80 %.

⚠⚠ **80 % and 100 % are different products and must not share persistence rules.** The existing
banner is *marketing* (§2.3: "the only marketing prompt", one-time, permanently dismissable via
`QuotaSoftLimitBannerDismissed`). This one is an *operational block* and must be **sticky while
true** — a live blocker, not a prompt. Same destination, opposite dismissal semantics.

**And it belongs in every app, not just Drive.** The gate is realm-level; every app writes, so any
app can be blocked. Drive is in fact the *least* surprising place for it — a file app talking about
storage is expected, whereas Fit silently not recording is where the confusion actually lands.

### 8b.5b The promotion — what it needs (scoped 2026-08-24, not built)

Drive's slice shipped first because it fixes the reported bug and is self-contained. The
generalisation is a separate track, and the survey says it is tractable rather than large:

- ⚠ **Nothing outside Drive references storage, banners or the launcher today** — zero files across
  Fit / Timeline / Maps / Calendar / Home / Mia / Contacts. So this is a new capability per app, not
  a refactor.
- ✅ The **status read is already shared**: `StorageService.status(): Flow<StorageStatus?>` in
  `:storage-service-client`. No new plumbing.
- ✅ **`ui-core` has an `iosMain`** and already ships `feedback/ErrorBanner.kt`, so it can host both
  the shared banner and the launcher seam.
- ⚠⚠ **iOS is not blocked the way `control-cross-app-open.md` implies.** That doc rules out
  launch-by-bundle-id, and that remains true — but **Control-iOS registers the URL scheme
  `tenshu-control`** (`control-ios/project.yml`), so the iOS implementation is
  `openURL("tenshu-control://services")`. There is no `StorageSetupLauncher` on iOS today at all
  (`drive-shared-ui` has no `iosMain`; Drive's iOS UI is SwiftUI), so this is the one genuinely new
  platform implementation.
- ⚠ Control may not be installed, and route-1 apps work without it. Gate the action on
  `canOpenURL`; a button that does nothing is worse than no button, so the banner must degrade to
  text.

### 8b.5c ⚠⚠ The state to generalise is NOT the quota one — corrected 2026-08-24

§8b.5b framed the promotion around quota. Reading `StorageSyncGate` on latest master says the more
important half is the other one, and the two are **different mechanisms, not degrees of one scale**:

| | condition | the gate | consequence |
|---|---|---|---|
| **No plan** | `hasStorage == false` | **shut** — `storageAllowsSync(figures) = figures?.hasStorage == true` | nothing pushes, from any app, ever — silently |
| **Over quota** | `hasStorage == true`, over limit | **open** | pushes are attempted and the relay 402s |

So an over-quota realm still *tries*, and the rejection surfaces as `needsAttention`. An **unfunded**
realm never speaks to the relay at all, and that is the silent one.

⭐ `ios-services-status-and-purchase.md` §3.5 measured it: a realm created on iOS sat at **0 blocks**
with two devices correctly paired and registered, the Services card saying only *"your storage
details aren't available yet"* — a promise that cannot arrive. ⚠⚠ The rescue needed a **second,
non-iOS device**, so an iOS-only user has no in-app path out. That is what makes this a
**correctness** affordance, not a monetisation prompt — the framing §8b.5a was still carrying.

⚠ Op-level quota guards remain **file-service only** (`ensureWithinQuota` on upload,
`ensureCapacity` on the two share ops — every caller checked). Nothing in Fit / Timeline / Calendar
/ Photos throws; their data simply never leaves. A typed failure would at least be visible.

**Built (this PR), in `ui-core`:**

- `RealmStorageState` — `Ok` / `NoPlan` / `Full(used, limit)` — plus `evaluateRealmStorage(...)`, a
  pure function over primitives so no app needs a new module dependency. ⚠ Unknown ⇒ `Ok`, the
  *inverse* of `StorageSyncGate`'s fail-closed, because withholding relay traffic and accusing the
  user of a problem are not the same act.
- `RealmStorageBanner` — renders nothing for `Ok`, so it can sit unconditionally above a nav host
  the way `ErrorBanner` already does. `errorContainer` for `Full`, the quieter `secondaryContainer`
  for `NoPlan`: the app is fully usable and the data is safe, and shouting on every screen of an
  unfunded realm is the loudest possible way to say "you have not paid".
- `openControlServices()` in `iosMain` — the one platform where no route existed.

⚠⚠ **The action is a lambda, not a second `StorageSetupLauncher`.** Drive already owns an interface
by that name; a same-named twin in `ui-core` is exactly the trap that broke iOS twice this month
(two `FakeFilesRepository` classes, one updated). Drive passes `launcher::openStorageSetup`, an iOS
app passes `openControlServices`.

**Left to do:** per-app adoption — one composable in each scaffold, its own strings, and for iOS an
`LSApplicationQueriesSchemes` entry for `tenshu-control` in each app's `project.yml` (⚠ `Info.plist`
is generated and gitignored). ⚠ That touches `androidMain` in seven apps, so it owes the smoke gate
(HR #8) and the iOS build — its own PR, deliberately.

### 8b.5 Open

- Whether the quota banner should offer an upgrade path inline, or only link to storage settings.
  **CLOSED — see §8b.5a.** Link to Control → Services; sticky while true, unlike the 80 % prompt.
- Whether an over-quota device should stop *enumerating* rather than stop *per item* — today it
  creates an empty destination folder on every drain, which is harmless but is also the artefact
  that made this look like a broken feature. Cheap to fix once, and arguably §3.9's "the destination
  is created lazily" should say "…and only when something can actually be written into it".

---

## 8c. ⚠⚠ A failure the user cannot see, and one file that stops a folder (2026-09-05, BUILT)

**Reported as:** *"Camera is selected but the app never uploads it; WhatsApp Images works fine."*
The same sentence as §8b, a different cause, and this time neither half of the report was right:
**every** upload had been failing for three days, in every bucket. Camera only looked singular
because it had been ticked two days earlier and so had nothing older to hide behind.

### 8c.1 What actually happened

Drive was a build from **before** the upload-op rename talking to a Control from **after** it. The rename moved the upload op
out of `androidMain` — `tenshu/file/system/android/fileUpload/bg` → `sourceFileUpload/bg` — so every
call came back `unsupportedOperation` for a wire tag the proxy no longer had.

⚠⚠ **Three guards were silent, and each reason generalises beyond this incident:**

1. **The announce gate admitted the session, correctly.** It refuses only `announced > current` or
   `announced < floor`; the rename shipped file-service 8 → 9 with an *empty* migration step, so the
   floor stayed low and an announced 8 sat inside the accepted range. **A version range is not an
   operation range** — op removal has no compatibility window at all, so a properly admitted client
   can call an operation that no longer exists. `SchemaSkew` cannot express this and should not be
   taught to; the gap is that a rename is a wire-breaking change with no gate of its own.
2. **`SchemaShapeBaselineEnforcementTest` was green**, because these four ops are outside the
   baseline's graph (its registry comes from `fileServiceCommonModule`, not the module that
   registers them) — which the rename's own commit message flagged as it happened.
3. **The drain threw the reason away.** See below.

### 8c.2 Defect 1 — a failed upload said nothing, anywhere

`TenshuFilesRepository.attemptUpload` discarded the cause **twice**: `guardingSourceRead` dropped the
`Throwable`, and the `fold(onFailure = …)` dropped the `TError`. Backup is the one upload path with
no user standing over it, so there was nothing left — no `ErrorEvent`, no log line, no UI. The
Synced Folders screen rendered `state.uploaded` alone, so a drain in which every upload was rejected
read *"Backed up 0 files"*, which is what a device with nothing to do also says.

**Built:** `BackupUploadOutcome.Failed` carries a `reason` (the error's message, else its type id);
it reaches `BackupState.JustFinished.failureReason`, the Synced Folders status line, the terminal
notification body, and the iOS surface. `backupStatusFor` now reports **`STATUS_ERROR`** for a run
that ended with failures — Control's inventory row read `UP_TO_DATE` throughout.

⚠ The reason is deliberately developer-shaped and shown to the user anyway: something they can
quote beats a polished sentence that says nothing. The alternative on offer was a generic
"couldn't upload", which is the state this replaces.

### 8c.3 Defect 2 — one bad file held a whole folder hostage

The per-bucket cursor is a **watermark**, not a set: `listNewItems` asks for `> cursor` over the
oldest-first order, so the only thing it can express is "everything up to here is dealt with".
A failure therefore *must* freeze it — advancing past a failed file would put it behind the
watermark and lose it. The drain also `break`ed, and that second half was the bug: 2 247 Camera
photos waited behind item #1, indefinitely, because freezing the cursor and stopping the folder are
different remedies and only the first is required.

**Built:** the drain keeps going and merely stops moving the watermark. The successes behind a
frozen watermark are re-offered next drain and answered `Skipped` by the §3.5 already-at-destination
backstop — a metadata round trip each, never a re-upload.

⚠ That cost is bounded by `MAX_CONSECUTIVE_BUCKET_FAILURES` (5), which is the line between *one bad
file* (keep going) and *nothing works here* (stop, so a broken build costs five round trips instead
of one per file in the folder). The old `break` bought that bound by accident, at the price of the
wedge. Any success resets the run, so scattered bad files still drain the folder completely.

⚠ **Known residue, accepted:** a file that MediaStore keeps listing but that can never be uploaded
re-scans its folder on every drain for as long as it persists. The common real case — a file deleted
between enumeration and upload — resolves itself, because the next drain does not enumerate it. The
alternative is persistent per-item quarantine state, which needs a "retry failed items" affordance
of its own or it silently abandons a photograph, i.e. defect 1 again.

---

## 8e. A policy pause that goes stale, and one that says how much it holds (2026-10-10, BUILT)

**Reported as:** *"it has not picked up photos today."* The engine was right: Wi-Fi-only was on and
Wi-Fi had been off since the night before, so both WorkManager jobs sat on an unsatisfied
`NOT_METERED` constraint and uploaded within a minute of Wi-Fi returning. Two gaps around that:

**1. The gate verdict outlived its cause.** `evaluateBackupGate` is only read inside a drain, and
drains were triggered by settings, storage and media events — never by the network or the charger.
So *"Paused — on mobile data"* stayed on screen after Wi-Fi came back until the next photo or the
6-hour wake. `BackupEnvironment.changes()` is now a fourth trigger. ⚠ It is for the **state**, not
the uploads: with Drive closed the WorkManager constraints still do the waking. ⚠ Abstract on
purpose — a defaulted empty flow would have left a platform silently stale. Android dedups on the
*classification* (capability callbacks fire per signal step, battery broadcasts per percent); iOS
signals the network only, so *"Waiting to charge"* there still clears on the next media event.

**2. A pause did not say what it was holding.** `BackupState.Paused` now carries `waiting`, counted
by enumerating past the cursors when the gate refuses. ⚠ An upper bound: it is taken before the
destination-exists and content-hash skips, because refining it would mean hashing files while
paused on battery. The Synced Folders line appends *"· N files waiting"*.

**⚠ This reverses half of §8b.4 — DECIDED 2026-10-10, with maintainer, after a pass on the phone.** §8b.4 said self-resolving gates get no Home banner,
because a banner for walking out of Wi-Fi range trains people to ignore it. `SyncBannerState.BackupWaiting`
now shows one for `METERED_NETWORK` and `NOT_CHARGING` — but **only while `waiting > 0`**, which is
the case §8b.4 did not separate: a pause with nothing behind it is still silent, a pause sitting on
a day of photos is not. It ranks below every other banner state. Seen on the reporting phone: Wi-Fi off plus one
new photo raised it, Wi-Fi back on uploaded and cleared it. ⚠ That pass does not isolate the new
trigger — the WorkManager job would have uploaded on its own — so the nothing-waiting case rests
on `leavingWifi_reportsThePauseWithoutAMediaEvent` alone.

---

## 8d. ⚠⚠ The "already backed up" skip asks per batch, and "could not look" defers (2026-10-01, BUILT)

The drain's third dedup layer (`DuplicateContentGate`, Photos' PH-6 — *"another device already
uploaded these bytes"*) is the only one that needs the realm. Full write-up and measurements:
`photos-app.md` §40.

### 8d.1 What was wrong

1. **It read the whole realm in one reply.** `openForDrain()` loaded `ContentIndex` — every
   non-trashed file, nine properties each, in one `getAll` — once per drain. ~640 bytes a file in one
   Binder transaction: **995 KB** on a 1,401-photo phone, ~5.8 MB at 10,000 files, and past ~1 MB
   the read does not come back at all.
2. ⚠⚠ **And a read that did not come back meant "skip nothing".** `openForDrain()` returned `null`
   for a failed load exactly as for the setting being off, so every candidate uploaded — content the
   realm already held, billed again, with nothing on screen saying so. The realm that is too big to
   read is precisely the one whose owner has the most to re-upload.

### 8d.2 What it does now

- **The question is narrowed to the candidates.** The drain hashes a **lookahead batch** — up to
  `DUPLICATE_LOOKAHEAD_ITEMS` (32) candidates the cheap layers let through, within
  `DUPLICATE_LOOKAHEAD_BYTES` (64 MB) of reading — and asks the realm about all of them at once:
  `RealmContentReads.heldContentHashes`, one fused `contentHash oneOf …` walk per 200 hashes, each
  reply at most 1,000 hash-only rows (~205 B each). JVM, 10,000 files: one 3.4 KB reply, ~22 ms,
  against the old 5.65 MB.
- ⚠⚠ **A candidate of unknown size ends the batch** (`DuplicateLookaheadTest`,
  `…candidatesOfUnknownSize_areHashedOneAtATime_notABatchAheadOfTheFirstUpload`). `sizeBytes == null`
  used to count as zero against the 64 MB, so a source that sizes nothing had 32 candidates of any
  length read end to end before the first upload — and before a Stop could land.
  ⚠⚠ **That source is iOS**: `IosMediaSourceProvider` never sets `sizeBytes` (PhotoKit's `PHAsset`
  has no size), so on iOS every batch is **one** candidate — one O(realm) walk per asset instead of
  one per 32. A first backup of 5,000 photographs is 5,000 walks rather than ~160: at the JVM's
  10–22 ms a walk (§40.4 of `photos-app.md`, 1,400–10,000 files) one to two minutes of walking,
  spread over uploads that take far longer. ⚠ Unmeasured on a device.
  **Owed — a lookahead weight for iOS, not built.** The true size is not cheap:
  `PHAssetResource.assetResourcesForAsset` is a query per asset and its `fileSize` is an
  undocumented KVC key, which is exactly what `MediaItem.sizeBytes` says not to put in front of a
  backup. What *is* free at enumeration is `pixelWidth × pixelHeight` and `duration`, enough for an
  upper estimate — but that is a weight for this budget, a new field, and must not be passed off as
  `sizeBytes`, which the run's byte figures show to the user.
- ⚠⚠ **`openForDrain()` returns `null` only when the setting is off.** A failed realm read is
  `ContentPresence.Unknown` per candidate, and the drain treats it as a **failed item** (§8c): the
  watermark freezes there, the run's summary carries the typed cause
  `BackupFailureKind.COULD_NOT_CHECK_REALM`, and the next drain asks again —
  `MAX_CONSECUTIVE_BUCKET_FAILURES` still stops a folder where nothing works. Neither "skip" (drops a
  photo the realm may not have) nor "upload" (re-bills one it may have).
- ⚠⚠ **An unchecked asset is not "seen".** The `Unknown` path takes the URI back out of `seenUris`:
  an iOS album is a view, so the same asset can sit in two selected albums, and the second album's
  duplicate-URI branch would otherwise advance *its* cursor past an asset nothing handled — then
  deselecting the first album loses the photograph for good
  (`…anAssetTheRealmCouldNotBeAskedAbout_staysOfferedThroughAnOverlappingAlbum`). Releasing it does
  not upload it twice: if the second album gets an answer and uploads, the first album's retry finds
  the content **held** and skips (`…anAssetReleasedByAFailedCheck_uploadsOnceThroughTheOtherAlbum_andNeverTwice`).
  ⚠ That guarantee is the content skip's own — switch the skip **off** between the two drains and the
  first album's frozen cursor re-offers the asset with nothing left to recognise it. ⚠ The same shape
  exists for an upload that **fails** in the first album (§8c) and is left as it was: releasing it
  would make the second album upload the asset into its own folder, and the first album's retry
  then a second copy — the duplication `seenUris` exists to stop. Not fixed here.
- ⚠ **"Held" ends a streak of failures**, as an upload and a data-plane skip do: the realm answered.
  It did not at first, so *could not look* alternating with *held* stopped a folder after five
  failures none of which were consecutive
  (`…couldNotLookAlternatingWithHeld_isNotARunOfFailures_andTheFolderDrainsOn`). The cursor stays
  frozen behind the first unchecked file all the same.
- ⚠ **Which cause the summary names: the run's last failure's, and only that one.** A run is several
  coalesced drains and its summary has room for one sentence (§8c), so:
  - *a later failure replaces the earlier cause whole* — both `failureReason` and `failureKind` are
    written together, so a typed cause never stands beside another drain's free text, in either
    order (`…aTypedFailureAfterAReasonedOne_namesTheTypedCauseAlone`, `…aReasonedFailureAfterATypedOne_namesTheReasonAlone`);
  - *a drain in which nothing failed erases nothing* — **chosen here, the rule was silent.** The
    summary still counts the earlier drain's failures (`failed` is the run's sum), so it still owes
    their cause, even when the retry in the later drain then uploaded the very file
    (`…aCleanDrainAfterAFailedOne_keepsTheCauseOfTheFailureTheSummaryStillCounts`);
  - *mixed causes are not listed* — **also chosen here.** The count is every failure, the sentence is
    the last one's, so "3 waiting — couldn't check…" can include two files that failed to upload for
    another reason. A per-cause breakdown would be a list, which §8c decided against
    (`…failuresOfMixedCausesInOneDrain_nameTheLastOne`).
  ⚠ The write is keyed on the drain's failure **count**, not on a cause being present. It changes nothing reachable today: `BackupUploadOutcome.Failed.reason` is
  non-null and the only other failure is the typed one, so no failure arrives with neither — which
  is also why no test can tell the two conditions apart.
- ⚠ **The cause is typed, not English**. `BackupState.JustFinished.failureKind`
  (`BackupFailureKind`, the `BackupPauseReason` pattern) sits beside the free-text `failureReason`,
  at most one set; each platform renders the kind from its own copy — Compose
  `synced_folders_status_finished_could_not_check`, the Android notification body
  `R.string.synced_folders_failure_could_not_check` (`backupNotificationContent` now *requires* a
  `failureText` renderer, so no default English can leak), and iOS `DriveBackupSurface.failureText`
  (Drive iOS has no string table; its status copy all lives there). Each of the three is a `when`
  with no `else`, so a new kind fails to compile until every platform has copy for it. `BackupState` is in-process
  only — neither stored nor on the wire — so HR #9 does not apply.
- ⚠ **A Control from before §40 cannot answer the batch question** (its predicates raised on a folder's
  absent hash), so the gate falls back once per drain to `everyContentHash` — every hash in the realm,
  in 500-row pages ≤ ~170 KB — and answers the rest of the drain from that set. That is the old cost
  class, bounded per reply; without it, an app updated ahead of its Control would stop backing up.
- ⚠ A blank or unreadable candidate hash is still `New`: the upload then fails with a reason the user
  can act on (unchanged).

Tests: `SyncedFolderControllerTest.theSkipAsksTheRealmInBoundedReplies_andStillSkipsWhatTheRealmHolds`
(2,000-file realm on `SqliteObjectStore` + the real lock; master: one 1,058,343-byte reply, red) and
`…aRealmThatCannotBeRead_defersTheCandidate_neverUploadsIt` (master: `[a.jpg, b.jpg]` uploaded, red).

---

## 9. Implementation Notes for Claude Code

- **Reuse, don't rebuild, the data plane.** Per-file uploads go through the
  *existing* `FilesRepositoryPlatformDelegate.uploadPlatformFileCancellable`
  (→ `uploadSourceFileBackground` / `uploadFileBackground`, the `*Bg` Layer-2
  ops). Do **not** add a new upload op or sync surface — §3.3/§6 depend on this
  being N invocations of the shipped op.
- **Copy the offline-keeper preference pattern verbatim.** `BackupPreferences`
  mirrors `TenshuOfflinePreferences`: resolve `deviceId` once via
  `syncService.getSyncStatus()`, then `preferenceService.readForDevice(
  DrivePreferences.BackupBuckets, id)` / `setForDevice(...)`. Device scope is
  load-bearing (§3.1) — these are **not** `REALM` prefs.
- **Mirror `NetworkStateProvider` for the new seam.** `MediaSourceProvider` is
  interface-in-commonMain + Android/Desktop actuals constructed inline at the
  Koin DI layer (`AndroidMediaSourceProvider(androidContext())` /
  `DesktopMediaSourceProvider()`), **not** `expect`/`actual` — the codebase does
  platform variance through Koin. It lives in the **app's** `-shared`
  (`:drive-shared` for the gallery source) because MediaStore access is an
  app-process permission; the `IngestSourceRegistry` it registers with is
  **Control-side** (`:control-shared` / a sync-adjacent module, alongside
  `ScheduleService`).
- **Phase 1 needs no reverse-IPC; build it first.** The in-process tier (§3.4)
  is a complete, shippable feature using only the landed folder-upload Layer-2
  path. Do **not** block it on Phase 2's Control engine / reverse trigger.
- **Reuse the §5d Drive foreground service.** The
  `FolderUploadForegroundService` / `FolderUploadServiceLauncher` liveness
  pattern (Drive-side `dataSync` FGS driven by a `single` controller's
  `StateFlow`) is exactly what `SyncedFolderController` needs — generalise it to
  observe either controller, don't fork it.
- **Storage gate is a precondition, not a nicety.** Gate both the toggle
  (settings VM) and the loop (controller/worker) on
  `storageService.status().hasStorage`; mirror the `StorageStatus` consumption
  in `drive-connectivity-service.md` §2 — same `Flow<StorageStatus?>`.
- **Conflicts auto-resolve to Skip.** The auto loop has no user present, so the
  `onConflict` callback the folder-upload repository exposes must resolve
  `AlreadyExistsError` silently as `Skip` (the §3.5 backstop) — never surface
  the folder-upload dialog from the background path.
- **Android-only files won't compile in the build env** (no SDK, as with the
  folder-upload Android work). `AndroidMediaSourceProvider`, the Phase-2 worker,
  and manifest (`READ_MEDIA_IMAGES`, exported ingest service) are
  correctness-by-construction + Studio verification; keep all testable logic
  (cursor diffing, constraint resolution, state machine, copy) in `commonMain`
  desktop-tested, exactly as §5d kept `FolderUploadLiveness` /
  `folderUploadNotificationContent` testable.
- **Phase 2 *is* bgproc Layer 3 — build it as the generic engine, not a
  one-off.** The `IngestSourceRegistry` + reverse trigger is the shared
  mechanism Timeline/Health need (§3.3); design it generic from the start and
  put the source-type specifics (gallery vs SAF) only in the per-app adapter.
  The one model divergence to flag in the PR: an ingest source is an
  app-resident callback Control triggers, **not** a Control-resident
  `HeadlessSafe` op (§3.3) — because enumeration can't leave the app's
  permission context.
