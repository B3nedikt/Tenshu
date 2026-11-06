# File Modification History — Synced `FileModification` Composite

**Status:** Draft. Not started.
Author: Benedikt + Claude (post-HomeScreen recent-files discussion, 2026-05-21).

**Scope:** `:file-service` (new `FileModification.kt` composite + concrete
subtypes, `File.kt` schema changes — new `history` relation + the §3.3
field→property migration for `size` / `lastModifiedAt` / `createdAt`),
`:file-service-common` (semantic merger for the modification collection,
property-consistency logic), `:file-service-proxy` (emit-side hooks at every
File write site), `:drive-shared` (`FilesRepository.recentFiles(...)`,
`HomeScreenViewModel` consumer migration),
`docs/sync/threat-model.md` (T-R-XX amendment for modification-block timing),
`docs/sync/design.md` (append-only merge trait note).

**Wire format:** new `FileModification` composite type with concrete subtypes
(`CreatedFileModification`, `ContentChangedFileModification`,
`RenamedFileModification`, `MovedFileModification` for v1) sync as `SyncBlock`s
like any other syncable composite. **Storage shape:** new top-level collection
of `FileModification` entities, parent relation back to `File`; `File`'s
mutable `size` + `lastModified` move from *fields* to *properties* (the latter
renamed `lastModifiedAt`, same "modified date" concept) and a new immutable
`createdAt` *field* is added (§3.3 — a real wire-shape migration). **Semantics:**
append-only — once written, a `FileModification` is immutable; merge across
devices is union + HLC sort.

This proposal depends on [`core-chain-sort.md`](core-chain-sort.md) for the
`sortBy` chain step that makes the HomeScreen consumer query expressible (and,
per §3.3, for `sortBy` to accept a *property* selector). It is consumed by
[`drive-file-details-screen.md`](drive-file-details-screen.md), which renders
the history list. The two sibling proposals (this + chain-sort) are the
substantive ones; this is the larger.

---

## 1. Motivation

### 1.1 The HomeScreen recent-files spec

`HomeScreenViewModel.init` (`:drive-shared`) today lists the root folder and
dummy data. The actual product spec is *"the N files the user most recently
touched, in reverse chronological order, across all folders, across all the
user's devices"*. That sentence has three load-bearing pieces:

- **"most recently touched"** — needs a per-file timestamp that updates on
  every meaningful change. Today's `File.LAST_MODIFIED` is set at create time
  and never refreshed.
- **"across all folders"** — needs a chain query that crosses folder
  boundaries. `TenshuFilesRepository.getFiles()` is folder-scoped today.
- **"across all the user's devices"** — needs per-modification authorship
  (so "modified on iPhone" / "modified on Laptop" is sayable) and HLC ordering
  across devices that sync independently.

A single denormalised `lastModifiedAt` field would solve the first two
cheaply. The third — and the broader "what happened to this file?" UI we'll
want once Drive matures — requires modelling modifications as first-class
sync entities, not just an updated timestamp.

### 1.2 Why not just `lastModifiedAt`

We could ship only the denormalisation, defer history forever. Two reasons not to:

1. **`lastModifiedAt` without authorship is a lie on a multi-device account.**
   "Last modified at 14:32" — by which device, doing what? With sync, the
   wall-clock ordering observed on one device differs from another; only a
   device-attributed event log resolves it.
2. **The history UI is on the roadmap regardless.** Drive will grow a
   "version history" / "what changed" view for any non-trivial productivity
   story. Designing the modification model now means the HomeScreen query
   and the history UI share one source of truth instead of accreting two.

The denormalised `lastModifiedAt` is included in this proposal as a derived
optimisation (§5), kept consistent by the same emit-side hooks that write
modifications.

---

## 2. Modification taxonomy

### 2.1 Survey of filesystem precedent

| Source                | Captures                                                                                                 |
|-----------------------|----------------------------------------------------------------------------------------------------------|
| Unix `stat`           | `mtime` (content), `ctime` (inode/metadata), `atime` (access, usually disabled)                          |
| macOS / modern Linux  | adds `birthtime`/`btime` (true creation)                                                                 |
| Windows NTFS USN      | Create, Delete, Rename (old + new name), DataExtend/Overwrite/Truncate, BasicInfoChange, SecurityChange, ReparsePointChange, StreamChange, EncryptionChange |
| iCloud / Google Drive | Created, Modified (content), Renamed, Moved, Shared, Trashed, Restored, Viewed                          |

Most of these collapse for Tenshu's purposes: Unix `mtime`/`ctime` split
matters at inode level, not at the user-visible "the file changed" level;
NTFS USN's DataExtend/Overwrite/Truncate are all "content changed" from a
sync perspective because the blob channel sees a new manifest either way.

### 2.2 v1 set

Four concrete subtypes for the first cut:

- **`CreatedFileModification`** — the first record for any file. Emitted by
  `CreateFile.execute`. Has no payload beyond the common fields.
- **`ContentChangedFileModification`** — emitted whenever a new file content
  manifest is written. Carries the new `manifestId` (or `contentRef`) so the
  history UI can later support "restore to this version" when blob retention
  allows.
- **`RenamedFileModification(oldName, newName)`** — emitted by `RenameFile`.
  Carries the two names so the UI can render *"renamed `foo.txt` to
  `bar.txt`"* without joining against historical state.
- **`MovedFileModification(oldParentPath, newParentPath)`** — emitted by
  `MoveFile`. Distinct from rename because the parent-folder relation
  changes; semantically and UX-wise it's "moved to a different folder", not
  "renamed". Splitting them at emit time means the UI doesn't have to
  reverse-engineer intent from a path diff.

### 2.3 Deferred / future

Each waits on a capability that doesn't exist yet; reserving the type name
now so consumers don't have to migrate.

- **`SharingChangedFileModification`** — when sharing / cross-realm
  membership lands. Today every file is single-realm.
- **`TrashedFileModification`** / **`RestoredFileModification`** — when trash
  exists. Today delete is direct tombstone; there's no in-between state.
- **`AccessedFileModification`** — explicitly **not v1**. High write volume
  (every open emits) plus the most privacy-sensitive event (the relay would
  see a sync block per file-open). If we ever want it, it should be
  **local-only** (skip sync) and **opt-in** in user settings. Flag in §9 for
  the future-Tenshu-product decision.
- **`PermissionChangedFileModification`** — when per-file permissions exist.
  Not on the roadmap.

### 2.4 Common fields (the abstract super-composite)

```kotlin
abstract class FileModification : Composite {
    val fileId: FilePath           // parent relation; keyed for collection lookup
    val timestamp: Instant         // HLC at emit time (authoring device's clock)
    val deviceId: DeviceId         // authoring device — drives "modified on Phone"
    // userId: deferred until shared realms exist
}
```

`timestamp` is HLC, not wall-clock — multi-device ordering is the whole point.
`deviceId` is the authoring device's stable ID from the device-management
landed work; the UI joins against the `Device` composite for display name.

---

## 3. Storage model

### 3.1 Top-level collection, not an inline field

The natural temptation is `File.history: TList<FileModification>` inline on the
File composite. **Reject** — but for relation-modelling reasons, not the
materialisation-cost argument an earlier draft of this section leaned on.

**The materialisation argument doesn't hold, and it's worth being precise
about why.** An inline `TList<FileModification>` field on a `File` entity does
*not* eagerly load — the deserializer hands list fields back as an
`ArrayDatabaseReference` (lazy) when the parent loads as an entity
(`TenshuProtocolObjectDeserializer.deSerialize:81–109`). So "sort all files by
`history.last()`" would not, in itself, force every file's history list to walk.
The genuine read-cost problem lives one layer down and is *orthogonal* to this
decision: the deserializer recurses **eagerly into composite fields and
composite relations to indefinite depth, and reads are projection-blind** —
see [`tech-debt.md`](../tech-debt.md) §"Database Deserializer: Eager Deep
Recursion + Projection-Blind Reads". That cost is paid whether history is
inline or a separate collection, and the right fix is the projection-pushdown
work in that entry — not the storage shape here.

What actually decides it:

1. **History is independently queryable as its own entity stream.** "Most
   recently modified across all files" is naturally
   `allModifications.sortByDescending { it.timestamp }.take(20)` over a
   top-level collection, then resolve parent files via the relation. Modelling
   modifications as owned children of `File` makes the cross-file query a
   reverse-relation traversal instead of a first-class list — workable, but
   inside-out for the primary use case.
2. **Append-only merge is cleaner on independent entities.** A collection of
   immutable `FileModification` entities merges as union + HLC sort (§4.2). An
   inline sub-list would fold history merging into `File`'s own semantic
   merge, coupling two unrelated merge concerns.
3. **Composes with the landed relation system.** `ServiceDataRelation` +
   `ChainTemplate` (the work that finished in 2026-05-07) is the right
   primitive for a "parent → list of owned children" relation.

The `lastModifiedAt` property (§3.3) is what the HomeScreen sort actually
targets, and it wins regardless of inline-vs-collection — it avoids touching
history at all for the common "order by recency" case.

So:

```kotlin
interface FileModification : Composite {
    companion object {
        const val ID = "tenshu/file/modification"
        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                FileModificationDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
                AppendOnly.Schema,             // NEW — see §4.2
            ),
            fields = {
                string(id = Field.FILE_ID, type = FilePath.Schema)
                int(id = Field.TIMESTAMP, type = UnixInstant.Schema)
                string(id = Field.DEVICE_ID, type = DeviceId.Schema)
            }
        )
    }
}
```

Concrete subtypes inherit from `FileModification.Schema` and add their own
fields (e.g. `RenamedFileModification` adds `OLD_NAME`/`NEW_NAME`). This is
the standard composite-hierarchy pattern used by e.g.
[`core-preference-service.md`](core-preference-service.md)'s `Setting<V>`.

**Open question.** Should the abstract `FileModification` be marked
`RealmData` (per the not-yet-started
[`core-realm-data.md`](core-realm-data.md)) so Control's "Data" section can
show "Modifications" as a top-level entity type? Probably yes once Realm Data
lands; flag for that proposal's implementer.

### 3.2 Parent relation: `File ↔ FileModification`

```kotlin
val File.Relation.History: ServiceDataRelation = serviceDataRelation(
    id = "${File.ID}/history",
    receiver = File.Schema,
    target = FileModification.Schema,
    // join FileModification.FILE_ID == File.PATH
    join = JoinSpec(FileModification.Field.FILE_ID, File.Field.PATH),
)
```

Chain DSL access from the file side: `file.select { relations(File.Relation.History) }`
materialises the file's modifications as `TList<Entity<FileModification>>`.
Chain DSL access from the modification side (for HomeScreen):
`allModifications.sortByDescending { it.timestamp }.take(20).select { relations(toFile) }`.

The reverse relation's exact shape depends on whether the relation rework's
**§3 entity-aware chain-on-schema** path supports this kind of reverse join
today; this is a chain-DSL-side open question (§9).

### 3.3 `size` + `lastModifiedAt` become properties — **LANDED (Phase 1b)**

**Status (2026-05-22):** landed. `File` fields are now just `[path]`; `size` and
`lastModifiedAt` are op-written **value properties** (the static `Property` handle
kind — [ADR 0013](../adr/0013-property-authoring-unification.md)). The `createdAt`
field discussed in earlier drafts was **dropped** — see "What landed" below.

**Principle:** fields are the static, essential data that identifies a thing;
mutable/recomputed data belongs in properties.

- `path` — identity (the `FileDataIdProvider` field). Stays a **field**.
- `lastModified` — correctly *named* (the modified date file browsers show); the
  implementation never kept it current. Becomes the **`lastModifiedAt` value
  property** (op-written by every write).
- `size` — grows/shrinks; becomes a **value property** (`0L` for folders,
  byte length for files).

#### What landed (vs. the sketch)

- **`createdAt` dropped, then re-introduced (2026-05-24) as a value property.**
  It was dropped originally (no consumer; self-referential fallbacks). The
  FileDetailsScreen "Created" row is now the consumer, so `File.CreatedAt` is back
  — but as an **op-written value property** (mirroring `Size` / `LastModifiedAt`),
  **not** the immutable *field* this sketch proposed. Rationale: it keeps the
  authoring surface uniform with the other metadata and avoids changing
  `File.create`'s signature at every call site. Immutability is enforced at the
  write sites instead: stamped once on creation (`UploadFile` new / `CreateFolder`
  / root init), preserved on overwrite (read back via
  `ExecutionContext.resolveProperty` — value properties aren't inline on the
  stored entity, so `entity.get(…)` returns null there), preserved on move
  (`include(sourceProps)`), and kept as the **earliest** of the two sides in
  `FolderMerger`. Tests: `FileTimestampRoundTripTest` (wire round-trip) +
  `FileCreatedAtTest` (stamp + overwrite-preserve through the client chain).
- **Op-written, not derived.** `UploadFile` / `CreateFolder` / `Move` /
  `FolderMerger` build entities via `File.entity(…)` / `Folder.entity(…)`, which
  attach the `size` + `lastModifiedAt` value properties. Derivation
  (`lastModifiedAt = max(history)`, `size` from the manifest) remains the
  deferred follow-up (§9.6/§9.7).
- **Entity-only accessors.** `Entity<File>.fileSize` / `lastModifiedAt` are
  nullable `getProperty(…)` reads; the plain-composite accessors are gone
  (properties live on entities). `FileMapper` reads them null-safely;
  `TenshuFilesRepository` + `select { relations(File.Relation.Size,
  File.Relation.LastModifiedAt) }` projects them (they resolve from the **stored**
  entity, so the entity must be persisted before projection).
- **`FolderMerger`** reads/writes them as properties; the per-path conflict
  tie-break falls back to `0` for property-less composite children (production
  folder children are entities that carry the property).
- **No DB migration framework exists**, so this is a **pre-release schema
  change** (existing local data won't deserialize under the new field count — a
  local reset is required). A real migration is out of scope for pre-release.
- **Legacy binary tests deleted.** `LegacyFileServiceBinaryParser/SerializerTest`
  re-exercised the core codec (covered by core-common's
  `BinaryParser`/`BinarySerializerTest`) with File fixtures via brittle
  byte-level assertions; removed rather than rewritten.
- **Android variants unverified** (no Android SDK in the build env);
  `:file-service-proxy:androidHostTest/AndroidFileUploadOperationTest` updated for
  the new API but not run.

After the change:

```kotlin
// File.kt — fields (static, essential)
fields = {
    string(id = Field.PATH, type = FilePath.Schema)
    int(id = Field.CREATED_AT, type = UnixInstant.Schema)   // NEW, immutable
}

// File.kt — properties (mutable), alongside Data / Preview / Category / History
val File.Relation.Size: …           // TInteger
val File.Relation.LastModifiedAt: … // TInstant
```

**`sortBy` must accept a property selector, not just a field selector.** The
HomeScreen query sorts on `lastModifiedAt`, now a property:
`sortByDescending { it.lastModifiedAt }`. Cross-proposal dependency on
[`core-chain-sort.md`](core-chain-sort.md) — tracked there (§6) and here (§9).

#### Where the value comes from — derived, not separately stored

The earlier draft recommended "store + update on write". That distinction is
weaker than it looked, and the better framing is **derived**:

- Both quantities are already *computed somewhere* today — imperatively, inside
  the write ops (`UploadFileImplementation` sets `size` + `lastModified`,
  `CreateFolderImplementation` sets `size = 0L` + `lastModified = now`). There
  is no independent state; the value is a function of other data the op already
  has.
- After history lands, the same is true on the **sync** path: a remote
  modification appends a `FileModification` (synced, append-only) and updates
  the file's content manifest (synced). So:
  - `lastModifiedAt` = `max(history.timestamps)` — a function of the synced
    history.
  - `size` = total content length, available from the synced **manifest** (sum
    of chunk lengths — see [ADR 0017](../adr/0017-file-content-two-plane-channel.md))
    without the content bytes; for a folder, an aggregate over children.

Because the source data is synced either way, a *separately stored* copy is
redundant and adds a consistency burden (§4.3). The cleaner shape is a
**materialised dynamic property** — derived via a chain, cached per
[`docs/agent.md`](../agent.md) §17 / House-Rule #6 (`SchemaRegistry`-owned
per-context materialisation), recomputed when its source changes. This makes
history (for `lastModifiedAt`) and the manifest (for `size`) the single sources
of truth and dissolves §4.3 entirely.

What it costs / needs (so this is an honest recommendation, not a free lunch):

- **Aggregate chain ops we don't have yet.** `max` over `history.timestamps`
  is expressible as `history.sortByDescending { it.timestamp }.first()` once
  `core-chain-sort` lands; **`size` needs a reduce/sum** over manifest chunk
  lengths (or a dedicated computation), which is not in any current proposal.
- **Folder vs file size differ** (child aggregate vs manifest sum) — two chain
  shapes behind one property.
- **`Syncable(Eager)` on the property** still caches the materialised value for
  cheap reads/sorts; the difference from "stored" is that the cache is a
  derivation, not authoritative state a writer must remember to update.

**Recommendation:** model both as **materialised (derived) properties**, with
the aggregate-chain-op gap for `size` tracked as a sub-dependency (§9). Fall
back to op-written values only if the derivation machinery proves too heavy —
but per the reasoning above, the computation has to live somewhere regardless,
and centralising it in a derivation beats scattering it across every write op
+ merger.

**Schema-DSL question (§9).** Whether the value/manifest-derived property reuses
`dynamicPropertyRelation` (chain-computed, like `Data` / `Preview`) or needs a
new builder is a Phase-1 detail.

### 3.4 Two "modified" dates — `ContentModifiedAt` beside `LastModifiedAt` — **LANDED (FileService v17, 2026-10-10)**

**What was wrong.** `LastModifiedAt` is op-written `now` by every write, so it is "when this row
was last touched in the realm". That is the right key for the recent feed (§5.1) and the wrong
answer to "when was this file modified": a library backed up in a day read as modified that day —
in Finder's *Date Modified* column, in the details screen, on the web. Every sync service keeps
the two apart (OneDrive: `lastModifiedDateTime` against `fileSystemInfo.lastModifiedDateTime`;
Dropbox: `server_modified` / `client_modified`).

**What landed.** A second stored value property, **`File.ContentModifiedAt`**: the file's own
modification time, taken from the source on upload (`SOURCE_MODIFIED_AT`, the parameter that
already seeds `CreatedAt`) or `now` where the uploader could not read one.

| | `CreatedAt` | `ContentModifiedAt` | `LastModifiedAt` |
|---|---|---|---|
| new file | source date, else now | source date, else now | now |
| overwrite | unchanged | new source's date, else now | now |
| move / rename | unchanged | unchanged | now |
| folder | now | **absent** | now |

Readers that show a "Modified" date use it — the Finder bridge (`ControlFileBridge`), the details
screen (`FileMapper.mapDetails`), the web row (`FileRowJs.lastModified`). The recent feed still
sorts on `LastModifiedAt`.

**Why a new property and not a redefinition of `LastModifiedAt`.** Redefining it would leave every
block already in the relay's history carrying the old meaning under the same id, and this
proposal's own model (§3.3, §4.3) has `lastModifiedAt = max(history)` — realm time by design. A new
id cannot be misread: an old block simply does not have it.

**Absent means "predates the property", and readers fall back to `CreatedAt`.** One function
spells the fallback, `contentModifiedAtOrCreated`. It is exact for a file that was never
overwritten, since both were seeded from the same source timestamp, and it is the oldest of the
candidates for one that was. ⚠ **Deliberately no backfill in the v17 step.** A copy made at the
store seam would exist on the devices that migrated and on none that replay the relay's history
(a newly paired device, a wiped one), so the realm would stop agreeing with itself about the same
row. The `FileSliceEntry` projection is unchanged for the same reason it can be: a provisional row
built from a slice has no content date and reads through the fallback until the real row lands.

**Open — written down rather than implied:**

- ~~Sorting by "Modified" still orders by `LastModifiedAt`.~~ **Closed 2026-10-10:**
  `FileSortKey.MODIFIED` orders by `ContentModifiedAt`, and the list row shows the same date
  (`FileMapper.map`), so a listing is ordered by the date it displays. A row with no slot has no
  key: it sorts after every dated row ascending and before them descending, and among
  its own kind by `LastModifiedAt`. That is every folder, whose "Modified" date is exactly that
  one. It would also be every pre-v17 file of a realm nobody filled: grouped apart from the dated
  files and ordered by upload time, as before this change. A coalesced key would interleave them
  but is a derived key, which does not fuse (`core-ordered-window-fusion.md` §8) and drops every
  sorted page to a full sort.
  ⚠ **The rows written before v17 were filled once, by hand, and that code is not in the repo.**
  A patched desktop Control walked every `File` row without the slot and saved it with
  `ContentModifiedAt = CreatedAt` as an ordinary local write (1,124 rows on the maintainer's
  realm, 2026-10-10), so the value reaches every device and every later joiner through sync —
  the synced mechanism the paragraph above asks for, run from one device instead of shipped to
  all. ⚠⚠ What that run showed and a later one must respect: **an entity save replaces the
  peer's whole property set** — a row re-saved without a slot loses it everywhere — so such a
  fill must run on a device that is caught up, and must carry every property across
  (`include(props)`, as `MoveFile` does). A realm created after v17 never needs it.
- **Every overwrite stamps `now`**: none of the four replace ops (`ReplaceFile`,
  `ReplaceSourceFile` and their `Bg` twins) has a source-date parameter. Correct for a save from
  an editor, wrong for an older file copied over a newer one — from any platform's Drive, or from
  Finder (`modifyContents`).
- **Folders have no content date.** Finder shows a folder's `LastModifiedAt`.

---

## 4. Sync semantics

### 4.1 Emit-side hooks

**Status (2026-05-23): LANDED (Phase 2).** Every File write op stamps the
authoring device + timestamp onto the right `FileModification` subtype and
saves it via `executionContext.saveData`, which fires the outbound
`SyncObserver` (one co-emitted `SyncBlock`) and the new
`FileModificationRelationMaintainer`. See "What landed" below.

Every File write operation gains an emit-side hook that writes a
modification block in the same `SyncBlock` as the file change. List of write
sites (audited from `:file-service-proxy`):

| Op                            | Modification emitted                          |
|-------------------------------|------------------------------------------------|
| `CreateFile.execute`          | `CreatedFileModification`                     |
| `WriteFileContent` (the blob-channel-aware path) | `ContentChangedFileModification` |
| `RenameFile.execute`          | `RenamedFileModification(old, new)`           |
| `MoveFile.execute`            | `MovedFileModification(oldParent, newParent)` |
| `DeleteFile.execute`          | *none* — tombstone supersedes; history dies with the file (§4.4) |

Single-transaction guarantee: the modification block and the file mutation
land in the same outbound `SyncBlock`. If the underlying write fails
mid-flight, neither lands. (Today's outbound assembly already groups
co-emitted blocks; this just reuses that.)

Under the derived-property model (§3.3), the write op does **not** separately
write `size` / `lastModifiedAt`: appending the `FileModification` and updating
the content manifest *are* the source-of-truth changes, and the materialised
properties recompute from them. (If the op-written fallback is chosen instead,
the op updates both properties in the same `SyncBlock` and §4.3 applies.)

#### What landed (Phase 2) — vs. the sketch

- **No separate `CreateFile` / `WriteFileContent` ops exist.** `UploadFile`
  is both: a brand-new file records `Created`; an in-place overwrite (the same
  core via `ReplaceFile`, `replaceExisting = true`) records `ContentChanged`.
  The op decides by reading `database.getData(dataId)` *before* the write.
  ⚠ An overwrite with the bytes the file already holds (same `ContentHash`, same bytes in the local store)
  is a no-op and records **nothing** (2026-09-15, `tech-debt.md` *A replace skipped the quota gate*),
  so `ContentChanged` rows are never a count of saves.
- **`RenameFile` doesn't exist as an op** — rename is `MoveFile` within the
  same parent. The hook lives in `MoveFileImplementation` and splits at emit
  time: `oldParentPath == newParentPath` → `Renamed(oldName, newName)`, else
  → `Moved(oldParentPath, newParentPath)` (§9.11). The record is keyed to the
  file's **new** path identity (`targetPath`); recursive folder-child moves
  each emit their own `Moved` (every child's parent path changes).
- **`ContentChanged.manifestId` carries the file path, not the real
  manifest/content ref.** `FileContentSyncEmitter.emitFileContent` returns
  only a `Boolean` today; threading the hash-of-ciphertext ref is deferred
  until "restore to version" (§2.2) actually consumes it — no consumer, no
  speculative plumbing.
- **Realm-wide collection, not a custom merger.** `FileModification`s land in
  a new `FileService.Relations.MODIFICATIONS` `serviceDataRelation`
  (`TList<FileModification>`), populated by `FileModificationRelationMaintainer`
  (a `DataObserver`, registered in `initFileService()`) on every local **or
  sync-arriving** modification save — the exact `NotificationRelationMaintainer`
  pattern. `AppendOnly` (§4.2) needs **no** semantic merger: each row is keyed
  by a globally-unique `compositeKey = fileId|deviceId|timestamp`, so distinct
  devices' rows never collide and the union is just "every received row, once".
- **`deviceId` threaded via the proxy factory** (`SyncManager.currentDeviceId`,
  wired in both Control `PlatformProxyModule`s + the Android upload/replace
  path). Nullable: a `null` deviceId (test harnesses / pre-sync wirings) makes
  the emit a silent no-op, mirroring the nullable `syncBlockEmitterHolder` /
  `tombstoneEmitter`.
- **Derivation still deferred (§9.6/§9.7).** `size` / `lastModifiedAt` remain
  op-written value properties (Phase 1b); deriving `lastModifiedAt =
  max(history)` waits on the aggregate chain ops. So this phase appends
  history; it does not yet recompute the denormalised properties from it.
- **Single-block guarantee is approximate.** The modification and the file
  mutation are *co-emitted* in the same drain (two blocks), not fused into one
  `SyncBlock` — reusing the existing outbound grouping, as the sketch noted.
- **Tombstone cascade (§4.4) is Phase 3, not here.** Deleting a file does not
  yet remove its modifications; that's the next slice.
- **Android variants updated but unverified** (no Android SDK in the build
  env): `UploadSourceFile/Replace` impls + factory thread `deviceId` too.
  `CreateFolder`/`MoveFile` go through the common factory on Android.
- **Tests:** `:file-service-proxy:jvmTest` `FileModificationEmitTest` (7) —
  one per subtype + the append-only union + the no-deviceId no-op. The
  cross-device union is the maintainer's job, already proven by the identical
  `NotificationSyncSystemTest` pattern; a dedicated paired-device history test
  (§7's `SyncSystemTest` extension) lands with the HomeScreen consumer (Phase 6),
  where a `deviceId` is wired into the system-test harness.

### 4.2 `AppendOnly` merge trait

New schema trait declaring that a composite's collection is append-only:
items can be added by any device, never modified, never removed except via
the parent tombstone cascade.

```kotlin
// :core/.../trait/AppendOnly.kt
interface AppendOnly : Trait {
    companion object {
        const val ID = "tenshu/core/trait/appendOnly"
        val Schema = trait(ID)
    }
}
```

Merge semantics for the modification collection are trivial under this trait:
each device's local store is the union of all `FileModification` blocks it
has received; HLC ordering across devices gives a deterministic global order.

This composes with the
[`sync-merge-strategies.md`](sync-merge-strategies.md) work that's currently
spec'd-but-not-started. Coordinate: this proposal either lands ahead of the
merge-strategies refactor (`AppendOnly` is a stand-alone trait), or after
(`AppendOnly` is registered as a built-in `MergeStrategy` subtype). Both
shapes work; sequencing TBD.

### 4.3 Property consistency under merge (op-written fallback only)

**This section applies only if the op-written fallback from §3.3 is chosen.**
Under the recommended **derived** model it dissolves: `lastModifiedAt` is a
function of `history` (which merges by §4.2) and `size` is a function of the
manifest (which syncs with the file), so there is no second copy to keep
consistent. The rest of this section documents the fallback's merge config.

When two devices independently modify the same file:

- Device A writes `ContentChangedFileModification` at HLC `tA` and sets the
  `lastModifiedAt` property `= tA`.
- Device B writes `ContentChangedFileModification` at HLC `tB > tA` and sets
  `lastModifiedAt = tB`.
- Device A receives B's block. The `File` semantic merger sees two
  `lastModifiedAt` values; takes the max. Result: `lastModifiedAt = tB`.
  `history` collection: union, sorted by HLC.

This works because `lastModifiedAt` merges as `Max(tiebreaker = HLC)` (per
[`sync-merge-strategies.md`](sync-merge-strategies.md) primitives). `size`
merges as `LastWriteWins(tiebreaker = HLC)` — it tracks the latest write, not
a max. Document the merge config on each property.

The invariant — `lastModifiedAt == max(file.history.map { it.timestamp })`
— holds eventually, not instantaneously. A devtools-mode consistency check
in `:file-service-common` tests verifies it post-merge; production code
trusts the invariant.

### 4.4 Tombstone cascade

**Status (2026-05-23): LANDED (Phase 3).** See "What landed" below.

When `DeleteFile` writes a tombstone for `File("/foo.txt")`:

- The tombstone applier removes the `File` entity.
- All `FileModification` entities with `fileId == "/foo.txt"` are also
  removed in the same applier step.
- No separate "delete history" event — the tombstone covers both.

This means deleted files don't have ghost history. If we later add trash,
`TrashedFileModification` is a regular modification that preserves history;
only `DeleteFile` (permanent delete) cascades.

The cascade lives in the tombstone applier ([`sync/design.md`](../sync/design.md)
§2.6) and is implemented by extending the applier's per-type cleanup map.

#### What landed (Phase 3) — vs. the sketch

- **Implemented as a `DataObserver`, not a "per-type cleanup map" in the
  generic applier.** There is no such map: deletes (local + applied) funnel
  through `executionContext.deleteData(dataId)`, which fans out to the
  registered `DataObserver`s. `:sync-service-proxy`'s `processDeleteBlock` is
  generic and cannot reference `FileModification`, so the cascade lives where
  the file-specific knowledge does — folded into
  `FileModificationRelationMaintainer.onDataDeleted` (`:file-service-proxy`).
  This is the same layering as `ParentFilesRelationMaintainer`.
- **One observer, two delete triggers.** `onDataDeleted` now branches on the
  deleted id's type: a `FileModification` row → drop its `MODIFICATIONS`
  membership (Phase 2); a `File` (file *or* folder) → scan the log and
  `deleteData` every row whose `fileId` matches. The nested `deleteData`
  re-enters the same observer's `FileModification` branch to clean membership —
  safe because `ObserverRegistry` is snapshot-then-iterate.
- **Covers both planes from one tombstone.** Host: `DeleteFileImplementation`'s
  recursive `deleteData` fires the observer for the folder + every descendant.
  Joiner: `processDeleteBlock` applies the file's single tombstone via
  `deleteData`, firing the same observer. So history dies with the file on each
  device independently — no per-modification tombstone is emitted
  (`SyncObserver.onDataDeleted` is a no-op), exactly as §4.4 intends.
- **No threat-model change.** The cascade only *removes* local data and emits
  no new wire shape, so House Rule #2 doesn't apply (Phase 2's T-R-10 already
  covers the modification blocks themselves).
- **Tests:** `:file-service-proxy:jvmTest` `FileModificationEmitTest` gains
  `deleteFile_cascadeRemovesItsHistory` + `deleteFolder_cascadeRemovesHistoryForFolderAndChildren`.
  The joiner path reuses the identical `deleteData` → observer mechanism, so a
  dedicated paired-device cascade test rides with Phase 6's `SyncSystemTest`
  extension.

### 4.5 Threat model

The relay sees one new sync block per file modification. T-R-XX amendment:

> **T-R-XX (new):** The relay observes a sync block per
> `FileModification.Eager` emit. Timing of these blocks leaks the file's
> per-modification cadence to a passive relay observer. The block payload is
> encrypted under the realm's keys; only the timing/count is in the clear.
>
> Residual: this matches T-R-09 (per-open file-content fetch timing) in
> shape. Mitigations (block batching, decoy emits, padding) are the same
> set spec'd for the blob channel and not built in v1. If T-R-09's residual
> becomes unacceptable, the file-modification cadence joins the same
> mitigation track.

`AccessedFileModification` would substantially worsen this and is therefore
**not** in v1 (§2.3).

### 4.6 Retention policy

**Status (2026-05-23): LANDED (Phase 5).** See "What landed" below.

Unbounded history is a sync-cost footgun. Cap proposal:

- **Per-file cap:** keep the most recent N modifications per file, where N
  defaults to 50. Older modifications are tombstoned by the emit-side
  hook of the **51st**-and-later modification (i.e., emit hook checks
  `history.size`, tombstones the oldest if at cap).
- **Time cap:** no time-based GC in v1. (Filesystems typically don't
  time-bound history either.)
- **`CreatedFileModification` is never GC'd** — pinned as the floor record
  for "when did this file first exist on the realm".

The cap is configurable per-app via the
[`core-preference-service.md`](core-preference-service.md)
preference service once it lands — until then, the constant lives in
`FileModificationSchema`.

This is the same shape as the blob channel's per-content retention policy
([ADR 0017](../adr/0017-file-content-two-plane-channel.md) Decision 6) and composes
similarly with the tombstone GC.

#### What landed (Phase 5) — vs. the sketch

- **`FileModification.RETENTION_CAP = 50`** lives on the companion (not a
  separate `FileModificationSchema`); the preference-service hook is future work.
- **Trim lives in `FileModificationRelationMaintainer.onDataSaved`**, not in each
  op's emit hook. The maintainer already owns the `MODIFICATIONS` log lifecycle
  (membership + cascade), so retention is one more responsibility there — every
  emit path gets it for free, no per-op plumbing. When a save pushes a file's
  history over the cap, the oldest non-`Created` rows are trimmed.
- **Only `WriteOrigin.USER` saves trim.** The originating device deletes the
  over-cap rows locally **and** emits a per-row `DeleteBlock` (the existing
  tombstone surface — `targetEntityType = FileModification.ID`); peers drop the
  same rows when that tombstone arrives, rather than re-trimming on ingest
  (which would double the tombstone traffic). `TombstoneRegistry` already indexes
  every saved entity's id-hash, so a peer resolves + deletes the tombstoned row.
  This makes the cap **globally consistent** and lets the relay GC the dropped
  blocks (composes with the tombstone GC, as the sketch intended).
- **The emitter is read lazily** via a `TombstoneEmitterHolder` threaded into
  `initFileService(...)` (wired in both Control `PlatformProxyModule`s). `null`
  (test / pre-sync) still trims the **local** store — only cross-device
  propagation is skipped.
- **Same-millisecond caveat (carried from Phase 2).** `compositeKey` is
  `fileId|deviceId|timestamp` at millisecond granularity, so two writes to one
  file within the same ms on one device collapse onto one row (no HLC logical
  counter in the key yet). Real usage rarely hits this; the retention **test**
  injects a `FakeClock` it advances per write to force distinct rows.
- **Tests:** `:file-service-proxy:jvmTest` `FileModificationEmitTest.retention_capsHistoryPerFile_keepingCreated`
  (cap holds at 50; `Created` preserved; per-file). Cross-device convergence of
  the trim rides Phase 6's `SyncSystemTest` extension.
- **No new threat-model entry** — retention reuses the `DeleteBlock` surface; the
  T-R-10 note records the bounded delete cadence.

---

## 5. Consumers

### 5.1 HomeScreen recent files

**Status (2026-05-23): LANDED (Phase 6, data + ViewModel).** See "What landed"
below.

```kotlin
// :drive-shared/.../TenshuFilesRepository.kt
override fun recentFiles(limit: Int): Flow<List<FileModel>> =
    client.fileService.defaultFileSystem.root
        .files
        .traverse()                              // every file/folder in the drive
        .sortByDescending(File.LastModifiedAt)   // value-property sort
        .take(limit)
        .select {
            fields(File.Field.PATH, Folder.Fields.FILES)
            relations(File.Relation.Category, File.Size.projection, File.LastModifiedAt.projection)
        }
        .flow()
        .map { it.fold(onSuccess = { f -> f.toList().map(fileMapper::map) }, onFailure = { emptyList() }) }
```

```kotlin
// :drive-shared/.../HomeScreenViewModel.kt
init {
    viewModelScope.launch {
        filesRepository.recentFiles().collect { files ->
            _uiState.update { it.copy(recentFiles = files.map(FileModel::toUiModel)) }
        }
    }
}
```

#### What landed (Phase 6) — vs. the sketch

- **`allFiles` was a fiction — there is no flat all-files collection.** Files
  live in per-folder `Folder.Fields.FILES` arrays. The fix is the new
  **`traverse()` chain op** (`core/.../collection/list/Traverse.kt`): a recursive
  depth-first walk of nested lists returning every node flattened — the same
  `walk` `searchAll` already does, extracted to `recursivelyWalk` and shared,
  minus the predicate. So `root.files.traverse()` *is* `allFiles`.
- **The sort is by the value-property handle**, not a lambda property selector:
  `sortByDescending(File.LastModifiedAt)` (the `ValueProperty<T>` + `sortBy(property)`
  handle), which sidesteps the entity-only-accessor typing problem the
  original `{ it.lastModifiedAt }` sketch had.
- **`recentFiles` lives on `FilesRepository`** (drive-shared), not a
  `FileService` client extension — it returns `Flow<List<FileModel>>` and reuses
  the existing `FileMapper`, matching `getFiles`.
- **Cost:** `traverse()` is a full-tree walk per emit (loads every file). Fine for
  v1 / normal drives; the cheaper top-N options (a maintained flat collection, or
  the modification log + reverse relation) are noted in `Traverse.kt`'s KDoc as
  the optimisation if this gets hot. No `createdAt` (still dropped, §3.3).
- **ViewModel + render wired and tested.** `HomeScreenViewModel` injects
  `FilesRepository`, collects `recentFiles()` into `uiState.recentFiles`
  (`FileModel::toUiModel`), and the dummy `searchBarFiles` is dropped. Verified:
  `:drive-shared:desktopTest` `RecentFilesIntegrationTest` (the real
  traverse→sort→take→select chain across nested folders, via the in-process
  proxy) + `HomeScreenTest` (Compose render of the "Recent Files" list from the
  `recentFiles` stream, incl. a live-update reflection — mirrors Control's
  `HomeScreenTest`).
- **Cross-device `SyncSystemTest` extension** (proposal §7) still deferred.

### 5.2 FileDetailsScreen

The per-file history (`File.Relation.History`) plus the `size` / `createdAt` /
`lastModifiedAt` metadata feed a new **FileDetailsScreen** in `:drive-shared`
— reached via the "Details & Activity" row in the file-actions sheet and the
three-dots in the image preview. That screen is specified separately in
[`drive-file-details-screen.md`](drive-file-details-screen.md); it is the
primary consumer of the full history list (HomeScreen only needs
`lastModifiedAt`). The two proposals are sequenced: file-service-history lands
the data, FileDetailsScreen consumes it.

---

## 6. Phases

1. **Schema additions, no behavior — LANDED** (1a, 1b):
   `FileModification` super-composite + four v1 subtypes in `:file-service`;
   the `File` field→property migration (§3.3) — `size` + `lastModifiedAt`
   become op-written value properties. `createdAt` dropped (no consumer).
   `AppendOnly` trait in `:core`.
2. **Emit hooks — LANDED (2026-05-23).** `UploadFile` (Created/ContentChanged),
   `CreateFolder` (Created), `MoveFile` (Renamed/Moved) in `:file-service-proxy`
   emit modifications into the new `FileService.Relations.MODIFICATIONS`
   collection via `FileModificationRelationMaintainer`. See §4.1 "What landed".
   **Derivation deferred** (§9.6/§9.7): `size`/`lastModifiedAt` stay op-written
   (Phase 1b) until the aggregate chain ops exist — not gating this phase.
3. **Tombstone cascade — LANDED (2026-05-23).** Deleting a file/folder removes
   its history (+ `MODIFICATIONS` membership) on every device, via
   `FileModificationRelationMaintainer.onDataDeleted` (a `DataObserver`, not a
   generic-applier map). See §4.4 "What landed". No threat-model change (no new
   wire shape).
4. **Threat-model amendment — LANDED (2026-05-23, with Phase 2):** T-R-10
   (modification cadence) in [`threat-model.md`](../sync/threat-model.md) §8.3.
5. **Retention cap — LANDED (2026-05-23).** Per-file N=50 trim in
   `FileModificationRelationMaintainer.onDataSaved` (USER-origin only): local
   delete + per-row `DeleteBlock` so all devices converge and the relay GCs the
   blocks; `Created` is the pinned floor. See §4.6 "What landed".
6. **HomeScreen consumer — LANDED (2026-05-23, data + ViewModel).**
   `FilesRepository.recentFiles(limit)` = `root.files.traverse().sortByDescending(File.LastModifiedAt).take(limit).select{…}`
   (new `traverse()` chain op + the `sortBy(property)` value-property sort) + the
   `HomeScreenViewModel` migration. See §5.1 "What landed". Verified incl. a
   `HomeScreenTest` Compose render. **The typed `fileService.modifications`
   client accessor + a per-file accessor LANDED (2026-05-24)** for the
   FileDetailsScreen activity section: `FileService.modifications: TList<FileModification>`
   (serviceDataRelation accessor on `FileServiceRo`, mirroring
   `NotificationServiceRo.notifications`) + `FileService.modificationsForFile(filePath)`
   = `modifications.filter<FileModification> { fileId equalTo filePath }`. This is
   the **chain-filter** form, **not** the §3.2 `File.Relation.History` reverse-join
   relation (that's still blocked on reverse-join chain-DSL support, §9.4).
   Enabling `filter<FileModification>` required registering the abstract parent's
   `interfaceClass` + a `FileModificationDto` (class→typeId map, like
   `NotificationDto`) and a typed RO in `fileServiceClientModule` (so `filter`'s
   predicate-receiver cast succeeds — `search` falls back to the base `Data` RO,
   `filter` needs the exact-type RO). Test: `ModificationsForFileTest`. The
   cross-device `SyncSystemTest` (§7) remains for when its consumer lands.
7. **History UI** (future, not v1): per-file "what changed" view.
   Out of scope here; just confirming the data is available.

Phases are landing as **separate PRs** (the original "Phases 1–5 in one PR"
plan was superseded by the 1a/1b/2 split — each phase is independently
reviewable). Phase 6 lands once both this and the chain-sort proposal are
merged.

---

## 7. Test coverage

- **`FileModificationSchemaTest`** — composite hierarchy + trait wiring.
- **`FileModificationMergeTest`** (`:file-service-common`) — append-only
  merge across two simulated devices; HLC ordering; tombstone cascade.
- **`LastModifiedAtConsistencyTest`** — invariant check
  (`lastModifiedAt` property `== max(history.timestamps)`) under a randomised
  multi-device merge sequence (stored-property option only).
- **`FilePropertyMigrationTest`** — `size` / `lastModifiedAt` resolve as
  properties (not fields); `createdAt` resolves as the renamed field;
  existing wire data deserialises under the new shape.
- **`RecentFilesIntegrationTest`** (`:drive-shared:desktopTest`) — depends on
  chain-sort being landed; exercises the full chain through
  `FilesRepository.recentFiles`.
- **`SyncSystemTest` extension** — paired-devices test that modifies a file
  on device A, expects device B's recent-files stream to refresh with the
  new ordering.

---

## 8. PR / commit map (placeholder)

Single PR per phase grouping (Phases 1–5 in one PR; Phase 6 in a second PR
gated on chain-sort). Suggested branches:
`feat/file-modification-history` and `feat/drive-recent-files-consumer`.

---

## 9. Open design decisions

The proposal is implementable as written; these are the calls worth
explicit alignment on before kickoff.

1. **`AccessedFileModification` — never, opt-in, or local-only?**
   My recommendation: **never on sync, local-only opt-in** when the UX
   need arises. Surface this to the maintainer for a product call.
2. **`RealmData` annotation on `FileModification`.** Probably yes; gated on
   [`core-realm-data.md`](core-realm-data.md) landing. Sequencing.
3. **Sequence vs. `sync-merge-strategies.md`.** This proposal's
   `AppendOnly` trait is either (a) a stand-alone marker if merge-strategies
   isn't landed yet, or (b) a built-in `MergeStrategy` subtype if it is.
   Implementer picks the path based on order-of-landing.
4. **Reverse relation chain shape** — `allModifications.select { … toFile }`
   ergonomics depend on whether the relation rework's chain-on-schema path
   covers this kind of reverse join. If not, the HomeScreen consumer falls
   back to two chains joined client-side. Confirm before Phase 6.
   **Update (2026-05-25):** the single-entity direction (`modification → File`
   keyed by `fileId`) is now expressible as a `referenceProperty`
   ([`core-reference-property.md`](core-reference-property.md)) — the same
   primitive that landed `FileModification.Device`. What that primitive does
   *not* cover is the one-to-many `File → modifications` reverse (a collection,
   not a single foreign key); that still needs the chain-filter form (§5.1) or a
   genuine reverse-join.
5. **`File` field→property migration wire-compat (§3.3).** `size` and
   `lastModified` are fields today; they become properties, and a separate new
   `createdAt` field is added. Fields and properties are stored differently
   (`dbEntry.fields` vs `dbEntry.properties`), so this is a real wire-shape
   change, not a Kotlin-source rename. Need a one-shot read migration moving
   the old `size` / `lastModified` field cells into the new slots. Audit
   external consumers (web SDK schema-walker, Drive Android/desktop) for the old
   field ids. **`createdAt` seeding:** no creation time is stored today; seed it
   from the old `lastModified` cell (the best available proxy — it was set to
   `now` at create), or from the `CreatedFileModification` once history is
   populated.
6. **`size` / `lastModifiedAt` derived vs op-written (§3.3).** Recommended:
   **derived** — `lastModifiedAt` = `max(history.timestamps)`, `size` = sum over
   the content manifest (folder: child aggregate), materialised + cached per
   House-Rule #6; this dissolves §4.3. Fallback: op-written values kept current
   by every write + merger (then §4.3 applies). The recommendation has a real
   prerequisite — see #7.
7. **Aggregate chain ops for the derivation (§3.3).** `max` over history is
   `sortByDescending{…}.first()` once `core-chain-sort` lands; **`size` needs a
   `reduce`/`sum` chain op** that no current proposal covers, and folder-vs-file
   size are two chain shapes. Decide later: build the aggregate ops, or ship the
   op-written `size` fallback first and convert to derived when the ops exist.
   *(Decision deferred — flag, don't block.)*
8. **Mutable property DSL primitive (§3.3).** Does the derived property reuse
   `dynamicPropertyRelation` (chain-computed, like `Data` / `Preview`) or need a
   new builder? Settle in Phase 1.
9. **`sortBy` property selector (cross-proposal).** `sortByDescending
   { it.lastModifiedAt }` now sorts on a property, not a field.
   [`core-chain-sort.md`](core-chain-sort.md) must accept property references
   in the selector lambda — tracked there (§6) and here. Confirm before the
   HomeScreen consumer (Phase 6).
10. **Per-file cap N = 50.** Picked from gut feel. Better: drive from the
    first realistic history UI design — if the UI shows "last 90 days" then
    N is "90-days-equivalent for the median user". Defer to product.
11. **`MovedFileModification` vs. tombstone+create.** Tenshu's path-keyed
    files mean a "move" is sync-mechanically a delete + create at the new
    path. `MovedFileModification` is a semantic overlay on top of that
    mechanic. Verify that the emit hook can identify "this delete + this
    create is one logical move" cleanly — `MoveFile.execute` already does
    this for the operation, so the emit hook lives there too.

---

## 10. Non-goals

- **File versioning / restore-to-previous.** Modeling modifications enables
  future versioning, but full content-version retention is the blob-channel
  retention conversation, not this one.
- **Audit log for shared realms.** Future, when sharing exists. The
  `deviceId` field already covers single-user-multi-device; multi-user
  would add `userId` then.
- **Real-time collaborative edit history.** Tenshu's sync model is
  per-block, not per-edit; collaborative editing is a different proposal.
- **Spotlight / global search via modifications.** Modifications are an
  ordered event log, not an index. Search uses chain `searchAll`.
