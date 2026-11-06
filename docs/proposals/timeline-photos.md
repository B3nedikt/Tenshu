# Timeline photos — the pictures you took, sourced from Drive

**Status:** v0.5 — sweep + design (2026-08-09); **T-P1–T-P4 SHIPPED — as built in §8–§11. The phase
map is complete.** Two follow-ups are named and deferred: tap-through into Drive's viewer (§10.5)
and reverse-geocoding a photo's coordinate into a `Place` (§11.6).
What is still needed for Timeline to show the photos the user took during a visit, a movement or a
trip. Companion to
[`timeline-services.md`](timeline-services.md) §"Photos are Drive files, not a new service" (which
reserves this slice in three sentences and is the doc this one expands),
[`drive-synced-folders.md`](drive-synced-folders.md) (how the photos get into the realm at all),
[`place-imagery.md`](place-imagery.md) (the *other* half of the same hero — a picture of the place
when the user took none) and [`inference-service.md`](inference-service.md) §3.1 (EXIF GPS, which
upgrades the association from time-only to time ∧ place).

**Trigger (2026-08-09):** photos are named in the whitepaper's own framing of the product
(§"Location History" — *"this is not really useful if the user is also interested in related data
like the pictures they made during their trips"*) and in [`milestones.md`](../milestones.md)
§Timeline (*"Photo / event linking through `:file-service` so timeline entries can reference media
from Photos without duplicating it"*). Timeline shipped without it: every `photos` list in the
projection was a hardcoded `emptyList()` and every `photosCount` a hardcoded `0`. T-P1 has since put
one real photo on the moment detail; the feed is still empty until T-P3.

---

## 0. The sweep — what already exists, and one fact that changes the plan

The gap is smaller than the milestone entry suggests, and the reason is worth stating first,
because it removes the dependency everyone assumes.

### 0.1 Already built

- **The whole UI.** `PhotoCluster` (the ~78 % peek carousel), the detail hero, the
  `PHOTOS · n` section, day/trip `photosCount` lines, and — load-bearing — `personalise()`, which
  tints the app's chrome from `Photo.dominantColor`. The presentation model
  (`timeline-shared-logic/.../TimelineModel.kt:23`) is `Photo(dominantColor, uri?, label?)`: the
  `uri` slot exists and nothing fills it.
- **Photos actually reach the realm.** Synced Folders backs the camera roll up into
  `/Backup/<device>/<bucket>` — verified on device 2026-08-06 (785 files) after the four defects in
  `drive-synced-folders.md` §6.5b were fixed. Before that date this proposal would have been moot:
  there were no photos in the realm to link to.
- **Timeline is already a Control client**, and Control already hosts `:file-service`. No new
  service, no proxy-side app, no new IPC surface.
- **The byte path exists, with a working recipe to copy.**
  `TenshuFilesRepository.getPreview` / `cacheThumbnails`
  (`drive-shared-logic/.../TenshuFilesRepository.kt:902-950`) is the two-step that
  `SyncStrategy.OnDemand` requires: read `eager(File.Preview(w, h))`, and on a `null` pull the
  content-plane thumbnail blob and re-ask.

### 0.2 ⭐ The fact that changes the plan: capture time is already correct

`milestones.md` §Photos scopes an EXIF-parsing media-metadata extension inside `:file-service`, and
`tech-debt.md` records the settled decision that `File.CreatedAt` is seeded from the source
**mtime, not EXIF**. Read together they suggest Timeline must wait for the Photos app.

It does not — **for the time axis**. On Android, an uploaded gallery item's `File.CreatedAt` is
seeded client-side from `ContentProviderUtil.getSourceDateMillis`
(`core/src/androidMain/.../ContentProviderUtil.kt:36-61`), whose **first** choice is
`MediaStore.MediaColumns.DATE_TAKEN` — the capture time — falling back to
`COLUMN_LAST_MODIFIED` and then `DATE_MODIFIED`. The backup drain rides exactly that path
(`AndroidFilesRepositoryPlatformDelegate.kt:75,104` → `UploadFileImplementation`).

So **every photo the Android backup has uploaded already carries its capture time in a synced,
queryable field.** A time-window join between a `Moment` and the user's photos is possible today,
with no schema change, no EXIF parser, and no Photos app. What is missing is not the *data* — it is
the *query*, and everything downstream of it.

### 0.3 What is genuinely missing

| # | Gap | Size |
|---|---|---|
| 1 | Timeline cannot see files at all (no dependency, no DI, no declared permission) | small |
| 2 | ⚠⚠ No way to ask "which photos fall in this window" — no range predicate, and the sort is un-fused | **the real work** |
| 3 | Nothing identifies *a photo you took* (vs a screenshot, a saved meme, a photographed receipt) | small–medium |
| 4 | No geo on photos — association is time-only and will be confidently wrong sometimes | medium (deferrable) |
| 5 | `dominantColor` is consumed in ten files and produced nowhere but the mock | small |
| 6 | `PhotoFrame` physically cannot render an image — it is a gradient box | small |
| 7 | The projection hardcodes `emptyList()` / `0` | mechanical |
| 8 | A decision: derived-at-read vs stored on `Moment` (§4) | decision, not work |

---

## 1. Gap 1 — Timeline cannot see files

`timeline-shared-data/build.gradle.kts` declares `:timeline-service`, `:place-service`,
`:health-service`, `:map-region-service`, `:preference-service` and `:sync-service` clients — no
`:file-service`. `TimelineClientModule.kt:53-65` registers the matching module pairs, likewise
without file. Adding them is the entire fix, plus two things that are easy to miss:

- ⚠ **The announce vector moves.** The client's declared version vector is walked from the
  registered module graph, so adding `:file-service` changes what Timeline announces at session
  start. Control already carries the service, so this is an ordinary lockstep matter — but it is
  exactly the shape that surfaces as *"Can't reach Control"* if an app ships ahead of Control
  (`core-schema-evolution.md` §6.8).
- ⚠ **Wire registration is not covered by an in-process test.** `File.Preview` /
  `File.Thumbnail` are dynamic and on-demand properties, and their request objects resolve through
  the **client** module's `roFactory` registration. An in-process harness registers everything and
  proves nothing; a missing registration surfaces on device as an opaque `error/internal` on a
  read that "obviously works". The iOS EPC clients already register `fileServiceCommonModule` +
  `fileServiceClientModule` for Timeline (`EpcTimelineClient.kt`), which is a useful precedent and
  a useful warning: the KMP client path does not.

**The permission surface is the interesting part.** `BundledAppManifestSource.TIMELINE_MANIFEST`
(`:control-shared-logic`, from line 226) declares seven write operations and no read. Reading
previews needs `tenshu/file/getPreview`, and opening a photo full-size needs `tenshu/file/getData`
— both `Ambient`, matching Drive's own entries.

⚠⚠ But the manifest **cannot express what actually happens here**, and the file's own KDoc already
says so about Fit: an `operationId`-keyed permission list has nothing to name for reads that ride
*relations* rather than operations. Timeline traversing the user's whole file tree is a materially
larger capability than "generate file previews", and today it would be granted silently by a
manifest line that understates it. This is the first time a first-party app reads another app's
entire domain, so it is worth recording as a permission-model gap rather than papering over it with
a flattering label. It is a `control-app-registration.md` question, not a Timeline one — noted here
so the omission is not mistaken for "Timeline reads nothing".

No threat-model amendment is owed: nothing crosses the relay differently, no A-asset changes, and
the photos are already in the same realm under the same keys. House Rule #2 does not fire.

---

## 2. Gap 2 — the query, which is where the work is

### 2.1 Why the obvious query does not exist

The chain DSL has exactly two predicates that return a `TBoolean`: `equalTo` (`core/.../EqualTo.kt`)
and `contains` (`core/.../Contains.kt`). **There is no comparison or range operator**, on any type.
"Files whose `CreatedAt` falls between `t0` and `t1`" is not expressible.

The fallback shape — `traverse().sortByDescending(File.CreatedAt).take(n)` — is the one Drive Home
already uses, and it is explicitly **not** covered by ordered-window fusion:

> Not covered (by design): Drive Home recents (`traverse().sortByDescending(...)`) — `traverse`
> defers onto the stack ahead of the sort, gate 2 falls through. Same for any filtered sort.
> — [`core-ordered-window-fusion.md`](core-ordered-window-fusion.md) §4

So it is a full-tree walk with full row materialisation. Drive pays that once, for one screen, for a
fixed small `n`. Timeline's feed is a scrolling list of days, each of which wants its own window —
the same cost, per day, per scroll. That is not a slow query; it is a query that gets worse the
longer the user has used the product, on the screen they open first.

### 2.2 Three ways out

**(a) A client-side index in Timeline.** One traverse projecting `(PATH, CreatedAt, Category)`,
bucketed by day in memory, refreshed on a trigger. Cheapest to build, and honest for a first slice.
It re-walks the whole tree to learn about one new photo, holds the index in the app's heap, and is
stale the moment the backup uploads — the same class of problem `bounded-store-cache.md` was opened
to fix elsewhere.

**(b) Add a range predicate to the DSL.** `greaterThan` / `lessThan` (or a `between`) on
`TInteger` / `UnixInstant`, registered as ordinary chain operations, plus getting the fusion gate to
fire on a filtered sort. This is the *general* fix and would pay off well beyond Timeline (Control's
event log, Drive's date filters, Fit's history). It is also wire-level work on the hottest shared
path in the platform, which is a different risk profile from a feature slice.

**(c) A day-bucketed image index in `:file-service`.** A maintained relation of image files carrying
`(fileId, path, capturedAt)`, `Sliceable(SliceKey.Day)` — the exact shape `Moment` already uses, and
for the same reason: a chronological feed whose first screen is "today". A day's photos become one
keyed read instead of a tree walk. This is `milestones.md`'s "media metadata indexing" arriving
narrowly, driven by a real consumer instead of speculatively.

**Recommendation: (c), scoped hard.** Not the EXIF/media-metadata milestone — a day-bucketed index
of image files and their capture times, nothing more. (a) is acceptable *inside* T-P1 to prove the
end-to-end path before committing to an index shape, and should be deleted by T-P2, not left as the
implementation. (b) is worth doing on its own merits and would let (c) be even thinner, but it must
not be smuggled in as part of a Timeline feature.

⚠ Whichever lands, the index is a **derived projection over files** and must not be broadcast as
new synced rows per device. The precedent is `MomentReconciliationPass` writing its verdict as
`USER` (`core-deterministic-invalidation.md` §1): two devices re-broadcast a derived projection at
each other, ~99.8 % of realm traffic, and it was a `Moment` projection.

---

## 3. Gaps 3–7 — the rest, briefly

### 3.1 Which files are photos you took

`File.Category` is derived from the filename (`fileCategoryForName`) and is a **value property**, not
reachable inside a `filter` predicate — so today the category test happens client-side, after every
row has crossed IPC. Worse, `Image` is true of a screenshot, a saved meme and a photographed
receipt, and a timeline full of screenshots reads as broken in exactly the way a recency feed
showing a duplicate does (`milestones.md` §Photos makes the same argument about photo identity).

Two provenance signals already exist and cost nothing: `IngestSource.KIND_GALLERY`, and the backup
destination path `/Backup/<device>/<bucket>` — a bucket the user explicitly selected as worth
backing up, which is a better proxy for "photos I took" than any pixel-level test. Prefer them over
a category filter; fall back to category only for files that arrived by other routes.

### 3.2 No geo

EXIF GPS is deliberately unparsed today (`tech-debt.md`, "source *mtime*, not EXIF"). Time-only
association therefore attaches whatever was captured during the window, wherever it happened — the
screenshot taken at the café is indistinguishable from the photo of the café.

`inference-service.md` §3.1 already scopes the fix and calls it correctly: *"Location on images is
nearly free and needs no model. EXIF GPS → reverse geocode → `Place`"* — landing on the **same**
`Place` spine Timeline already uses, which means it pays off in Drive and Timeline at once. Not
required for v1; decisive for quality, and the reason T-P4 exists rather than being "later".

### 3.3 Dominant colour is consumed everywhere and produced nowhere

`dominantColor` appears in ten files and is minted only by `TimelineMockData`. Every card tint and
the whole personalisation system read it. It needs a small pure function over decoded thumbnail
bytes, plus a decision on **where** it is computed: at index time (once, cached, costs a field) or
on read (free to store, repeated per view). Index time, given the thumbnail is already being
decoded there — but it belongs to whichever component owns the index, so it follows §2.2's outcome.

### 3.4 The photo composable cannot render an image

`TtPhoto.kt` (since T-P1, `PhotoFrame.kt`) synthesises a gradient; its own KDoc says "Real images replace this later". A real
`decodeImage` expect/actual exists in `drive-shared-ui/.../ImageDecode.kt` and, duplicated, in
`contacts-shared-ui`. Timeline is consumer #3 ⇒ promote it to `:ui-core` rather than fork it a third
time.

### 3.5 The projection

`TimelineProjection.kt` hardcodes `photos = emptyList()` (≈ lines 230, 756, 801, 993),
`photosCount = 0` (≈ 834, 897, 993) and a placeholder `DEFAULT_COLOR` cover; the same holds at
`TimelineProjectionRepository.kt:736`. `MomentInput` carries no photo fields. All mechanical once
§1 and §2 exist — and pleasantly so, because the projection is pure and unit-tested over
lightweight inputs.

---

## 4. The decision: derived at read, not stored on `Moment`

`Moment`'s KDoc reserves `photos + cover` as `:file-service` references, deferred a slice. Two
shapes are available, and the choice is not obvious:

**Stored on `Moment`.** A `TList<FileRef>` plus a cover. Costs: a `timeline-service` version bump
and a `SchemaMigration` in the same PR (House Rule #9 — `Moment` is a positional composite, and its
schema already carries the "⚠ appended at the end … ship lockstep" warning from the last additive
field); a per-element `DataIdProvider` decision for the list; and — the real objection — it
**broadcasts a derived join to every device in the realm**. Both halves of that join (the moment,
the file) are already synced. `MomentReconciliationPass` made precisely this mistake once already
(`core-deterministic-invalidation.md` §1).

**Derived at read.** The projector joins moments to photos by time window at projection time. No
wire change, no version bump, no migration, nothing new on the relay. It also **self-heals**: the
backup typically uploads hours after the visit was recorded, and a derived join simply starts
returning photos when they arrive, where a stored list would have been written empty and would need
a re-scan to fix.

**D1: derived at read for v1.** The join is a pure function of data both devices already hold; it
does not deserve to be a synced fact.

**D2: store only *manual* curation, later.** A user pinning a cover, or removing a photo the
heuristic got wrong, is real user intent that cannot be recomputed — that earns a stored field, and
earns the bump it costs. A time-window join does not.

**D3: no new photo service, ever** — restating `timeline-services.md` §"Photos are Drive files".
Photos are Drive files; the content plane already delivers their bytes.

---

## 5. Two traps worth knowing before writing code

⚠⚠ **Thumbnails are `OnDemand` since C7.** A day-scroll that projects `File.Thumbnail` for forty
photos triggers forty content-plane blob fetches. `File.Thumbnail`'s own KDoc is explicit that
fetching one is neither automatic nor free, and that `SyncStrategy.OnDemand` is worth reading before
adding a consumer — this is that consumer. Timeline needs Drive's two-step discipline
(`getPreview` → on `null`, cache, re-ask) and a bounded, viewport-driven fetch from the first
commit, or the feed stalls on the first scroll of a real store. Those blobs then also count against
the local cache ceiling (`retention-quota-model.md` tier 3), which is fine — they are re-fetchable,
never data loss — but Timeline becomes a new consumer of that budget and should be sized as one.

⚠⚠ **iOS is a different story and should not gate Android.** There is no iOS gallery backup source
(the Synced-Folders reframe, `ios-drive-parity.md` §15 P6b, is unbuilt), and iOS Drive upload does
not pass `sourceModifiedAt` yet (`tech-debt.md`, "Outstanding — iOS"). So on an iPhone the realm's
photos are both **sparse** and, where they exist, **mis-dated to the import time** — the exact
failure §0.2 says Android avoids. Timeline-on-iOS will show photos that the *Android* device backed
up (the metadata syncs, the blobs fetch on demand), and little else, until both are fixed. Ship
Android-first and say so, rather than shipping a feature that looks broken on one platform.

---

## 6. Phasing

Each slice is independently shippable and independently useful.

**T-P1 — one real photo on screen. SHIPPED 2026-08-09 — as built in §8.** `:file-service`
dependency + DI registration + manifest permissions (§1); Timeline's own `getPreview`-shaped read;
`decodeImage` promoted to `:ui-core`; `PhotoFrame` renders bytes when it has them and falls back to
today's gradient when it does not; the moment-detail **hero only**, over the naive traverse of
§2.2(a). Proves the whole path end to end — permission, IPC, on-demand blob fetch, decode, render —
on one surface, before committing to an index shape.

**T-P2 — the index. SHIPPED 2026-08-09 — as built in §9, and it is *not* §2.2(c).** The scan moved
into the proxy behind a `photosCapturedBetween` op, with gallery provenance as a ranking signal and
dominant colour averaged UI-side; the §2.2(a) client-side walk is deleted. §9.1 records why the
persisted index was not built and what would justify building it later.

**T-P3 — the feed. SHIPPED 2026-08-10 — as built in §10.** `photos` and `photosCount` populated
across moment, trip and place; `PhotoCluster` live over a viewport-driven loader; real covers driving
`personalise()`. The `TimelineProjection` empties (§3.5) are closed. ⚠ **Tap-through into Drive's
viewer is not built** — there is no deep link to tap through to; §10.5 has the two options.

**T-P4 — geo. SHIPPED 2026-08-10 — as built in §11.** EXIF GPS → the join upgraded from time-only to
time ∧ place. ⚠⚠ The premise "nearly free" held for the parsing and not for the *getting*: Android
redacts GPS from every image `MediaStore` hands out, so the slice turned on one guarded
`setRequireOriginal` in the client's process. ⚠ The "→ reverse geocode → `Place`" half is
deliberately **not** built (§11.6) — it is Drive's payoff, and it lands on place identity and the
geocoding track's consent model rather than on Timeline.

**Not in scope:** face clustering, semantic search, OCR (all `inference-service.md`); a Photos app;
photo *editing* or *curation* beyond D2's manual pin; `place-imagery.md`'s third-party place photos
(the complementary case — a picture of the place when the user took none).

---

## 7. Open questions

- **Q1 — window semantics.** A visit has a start and an end; a movement has a path and a duration;
  a day has neither. Is a photo assigned to the moment whose window contains it (and what happens in
  the gaps between moments — a photo taken while walking to the car), or to the nearest moment
  within a tolerance? The gaps are not rare: capture is dwell-and-move, and the seams between them
  are real minutes.
- **Q2 — how many.** A day at the beach is 200 photos; the cluster shows a handful. Does the index
  cap per moment, and on what — recency, spread across the window, or the first one (which is
  usually the establishing shot, and is what a person would pick)?
- **Q3 — the trigger.** What re-runs the join when the backup uploads a photo for a moment that was
  already projected? The `storageStatus` / observe-changes triggers that Synced Folders uses are the
  obvious candidates, and §6.5b of that doc is a cautionary tale about triggers that look present
  and are not.
- **Q4 — videos.** The backup already includes them behind `includeVideo`, `File.Category` knows
  them, and Drive can produce poster frames (`drive-content-preview.md` Ph3). A video in the cluster
  is nearly free, but the poster is another OnDemand blob class. Fold into T-P3 or defer.

---

## 8. As built — T-P1 (2026-08-09)

One photo, on the moment detail, end to end: Drive's file tree → a time-window join → the
`OnDemand` preview blob → a decode → the hero band. Everything below is either a deviation from
§6's sketch or a decision the sketch left open.

### 8.1 The decoder moved, but only half of it

`decodeToImageBitmap` / `decodePreviewBitmap` now live in **`:ui-core`** (`app.tenshu.ui.image`),
with the Android downsampling pass and the desktop Skia→ImageIO fallback carried over unchanged and
a new **iOS actual** (Skia, no ImageIO bridge — an iOS app cannot spawn a `swiftc` helper). Drive's
call sites moved to the new import; there is exactly one implementation now.

⚠ **`isImagePreviewSupported` stayed in Drive, deliberately.** It is a *routing* rule — which
screen a file opens on — not a decode, and its answer is about Drive's viewer. ⚠ Two forks remain
untouched: `:contacts-shared-ui`'s avatar decoder and Control's Home-screen one. Folding them in
was not this slice's job, but they are now the only duplicates left.

### 8.2 The window is a decision, and it is in the repository (§7 Q1, partially answered)

`PhotoSelection.window(startMs, durationSec, minWindowMs)` — the moment's own start, running for its
duration, floored at **15 minutes**. Half-open, so two adjacent moments cannot both claim a photo
taken exactly on their boundary. The floor is what makes an Event (duration `0`) work at all.

Fifteen minutes rather than an hour because the cost of generosity is asymmetric: a photo attached
to the wrong moment is far more visible than one that is missing. The gaps *between* moments (the
walk to the car) are still nobody's — Q1 stays open for the feed, where it will matter more.

⚠ The selection is a **pure function over lightweight candidates** (`PhotoSelection`), not entity
code, for the same reason `TimelineProjection` is: the decisions worth testing are about time and
category, and they should not need a proxy standing up behind them. Ten tests pin them, including
the two that would be silent bugs — a file with **no** `CreatedAt` is dropped rather than read as
the epoch, and video is excluded rather than half-supported (§7 Q4).

### 8.3 The hero appears only when there is really an image

`MomentDetailUiState.Loaded` gained `coverPhoto` + `coverPhotoBytes`, and they are set **together
or not at all**. No photo, a preview this device has not fetched (`File.Thumbnail` is `OnDemand`),
and an unreachable Control are one outcome: the screen renders exactly as it did before. A tinted
band over a photo that failed to load reads as *broken*, where no band reads as *absent* — and
absent is the truth for almost every moment today.

⚠ **A movement with a drawable route never pays for the photo scan.** Its hero is the map, so a
photo would be read and discarded — and the read behind it walks the whole file tree. `load()` now
reads as "the route hero, or else the photo hero", which is also the honest description of the
screen. A movement whose trace is missing or too short falls back to the photo hero like any other
kind.

### 8.4 What the permission list still cannot say

`TIMELINE_MANIFEST` declares `tenshu/file/getPreview`. That is the truth about *rendering* a photo
and a serious understatement of the rest: **finding** one walks the user's whole file tree over the
`files` relation, and relations are not operations, so there is nothing to name. The KDoc now says
so beside the entry, next to the same admission `FIT_MANIFEST` already carries about Fit's health
reads. This is the first first-party app to read another's entire domain; it is a permission-model
gap (`control-app-registration.md`), not a Timeline one.

### 8.5 ⚠ What is *not* proven, and by what

Unit-green here means less than usual, because the interesting failure is structural:

- **The chain read has no integration test.** `traverse()` + `fields(PATH)` + `relations(CreatedAt)`
  and the `eager(File.Preview(w, h))` one-shot are exercised only on a device. Standing up
  `:file-service-proxy` inside Timeline's test graph would prove the query; it would *not* prove the
  registration, because an in-process harness registers every module and can never see a client that
  forgot one (a missing client `roFactory` — `error/internal` on a select that projects a dynamic property).
- **The smoke gate deliberately does not build Timeline.** Its header states the asymmetry as
  policy: an app the gate reinstalls is current by construction, so the apps it leaves alone are the
  only ones whose lockstep and announce assertions carry information. Adding `:timeline-android` to
  the built list would have *removed* the signal — install the app yourself, then run the gate, and
  it is checked as an `EXTRA_PKG`. (That is how this slice was verified.)
- **Nobody has looked at it.** A hero is a visual thing; the tests pin state transitions and
  selection rules, not that a photograph fills a 220.dp band the right way round.

### 8.6 One rename

`TtPhoto` → **`PhotoFrame`**, in Timeline and in Maps' independent copy of it. `Tt` (presumably
*Tenshu Timeline*) was the only prefix of its kind in the repository — the documented convention is
`App*` for shared `:ui-core` composables and no prefix at all inside a feature module, which is what
every one of its Ph4 siblings (`PhotoCluster`, `NamedMoment`, `ChapterHeader`) already did. It
survived because Maps copied the component and the name travelled with it.

⚠ Not `Photo`: the parameter is a `Photo`, and `Photo(photo: Photo)` compiles but reads badly at
every call site — which is the likeliest reason the prefix existed in the first place.

### 8.7 File map

| File | Change |
|---|---|
| `ui-core/.../ui/image/ImageDecode.kt` (+ android/desktop/ios actuals) | new — the one decoder |
| `drive-shared-ui/.../common/ImageDecode.kt` (+ actuals) | keeps `isImagePreviewSupported` only; call sites repointed |
| `timeline-shared-logic/.../data/TimelineRepository.kt` | `photosForMoment` + `photoBytes`, both defaulted |
| `timeline-shared-data/.../projection/PhotoSelection.kt` | new — the pure window + selection rules |
| `timeline-shared-data/.../TimelineProjectionRepository.kt` | the traverse join, the two-step preview read, a third private `navigateTo` |
| `timeline-shared-ui/.../di/TimelineClientModule.kt` | registers `fileServiceCommonModule` + `fileServiceClientModule` |
| `timeline-shared-logic/.../moment/MomentDetailViewModel.kt` | `loadRouteHero` / `loadCoverPhoto` split; cover state |
| `timeline-shared-ui/.../components/PhotoFrame.kt` (was `TtPhoto.kt`), `DetailScaffold.kt`, `MomentDetailScreen.kt` | render the bytes |
| `control-shared-logic/.../BundledAppManifestSource.kt` | Timeline's `getPreview` permission + the read-gap note |

No wire, schema, format or relay movement — the join is derived at read (D1), so **HR#9 does not
fire** and no threat-model amendment is owed.

---

## 9. As built — T-P2 (2026-08-09)

The photo query stopped shipping the user's whole file tree across IPC to throw almost all of it
away. What landed is **not** §2.2(c)'s persisted day-bucketed index, and the reasons are worth
reading before anyone builds one.

### 9.1 ⚠⚠ The index shape in §2.2(c) did not fit the machinery it assumed

`RelationMaintainer` (the generic, declaration-driven maintainer that replaced ~30 hand-written
ones) derives a relation's array key **from the element's own key**. That is what lets
`Folder.FILES` and `File.FileHistory` work on the **delete** path, where only the `DataId` survives
and the row is already gone. A capture-date bucket is not derivable from a file's path, so a
day-keyed relation could not have ridden that machinery at all: it would have needed its own
element type keyed `day|path`, its own producer, a one-shot backfill for the photos already in the
realm, and move/delete consistency — a permanent maintained surface inside `:file-service`, for one
app's feature.

**What shipped instead:** `PhotosCapturedBetween`, an op on `FileSystem` that walks the proxy's own
store and returns only the matches. It is still O(files) per call — but a caller asks **once for the
span it is about to render**, not once per row, so a feed load is one walk instead of one per
moment. No stored rows, no backfill, no invalidation, nothing to drift.

⚠ **The memoised index behind that signature was deliberately not built.** It needs per-proxy state,
and the only clean places to hang it (a `TComponent` registration, or the platform factory
functions) mean threading it through five DI graphs — disproportionate when the thing it would save
is a repeat in-process scan, and invisible to callers if it is ever worth adding. The op signature
is the seam; the implementation behind it can gain a cache without any consumer knowing.

### 9.2 Provenance ranks, it never filters

`CapturedPhoto.backedUp` is true for a file under `BACKUP_ROOT` — i.e. it reached the realm because
the user pointed a folder backup at the album it came from. That is a **user signal**, not a guess
about pixels or filenames, which is why it beats every heuristic considered: somebody chose that
bucket.

⚠ It **orders** results (backed-up first, then newest) and never excludes any, so a moment whose
only image is a screenshot still shows the screenshot rather than nothing. The ordering is applied
**before** the cap, or the cap would discard exactly the answer the ranking just found — the
`limit = 1` case is test-pinned for that reason.

⚠ `BACKUP_ROOT` now lives in `:file-service` and Drive's `backupDestinationFolder` reads it instead
of its own literal. Two components must agree on that path; a convention neither owns is the join
that degrades silently when one side changes it.

### 9.3 The tint is computed where the pixels already are

`dominantColor` was consumed in ten places and produced only by the mock. The doc expected the index
to compute it "since it already decodes a thumbnail" — but **proxy-side the bytes are frequently not
local** (`File.Thumbnail` is `OnDemand`), while by the time anything is *drawn* they are, by
construction. So `rememberPhotoImage(bytes)` decodes once and derives the tint from the same
bitmap, and `personalise()` finally shifts the chrome toward the photo on screen.

⚠ It is a **mean, not a "dominant" colour** in the clustering sense: a red car on grey tarmac
averages to a muddy mauve where k-means would return the red. That is the right trade here — the
accent shifts *toward* it at low strength, so a calm, roughly-right hue beats a saturated one
fighting the design system. Sampled on a 32×32 grid; alpha-weighted, or a transparent PNG border
would drag every thumbnail's tint toward black.

### 9.4 No version bump, and why not

`SchemaShapeBaseline` reports exactly **two new types and zero changed lines** — `CapturedPhoto` and
the op schema. Nothing that is stored or synced reshaped, so HR#9's bump is not owed (the
`MovementTrace` precedent). `CapturedPhoto` carries no `DataIdProvider` and no `Syncable` trait, so
nothing can persist one by accident.

⚠ The file service's declared version is deliberately **not** bumped for a new *operation* either. A
service version fences peers' pulls; an op that no peer's data depends on has no business doing
that — the same reasoning that kept `SyncService` at v2 when `SchemaFenceStatus` landed. Op-level
skew is E3's job (op floors, unbuilt); today it is covered by lockstep and the smoke gate's announce
check.

### 9.5 What is proven now that was not

T-P1 shipped with the chain read unproven; **`PhotosCapturedBetweenOperationTest` closes that** —
eight cases driven end to end over the op wire (RO → param map → proxy scan → materialised
composites) against a real store: window bounds, half-openness, non-images, nested folders, the
ranking under `limit = 1`, the screenshot-only fallback, and both degenerate inputs.

⚠ Still not proven, and still only provable on a device: that Timeline's **client** module
registrations are complete. An in-process harness registers everything, so it can never catch a
client that forgot one.

### 9.6 File map

| File | Change |
|---|---|
| `file-service/.../CapturedPhoto.kt` | new — the return shape + `BACKUP_ROOT` |
| `file-service/.../system/PhotosCapturedBetween.kt` | new — the op |
| `file-service-common/.../CapturedPhotoDto.kt` + module | wire registration |
| `file-service-client/.../PhotosCapturedBetweenRo.kt` + module | client Ro |
| `file-service-proxy/.../PhotosCapturedBetweenImplementation.kt` | the scan, the window test, the ranking |
| `drive-shared-logic/.../SyncedFolderController.kt` | `BACKUP_ROOT` instead of a literal |
| `timeline-shared-data/.../TimelineProjectionRepository.kt` | the traverse deleted; calls the op |
| `timeline-shared-data/.../PhotoSelection.kt` | shrinks to the window — selection moved proxy-side |
| `timeline-shared-ui/.../PhotoImage.kt` | new — decode-once + `averageColorHex` |
| `timeline-shared-ui/.../PhotoFrame.kt`, `DetailScaffold.kt`, `MomentDetailScreen.kt` | take a decoded bitmap; real tint |
| `control-shared-logic/.../BundledAppManifestSource.kt` | the op declared; the §1 read-gap note narrowed |
| `system-test-utils/.../schema-shape-baseline.txt` | two additions, no changed line |

---

## 10. As built — T-P3 (2026-08-10)

The feed carries photos now: a row shows the ones taken while it was happening, a trip counts its
own, and the tint follows the picture. One thing on the phase list is deliberately **not** here —
§10.5.

### 10.1 The assignment moved into the projection, and that is the point

T-P1 and T-P2 answered "which photos belong to this moment" in the *repository*, for one moment at
a time. T-P3 answers it in `TimelineProjection.days(...)`, once, for every moment being rendered —
so **the feed row and the detail it opens read the same assignment** rather than two computations
that agree by luck. `moment(id)` already projected the whole feed to answer for one row (that is how
merged spans and role vetoes stay consistent); photos now ride that same guarantee.

⚠ Consequently `TimelineRepository.photosForMoment` is **gone**. It was a second answer to a
question that now has one, and leaving it would have been the drift it was introduced to avoid. The
seam keeps `photoBytes`, which asks a different question: pixels, for a photo the projection already
chose.

⚠ The window is the **merged** span, not the representative fragment's duration — a stay the capture
layer split into three rows presents as one moment, so it claims the photos of the whole stay. Same
floored, half-open rule as before, single-sourced in `PhotoSelection`.

### 10.2 One read per projection, not one per row

`readPhotos(moments)` asks `photosCapturedBetween` **once** for the combined span of everything
being projected. That is exactly the shape T-P2's op was built for: a feed of forty rows costs one
in-proxy walk, not forty.

⚠ Capped at 500 photos per read. A realm's history spans years, and the op ranks (provenance, then
recency) *before* it truncates, so a bounded answer drops the oldest and least-likely-wanted first —
a moment old enough to fall past the cap shows no photos rather than wrong ones.

⚠ No memoisation: the read re-runs on every projection, which is proportionate (the same transform
already re-reads moments, places, workouts and traces) and keeps photos **fresh** — a cache keyed on
the span would not notice a photo uploaded *into* a span it already knows.

### 10.3 The feed fetches blobs only for what is on screen

`File.Thumbnail` is `OnDemand`, so every rendered photo can cost a content-plane fetch — the trap
§5 flagged. The fix is that `PhotoFrame` resolves its **own** bytes through a new `LocalPhotoBytes`
composition local, so only frames that are actually *composed* ask: a `LazyColumn` row that has not
scrolled into view and a carousel page that is not near the current one never do, and `produceState`
cancels what a fast scroll flew past instead of queueing it.

⚠ A `CompositionLocal` rather than a parameter threaded through five composables, because it is a
*service* ("given a path and a size, give me bytes") that every photo surface needs and none owns.
Absent — previews, the mock, the not-connected surface — every frame falls back to its gradient,
which is the right answer for a screen with no proxy behind it.

### 10.4 Aggregates count their members, never their date range

`Trip.photosCount` sums its member moments' windows; `Place.photosCount` sums its visits'. Counting
over a trip's *date range* would fold a week of everyday photos into a weekend away, and a place
visited twice months apart would claim everything photographed in between.

### 10.5 ✅ Tap-through into Drive's viewer — CLOSED 2026-08-21 by `control-cross-app-open.md` TT-2a

The phase list said "tap-through into Drive's existing viewer". There is no surface to tap through
*to*: Drive declares one deep link (`tenshu-invite`) and nothing that opens a file by path. Building
it means a new cross-app navigation contract — an intent filter in Drive, a launcher in Timeline, a
path→viewer route, and a decision about what happens when Drive is not installed — which is its own
slice, not a line of this one.

Two options when it is taken up, neither obviously right yet: a real deep link into Drive (correct
app owns the viewer, but a new cross-app surface), or a full-screen viewer inside Timeline (no new
contract, but a second viewer to maintain). Tapping a photo does nothing today, which is at least
not a promise the app fails to keep.

⭐ **Resolved 2026-08-21 — the first option, and it stopped being a trade-off.** Three of the four
costs above were paid generically by `control-cross-app-open.md` TT-1: no intent filter is needed
(the component is explicit), Control mints the launch, and `NOT_INSTALLED_HERE` is the
not-installed decision. The path→viewer route already existed. Timeline gains **no viewer, names no
app and declares no `<queries>`** — it hands Control the file's `DataId` and Control decides. See
that doc's §5f. ⚠ Not yet exercised on a device: a Timeline photo is a backed-up Drive file, so the
tap needs a realm with content.

⚠⚠ **Amended 2026-09-01 — the tap-through is a *detail-surface* affordance, not a photo-frame one.**
Wiring it into `PhotoCluster` gave it to the Home feed's moment cards too, where the photo sits under
the moment's own title inside the row's own click: the same card then opened two different apps
depending on which pixel was pressed, and the title's destination was unreachable through the frame.
A feed row's photo now goes where its title goes; the deep link stays on moment detail and a place's
visit rows, where the photo genuinely is the subject.
`control-cross-app-open.md` §5l.1 carries the reasoning.

⭐⭐ **And the tap-through itself is device-verified at last (2026-09-01)** — the "not yet exercised
on a device" caveat above is spent. It took a realm with backed-up photos in it, which is also what
surfaced the feed-row defect.

### 10.6 What is proven

- **7 pure assignment tests** — the window containment, the unclaimed gap between moments, the floor,
  provenance surviving the per-moment cap, a merged stay claiming its whole span, the no-photos
  no-change case, and a trip counting members rather than its date range.
- The hero tests were rewritten around the new shape: the view model no longer chooses a photo, so
  what is pinned is that it fetches bytes exactly once, that photo and bytes surface together or not
  at all, that a failed read leaves the moment on screen, and that **a drawn route suppresses the
  photo fetch entirely** (that one is a content-plane fetch, not just wasted work).
- Full `desktopTest` for the three timeline modules + `:timeline-android:assembleDebug`.

⚠ Unchanged from T-P2: nobody has looked at the rendered feed. The per-frame loader in particular is
a scroll-behaviour claim, and scroll behaviour is not something a unit test observes.

### 10.7 File map

| File | Change |
|---|---|
| `timeline-shared-data/.../TimelineProjection.kt` | `PhotoInput`; `days(photos = …)`; `photosWithin`/`photoCountWithin`/`spanOf`; trip + place + placeVisit counts |
| `timeline-shared-data/.../PhotoSelection.kt` | the window floor + per-moment cap, single-sourced |
| `timeline-shared-data/.../TimelineProjectionRepository.kt` | `readPhotos(moments)` feeding all five projection sites; `photosForMoment` deleted |
| `timeline-shared-logic/.../TimelineRepository.kt` | `photosForMoment` removed from the seam |
| `timeline-shared-logic/.../MomentDetailViewModel.kt` | hero reads the projection's photos; fetches bytes only |
| `timeline-shared-ui/.../PhotoImage.kt` | `LocalPhotoBytes` + `rememberLoadedPhoto` |
| `timeline-shared-ui/.../PhotoFrame.kt` | resolves its own bytes when not handed a bitmap |
| `timeline-shared-ui/.../App.kt` | provides the loader once, shell-level |

No wire, schema, format or relay movement — T-P3 is projection and UI only.

---

## 11. As built — T-P4 (2026-08-10)

A photo now carries where it was taken, and a moment only claims photos taken *there*. The join is
time ∧ place.

### 11.1 ⚠⚠ The finding that decides the whole slice: the coordinate was never in the bytes

`inference-service.md` §3.1 calls EXIF GPS "nearly free and needs no model", which is true of the
*parsing*. It is not true of the *getting*: since Android 10 `MediaStore` **strips an image's GPS
tags from every copy it hands out** unless the reading app holds `ACCESS_MEDIA_LOCATION` and asks
for the original. Nothing in this repository declared that permission.

So a parser wired up on its own would have found nothing, on every photo, silently — indistinguish-
able from "no camera writes GPS any more". The fix is one guarded call at the single place the bytes
are actually opened: `BinderTaskTransport.sendFile`, which runs in the **client's** process (Drive
opens the descriptor and pushes it to the proxy). `MediaStore.setRequireOriginal` there, gated on
API 29+ and on the permission being granted, and everything downstream just works.

⚠ It matters that this is the *client's* open. The proxy is where the bytes are parsed, but Control
holds no media permissions and has no screen on which to ask for any — so lifting the redaction
proxy-side was never available.

### 11.2 The permission is bundled, and never gates a backup

⚠⚠ **As shipped, it was also never *asked for*** — found on device the same day, when a photo backed
up with no coordinate and `dumpsys` showed `ACCESS_MEDIA_LOCATION: granted=false`. Both paths that
call `requiredPermissions()` are gated on `mediaAccess != MediaAccess.FULL`, and `MediaAccess` is
computed from `READ_MEDIA_IMAGES` alone — so for anyone whose library grant was already FULL (i.e.
everyone with backup working, the only people for whom this feature does anything) neither branch
ever fires. Declared, bundled into the request array, never handed to a launcher.

That is the same failure class §11.1 warns about, reproduced one layer up in the thing meant to
prevent it. The fix (2026-08-10) is a **separate signal**, `MediaAccessProvider.photoLocationGranted`,
and a distinct Synced-Folders notice shown when `enabled && access == FULL && !granted`:

- ⚠ It could **not** ride `MediaAccess`. Demoting a fully-granted library to `PARTIAL` over a
  location permission would misreport what backup can do — and `PARTIAL` drives real behaviour
  (which buckets enumerate).
- ⚠ Its absence is invisible without asking: Android strips the tags rather than failing, so a
  photo backed up without the grant is indistinguishable from one that never had a coordinate.
  Nothing downstream can notice, which is why the signal has to exist at the permission itself.
- ⚠ The notice is an **offer, not a fault** — backup is working; what is missing is one thing the
  photos could otherwise tell Timeline. Re-requesting the whole array is fine: the system prompts
  only for what is missing.



`ACCESS_MEDIA_LOCATION` is requested in the **same dialog** as Drive's existing read permissions,
because it is meaningless alone: it only lifts a redaction on images those permissions already
allow. Denying it costs the coordinate and nothing else — the backup runs, the photo uploads, and
Timeline still places it by time. There is no state in which Drive should refuse to back a photo up
because it could not learn where the photo was taken.

⚠⚠ **And then it crashed every file upload** — found within minutes of the grant being given
(2026-08-10). `MediaStore.setRequireOriginal` **appends a query parameter**, so it returns a
*different* URI — and a document handed over by `ACTION_OPEN_DOCUMENT` carries a grant for the exact
URI the picker issued, not for a mutated one. The picker path threw
`SecurityException: … requires that you obtain access using ACTION_OPEN_DOCUMENT`, on the main
thread, on every upload.

Two mistakes, and the second is the one worth remembering:

1. **No authority check.** `setRequireOriginal` is for `MediaStore` URIs. The gallery backup — the
   only path whose photos this feature is about — enumerates `content://media/…`, so narrowing to
   `MediaStore.AUTHORITY` costs nothing and excludes the document providers entirely.
2. ⚠⚠ **The guard was on the wrong call.** `setRequireOriginal` was wrapped in `runCatching` (it
   does not throw); `openFileDescriptor` — which does — was left bare. So a mutated URI turned a
   working upload into a crash. The read now **falls back to the plain URI on any failure**, and
   that second open is deliberately unguarded so a genuine permission problem still fails exactly
   as it did before this feature existed.

The predicate is extracted as a pure `shouldRequestOriginal(authority, granted)` and pinned, because
it is precisely the thing that was wrong and the rest of the path needs a `Context`. ⚠ Doing so
surfaced that `:core-common`'s `androidHostTest` source set **had stopped compiling** (a renamed
compiler diagnostic in an unrelated test, unnoticed because no standard gate compiles it).

### 11.3 The parser is hand-rolled, in `commonMain`

~180 lines walking JPEG's APP1 → TIFF header → IFD0 → GPS IFD. No dependency added to a service
module, one implementation for every platform, and the whole of it readable in one file. It never
throws: it runs on whatever the user uploaded, and a malformed file must not fail an upload.

⚠ **JPEG only.** HEIC keeps its EXIF in an ISO-BMFF box, so iPhone photos return nothing. That costs
nothing today — iOS has no gallery backup, so the realm's photos come from Android — and it is a
self-contained addition when it does.

⚠ Stored as **two scalars** (`File.CaptureLatE7`/`CaptureLngE7`), not one composite: a value
property is a `ValueSchema`, and the reference-property alternative would mean minting an entity per
photo location, which is precisely what a coordinate is not.

### 11.4 The join: exclude only what says it was somewhere else

Three rules, and the middle one is the one to get right:

1. A photo whose coordinate is **far from the moment's place is dropped** — the only place in this
   feature where a photo is excluded rather than out-ranked. It says where it was taken, and it was
   not here: a screenshot saved during a visit, a picture received in a chat.
2. ⚠⚠ A photo with **no coordinate is kept**. "Unknown" must never read as "elsewhere" — most photos
   have none (permission denied, no fix, a HEIC), and reading absence as exclusion would empty the
   feed on exactly the devices where the rest works.
3. A photo taken **there** out-ranks one that merely shares the minute, ahead of the backup-folder
   signal: a coordinate is stronger evidence than the folder a file happens to sit in.

Radius **250 m**, deliberately generous: a visit's coordinate is a dwell cluster's centre, indoor
fixes are ±100 m on their own, and a photo may be taken from across the square. What it rules out is
the other side of town, which is the entire difference between "taken then" and "taken there".

⚠ A moment with **no place** (a movement) falls back to time alone, exactly as before — it must not
lose every photo that carries a coordinate merely because it has nothing to compare against.

### 11.5 HR #9: this one really did owe a bump

`CapturedPhoto` gained two fields, and `SchemaShapeBaseline` called it a **reshape without a version
bump**. The tempting argument — that the type is never stored and never synced, so no row anywhere
is reshaped — is only half right: it *is* a positional wire shape, and a client one version behind
would mis-parse the reply mid-composite rather than fail.

So `FileService` is **v3**, with an empty migration step registered beside v2's (nothing stored to
migrate; the registration is what keeps a v2 store inside its derived floor so it migrates rather
than wipes). ⚠ The cost — the realm epoch rises on the next file write and peers below the floor are
pull-fenced until updated — is the accepted pre-release cost, and is deliberately **not** an argument
against doing it.

⚠ Note the contrast with T-P2 (§9.4), which added an *operation* and did not bump: adding a name is
not reshaping a shape.

`PropertyKindBaselineEnforcementTest` also required regeneration for the two new properties — both
`INLINE`, neither a kind flip.

### 11.6 What is deliberately not built

**Reverse-geocode → `Place`.** The phase description ends "…→ `Place`", and that half is Drive's
payoff ("photos from that café"), not Timeline's: it means minting or matching `Place` rows from
photo coordinates, which lands squarely on place identity (the one-venue-to-N-rows fragmentation of
`timeline-recognition-and-place-naming.md` §8), on the geocoding track's consent model, and on the Maps app's Places list. Timeline needs
none of it — it already has the places, and what it was missing was the photo's coordinate to
compare against. Sequencing that with `place-geocoding.md` rather than bolting it on here.

### 11.7 What is proven, and what is not

- **8 parser tests**, built byte-by-byte rather than from a fixture: both byte orders, both
  hemispheres (a sign error puts the user on the wrong side of the planet and no "is it null" check
  would catch it), null island, truncation, garbage, and a PNG.
- **6 join tests**: excluded-when-elsewhere, kept-when-there, kept-when-unknown, out-ranking,
  the no-place fallback, and across-the-square.
- Full `jvmTest`/`desktopTest` for every touched module; both baselines regenerated and re-verified;
  Android smoke gate PASSED — including `fit schema session admitted`, i.e. the `FileService` v3
  bump did not fence an app the gate does not rebuild.

⚠ **Unproven, and only provable on a device:** that a real backed-up photo arrives with a coordinate.
Every link in that chain — the runtime grant, `setRequireOriginal`, the descriptor crossing Binder
unredacted, the proxy-side parse — is Android framework behaviour that no unit test observes. The
honest expectation is that this is where a defect would be, and the check is a photo backed up on a
real phone showing up on the right moment and not on a neighbouring one.

### 11.8 File map

| File | Change |
|---|---|
| `file-service/.../ExifGps.kt` | new — the parser |
| `file-service/.../File.kt` | `CaptureLatE7` / `CaptureLngE7` |
| `file-service/.../CapturedPhoto.kt` | the coordinate on the wire |
| `file-service/.../FileService.kt` | **v3** |
| `file-service-common/.../FileServiceCommonModule.kt` | property registration + the v2→v3 empty migration |
| `file-service-proxy/.../UploadFileImplementation.kt` | parse at upload, client's reading wins |
| `file-service-proxy/.../PhotosCapturedBetweenImplementation.kt` | carries the coordinate out |
| `core-common/.../BinderTaskTransport.kt` | ⚠⚠ `setRequireOriginal` — the one call that makes any of it possible |
| `drive-android/AndroidManifest.xml`, `AndroidMediaAccessProvider.kt` | the permission, bundled |
| `timeline-shared-data/.../TimelineProjection.kt` | the time ∧ place join |
| `timeline-shared-data/.../PhotoSelection.kt` | the radius |
| `system-test-utils/.../*-baseline.txt` | both regenerated |
