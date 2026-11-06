# Photos app — screens, and what the platform still owes it

**Status:** v1.6 — decisions SETTLED (§5); **every phase BUILT** — PH-1…PH-8, S2, the shared
index and §24's Collections rename (§9–§25). **§25 is the phasing** — what to do next, and which
half of it is gated on no model at all.
⚠⚠ **§24.2 is the one to read before anything else in this doc.** Four Kotlin string templates
shipped with their `$` escaped by a shell heredoc, which Kotlin compiles silently — so every album
got one id, every trashed photo got one `/Trash` path, and the tab **crashed for any user with two
years of photographs**. Every test passed, because not one of them ever read an `id`.
⚠⚠ **§23: the feed's keyset window excluded its own cursor**, so photographs sharing a page-boundary
capture time were missing from the app entirely. ⚠⚠ **§22: `/Trash` was never created**, so PH-5's
trash had never once worked.
⚠⚠ §21: the viewer is a pager over the caller's list; its hero bytes live in the pages, not the view
model, and putting them back would make memory a function of how far the user swiped.
⚠ §24.4: the `__indexed` sentinel was unreachable from client code, so Photos' label search shipped
without the exclusion Mia has always had.
⚠⚠ §20.2: the drain must never subscribe to the shared index — it causes the changes, so a
500-photo pass would re-project the realm 500 times.
⚠⚠ §19 moved the backup engine out of Drive, 51 files across seven modules: **iOS is unverified**
(the gate refuses off macOS) and HR #8's smoke gate has not run, which makes it the change on this
track most likely to carry a break.
⚠⚠ §18.4: wiring S2 found that Photos' client graph registered **two of the
seven services the app calls**, so PH-3's Places, PH-4a's Settings and PH-6b's toggle had all been
reading services that were never registered — none of which crashed. A test now guards it.
⚠⚠ §17.1: PH-7 closed a failure that was **silent for months** — a KDoc's assumption about another
part of the system expired, and every iPhone photo landed with no coordinate. ⚠⚠ §17.6 records two
defects in *this document*: **S2 (Search) is unbuilt and was unowned**, having been deferred by PH-1
to a PH-3 that never claimed it. ⚠⚠ §16.5 records a bug the tests caught that would otherwise have
shipped silently — a mis-folded content hash makes the duplicate skip never fire, with no crash and
no error. ⚠ PH-4b —
moving the backup *engine* out of Drive so Photos can add a source — is named and not done (§14.4).
⚠⚠ §15.3 records the **third claim in this doc that the build falsified**: PH-G17's re-ingest loop
does not exist as described. ⚠⚠ §13.2
records why the **photo→`Place` geocoding hook (PH-G7) did not ship**: the geocoder seam lives in a
Timeline *app* module, so it is a platform move, not a Photos slice. ⚠⚠ §11.2 records a claim in this doc that the build **falsified**:
album membership cannot ride the `DataId` reference-migration primitive, and is keyed on
`File.ContentId` instead. §6 is the phase map, §7 the unlock table. Opened against
[`milestones.md`](../milestones.md) §Photos, whose dependency list was written when Drive was the
only app. Three of the four platform pieces it names have since arrived **by other routes**, so
this doc's first job is to say what is actually left. §3 proposes the screens, §4 is the gap list,
§5 the decisions the maintainer owes, §6 a phase map.

Sibling docs: [`photos-viewer-about-screen.md`](photos-viewer-about-screen.md) (the viewer's chrome,
the About screen, and the EXIF depth question — PH-9…PH-15),
[`timeline-photos.md`](timeline-photos.md) (the photo read ops, already built),
[`drive-synced-folders.md`](drive-synced-folders.md) + [`drive-synced-folders-phase2.md`](drive-synced-folders-phase2.md)
(how photos reach the realm), [`inference-service.md`](inference-service.md) (labels, semantic
search), [`place-geocoding.md`](place-geocoding.md) + [`place-roles.md`](place-roles.md) (Places),
[`drive-synced-thumbnails.md`](drive-synced-thumbnails.md) (the bytes behind a grid),
[`photos-video-support.md`](photos-video-support.md) (videos — the looping tile, the `MOTION` class),
[`retention-quota-model.md`](retention-quota-model.md) (what deletion means),
[`takeout-import-and-recall.md`](takeout-import-and-recall.md) (Google Photos import).

---

## 1. The sweep — Photos is no longer a services project

`milestones.md` §Photos scopes Photos as *"the first consumer of three new platform services"* —
media metadata indexing, Places (lite), and a generic search service — plus a background-ingest
story and per-platform gallery integration. Read today, that entry is **substantially stale in the
app's favour**, and one of its three services arrived in a shape it did not predict.

| Milestone dependency | Where it actually stands |
|---|---|
| *"`:file-service` extension for media metadata; schema additions for EXIF-shaped fields"* | **Partly shipped, by Timeline.** `File.CaptureLatE7` / `File.CaptureLngE7` are stored, synced fields (`file-service/.../File.kt`), parsed by a hand-rolled common-code EXIF walk (`file-service/.../ExifGps.kt`) and set on upload (`file-service-proxy/.../UploadFileImplementation.kt`). `File.CreatedAt` is already the **capture** time for a backed-up gallery item (Android seeds it from MediaStore `DATE_TAKEN`). `File.Category` (`Image`/`Video`/…) is derived on upload. ⚠ Everything *else* EXIF knows — dimensions, orientation, camera, lens, duration for video — is unparsed, and the parser is **JPEG-only**. |
| *"Places service (lite)"* | **Shipped past lite.** `:place-service` has `Place`, clustering, proximity, merge, network fingerprints, reverse geocoding (`place-geocoding.md` G1–G3) and roles (`place-roles.md` PR-1…PR-4). Photos needs no new Places work — it needs a **join** it is not allowed to push down (§4, PH-G7). |
| *"Search service shape, with Photos as the first consumer"* | **Delivered as three narrow ops, not a service.** There is no full-text index anywhere in the tree. What exists: the chain DSL's `search`/`searchAll` + `inRange` + `sortByDescending` + `take` (entity-aware since `core-chain-entity-typing.md`, with `sortBy(key).take(n)` fused into one streamed bounded top-N pass by `core-ordered-window-fusion.md` F2); `photosCapturedBetween` and `photosNear` (`file-service/.../system/`); and `photosLike` (semantic, `:inference-service`). Photos should **consume these**, not commission a fourth thing. |
| *"Background-service story — folder-watching for new photos must work without the user opening Photos"* | **Still open, and still the hard one.** `drive-synced-folders-phase2.md` is a design sketch gated on bgproc Phase 6/7 (`core-background-processing.md` §5.2). Capture is app-side, policy is Control-side. |
| *"Per-platform gallery integration (Android `MediaStore`, iOS `PHPhotoLibrary`, desktop folder watching)"* | **Shipped for Android and iOS.** `SyncedFolderController` (commonMain) over a `MediaSourceProvider`; `AndroidMediaSourceProvider`; `IosMediaSourceProvider` (PhotoKit, `ios-drive-parity.md` P6b). Desktop is folder-watching only. |
| *"`File.ContentHash` as a client-side identity for cross-device de-duplication"* | **Still exactly as described: computed, wire-registered, no consumer.** The only reads are the manifest integrity anchor (`FileBytesPropertyWriter`, `FileSliceSnapshotProducer`). No index, no grouping. |
| *"The source-folder picker … should **move** here, not be rebuilt"* | Unchanged, and now a genuine ownership question rather than a chore (§5, PH-D3). |

Two more things exist that the milestone entry predates and Photos gets for free:

- **Content understanding.** `:inference-service` ships `MediaLabel` rows (one per file × capability
  × label, synced), a `photosLike(text, limit)` semantic-retrieval op that returns `SimilarPhoto`,
  and an honest `SemanticSearchUnavailable` rather than an empty list. ⚠⚠ **The production backend
  registry is empty** — IN-3b (the real ONNX backend) has not landed, so on a
  real device today every one of these answers "cannot run here".
- **Google Photos import.** `:takeout-import` routes a Takeout zip by content, and `PhotoSidecar`
  already parses Google's per-photo JSON. `TakeoutPhotoImporter` (`:timeline-shared-data`) consumes
  it for Timeline. ⚠ Nobody has run any of it against a real export on a real phone.

**Conclusion:** Photos is an *app* project — five app modules, eight screens, one small new service
for albums (`:photo-service`, PH-D1) — plus a short list of named platform gaps, of which exactly
one is genuinely load-bearing for a first release: **PH-G1**, the flat feed's read path. The other
candidate, background ingest, turned out to be half-built already (`:ingest-service`, PH-G8).

---

## 2. What a Photos client can already call

Everything below is in the tree today and needs no platform change.

| Need | Call |
|---|---|
| Every image in a time window, ranked | `FileSystem.photosCapturedBetween(startMs, endMs, limit)` → `TList<CapturedPhoto>` (path, capturedAt, backedUp) |
| Every image near a coordinate | `FileSystem.photosNear(latE7, lngE7, radiusM, limit)` |
| Arbitrary flat query over files | `searchAll<File> { … }` + `inRange` + `sortByDescending(File.CreatedAt)` + `take`/`drop`, entity-typed |
| Grid bytes | `File.Preview(w, h)` — the single "best available preview" API; classes `Thumbnail` / `ThumbnailLarge` / `ThumbnailXLarge`, all `dynamicProperty(Syncable(OnDemand))` on the content plane. Cold-read recipe: read `eager(File.Preview(w,h))`, and on `null` pull the content-plane blob and re-ask (`TenshuFilesRepository.getPreview` / `cacheThumbnails`) |
| Full-size bytes / streaming video | `File.Data`, `GetData`, `StreamingPreview` + `VideoPlayback` (`drive-streaming-preview.md` S1–S7) |
| Semantic search | `InferenceService.photosLike(text, limit)` — ⚠ throws when no backend |
| Label search | `MediaLabel` rows (IN-3a) |
| Places | `:place-service` — `Place`, roles, geocoded labels, `PlaceProximity` |
| Source folders / buckets | `SyncedFolderController` + `MediaSourceProvider` (Android MediaStore, iOS PhotoKit) |
| Capacity + gating | `:storage-service`, ADR 0019 |

Reusable UI: `ui-core` (chrome, adaptive navigation, image decode, preview), and Drive's
`VerticalFileItem` / `MultiSelectBottomSheet` / `SelectionTopAppBar` / `preview` / `backup` /
`search` packages in `:drive-shared-ui`, most of which want lifting into `ui-core` rather than
copying (PH-G13).

---

## 3. Screens

Three tabs, as `milestones.md` specifies, plus the surfaces they open into. Each entry names the
call it makes and the gap it hits.

### S1 — Home (feed)

A flat, date-sectioned grid of every image and video in the realm, newest first, ignoring paths.
⭐ **Sticky headers and the zoom control shipped 2026-09-04 (§28).** ⭐ **The feed opens as a mosaic
— some photographs drawn over 2×2 cells — since 2026-09-15 (§32).** ⚠⚠ This line used to say the
zoom control *"costs only layout"*; it does not — the density decides what a section *is*, so it
changes the separator rule upstream of `cachedIn` (§28.1). Multi-select ⭐ **shipped 2026-09-03
(§27)** — for delete and
add-to-album; ⚠ **share is not offered**, because sharing a photograph out is PH-G11 and has no
implementation behind it.

- **Read:** keyset page — `searchAll<File> { Category == Image ∨ Video, CreatedAt inRange(0, cursor) }
  .sortByDescending(File.CreatedAt).take(pageSize)`, wrapped in the paging source, `cachedIn` per
  `core-paging-scroll-restoration.md` (⚠⚠ its three reactivity guards are the live contract).
- **Gaps:** PH-G1 (scan cost + no dedup at page granularity), PH-G3 (ContentHash grouping),
  PH-G9 (thumbnail prefetch on a fast scroll).

### S2 — Search (over the Home tab)

One bar, three sources, one result list with a source badge per hit — the pattern Mia already uses
(`MiaHit` / `MiaSource`):

1. **Name / path** — chain `search` (Drive's `FileSearchBar` verbatim).
2. **Label** — `MediaLabel` rows ("beach", "dog"). Present only when the capability is on.
3. **Semantic** — `photosLike(text)`, debounced (the op is a per-call scan over vectors).

⚠⚠ When `photosLike` throws `SemanticSearchUnavailable`, the UI must say *"on-device search is
off / no model installed"* with a link to Control — **never** render it as "nothing matched"
(the mistake `mia-query-layer.md` §14.1 names). With IN-3b unshipped, sources 2 and 3 are absent on
every real device, and the screen must read as complete without them.

### S3 — Viewer

Full-screen pager over the current result set: pinch-zoom, swipe between, video playback, an info
sheet (capture time, place label, camera, dimensions, size, path, "on N devices"), and actions
(share, favourite, delete, add to album, open in Drive).

⚠⚠ **The pager is over the *caller's* list, never a cursor of its own** (PH-8, §21.1): the viewer
opens from four different lists and only one of them is the library in capture order. Endless
swiping is the owning screen's paging reached through `onLoadMore`, and each page owns its own hero
bytes because a pager has a window and a view model does not (§21.3).

- **Gaps:** ~~PH-G4~~ (closed by PH-7 — the info sheet ships, §17), PH-G7 (the coordinate has no
  place *name* until someone geocodes it), PH-G9 (`ThumbnailXLarge` is 256 px — a byte-less device
  shows a 3–5× upscale until the original arrives; `drive-synced-thumbnails.md` §12),
  PH-G12 (no crop/rotate), PH-G14 (iOS viewers owed).

### S4 — Collections

⚠ **Named *Collections*, not *Albums*, since §21** — the word Google Photos and Apple Photos both
use for this tab, both of which keep *album* for the hand-built list inside it. An album is one
section here, not the tab.

⭐ **Laid out as rows with a page per section since §35** (2026-09-30) — the Google Photos shape.

Two kinds. **Manual albums** — user-created, ordered, with a cover. **Smart albums** — derived,
non-persisted queries: *Places* (cluster by `Place` via capture coordinate), *Recent*, *Videos*,
*Screenshots* (`backedUp == false` ∧ bucket heuristic), *Favourites*, and label albums
("Dogs", "Documents") — the **Things** section, whose Photos-side half is built (§24.3) and which
stays empty until IN-3b lands a backend. Also **Devices** (the `/Backup/<device>/<bucket>` tree the
backup drain writes), **Folders** (the one honest bridge back to Drive's model), and
**Duplicates** — a review surface, not a filter (§5, PH-D2).

- **Gap:** PH-G2 — **there is no album anywhere in the platform.** This is the single new data model
  Photos needs.

### S5 — Album detail

Grid, reorder, set cover, add/remove members, rename, delete, share. Ordering and membership are
album-owned rows; removing a photo from an album must never delete the file (PH-D4).

### S6 — Place / date detail (smart-album detail)

A map header (`ui-map` / `:basemap-core`, already used by Maps and Timeline) plus the grid of
photos taken there. Reached from S4 or from the viewer's place chip. This is the "Photos taken in
Paris" hero, and it is a **join Photos performs**: `:file-service` deliberately refuses to know
what a `Place` is (`PhotosNear` KDoc), so the app reads `Place` rows from `:place-service`, then
calls `photosNear` per place. PH-G7 is the missing half.

### S7 — Settings

Source pickers (which device folders / PhotoKit buckets are ingested — Photos' own screen over
the shared engine, per PH-D3), *"skip photos already in the realm"* (PH-D2, default on), trash
retention, backup constraints (Wi-Fi only, charging — the
`backgroundSyncConstraints()` resolver in `:preference-service`), storage usage
(`:storage-service`), the two-switch inference consent (realm ∧ device, absence = NO), and the
per-app Maps-renderer election on iOS if S6 gets a map (⚠ MapKit is never the default, A35).

### S8 — Trash / recently deleted

Settled by PH-D4: **deleting moves the file to a realm folder** (`/Trash`, auto-created on first
use), with a *"delete permanently"* checkbox on the confirm dialog for the case that motivates it —
the user is out of quota, or clearing duplicates, and wants the bytes gone now. Trash lists what is
in there with days-remaining, and offers restore, empty-now, and per-item permanent delete.

Three things fall out of that choice, and they are why it is the *right* choice:

- A move is **pure metadata** (`file-content-addressing.md` Ph0–3), so trashing 500 photos costs no
  bytes and no re-upload.
- The file still exists, so the backup drain's device-local cursor still knows the source asset and
  **will not re-ingest it** — which answers the re-ingest half of PH-D4 for free. ⚠ Permanent
  delete does *not* get that for free (PH-G17).
- `/Trash` is a realm folder, so **Drive sees it too** — which is correct (one realm, one trash),
  and makes this a platform slice rather than a Photos feature. Photos is its forcing consumer.

⚠ Auto-purge after the retention window needs a scheduled job, i.e. the same bgproc Ph6/7 binding
PH-G8 waits on. Until then: purge opportunistically on app launch, and say so in the UI rather than
promising a timer the platform cannot keep.

---

## 4. Gaps

Ordered by whether they block a first release.

**Blocking:**

- **PH-G1 — the flat feed's read path. ⭐ ANSWERED by PH-1a (§9): the chain page works, and it
  cost two platform fixes to get there.** `photosCapturedBetween` / `photosNear` remain what their
  KDocs say — O(files-in-realm) scans, three scalars, no paging — and the feed does not use them.
  It issues `traverse().filter { category }.filter { createdAt.inRange }.sortByDescending(CreatedAt)
  .take(n)`, pinned end-to-end by `PhotosFeedChainTest`. ⚠ Still open, and deliberately so: the
  **cost** half. A page is a full-tree walk, and what one costs at 20 000 rows is a measurement on
  real hardware, not a unit test.
- **PH-G2 — no album model.** `Album` (name, cover, createdAt), `AlbumEntry` (album, file,
  position), and a flag row for favourite / hidden. ⚠⚠ **None of these may be a field on `File`**:
  it is the platform's hottest composite, and `MediaLabel` and `PlaceRole` both took the
  separately-keyed-row route for exactly this reason (and `Place` literally cannot gain a field —
  `place-roles.md` §2.1). ⚠⚠ Membership is keyed on **`File.ContentId`**, not on a path or a `DataId`. The
  first draft of this gap said `DataId` + the reference-migration primitive would keep an album
  intact across a move; **PH-2 proved that wrong** — a `File`'s `DataId` *is* its path, and
  `DataIdMigrator` only retires the old key, rewriting no foreign row's stored key field. See
  §11.2.
  Owner: **`:photo-service`**, settled (§5, PH-D1).
- **PH-G8 — background ingest, but less of it than the milestone implies.** ⭐ The registry half
  **is already built and already shared**: `:ingest-service` ships `IngestSource` (one synced,
  device-scoped row per source, with enabled-state and status), `RegisterSource` / `SetSourceEnabled`
  / `UpdateSourceStatus`, and `SyncedFolderController` registers into it today — as does Fit's
  import recorder. Photos becomes the third registrant, not the first. What is still missing is the
  *scheduling* half: the `PlatformScheduler` / WorkManager binding (bgproc Ph6/7), the enforcement
  read-gate, and the desktop `FolderWatcher` that consumes `IngestSource.watchedPaths`. Until those
  land, ingest happens on foreground launch — a real but shippable degradation, and already how
  Fit's auto-import behaves.
- **PH-G13 — app plumbing.** Five new modules (`photos-shared-logic`, `photos-shared-ui`,
  `photos-android`, `photos-desktop`, `photos-ios`) plus the iOS route-1 in-process runtime wiring
  (`ios-first-party-route-1-migration.md` §9), Control app registration (⚠ Track A **A4 has not
  shipped**, so no app — Drive included — appears in Control's Apps screen), and the HR #8 smoke
  gate. ⚠⚠ Per `docs/testing.md` §6, adding Photos to `scripts/android-smoke.sh` is required the
  day it exists **and at least one first-party app must stay un-built** for the HR #9 lockstep
  assertions to carry information.

**Shapes the product, not blocking:**

- **PH-G3 — cross-device dedup, and it is now a v1 item.** `File.ContentHash` still has no
  consumer and no index, and PH-D2's *"skip photos already in the realm"* setting is precisely a
  consumer: deciding whether to skip means asking *"does this hash exist?"* once per candidate
  asset, which is a lookup, not a scan. So the setting and the index ship together, or neither
  does. ⚠⚠ Grouping and skipping stay **client-side** — the relay addresses ciphertext by design
  ("no cross-user dedup, ever"), and pushing this into the storage layer would build the
  confirmation-of-file oracle `file-content-addressing.md` exists to prevent. ⚠ The hash is
  computed *during* upload today (`UploadFileImplementation`), so an ingest-time skip needs it
  computed **before** the upload commits — cheap for a local file, but a real change to the drain's
  order of operations. Note the two identity layers stay distinct: device-local asset-id dedup
  already happens in the drain, and is what stops one device double-uploading; this is the
  cross-device layer.
- **PH-G4 — EXIF beyond GPS. ⭐ CLOSED by PH-7 (§17).** Width, height, orientation, camera make and
  model and video duration are now read at upload and stored on the row; the viewer has an info
  sheet. ⚠⚠ The half that mattered was not the new fields: `ExifGps.kt`'s KDoc justified being
  JPEG-only with *"iOS has no gallery backup"*, which stopped being true when
  `IosMediaSourceProvider` shipped with P6b — so **every iPhone photo was landing with no capture
  coordinate**, silently emptying S6 for iOS users. The HEIF box walk fixes that. ⚠ Still not read:
  ISO, aperture, lens, and the capture *time* from EXIF rather than the file's mtime.
- **PH-G5 — provenance.** `CapturedPhoto.backedUp` (is it under `/Backup/`) is the only provenance
  signal in the platform. "Photo vs screenshot vs download" is a real taxonomy in a photos app and
  currently has to be guessed from the bucket name.
- **PH-G6 — content smart albums are inference-gated.** Date and place albums work today; anything
  about *what is in the picture* waits on IN-3b. ⚠ The model itself is pinned (`models.md` §3.8); the gate is now IN-3m's on-device measurement. Design the Albums tab so the
  label section is absent, not empty.
- **PH-G7 — a photo's coordinate has no name, and the blocker is a module boundary.** PH-3 found
  the real obstacle: **`ReverseGeocoder` lives in `:timeline-shared-data`**, a Timeline *app*
  module, so Photos cannot reach it without an app-to-app dependency. Lifting the seam into a
  platform home (`:place-service-common` is the obvious candidate) touches Timeline's geocoding
  rails, its per-platform actuals and its consent gate — a platform slice with its own review, not
  a Photos phase. ⚠ Until then Photos groups by places **Timeline captured and named**, so a
  Photos-only user has no Places section at all (§13.2).
- **PH-G9 — grid bytes. ⭐ The repeat-read and flood halves are CLOSED (2026-09-03, §26); the
  prefetch half is deliberately NOT built and the section says why.** A fast-scrolling grid on a
  byte-less device asked for hundreds of on-demand content-plane pulls, and the same photograph
  more than once: measured at **17 reads for 10 photographs** across one scroll down and back.
  `PreviewCache` remembers the encoded bytes and bounds fetches in flight. ⭐ **The prefetch half
  is now MEASURED and stays unbuilt (§30) — and the reason first given for not building it was
  wrong.** ⚠ Still open:
  `ThumbnailXLarge` closed part of the 220× gap but the viewer still upscales until the original
  lands, and the *viewer's* hero loader is uncached.
- **PH-G10 — deletion.** `retention-quota-model.md` P3 is account-level only (live since 2026-10-24).
  There is no trash, and no answer to "the file is gone from the realm but the asset is still on
  the phone" — the re-ingest loop (PH-D4). ⚠ §2's three-tier taxonomy is load-bearing here:
  conflating durable content with the delivery log was the relay's time-based blob-GC data-loss bug.
- **PH-G11 — sharing a photo or an album out.** Folder link sharing (`drive-folder-link-sharing.md`)
  is design-only and ⚖️ counsel-gated; shared realms share *folders*, and an album is not a folder.
  v1 ships OS-level share of the bytes and nothing more.
- **PH-G12 — editing, and the formats a phone actually produces.** No crop/rotate/markup. No model
  for Live Photos / motion photos (an image + a video that are one asset), bursts, or RAW+JPEG
  pairs — each arrives as unrelated files in a flat feed, which is visibly wrong in the way §Photos
  warns about.
- **PH-G14 — iOS preview parity.** `drive-content-preview.md` still owes iOS viewers; a photos app
  is the least forgiving consumer of that gap.
- **PH-G16 — the shipped scan ops do not know about `/Trash`.** `photosCapturedBetween` and
  `photosNear` walk every `File` row in the realm, and their callers are **Timeline and Mia**, not
  Photos. The moment PH-D4's trash exists, a photo the user deleted keeps appearing in a Timeline
  moment and as a Mia citation. Whatever excludes it has to live where those ops are (proxy-side,
  `:file-service`) — a path prefix exclusion is the cheap answer and the ugly one; a
  `File.Trashed` marker read by the ops is the honest one, and is a stored-shape change (PH-G15).
  ⚠ This is the one gap the trash decision *creates*, and it is cross-app, so it is not optional.
- **PH-G17 — ⚠⚠ the re-ingest loop does not exist as this doc described it.** The claim was that
  emptying the trash lets the drain re-upload the photo, "so the photo the user deleted to free
  space comes back". PH-5 checked: the backup cursor is a **monotonic watermark** — Android's
  `listNewItems` returns items *strictly newer* than the stored cursor, and the cursor is
  MediaStore `_ID`. An asset already uploaded sits below the watermark and is **never revisited**,
  whatever happens to the realm copy. Editing it does not change `_ID` either. What remains is a
  much narrower edge: the photo returns only if the bucket's cursor is **reset** (app data cleared,
  the bucket re-selected, a new device), or if the user re-imports it into the gallery, where it is
  a genuinely new asset. Neither is worth a device-local deleted-asset record, so PH-5 shipped
  without one. §15.3.
- **PH-G15 — schema obligations.** A new service means HR #9 (`service(id, version)` bump +
  `SchemaMigration` in the same PR — ⚠ **not** `TENSHU_STORAGE_FORMAT_VERSION`, which is `:core`'s
  alone and wipes every store), and ⚠ a *new* service passes `SchemaShapeBaselineEnforcementTest`
  for the wrong reason until its `-common` module is added to `:system-test-utils`.

---

## 5. Decisions — settled 2026-08-21

All five were answered by the maintainer in one pass. Recorded here with the reasoning, because
three of them constrain code that does not exist yet and one of them (PH-D4) creates a new
cross-app obligation (PH-G16).

### PH-D1 — albums live in a new `:photo-service` ✅

Five modules (`photo-service` / `-common` / `-client` / `-proxy` / `-test-utils`) owning `Album`,
`AlbumEntry` and the photo flags (favourite, hidden). Not `:file-service`: that is the platform's
most central service and it would have to learn a Photos concept, against the precedent set twice
already — `MediaLabel` went to `:inference-service` and `PlaceRole` to `:place-service` rather than
onto the entity they describe. ⚠⚠ **The claim this decision originally carried was falsified in PH-2.** It said membership would
address files by `DataId` and ride `referenceProperty` + the `DataId`-change→reference-migration
primitive, so a move would not empty an album. It would have: a `File`'s `DataId` is derived from
its path, and `DataIdMigrator` owns only the **old-side** retirement — it rewrites no foreign row's
stored key field. Membership is keyed on `File.ContentId`, which `MoveFile` preserves verbatim.
§11.2 carries the full argument.

### PH-D2 — be truthful about duplicates; skip at ingest, review after ✅

**Maintainer's call:** don't hide duplicates in the feed; offer a setting that avoids importing
them, and a way to clean up the ones that got through.

⭐ That is also, almost exactly, what the field converged on — worth recording, because it means
the conservative answer is the industry-standard one rather than a compromise:

- **Google Photos** deduplicates at *upload*: a re-uploaded identical file is recognised and not
  stored twice, so the library never shows it twice. It does **not** offer a duplicate-finder for
  what slipped through by other routes (edited copies, re-encodes) — the strictness at the door is
  the whole strategy, and users notice the gap.
- **Apple Photos** does both halves: import from a device skips assets it already has, and since
  iOS 16 a **Duplicates** album under Utilities surfaces near-identical items with a **Merge**
  action that keeps the highest-quality copy and the *union* of the metadata (favourite, captions,
  album memberships). Deliberately user-driven — nothing is merged without a tap.
- **Amazon Photos, Synology Photos, digiKam** all ship a duplicate-finder as an explicit tool, and
  all of them ask before deleting.

Nobody silently hides one of two identical files in the main feed, and the reason is that "silently
hidden" and "lost" are indistinguishable to a user. So:

1. **Feed shows what is there.** Two files, two tiles. No implicit grouping. *(A tile may badge
   "2 copies" once PH-G3's index exists — a badge is a statement, not a hidden row.)*
2. **Ingest-time skip** — Settings › *"skip photos already in the realm"*, **default on**, matching
   what both Google and Apple do at the door. This is the setting that actually prevents the
   problem, and it is why PH-G3 became a v1 item.
3. **Duplicates smart album** (S4) — the Apple-shaped review surface, showing exact `ContentHash`
   matches only, grouped, with per-group *keep this one* / *delete the rest*. **Never automatic.**
   Near-duplicate detection (re-encodes, edits) is a perceptual-hash problem and belongs to a later
   inference phase, not here.

⚠ Exact-hash grouping has one honest limit worth stating in the UI copy: an edited or re-encoded
copy is a *different* file, and Photos will not call it a duplicate.

### PH-D3 — the source picker is duplicated on purpose, over a shared engine ✅

**Maintainer's call:** ship it in both apps rather than sending the user to the other one; share
code where it makes sense (a `ui-files`-shaped module); accept some duplication because the two
screens are *similar, not the same*.

⭐ The engine half is already shared and already proves the shape: `:ingest-service`'s `IngestSource`
registry is device-scoped, synced, and **has three registrants pending** — `SyncedFolderController`
(Drive) writes into it today, Fit's import recorder writes its own kind, and Photos becomes the
third. So "duplicated" only ever describes the *screen*: the registry, the drain, the constraints
resolver and the storage gate are one implementation.

What each screen shows is genuinely different, which is the argument for not forcing one:

| | Drive | Photos |
|---|---|---|
| Unit | a folder / SAF tree / desktop path | a **bucket** (Camera, Screenshots, WhatsApp) or a PhotoKit album |
| Framing | *"keep this folder backed up"* | *"back up my camera roll"* |
| Default | nothing selected | camera bucket suggested on first run |
| Extras | destination path, conflict policy | skip-duplicates (PH-D2), video toggle |

**Recommended split:** extract the *stateless* pieces — the bucket/folder row, the size + count
line, the enable toggle, the permission-rationale block, the empty state — into a new
**`:ui-files`** (Compose, sits beside `ui-core`, no app dependency), and let each app compose its
own screen and its own ViewModel over the shared `SyncedFolderController`. ⚠ `SyncedFolderController`
itself lives in `:drive-shared-logic` today and must **move** to a neutral home before Photos can
use it — it is already source-generic, so this is a module move, not a rewrite, and it is the first
task of PH-4.

### PH-D4 — delete moves to `/Trash`; a checkbox does the real thing ✅

See S8 for the screen and the three consequences. Two obligations it creates, both new to this
version of the doc: **PH-G16** (the shipped realm-wide scan ops would keep showing trashed photos
to Timeline and Mia) and **PH-G17** (permanent delete reopens the re-ingest loop, and owes a
device-local deleted-asset record in the same slice).

⚠ This is also the point where `retention-quota-model.md`'s three-tier taxonomy has to be read
before writing code: conflating durable content with the delivery log was the relay's time-based blob-GC data-loss bug,
and a trash folder is durable content that *looks* like a delivery-log tier.

### PH-D5 — ship without the content sources ✅

**Maintainer's call:** ship; the AI features arrive later and land in several apps at once, so
reference them here as phases that *unlock* rather than blocking on them. §7 is that map.

## 6. Phase map

| Phase | Content | Gates |
|---|---|---|
| **PH-1** ✅ | Module skeleton + Home feed (S1) over the chain-DSL keyset page + viewer (S3). Read-only. **BUILT** — §9 (the read) and §10 (the app). ⚠ Four modules not five, no iOS shell. ⚠ S2 was deferred to a PH-3 that never claimed it; **it shipped separately** (§18). | PH-G1 measurement (its cost half is still open) |
| **PH-2** ✅ | `:photo-service`: `Album` / `AlbumEntry` / flags; Albums (S4) + album detail (S5); favourites. Carries the HR #9 bump + migration + `:system-test-utils` registration. | PH-G2, PH-G15 |
| **PH-3** ✅ | Smart albums by date, place and category; place detail (S6) with the `Place` join. **BUILT** — §13. ⚠⚠ The geocoding hook did **not** ship: PH-G7's seam is in a Timeline app module (§13.2). ⚠ Did not pick up S2, which shipped on its own (§18). | PH-G7 |
| **PH-4** ✅ | **PH-4a** §14 — `:app-ingest` + Settings, which *manages* sources. **PH-4b** §19 — the engine moved behind a five-method `BackupDataPlane`, and Photos' picker writes the same rows Drive's drain reads. ⚠⚠ Nine preference ids kept verbatim: renaming would orphan every device's settings, silently (§19.3). ⚠⚠ iOS unverified and no smoke gate. ⚠ `:ui-files` still not created — one consumer (§19.6). | PH-D3 |
| **PH-5** ✅ | `/Trash` (S8), restore, delete-for-good, `File.Trashed` + the scan-op exclusion (**PH-G16 closed**). **BUILT** — §15. ⚠⚠ No deleted-asset record: PH-G17's premise was falsified (§15.3). ⚠ Auto-purge waits on bgproc. | PH-D4 |
| **PH-6** ✅ | `ContentHash` index; skip-on-import setting; Duplicates album. **BUILT** — §16. ⚠⚠ An accessor is not an index: the engine has none, so `ContentIndex` walks once per drain (§16.2). ⚠ No hardware pass. | PH-G3 |
| **PH-7** ✅ | Metadata depth: HEIF EXIF box walk, dimensions / orientation / camera, video duration, and the viewer's info sheet. **BUILT** — §17. ⚠⚠ It closes a **silent** failure: every iPhone photo was landing with no coordinate (§17.1). ⚠ No backfill — a pre-v8 file gains nothing until it is re-uploaded (§17.4). | PH-G4 |
| **PH-8** ✅ | The viewer becomes a **pager over the list the grid showed** (S3): endless swiping driven by the caller's paging, per-page hero bytes, an info sheet that follows the page. **BUILT** — §21. ⚠⚠ §21.3: the bytes had to leave the view model — a pager has a window, a view model does not. ⭐ §21.5: Back went to the feed from every entry point, losing the album or the query. | — |

PH-5 and PH-6 are deliberately adjacent: emptying the trash is the most common way a user acts on a
duplicate, and both touch the drain's memory of what it has already seen.

Background ingest (PH-G8) is not a phase here — its registry already exists and its scheduler is
`drive-synced-folders-phase2.md`'s track, landing when bgproc Ph6/7 does.

## 7. Unlocked later — the platform tracks Photos becomes a consumer of

Per PH-D5, none of these block a release; each is a phase that becomes writable the day its track
lands. Sweep only — details when the phase is picked up.

| Track | What Photos gets | Status of the track |
|---|---|---|
| [`inference-service.md`](inference-service.md) **IN-3b** | The real backend (ONNX) + vectors + batch scheduling. Turns on **label albums** in S4 and sources 2–3 of S2's search. | ⚠ The production backend registry is **empty**. ✅ Model pinned (`models.md` §3.8); the gate is IN-3m's on-device measurement. IN-3a (rows + index pass) shipped against the fake. |
| **IN-5** (OCR) | *"the photo of the receipt"* — text search over images; a second capability on the same seam. | Not started; needs a second model. |
| **IN-6** (faces) | The People album — the most-expected Photos feature that this doc does not scope. ⚠⚠ Opt-in, off by default, and ships **with** its threat-model amendment + ⚖️ counsel item. ⚠ Distinct from `camera-service.md` CD-D7, which *excludes* face recognition — that subject is a non-consenting stranger at a door, this one is the user's own library. | Not started. |
| **IN-7** (character tagging) | User-taught prototypes — "tag this person/pet as X" without a face model. | Free given IN-3, not started. |
| [`mia-query-layer.md`](mia-query-layer.md) **MQ-5** | *"show me photos from Rome last summer"* answered in Mia, citing Photos rows. MQ-4's `photosLike` already exists; the planner lowering to it does not. | MQ-5a (model-as-planner seam) shipped; phrasing + model→chain remain. ⚠⚠ A model may pick the intent, never the slots and never a write. |
| [`control-cross-app-open.md`](control-cross-app-open.md) | A Mia or Timeline citation **opens the photo in Photos** — the address is a `DataId`, so Photos registers as the owner of image `File` rows. | v0.5 design, no code. Photos is a natural second consumer after Drive. |
| [`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) | Sharing an album as a link (one `K_share` + an encrypted manifest). ⚠ An album is not a folder, so this is an adaptation, not a reuse. | Design SETTLED, no code; ⚖️ counsel gates launch. |
| [`place-imagery.md`](place-imagery.md) **PI-1** | The inverse of S6: a picture for a place the user never photographed, so an empty place detail still shows something. | v0.1 design; Tier 2 ⚖️ gated on ToS + billing. |
| [`retention-quota-model.md`](retention-quota-model.md) **P3** | Real reclamation behind the trash — P3 is **live since 2026-10-24** but reclaims a closed account's whole realm, never a trashed file, so PH-5 purges opportunistically and says so. | Per-file reclamation is §3.4's content sweep; ⚖️ counsel sign-off on P3's text still owed. |
| [`core-background-processing.md`](core-background-processing.md) **Ph6/7** | Ingest without opening the app, and a real auto-purge timer for the trash. | The scheduler binding; `drive-synced-folders-phase2.md` is its Photos-facing half. |
| [`drive-content-preview.md`](drive-content-preview.md) | iOS viewers — the gap a photos app is least able to tolerate. | Ph1–4 shipped elsewhere; iOS owed. |
| [`takeout-import-and-recall.md`](takeout-import-and-recall.md) | Google Photos import: `PhotoSidecar` already parses the per-photo JSON (captions, albums, favourites) that Photos, unlike Timeline, can actually use. | B1–B5 shipped; ⚠⚠ never run against a real export on a real phone, and `importFiles` holds every file's bytes at once. |
| [`core-ordered-window-fusion.md`](core-ordered-window-fusion.md) | Whatever PH-G1's measurement asks for — the feed is F2's second real consumer after Drive's listing. | F1 shipped, F2 partial. |

## 8. Obligations any Photos PR carries

- HR #8 — `scripts/android-smoke.sh`, with Photos added to the built set and at least one app left
  un-built (`docs/testing.md` §6).
- HR #9 — version bump + `SchemaMigration` for any new or reshaped stored/wire type; regenerate
  `SchemaShapeBaseline`; add `photo-service-common` to `:system-test-utils` (⚠ until then a new service passes the baseline test for the wrong reason).
- The iOS commands (`./gradlew iosKotlinCheck`, `scripts/ios-smoke.sh --build-only photos`) for
  anything touching `iosMain`, a shared `*UiState` or an exported surface — the JVM gate compiles
  none of it.
- `core-paging-scroll-restoration.md`'s three reactivity guards stay green; a Photos feed adds a
  fourth of the same shape.
- Threat model: albums and flags add no new asset class (synced ciphertext rows like any other).
  Face detection (IN-6) and any label that leaves the device do, and owe an amendment in their own
  PR.

## 9. As built — PH-1a: the feed's read path (2026-08-21)

PH-1's gate was *"verify the chain page works, and measure it before committing."* The
verification found more than it expected, so this section records what shipped and what it cost.

### 9.1 The page, as it will ship

```kotlin
root.files
    .traverse()                                                   // every file/folder in the realm
    .filter<File> { category equalTo FileCategory.Image }          // media only
    .filter<File> { createdAt.inRange(0L, cursorMs) }              // the keyset window
    .sortByDescending(File.CreatedAt)                              // newest first
    .take(pageSize)                                                // F2's bounded window
    .select { fields(File.Field.PATH); relations(File.CreatedAt, File.Category) }
```

`PhotosFeedChainTest` (`:file-service-proxy`) pins it end to end: newest-first across folder
depths, non-images and folders excluded, page 2 starting exactly where page 1 stopped with no row
served twice, and an exhausted window keeping nothing rather than everything.

⚠ **Two filter steps, not one, and that is a shape fact.** The chain DSL has no boolean combinator
— `EqualTo`, `Contains` and `InRange` each return a `TBoolean` and nothing consumes two of them —
so *category ∧ window* is two chained predicates. A future `and` would collapse them; the test is
written so it keeps passing when that lands.

### 9.2 ⚠⚠ `File.Category` was never written by anything

The category predicate failed on a realm full of JPEGs with *"Could not resolve property
reference"*. The cause was not the chain: **nothing in the platform had ever written
`File.Category`.** Every consumer re-derived it from the filename instead — Drive's row glyph
(whose own comment called it *"a vestigial property"*), both photo ops, and the preview resolvers.
Three copies of one `when`, and a stored slot that was always empty.

So it is now a **derived dynamic property**, resolved per row by a new pure op `FileCategoryOf`
— the `File.Name` pattern, with `File.Preview`'s "a template may call an operation" shape because
an extension→category mapping is a `when` and the chain DSL has no branch. This needs **no
backfill**: a file uploaded years ago answers as readily as one uploaded now, because the answer
was never in the store to begin with. It also gives the desktop/web bridge (`ControlFileBridge`)
a category for the first time — it was reading that empty slot and shipping `null` for every file.

⚠ HR #9: `FileService` 5 → **6** with an empty migration step. `SchemaShapeBaseline` reports no
positional reshape (a property lives in the string-keyed map, not the field list) — the bump is
owed for the *wire* half: a peer that predates this asks for the category with a static
`PropertySelector` and would read every file in the realm as uncategorised.

⚠⚠ **`PropertyKindBaselineEnforcementTest` is the test to read before touching this again.** A
`valueProperty` → `dynamicProperty` flip is exactly what once wedged sync permanently on every device holding the rows: C7 moved
the two thumbnail classes, the shape baseline was honestly green, and every device that already
held thumbnail rows read their inline bytes as a child-row id and threw on the ingest path. That
baseline exists to make the flip loud, and it fired here as designed.

⭐ It is nonetheless safe here, and the baseline itself is the evidence: `tenshu/file/category` was
**not in the pinned set at all** before this change — the platform never gave it a storable kind,
because the registration carried no value factory. There is no stale inline slot on any device to
be misread, which is the precondition that wedge needed. The regenerated line is an *addition*
(`tenshu/file/category|tenshu/file|CHILD_ROW`), not an `INLINE↔CHILD_ROW` flip, and that
distinction is the whole safety argument.

### 9.3 Two platform primitives the feed forced open

- **`File.createdAt` / `File.category` chain accessors** (`:file-service`). Before this, **no
  predicate in the repo referenced a stored value property** — every one went through `File.path`
  (a field) or `File.name` (a dynamic property), so whether the engine resolved a `Property.Value`
  inside a predicate was simply unknown. It does.
- **`TConstantRo`** (`:core-common`) — the chain-build placeholder for a constant-valued property,
  the third value shape beside `TStringRo` and `UnixInstantRo`. ⚠ It is typed `TConstant`, not the
  concrete family: `FileCategory` is a **sealed** interface, and a placeholder implementing it
  would be a new subtype that breaks every exhaustive `when` over the category (there are three,
  none with an `else`). `File.Category`'s decoder re-types the resolved value, so no consumer sees
  `TConstant`.

### 9.4 The gate

`./gradlew desktopTest jvmTest test --continue` — **109 test tasks executed**, four failures, none
of them this change: `MacDatalessProbeTest` needs macOS `chflags` (`tech-debt.md` §"fails
deterministically off macOS"), `TestClientGraphResolutionTest` and
`DeleteAndReuploadFolderSystemTest` are on tech-debt's intermittent list and both pass on re-run,
and `PropertyKindBaselineEnforcementTest` was the real signal, handled above.

⚠ `scripts/run-gate.sh` could not run this at all on a GNU-mktemp box: `mktemp -t tenshu-gate`
fails ("too few X's in template"), after which the script ran the gate with `LOG` unset, counted
zero test tasks and printed **"all green"** — a false pass, which is the one thing that script
exists to prevent. Fixed in the same commit.

### 9.5 What PH-1a deliberately did not do

- **No measurement yet.** The cost half of PH-G1 stands: `traverse()` is a full-tree walk and its
  cost at 20 000 rows is a device measurement, not a unit test. Until it is run, the feed's page
  size is a guess.
- **No app modules.** PH-1's UI half (the five `photos-*` modules, the grid, the viewer) is next;
  this slice is the read it will issue, proven from the service side where a failure is legible.
- **The three name-derivation copies still exist.** `PhotosCapturedBetweenImplementation`,
  `PhotosNearImplementation` and the preview resolvers still call `fileCategoryForName` directly.
  They are now *duplicates of the op's body* rather than of a missing property, and folding them
  into `FileCategoryOf` is a follow-up that touches shipped read paths — worth doing, not worth
  bundling here.

## 10. As built — PH-1b: the app (2026-08-21)

### 10.1 Four modules, not five

`:photos-shared-logic` (Compose-free), `:photos-shared-ui` (Compose, android + desktop),
`:photos-desktop`, `:photos-android`. The real repository lives in `-shared-logic` beside the view
models rather than in a `-shared-data` twin as Contacts has: it needs no Compose and no platform
delegate, because it is a chain read over Control's `:file-service` and nothing else.

⚠ **No `:photos-ios`.** An iOS shell is SwiftUI over the same view models plus the route-1 runtime
wiring and an Xcode project — its own slice, exactly as Calendar's was (CA-8). The Compose-free
split is what keeps that slice cheap, and the module carries no Compose dependency for that reason.

### 10.2 What is on screen

The feed (S1) and the viewer (S3). **Not** the three tabs S1–S7 describe: Albums needs
`:photo-service` (PH-2) and Settings needs the source picker moved out of Drive (PH-4), and a tab
bar with two dead tabs is worse than one honest screen.

- **Paging** ⚠⚠ **superseded 2026-09-01** — this described a state machine in the Compose-free
  module with its own cursor, in-flight guard and near-the-end listener. All of it is deleted:
  the feed is a `Flow<PagingData<FeedItem>>` over `:core-paging`'s keyset source
  ([`core-keyset-paging.md`](core-keyset-paging.md) K-4, as-built §10). The window itself is
  unchanged and still the one in §9.1 — what moved is who drives it.
- **Day sectioning** is pure arithmetic in `groupIntoDays`, with local-midnight injected — so the
  module takes no date-time dependency and a day straddling two pages still gets one header.
- **Tiles load their own bytes**, keyed on path, through the `getPreview` → pull-thumbnail →
  re-ask recipe. ⚠ The alternative (bitmaps in the view model) keeps every thumbnail scrolled past
  alive for as long as the feed is open — hundreds of megabytes on a real library.

### 10.3 ⚠⚠ Empty is not a state this app is allowed to guess at

The feed distinguishes *loading*, *unreachable*, *failed* and *empty*, and both the state machine
and the screen are tested for it. A photos app that renders "No photos yet" because the Control
proxy is not running has told the user their library is gone — the failure
`ios-control-notifications-and-shares.md` names as "not-connected is a failure, never an empty
success", and the same reason `photosLike` throws rather than returning an empty list. The render
test asserts the empty-library copy is **nowhere on screen** in either error state.

### 10.4 The two registrations that fail silently if forgotten

- **Control's app catalogue** (`BundledAppCatalogue`) — registered in the same commit, because the
  Calendar/Contacts note there is the warning: an app missing from that list is refused at the
  desktop socket exactly as hard as an untrusted one, and the only symptom is a UI spinning forever
  on a session that can never open.
- **The Android smoke gate** — Photos joins the built set on day one (HR #8, the Mia precedent).
  ⚠ The un-built canary set stays Fit and Contacts, which the rule requires. ⚠⚠ Photos ships
  alongside a `FileService` bump, so it is the app most likely to be caught out of lockstep, and a
  mis-wired client here shows an **empty grid** rather than crashing.

### 10.5 What PH-1b did not do

The 20 000-row cost measurement (still PH-G1's open half), the iOS shell, search (S2), and every
write — albums, favourites, delete — which are PH-2 and PH-5. The viewer is a hero and a way back:
no pager, no pinch-zoom, no info sheet, each of which needs something this slice does not have.

## 11. As built — PH-2a: `:photo-service` (2026-08-21)

Five modules (`photo-service` / `-common` / `-client` / `-proxy` / `-test-utils`), three row types,
five operations, eight tests.

### 11.1 The shape

`Album` (id, name, `createdAt`, cover), `AlbumEntry` (album × photo, with a sparse `position`) and
`PhotoFlags` (favourite, hidden). Ops: `SaveAlbum`, `DeleteAlbum`, `AddToAlbum`, `RemoveFromAlbum`,
`SetPhotoFlags`.

⚠ Every relation is `maintained = true`, so `initPhotoService()` is **one line** — the generic
maintainer derives the element type, the array key and each element's key from the schema, and a
service with three row types needs no proxy observer of its own.

⚠ Smart albums are **not** rows. Places, dates, videos and favourites are queries; storing their
results would mean maintaining them. Only a manual album — the case a query cannot express — is
persisted.

### 11.2 ⚠⚠ The key is a content id, because the doc's own claim was wrong

PH-D1 said album membership would address files by `DataId` and survive a move via the
reference-migration primitive. Building it falsified that:

- A `File`'s `DataId` is a `StringDataId` **derived from its path** — which is *why* `MoveFile` has
  to call `DataIdMigrator` at all.
- That primitive's own KDoc scopes itself: *"This class owns that **old-side** work"* — retire the
  old key, tell peers, sweep the bookkeeping. **Nothing rewrites a foreign row's stored key field.**

So an `AlbumEntry` holding a path would dangle the moment the user moved the photo in Drive, and
the album would quietly lose it — the exact failure the doc claimed was handled. `File.ContentId`
is the identity that survives: `MoveFile` preserves it verbatim (*"the SAME contentId"*), because
the ciphertext and manifest are bound to it rather than to the path. Entries are keyed
`"$albumId|$contentId"`, so a move is invisible **by construction rather than by repair**.

⚠ The path rides along as a **hint**: resolving a content id back to a file has no index and would
cost a realm walk, while a path resolves through the same cheap `navigateTo` + `search` every other
read uses. A stale hint self-heals — the reader falls back to a content-id lookup and re-adds with
the path it found, which is why `AddToAlbum` refreshes the hint but never the position.

⚠ A file predating content addressing carries an empty `ContentId` and is **refused**, not stored:
every such entry in one album would key on `"$albumId|"` and overwrite the last. There is a test.

### 11.3 What the ops must not do

Two tests exist for absences rather than behaviours, because these are the failures a user finds
rather than a suite: `DeleteAlbum` cascades to its entries **and touches no `:file-service` row**,
and `RemoveFromAlbum` cannot reach one at all. "Remove from album" and "delete photo" are one
gesture apart in every photos app ever shipped, so the separation is enforced by what the ops can
address, not by the UI's care.

### 11.4 Registered where a new service is otherwise invisible

`:photo-service-common` and `-client` are in `:system-test-utils`' graph **in the same commit that
creates them**. ⚠⚠ Until that registration a new service passes `SchemaShapeBaselineEnforcementTest`
for the wrong reason — it is outside the baseline's graph, so its pin is *absent* rather than
wrong, and nothing fails until the first reshape silently breaks a peer. Both baselines regenerated
to pure additions at version 1: 11 new types, 3 new property kinds, nothing moved.

⚠ `PhotoService` is version **1** with no migration chain, which is correct exactly once. HR #9
starts applying at the first reshape after this.

### 11.5 Not in this slice

The Albums and album-detail screens (S4/S5), reordering as a gesture, and the cover picker — PH-2b.
The service is what they are built on, proven from the side where a failure is legible.

## 12. As built — PH-2b: albums on screen (2026-08-21)

### 12.1 What is on screen

Two tabs (Photos, Albums), album detail, Favourites, and the two gestures that reach the service:
long-press a feed tile to file it, tap the heart to favourite. ⚠ Still no Settings tab — PH-4 —
for the reason PH-1 shipped one screen rather than three: a tab that only says "coming soon" is
advertising.

⚠ **Favourites is the only smart album.** Places and dates are PH-3 and label albums wait on IN-3b,
so the tab shows one derived row rather than a shelf of greyed-out promises.

⚠ **Favourites and a manual album are one screen**, differing in the single way the user cannot
change: Favourites has no membership, so `editable` is false and the remove gesture is *absent*
rather than disabled.

### 12.2 The line the UI is built to keep

Long-press in album detail removes from the album. It cannot delete a photograph — not because the
screen is careful, but because `RemoveFromAlbum` cannot reach `:file-service` at all (§11.3). Two
view-model tests and one render test hold that line from the other side, and the empty-album copy
says the thing users get wrong about albums everywhere: *"an album is a list of photos you choose —
it doesn't copy or move them."*

⚠ A photo with no `contentId` cannot be filed, so the feed does not *offer* the gesture for one:
`onLongClick` is null rather than a lambda that fails. The repository and the op refuse it too —
three layers, because a UI that advertises an action which always fails is worse than one that
never offered it.

### 12.3 ⚠⚠ Wired into Control, in the same slice

`:photo-service` is in `ProxyModule`'s schema list and in all three `PlatformProxyModule`s
(`initPhotoService()` + the factory), with the dependency declared for **iosMain** as well.

That is not routine plumbing — it is the repo's most expensive recurring mistake, recorded twice in
the file itself: Calendar shipped four phases before anyone noticed it was absent here (*"every
write failed and every synced row stayed invisible"*), and inference shipped four slices the same
way. `inference-service.md` §17 states the rule: **a service is shipped when it is in this list, not
when its tests are green.** The iOS half has its own precedent — CA-5c wired iOS but declared the
dependency only for Android and desktop, so iOS failed to compile at all and no `./gradlew test`
could catch it. Hence `iosKotlinCheck` in this slice's gate.

### 12.4 The read that costs the most, and why it is honest

`favourites()` walks the feed and keeps what matches, because **there is no index from a content id
back to a file**. The set of favourites bounds the result, not the work — so this is the one read
in the app whose cost scales with the library rather than with what it returns. It is affordable
now and it is the first thing PH-G3's index would fix; it is written as a plain loop rather than
hidden behind a helper so that stays obvious.

The same shape appears once more, deliberately: resolving an album entry whose path hint is stale
falls back to a content-id walk **and re-adds the entry with the path it found**, so a moved photo
costs one walk ever rather than one per read.

### 12.5 Not in this slice

Drag-to-reorder and the cover picker. Both are UI-only — `AlbumEntry.position` is already sparse so
an insert is one write, and `Album.coverContentId` already exists with a documented "first entry"
fallback — so they wait for PH-3 rather than padding this one.

## 13. As built — PH-3: the derived sections (2026-08-21)

### 13.1 One pass, not one scan per place

Places, years and videos are **queries over a feed read that already happened**, computed in the
view model and carried into the detail screen.

⚠⚠ The obvious implementation is one `photosNear` per place. That op is an O(files-in-realm) scan
by its own KDoc, so a Places list of twenty would walk the realm twenty times. Instead the feed's
projection now carries `File.CaptureLatE7` / `LngE7`, and assigning each photo to its nearest place
is arithmetic — which lives in the Compose-free module and is tested without a store.

⚠ Nearest place, not every place in range: two cafés fifty metres apart would otherwise both claim
the same photograph, and a user reading either would find a picture they did not take there.

⚠ The distance is a **haversine**, not a flat approximation. A flat one is right at the equator and
wrong in Europe — the class of bug that ships because nobody's test data left the Atlantic. There is
a test asserting a degree of longitude shrinks from ~111 km to ~72 km at 50°N.

### 13.2 ⚠⚠ Why the geocoding hook (PH-G7) is not here

The gap says a photo's coordinate should be reverse-geocoded into a `Place`. It cannot be, from
Photos, today: **`ReverseGeocoder` lives in `:timeline-shared-data`** — a Timeline *app* module.
Photos depending on it would be an app-to-app dependency, and lifting it into a platform home
(`:place-service-common` is the obvious candidate) is a refactor that touches Timeline's geocoding
rails, its Android/desktop actuals and its consent gate. That is a platform slice with its own
review, not a paragraph of a Photos phase.

The consequence is worth stating plainly rather than hiding: **Photos groups photos by places
Timeline already captured *and named***. A user who has never run Timeline has no places, so the
section is legitimately absent for them; a photo taken somewhere they never dwelled belongs to no
place even though it carries a coordinate. There is a test for exactly that case, because the
tempting alternative — an "Unknown place" bucket — tells the user nothing they can act on.

### 13.3 The sections, and what each refuses to do

- **Places** — named places only. An unnamed `Place` (its `label` is empty until something
  geocodes it) is dropped rather than shown.
- **Years** — one row per year that has photos, newest first. ⚠ `yearOf` is injected, like
  `groupIntoDays`' day-start: the logic module carries no date-time dependency, so the shell
  answers the timezone question.
- **Videos** — present only when there are some. An empty row is a promise, not a feature.
- ⚠ **Label albums are still absent** and stay absent until IN-3b lands a real backend. The tab
  shows what it can fill.

A derived section is **not editable**: removing a photo from "2026" is not a thing a user can mean,
so the detail screen's remove gesture is absent there exactly as it is for Favourites.

### 13.4 What PH-3 did not do

- **The map header on place detail.** S6 wants one; it would pull `ui-map` / `:basemap-core` into
  Photos, which is a dependency decision (and an offline-regions one) rather than a layout task.
  Place detail is the album-detail screen until that is taken deliberately.
- **Drag-to-reorder and the cover picker**, still. Both remain UI-only — `AlbumEntry.position` is
  sparse and `Album.coverContentId` exists — and both keep losing to work that unblocks something.
- ⚠ The Albums tab's refresh now walks the whole feed once (`collectFeed`). It is the same missing
  index PH-G3 names, it is written as a plain loop so it stays visible, and it is the second read in
  this app whose cost scales with the library rather than with what it returns.

### 13.5 One small platform addition

`Place` gained the projected-row accessors it was missing (`label`, `city`, `countryCode`, `latE7`,
`lngE7`) — it had only `placeId` and `labelSource`, and a `select { fields(...) }` chain yields
`Entity<Place>`, not `Place`. The `Calendar` accessor block is the precedent for keeping the set
complete.

## 14. As built — PH-4a: Settings over the shared registry (2026-08-21)

### 14.1 ⭐ The toggle is real, and that was the thing to check first

The obvious risk in giving Photos a Settings tab over Drive's backup was building a screen whose
switch does nothing. It does something: `SyncedFolderController` reads `IngestSource.enabled`
before every source's pass — *"honour Control's central kill switch for this source before the
shared gate"* — so switching a source off in Photos genuinely stops it, in whichever app owns the
drain.

⚠⚠ **But it is a kill switch, not the master toggle, and the screen says so.** The owning app's own
backup switch stays the primary gate, and the registry read is deliberately *fail-open* — an absent
row or a read error means enabled — so switching a source **on** here cannot force backups to run
while Drive's backup is off. The header carries one sentence to that effect ("Backup itself is set
up in Tenshu Drive"), because a UI implying symmetry would be advertising an "on" that sometimes
does nothing.

### 14.2 `:app-ingest`, and one copy of the startup-race fix

A new module beside `:app-notify` / `:app-reachability` / `:app-outbox`, holding the registry
client (`IngestSources` + `IngestSourceRegistry`) and **`DeviceIdentity`, moved out of
`:drive-shared-logic`**.

`DeviceIdentity` is the fix for *"screen opened before sync identity was up, and stayed on the
defaults for the rest of the session"*. Every per-device row is keyed on it, and Photos needed the
same answer — so it moved rather than being forked. ⚠ Two copies of a startup-race fix is how one
of them gets repaired and the other does not.

⚠ The registry client is behind an **interface** (`IngestSources`): the distinction most worth
testing on this screen is "could not read" versus "nothing is backed up", and proving that must not
require a proxy.

### 14.3 ⚠ `:ui-files` was not created, deliberately

PH-D3 called for extracting the stateless picker pieces into a shared UI module. Photos' Settings
is the **only** consumer today — Drive's screen is not being rewritten in this slice — and a module
with one consumer is not an extraction, it is a detour. The repo makes this argument about itself
already (`:calendar-shared-logic`'s removed JS target: *"Nothing ever consumed it"*). The extraction
lands when Drive's screen actually moves onto it, which is PH-4b's natural companion.

### 14.4 ⚠⚠ What PH-4a cannot do: add a source

Photos can **manage** sources; it cannot create one. Registration happens in the drain
(`SyncedFolderController.registerSource`), and the drain is still Drive's.

Moving it is PH-4b, and the obstacle is a seam rather than effort: the controller takes
`FilesRepository` — Drive's entire repository interface — while using exactly four of its methods
(`ensureBackupDestination`, `storageStatus`, `uploadForBackup`, `withBulkUploadTask`). A neutral
home needs that narrowed to a `BackupDataPlane`-shaped interface first, and the move then touches
Drive's DI, its UI, the Android FGS launcher and the iOS surfaces — with HR #8's smoke gate on real
hardware, which is not something this session can run.

Until then the empty state points at Drive rather than offering a picker that would write a row
nothing reads.

### 14.5 The status line

A source that is **failing** reads as a problem, not as paused: the two look identical in a list
and mean opposite things. Off / Backing up (with a count) / Up to date / Out of space / Waiting for
Wi-Fi or charging / Idle are the other states, each straight from `IngestSource`'s own constants.

## 15. As built — PH-5: the trash (2026-08-21)

### 15.1 Deleting is a move, and that is what makes it cheap

`/Trash/<contentId>-<name>`, via the existing `MoveFile`. The bytes, the `contentId` and the file's
history stay put, so restoring is another move rather than a re-upload — and `TRASH_ROOT` lives in
`:file-service` beside `BACKUP_ROOT`, for that constant's stated reason: several components must
now agree on it and none of them owns it.

A `TrashedPhoto` row in `:photo-service` remembers where the file came from, because **a move
forgets**. ⚠ Recorded *after* the move: a record with no file is harmless (restore finds nothing and
clears it), while a file in the trash with no record is still recoverable by hand from Drive. The
reverse order gets both wrong.

⚠ Photo-service is version **2** now — its first reshape, and the point where HR #9 starts applying
to it. `:file-service` goes to **7** for the derived `File.Trashed` property. Both migrations are
empty and both baselines regenerated to additions plus version churn; no field moved.

### 15.2 ⚠⚠ PH-G16, the obligation the trash created, is closed

`photosCapturedBetween` and `photosNear` are the realm-wide reads **Timeline and Mia** make. Left
alone, a photo the user deleted would keep illustrating a Timeline moment and answering a Mia
question — and nothing would fail, because a moment with a picture in it looks like a working
feature. Both ops now skip anything under the trash, with tests.

⚠ The prefix check is `"$TRASH_ROOT/"`, not `startsWith(TRASH_ROOT)`. The second would silently
empty a user's `/Trashcan` folder out of the feed, out of Timeline and out of Mia, with nothing
anywhere saying why. There is a test named for exactly that.

⚠ The feed excludes the trash **in the chain**, through the derived `File.Trashed` property, because
the DSL has no negation and filtering client-side after the page would hand the pager a short page
and end the feed early. ⭐ `File.notTrashed()` exists because `equalTo false` — the obvious way to
write it — compares a Kotlin `Boolean` against `Data` and `equalToLogic` returns false for *every*
row, silently emptying the feed rather than failing. The helper makes that unwritable.

### 15.3 ⚠⚠ The third falsified claim: PH-G17

This doc said permanent delete *"reopens the re-ingest loop"* — that the drain would see an asset it
has no record of and the deleted photo would come back — and called shipping without a device-local
deleted-asset record "a bug".

It is not so. The backup cursor is a **monotonic watermark**: `listNewItems(bucketId, sinceCursor)`
returns items *strictly newer* than the cursor, and on Android the cursor is MediaStore `_ID`. An
already-uploaded asset is below the watermark and is never looked at again, whatever happens to the
realm copy — editing it does not move `_ID` either.

What survives is narrow and arguably correct: the photo returns if the bucket's cursor is **reset**
(app data cleared, the bucket re-selected, a new device), or if the user re-imports it into the
gallery, where it is genuinely a new asset. PH-5 therefore ships without the deleted-asset record,
and PH-G17 is rewritten rather than carried.

⭐ Three claims in this doc have now been falsified by building them — the `DataId` album key
(§11.2), the geocoding hook's shape (§13.2) and this one. That is the phases working: the doc was
reasoned, and each slice tested the reasoning against the code.

### 15.4 One place can destroy a photograph

Trashing is reachable from the viewer; **deleting for good is only reachable from the Trash screen,
behind a dialog that names what is lost** — "This can't be undone. The photo is removed from all
your devices." — rather than asking "Are you sure?". Tap in the trash restores (the safe action, and
the one a user in the trash usually wants); the destructive one takes a long press *and* the dialog.
Render tests pin the copy, that backing out destroys nothing, and that the Empty action is absent
when there is nothing to empty.

⚠ A photo with no `contentId` cannot be trashed — there is no key to record it under, and a trash
entry that cannot be restored is worse than a refusal.

### 15.5 Not in this slice

Auto-purge after a retention window (it needs the bgproc scheduler PH-G8 waits on — until then the
trash is emptied by hand, and the screen says so), and `retention-quota-model.md`'s P3 reclamation,
which is built but INERT behind two flags.

## 16. As built — PH-6: the content index, the skip and the Duplicates surface (2026-08-24)

Two commits: the index and the review surface, then the ingest-time skip. PH-G3 required them to
ship together — *"the setting and the index ship together, or neither does"* — and they do.

### 16.1 `File.ContentHash` finally has a consumer

Since content-addressing Phase 1 the hash has been computed on every upload, stored on the row and
synced eagerly, and read by exactly two things: the manifest integrity anchor and the slice
snapshot producer. Nothing ever asked *"is this content already here?"*, which is the question the
property's own KDoc says it exists to answer.

`File.contentHash` is now a chain accessor, the twin of `File.createdAt` from PH-1a, so the hash is
expressible in a predicate.

### 16.2 ⚠⚠ An accessor is not an index, and that gap is the whole design

The engine has **no property index of any kind**. `SearchImplementation` is a linear walk over the
traversed list; `filter` and `searchAll` are peephole-fused but still scans. So making the hash
expressible does nothing for cost: asking the chain once per candidate asset is `O(library)` per
question, and a drain of *M* assets over *N* files would be `O(M·N)`.

`ContentIndex` (`:app-ingest`) is therefore the actual PH-G3 deliverable: **one walk, grouped by
hash**, `O(N + M)` for the same drain. It lives in the shared module because both callers need it —
Photos' Duplicates screen and Drive's drain — and PH-4a's argument applies unchanged: two copies is
how one of them gets a fix the other does not.

It carries three rules that are easy to get wrong and invisible when you do:

| rule | why |
|---|---|
| a file with **no** stored hash is omitted, never filed under `""` | a group of "everything we could not identify" is the one grouping that is actively wrong |
| a **trashed** file is excluded | consistent with every read path since PH-G16 — and a copy in the trash must not go on blocking the slot the deletion was meant to free |
| a failed load returns **`null`**, not an empty index | "could not look" and "the realm is empty" are opposite instructions to a skip |

⚠⚠ Grouping stays client-side, on a device that already holds the plaintext and therefore learns
nothing from it. Pushing it into storage or the relay would build exactly the confirmation-of-file
oracle `File.ContentId`'s randomness exists to prevent (HR #1, threat model A1). **No cross-user
dedup, ever** — the same sentence `file-content-addressing.md` §3.1 already carries.

### 16.3 The Duplicates surface shows; it does not act

S4 gains a **Duplicates** entry, placed with Favourites rather than among the smart albums, because
opening it yields *groups with an action* rather than photos — the shape Apple's Utilities section
settled on for the same reason (§5, PH-D2).

Per PH-D2, and pinned by tests:

- The **feed is unchanged.** Two files, two tiles. Nothing is hidden.
- A tap means **keep this one**, never "delete this one", and it opens a confirmation that names
  the survivor rather than the casualties.
- The permanent-delete checkbox is **off by default** and says what each mode costs — the
  reversible action frees no space, which is the entire reason the irreversible one is offered
  (PH-D4's "out of quota" case).
- There is no "clean up all".
- The empty state states its own limit: *"We compared N files. Edited or re-encoded copies aren't
  counted — they're different files."* Exact hashes only; perceptual matching is a later inference
  phase's problem.

`PhotosRepository.deletePermanently(photo)` is new — the second call in the app that destroys a
photograph, and the first that does not require a trash record (PH-5's `deleteForever` is keyed on
one). §15.4's "one place can destroy a photograph" is therefore now **two**, both behind a dialog
that names the loss.

⚠⚠ **Amended 2026-10-02 (`photos-ios.md` §8.18) — the surface acted, and said nothing about how it
went.** `keepOnly` dropped every `trash` / `deletePermanently` answer, so a refused write looked like
one that worked (§22's shape, on the one screen with a permanent-delete switch); nothing was bounded,
so a lost reply left the row disabled — or the first read's spinner up — for good; the confirmation
of a tap on another set while one ran was dropped in silence; Back mid-loop cancelled the remaining
copies; and Collections was never told. Now, in `DuplicatesViewModel` / `DuplicatesUiState` /
`DuplicatesCopy` for both shells:

- Every write goes through `boundedWrite` and every outcome short of "all of them" is a
  `DuplicatesUiState.report` — outcomes, never where a copy is. The loop stops at the first unanswered
  write, and that copy is locked for the screen's life: never written again and ⚠⚠ **never offered as
  the copy to keep** (its delete may still land; removing the others would then leave none).
- **What was confirmed is what runs.** The confirmation (`DuplicatesConfirmation`) carries the
  survivor, the copies it counted and the mode it named; a switch flipped afterwards changes nothing,
  and a set that a re-read changed under the dialog writes nothing and says so.
- **"Could not look" has a retry and its own sentence**, and replaces the list even when groups from
  an earlier read are still held — a list that could not be re-read is not one to act on.
- A copy with no content id is not sent to the trash (the record is keyed on it); the report says why.
- The two modes never share a word that matters: *"Move the other copies to the trash? … You can
  restore them"* / **Move to Trash**, against *"Delete the other copies for good? … This can't be
  undone"* / **Delete for Good**. Counts are pluralised ("1 other copy", "1 set … out of 1 file").

### 16.4 The skip is the third dedup layer, and the three are not interchangeable

The drain already had two, and both are **device-local asset identity**:

| layer | keyed on | stops |
|---|---|---|
| per-bucket cursor | MediaStore `_ID` / PhotoKit id | this device re-offering an asset it handled |
| `seenUris` | the item's uri | one asset uploading once per selected album, in one run |
| **PH-6** | **`File.ContentHash`** | **a photo another device already uploaded** |

Only the third is cross-device, and only the third has to read the candidate's bytes. It is ordered
**after** the other two on purpose: they are free, this one is not.

`DuplicateContentGate` is the seam (`NoopDuplicateContentGate` by default, matching the file's
existing `Noop*` collaborators). It opens **once per drain**, not once per source, and hands back a
`DuplicateContentView` that also remembers hashes accepted *during* this run — so two identical
assets in one pass upload once, which the realm snapshot alone could not catch.

⚠⚠ Every uncertainty falls back to **uploading**: setting off, identity unresolved, index load
failed, hash unreadable. A gate that reads "could not look" as "already have it" does not skip a
duplicate — it drops a photograph. `anUnreadableCandidateIsUploadedRatherThanDropped` holds it.

### 16.5 ⚠⚠ The bug the test found, and why it would have shipped silently

`contentHashOf` on the platform delegate must produce a value **byte-identical** to what
`UploadFileImplementation` stamps — same `IncrementalSha256`, same `FileContentChunkSizeBytes`
fold. `foldPlatformContentHash` exists so no platform has to get that right three times.

The first draft folded whatever one `read` returned. That is wrong: a real stream — notably one
over an Android content provider — routinely returns fewer bytes than asked for while more remain,
so the chain would cut at the wrong boundary. The resulting hash matches **nothing**, and *nothing
fails*: no crash, no error, no log line. The skip simply never fires, and the user's setting
silently does nothing.

`PlatformContentHashTest` dribbles a file out 64 bytes at a time and compares against
`wholeFileHashOf` — the production construction, not a pinned literal, because a pinned hex string
would go on passing after the emitter changed. It caught the bug on the first run.

### 16.6 One setting, declared where both apps can see it

`IngestPreferences.SkipExistingContent` (DEVICE-scoped, **default on**) lives in `:app-ingest`, not
`DrivePreferences`. The drain is Drive's, the switch is Photos' (PH-D3's table lists it as a Photos
extra), and a preference declared in one app's module cannot be read by the other. Two ids for one
question is how a toggle comes to lie about what the engine is doing.

Default-on matches what both Google Photos and Apple Photos do at the door, and it is the half of
PH-D2 that actually prevents duplicates rather than cleaning them up afterwards.

The Photos switch is seeded from the preference's own default rather than `false`, so it never
renders in the position that would misdescribe the drain while the stored row resolves — the same
seeded-read trap `BackupPreferences` documents, applied to a switch instead of a list.

### 16.7 Not in this slice

- **The feed's "N copies" badge.** PH-D2 allows one *"once PH-G3's index exists"*. It does now, but
  a badge means loading a whole-library index behind the app's most-used screen, on every open.
  That is a worse trade than the badge is worth; it wants a cached or incremental index first.
- **The two library-scaling reads §12.4 and §13.5 name** (`favourites()` and the Albums refresh).
  `ContentIndex` is the right shape to fix them, but only if it is *cached* — building it per call
  replaces one walk with another. Caching it means invalidation, which is its own slice.
- **A hardware pass.** No emulator or device ran this. The skip's Android and iOS halves —
  `ContentResolver.openInputStream` and the PhotoKit read — are compile-verified and unit-tested
  through the shared fold, but *"does it actually skip on a real camera roll"* is unanswered here.
  HR #8's smoke gate and a real drain are owed.

## 17. As built — PH-7: what a file says about itself (2026-08-24)

### 17.1 ⚠⚠ The bug was the KDoc, not a missing feature

`readExifLocation` shipped with a note explaining why JPEG-only was fine:

> *⚠ JPEG only. HEIC/HEIF keeps its EXIF in an ISO-BMFF box rather than an APP1 segment; iPhone
> photos therefore return `null` here. That costs nothing today — iOS has no gallery backup, so the
> realm's photos come from Android — and it is a self-contained addition when it does.*

That reasoning was correct **when it was written** and became wrong the day `IosMediaSourceProvider`
shipped with `ios-drive-parity.md` P6b. Nothing failed. No error was logged, no test went red, no
upload was rejected. Every iPhone photo simply landed with no coordinate, and the Places section
was empty for iOS users with nothing on screen to explain why.

⭐ Worth recording as a *class*, because this is the third time on this track: a comment that
carries a load-bearing assumption about **another** part of the system has no way to notice when
that assumption expires. The falsified claims in §11.2, §13.2 and §15.3 were all found by building
against them; this one was found by reading a KDoc that no longer described reality.

### 17.2 One TIFF walk, four containers

| container | dimensions | EXIF | duration |
|---|---|---|---|
| JPEG | `SOF` frame header | `APP1` segment | — |
| HEIC / HEIF / AVIF | `ispe` box | `Exif` item, via `iinf` + `iloc` | — |
| MP4 / MOV | `tkhd` box | — | `mvhd` box |
| PNG | `IHDR` chunk | — | — |

⚠⚠ **The tag walk is shared, deliberately.** JPEG and HEIC embed the *same* TIFF block and differ
only in the way in, so `ExifTiff.kt` is one walk with two entrances. Two walks would drift, and the
drift would be invisible — a tag read correctly from a JPEG and wrongly from a HEIC looks exactly
like an iPhone that never recorded it. Same argument as `PlatformContentHash` one slice earlier.

Three details that a plausible implementation gets wrong, each pinned by a test:

- **`PixelXDimension` is SHORT on some cameras and LONG on others.** Reading it always as LONG
  picks up two bytes of the next field and yields an enormous, *plausible* number — worse than
  absence, because a wrong width still renders.
- **`iloc`'s field widths are declared in the box.** Offsets and lengths are each 0, 4 or 8 bytes
  wide, and `construction_method` exists only from version 1. A hardcoded layout reads one iPhone
  generation and misreads the next.
- **The frame header beats the Exif dimension.** A re-encoder resizes the image and leaves
  `PixelXDimension` at the original value; `SOF` is the encoded truth.

⚠ Two documented heuristics rather than full resolution: the largest `ispe` is taken as the primary
image (proper resolution means following `pitm` and `ipma`), and the largest `tkhd` as the video
track (an audio track's is 0 × 0). Both are right for every real file and both are marked as
choices in the code, not left to look like oversights.

### 17.3 Six additive scalars, and why they are scalars

`PixelWidth`, `PixelHeight`, `Orientation`, `CameraMake`, `CameraModel`, `DurationMs` — stored value
properties on `File`, file-service **v8** with an empty migration (HR #9). The baseline confirms the
claim rather than the other way round: **six new INLINE properties, no positional reshape**, exactly
as v2, v6 and v7 each recorded.

Scalars, not a composite, for `CaptureLatE7`'s reason: a value property is a `ValueSchema`, and a
reference property would mean minting an entity per photo's metadata.

⚠⚠ **Absent is a value.** Nothing writes a default — not `0 × 0`, not orientation `1`. A file whose
container this codebase cannot read must stay silent, because "we could not read it" and "it is
upright" are different facts and only one of them is evidence.

### 17.4 ⚠ No backfill, and it cannot have one

The values are read from the uploaded bytes, which the proxy does not keep. A migration that
backfilled would have to re-read every file in the realm — and on a device holding no bytes, fetch
them from the content plane first. So a pre-v8 file gains nothing until it is re-uploaded, and the
info sheet on an existing library is mostly a name and a path. Said plainly in the schema note as
well, because *"why is my info sheet empty"* is the question this will produce.

### 17.5 The info sheet, and the one rule it follows

S3's info sheet is the consumer PH-G4 was blocking, and the viewer's own KDoc said so: *"the info
sheet needs metadata that PH-7 adds."*

- **Read on open, not on view.** The read is a search of the realm for one row; paying it on every
  viewer open would slow the most-used screen in the app for a panel most people never open. It is
  also not projected into the feed — six more relations per page, read on almost no rows.
- **Absent omits the row.** Never "Unknown". A photo whose EXIF a messaging app stripped genuinely
  has no camera.
- **The displayed size is not the stored size.** A portrait iPhone photo is stored landscape with
  orientation `6`; the fold lives once, on `MediaMetadata.displayWidth`, so no screen can disagree
  with another about which way round a photo is.
- **A camera that names itself twice is printed once** — "Canon EOS 5D" beside make "Canon" would
  otherwise read "Canon Canon EOS 5D". The stored values stay verbatim; only the line is tidied,
  which is the presentation-side fold `File.CameraMake`'s KDoc reserves for a consumer.

### 17.6 ⚠⚠ Two defects in this document, found while checking what was left

- **S2 (Search) is unbuilt and was unowned.** PH-1's row deferred it to PH-3 — *"it wants the label
  sources to be worth a bar"* — but PH-3's row never claimed it and PH-3 did not ship it. There is
  no search screen in the app. Source 1 (name/path, Drive's `FileSearchBar` verbatim) works today
  and needs no other track; sources 2 and 3 are IN-3b-gated, and §3's S2 already specifies the
  screen to read as complete without them. Both rows now say so.
- **The phase map was stale**: PH-2 and PH-3 carried no ✅ despite §11–§13 being as-built sections.

⚠ Recorded rather than quietly fixed because the mechanism is worth knowing: a phase that *moves*
an item names the destination in its own row, and nothing checks that the destination agreed. That
is how S2 fell between two phases that were both marked done.

### 17.7 Not in this slice

- **ISO, aperture, lens, and EXIF capture time.** `File.CreatedAt` is still seeded from the source
  file's mtime rather than `DateTimeOriginal`, which differ for a photo copied between devices.
  Additive, and wants its own decision about which wins.
- **Using the orientation when *rendering*.** The sheet reports the display size correctly, but the
  hero image is drawn from a preview the proxy generated — whether that pipeline already applies
  orientation is unverified here, and asserting it needs a real photo on a real device.
- **A hardware pass.** The HEIC walk is tested against hand-built containers whose every byte is
  written out in `MediaFixtures.kt`; it has never seen a file an actual iPhone produced. That is
  the one thing that would make this phase's headline claim — *"iPhone photos now carry their
  coordinate"* — a verified statement rather than a well-tested one.

## 18. As built — S2: search, and five services nobody had registered (2026-08-24)

The orphan §17.6 found. PH-1 deferred it to PH-3, PH-3 never claimed it, and it had sat unbuilt
through five phases that all reported done.

### 18.1 One bar, three sources, and only one of them can run

| source | how | available today |
|---|---|---|
| 1. name / path | `traverse().filter { name.contains(q, ignoreCase = true) }` | **always** |
| 2. label | `MediaLabel` rows, joined back to files by path | never — nothing writes one |
| 3. semantic | `photosLike(text)` | ⚠ **live since 2026-09-17** — a published model, indexed on a real device. Ranked above a measured relevance floor (`inference-service.md` §35); before that floor every query returned `limit` photographs, each badged "looks like this" |

⚠⚠ **So the screen is built around source 1 carrying it alone**, and reads as *complete* that way
rather than as a feature with two thirds missing. Its idle state says "Search by file name" —
what actually works here — instead of promising three sources no device can run.

Sources 2 and 3 are wired, tested and inert. That is the point: the day IN-3b ships a backend they
begin contributing with no further change here.

### 18.2 ⚠⚠ A refusal is not "nothing matched"

`photosLike` **throws** `SemanticSearchUnavailable` rather than returning an empty list, precisely
so a caller cannot collapse *"this cannot run here"* into *"there is nothing"* — the mistake
`mia-query-layer.md` §14.1 names and the reason IN-4b made the op throw at all.

`PhotoSearchUiState` therefore carries `semanticStatus` **apart from** `hits`, and the screen puts
the reason above the results rather than instead of them: name search may well have found plenty,
and a refusal from source 3 is a footnote on a working screen, not an error state. Four reasons get
four sentences.

⚠ Photos' wording is deliberately **not** Mia's. The reasons are shared
(`SemanticSearchUnavailable.Reasons`); the sentences are not, because Mia answers in the first
person in a chat and this is a note under a search bar. ⚠⚠ None of them names an action, and that
is not an oversight — nothing in any app writes `InferenceCapability.realmConsent` /
`deviceConsent`, so there is no screen to send anyone to. Inventing a link would be worse than
saying plainly that this device cannot do it. `AskMiaImplementation` made the same call for the
same reason; revisit when IN-3b ships the capability UI.

### 18.3 One photograph, one row

Three sources over one library agree often. A hit found by all three is **one** row carrying all
three, never three rows — a list that repeats a photograph once per source reads as a broken
search rather than a thorough one.

Order and badge both follow how *checkable* the claim is: a name match the user can verify at a
glance leads and carries **no badge at all** (labelling the common case is noise); a label names
the word a model chose; "looks like this" is the least inspectable and comes last. A hit nobody
could explain from the filename always says what put it there.

⚠ Search is capped, not paged — `contains` gives no ordering to key on — and the screen **says**
when the list was cut. A screen quietly showing 60 of 900 is the dishonest version.

### 18.4 ⚠⚠ The finding: five services the app reaches and never registered

Wiring source 2 meant adding `:inference-service` to Photos' client graph, which meant reading the
graph — and it turned out to register **two** of the **seven** services this app calls.

| phase | service | what a user actually saw |
|---|---|---|
| PH-3 | `:place-service` | the Places section, permanently empty |
| PH-4a | `:ingest-service` | Settings saying *"Can't reach Tenshu Control"* |
| PH-6b | `:preference-service`, `:sync-service` | the duplicate-skip toggle stuck at its default |

`TenshuEnvironment.getService` **errors** on an unregistered schema — *"No schema registered for
service id"* — and every caller in this app catches. So none of it crashed. Each looked like a
different, plausible, ordinary bug: no places yet, Control not running, a setting that would not
stick.

⚠⚠ **Why nothing caught it.** Each phase added a Gradle dependency to `:photos-shared-logic`, which
compiles perfectly, and nothing connected that to a runtime list two modules away in
`:photos-shared-ui`. The compiler cannot see the gap; a view-model test cannot either, because both
sides of it are correct in isolation — the code is right and the graph is short.

⭐ It is the same shape as PH-2b's own commit title, *"albums on screen, and the wiring that fails
silently"*, and it happened three more times after that. So the fix is not the five lines:
`photosSchemaConfigs` is now a top-level val and `PhotosClientGraphTest` asserts every service the
app reaches is in it, failing with the service id, the phase that added it, and the sentence *"this
does NOT crash at runtime"*. Removing one registration turns it red, which is how it was checked.

⚠ It is a list a human maintains — the test cannot derive "services this app calls" from source —
so it carries an instruction rather than a claim: **when a repository reaches a new service, add it
here and to `photosSchemaConfigs`.**

### 18.5 One small platform addition, and a first consumer

`MediaLabel` gained the projected-row accessors it was missing (`labelKey`, `filePath`,
`capability`, `label`, `confidencePerMille`, `producedBy`) — the same gap `Place` had until PH-3,
and the `Calendar` block is the precedent for completing the set rather than adding one per caller.

`TenshuClient.inferenceService` is new too: everything before Photos reached that service
**proxy-side**, through Mia's own lowering, so the client Ro shipped with IN-4b and nothing had ever
asked a client for it.

### 18.6 Not in this slice

- **Searching video.** The name filter keeps `category equalTo Image`, matching the feed. Videos are
  a smart album today; including them here wants the same decision taken in both places at once.
- **Search inside an album or a place.** One bar over the whole library, as S2 specifies.
- **Ranked name matches.** A `contains` hit has no score, so order is insertion order. Fine while
  the list is capped and short; it will want thought when it is not.
- ⚠ **The three inert sources are unproven end-to-end.** Source 2's join and source 3's refusal
  path are tested against a mock; no device has ever returned a `MediaLabel` row or a
  `SimilarPhoto`, because none can. What IN-3b lands, this will meet for the first time.

### 18.7 Amended by the iOS Search tab (2026-10-03, `photos-ios.md` §8.19)

Every rule of the screen now lives in `:photos-shared-logic` — `PhotoSearchLayout` / `PhotoSearchCopy`
beside the view model — and Compose draws from it, as the iOS tab does. What changed for Android and
desktop, where it was wrong:

- ⚠⚠ **"Could not look" is a third answer.** A name or tag read that failed, threw or outlasted its
  bound (`SEARCH_TIMEOUT_MS`) used to fold into an empty list and read "No photos match". It is
  `PhotoSearchUiState.couldNotLook` now, drawn with *Try Again* (`PhotosRepository.searchByNameRead` /
  `searchByLabelRead`: `null` = could not look, §36's rule for search).
- ⚠⚠ **The spinner could stay for good** — a trailing space, or a letter typed and taken back before
  the debounce, un-settled the screen and then ran nothing. A question already answered keeps its answer.
- ⚠ **A newer query no longer waits behind an older scan**, and an older answer is never published
  over a newer one (cancelled, and a generation check behind the cancel).
- ⚠ **A result may only cite a photograph still in the library** — the label and "looks like" joins
  drop a path with no live `File` row OR under `/Trash` (`citablePhotoAt`), and the screen re-reads
  quietly on a return from the viewer (P0g's Photos half, `media-processing-pipeline-sweep.md` F12).
- A platform with no index says so as **off**, never asks the proxy, and never shows the consent
  sentence (`DescriptionSearch.NotBuilt` — iOS until PIO-7).

## 19. As built — PH-4b: the backup engine leaves Drive (2026-08-24)

§14.4 said Photos could *manage* a source but never *add* one, and named the obstacle precisely:
the drain took `FilesRepository` — Drive's entire repository — while using four of its methods. It
was right about the obstacle and right that the fix is a seam.

### 19.1 Five methods, and a test that keeps it at five

`BackupDataPlane` (`:app-ingest`) is what the drain actually needs: `withBulkUploadTask`,
`ensureBackupDestination`, `contentHashOf`, `uploadForBackup`, `storageStatus`. Drive's
`FilesRepository` now **extends** it and loses nothing; the drain no longer knows a file store can
list, preview, share, move, trash or pin.

⚠ The rule that keeps it narrow is on the interface: **does this serve one upload?** Nothing here
reads a listing or resolves a path for display. `BackupDataPlaneNarrownessTest` holds the count and
is meant to be annoying — the pressure to widen is constant and each step looks reasonable ("just
to check", "just for the log line"), and every one of them re-couples the engine to an app.

⭐ The practical measure: a `BackupDataPlane` test double is five short methods. Drive's
`FakeFilesRepository` is hundreds of lines. That asymmetry *is* the argument.

### 19.2 What moved

Into `:app-ingest` — `SyncedFolderController` and the whole engine around it (`BackupSource`,
`BackupDestination`, `NewFolderDetector`, `DuplicateContentGate`, `BackupNotification`,
`BackupEnvironment`, `MediaAccessProvider`, `IngestRegistry`, `MediaSourceProvider`,
`BackupPreferences`), plus Android's three platform providers so a phone can enumerate its own
folders from either app.

Stayed in Drive — `FilesRepository`, `TenshuFilesRepository`, every screen and view model. iOS's
`IosMediaSourceProvider` stays in `:tenshu-sdk-ios`, where the PhotoKit binding belongs.

### 19.3 ⚠⚠ Nine preference ids that must never change

`BackupPreferences` moved with the engine, and its nine keys moved into `IngestPreferences` —
**with their ids untouched**. They are still `drive.backup.enabled`, `drive.backup.buckets` and so
on.

Renaming them to something tidier would not migrate anyone's settings; it would **orphan** them.
Every paired device would read the declared default instead and quietly stop backing up — no error,
no migration, no way to tell from the outside. The `drive.` prefix is now a historical fact about
where a key was born, not a claim about who owns it, and that is a much cheaper thing to live with
than a silent reset.

### 19.4 Photos can add a source

Settings gains a folder picker on Android: it lists what `MediaSourceProvider` finds and writes
`BackupPreferences.setSelectedBuckets` — **the same device-scoped rows Drive's drain enumerates**.
A folder ticked in Photos is backed up by whichever app is running the drain, which is what §14.4
meant by "a picker that would write a row nothing reads" being the thing to avoid.

Three details:

- **Absent, not empty, where the device cannot answer.** Desktop's drain lives in Control's
  `FolderWatcher`, so Photos there binds no provider and the section does not render. An empty list
  would say "you have no folders", which is a different and false claim.
- **Read-modify-write against the stored row**, not against the screen's copy: the selection is
  synced and the drain may be reading it, so writing the screen's list back wholesale would clobber
  a change made elsewhere between the read and the tap.
- **The picker asks for the same `includeVideo` the drain uses**, so it cannot offer a folder the
  drain would then skip.

⚠ Photos' manifest gains the three read permissions and `READ_MEDIA_VISUAL_USER_SELECTED` (load-
bearing on API 34+ for the reason Drive's manifest spells out). It gains **no** INTERNET — it still
reaches the realm through Control — and deliberately **no** `ACCESS_MEDIA_LOCATION`: Photos does not
upload, so it never needs a photo's original bytes, and asking for a permission it cannot use would
be a claim on the user's location data with nothing behind it.

### 19.5 ⚠ What a mechanical move of this size actually costs

51 files across seven modules. Two things went wrong, both caught by the compiler, both worth
recording because the next such move will meet them:

- **A substring check is not a symbol check.** An "is this already imported?" test written as
  `"import ….MediaAccess" in source` is satisfied by `import ….MediaAccessProvider`, so the
  narrower symbol was silently considered present. Exact-line matching fixed it.
- **A name collision two modules away.** `:control-shared-logic` has its own, unrelated
  `StorageStatusModel`, and a symbol-name sweep happily added an import of *Drive's* to ten Control
  files. Nothing about the sweep could have known; the compiler did.

⚠⚠ **iOS is unverified.** Nine `iosMain` files reference the moved package and every import was
rewritten and read by hand — including the one constructor call that had to change
(`filesRepository =` → `dataPlane =` in `DriveBackupSurface`) — but `iosKotlinCheck` refuses on
Linux, so none of it is typechecked. This is the change most likely in the whole track to have an
iOS break in it.

⚠⚠ **And HR #8's smoke gate has not run.** This moves the Android backup engine between modules and
changes an app manifest; `compileDebugKotlin` passing is not the same as the app starting. A real
device pass is owed before this is trusted.

### 19.6 Not in this slice

- **`:ui-files`.** PH-D3's shared picker components. Photos' picker is a plain list of switches and
  Drive's screen is unchanged, so the extraction still has one consumer and is still a detour
  (§14.3). It becomes real when Drive's screen moves onto the shared pieces.
- **Photos running the drain.** Photos writes the selection; Drive's process still runs the loop.
  Two drains on one device would double-upload, so which app owns the loop is a decision — not
  something to fall into by binding a controller in a second app.
- **The desktop picker**, for the same reason its section is absent: Control owns ingest there.

## 20. As built — the shared index (2026-08-24)

§16.7 deferred this as *"caching means invalidation, which is its own slice"*. The slice turned
out to be mostly about **not writing an invalidation policy**.

### 20.1 ⚠⚠ A subscription, not a cache

A cache needs a rule for when it is stale, and every candidate is wrong somewhere: a TTL serves
stale answers for its whole window; a dirty flag set on local writes misses everything that
arrives by **sync** from another device, which for a backup app is most of it.

`.flow()` on a chain is a live subscription — the store re-emits the whole list on every change,
including synced ones. So `RealmContentIndex` is a `Flow<ContentIndex?>` and **the store owns
invalidation**, which is the only layer that can actually know.

Three bounds keep a whole-realm subscription affordable, and they are the `FitProjectionRepository`
/ `TimelineProjectionRepository` recipe rather than anything invented here:

| bound | what breaks without it |
|---|---|
| `shareIn` + `replay = 1` | every screen re-walks the realm; a "shared" index shares nothing |
| `WhileSubscribed(5 s)` | a backgrounded app holds a whole-realm read forever |
| `conflate()` | a sync block landing 200 files re-groups 20 000 rows 200 times |

### 20.2 ⚠⚠ The drain must not subscribe

The obvious next step — *"there is a shared index now, use it in the drain too"* — is wrong, and
wrong only under load. The drain **causes** the changes it would be watching: subscribing would
re-project the whole realm once per upload, so a 500-photo pass would do 500 whole-realm
projections where it now takes one snapshot. `DuplicateContentGate.openForDrain()` keeps calling
`snapshot()`; screens call `current()`.

### 20.3 The reads it replaced were worse than the doc said

§12.4 described `favourites()` as *"one realm walk, not one per favourite"*, and §13.5 said the
Albums tab *"walks the whole feed once"*. Both were optimistic. Each paged the feed until it ran
dry — and **a page is its own `traverse()` with its own sort**, so the real cost was one full-realm
walk *per page*: roughly 334 per read on a 20 000-photo library, and the Albums tab paid it twice
because `favourites()` runs alongside `collectFeed()`.

⭐ Neither comment was dishonest; both were written about a loop whose cost lives one layer down.
That is the argument for `SharedIndexReadsTest` **counting pages** rather than asserting results:
the results never changed, so nothing about the output could have revealed the cost.

`ContentIndex` gained `byContentId` — the index §12.4 asked for by name — and `all`, newest first.

### 20.4 ⚠ `null` means "could not look", and one screen needs it

`observe()` emits `null` for a failed read rather than an empty index. Flattening the two would
have been simpler for the Albums tab, which is happy to show nothing — but the Duplicates screen
has to be able to say *"could not look"*, and collapsing the distinction would have pushed it off
the shared subscription and back onto a walk of its own, which is the cost this removes.

### 20.5 ⚠⚠ A gap the deleted tests found

The first version of the test file exercised the **sharing policy** — one upstream for many
collectors, survives a tab switch, stops when nobody watches. It was deleted: those are
`kotlinx.coroutines` semantics, the tests drove a stand-in upstream rather than anything this class
decides, and re-testing a library reads as coverage without being any.

⭐ Writing them was still worth it, because it surfaced a real gap: `source()` had no `catch`. The
chain can **throw** rather than fail — `getService` errors outright on an unregistered schema, and
S2 §18.4 had just found five live instances of exactly that — and an exception on a *shared* read
reaches every screen collecting it. That is the one failure mode sharing makes strictly worse than
a per-screen read. It now emits `null`, and that is what the surviving tests pin.

### 20.6 ⚠ The feed's "N copies" badge, still not shipped — for a new reason

§16.7 blocked it on *"a cached or incremental index"*. That blocker is gone. A different one is
not: the feed is the **cold-start screen**, and the index is a whole-realm read. Putting it behind
first paint would make every app open pay for a projection, to tell the minority of users who have
duplicates something the Duplicates screen already tells them on request.

⚠ The tempting compromise — badge only when the shared read happens to be warm — is worse: a badge
that appears depending on which tab you visited first is a fact the user cannot account for.

### 20.7 Not in this slice

- **An incremental index.** The upstream re-emits whole and this re-groups whole. `O(N)` per
  change is affordable at conflated, screen-scoped frequencies; a diffed update is the next
  optimisation and wants the 20 000-row measurement PH-G1 still owes.
- **Sharing the index across apps.** Drive's drain builds its own snapshot in its own process.
  One index per process is right; a cross-process one is a cache with an invalidation policy again,
  through IPC.

---

## 21. As built — PH-8: the viewer is a pager, not a photograph (2026-08-30)

The viewer S3 described — *"full-screen pager over the current result set"* — shipped in PH-1b as a
hero and a way back, with its own KDoc admitting the gap: *"the pager needs the feed's list handed
down"*. This slice hands it down.

### 21.1 The question that decides the design

*How does a gallery app show "the next photo"?* The tempting answer, and the wrong one, is that the
detail screen knows how to fetch its successor. Every mainstream implementation does the opposite:
**the detail screen is the same list, in a different presentation.** Google Photos' viewer is a
`ViewPager2` over the same paging source the grid is bound to; Apple's is a paged collection view
over the same fetch result. The tapped row supplies a *position*, not a payload.

The reason is not aesthetic. A viewer that fetches its own successor must answer *"successor in
what?"* — and this app can open the viewer from four different lists (the feed, an album, a smart
section, a search result), three of which are not "the library in capture order" at all. A viewer
with its own cursor would page the feed while the user was looking at search results, and there is
no version of that which is not a bug.

So `PhotoViewerScreen` takes `photos: List<Photo>` and `initialIndex: Int`, and `Destination.Viewer`
carries a **source** rather than a photo.

### 21.2 ⚠⚠ "Endless" is the *caller's* paging, reached through the pager

The feed's list grows: reading past the loaded window appends a keyset page (K-4 replaced
`loadMore()` with Paging's access hint). The viewer does not
duplicate any of that. Two lines make the swipe endless:

- the pager's `pageCount` is a **lambda** over `photos.size`, not a count captured at first
  composition; and
- within `PREFETCH_PAGES` (2) of the end, the pager calls the same `onLoadMore` the grid's scroll
  fires — `snapshotFlow` + `distinctUntilChanged`, the feed grid's recipe verbatim.

The feed's list is therefore read **live** off `feedState.photos` rather than snapshotted into the
destination: `loadMore` appends, the count lambda sees it, and the photograph that did not exist
when the user tapped is simply there. ⚠ `onLoadMore` is `null` for an album, a smart section and a
search result — those lists are whole when they appear, and handing them a callback would be a
promise nothing keeps. A test pins both halves.

### 21.3 ⚠⚠ Each page owns its bytes — the one rule that bounds memory

The old view model loaded the hero into `PhotoViewerUiState.bytes`. With a pager that is no longer
merely inelegant, it is unbounded: a view model has no window, so every photograph swiped past would
stay decoded for as long as the viewer is open. A 1024 px hero is ~4 MB decoded, and a minute of
swiping is a gallery of them.

So the bytes moved into the page composable, keyed on the path — **`PhotoTile`'s rule, promoted from
a tidiness argument to a memory bound.** The pager composes a window (`beyondViewportPageCount = 1`)
and drops the rest, so the number of live heroes is a property of the window, not of the swipe.

⚠ That same `beyondViewportPageCount = 1` is what makes the swipe feel instantaneous: the
neighbour's read is already in flight before the finger goes down. Fewer, and every swipe shows a
spinner; more, and the viewer holds decoded heroes nobody looked at. The prefetch test asserts both
directions — `p1` and `p3` read from page 2, `p5` not.

`PhotoViewerUiState` consequently lost `bytes` and `loading`, and with them the hand-written
`equals`/`hashCode` that a `ByteArray` field forced on it.

### 21.4 The info sheet has to follow the page, and clearing is the load-bearing half

The sheet takes the *name* from `state.path` and the *camera* from `state.details`. Move the first
without the second and it prints one photograph's dimensions under another's name — an assertion
neither row makes, and exactly the class of quiet wrongness PH-7 was written to avoid. So
`onPhotoShown` moves the path and clears the details **in the same emission**, and re-reads only if
the sheet is open. A test asserts the state *between* those two events, because that frame is where
the bug would live.

⚠ Swiping with the sheet closed reads nothing at all. PH-7's laziness was justified by "most people
never open the panel"; a pager turns that from a saving into a requirement, since a metadata read
per swipe would put a realm search on the app's most repeated gesture.

### 21.5 ⭐ A defect found on the way: Back always went to the feed

`onBack` was `{ destination = Destination.Feed }` for every entry point. Opening a photo from an
album, a smart section or a search result and pressing Back dropped the user on the Home feed with
their album gone and their query lost. It predates this slice — nothing in the phase map noticed it
because the viewer was reachable from one screen when it was written and from four by the time S2
and PH-3 landed. `Destination.Viewer` now carries an `origin` and Back returns to it.

⚠ Trash does the same: it returns to `origin`, not to the feed.

### 21.6 ⚠ Indexing by path, not by value

`List<Photo>.indexOfPath` matches on `path` rather than using `indexOf`. A `Photo` is a value, and
two reads of the same file legitimately differ in a field the screen never showed — `contentHash` is
projected by the Duplicates read and by nothing else — so structural equality would quietly answer
`-1` and open the viewer on the wrong photograph. A miss coerces to the first row.

### 21.6b ⭐ PH-8b — the back arrow was under the status bar

Found by driving the device rather than by reading the diff: a tap at the back arrow's apparent
centre did nothing, and one 47 px lower worked. Every overlay sat at an alignment of the full-bleed
`Box`, so the top controls were **behind the status bar** and the bottom ones behind the gesture
bar. It predates the pager — the single-photo viewer had the identical alignment — but a viewer you
now stay inside is one you back out of far more often.

⚠⚠ The fix splits the layer, and the split is the point: **the controls get
`WindowInsets.safeDrawing`, the photograph does not.** Insetting the whole `Box` would letterbox
every image to avoid a problem only the buttons have.

⚠ No test pins it. A render test cannot see a system inset, and the desktop suite has no status bar
to be under — this one is held by the device pass and by the KDoc saying why the layer exists.

### 21.7 Not in this slice

- **Pinch-zoom.** It wants the horizontal drag the pager owns, so it is a gesture-priority decision
  (zoom in ⇒ the pager must stop consuming the drag), not a `Modifier`. S3 still lists it.
- **Video playback.** `drive-content-preview.md` §5 — playback is the one preview that does not
  reduce to an `ImageBitmap`, and the pager does not change that.
- **A shared decode cache between the grid and the viewer.** Opening a photo re-reads at hero size
  even though the tile's 256 px bytes were just decoded; showing the tile's bitmap first and
  swapping would need a cache neither screen has. ⚠ It is also close to free today only because
  PH-G9 means the hero often *is* the 256 px thumbnail — that stops being true the day
  `ThumbnailXLarge` lands, which is when this is worth building.
- **A hardware pass.** Desktop render tests only; no phone has swiped this.


## 22. ⚠⚠ PH-5's trash had never worked — `/Trash` was never created (fixed 2026-09-01)

`trash()` moved a photograph to `"$TRASH_ROOT/…"` and nothing anywhere in the codebase creates
`/Trash`. `moveFile` needs a folder to hang the destination on, so every call answered
`notFound: "No folder at /Trash to hold …"`. The feature had been broken since the phase shipped, on
every realm.

**It failed silently, and that is the part worth keeping.** Three layers each dropped the signal:

1. `TenshuPhotosRepository.trash` returns `Boolean` — correct, and nobody read it.
2. `TrashViewModel.trash` called it and discarded the result.
3. The viewer's `onTrash` popped the screen *before* the write could have finished, so the
   photograph vanished from the viewer, stayed in the grid, and never reached the Trash.

The user-visible result was a destructive action that appeared to work, did nothing, and left the
library in a state that looked like a paging bug.

⚠⚠ **Found on a device, during a device pass for something else** (`core-keyset-paging.md` K-4).
Nothing in the test suite could see it: every trash test injects `MockPhotosRepository`, which
replaces the whole class with an in-memory map where a destination folder is not a concept. Five
green tests covered a feature that did not work at all — the same shape as §6.3's mock/production
divergence in `core-keyset-paging.md`, one layer up.

### 22.1 The fix

- `ensureTrashFolder()` creates `/Trash` before the move. ⚠ An `AlreadyExistsError` is **success**,
  matched on the error's *type* rather than its message, and handled rather than avoided with a
  look-first check — two photographs trashed at once would both look, both create, and one would
  lose. Called on every trash, because a "did we create it" flag is wrong the moment someone deletes
  the folder from Drive, which they can: it is an ordinary realm folder.
- `TrashViewModel.trash` reports the outcome, and the viewer **only pops when the trash happened**.
  Staying put says "that did not happen" without claiming to know why. ⚠ There is no snackbar in
  Photos to say more; adding one is its own change.
- `MockPhotosRepository.trash` now honours `outcome`, so a failed write is expressible at all.

### 22.2 The tests, and where they had to live

`RealRepositoryReadTest` — the real repository over a real in-process proxy — because that is the
only place the bug exists. Three cases: trashing on a realm with no `/Trash`; a **second** trash
reusing the folder (an `AlreadyExists` treated as failure would make trashing work exactly once per
realm, a subtler version of the same bug); and a restore putting the file back.

⚠ Two mutants, no survivors: removing the folder creation fails all three; treating an existing
folder as a failure fails exactly the second-trash one.

⚠ The fixture needed `:sync-service-common` registered (test-only). `moveFile` reaches the
DataId-change → reference-migration primitive, which wants `tenshu/sync/rekeyMigrationMarker`; on a
device Control has every service loaded, so the gap is the fixture's and its symptom — "No composite
factory registered" — reads like a product fault.

### 22.3 Verified on a device

OnePlus CPH2581, 2026-09-01: trash a photograph → it leaves the grid and appears in the Trash;
restore it → it is back in the feed at the same position. Same process throughout, no crashes. That
is the first time PH-5's trash has completed end to end.

⭐ **The restore also settled an open question from K-4.** `core-keyset-paging.md` §10.3 recorded
that `refreshNewest`'s replacement — the chain's own invalidation — had lost its tests and was
unverified. The Trash screen's restore calls **no** explicit feed refresh, the presenter is hoisted
above the nav host so returning to the tab does not rebuild it, and the process never restarted —
yet the photograph reappeared in the feed. The reload can only have come from the change signal.

### 22.4 Still open

⚠ `restore()` has the same shape of hazard one step removed: it moves back to the recorded original
path, and if that folder has since been deleted the move fails the same way, silently. Left alone
because recreating a folder the user deleted is a judgement call, not an obvious fix.

⚠ `PHOTOS_MANIFEST` still declares only `getPreview`. Its own KDoc says the trash/delete ops "arrive
with the phases that add them" — PH-5 shipped and they never did, and `createFolder` now joins them.
Not corrected here on purpose: adding permission entries is user-visible (it can re-prompt for a
grant) and belongs in a change that intends it.


## 23. ⚠⚠ The feed's keyset window excluded its own cursor — photographs were missing (fixed 2026-09-01)

`feedWindow` bounds the page with `createdAt.inRange(0L, beforeMs)`. **`inRange` is half-open**: its
parameter is named `endExclusive` and `inRangeLogic` evaluates `start <= v < end`. So the window
excluded the cursor — while every comment in the paging path, and `:core-paging`'s `KeysetKey`
contract, asserted it was *inclusive*.

The result was silent data loss. Three photographs sharing one capture time, paged two at a time,
served two: the third was never in the feed at all. And the tie skip made it worse rather than
better — it subtracted rows from an exclusion that had already happened, so a boundary dropped
twice.

⚠ **Pre-existing, not a paging regression.** `feedPage` has carried this predicate since PH-1;
`core-keyset-paging.md` K-4 inherited it through the shared `feedWindow` and carried it to the live
paged feed. It was found while chasing a 7-row discrepancy between a filtered scan (922 rows) and a
paged walk (915 photos) on a real device.

**Fix**: `exclusiveEndFor(cursor) = cursor + 1`, with `Long.MAX_VALUE` left un-widened — `+ 1` there
overflows to `Long.MIN_VALUE` and the *first* page would match nothing. Both are mutation-tested.

### 23.1 ⚠⚠ Three fixture errors on one test, each of which looked like a pass

The unpaged regression caught the bug immediately. The paged one — the path a user actually scrolls
— took three corrections before it could see anything, and every intermediate version was green:

1. **The fixture was entirely one tie run.** `:core-paging` hands back a *positional* key rather than
   a cursor when a page is all one value (its documented fallback), and a positional read has no
   bound to be exclusive about. The fixture dodged the bug by construction.
2. **The fixture was shorter than `initialLoadSize`** (3 × pageSize), so the whole thing fitted in
   the first load and no page boundary was ever crossed.
3. **`asSnapshot()` was called with no loader block**, which returns the initial load and stops — so
   it reported six rows as the entire feed, which is indistinguishable from the data loss it exists
   to detect.

⚠ The lesson is not "write better fixtures". It is that **a paging test that does not page, on data
shorter than a page, is green for three independent reasons that all look like success** — and only
mutation testing separated them. Had the unpaged test not existed, the bug would have survived a
green suite.

---

## 24. As built — the tab is *Collections*, and four identity bugs it was hiding (2026-08-30)

**Merged:** _(this PR)_ · **Scope:** `:photos-shared-logic`, `:photos-shared-ui`,
`:inference-service` (one constant moved), plus two one-line fixes in `:mia-service-proxy` and
`:drive-shared-ui` test code. No schema change, no service version bump.

Asked for as a rename. The rename is four strings; everything else in this section is what the
rename walked into.

### 24.1 The rename, and the line it draws

**S4's tab is *Collections*.** Google Photos and Apple Photos both use that word for this tab, and
both keep *album* for the thing inside it — the list a user builds by hand. That is exactly the
shape already here, and the old name obscured it: `AlbumsScreen` rendered Places, years and Videos,
none of which is an album, so every derived section read as an intruder in its own tab.

So the split is now in the names:

| Renamed | Not renamed |
|---|---|
| `Destination.Albums` → `Destination.Collections` | `Album`, `AlbumEntry`, `AlbumSummary`, `:photo-service` |
| `AlbumsScreen` → `CollectionsScreen` (new package `screens/collections`) | `AlbumDetailScreen` / `AlbumDetailViewModel` — S5 really is an album |
| `AlbumsViewModel` / `AlbumsUiState` → `Collections*` | `SmartAlbum` — "smart album" is still the industry term for a derived one |
| The tab label, the empty state, the two error strings | `AlbumCard`, which now carries both kinds |

⚠ **No stored row, no wire field and no service version moved**, which is why this needs no
migration and no bump: every renamed symbol is client-side UI. The one user-visible copy change
beyond the label is the empty state — *"No albums yet"* became *"Nothing here yet"*, because on the
Collections tab the old sentence told a user the Places and Things sections do not exist when in
fact they had no photos to derive any from.

### 24.2 ⚠⚠ Four string templates had their `$` escaped, and three of them were real bugs

Renaming meant reading `AlbumsViewModel` line by line, which is how this surfaced. Across the Photos
module, four `"…${…}"` templates were stored with the `$` escaped as `${'$'}` — what a shell
heredoc does to a Kotlin template when the file is written through one. **Kotlin compiles the result
silently**: `"album-${'$'}{clockMs()}"` is a legal string that evaluates to the eleven literal
characters `album-${clockMs()}`, identically, every time.

| Site | What it produced | Consequence |
|---|---|---|
| `TenshuPhotosRepository.createAlbum` | one literal id for **every** album | the second album the user creates folds onto the first |
| `TenshuPhotosRepository.trash` | one literal `/Trash` path for **every** photo | the second trashed photo collides with the first — the exact thing the content-id prefix exists to prevent |
| `CollectionsViewModel` place + year ids | one literal id per **kind** | **the tab crashes for any user with two years of photographs** — see below |
| `CollectionsViewModel` subtitles, mock ids, mock trash paths | `${photos.size} photos` on screen | cosmetic, and the visible tip of the other three |

⭐ **The crash was verified, not inferred.** A throwaway render test with two `SmartAlbum`s sharing
an id produced, exactly:

> `java.lang.IllegalArgumentException: Key "year:$year" was already used. If you are using
> LazyColumn/Row please make sure you provide a unique key for each item.`

⚠ Worth knowing because the opposite is also plausible — a keyed lazy list *could* have deduplicated
silently, which would have made this a scroll-position oddity instead of a crash. It does not.

⚠⚠ **Every test passed.** `SmartAlbumsTest` asserted titles and counts; `AlbumViewModelsTest`
asserted names and membership. **Not one test anywhere read an `id`** — and the id is the field the
lazy grid keys on and the field album membership is stored against. A test that reads only what a
human reads cannot see an identity bug.

⚠ `TenshuPhotosRepository` had **no test file at all** — it is exercised only through
`MockPhotosRepository`, which carried two of the same escapes. So the two most serious sites were
not merely untested, they were unreachable from any test. Both id-minting expressions are now pure
top-level functions (`mintAlbumId`, `trashPathFor`) for that reason alone: inline they could not be
tested without a running Control, and out of line they are four assertions.

`CollectionIdentityTest` is the guard, and it asserts the fields a *framework* reads rather than the
fields a person reads — distinct ids, and no `{` surviving into one. ⚠ Verified the way this repo
requires: each template was re-broken and the test watched go red before being restored.

⚠ Two more escapes lived in test code outside Photos and are fixed here too:
`MiaTestProxy`'s `newId` minted one id for every row, and a `SyncedFolderControllerTest` assertion
message printed its own template. Three legitimate uses survive untouched — a vCard literal and the
hub's `$ENV_VAR` help text.

### 24.3 The Things section — S4's label albums, built as a consumer that waits

S4 has always specified label albums *"only once IN-3b lands"*. This ships **the Photos half**, and
nothing else:

- `PhotosRepository.allLabels()` reads every `MediaLabel` row in one pass;
- `groupPhotosByLabel` folds them against the photos the tab **already read** (§20's shared index),
  so the section costs one relation read and no extra library walk — the same one-pass contract
  PH-3's Places section holds to;
- `CollectionsUiState.things` renders under its own **Things** header, above *Places & times*.

⚠⚠ **It is empty on every device today, and the section is therefore absent on every device today.**
The proxy binds `InferenceBackendRegistry.Empty` (`inference-service.md` §17.4), so no pass has ever
written a `MediaLabel` anywhere. Absent rather than greyed out, because a row that does nothing until
some future release is advertising — the argument that kept two dead tabs out of PH-1. What this buys
is that IN-3b becomes a backend swap on the Photos side too: the twelve tests in
`LabelCollectionsTest` describe what the user gets the day rows appear, and none of it has to be
written then.

**One product floor, and one deliberately absent.** A label needs **two** photos to be a collection
— one photograph is a search result, and the tab has a search. That is a display judgement Photos
owns.

⚠⚠ **The confidence floor is 0, and that is the considered answer rather than a missing one.** The
first draft of this slice set it at 0.500 — a coin flip, which sounds like the cautious choice.
`models.md` §3.7.2 already knew better: SigLIP scores `sigmoid(117.33·cos − 12.93)` (⚠ the `-224`
checkpoint's constants; the pinned one's are in §3.10.8), and over a real
library **no (image, label) pair reaches p = 0.5 at all** — the highest observed is ≈ 0.25, with
usable cut-offs near **0.05–0.15**. A 0.500 floor would have shipped a Things section that could
never contain anything, and it would have looked like "the model found nothing" rather than like a
bug.

⭐ The general form is the reason it is 0 and not 0.05: **the cut-off belongs to whoever calibrated
the vocabulary** (IN-D9's per-leaf absolute floor plus margin), not to a grid. A second, uncalibrated
floor in the UI silently drops rows the calibrated side decided to keep, and 0.05 would have been
just as invented as 0.5 — only less obviously so, which is worse, because a plausible constant is
the kind nobody re-examines. The parameter stays for a caller that has a real number.

### 24.4 ⚠⚠ `__indexed` would have been the largest collection every user owned

`MediaIndexer` writes a sentinel label, `INDEX_MARKER_LABEL = "__indexed"`, for **every file it
looks at**, opinion or not — that marker is how a second pass knows what to skip. Its own KDoc says
*"public because every reader has to exclude it"*.

It was not public to the readers who need it. It lived in `:inference-service-proxy`, and **no
client module may depend on a proxy module** — so `TenshuPhotosRepository.searchByLabel`, written on
the client side, shipped **without the exclusion**. Photos' label search is a `contains`, so a user
typing `ind` would have got their entire indexed library back, every hit captioned `__indexed`. Mia,
which lives in a proxy module, has always had the line.

⚠ The constant now lives in `:inference-service` beside `MediaLabel`, where both sides can see it,
and both Photos readers drop it — as does `MockPhotosRepository`, because a fake that answers on the
sentinel would let a screen ship the failure it is standing in for.

⚠⚠ Invisible today for the same reason as everything else on this track: with no backend there are
no labels, so neither the leak nor its fix is observable on a device. It is a bug that was waiting
for IN-3b to become visible, which is the worst moment to find it.

### 24.5 What is proven, and what is not

`:photos-shared-logic` **103 tests**, `:photos-shared-ui` **49**, both green, plus the full gate.
Every new guard was verified by breaking the thing it guards and watching it fail.

⚠ **Not proven:** the rename on a device. No emulator run and no `scripts/android-smoke.sh` pass —
this touches `commonMain` only, but the tab-crash fix in §24.2 is exactly the kind of thing a
render test can confirm and a real library can still surprise. ⚠ **`iosKotlinCheck` not run** — there
is no `photos-ios`, and no `iosMain` source set was touched.

⚠ **Still not proven, and unchanged by this PR:** that a real album, on a real device, created
before this fix, is recoverable. Every such album shares one id; a user who made two has one row.
There is no migration here because there is nothing to migrate *to* — the ids that collided were
never distinct. Pre-release, the honest answer is that the affected state is a single merged album.

---

## 25. The order to do the rest in (2026-08-30)

§4's gaps are a *list*; this is the **sequence**, and the reason for it. Written after §21, when the
question "what next on Photos" turned out to have a different answer from "what is left on Photos".

⚠⚠ **The inference track will look like the whole roadmap, and it is not.** Half of what a user
would name when asked what Photos is missing — place *names*, fast scrolling, dedup, background
ingest — is gated on nothing at all. Those are the parallel lane below, and they should not wait
behind a model.

### 25.1 The gated lane — everything downstream of one number

Ordered, and each step is falsifiable on its own. Detail lives in
[`inference-service.md`](inference-service.md) §19; the Photos-side consequences are here.

| Phase | What | Photos gets |
|---|---|---|
| **0** | **IN-3m** — run the shipped harness on a real phone | Nothing yet. ⚠⚠ It can return *"do not ship this"*, which is why it is first |
| **1** | IN-3b, cut desktop-first → store → Control screen → Android → schedule | The first device that answers anything but `NO_BACKEND` |
| **2** | IN-D9's vocabulary calibration | Labels that are *good*, which is a different problem from labels that *exist* |
| **3** | Consumption | §24.3's Things section fills **with no further Photos work**; S2's sources 2 and 3 become real |

⚠ **PH-G6** (*"content smart albums are inference-gated"*) is now gated on **less** than it was:
§24.3 built the Photos half, so PH-G6 closes when IN-3b lands rather than needing a Photos slice of
its own. The two remaining screenshot-shaped sections are each a *second* capability, not a
threshold tweak: **Documents** is IN-5 (OCR, a second model) and **People and pets** is IN-6 (faces,
off by default and Settings-only, shipping with its own threat-model amendment).

### 25.2 The parallel lane — gated on nothing above

- ⭐ **PH-G7 — a photo's coordinate has no name.** The single highest-value item on this list and it
  needs no model: it is the *Places* section actually reading like Places. The blocker is a module
  boundary, not a missing capability — the geocoder seam lives in a Timeline app module, so it is a
  platform move rather than a Photos slice (§13.2).
- ~~**PH-G9** — thumbnail prefetch on a fast scroll~~ ✅ the repeat-read and flood halves,
  §26 — ⚠ prefetch itself was argued against rather than built; **PH-G3** — `ContentHash` cross-device dedup,
  still computed with no consumer; **PH-G8** — background ingest, which waits on bgproc Ph6/7 and on
  nothing here; **PH-G16/PH-G17** — the scan ops' `/Trash` blindness and the re-ingest loop.
- ⚠ **The device pass §24.5 owes.** It touches `commonMain` only, so HR #8 is not triggered — but
  the tab-crash fix is exactly what a real 20 000-photo library can still surprise.

### 25.3 ⚠⚠ Why Phase 0 is first, and is not an engineering task

It is an afternoon with a phone, against an instrument that already exists (`inference-service.md`
§15.11) — and it is the only step that can tell you the other three are worth starting.

§15.9's third row is the one to watch: *if the measurement says the shippable model is the one we
are not allowed to ship*, that is a **licence conversation**, not a backlog item — and it is far
better learned from a 200-image run than after IN-3b is built on four platforms.


## 26. As built — PH-G9's grid bytes (2026-09-03)

### 26.1 The defect, as a number

`PhotoTile` keys its `produceState` on the path. That is right and must stay: it is what stops the
view model holding a decoded bitmap per row, which on a real library is hundreds of megabytes. The
consequence is that a **recycled tile re-runs its loader** — and nothing between the tile and the
chain remembered anything, so the *request* was repeated. A request is a `navigateTo` + `search` +
an eager `File.Preview`, over IPC, per tile.

Measured with the screen's own harness, one scroll down and back up:

| | |
|---|---|
| loader calls | **17** |
| distinct photographs | **10** |

⚠ The measurement is kept as a test (`aTileScrolledAwayAndBackReReadsItsBytes`) rather than
deleted once fixed, and it asserts the *tile* still re-runs its loader. Paired with
`aCachedLoaderReadsEachPhotographOnceAcrossAScrollRoundTrip`, the two say different things: the
first that decoded bitmaps stay out of the view model, the second that the chain is not asked
twice. If they ever agree, something changed that neither alone would explain.

### 26.2 What was built

`PreviewCache` — an LRU of **encoded bytes** in front of the tile loader, plus a ceiling on
fetches in flight.

⚠⚠ **Bytes, never decoded bitmaps.** A 256 px bitmap is ~256 KB decoded and a few kilobytes
encoded. Caching the cheap half leaves `PhotoTile` owning the expensive half, exactly as its KDoc
requires.

⚠⚠ **A `null` is not remembered.** On a byte-less device `null` means "the thumbnail has not
arrived yet" — a wait, not an answer. Caching it would freeze the tile as a placeholder for as long
as the screen is open, and the content plane delivering the blob a second later would change
nothing. The same rule the count memo needed (`core-keyset-paging.md` §13), for the same reason.

⚠ Bounded in **bytes** rather than entries, because a thumbnail's size is not a constant: the
ladder mints WebP on Android and PNG elsewhere, and a lossless 256 px PNG is an order of magnitude
larger than its WebP twin. An entry cap tuned against one would be wrong on the other. 8 MB is a
**budget picked** to sit small next to the ~10 MB the shared index already costs — not a number
derived from hit rates, and saying so is the point.

⚠ Four fetches in flight, matching `content-plane-transfer-concurrency.md`'s chunk fan-out for the
same reason: the proxy is one process, and the win is overlapping latency rather than queueing more
work than it can answer.

### 26.3 ⚠⚠ Prefetch was argued against, not built

The gap's own title says *"thumbnail prefetch on a fast scroll"*. Prefetch was not built, and this
is the reasoning rather than an omission:

**The measured problem is too much work, not too little.** A fling issues far more reads than a
viewport needs, and speculative reads ahead of it add to that.

⚠⚠ **This paragraph used to continue "…the pipe is saturated and *work* is the cost", and §30
measured that and found it false** — `waitMs` is 0 at the median even under a hard fling. The
conclusion held for a different reason: a read costs 18–21 ms, so there is no latency to hide.
Read §30 rather than this sentence.

⚠ It becomes worth revisiting once the flood is bounded and someone measures a *slow* scroll
against a byte-less device — that is the case prefetch is for, and it is unmeasured. The ceiling
built here is the precondition for that measurement, not a substitute for it.

### 26.4 What this does not touch

⚠ The **viewer's** hero loader is uncached. It is a different size (four times the grid's in each
dimension), a different access pattern (a pager, one at a time), and PH-G9 is the *grid's* gap;
folding it in would have made the cache's byte budget answer to two very different payload sizes.

⚠ No device measurement. The 17-for-10 number is from the desktop harness, whose viewport and
swipe are small; a fling through 922 photographs on a phone will be worse, and by how much is
unmeasured.


## 27. As built — the feed's multi-select (2026-09-03)

S1 asked for *"multi-select for share / delete / add-to-album, reusing Drive's selection chrome"*.
Two of the three actions exist; the third does not, and the section is as much about that as about
the two.

### 27.1 ⚠⚠ Share is not offered, and a missing button is the honest form of a missing feature

Sharing a photograph out is **PH-G11** — an open gap with no implementation. A *Share* item in the
selection bar would be a control that cannot work.

That is the failure ADR 0025 records one layer down: a **Try again** button sat under a banner
saying Control was not installed, offering to retry something that could never succeed. The bar
therefore carries **Add to album** and **Move to trash**, and nothing else, until PH-G11 lands.

### 27.2 ⚠⚠ The partial failure is the whole design

`TrashViewModel` already carries why `repository.trash` returns a `Boolean` and why its caller is
expected to read it: trashing failed on **every** realm for months because nothing created
`/Trash`, and the viewer closed as though the photograph had gone. *"A destructive action that
quietly does nothing is worse than one that fails loudly."*

In bulk the same failure is worse, because **"some of them" is a state a single boolean cannot
express**. So `BulkOutcome` carries two numbers, never one, and the dialog says *"7 updated. 3 could
not be, and are still selected so you can try again."*

⚠⚠ **The failures stay selected.** Clearing the whole selection after a partial failure throws away
the user's record of *which* photographs still need dealing with — they would have to find the three
that did not move, in a grid of hundreds, with nothing marking them. The successes leave the
selection; the failures remain in it, which is also the retry affordance.

⚠ The writes are **sequential**, not fanned out. Every one is a write through the same proxy, and
firing fifty at once would queue more work than it can answer — the shape PH-G9 had just finished
removing from the read path.

### 27.3 What changed for the user

⚠⚠ **Long-press no longer opens the add-to-album sheet.** It begins a selection, which is what S1
asks for and what the reference apps do. The action did not disappear — it moved into the selection
bar, where it also works for more than one photograph — but a single-photograph add is now two taps
rather than one. `aLongPressBeginsASelectionRatherThanOpeningTheAlbumSheet` names the old behaviour
so the change is deliberate in the record rather than discovered later.

⚠ A tap **toggles** while a selection is running and opens the viewer otherwise. Opening a
photograph mid-selection would bury the selection behind a full-screen page.

### 27.4 The chrome, and why it is not Drive's

Drive's `SelectionTopAppBar` lives in `drive-shared-ui`, which Photos does not depend on. Lifting it
to `:ui-core` would be a refactor of Drive's chrome dressed as a Photos phase, so the bar is
**patterned on** Drive's rather than shared with it: same shape (count, a close that clears, actions
on the end), same rule that it *replaces* the screen's own header rather than stacking above it.

⚠ Selection state lives in `PhotosFeedViewModel`, keyed by path but holding the whole `Photo`.
Drive's equivalent keeps paths because its operations take paths; Photos' take a `Photo` — `trash`
needs the content id, `addToAlbum` needs both — and a selected photograph can be scrolled far
outside the loaded window by the time the user acts on it.

### 27.5 Two test traps this hit

⚠ `combinedClickable` **merges** a tile's descendants into one semantics node, so the selected mark
was not addressable and the assertion read `0`. It needs `useUnmergedTree = true`. The same merge is
why `PhotoTile` gained a test handle of its own: nothing inside it can be reached individually.

⚠ The first version of the click tests targeted `FEED_GRID_TAG` — the grid, not a tile — so a tap
landed on empty space. Six mutants, six killed, only after both were fixed.

### 27.6 Not built

⚠ Selection is the **feed's** only. Album detail, search results and the duplicates screen each show
grids and none of them selects; S1 is the only screen the spec asks for.

⚠ No *select all*, and no range select by drag. Both are real affordances on a grid this size and
neither is in S1.


## 28. As built — sticky headers and the zoom control (2026-09-04)

The last two things S1 asked for and nothing owned.

### 28.1 ⚠⚠ "Costs only layout" was wrong, and the correction is the design

S1 described the zoom control as *"the standard idiom [that] costs only layout"*. A density that
only changed the column count would show a year of photographs under headers still reading **17
December** — because the density decides what a *section* is, and sections are made by the
`insertSeparators` pass in `PhotosFeedViewModel`, upstream of `cachedIn`.

So the density is a `StateFlow` the paged stream depends on, through `flatMapLatest`. Changing it
rebuilds the stream.

⚠ That costs the scroll position, and that is correct rather than a compromise: the rows the user
was looking at are a different size and a different number now, so "the same place" no longer names
anything.

⚠ `FeedDensity` owns the column count as well as the grouping, in the same enum, so the two cannot
drift — a year grouping drawn in three fat columns is a very long strip.

⚠ The mutation named `A: the density is ignored — grouping is always by day` is the *"only layout"*
implementation, and five tests kill it. It is in the sweep on purpose: the spec's own sentence
describes a version of this feature that is wrong, and the suite should say so.

### 28.2 ⚠⚠ `stickyHeader` exists here and cannot be used

`LazyGridScope.stickyHeader` **is** available in this Compose (probed by compiling a call, not
assumed). It is unusable for this feed: it wants each section declared as its own lazy item, and
this list is **flat and paged** — the section boundaries below the loaded window are placeholders,
so they cannot be declared at all.

The header is therefore an **overlay** that reads the top visible row, which works at any scroll
position and needs nothing the list does not already know. It is the machinery PH-17d already built
for the scrubber's date bubble.

⚠ Shown only when the top row is **not itself a header**. While a section's own header is on screen
it *is* the header, and an overlay repeating it one line above would read as a duplicate rather than
as a sticky. That rule is what makes it look sticky without pushing anything.

⚠ A placeholder at the top says **nothing** rather than holding the last label — the same rule the
date bubble follows (§7.1), for the same reason.

### 28.3 The control, and the idiom that is not built

⚠⚠ **Pinch-to-zoom is not built.** It is the reference idiom, and it wants the vertical drag the
grid owns — so it is a gesture-priority decision rather than a `Modifier`, which is exactly why S3
still lists pinch-zoom in the viewer as unbuilt (§21).

What shipped is **one button that cycles**: Day → Month → Year → Day. Three segments cost the width
the title needs on a phone. (§32 put the mosaic in front: Mosaic → Day → Month → Year → Mosaic.) ⚠ Its description names the *current* grouping rather than the next one
— a control that describes what it will become reads as a statement about what is on screen.

### 28.4 Not built

⚠ The density is **not persisted**. Whether a zoom level is per-device or synced is a
`PreferenceService` question, and guessing either way here would be a settings decision smuggled
into a layout change. The feed opens grouped by day, every time.

⚠ The month name is a hand-written `when`, not a locale format: `kotlinx.datetime` has no localised
month names and the app has no formatter for them. It is English, and i18n owns the fix.

⚠ No device pass — the phone has been off USB. Whether seven columns of thumbnails are usable on a
phone, and whether the overlay's translucent background reads over a bright photograph, are exactly
the questions a device settles.


## 29. Device pass — S1's four merged phases (2026-09-04, OnePlus CPH2581)

One pass over `origin/master` at `0fb00669`, covering PH-G9, the §13 count memo, multi-select (§27)
and sticky headers + zoom (§28).

⚠⚠ **Built from a clean worktree, not the primary checkout.** The main tree had a peer session's
unpushed commit and staged WIP in it, and its HEAD moved *during* a build — an APK from there mixes
two people's work and cannot be attributed. ⚠ The APK was then verified by **dex symbol** for all
eight features before it was trusted.

| what | result |
|---|---|
| sticky header | ✅ pinned over its own section's tiles while the inline headers below scroll — and legible over photographs |
| zoom → Month | ✅ **5 columns and headers regrouped to "August 2026"** — the regrouping, not just a resize |
| zoom → Year | ✅ 7 columns, "2026"; tiles small but still legible on a phone |
| zoom → Day | ✅ cycles back; 3 columns, day headers |
| multi-select | ✅ long-press enters, the bar replaces the title, the check and 6 dp inset read clearly against real photographs |
| album picker | ✅ opens from the selection bar, with an honest empty state |
| `PreviewCache` | ✅ scrolled away and back, tiles return populated with no placeholder flash |

### 29.1 ⚠ A stale string the pass found, and no test could have

The empty-album dialog read *"Make one on the **Albums** tab"*. That tab was renamed to
**Collections** (§24) and this sentence was not. Fixed here.

⚠⚠ Nothing was going to catch it: no test compares app copy against the navigation's own labels,
and the string is only reachable on a realm with **no albums**, which no fixture and no developer
device is likely to be in. It took a real device in an unusual state.

### 29.2 ⚠ The sticky header has a gap, and it is inherent to the rule

The overlay is suppressed whenever the top visible item **is** a header — which is what stops it
duplicating the header it would sit on. The cost is a window during the scroll where that header is
*partially* clipped by the top of the viewport and the overlay has not taken over, so the section is
briefly unreadable.

A true `stickyHeader` pins the outgoing header instead of hiding it, and cannot be used here (§28.2).

⭐ **Closed 2026-09-04 — §31.** ⚠ The fix this section proposed named the wrong primitive:
`layoutInfo.visibleItemsInfo.first().offset` is not the top item's offset. See §31.1.


## 30. Measured — PH-G9's prefetch half, and a wrong argument corrected (2026-09-04)

§26.3 argued against prefetch and asked for a measurement before revisiting. Here it is, and it
**falsifies the argument's premise while confirming its conclusion**.

`PreviewCache.bytes` was instrumented to separate the two halves of a miss — `waitMs`, the time
spent waiting for one of the four in-flight permits, and `fetchMs`, the read itself. That split is
the whole question: a miss slow because it **queued** means a saturated pipe, which prefetch makes
worse; a miss slow with a permit free is **latency**, which is what prefetch exists to hide.

OnePlus CPH2581, 922 photographs, `origin/master`:

| scroll | calls | waitMs p50 / p90 / max | fetchMs p50 / p90 / max | bytes fetched |
|---|---|---|---|---|
| **slow** — 12 short swipes, 1 s apart | 34 | **0 / 20 / 42** | **18 / 32 / 40** | 3.5 MB |
| **fast** — 6 hard flings, no pause | 147 | **0 / 4 / 25** | **21 / 38 / 61** | 18.3 MB |

### 30.1 ⚠⚠ The reason given in §26.3 was wrong

§26.3 said: *"a fling already issues more reads than the proxy can answer; speculative reads add to
exactly the queue that is the complaint… the pipe is saturated and work is the cost."*

**The pipe is not saturated.** Under a hard fling `waitMs` is **0 at the median and 4 ms at p90** —
147 reads and essentially no queueing. Four permits at ~21 ms a read is ~190 reads a second, and a
fling composes nowhere near that. The argument was reasoning from a bottleneck that is not there.

### 30.2 The conclusion survives, for a different reason

Prefetch still buys nothing here, because **there is nothing to hide**: a preview read costs **18–21
ms at the median**, against a slow scroll that advances a row every ~700 ms. The bytes are already
on screen before the eye arrives. Prefetch pays when a fetch is slow relative to the scroll; this
one is faster than a frame.

⚠ The in-flight ceiling of four is therefore not a bottleneck either — `waitMs` p50 is 0 in both
runs. It costs nothing and remains a rail against a pathological case rather than a live constraint.

### 30.3 ⚠⚠ What is still unmeasured, stated precisely

**This device holds its thumbnails.** Every read in both runs was local — 18–21 ms for a ~136 KB
payload is a store read over Binder, not a content-plane pull. PH-G9's own wording is *"a fast
scrolling grid **on a byte-less device**"*, and that device is the one case these numbers do not
cover.

The question is now exact rather than vague: on a device whose thumbnails are not local,
`preview()` additionally runs `cacheThumbnails` — a blob pull — and **prefetch pays if and only if
that pull exceeds the scroll's row cadence**. Measuring it needs a realm whose thumbnails have been
evicted, which is a deliberate setup rather than a device anyone happens to have.

### 30.4 ⚠ A side finding: the cache is small next to a fling

One fling fetched **18.3 MB** against an 8 MB budget, so most of it was evicted on the way past.
That is not obviously wrong — the budget exists to bound residency, and a fling is exactly the case
where holding everything is pointless — but it does mean the cache's value is scroll-*back* (the
17-for-10 measured in §26.1) rather than anything during the fling itself.

⚠ Both runs show a **0 % hit rate**, for the same reason: a one-directional scroll never revisits a
tile. The cache is not idle in normal use; it is idle in *this* measurement, which only ever moved
one way.


## 31. The sticky header takes over as a header leaves (2026-09-04)

§29.2's gap, found on a device: the overlay was suppressed whenever the top item **was** a header —
which is what stops it duplicating that header — leaving a window where the header was half scrolled
past the top, clipped and unreadable, with nothing taking over.

The rule is now about the header's **offset**, not merely its index:

| top item | offset | overlay |
|---|---|---|
| a header | fully on screen | **hidden** — it *is* the header |
| a header | scrolled past the top at all | **shown**, naming that section |
| a tile | — | shown, naming its section |
| a placeholder | — | hidden (§7.1's rule) |

### 31.1 ⚠⚠ §29.2 named the wrong primitive, and the code that followed it was wrong

§29.2 proposed `layoutInfo.visibleItemsInfo.first().offset`. That is **not** the top item's offset.
`visibleItemsInfo` is the visible *line range*, and its first entry can be an item a whole row above
the viewport — measured in the failing test at **index 1, offset −340, while the top item was index
2**. The first implementation used it and the control assertion failed immediately.

The documented pair is `firstVisibleItemIndex` (which item is on top) and
`firstVisibleItemScrollOffset` (how far it is scrolled past the top). That is exactly the question,
and it needs no layout arithmetic.

⚠ The wrong primitive *looked* right, compiled, and produced a plausible-seeming value. Only a test
that asserted the un-clipped control — not just the clipped case — caught it.

### 31.2 The test needs a scroll no gesture can make

Putting a header **partially** off the top is the whole case, and no swipe hits a given number of
pixels deterministically. `gridState.scrollToItem(index, scrollOffset)` does, so the grid state is
now hoisted into `PhotosFeedScreen` as a defaulted parameter.

⚠ The test asserts **both** halves — `scrollOffset = 0` hides the overlay, `scrollOffset = 20` shows
it. The mutation named `A: back to index-only` restores the §29.2 behaviour exactly, and only the
second half kills it.

### 31.3 ⚠ It also stopped recomposing the screen every frame

`firstVisibleItemScrollOffset` changes on every scroll frame, so reading it straight in composition
would recompose this screen — the grid included — sixty times a second. It is behind
`derivedStateOf`, so recomposition happens when the *label* changes, which is once a section.

⚠ The previous rule read `firstVisibleItemIndex` directly, which changes once a row rather than once
a frame — cheaper than a raw `layoutInfo` read, but still more often than the label changes.


## 32. As built — the mosaic feed (2026-09-15)

Asked for by the maintainer after comparing the feed with Google Photos on the OnePlus: *"a nice view
mode where a few pictures are bigger than others … as the default"*. The existing modes only changed
the picture size.

### 32.1 What the reference does — measured on the device, not remembered

Google Photos' grid menu (**Photos view → Layout**) offers *Comfortable*, *Day* and *Month*.

| Layout | What it draws (UI dump on a 1080 px screen) |
|---|---|
| **Day** — the maintainer's setting | Three square columns, 6 px gutters. Some photographs are **2×2** (718 px, left or right, with two small ones stacked in the other column), some **full-width 3×2**. Roughly one of those every two to five rows. Dates hidden, with a floating date pill. |
| Comfortable | Justified rows of one to three photographs, widths following each photograph's shape. |

⭐ **This is the Day layout.** `FeedDensity.Mosaic` is it, and the default; the uniform grid is still
there, one tap away: Mosaic → Day → Month → Year.

### 32.2 ⚠⚠ Why the grid's items are lines

A 2×2 photograph spans **rows**. `LazyVerticalGrid` spans columns only, and
`LazyVerticalStaggeredGrid` spans one lane or all of them, so neither can draw it. A custom
`LazyLayout` could, at the price of re-owning scroll state that the scrubber (PH-17) and the nav
host's restoration are both built on.

So `FeedLayout` (`:photos-shared-logic`) cuts the paged snapshot into `FeedLine`s: `Header`, `Row` or
`Feature`. The grid is **one column of lines**, still a `LazyGridState`, and `FeedLineCells` places
each line's cells. ⚠ **Every density is drawn this way**, so there is one path through the screen.
⚠ The grid's indices are therefore lines. The scrubber did not need a change, because it already
counted items per line rather than assuming the column count. One test that scrolled to "index =
photo count" did.

⚠⚠ **A density change must re-anchor, and a line index cannot carry it**. The grid
state holds a line index, and a line is three, five or seven photographs. Left alone, line 100 is
photograph ~300 in the day view and ~500 in the month view. The only coordinate the densities share
is a photograph's **library position** (`FeedLayout.photoAt` / `lineOfPhoto`).

- The screen reads the top photograph's position **in composition**, off the layout the grid was last
  measured against. By the time an effect runs, the grid may already have moved its index.
- It scrolls to that photograph's line at once, again when the rebuilt stream's first page lands
  (the top of the library, where the photograph is usually a placeholder), and again when the jump
  that request causes lands.
- It stops once the photograph is loaded, or as soon as the user drags or scrubs.
- ⚠ The test scrolls to a day's **second** row. On a day's first row the grid keeps its place by key
  alone, since that photograph starts a line at every density, so the first draft of the test passed
  with no re-anchor at all.
- ⚠⚠ **A header and the row under it share one library position**, so the anchor records which of
  the two was on top (`lineOfPhoto(photo, header = true)`). Without it the commonest case of all —
  the feed as it opens, first header on top — scrolled that header away (Codex, second pass).
- ⚠⚠ **A feature is two rows tall, so the line being on top does not put its first photograph
  there.** Past half of it, the photograph leading the view is the feature's LOWER small one, and
  anchoring on the line's first scrolled back half a feature (Codex, third pass —
  `photoAt(line, scrolledPast)`).
- ⚠ A trap in testing this: a fixture of 30 photographs fits the month view **entirely** in the
  viewport, so the grid can only ever be at line 0 and an assertion about where it lands is one no
  layout could satisfy. The fixture is 120.

⚠⚠ **The scrubber seeks in photographs too, and it must** (Codex, second pass). Its year pills are
placed by photographs (`trackFractions`), and lines are not proportional to photographs: a day header
is a line of its own while three photographs share one. Where photographs-per-day differs between
eras — a sparse year above a dense one — a thumb positioned in lines sits against a pill naming a
year a long way from where it lands. `FeedLayout.progressAt` / `lineAtProgress` convert both ways,
and `FeedScrubber` takes them as `progressAt` / `indexAtProgress`; without them it keeps its old
item-count arithmetic, which is what its own tests use. ⚠ They reach the drag handler through
`rememberUpdatedState`: keying the gesture on a caller's lambdas would cancel a drag on every
recomposition, and capturing them once would read a layout from before a density change.

### 32.3 ⚠⚠ The invariant: nothing already drawn moves when a page lands

The list is paged **with placeholders**, so most of it is unknown and pages land in any order, above
the viewport as well as below. The layout must not reshuffle as they do. Three rules keep it still:

- **A feature consumes the same three items a full row would.** A row becomes a feature when its
  third photograph loads, and no other line moves. `FeedLayoutTest` pins it by cutting the same list
  as `Day` and as `Mosaic` and requiring identical line starts.
- **Whether a line is a feature depends on its own photographs and their position in the whole
  library.** It never depends on where the loaded window starts. `placeholdersBefore` is the source's
  count of unloaded **photographs**; the separator pass adds headers only between loaded rows. So the
  first loaded photograph's position is exact, and a page landing above changes nobody's.
- **The loaded window starts a fresh line.** The placeholders above it are cut into rows from their
  **end**, and the window's last row runs on into the placeholders after it instead of stopping short.

⚠⚠ **The first version broke the second rule.** It counted the rhythm
from the window's first item. After a scrubber jump into a long day, the 60-photo page above moved
every loaded line 20 slots through a 7-line cycle, so features on screen turned into rows as the page
landed. `aPageLandingAboveTheWindowChangesNoLineItAlreadyDrew` replays exactly that, and fails
against the old rule.

⚠ A section whose header is still unloaded above the window has its lines cut from the window's
edge. They re-phase when the header lands. The flat grid did the same, since a full-width header
arriving shifted every tile after it. A same-day page landing above does not re-phase them: the page
is 60, a multiple of three.

### 32.4 The rule

- A line whose first photograph is library position *p* sits at slot *p* ÷ 3 of a 7-slot cycle.
  Slots 0 and 3 are features: two plain rows, then three. A fixed period read as a stencil.
- ⚠ **So the rhythm runs through the library, not per day.** A day does not necessarily open on a
  feature. The first version opened every day with one, and that is exactly what made it unstable.
  The reference's day view does not do it either.
- A feature needs three **loaded** photographs in one section, so a day of one or two photographs
  gets small tiles, never one giant beside a hole.
- Slot 0 puts the hero on the left and slot 3 on the right, so consecutive heroes alternate.
- ⚠ A right-hand hero is the line's **middle** item, so reading order stays paged order.
- ⚠⚠ **A feature is two rows tall, and "the line is visible" is not "its photographs are".** The
  video rotation (M5) reported every photograph of every visible line. With only a feature's top half
  on screen, that offered the lower small video to the rotation, spending a turn on a clip nobody
  could see. `FeedLine.Feature.rowOf` now names each small photograph's row, and the
  screen checks that half against the viewport.

### 32.5 The hero is sharp only because tiles measure themselves

A 2×2 tile is ~830 px on the OnePlus. It is sharp because `PhotoTile` requests a size from its own
measured pixels (`drive-synced-thumbnails.md` §16), which yields the `XLARGE` rung here. Against the
old fixed 256 px request it would be a 3× upscale. This slice was built on that branch for that reason.

### 32.6 Not built

- ⚠ **Heroes are chosen by position, not quality.** The reference picks its best photographs with a
  model. The feed row carries a path, a time and a shape. Favourites would be an honest signal, but
  they are keyed on content id in `:photo-service`, which the feed read does not join.
- ⚠ **No full-width 3×2 heroes.** "Landscape" is not knowable from the row: `File.PixelWidth`/`Height`
  are the *stored* size, before EXIF orientation, so a portrait photograph can read as landscape and
  would be cropped to a strip.
- The reference's hidden dates with a floating pill, the Comfortable layout, pinch-to-zoom (§28.3),
  and persisting the choice (§28.4).
- ⚠ **No device pass yet.** The geometry is pinned by `PhotosFeedScreenTest`, and the look was checked
  on a desktop render at the phone's density.

---

## 33. As built — a grid that widens with its window, and the desktop shell (2026-09-16)

Reported on a 3456 × 2234 retina MacBook: *"blurry images, and more blurry when I change the window
size"*, with a question about what Drive and Control already have in `:ui-core` that Photos does not.
The thumbnail half of that report is `drive-synced-thumbnails.md` §17. This section is the layout half.

### 33.1 Why a wider window was blurrier

`FeedDensity.columns` was a constant, 3 / 5 / 7, so a tile's size was simply the window's width divided
by it. Maximised on that panel the grid is ~1600 dp: a ~530 dp tile, over 1000 px at 2x, and a 2×2
feature over 2000 px. The largest stored thumbnail class is 1024 px, so every tile was an upscale, and
one that **grew with the window**. ⚠ Computed, not observed: nothing in the session could drive or
capture the window. A tile measured at the default size (426 px) was sharp whenever its hero rung
existed.

### 33.2 The rule — `gridWidthScale`

`columns = density.columns × k`, where `k` is the smallest allowed scale ≥ `ceil(width / 720 dp)`, up to
5: a tile at the three-column densities never exceeds **240 dp** (`MAX_BASE_TILE_DP`) below a 3600 dp
grid, which keeps a 2×2 feature inside the 1024 px rung on a 2x panel.

- ⚠⚠ **A multiple of the base count, never an arbitrary one.** A feature must take exactly the
  photographs a row takes, or a landing page moves every line below it (§32.2). `k` feature blocks —
  each a 2×2 hero beside two — take `3k` photographs, filling two lines of cells, only when the line is
  `3k` wide. So the mosaic goes 3 → 6 → 9 and never through 4 or 5; `FeedLine.Feature` gained `blocks`,
  and its heroes alternate across the line as they already did down it.
- ⚠⚠ **And a count the page size divides**. The loaded window starts a fresh line,
  so a page landing above re-cuts every line it drew unless that page is whole lines — the premise
  §32's mosaic already rested on at three columns. 9 against 60-photograph pages failed it, so the
  allowed scales are the ones where the density's own count × `k` divides `DEFAULT_PAGE_SIZE`: for
  the mosaic and the day view **3, 6, 12, 15 columns**, for the month view 5, 10, 15, 20 (a second
  Codex pass found Month at 25). A maximised 1600 dp window gets 12. Derived from the page size, so changing it cannot
  silently break this; `aPageLandingAboveChangesNoLine_atEveryColumnCountAWideGridPicks` pins it,
  with a control at nine that does move lines. ⚠ The 7-column year view never divided 60.
- ⚠⚠ **Width, measured where the grid is.** The screen reads its own `BoxWithConstraints`, so a
  navigation rail, a split-screen neighbour or a tablet's freeform window are already subtracted, and
  the rule needs no notion of which device it is on. Nothing reaches a view model or the repository:
  `FeedLayout` is cut in the screen, and each tile already asks for its own measured pixel size (§32.5).
- ⚠ **Constraints, not `onSizeChanged`.** The first frame would cut lines at a phone's count, and a
  scroll position restored into that cut names a different photograph than it was saved on.
- ⚠ Every density scales by the same `k`, so zooming out keeps the ratio it has on a phone. A phone
  (~400 dp) has `k = 1` and keeps exactly the grid it had.
- The plain grids (Trash, Search, an album, Collections) use the same rule through
  `WidthScaledGridCells`, deliberately not `GridCells.Adaptive`, which would pick counts out of step
  with the feed beside them.

### 33.3 A column change re-anchors, like a density change

A window dragged wider re-cuts every line, and the grid state holds a line index — §32's density
problem again. The anchor now watches `(density, columns)`. ⚠⚠ **One difference:** only a density
change rebuilds the paged stream. After a column change there may be no next snapshot at all, and the
density path's *"wait for the rebuilt stream"* would leave the job alive to yank the list whenever a
page next lands. So a column change scrolls once and stops if the photograph is loaded.
`aGridGrownWiderKeepsTheTopPhotographsDayOnTop` pins it, mutation-checked.

⚠ **Not handled:** a window resized *while the viewer is open*. The feed is out of composition then, so
its restored line index is from the old cut and there is no previous shape to anchor from.

### 33.4 The desktop shell — what `:ui-core` already had

A survey of Drive, Control and `:ui-core` against Photos (2026-09-16):

| Affordance | Before | Now |
|---|---|---|
| `rememberPersistedWindowState` (size, position, placement) | Drive, Control, Calendar, Fit, Mia, Timeline — not Photos | Photos too, key `photos` |
| `filePersistedNavRailPreference` | Drive, Control | Photos too |
| `keyboardShortcuts` | Drive's Files screen | Photos' feed and viewer, through `AppKeyboardShortcutHost` |
| Adaptive navigation, window-size class | already via `TenshuTheme` / `AdaptiveNavScaffold` | unchanged |
| Scrollbars, hover, context menus, drag-and-drop | nowhere in the three apps | unchanged — Photos was not behind |

⚠⚠ **`AppKeyboardShortcutHost` is new in `:ui-core`, and it exists because bare `keyboardShortcuts` is
inert.** The modifier only sees key events routed to a focused node inside it, and a screen of images
and icon buttons has none: a window opens with nothing focused. The host is a focus target and asks
for focus once when it enters composition. `AppKeyboardShortcutHostTest.aShortcutFiresBeforeAnythingWasClicked`
fails without that request (mutation-checked). ⚠ That suggests Drive's Files-screen shortcuts, attached
the bare way, also do nothing until something inside the screen holds focus. Not verified, and not
changed here.

The keys:

- **Feed:** Esc clears the selection, Delete **or Backspace** trashes it, and Cmd/Ctrl+F opens Search
  — whose query field now takes focus when the screen first opens, so the user can type at once.
  The host wraps the whole tab so a click on the selection bar keeps the keys working.
- **Viewer:** ← / → page (not while magnified, D4), Esc closes the details before the viewer, and
  Delete or Backspace trashes the photograph on screen.
- ⚠⚠ **A held key fires once**. Auto-repeat on a held Delete ran the trash once
  per repeat, and concurrent bulk jobs over the same selection could restore photographs the first
  job had already moved. The host tracks key-down/key-up (common Compose exposes no repeat count),
  and only a shortcut marked `repeats` — arrow-key paging — fires again.
- ⚠ **Backspace as well as Delete**, because the key labelled *delete* on a Mac sends Backspace.
  `AppShortcuts.deleteSelection` names `Key.Delete` alone, so Drive's shortcut needs fn on a Mac.
  Trash is recoverable, which is why the key needs no confirmation.

### 33.5 Not built

- The viewer's request size is §34.
- Scroll-wheel or trackpad zoom in the viewer, a menu bar, arrow-key movement through the grid.
- ⚠ **No device pass on a real desktop library yet.** Geometry and keys are pinned by
  `PhotosFeedScreenTest`, `FeedLayoutTest`, `GridWidthScaleTest` and `PhotoViewerKeyboardTest`.

---

## 34. As built — the viewer asks for the size it draws (2026-09-16)

Item 3 of the retina-Mac report (§33 is the grid, `drive-synced-thumbnails.md` §17 the thumbnails).

### 34.1 What changed

The viewer asked every page for a constant `HERO_PX = 1024`. On a 3456 × 2234 panel a 4:3 photograph is
drawn ~2979 px wide, so the request guaranteed a ~3× upscale before any device answered. It now asks
for `heroPreviewRequestPx` — the photograph's long edge **as drawn** under `ContentScale.Fit`, rounded
up to a 512 px step:

- **never below 1024 px**, so a small window keeps the hero rung rather than dropping to `LARGE`;
- **never above the photograph's own long edge**, since a render cannot add pixels;
- **never above 4096 px**.

⚠ Read off the page's constraints, so the first frame already has the right key and the page loads
once, not once at a guess and again at the real size (the trap `drive-synced-thumbnails.md` §16
recorded for tiles). ⚠ Keyed on the step, so a window drag re-asks a handful of times. ⚠⚠ **The picture
on screen stays while the larger one loads**: `produceState` keeps its value across a key change, and
`HeroState.Ready` now carries its path so a recycled page never keeps another photograph's picture.

### 34.2 What it buys today, and what it does not

| Device | A request above 1024 px is answered by |
|---|---|
| Holds the original | a render from it — sharp. ⚠⚠ Only because the repository asks **past** `GetStoredThumbnail.COVER_TOLERANCE` (`renderEdgeOnByteHolder`): between 1024 and 2048 px the proxy otherwise serves the stored 1024 px rung even here (D-2), which is what a 1536 px viewer would otherwise be given. ⚠ JPEG above 1024 px on JVM/iOS (`drive-synced-thumbnails.md` §17.6), where it was megabytes of PNG |
| Does not | the 1024 px hero rung, as before (`asksForHeroByName`) |

⚠⚠ **So on the maintainer's Mac, viewing photographs taken on the phone, this changes nothing yet.** It
is the half of the fix that can be built without a new rung: once `XXLARGE` exists (§17.4 of the
thumbnail doc, the maintainer's decision), the same request picks it up with no viewer change. ⚠ The
file's stored `PixelWidth`/`PixelHeight` are pre-EXIF-orientation, so a rotated photograph can be fitted
on the wrong axis, which can understate the request as well as overstate it, so both
orientations are fitted and the larger is asked for.

### 34.3 Not built

- Re-asking at a higher size while **zoomed** — a magnified page still scales the bitmap it has.
- A signal when a larger rung lands during viewing (§16.2's known limitation, and the progressive swap
  §17.4 wants).


## 35. Collections as rows, with a page per section (2026-09-30)

**Why.** Once People found everyone (IN-6 recall, `photos-people-and-pets.md` §12.14) the tab drew
10+ full-width faces before anything else — People alone ran to several screens, and Things, Places
and the user's own albums sat below it. Every section was drawn in full, so the tab grew with the
library. Surveyed on the maintainer's phone against Google Photos' Collections tab the same day.

**What Google Photos does:** a column of sections, each a **horizontal row** with a *View all*. People
and pets is a row of portraits, and *View all* opens a **3-column grid of circles**, a name under a
circle only when it has one and **no photo counts**. Places is a row led by a map tile, and *View all*
is a **list** (thumbnail, name, "217 items") under a map. Fixed destinations (Favourites, Screenshots,
Videos …) are **compact icon-and-text tiles**, not full-width blocks.

**What Tenshu builds:**

| On the tab | *View all* opens |
|---|---|
| Compact tiles: **Favourites** (with count), **Duplicates**, **Videos** (when there are any) | — (each opens its own screen, as before) |
| **People** — circles, name under when named; the status line when empty | 3-column grid of circles, **named first**, no counts |
| **Albums** — cards, led by a *New album* tile | 2-column grid of albums, *New album* in the bar |
| **Places** — cards | list: thumbnail, name, count |
| **Things** — cards | 2-column grid |
| **Years** — cards | list: thumbnail, year, count |

- ⚠⚠ **A route per section, never a payload** (`Destination`'s rule): `Destination.Section(kind)`
  carries an enum, and the page reads the same `CollectionsUiState` the tab does — the one feed walk
  (PH-3). Opening an item from a section page pushes the same `Smart`/`Album` route the row does.
- ⚠ **A row keeps its section's order** and shows the first items only; *View all* is always there,
  because a row that hides it when short makes the user count tiles to learn whether there are more.
- ⚠ **The empty-section rules are unchanged**: a section is absent when empty, except People, which
  keeps its header and says why (`peopleStatusLine`).
- **Not built:** Google's map tile and map header (Photos has no map view — Maps is its own app),
  pets (P1), *On this device* (a synced library has no single device's folders to show).

## 36. Collections loads per section (2026-10-01)

**Why.** The tab loaded in ONE refresh — six reads to Control in sequence (albums, favourites, the
whole-library `allPhotos` walk, places, labels, `faceGroups`) — and drew nothing until all six
answered. A slow or lost read therefore held a full-screen spinner over the whole tab, and the 2-minute
bound on that wait turned it into a full-screen error. Seen on the maintainer's phone: an 8-minute
spinner (a lost reply), and a spinner then *"Something went wrong loading your collections"* while
Control was busy re-indexing.

**Measured first (2026-10-01, a timing build on the phone, 1,286 photos, 67 groups):** with Control
idle the whole refresh took **~0.45 s** — albums 129 ms, favourites 31 ms, `allPhotos` 1 ms (already in
memory from the feed), places 38 ms, labels 37 ms, `faceGroups` 212 ms. ⚠ So the reads are not slow in
themselves; the failure is that **one slow read, under load, takes the whole tab with it.** That orders
the work below.

**Step 1 — each section loads, fails and retries on its own** (built, below).
- Albums and favourites, the library walk (→ years, videos), places, labels (→ Things) and people
  (`faceGroups`) are separate reads started together; each section is drawn the moment its inputs
  arrive. ⚠ Things, places and people need the library walk too — they wait for it, not for each other.
- Each read is bounded on its own (the read-bound rule: the timeout bounds the WAIT, the read is
  abandoned, never awaited), and a failure is that section's: People and Years show a placeholder row
  while loading and one line with *Try again* if their read failed. Things and Places — sections that
  exist only when they have content — stay absent while unknown.
- ⚠ The full-screen states are kept only for what they mean: *Can't reach Tenshu Control* when Control
  is not running and nothing is on screen. There is no full-screen spinner — the tab draws its tiles
  and placeholder rows at once.
- A one-line timing summary per refresh (`Collections -> albums=…ms …`) stays in the product: it is what
  told us the reads are fast, and the next slow tab is diagnosed by reading it.

**Step 2 — show the last result at once** (built, below). Keep the last loaded sections and draw them on open,
refreshing behind them, so reopening Photos never starts from a blank tab. ⚠ Holds titles, counts and
cover paths only — never the photo lists, which are the library.
⚠⚠ **Two caveats any cache of this tab carries** (shared with §37's cached first page): a photograph
trashed on another device can show in the snapshot for a moment until the live read replaces it — the
snapshot is only ever drawn as LOADING, never as current; and the snapshot must be **purged on every
identity teardown** — realm reset or switch, sign-out, and Control revoking Photos — or the tab draws
another identity's people, places and albums, for as long as Control stays away.

**Step 2 as built (2026-10-01).**
- ⚠⚠ **Local, never through Control.** Photos had no storage of its own — even `PhotosPreferences` is
  read through the proxy — and a cache read through Control is useless exactly when Control is slow.
  The snapshot is a file in Photos' own sandbox, written through `:app-outbox`'s per-platform file
  store (the existing seam: atomic temp-and-rename on Android and desktop, `writeToFile:atomically:`
  on iOS) — `CollectionsSnapshotStore` / `JsonCollectionsSnapshotStore` in `:photos-shared-logic`.
  Android `cacheDir/collections_snapshot/`; desktop `DesktopAppPaths.dataDir("app.tenshu.photos.desktop")/
  collections_snapshot/`; iOS the container's `Caches/collections_snapshot/` (`iosCollectionsSnapshotStore()`,
  passed by `PhotosSurface.collections()`). Losing it costs one placeholder start.
- **What is kept** (`CollectionsSnapshot`, JSON, `FORMAT_VERSION` 1): per section the cards as drawn —
  id, title, subtitle, count, cover path and pixel size, a person's face crop and alias ids — plus the
  album summaries, the favourite count and `peopleStatus`. ⚠⚠ **Never a photo list**: the file is
  built from what a card shows (`SmartAlbum.photoCount`, `coverPath`). An unreadable or other-version
  file is ignored; a failed read, write or delete is logged and ignored.
- ⚠⚠ **Never drawn as current.** With a snapshot the view model starts every section `LOADING`, and the
  snapshot fills only sections no read has `LOADED` and that are empty — before any second refresh — so
  it never overwrites a live answer and is independent of WHEN the first refresh runs. Each section
  stays `LOADING` until its own read replaces the cards. ⚠ A section whose read FAILED before the realm
  or the file answered still takes them and stays `FAILED`; a `LOADED` section, even empty, is
  authoritative. The cards reach both shells through
  `CollectionsLayout` (a snapshot card is an ordinary `SmartAlbum` carrying `snapshotCard`), so iOS
  draws them too. The Favourites tile shows the last known count while its read is in flight.
- **Stale beats blank, said as stale:** a failed read keeps the cards, and the layout's existing line
  says so (*"Couldn't refresh people."* etc. — no second wording).
- ⚠⚠ **A snapshot card has no photographs, and can be tapped.** `CollectionsUiState.smartPage` (shared;
  Compose's `Destination.Smart` and iOS's `SmartGridScreen`) answers **loading** until the card's
  section has read live — the route is an id, so the page fills in — and **failed** if that read
  failed: never an empty grid, which on a person reads as "no photos". Naming is not offered on a
  snapshot card. Only a live resolution is kept as the alias-loss fallback.
- ⚠⚠ **One realm's, and gone with the identity.** The snapshot is keyed by the realm's `ownerId` (the
  realm key's fingerprint): the record is named for it and carries it, `read` answers only the asked
  realm's, and writing one realm's drops any other. The realm comes from `CurrentRealm`, which has
  three answers — an id; `null` = *no identity*, which **purges** every snapshot; a throw = *unknown*,
  which draws nothing and drops nothing. ⚠ Nothing is drawn before the realm has answered, so a
  snapshot is never shown for an identity the session cannot read. Signals that purge it:
  - Android/desktop (`controlCurrentRealm`): `getSyncStatus().ownerId` absent — **realm reset / Start
    fresh, *Forget this device* (sign-out), a superseded realm** all clear the persisted owner slot —
    or `syncHealth` connectivity `device_unrecognized` — **revocation** (it keeps the ownerId). ⚠⚠
    Revocation arrives LATE: a fresh Control first reports `null` or its optimistic `online`, and the
    row carries no relay-confirmed marker, so health is **observed for the view model's life**
    (`CurrentRealm.teardowns`) — a later `device_unrecognized` purges the file, takes every snapshot
    card (and unconfirmed album or favourite count) off the screen, and blocks any write-back. ⚠ All snapshot
    file work (read, write, delete) runs one at a time behind the STORE's fence — one per process, which
    the teardown paths outside the view model take too (`purge()`, iOS `deprovision()`) — and a write
    checks for a teardown, and for any purge since it was prepared, only after taking it. So a delete
    always lands after a write already in flight, and a write prepared before a purge never lands after it. A
    different `ownerId` (**realm switch**) never reads the old record and the next write drops it.
    `locked` is unknown: not drawn, not purged (locking is not a teardown). A lock that arrives LATE (a
    sealed Control restart reports `null` or `online` first) is watched too
    (`CurrentRealm.invalidations`): the cards come off the screen, but the file is kept.
  - iOS: the route-1 hand-off `RealmKeyMaterialBridge.read()?.ownerId` — offline and synchronous;
    Control's `clearDriveSession` clears it on **reset, sign-out and revocation** — and
    `PhotosLocalRuntime.deprovision()` (the teardown gate) clears the snapshot outright. ⚠ Effective when it
    returns: `tearDown()` bumps the store's clear count and deletes on the calling thread without
    waiting on the fence (the Swift caller is synchronous, and a scheduled purge could be suspended or
    killed behind a write); a write in flight re-checks the count after writing and deletes what it
    wrote. The fenced purge runs as well. Only a crash between a write and that check can leave a file.
  - ⚠⚠ **A realm that changes while Photos runs** — A → B, a reset A → none, or none → B, all counted as
    transitions — is detected in ONE place (`observeRealm`), fed by the first read, a long-lived realm
    watcher (`CurrentRealm.changes`) and a realm check after EVERY refresh, a failed-everywhere one
    included; a refresh never discovers a change on its own. A transition resets every realm-bound
    card (live ones a failed section kept, snapshot ones, the favourites count) to the initial LOADING
    state, drops the file, abandons the refresh in flight for a fresh one, and latches saving until a
    refresh STARTED after the change has read every section afresh.
- ⚠⚠ **The write invariant** — every gate is checked in one critical section, holding the store's fence
  (`CollectionsViewModel.writeSnapshot`). A captured snapshot lands only if:
  1. its realm is the current realm (and no teardown has happened);
  2. no clear has happened since it was captured (the store's clear count);
  3. it is the newest capture to reach the store — a ticket issued at capture, compared with the
     store's `newestWritten`, so an older save whose realm read answered late never lands over a newer
     one;
  4. no realm-change latch is pending.
  And a `tearDown()` that overtakes a write (it does not wait for the fence) makes the write undo itself.
- ⚠ A per-type Control data reset keeps the realm: the snapshot's rows are then simply replaced by
  the next live read (drawn as LOADING until it lands).
- **Privacy:** a local cache of the user's own metadata on their own device, the posture of Control's
  own store. Person names are synced labels that already reach every device of the realm. No photograph
  bytes and no photo lists. Purged on every identity teardown above.

**Step 3 — stop walking the library for the tab** (later, only if measurement asks for it). Years,
videos and places need counts and covers, which Control can answer as aggregates; the photo lists are
needed only on a section's own page. At ~1 ms warm, the walk is not today's problem.

**Also found (2026-10-01): a blank face that never recovered.** A cover's preview came back as **0
bytes** — not `null` — on a photo whose `xlarge` rung (55 KB) was on disk, so the bytes were lost on
the way, while Control was busy. `PreviewCache` refuses to cache `null` but cached the empty array, and
the circle stayed blank until Photos restarted. ⚠⚠ Empty bytes are now treated as *no answer*
everywhere a preview is read or cached. Why the transfer delivered nothing is open (`tech-debt.md`).

**Amended 2026-10-10 — where a refresh runs, and what its line says** (`photos-ios.md` §8.22.1).
`CollectionsViewModel` takes a `workDispatcher`: the six reads and every grouping of the library run
there, and state is published from `viewModelScope` as before. `null` keeps the caller's context —
right for the Compose shells, whose reads are IPC and suspend, and wrong on iOS, where a read is a
store query on the calling thread; the iOS shell passes `Dispatchers.Default`. ⚠ No grouping lives
inside `_state.update` any more: that lambda runs on the publishing thread, and again on every
contended retry. The summary line now gives each read's own running time, adds
`(answered after N ms)` when the answer was delivered late, and starts with `reads=background` or
`reads=caller` — six equal figures used to read as six slow reads and were one held thread.

**Amended 2026-10-10 — where every other repository call runs** (`photos-ios.md` §8.22.2). The
contract above is one view model's. For the rest it is the repository's: a shell whose calls do not
suspend installs `OffCallerPhotosRepository(repository, dispatcher)` once, and no view model changes —
its coroutine stays on `viewModelScope` and suspends at each call, as it does over IPC. What changed
in shared code, for every shell:
- `PhotosRepository.feedPaging(pageSize, readContext)` — where the feed's page reads run; empty (the
  Compose shells) keeps them where Paging calls `load`. Implementations whose pages are in memory
  ignore it.
- `PhotosFeedViewModel(workDispatcher = …)` — where the scrubber's year index is built; `null` keeps
  the collector's context.
- `boundedCall` cancels its call when its caller is cancelled. ⚠ It is the READS' bound; a write's
  is `boundedWrite`, which never cancels. Over Binder the cancellation changes nothing — the
  transaction runs on — and on a dispatcher a read that has not started never does.
- ⚠⚠ A new member of `PhotosRepository` is a compile error in `PhotosRepositoryDecorator` until it
  is named there. That is deliberate: `by` delegation would forward it in the caller's context.

**Amended 2026-10-10 — a refresh only when something changed** (`photos-ios.md` §8.22.3).
`PhotosRepository.libraryWatch()` answers whether what the tab reads has changed, without reading
it; `null` — the default — is "cannot tell", and the tab then behaves exactly as above. With a watch,
the view model gains three calls and one rule: `refreshIfChanged()` for a return, `changed()` for an
edit the app made, `setShown(surface, shown)` for whether anything is drawing the state — and a walk
is clean only if it read every section and the watch's revision is the one it **began** at.
`refresh()` itself is unchanged. ⚠ `MockPhotosRepository.watch` is `null` unless a test sets it, so
every existing test of this view model runs the old behaviour.

## 37. Measured — why the feed takes ~8 s to show photographs after a cold start (2026-10-01)

**Symptom.** On the maintainer's phone the *Photos* tab sits on a spinner for ~10 s after Photos is
launched, then draws every visible tile at once. §36 found that Collections' reads were fast and its
problem was serialisation; ⚠ **the feed is a different case — its reads are genuinely slow, and the
cold start runs up to five whole-library reads at once.** Previews are not part of it.

**How it was measured.** A timing patch (`FEEDTIME` lines; branch `diag/photos-feed-cold-start`,
never to be merged) on top of §36's per-section Collections loading, on the OnePlus CPH2581: 1,401 photographs, thumbnails local,
Control warm unless stated, Photos force-stopped and launched with `monkey`. The patch carries four
experiment switches read from `debug.feedtime.mode`, so each candidate fix was measured on the same
build, the same library and the same afternoon. Times are launch → the first rows composed;
screenshots confirmed the tiles are on screen within ~0.1 s of that line.

### 37.1 Where the time goes

| run | first rows | what happened |
|---|---|---|
| as shipped (4 runs) | **7.1 / 7.8 / 8.0 / 11.1 s** | below |
| Control force-stopped too | **30.2 s** (as-shipped build: spinner still up at 27 s) | below |

A warm-Control cold start, in order:

1. **~0.6 s** — process start, Koin, first composition; the view model exists and the first page
   read is issued before the Control connection is up.
2. **The first read is thrown away.** `reloadSignals()` emits an immediate load and then a
   *subscribe tick* once the change observer is registered with Control; `flatMapLatest` cancels the
   first generation for the second. The tick arrived **0.4–2.7 s** after the first read began.
   ⚠⚠ **The cancellation does not reach Control**: the abandoned read's `page.throw` arrived
   **1.9–8.2 s** later — the full traversal ran anyway, beside the one that counts. Every density
   change pays the same double read (§28's zoom control rebuilds the stream).
3. **The page read: 3.5–7.9 s** for 180 rows (initial load = 3 × 60), while Control is also
   answering, all issued within ~0.2 s of launch:
   - the abandoned first-generation read (above);
   - `feedCaptureTimes()` — the scrubber's year marks, a whole-library `getAll` (3.3–6.0 s);
   - the shared content index — a whole-realm `flow()` projection (**5.3–14 s** to its first
     emission), subscribed by `CollectionsViewModel`'s **init** refresh: the view model is created at
     the App level, so the Collections tab's six reads run on every launch, on the *Photos* tab;
   - Collections' other five reads (§36, ~0.5–0.8 s together).
4. **The count, after the page: 1.0–1.8 s** (0.6–1.0 s on a warm reload).
   `TenshuKeysetPagingSource.load` reads the page, *then* awaits `total()` — a filtered
   `count().resolve()` over a full traversal — before returning it.
5. **~0.1 s** — first composition with rows, then previews: **20–40 ms per tile**, local reads, 4 in
   flight, ~9 tiles on a phone screen in the mosaic. ⚠ Not a factor on this device (its thumbnails
   are local — the same caveat as §30.3; a byte-less device adds a pull per tile).

**The floor.** With the side reads out of the way, a page read on its own still costs **~1.6–2.1 s**
(an append of 60 rows, a 60-row first page, a 180-row reload from the zoom control — all measured),
and 60 vs 180 rows differs by only ~0.3–0.5 s. The cost is the **walk**, not the rows or the Binder
payload: every page was `traverse()` + three filters + a full sort of the realm, because a
`traverse`/`filter` upstream of the sort disarmed the top-N fusion (`core-ordered-window-fusion.md`
§3.1 gate 2). That is PH-G1's open cost half, measured here at ~1.1–1.5 ms per realm file, per page.
⚠⚠ **Measured before F4.** Filter-aware fusion (F4, `core-ordered-window-fusion.md` §9) shipped
the same day: the feed's page now runs as ONE thin walk with a bounded top-N — still O(realm) thin
reads, but no full materialisation and no full sort (JVM: 117 → 57 ms a page at 10k files). ⚠ Every
PHONE timing in this section — §37.1, §37.2's table and the ~2.8 s included — was measured BEFORE
F4 (the JVM benchmark just quoted is the post-F4 exception); the post-F4 phone cost is **not yet
measured** and is owed by the PR that builds fixes 1–4. ⚠
`feedTotal` (a filtered `count()`) and `feedCaptureTimes` are unwindowed and still materialise (§9.6).

**Control's own state.** Throughout the session Control sat at **~100–180 % CPU with Photos idle**,
pushing a **995 KB stream emission** to a client callback roughly **every 5 s** and toggling its
foreground service at the same cadence (Drive's process spiked in step). ⚠ The caller is not
attributed — Control logs nothing about it — but it is load every Photos read competes with, and it
belongs to its own investigation. The phone was also under memory pressure (~0.4 GB free, ~7.7 GB
swap in use). A **Control cold start** is far worse: the subscribe tick came **8 s** after launch
(the sync runtime was elected three times, Drive's session reconnected), and every read took
21–27 s.

### 37.2 The fixes, measured on the same build (⚠ pre-F4 — see §37.1)

| switches | first rows (runs) | vs as shipped |
|---|---|---|
| none (as shipped) | 7.1 / 7.8 / 8.0 / 11.1 s | — |
| `onegen` — no immediate load, start on the subscribe tick | 5.0 s | −3 s |
| `defer` — scrubber marks + content index wait for the first rows | 3.5 / 4.5 s | −4 s |
| `defer` + `onegen` + `parcount` — the count runs beside the page | 3.3 s | −5 s |
| … + `small` — first load is one page (60), not three | **2.7 / 2.9 s** | **−5.5 s** |

What remains at 2.7 s is ~0.7 s of launch and connection, ~0.4 s waiting for the subscribe tick,
and the ~2 s page read.

### 37.3 Ranked fixes

1. **Keep the cold start's side reads off Control until the first page lands** — measured −4 s,
   cheap. Two callers: (a) `CollectionsViewModel`'s `init { refresh() }` — the tab already refreshes
   when it is selected, so a launch on the Photos tab should not pay for it (or should pay after the
   feed's first rows); (b) `yearMarks` — the scrubber can wait for the feed, it is useless before it.
   ⚠ (a) touches the view model §36's per-section loading and the Collections-cache branch are changing — coordinate. ⚠
   The cost is Collections' first open: its reads start when the tab is opened rather than at launch.
   ⚠⚠ **The iOS tab relies on that `init` refresh**: `photos-ios/.../CollectionsScreen.swift` skips
   `refresh()` on its first `onAppear` because the initializer already ran one. Defer the refresh only
   in the Compose shell, or change iOS's first appearance in the same PR — otherwise the iOS tab never
   loads on first open.
2. **One first read, not two** — measured −3 s alone, and it removes a duplicate whole-realm read
   from every density change. ⚠⚠ **Keep an error path**: waiting for the observer before the first
   read means a down or schema-skewed Control yields NOTHING — `TList.changes()` retries a failed
   subscription and emits nothing until it registers, so no `Pager` is built and no `LoadState.Error`
   (or "Can't reach Tenshu Control") ever appears. Subscription failure must surface as a feed error. Two parts: start the first generation once the observer is live (or
   let the subscribe tick re-read only when a change was actually missed), and ⚠⚠ find out why a
   cancelled client read is not cancelled in Control — that is platform-wide, and any listing that
   is superseded while loading (a fast tab switch, a scrub) pays it.
3. **Count beside the page, not after it** — hides 1.0–1.8 s. A `:core-paging` change to
   `TenshuKeysetPagingSource.load`; the memo is already per generation (`core-keyset-paging.md` §13).
4. **First load = one page** — ~0.3–0.5 s. `seekablePagingConfig`'s `initialLoadSize` defaults to
   3 × `pageSize`; 60 rows already fill a phone screen several times in any density.
5. **Show the last first screen at once** — the only fix that helps when Control is cold (30 s) or
   busy. Persist the top ~30 rows (path, capture time, size, content id, ⚠ the media kind and duration
   — `File.Category`, `File.DurationMs` — or a cached video draws and behaves as a still) and their
   tile bytes in Photos, draw them on open, replace them when the first page lands. Medium cost;
   ⚠ a photograph trashed elsewhere can show for a moment, and ⚠⚠ the cache must be purged on EVERY
   identity teardown — realm reset or switch, sign-out, and Control revoking Photos — or it can be
   drawn for an identity the session no longer has, indefinitely if Control stays away. The same
   caveats as §36's Collections step 2.
6. **Make a feed page cost O(page), not O(realm).** ⭐ The first half shipped as F4: the page
   is one thin fused walk instead of materialise + filter + full sort. What remains is the O(realm)
   thin scan itself — the fix that scales from here is a Control-side media-by-capture-time index
   (`core-ordered-window-fusion.md` F3) — and the two unwindowed reads that still materialise, the
   filtered count and the scrubber's capture times (§9.6). Re-measure on the phone before sizing it.
7. **Control's background load and cold start** — out of Photos' hands: the unattributed 5 s,
   995 KB stream and the 8 s to a live observer after a Control restart each need their own
   measurement.

Items 1–4 together measured **~8 s → ~2.8 s** on a warm Control, before F4. ⚠ The 30 s
cold-Control case is **unmeasured with them**: only the as-shipped baseline was run with Control
force-stopped. 1–4 cannot remove the ~8 s before the observer registers, but they remove work that
competes once it does (the abandoned first read, the deferred side reads), so they may shorten the
21–27 s read after it — re-run that case with the fixes before ranking 5 and 6 for it.

## 38. As built — the feed's first rows, four cheap fixes (2026-10-01)

**Why.** §37 measured a warm-Control cold start at 7.1–11.1 s to the feed's first rows and ranked the
fixes. This section is items 1–4 of its §37.3, built; items 5–7 (a persisted first screen, the rest of
an O(page) feed read, Control's own background load) are not. It also answers §37's open question —
the cold-Control case with the fixes (§38.2).

### 38.1 What changed

1. **One first read, not two — in `:core-paging`'s keyset source.** For `keysetPagingSource`,
   `reloadSignals()` no longer emits an immediate tick beside the observer's subscribe confirmation:
   the confirmation **is** the first load. ⚠ The offset `pagingSource` (Drive's folders, Control's
   events, Fit's history) is deliberately unchanged — its pages are bounded and cheap, and waiting
   would put a subscribe round trip (~0.5–0.9 s warm) in front of every folder's first rows, a trade
   not measured for those listings. Reading after the observer is registered closes the missed-write gap the
   old read-then-re-read closed, by ordering instead of by a duplicate read. Every density change
   (§28's zoom control rebuilds the stream) goes through the same function, so it reads once too.
   Two exits keep the wait from becoming a spinner:
   - ⚠⚠ **A refused subscribe ticks at once** (`changes(emitOnFailedSubscribe = true)`, new in
     `:core`). An unreachable or schema-skewed Control refuses the subscribe, `changes()` then retries
     silently and emits nothing — so without this the feed would never read, never fail, and never
     show *"Can't reach Tenshu Control"*. Pinned: `FirstLoadWaitsForTheObserverTest
     .aRefusedSubscribe_endsInTheFeedsErrorState_notAnEndlessSpinner` (verified by mutation: with the
     flag off it fails "at once — not after the deadline").
   - **`FIRST_LOAD_OBSERVER_DEADLINE_MS` = 10 s** for a confirmation that never arrives. ⚠⚠ Sized for a
     **cold** Control: there the confirmation came 6.4–8.6 s after launch, and the first build's 5 s
     deadline was overtaken — the read went out, the confirmation re-read, and the double read came
     back exactly where Control is slowest (§38.2). A deadline that fires early costs a whole duplicate
     read; one that fires late costs only the rare lost confirmation.
2. **Nothing else reads until the first rows land.** `FeedFirstPage` (one per app, a Koin `single`)
   is marked when the first photograph passes through `PhotosFeedViewModel.items`' map; the
   scrubber's whole-library `feedCaptureTimes()` waits on it, and so does `CollectionsViewModel`'s
   first refresh when the shell passes `startAfter` — and with that refresh the shared content index's
   whole-realm projection, whose only launch-time subscriber it was. Deferred, never dropped: the
   feed's *Add to album* sheet reads `CollectionsUiState.albums`, so Collections still refreshes on
   every launch, the moment the rows land. ⚠ Opening the Collections tab first runs its own refresh,
   and the deferred one is then skipped (`generation == 0`), never a second walk.
   ⚠⚠ **The iOS shell is deliberately unchanged**: `PhotosSurface.collections()` passes no gate, so
   the constructor still refreshes — `CollectionsScreen.swift` skips `refresh()` on its first
   `onAppear` *because* the initializer performs it. Gating iOS means changing that Swift path in the
   same PR. iOS's scrubber marks do wait (its feed view model owns its own `FeedFirstPage`).
3. **The count reads beside the page** (`TenshuKeysetPagingSource.load` starts `total()` with
   `async` before the page read and awaits it after; a failed page cancels it).
4. **The first load is one page** — `seekablePagingConfig` sets `initialLoadSize = pageSize`, the call
   Drive and Fit already made. The next page is prefetched the moment the first is drawn (Paging's
   prefetch distance is a page), so scrolling does not wait on it.

Tests, red on master first: `FirstLoadWaitsForTheObserverTest` (the confirmation is delivered *after*
`registerCallback` returns, as Binder does — a fixture that confirms inside it collapses the two reads
and hides the defect; master read `[30, 30]` where one read of 10 was wanted),
`KeysetPagingSourceTest.theCountIsReadBesideThePage_notAfterIt` (master: 2000 ms of virtual time, not
1000), `FeedFirstStartupTest` (built from the app's **own** Koin module — master issued
`[allPhotos, places, labels, faceGroups, albums, favourites, captureTimes]` before the first page),
`CollectionsWaitsForTheFeedTest`.

### 38.2 Measured (OnePlus CPH2581, 1,401 photographs, FEEDTIME timing build)

Same method as §37 — Photos force-stopped and launched with `monkey`, launch → first rows composed,
on the phone's own clock — but **on current master**, which includes F4 (filter-aware fusion made
each feed page cheaper in Control), so the "before" column is not §37's.

| | before (master `c64602dac`) | after (this PR) |
|---|---|---|
| Control warm | **5.3 / 5.4 / 5.8 / 9.1 / 10.8 s** | **2.2 / 2.4 / 2.5 / 2.5 / 2.6 / 2.6 / 2.7 / 2.8 s** |
| Control force-stopped (cold) | **26.1 / 28.2 s** | **9.2 / 9.6 s** (5 s-deadline build: 13.8 / 16.5 s) |

Where a warm start's 2.5 s goes now: ~1.2–1.6 s to the observer's confirmation (process start,
Koin, binding, the subscribe round trip), then the page — **0.6–0.8 s for 60 rows** — and the count
beside it, which now finishes **0.4–0.5 s after the page** (~1.1–1.2 s). ⚠ **The count is the new
long pole**, warm and cold (cold: page 1.1–1.5 s, count 3.1–3.7 s): the next cheapest win is to
draw the rows before the count lands, or to make the filtered count cheaper in Control. The
master runs that took 9–11 s were the ones where Control was also busy (a reconnect mid-run in one).
The quiet ones were 5.3–5.8 s: the read that counted began only at the confirmation (1.7–2.2 s), read
180 rows in 1.5–1.8 s beside the abandoned first read and the side reads, then counted for 1.75–1.9 s
more.

The side reads still run, after: on a warm start the scrubber's capture times took ~3 s and the
content index's build 5.9–6.9 s, both behind rows already on screen.

### 38.3 Still open

- **A cancelled read now stops in Control** — that is `cancellableReads { }`, merged after
  these measurements were taken, so none of §38.2's numbers include it. Both reads a feed load makes
  go through it: the page, and the count running beside it (`total()`), so a failed page's
  `counting.cancel()` and a superseded generation stop the count in Control too
  (`KeysetPagingSourceTest.theCountBesideThePageIsACancellableRead_andAFailedPageCancelsIt`).
- **A density change was not measured on the device.** It takes the same path (a new
  `keysetPagingSource` flow → `reloadSignals()`), so it now reads once — after one subscribe round
  trip (~0.3 s warm) instead of at once.
- §37.3 items 5–7, unchanged.

## 39. Measured, then fixed — Collections' whole-library read (2026-10-01)

**Symptom.** With §36–§38 in place, the first Collections refresh after a cold start on the
maintainer's phone (1,401 photographs) read
`places=65ms favourites=64ms allLabels=100ms faceGroups=437ms allPhotos=6080ms albums=6108ms`.
`allPhotos` is the library every derived section is built from; `albums` waited on it because its
cover shapes were a lookup in the same index.

### 39.1 Where the 6 s went

`allPhotosRead()` was the shared content index (§20): `RealmContentIndex`, a `.flow()` over
`traverse().filter { notTrashed() }.select { path + 9 properties }` — every file in the realm, as ONE
stream emission. Split with a timing build on the emulator (1,400 synthetic photographs, Control
warm, three cold starts of Photos — `allPhotos` 2.38–2.43 s there):

| stage | emulator | share |
|---|---|---|
| subscribe round trip + Binder hand-off | ~70 ms | 3 % |
| **Control: walk + materialise + project** | **1.56–1.58 s** | **66 %** |
| Control: serialise the emission (**919 KB**) | 0.17–0.19 s | 7 % |
| Photos: parse it | 0.36–0.40 s | 16 % |
| Photos: `StreamRows.merge` (a DataId hash per row) | 0.15–0.17 s | 7 % |
| Photos: build the index + map to `Photo` | 0.03–0.04 s | 1 % |

The phone's 6.1 s is the same shape ~2.5× slower. Two findings decide the fix:

1. ⚠⚠ **`Select` read the stored row once per property.** Each `relations(…)` entry went through
   `resolveProperty` → `getDataId` + `database.getData` — a full read and decode of the row — so
   nine properties were nine reads. The JVM harness (`WholeLibraryReadBenchmarkTest`, production
   store pair) counts it: **9.2 store reads per file** for the index projection against 2.0 for a
   path-only walk; the projection was ~75 % of the walk. Platform-wide — every multi-property
   projection paid it, the feed's 60-row pages included.
2. ⚠⚠ **The emission is the whole realm in one Binder transaction, and it is at the ceiling.** 919 KB
   on the emulator, **995 KB** on the phone (§37.1's "995 KB stream emission" is this read), against
   Binder's ~1 MB per-process transaction buffer — logcat already warns *"Large data transaction of
   918860 bytes"*. ~640 bytes a row: a library a little larger than the phone's gets **no answer at
   all**, and at 10,000 photographs the emission is ~5.8 MB (JVM harness). Paging or narrowing is not
   an optimisation here; it is what makes the read possible.

Ruled out by the same numbers: client-side decode is 23 % (parse + merge), not the problem; the index
build and the grouping after the read are ~1 %; Collections' other reads were already fast
(50–440 ms) — they only looked slow because `albums` waited on the index.

### 39.2 What changed

1. **`Select` reads each row once** (`:core-common`). New `ReferenceResolver.resolveProperties`:
   one call per projected row, one store read for all of its **stored value** properties; anything
   else (dynamic, reference properties, entity relations) still goes through `resolveProperty`
   unchanged, so every answer is the one-at-a-time answer — only the read count moves. No wire,
   schema or op change (HR #9 untriggered). JVM, index projection: 9.2 → 2.9 store reads per file;
   Control's walk 67 → 28 ms at 1.4k files and 288 → 134 ms at 10k.
2. **Collections reads the library itself, in bounded pages** (`TenshuPhotosRepository.readLibrary`).
   `traverse · category == Image · createdAt ∈ [0, cursor] · notTrashed · sortByDescending(CreatedAt)
   · drop(skip) · take(500) · select { path + 6 properties }` — the feed's keyset cursor (ties at a
   boundary neither lost nor repeated), each page one fused thin walk (§9 of
   `core-ordered-window-fusion.md`) and one reply of ~216 KB (500 rows × ~430 bytes, emulator).
   - Images only, as the index read was; ⚠ ONE category predicate (`category equalTo Image`), not
     `isMedia()` and then `Image` again — a dynamic predicate is the dearest thing a fused walk
     evaluates per row (JVM: one is ~30 % of a 500-row page's walk).
   - Narrower rows: no `ContentHash` or `Size` (only Duplicates reads those), no `Category` or
     `DurationMs` (an image has none).
3. **Nothing on the Collections tab subscribes to the shared index any more.** Favourites and album
   covers are one fused walk over `contentId oneOf ids` (a new `File.contentId` chain accessor; ids
   in chunks of 200, because `oneOf` scans its candidates per row). The index now serves Duplicates
   alone. ⚠ A side effect worth knowing: while Photos was open, that stream re-evaluated the whole
   realm in Control on every write anywhere in it — the emulator logged re-runs after each
   thumbnail-backfill pass.

What the library answers, against the index read: the same photographs newest first, but **with**
the ones that carry no content hash (uploaded before the hash was stamped — the index cannot key
them; the feed shows them) and **without** the ones that carry no capture time (the feed drops them
too; the index filed them under 1970). ⚠ Videos are still left out, as before: `allPhotosRead` was
images-only behind the index, so Collections' *Videos* row has not been drawn since §20 — kept as
is here, not fixed in passing.

### 39.3 Measured

| | before | after |
|---|---|---|
| JVM, 1,400 files: Control walk + serialise + parse | 67 + 46 + 54 ms (one 808 KB reply) | 29 + 18 + 31 ms (3 pages, 474 KB total) |
| JVM, 10,000 files | 288 + 264 + 351 ms — **one 5.8 MB reply: no answer over Binder** | 728 + 133 + 204 ms (17 pages ≤ 210 KB) |
| Emulator, `allPhotos` | 2.38 / 2.39 / 2.43 s | **1.34 / 1.37 / 1.38 / 1.48 s** |
| Emulator, `albums` | 2.38 / 2.39 / 2.44 s | **61 / 87 / 87 / 97 ms** |
| **Phone** (OnePlus CPH2581, 1,401 photographs), `allPhotos` | 6.08 (reported) / 6.92 / 7.37 / 7.22 s | **3.50 / 3.59 / 3.66 s** (4.02 s with Control just reinstalled) |
| **Phone**, `albums` | 6.11 / 6.94 / 7.40 / 7.25 s | **91 / 121 / 142 / 167 ms** |

Phone: cold starts of Photos, Control warm, read off the `Collections ->` line; "before" is the
installed build of the day (master-based), "after" this PR's Control + Photos (no schema bump, so only
those two). (Emulator "after" runs on a loaded host — load average 15–26 — after one warm-up; the first run
after a Control restart is slower in both columns.) Per page on the emulator: Control 0.24–0.39 s
(walk + projecting 500 rows), serialise ~50 ms, Photos ~0.15–0.2 s.

### 39.4 Tests

- `SelectReadsEachRowOnceTest` (`:file-service-proxy`, 2) — the index's nine-property projection
  over a counting store costs at most ONE extra store read per row beyond a path-only walk (master:
  **8.0 extra per row**, red), and every property answers exactly what it answers alone (parity over
  values and absences).
- `RealRepositoryReadTest.theCollectionsReadsNeverOpenTheWholeRealmStream` — the library, favourites
  and album covers read with the index wired as the app wires it (a share scope), and no stream open
  afterwards (master: `expected:<0> but was:<1>`, red).
- `RealRepositoryReadTest.theLibraryReadKeepsEveryPhotographAcrossPages_includingATieLongerThanAPage`
  — a guard, green on master too: 537 photographs at one capture time across the 500-row boundary,
  each read exactly once, nothing that is not an image.
- `aPhotographsShapeReachesEveryReadTheGridDrawsFrom` (existing) now covers the cover-shape walk.
- Mutation-checked: with the `Select` change reverted only `SelectReadsEachRowOnceTest`'s count test
  goes red; with the repository reverted only the stream test does.

### 39.5 Still open

- ⚠⚠ **A page is still an O(realm) walk**, so the read is O(realm × pages): 3 walks at 1,400, 17 at
  10,000 (JVM 0.73 s of walking). It now *works* at 10k where it did not, but it is quadratic. The
  scaling fix is the one §37.3 item 6 already names — a Control-side media-by-capture-time index
  (`core-ordered-window-fusion.md` F3) — or Collections asking Control for aggregates (§36 step 3).
- ⚠ **The scrubber's `feedCaptureTimes()` still materialises** (`core-ordered-window-fusion.md`
  §9.6): on the emulator it is a 0.71–0.75 s read that starts with Collections' refresh and slows its
  first page by ~0.1–0.15 s.
- ~~⚠⚠ **Duplicates still reads the whole-realm index** — the same single transaction, so on a realm a
  little over the phone's it fails to load at all.~~ **Fixed in §40**, with the backup drain's skip;
  the index is gone.

## 40. Duplicates and the backup skip, off the single-transaction index (2026-10-01)

**Why.** §39 left two readers on the shared content index — every non-trashed file in the realm,
nine properties each, in **one** reply (~640 bytes a file; 995 KB on the phone, ~5.8 MB at 10,000
files, and past Binder's ~1 MB no answer at all):

1. **Duplicates** (`RealmContentIndex`'s stream) — on a realm a little over the phone's it said
   *could not look*;
2. **the backup drain's "already uploaded" skip** (`ContentIndex.load`, a one-shot `getAll`) — and
   ⚠⚠ a load that failed meant *skip nothing*, so every candidate re-uploaded: content the realm
   already held, billed twice, silently (`drive-synced-folders.md` §8d).

### 40.1 The narrowest question each one asks

| consumer | question | read | per-reply bound |
|---|---|---|---|
| backup skip | *which of these ≤32 candidate hashes does the realm hold?* | `RealmContentReads.heldContentHashes`: `traverse · contentHash oneOf ≤200 · notTrashed · sortBy(ContentHash) · take(1,000) · select { ContentHash }` — one fused walk (§9 of `core-ordered-window-fusion.md`); a full reply re-asks only for the hashes it did not name | 1,000 hash-only rows, **~205 KB** |
| Duplicates | *which contents appear more than once?* | (1) every file's `(path, contentHash)`, `sortBy(path) · drop · take(500)` pages; grouped client-side, so a group straddling a page is one group; (2) the duplicated copies' full rows by `path oneOf ≤200` | (1) at most 500 + 32 overlap rows, ~275 B + the path each: **~166 KB** for ordinary paths (≤ 1.5 pages if a page must look further back); (2) at most 200 nine-property rows, ~140 KB. ⚠ Both carry a path, so both are sized in **bytes** too — see *A path has no length limit* below |

Why these shapes, and not others:

- **No Control-side grouping.** "Group by hash, count > 1" would be a new op — a wire change for one
  screen. The page walk asks nothing new of Control; the client already grouped.
- ⚠⚠ **Offset pages, kept honest by an overlap.** A keyset cursor needs "greater than" over the key;
  the chain has none for strings, and teaching `inRange` strings would make an older Control answer
  `false` — an empty page that silently ends the read. So a page is `drop(offset)`, and each page
  re-reads 32 rows behind the cursor and must start at or before the last path held: inserts before the
  cursor are tolerated (only paths after it are taken), deletions up to the overlap are tolerated, past
  it the page looks back up to half a page, and past that the walk restarts (3 times, then *could not
  look*). Paths are unique, and the client compares them with `String.compareTo`, which is `SortBy`'s.
- ⚠⚠ **A path has no length limit, so a row count is not a byte bound**. Nothing
  refuses a long path at write time, and 500 rows of 2 KB paths are already past Binder's ~1 MB — the
  very ceiling this section removes, back again for a deeply nested realm. Both reads that carry a path
  are sized to `REPLY_BYTE_BUDGET` (256 KB, a quarter of the buffer every in-flight transaction shares):
  - *phase 2* knows its paths before it asks, so `chunkWithinReply` splits them by what their rows
    will weigh (700 B + the path) — a bound **by construction**, for the request too;
  - *the page walk* cannot count bytes it has not read, and a byte-capped window would be a new op
    parameter (a reshape, HR #9). So (a) **a page that got no answer is asked again at half the rows**,
    down to one new row, and that ceiling stays lowered for the walk — this is what holds whatever the
    paths are; only a single row too big for a transaction is *could not look*; and (b) **each page
    sizes the next** from the bytes it carried, which is accurate because the rows are sorted by path
    and neighbours share a prefix. The overlap shrinks with the page.
  ⚠⚠ **When two rows will not fit a transaction but one will** (paths past ~half a megabyte — no real
  filesystem makes one, but nothing here forbids it), the smallest overlapping page is already too
  big. The walk then reads **one row to a transaction**: the row at the cursor, then the row before
  it, which must still be the last one held; any other answer restarts the walk. ⚠ This is weaker
  than the overlap and cannot be made equal to it with the ops that exist — the overlap is exact
  because the anchor and the new rows are *one* read, and here they are two. A delete before the
  cursor ahead of the first read **and** an insert before it between the two cancel out unseen, and
  one row is missed for that walk (a group a copy short; or, in the skip's fallback, one re-upload).
  The exact alternatives are all outside this change: a string keyset (`path greaterThan`) or a
  byte-capped window — a new predicate/op parameter, a reshape (HR #9), and an older Control would
  answer it wrongly — or a path-length limit enforced at write time. Without any of them the choice
  for such a realm was this or *could not look*, for good.
  ⚠ The lowered ceiling never rises again within a walk, so one cluster of such paths leaves the rest
  of that walk at one row per two transactions.
  ⚠ "Too big" is indistinguishable from any other failed read, so a dead transport now costs ~9 failed
  calls before the same *could not look*. ⚠ That Binder refuses an oversized reply as a failed call
  (`TransactionTooLargeException` → `BinderTaskTransport.asNotConnected`) rather than a hang is read
  from the code and modelled in the test — **not observed on a device**.
- **Phase 2 by path, not by hash**, because a path is a field every row has (40.2) — so Duplicates
  works on any Control.
- **The skip asks per batch, not per candidate**: each walk is O(realm), so the drain hashes a
  lookahead of up to 32 candidates (≤ 64 MB of reading — a Stop still lands after the batch) and asks
  once. A drain with nothing new asks nothing.

### 40.2 ⚠⚠ A predicate over an absent stored property is "no match" now (`:core-common`)

`contentHash oneOf …` could not run on any real realm: a **folder** carries no content hash (nor does
a file uploaded before hashes were stamped), and `evaluateCallstack` **raised** *"Could not resolve
property reference"* on the first one — failing the whole read. That contradicted two contracts the
code already documented (`inRange`: *absent is never in range*; `File.contentHash`: *no predicate over
it matches such a row*). §39's `contentId oneOf` only escaped it because `category equalTo Image`
runs first and drops folders.

Now: an absent **stored value** property throws `AbsentPropertyException` (still an
`IllegalStateException`, same message — so nothing outside a predicate changes), and
`evaluatePredicateOn` — the per-row body of `Filter`, and the fused walk's fallback for an absent head —
reads it as `false`. An unresolvable dynamic property, reference property or entity relation still
raises. No wire, schema or op change (HR #9 untriggered).

⚠ **Skew:** a Control from before this still raises, so `heldContentHashes` fails against it on any
realm with a folder. The backup gate then falls back, once per drain, to `everyContentHash` — every
hash in the realm in 500-row pages — and answers the rest of the drain from that set: the old cost
class, but bounded per reply. Mutation-checked: with the core change reverted, the drain's bounded-read
test stays green through the fallback. Duplicates never needed it (phase 2 is by path).

### 40.3 "Could not look" defers, it never uploads

`DuplicateContentGate.openForDrain()` returns `null` **only** when the setting is off. A failed read
is `ContentPresence.Unknown`, which the drain handles as a failed item (§8c of
`drive-synced-folders.md`): the watermark freezes, the run summary carries the typed cause
`BackupFailureKind.COULD_NOT_CHECK_REALM` (each platform's own copy, never English from the engine), the
URI is released from `seenUris` so an overlapping iOS album still offers it, the next drain asks again,
and five in a row stop the folder (§8d there).

### 40.4 Measured (JVM, production store pair)

`ContentHashReadBenchmarkTest` (opt-in, `TENSHU_CONTENT_BENCH=1400,10000`): `SqliteObjectStore` +
`ReentrantLockDatabaseLock`, files over seven `/Backup/Pixel 8/Camera <year>` folders, ~5 % repeated
bytes, every reply serialised as the transport would (`ReplySizes`). Median of 3 after 2 warm-ups, on a
loaded shared machine:

| read | 1,400 files | 10,000 files |
|---|---|---|
| **before** — the index projection (both consumers) | 1 reply, **794 KB**, 67 ms | 1 reply, **5.65 MB — no answer over Binder**, 339 ms |
| Duplicates — `duplicateScan` | 4 replies, largest **166 KB** (525 KB total), 53 ms | 26 replies, largest **167 KB** (3.9 MB total), 876 ms |
| backup skip — one 32-candidate batch | 1 reply, **3.6 KB**, 10 ms | 1 reply, **3.4 KB**, 22 ms |
| backup skip fallback (pre-§40 Control) — `everyContentHash` | 3 replies, largest 166 KB, 38 ms | 21 replies, largest 167 KB, 621 ms |

Row sizes (in-memory dump): a `(path, contentHash)` row is ~273 B with a 13-character name, a
hash-only row ~204 B, a path-only row ~153 B — ~130 B of every row is the entity's type ids.

⚠ Not measured on a device. The JVM store is warm and carries no thumbnails; re-measure on the phone
before quoting a phone number.

### 40.5 Removed

`RealmContentIndex` (the shared stream, and its DI line in `PhotosModules` / `PhotosLocalRuntime`),
`ContentIndex.load`, `ContentIndex.byContentId` and `ContentIndex.all` — nothing reads the whole realm
in one reply any more. `ContentIndex` stays as the in-memory grouping of the rows phase 2 returns.
⚠ Do not add a loader back: the realm that is too big to read in one reply is the normal case.

### 40.6 Tests

- `DuplicatesBoundedReadTest` (`:photos-shared-logic` jvmTest, real store) — 2,500 files, groups
  straddling the 1st/2nd page boundaries, the first and last file, a group over three pages, a PDF pair
  excluded; every reply ≤ 256 KB, no stream open. **Master: one 1,300,040-byte reply, red.**
- `SyncedFolderControllerTest.theSkipAsksTheRealmInBoundedReplies_andStillSkipsWhatTheRealmHolds`
  (real store, 2,000 files in a folder) — held candidate skipped, new one uploaded, every reply
  ≤ 256 KB. **Master: one 1,058,343-byte reply, red.**
- `SyncedFolderControllerTest.aRealmThatCannotBeRead_defersTheCandidate_neverUploadsIt` — the real gate
  over a client whose reads throw: nothing uploaded, the watermark unmoved. **Master: `[a.jpg, b.jpg]`
  uploaded, red.**
- `RealmContentReadsTest` (`:app-ingest`, 7) — the page walk across many boundaries; inserts and
  deletes behind the cursor mid-walk (within and beyond the overlap), nothing lost or repeated; the
  presence lookup exact, over a realm with folders, and when one hash's copies fill a reply; the scan's
  groups across pages; a throwing client is *could not look* for both reads.
- `RealmContentReadsTest.aRealmOfLongPaths_isStillScanned_inRepliesATransactionCanCarry` — 320 files
  with ~6 KB paths, the **production** page and chunk sizes, over a proxy that refuses any reply past
  1 MB as Binder would: the scan completes with every group, and after the first page that gets through
  (783 KB — nothing had measured a row yet) every reply is ≤ 256 KB. Mutation-checked: no halving →
  *could not look* after one refused reply; `chunkWithinReply` ignoring bytes → phase 2's 1.3 MB reply
  refused; no next-page sizing → pages of 984 KB and 639 KB.
- `RealmContentReadsTest.aRealmWhoseRowsFitATransactionOnlyAlone_isStillScanned` — four ~600 KB
  paths (two pairs) among short ones, 1 MB ceiling: the scan completes with both groups. With the
  one-row mode removed: *could not look* after 9 refused replies.
  `…whenOnlyOneRowFitsAReply_theWalkStillReturnsEveryFileOnce` and
  `…aDeleteBehindTheCursor_whileReadingOneRowAtATime_isSeenAndNothingIsSkipped` pin the mode itself
  at a ceiling measured between a one-row and a two-row reply; with the anchor read's check removed
  the second loses `f006`.
- `RealRepositoryReadTest` — the shape test now covers the Duplicates scan; the stream test covers
  Duplicates too (`theCollectionsAndDuplicatesReadsNeverOpenAWholeRealmStream`).
- `ReplySizes` + `TestProxy.replyObserver` (`:core-test-utils`) — the seam: an in-process reply is an
  object, so nothing could tell a 50-row page from a whole-realm reply before.
- Mutation-checked, each reverting one piece and running all of the above: one 1,000,000-row page →
  only `DuplicatesBoundedReadTest` red; the overlap check removed → only the mid-walk test red; the
  re-ask loop removed → only the saturated-reply test red; the core change reverted → only the
  folder test red; `Unknown` uploading → only the defer test red.

### 40.7 Still open

- ⚠ **Duplicates is O(realm × pages)** — 21 page walks plus one per 200 duplicated copies at 10,000
  files (26 walks, 0.88 s on the JVM), the same
  quadratic shape §39.5 names for Collections, with the same fix (a Control-side index).
- ⚠ The skip's batch is one walk per 32 candidates: a first backup of 5,000 photographs is ~160 walks.
  Small against the uploads it saves, but unmeasured on a device.
- ⚠⚠ **On iOS it is one walk per candidate** — 5,000, not 160 — because an unsized candidate ends the
  batch and PhotoKit sizes nothing cheaply. The owed fix (a lookahead weight from pixel count and
  duration) is in `drive-synced-folders.md` §8d.2.

## 41. A photograph syncing in is shown: the feed follows the top, and a tile asks again (2026-10-13)

### 41.1 The feed follows the top

**Reported as:** the desktop app does not receive photographs from the phone, while the tray and
Drive show them.

**What it was:** it received them. A heap dump of the running app had the arrival as the first tile
of the list the grid was presenting, and the library count one higher. A lazy grid keeps its first
visible line by **key** (`FeedLayout.key`), so a photograph that starts a new day — a header and a
row — pushed the old first line to index 2 and the grid went with it. The arrival was drawn one line
above the viewport.

**The rule** (`PhotosFeedScreen`, shared by desktop and Android): at rest at the very top — nobody
scrolling, scrubbing or re-anchoring — a change of the first line's key requests index 0 for the next
remeasure. Anywhere else the key rule stands; it is what keeps a reader's place while photographs
land above them. iOS has had the same rule since `photos-ios.md` §8.20.

- ⚠ Decided in composition, not in an effect: by the time an effect runs the grid has re-measured.
- ⚠⚠ **Before calling a feed "not reactive", scroll up** — or read the presented list, not the pixels.
- Tests: `PhotosFeedScreenTest.aPhotographArrivingAtTheTopIsShown_whenTheFeedRestsThere` (red with
  the request removed: index 2, not 0) and `…DoesNotMoveAReaderFurtherDown`.
- ⚠ Not verified on an Android device.

### 41.2 A tile whose bytes have not arrived asks again

The second half of the same report, found once §41.1 put the arrival on screen: the tile stayed a
grey placeholder until it had been scrolled away and back a few times.

A photograph's **row syncs before its thumbnail**, so the tile is composed while there are no bytes
anywhere. `PhotoTile` asked once per (path, size), and `PreviewCache` rightly remembers no `null` —
but nothing asked a second time, so "a wait, not an error" was a wait nobody ended. A scroll
recycled the tile, which is the only thing that re-ran the load.

**The rule** (`PhotoTile`, every screen that draws one): a `null` is asked for again while the tile
is composed — after 1 s, doubling to a 60 s cap, never stopping. Bytes end it, decodable or not.

- ⚠ The steady state is one read a minute per visible tile that will never have a thumbnail. Not
  measured against a library with many of them.
- ⚠ Polling, not a signal: whether a thumbnail landing ticks the feed's change stream was not
  established, and an equal `Photo` would not re-key the tile if it did.
- Test: `PhotoTileRequestSizeTest."a tile whose bytes had not arrived asks again until they have"`
  (red with the wait removed).
- ⚠ Reasoned from the code and the report; the null first read was not observed on the device.

## 42. Web scope — questions for the maintainer (web-app-suite Wave 2, 2026-10-23)

**Status:** DECIDED 2026-10-24 (§42.6) — was a question PR (`web-app-suite.md` §8.1). Nothing below is built. Run A (the SDK
boundary and bridge) builds only what the brief lists as undisputed: the capture-date feed with keyset
paging and the scrubber index, the viewer on the large-preview rungs plus the stored detail fields,
albums, favourites, the trash and `MOTION` clip bytes. The answers to this section decide what run B
(the app) and any later run may add.

The ground facts every item below rests on:

- The browser runtime registers `:inference-service` and `:place-service` for **sync only**
  (`SyncedServicePlugins.kt`, F7): their rows arrive and merge, but no operation factory and no
  `init*` runs, so their maintained relations are not maintained and their ops do not dispatch.
- `:inference-service` reports `NO_BACKEND` on JS. There is no ONNX runtime in the browser build.
- A browser upload already mints the still ladder at upload (`drive-web-parity.md` W2b), through
  `createImageBitmap`, so a mint only works for what the browser can decode.
- ⚠⚠ Nothing on web may project `File.Preview` (`drive-web-parity.md` §1.2i). The viewer reads the
  stored classes through `FileSystem.getLargePreview`, which says whether the covering rung is
  `present`, `absent` or `unknown`.

### 42.1 Faces, labels and semantic search

- **The browser can** read synced rows. `MediaLabel` rows sync, and the inference schema is registered.
- **It cannot** run a model. ONNX Runtime Web exists, but it is a third-party wasm download of several
  MB per model, and `web-app-suite.md` §2 rules out in-browser ML for v1. So:
  - semantic search cannot embed a query;
  - face grouping is a proxy op (`FaceGroups`) over embeddings, with no JS implementation wired;
  - labels are inert natively too (`inference-service.md` §37: empty vocabulary, no backend claims
    tagging).
- Reading stored labels would also need `initInferenceService` on web. That needs its own check that
  it starts no indexer.

**Recommendation: hide all three on web v1.** No People row, no Things row, no semantic search box.
Name search (a `contains` over file names) needs no model, and we could build it if you want it.
Revisit labels when they produce anything natively.

### 42.2 HEIC originals

- **The browser can** draw the rungs. They are WebP (Android) or PNG (elsewhere), minted by the
  uploading device. It can also hand the original to the user as a download.
- **It cannot** decode HEIC in Chrome or Firefox (`<img>`, `createImageBitmap`). Safari 17+ can. A
  wasm decoder (libheif) is a third-party payload, and we fetch nothing third-party.

**Recommendation: show the largest rung, never decode the original.** The viewer takes
`getLargePreview(path, px)`. When it answers `coveringRung: 'absent'`, the viewer may offer "Download
original", which saves the `.heic` file and does not render it. A HEIC uploaded *from a browser* gets
no rung (the mint cannot decode it). It would show as a placeholder until a native device mints one,
which no native path does for a file it did not upload. That is a reason for 42.3's answer to refuse
HEIC on web upload.

### 42.3 Upload from the browser

- **The browser can** upload today. `fs.uploadFile` takes the bytes, `File.lastModified` and the
  capture coordinate parameters (TB-D4), and the stills ladder is minted at upload for decodable
  images.
- **It cannot:**
  - read a reliable capture time. `File.lastModified` is the copy time for most files, and the feed
    orders by `CreatedAt`, which upload seeds from `sourceModifiedAt`. A JS EXIF `DateTimeOriginal`
    read would fix it, but no such parse is wired on web;
  - mint `MOTION` for a video. No encoder is wired; WebCodecs exists but is not used;
  - decode HEIC (42.2).
- There is no "camera roll" folder on web, so the destination folder is a product choice.

**Recommendation: build reduced, in a later run, not run B's first cut.** JPEG/PNG/WebP only, into one
fixed folder you name. Capture time comes from an EXIF parse in the SDK, falling back to
`lastModified` with a visible "date may be the upload date" note. Videos are uploaded without
`MOTION`. Until you decide, Photos on web has no upload entry, and Drive remains the upload surface.

### 42.4 Places in Photos

- **The browser can** carry each row's capture coordinate (`CaptureLatE7`/`CaptureLngE7`). Run A puts
  both on every feed row.
- **It cannot** name a place without `:place-service` reads. The Timeline run owns the place plugin
  this wave, and Photos must not touch it.

**Recommendation: no Places section until the Timeline run exposes place reads.** Then Photos joins on
that surface, not on a second reader of the place relation (the `web-app-suite.md` §4.1 trap).

### 42.5 Anything else

| Feature | Browser reality | Recommendation |
|---|---|---|
| **Mint-on-view** (`ensureViewerRung`, X-4: pull or render the hero rung from the original) | It can download the original and mint with `createImageBitmap` for decodable formats. It cannot know a metered network, and the proxy's quota/metered gate is native. 6–18 MB per photograph. | **Drop.** The viewer uses `getLargePreview`'s tri-state; only an explicit user action fetches an original (Drive's lightbox pattern). |
| **Photographic settings** (`photographicSettings`: the full EXIF parse of the original's bytes) | It can parse bytes it holds, and `readMediaMetadata` is commonMain. It does not hold most originals, and fetching one to fill a panel is the 6–18 MB problem again. | **Build reduced:** run A ships the *stored* fields (capture time, size, pixel size, orientation, camera make/model, duration). A full parse only when the original is already local, or never. Your call. |
| **Duplicates** (PH-6) | A realm walk in bounded replies (`RealmContentReads.duplicateScan`) is plain reads, and the delete is an existing `fs` op. | **Defer.** It is not in Wave 2's MVP row, and its destructive half needs the confirm UI to be right. Ask again with the app. |
| **Hidden flag** (`PhotoFlags.hidden`) | Pure data. | Run A **preserves** it on every favourite write (as native does) and never sets it. No hidden-photos surface until asked. |
| **Video playback** (the original, not `MOTION`) | `ContentStream` and Drive's service-worker range path already play a `<video>` from the realm. | Not a question: run B reuses Drive's path. `MOTION` stays clip bytes only, outside `GetPreview`'s ladder. |

### 42.6 Decided (maintainer, 2026-10-24)

All recommendations accepted:
- **42.1** Faces, labels and semantic search are hidden on web v1.
- **42.2** HEIC: show the largest rung, never decode the original; offer "Download original" when
  the covering rung is absent.
- **42.3** No upload entry in Photos on web v1; Drive stays the upload surface.
- **42.4** No Places section until the Timeline run exposes place reads; Photos then joins on that
  surface, not on a second reader.
- **42.5** Mint-on-view: dropped. Photographic settings: stored fields only. Duplicates: deferred.
  Hidden flag: preserved, never set. Video: reuses Drive's playback path.
- ⚠ **Favourites include videos on web; native's are images only** (`TenshuPhotosRepository.favourites`
  reads `imagesOnly`). Web keeps it — a favourited video should appear — pinned by
  `PhotosLibraryContractTest.aVideoCarriesItsDuration_andAFavouritedVideoIsAFavourite`.

## 43. As built — web Photos run A: the SDK boundary and bridge (web-app-suite Wave 2, 2026-10-23)

The undisputed half of §42 reaches the web through three pieces:
- `Photos` (`tenshu-sdk-js` `Photos.kt` + `PhotosLibrary.kt`);
- `web/sdk/src/photos.ts` (`@tenshu/sdk/photos`);
- 20 `photo.*` gateway methods and 3 subscriptions (`web/apex/src/lib/gateway/photo.ts`).

Run B (§44) is the UI, and its allowlist is the first to name `photo.*` methods. §42's open items (faces, labels, search, places, duplicates,
upload, mint-on-view, full EXIF) are absent, as §42.6 decided.

- **The plugin.** `PhotoPlugin.kt` replaces photo's F7 sync-only entry. It adds the seven
  `:photo-service` ops and `initPhotoService()`, which is one `installRelationMaintainer()`: a
  singleton data observer that runs inside a save and starts no job. It sits before sync (the ops
  never dial the relay), has no mergers (native registers none) and starts no background work: no
  drain, backfill, indexer or mint-on-view. `PhotoPluginTest` holds all four.
- ⚠⚠ **A second reader, on purpose.** `*-shared-logic` must not compile to JS, so
  `PhotosLibrary.kt` re-implements the relations `TenshuPhotosRepository` reads. Its header lists
  the seven rules it copies:
  1. the feed window;
  2. the inclusive cursor with the `MAX_VALUE` sentinel;
  3. ⚠⚠ the order, which **diverges**: web's is total, `(CreatedAt desc, path asc)`, with a
     `(ms, last path)` cursor. Native's counted tie skip repeats or skips a row when one arrives or
     is trashed at the boundary millisecond;
  4. no capture time means dropped;
  5. the trash path;
  6. the album id;
  7. `hidden` passed through.

  Change native and this follows. `PhotosLibraryRulesTest` and `PhotosLibraryContractTest` pin them,
  and mutating rules 3, 7 or the trash exclusion turns a contract test red (checked).
- **Keyset paging for the bridge.** `feedPage(cursor, pageSize)` returns `{ photos, nextCursor }`.
  - The cursor is an opaque `v2.<beforeMs>.<lastPath>` token naming the last row served, decoded
    and checked in Kotlin. It counts nothing, so a row arriving or trashed at the boundary
    millisecond neither repeats nor skips a row.
  - ⚠ The chain has no string `>` predicate, so the rest of the boundary millisecond is read whole
    and cut by path in Kotlin; everything older is one fused top-N walk.
  - `pageSize` must be 1..500 (native's byte-sized `LIBRARY_PAGE_SIZE`), so a page is never the
    library.
  - `nextCursor` comes from the page's ROWS, and is `null` after a short page.
  - A row synced in newer than the cursor belongs to the head (refetch it); an older one lands in
    its page.
  - `captureTimes()` is the scrubber's whole-feed map, one number per photograph, read over the
    same window.
- **Bytes stay Drive's.** Tiles use `fs.getPreview`, the viewer uses `fs.getLargePreview` (with its
  `coveringRung`), and a video's original uses `fs.*ContentStream`. Run B's allowlist names them.
  Nothing on web projects `File.Preview`. `motionClip` is the one byte read, because `MOTION` is
  outside `GetPreview`'s ladder.
- **Errors and not-found.**
  - Every call is `{ ok: true, … }` or `{ ok: false, error: { kind } }`, and a failed read is never
    an empty list.
  - A read of one thing by path (`photo`, `details`, `motionClip`) answers `ok` with `null`. This
    covers a missing folder too: the `NotFoundError` from `navigateTo` is read as absence.
  - An op whose target does not exist fails with a typed key: `error_photos_unknown_album`,
    `_not_found`, `_not_a_photo`, `_not_in_trash`, `_no_content_id`, `_bad_cursor` or
    `_bad_page_size`.
  - ⚠⚠ `trash` and `addToAlbum` refuse a non-media path or one under `/Trash` (`_not_a_photo`):
    re-trashing overwrote the record's `originalPath`. `photo(path)` answers `null` for a non-media file.
  - ⚠⚠ `deleteForever` clears the trash record only when the delete succeeded or found nothing; any
    other failure keeps it, or the file is stranded out of the feed and the trash both.
  - ⚠ Unlike native, `setFavourite` fails when the flags read fails, rather than writing
    `hidden = false`.
- **Reactive.** `observeAlbums`, `observeFavourites` and `observeTrash` push over the
  photo-service relations' Flows and recompute the snapshot on each emission.
  - `fail` is terminal (`Contacts.observeJob`).
  - ⚠⚠ Each push form projects EVERY field its snapshot reads. `StreamRegistry` compares projected
    rows, so an identity-only projection never pushed a rename
    (`observeAlbumsPushesARename…` is red without it).
  - The feed is snapshot + refetch: its only Flow is a whole-realm stream.
  - ⚠ A file moved or deleted in Drive changes no photo-service row, so the push forms do not see
    it. Refetch after file writes.
- ⚠⚠ **Test trap found here.** On Kotlin/JS, `assertTrue(suspendCall() != null)` with a `dynamic`
  result never runs the call and dies as a `CoroutinesInternalError` with no cause. Bind the result
  to a `val` first. The note sits in `PhotosLibraryContractTest`.

## 44. As built — web Photos run B: the app (web-app-suite Wave 2, 2026-10-24)

`web/photos` on the app-kit, registered by `web/apex/src/apps/registry/apps/photos.ts`. It builds
§42.6's list and nothing it ruled out: no faces, labels, search, upload, places, mint-on-view,
duplicates or hidden surface (`App.test.tsx` pins the absence).

- **The allowlist** is 22 `photo.*` names, `sync.health.observe`, the session methods and five
  of Drive's `fs.*` methods by name: `fs.getPreview`, `fs.getLargePreview`, `fs.openContentStream`,
  `fs.readContentRange` and `fs.closeContentStream`. ⚠ Never `fs.*`, and never `fs.getData`:
  "Download original" reads over the streaming ops, so the origin holds no whole-file read and
  no file write. `photo.get` is not granted because nothing calls it.
- **The feed** (`lib/feedPager.ts`, `lib/layout.ts`, `components/PhotoGrid.tsx`).
  - The capture-time index is the grid's skeleton: its length sizes the scroll height, and its
    months place every header before any page past the first loads.
  - The rows come from the keyset pager. It sends exactly the `nextCursor` it was given, holds
    one request at a time, and never shows a path twice. A cursor that does not move counts as the
    end.
  - ⚠⚠ Keyset paging only walks forward. A scrubber seek deep into the library walks the pages
    in between: metadata only, at 250 rows a page, but O(depth). A seek-by-time cursor would
    remove that walk; it is listed under "Needs a decision" on the PR.
  - The scrubber is native's `FeedYearIndex.kt`, ported: pills placed by count, thinned greedily
    from the top.
- **⚠⚠ The memory bound** (`lib/thumbnailCache.ts`). A tile holds its object URL only while
  an `IntersectionObserver` reports it on screen. Off screen, it releases the URL and drops its
  `src`. Released URLs wait in an LRU of 150 and are revoked past that. A fetch still queued when
  its tile leaves the screen is never sent. `null` is not remembered, because a rung can still
  arrive by sync. `thumbnailCache.test.ts` and `FeedView.test.tsx` scroll 10 000 photographs
  end to end and pin live URLs at no more than the visible tiles plus 150.
- **The viewer.** It asks `getLargePreview` for native's `heroPreviewRequestPx`, ported, and draws
  whatever rung answered.
  - ⚠⚠ The original is never fetched to be drawn. "Download original" appears only when
    `coveringRung === 'absent'`, never on `'unknown'`.
  - The download streams to disk where the File System Access API exists and lets this frame
    use it. Elsewhere it collects the windows into one Blob: that is the only path that holds a
    whole file, and it logs that it did. ⚠⚠ Inside the apex the app is a cross-origin iframe, where
    the spec forbids file pickers (`SecurityError`), so the hosted flow takes the Blob path even in
    Chromium.
  - ⚠ The list can shrink under the open viewer (unfavourite the last favourite). The index
    follows it in, and an emptied list closes the viewer.
  - Videos loop their `MOTION` clip, then play through a copy of Drive's stream Service Worker. A
    Service Worker serves its own origin only, so `public/tenshu-stream-sw.js`,
    `lib/streamProtocol.ts` and `lib/videoStream.ts` are copies. ⚠ Change Drive's and you change
    these.
  - Clip reads are serialised, one in flight, and a read superseded while it waits is never sent.
- **Snapshot + refetch.** The feed refetches its index and restarts its pager after this app's
  own trash and restore, and from the Refresh button. Albums, favourites and the trash are pushed.
  An open album refetches its photographs when the pushed count moves.

