# The Photos viewer's chrome and its About screen

**Status:** v0.2 — **PH-9 BUILT** (§6), the rest design. Opened 2026-08-30 from a maintainer
request: a Google-Photos-shaped **About** screen for one photograph, a real toolbar, and a favourite
toggle.

⭐⭐ **The headline of the sweep: three of the four things asked for are mostly *wiring*, and the
fourth is blocked on a platform slice this repo already named.** Favourites are fully built and
called by nothing; the EXIF walker already resolves the Exif sub-IFD and discards almost everything
it reaches; the map has two existing wrappers. The one genuinely missing piece is a **place name for
an arbitrary coordinate** — PH-G7, unshipped, and the reason a "Paris, France" line cannot be
honest yet.

⚠⚠ The load-bearing constraint on the whole track: **`photos-app.md` §17.4 — there is no EXIF
backfill.** Metadata is read at *upload* from bytes the proxy no longer holds, so widening the
stored row gains nothing for a single photograph the user owns today. §3.1 is the way out.

Sibling docs: [`photos-app.md`](photos-app.md) (§21 is the pager this builds on; S3 is the viewer,
S6 the place detail), [`control-cross-app-open.md`](control-cross-app-open.md) ("Open in Maps"),
[`place-geocoding.md`](place-geocoding.md) + [`place-roles.md`](place-roles.md),
[`timeline-photos.md`](timeline-photos.md) (Timeline's own photo read),
[`inference-service.md`](inference-service.md) (labels, later).

---

## 1. The sweep — what is already here

Written first because it changes the shape of every phase below.

| Piece | State | The catch |
|---|---|---|
| **EXIF walker** | ⭐ `readMediaMetadata` + `ExifTiff.kt` in `:file-service` `commonMain`. Hand-rolled, JPEG / PNG / HEIC / AVIF / MP4 / MOV, and it **already resolves the Exif sub-IFD and the GPS IFD**. | ⚠ `MediaMetadata` keeps **7 fields** and throws the rest away. ⚠⚠ No `RATIONAL` reader — and exposure, aperture and focal length are all RATIONAL. That is the one missing primitive. |
| **Favourites** | ⭐⭐ `isFavourite` / `setFavourite` / `favourites()` implemented end to end, and the Albums tab's Favourites entry already reads them. | ⚠⚠ **No UI anywhere calls them.** The star is one `IconButton`. ⚠ Keyed on `contentId`, empty for a pre-content-addressing file, and the op refuses it — so the star must be *disabled*, not offered-and-failing (the rule the feed's long-press already follows). |
| **Albums** | `albums()`, `addToAlbum`, `removeFromAlbum`. | ⚠ **No reverse lookup.** "Which albums is this photo in?" has no op — the About screen's album row needs one. |
| **Map** | `OfflineBasemap` is an `expect`/`actual` in `:ui-map` with all three actuals, plus `RoutePolyline` and `BasemapChrome`. | ⚠⚠ The two "small map with a pin" wrappers — `maps-shared-ui/MapSnippet.kt` and `timeline-shared-ui/PlaceMap.kt` — are both in **app** modules. Reusing either is an app-to-app dependency; lift one into `:ui-map` rather than write a third. ⚠ `photos-shared-ui` depends on neither `:ui-map` nor `:basemap-core` today. |
| **Place name** | ⚠⚠ **The blocker.** PH-G7 is unshipped: `ReverseGeocoder` lives in `:timeline-shared-data`, a Timeline app module (`photos-app.md` §13.2). | Photos can name only places **Timeline already captured and named**. A photo taken somewhere the user never dwelled has a coordinate and no name. |
| **Cross-app open** | ⭐ `control-cross-app-open.md` TT-1…TT-4b complete. The address is a `DataId`, never an app. | ⚠⚠ Platform-asymmetric: **Android — Control may neither launch nor raise** (§5e.1); desktop — Control does both; iOS — URL only. ⚠⚠ §5g.2: *a line with no receiving route is worse than none.* |
| **Workout / trip association** | `timelineServiceCommonModule` and `healthServiceCommonModule` exist and are registerable, so the read is possible. | ⚠⚠ `photos-app.md` §18.4: a service the app reaches but never registers fails **silently** — it renders empty. `PhotosClientGraphTest` guards this and must gain any new row. |
| **Timeline reading the favourite flag** | — | ⚠ Timeline reads photo *files* (`TimelineProjectionRepository.readPhotos`) but **does not register `:photo-service`** — only `:system-test-utils` does, outside Photos. Using favourites for trip thumbnails is one registration away, with §18.4's silent-failure trap attached. |

---

## 2. Decisions this track owes

### D1 — "All the EXIF": read it on the device, do not widen the row ⭐ *recommended*

Two ways to show more than seven fields:

- **(a) Widen `MediaMetadata` + the stored row.** Cheap to read, and an HR #9 bump + migration.
  ⚠⚠ **It shows nothing for any photograph the user already owns** — §17.4, no backfill — so the
  screen the request is about would be blank on the whole existing library.
- **(b) Parse on demand, on the device, from the file's own bytes**, calling the *same*
  `readMediaMetadata` from `commonMain`. Works for every file ever uploaded, needs no schema change,
  no version bump and no migration.

**(b), with (a)'s stored scalars as the instant-render fallback.** The screen renders the known
scalars immediately and fills in the long tail when the bytes land.

⚠⚠ **Amended by PH-11a (§7), and the amendment matters.** This paragraph originally called the cost
"a content-plane fetch of the original, honest for a screen the user explicitly opened". That was
written assuming a **ranged** read. There is not one: `GetData` has no offset or length parameter,
so the only way to reach the EXIF — which lives in the first few kilobytes — is to pull the **entire
original**, several megabytes, to read a couple of hundred bytes. On a phone on mobile data that is
not a trade a details panel gets to make silently. §7.3 records what PH-11b owes as a result.

⚠ (b) does not make (a) pointless: a *future* upload storing exposure and lens costs six more
scalars and makes the fast path richer. But it must not be the thing the feature waits on.

### D2 — the place line ships degraded rather than late

"City, Country" cannot be honest for an arbitrary photo until PH-G7 lands (§1). The options are to
block the About screen on a platform slice, or to show the place **only when the coordinate falls in
a `Place` Timeline named**, and show nothing otherwise.

**Ship degraded.** `photos-app.md` §13.2 already refused an "Unknown place" bucket for the same
reason, and a missing line is a smaller lie than a wrong one. ⚠ The map and *Open in Maps* do **not**
need a name — a coordinate is enough — so most of the visual weight of that section survives.

### D3 — the workout row is deferred behind the screen existing

Associating a photo with a workout or a trip by time is a genuinely good idea and it is **two service
registrations and a silent-failure mode** (§1) for one decorative row. It should be a phase of its
own, after the About screen is real and can be looked at.

### D4 — gesture priority is decided **once**, for three gestures at once

Three gestures want the same surface: pinch-**zoom** (and its pan), **vertical drag up** to open
About, and the conventional **drag down to dismiss**. Deciding them one at a time is how a viewer
ends up with a zoom that cannot be panned downward. ⚠ Today the pager owns the horizontal drag and
the vertical axis is genuinely free — verified on device: vertical swipes in the viewer do nothing.

⭐ **§3.2 shrinks this.** Drag-up is the bottom sheet's own gesture, so only two things are actually
being arbitrated: the sheet (up) and dismiss (down), which are opposite directions and compose
naturally. Zoom remains the one that needs a rule — **while zoomed in, the drag belongs to the pan**,
and the sheet and the dismiss are unreachable until the photo is back at fit-scale.

### D5 — how much of Google's layout to keep

Open. A screenshot from the maintainer is wanted here (offered, not yet supplied): the question is
spacing and section order, not content.

### D6 — ⚠⚠ the three ✎ pencils are writes we do not have, and one of them moves the feed

The reference screenshots carry **three edit affordances** that read as part of the layout and are
not: a caption field, a ✎ on the capture time, and a ✎ on the location. None exists in this
platform today, and they are not equal in cost.

- **Caption** — a per-photo synced string keyed on `contentId`, exactly the shape the favourite flag
  already has. Its home is `:photo-service`, and it is an HR #9 bump + `SchemaMigration` of that
  service. Contained, and genuinely useful.
- **Location ✎** — writes a coordinate the file did not claim. ⚠ It must be stored *beside* the EXIF
  value, never over it: "what the camera recorded" and "what the user corrected" are different
  statements, and PH-7's whole posture is that absent means *the file did not say*.
- **Capture time ✎** — ⚠⚠ **`capturedAtMs` is `File.CreatedAt`, which is also the feed's keyset
  cursor** (`Photo`'s KDoc). Editing it does not just relabel a row, it **moves the photograph in
  the feed** — and an edit made from a viewer that is paging over that same list shifts the pager's
  own indices underneath it. That is a real design question, not a field to add.

**Recommendation: caption yes, the two pencils not in this track.** They are edits to what the file
says about itself, they need a stored-versus-observed distinction the schema has never had, and the
time one has a paging consequence that deserves its own slice. Shipping a sheet with no pencils is
honest; shipping one whose pencil silently reorders the feed is not.

---

## 3. The screens

### 3.1 Viewer chrome (replaces the three floating icons)

A real top bar over the photograph:

```
←        9 Apr                    ☆   ⋯
      21:26 · Paris, France
```

- **Centre**: the localised date; beneath it time, and place *where known* (D2).
- **☆ Favourite** — pure wiring (§1). ⚠ Disabled with no `contentId`.
- **⋯ Overflow** — *About*, and later the actions S3 still owes (share, add to album, open in Drive).
- ⚠⚠ The bar and the bottom controls sit inside `WindowInsets.safeDrawing`; **the photograph does
  not**. That split is already built (PH-8b) and is what stopped the back arrow living under the
  status bar.

### 3.1b Immersive mode — a tap hides *everything*, including the system bars

Google's viewer treats a tap on the photograph as a toggle: the top bar, the bottom actions **and
the OS status and navigation bars** all go, leaving the photograph alone on black. A second tap
brings the lot back. Swiping between photos and dragging the sheet up keep working throughout.

⚠⚠ **It is one state, not two.** The tempting implementation is a `chromeVisible` flag over our own
controls plus a separate call to hide the system bars — and it is wrong in a way that shows: restore
the chrome without restoring the bars and the back arrow **drifts upward** into the freed space,
because `WindowInsets.safeDrawing` shrank when the bars went. Our own chrome and the OS bars hide
and show *together*, off one flag, or the inset layer §21.6b added starts lying.

⭐ Read the other way round, this is why coupling them is also what keeps the inset fix correct:
`safeDrawing` is always describing the bars that are actually on screen at the moment anything is
drawn inside it.

⚠⚠ **The system-bar half is a platform seam, and Photos has no iOS shell.** Compose Multiplatform
has no common API for this: Android is `WindowInsetsControllerCompat.hide(systemBars())` plus
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, desktop has no system bars at all, and iOS would be
`prefersStatusBarHidden`. So it is an `expect`/`actual` whose desktop actual is an honest **no-op**,
not a stub pretending to work — the failure `compose-global-modal-no-ios-twin` names, where an
app-global Compose affordance silently has no twin on a platform.

⚠ The tap must not eat the pager's drag. A `clickable` with `indication = null` on the page content
leaves the pager's drag detection in charge and avoids a full-screen ripple, which at this size
reads as a rendering fault rather than feedback.

⚠ Opening the About sheet forces the chrome back on. A sheet dragged up over a hidden top bar, then
dismissed to a bare photograph, loses the user their way out.

### 3.2 About — ⭐⭐ a bottom sheet, not a screen

**The maintainer's reference screenshots settle this, and it is the single most useful thing they
say.** Google's About is a **bottom sheet dragged up over the photograph**, with a drag handle, the
image still visible above it. Three consequences, all simplifying:

1. **There is no navigation destination.** No `Destination.About`, no back-stack entry, no
   origin-tracking — the photo stays where it is and the pager keeps its page.
2. **"Reachable by scrolling upwards" is not a custom gesture.** It is the sheet's own drag, which
   Compose already implements. D4 shrinks accordingly (below).
3. **⋯ → About and the drag are the same affordance**, so they cannot disagree about state.

⚠ It follows that About must render over a *photograph that can still be swiped underneath it*, or
must lock the pager while open. Google locks it. Locking is the honest choice: a sheet describing
one photo above a pager showing another is §21.4's stale-metadata bug wearing a bigger hat.

```
                    ▁▁▁▁                       ← drag handle
Thu, 9 Apr 2026  ·  21:26                  ✎   ← ✎ edits the capture time (D6)
Add a caption…                                 ← a write we do not have (D6)

Albums
  [cover]  Trip to France
           28 items

Paris, France          ✎        Open in Maps → ← ✎ edits the location (D6)
┌───────────────────────────────────────────┐
│  map, the photograph in a circle at its   │
│  coordinate                               │
└───────────────────────────────────────────┘

Details
  ┌─────────────────────────────────────────┐
  │ ☁  Backed up · 3.1 MB                   │  ← ours: residency, "on N devices"
  │    Original quality                     │
  └─────────────────────────────────────────┘
  ┌─────────────────────────────────────────┐
  │ ◎  OnePlus 12                        ⌄  │  ← ⌄ EXPANDS. The EXIF is in here.
  └─────────────────────────────────────────┘
```

⭐ **This answers *"why does Google show so little?"* — it does not.** The camera card carries a
chevron, and the exposure triple, lens and the rest are behind it. Their Details is *collapsed*, not
sparse. So the shape to copy is a **card per subject that expands**, not a flat table — which is
also what keeps a phone screenshot (three tags) and a camera raw (forty) both looking deliberate.

⚠ *Details* lists **what the file said** and omits what it did not — the rule PH-7 already follows.
With D1's on-device parse behind the chevron, ours can be strictly more complete than Google's while
staying strictly as honest.

⭐ The "Backed up · 3.1 MB · Original quality" card is where **we have something Google structurally
cannot say**: which of *your* devices hold the bytes. `photos-app.md` S3 already specs *"on N
devices"* for the info sheet, and this is its home.

---

## 4. Phase map

| Phase | Content | Depends on |
|---|---|---|
| **PH-9** ✅ | Viewer chrome: top bar with date / time / place, ☆ favourite (wiring only), ⋯ overflow, and **immersive mode** (§3.1b) — one flag over our chrome *and* the OS bars, behind an `expect`/`actual` whose desktop actual is a no-op. | — |
| **PH-10** ✅ | The About **sheet** (§3.2): header, albums row, place line, Details. ⚠ Locks the pager while open — §9. ⭐ The reverse lookup needed **no new op**. | PH-9 |
| **PH-11a** ✅ | EXIF depth, the parser half: the `RATIONAL`/`SRATIONAL` reader and twelve tags, on both container paths. **BUILT** — §7. ⚠ Read but never stored, so no schema move. | — |
| **PH-11b** ✅ | The on-device parse (D1b), **gated on `hasLocalData`** — §8. Rendered in today's info sheet, so the depth is visible before PH-10 exists. ⚠ A photo whose bytes are elsewhere says so rather than fetching. | — |
| **PH-12** | The map section + *Open in Maps*. Lifts a `PlaceMap` into `:ui-map` first (§1). | PH-10, cross-app open per platform |
| **PH-13** | **PH-G7**, the platform slice: `ReverseGeocoder` moves to `:place-service`. Upgrades D2's degraded line everywhere at once — Photos, and the Places section too. | its own review |
| **PH-14** | Workout / trip association + *Open in Timeline* (D3). | PH-13, `PhotosClientGraphTest` rows |
| **PH-15** ✅ | Pinch-zoom + drag-to-dismiss, under D4's one rule — §10. ⚠ Drag-**up** is left free on purpose: §3.2 owes it to the sheet. | D4 |
| **PH-16** | The caption (D6) — a synced per-photo string in `:photo-service`, with its HR #9 bump. ⚠ The two ✎ pencils are deliberately **not** here. | PH-10 |

⚠ PH-9 and PH-11 are the two with the best ratio of value to risk, and they are independent of the
blocked half. PH-13 is the one that unblocks the most and belongs to no single app.

---

## 5. What this track must not do

- **Must not** widen the stored row and call the EXIF question answered — D1 explains why that ships
  a blank screen for every photo the user has.
- **Must not** print a place name derived from anything but a real `Place`. An "Unknown place", a
  bare coordinate dressed as a location, or a country guessed from a timezone are all worse than the
  omitted line.
- **Must not** ship *Open in Timeline* / *Open in Maps* on a platform where the receiving route does
  not exist — `control-cross-app-open.md` §5g.2.
- **Must not** drive immersive mode off two flags. One state, or the inset layer drifts (§3.1b).
- **Must not** ship an editable capture time without answering what it does to the feed's keyset
  cursor (D6). A pencil that silently reorders the library is worse than no pencil.
- **Must not** overwrite an EXIF value with a user correction. Store beside, never over.
- **Must not** register a service in `photosSchemaConfigs` without a `PhotosClientGraphTest` row.
  §18.4 is the precedent: three phases shipped screens reading services nobody had registered, and
  every one failed silently.

---

## 6. As built — PH-9: the viewer's chrome (2026-08-30)

A top bar (← · date / time · place · ☆ · ⋯), the favourite toggle, immersive mode, and a
`BackHandler` the app never had. Device-verified on a OnePlus CPH2581.

### 6.1 The star was wiring, exactly as §1 predicted

`isFavourite` / `setFavourite` were already implemented end to end and called by nothing. What the
slice actually added around them is the two rules that keep them honest:

- ⚠⚠ **`canFavourite` is separate from `favourite`.** A file with no `contentId` cannot carry the
  flag and the op refuses it, so the star renders **disabled** — never hollow-and-failing. The two
  absences are different claims and the state keeps them apart.
- ⚠⚠ **The optimistic update reverts on refusal.** `setFavourite` returns `false` for a row it will
  not write, and a star left filled over a flag that was never stored makes the viewer and the
  Favourites album disagree — with the user believing whichever they saw last.

⭐ Verified end to end on device: starred, force-stopped the app, relaunched, reopened the same
photograph — *"Remove from favourites"*. A full round trip through `:photo-service` and the realm.

### 6.2 The place line, and what it does on a real library

`nearestPlace` is the single-photo twin of `groupPhotosByPlace`, keeping all three of its refusals
(nearest-of, named-only, `null` beyond the radius). ⚠ Note the KDoc on `PhotoPlace` had **already
referenced `nearestPlace`** before it existed — the function was designed and never written.

⚠ On the maintainer's own device the subtitle renders **"16:22" with no place** — D2 working as
designed, not a defect. PH-G7 is unshipped, so a coordinate only resolves where Timeline has already
named a `Place`.

### 6.3 ⚠⚠ A test that passed for the wrong reason, caught by falsifying it

`thePlaceIsTheNearestNamedOneInRange` originally put its rival place 3 km away. The radius filter
removed it *before* the nearest-of rule ever ran, so the test passed with `minByOrNull` replaced by
`firstOrNull` — it was checking "in range" while claiming to check "nearest". The fixture now puts
a second named place **150 m** away and listed first, and the falsification fails as it should.

⚠ The original fixture also had its E7 coordinates wrong by a factor of ten. It still passed,
because the photo and the place shared the same wrong value and the distance was zero — a fixture
can be internally consistent and describe nowhere.

### 6.4 ⭐ System back left the app — found by pressing it

Photos navigates on a `Destination` held in Compose state, and **nothing listened for system back**:
pressing it inside the viewer went straight to the launcher. Survivable while every screen was one
tap deep; not once the viewer became somewhere you dwell.

⚠ Fixed with one `BackHandler` at the shell, not one per screen — a per-screen handler is more code
*and* leaves whichever screen someone forgets dropping the user out of the app. The `expect`/`actual`
is `drive-shared-ui`'s, verbatim.

### 6.5 Immersive mode, and how it was actually verified

⚠⚠ **`screencap` is unusable for this on the maintainer's OnePlus.** It returns a *stale cached
frame* of a previously-visible app composited under the live layer: three consecutive captures
showed our chrome over a frozen Google Photos frame, with two different clocks in one status bar.
Reading a screenshot would have "confirmed" a defect that does not exist.

⭐ What proved it instead, and what any later device pass here should use:

- `uiautomator dump` for our own chrome — every node in the tree is `app.tenshu.photos.android`,
  and the count of `Back` / `More` / favourite nodes goes 3 → **0** → 3 across taps.
- `dumpsys window windows` for the **OS** bar: the `StatusBar` window reports
  `isVisible=false, isVisibleRequested()=false` while immersive and `true` after a tap — including
  after leaving the viewer, which is the `DisposableEffect` restore doing its job.

### 6.6 Not in this slice

- **About** is not in the ⋯ menu yet. Its three items — Details, Add to album, Move to trash — all
  do something today, per `control-cross-app-open.md` §5g.2: a line with no receiving route is worse
  than none. *About*, *Share* and *Open in Maps* arrive with PH-10…PH-12.
- **No bottom action bar.** Google's Share / Edit / Add to / Bin needs Share and Edit, which this
  app does not have; the ⋯ menu carries the two actions that exist.
- **The date is not tappable.** Google's "9 Apr ›" navigates to that day in the grid; the feed has
  no day anchor to jump to.
- **No test pins immersive mode or the inset layer.** Neither is visible to a render test — no
  status bar exists in the desktop suite to hide. Both are held by the device pass and §6.5's method.

---

## 7. As built — PH-11a: the parser has depth (2026-08-30)

Twelve photographic tags, on both container paths, with the `RATIONAL` reader that was the one
missing primitive. **Read but never stored** — the upload writes the same six scalars it always did,
so this is not a schema move and carries no HR #9 obligation.

### 7.1 What was actually missing

§1 said the walker "already resolves the Exif sub-IFD and the GPS IFD", and that held up: `entryOf`,
`tagValue` (BYTE/SHORT/LONG) and `tagAscii` were all there and correct. The gap was exactly one
type — **`RATIONAL`** — and every photographic tag anyone wants is one: exposure `1/250`, aperture
`18/10`, focal length `2600/100`.

⚠ A rational is **always at an offset, never inline**: two 32-bit words is eight bytes and the
entry's value field holds four. Unlike every other reader in that file there is no inline case, and
a reader that tried to handle one would be reading the offset as the numerator.

⚠⚠ `SRATIONAL` is a separate type id and exposure bias is signed. Read unsigned, −1/3 EV becomes
about 1.4 billion — a number that renders happily. Both the type check and the sign are tested.

### 7.2 Two rules the field set forced

- ⚠⚠ **`0` is an answer for some tags and absence for others.** Exposure time and focal length are
  guarded `> 0` — a camera claiming a zero focal length is saying nothing. Exposure bias, flash,
  metering and white balance are **not**, because `0 EV`, "flash did not fire" and "auto white
  balance" are real, common readings. The reflex to filter falsy values would silently turn the most
  frequent value of those fields into "the file did not say".
- ⚠⚠ **`isEmpty` is now `this == MediaMetadata()`.** It was a hand-written conjunction over seven
  fields; this slice added twelve more, every one of which would have had to be remembered there. A
  forgotten field makes `isEmpty` answer `true` for a file that *did* say something — silently, and
  only for the tag nobody added. Data-class equality cannot drift from the constructor.

⭐ The fold onto `MediaMetadata` is **one function called from both containers**, not the same twelve
assignments written twice. `MediaMetadata.kt`'s own KDoc makes the argument for one walk — *"two
walks would drift, and the drift would be invisible"* — and two copies of the fold is that hazard one
layer up. The HEIC test fails the moment the fold is removed from that path, which is the case that
matters: every iPhone photo is a HEIC.

### 7.3 ⚠⚠ What PH-11b owes, and why it is not here

The parse is worthless until something calls it with bytes in hand, and that is where this stopped —
for a reason the plan did not anticipate.

**There is no ranged read.** `GetData` takes a file and returns all of it; it has no offset or
length. EXIF lives in the first few kilobytes of a JPEG or HEIC, so an on-demand parse today means
pulling the **whole original** — megabytes — to read a couple of hundred bytes. D1 was written
assuming otherwise and is amended above.

Three ways out, none free:

1. **A ranged `GetData`.** The right fix, and it serves more than this — ⚠ check first whether the
   op's params composite is schema-registered, because widening it would then be an HR #9 reshape.
2. **Parse only when the device already holds the bytes**, and say so otherwise. ⭐ Cheap, honest,
   and it composes with the "Backed up · on N devices" card §3.2 already wants — the panel would
   show the full settings for photographs this device has, and offer to fetch for the rest.
3. **Store more scalars at upload.** Rejected by D1 and still rejected: no backfill, so it shows
   nothing for any photograph already in a realm.

⚠ Option 2 is the one to build first: it needs no op change, and it makes the depth visible for the
common case of a photo you just took on the device you are holding.

### 7.4 Not in this slice

- **Nothing renders these yet.** The info sheet still shows what it showed; the About sheet (PH-10)
  is where they belong, and PH-11b is what will fill it.
- **Maker notes.** Apple and Google keep the interesting parts (lens identity, computational-photo
  flags) in a vendor-specific `MakerNote` blob with no public grammar. Out of scope, probably
  permanently.
- **`DateTimeOriginal` is not parsed to an instant.** EXIF records no zone, and inventing one is a
  claim the row cannot support — it is carried verbatim.

---

## 8. As built — PH-11b: parsed where the bytes already are (2026-08-30)

§7.3 left PH-11b blocked on a ranged read. It shipped without one, by asking a different question:
**not "can we fetch this cheaply?" but "is it already here?"**

### 8.1 The gate is the design

`hasLocalData(path)` is O(1) and reads no payload — its own KDoc says so, and Drive's offline glyph
already leans on exactly that. So the order is:

1. probe presence — free;
2. only if `true`, read `File.Data` — now a **local disk read**, not a content-plane fetch;
3. parse with `readMediaMetadata`, the same `commonMain` function the upload uses.

⚠⚠ **Reversing those two lines is the whole bug this avoids.** `File.Data` on a file the device does
not hold pulls the entire original — megabytes for a couple of hundred bytes of EXIF, on whatever
connection the user happens to be on.

⚠ `ExifReadResult` has four cases and `NotOnDevice` is the one that earns the type: the file is
fine, the realm is fine, the bytes are simply elsewhere. It is a **state to render**, never an
error — and never hidden, because on a real library it is the *common* case and a panel that
silently shows nothing for most photographs reads as a parser that does not work.

⚠ A `true` probe followed by an empty read also answers `NotOnDevice`: a race with an eviction is
"not here", not a failure.

### 8.2 It renders in the sheet that exists, not the one that does not

PH-10's About sheet is where these rows belong. Waiting for it would have left PH-11a's twelve tags
with no consumer at all, so they render in today's info sheet — the same panel, the same lazy rule,
one section lower. PH-10 moves them; nothing about the read changes.

⚠⚠ **The read fires only for an open sheet.** Not on viewer open, not on a swipe past a closed
panel. Four tests exist for that alone, because it is the difference between a free panel and one
that reads a file every time a finger moves.

### 8.3 Presentation lives in the app, not on the row

`exposureLabel`, `apertureLabel`, `focalLengthLabel` and `exposureBiasLabel` are extensions in
`:photos-shared-logic`, beside `PhotoDetails.camera` — **not** on `MediaMetadata`. That is the rule
PH-11a's own KDoc set for the raw enums and the `core-realm-data.md` precedent: `:file-service`
records what the file said; how it reads in a sentence is the consumer's business, and Mia or
Timeline may phrase it differently.

Three judgements worth keeping:

- **Short exposures are fractions, long ones are decimals.** `"0.004 s"` is correct and unreadable
  to the only people who look at that row.
- **`f/2`, never `f/2.0`.**
- ⚠⚠ **`0 EV` renders.** It is the default and by far the commonest reading, and a field that
  vanishes exactly when it holds its usual value looks broken rather than absent.

### 8.4 Not in this slice

- **A ranged `GetData`** is still the better long-term answer, and would let this work for photos the
  device does not hold. §7.3 option 1 stands, including the check on whether the op's params
  composite is schema-registered.
- **No "fetch it anyway" affordance.** The panel states the fact; offering the download is a PH-10
  decision, and it belongs beside the "Backed up · on N devices" card rather than in a metadata list.
- **Video is read whole.** `moov` can sit at the end of an MP4, so a long video means a large local
  read. Acceptable for a local file behind an explicit tap, and worth revisiting with the ranged read.

---

## 9. As built — PH-10: the About sheet (2026-08-30)

The info sheet became **About**: a header that says when, the albums this photograph is in, where it
was taken when that is known, and the details — including PH-11b's camera settings.

### 9.1 ⭐ The album reverse lookup needed no new operation

§1 listed "which albums is this photo in?" as missing, and the phase map said PH-10 needed a new op
for it. It did not. `albumPhotos` already reads **every** `AlbumEntry` row and filters client-side by
album id, so filtering the same read by *content id* instead is the identical cost — two reads that
already exist (`allEntries` for the ids, `albums` for the names, counts and covers), composed.

⚠ It short-circuits on an empty `contentId` rather than reading anything: membership is keyed on
that id (`photos-app.md` §11.2), so a file predating content addressing is in no album by
construction, and spending two realm reads to be told a certainty is waste.

⚠ A failed read omits the section rather than failing the panel. The album row is decoration on a
sheet about one photograph; the panel is still correct without it.

### 9.2 ⚠⚠ The pager locks, and that is the price of a sheet rather than a screen

§3.2 chose a sheet over the photograph. The consequence it named is the one that had to be built: a
sheet describing one photograph above a pager showing **another** puts every field in the panel on
the wrong picture at once — §21.4's stale-metadata bug, but for the whole panel rather than one row.

`userScrollEnabled = !state.infoOpen`. One line, and the correctness rule the whole
sheet-not-screen decision rests on.

### 9.3 ⚠⚠ A test that passed with the lock deleted

`thePagerIsLockedWhileTheSheetIsOpen` used `swipeLeft()`, whose gesture starts at the node's
**centre** — which the sheet covers, since it occupies the lower 72%. So the swipe never reached the
pager whether it was locked or not, and the test **passed with `userScrollEnabled` removed
entirely**. It was asserting "the sheet is in the way".

It now swipes across the top 8%, clear of the sheet, and goes red the moment the lock is deleted.
⚠ This is the second fixture on this track to pass for the wrong reason (§6.3 was the first, on
`nearestPlace`), and both were caught only by deleting the mechanism and re-running. A green test is
evidence of nothing until it has been seen to fail.

### 9.3b ⭐ Two defects a device found and no test could have (2026-08-31)

The first device pass since PH-9 ran the merged viewer on a OnePlus CPH2581. Both findings are
gesture arbitration, and both were **caused by PH-10's own pager lock**.

**A swipe over the photograph dismissed all the chrome, sheet included.** `clickable`'s tap
detection cancels when another gesture **consumes** the pointer — not merely when it moves. While
the pager was scrollable it swallowed every horizontal drag, so the click never fired. Setting
`userScrollEnabled = !state.infoOpen` stopped it consuming them, and any drag over the photograph
then ended as a *click*. ⚠⚠ One gesture changed and an unrelated one broke, which is precisely the
hazard D4 predicts and the reason PH-15 decides zoom, drag-up and drag-to-dismiss together rather
than one at a time.

Fixed by disabling the toggle while the sheet is open — the sheet is the interaction target while it
is up, and it is dismissed with Back or the overflow, never by brushing the picture behind it.

**Back skipped the sheet entirely.** One press closed the sheet *and* left the viewer, so asking to
dismiss a panel put the user back on the grid. A nested `BackHandler` now consumes it: the innermost
enabled one wins, so the first Back closes the sheet and the second returns to the feed.

⚠ Neither was reachable from a render test on this branch. The first needs a real pointer stream
(the desktop test that now guards it had to be written *after* the device showed the shape); the
second needs a platform back gesture, which the desktop `BackHandler` actual is an honest no-op for.

⚠⚠ **Installing the merged Photos put the app straight into schema skew** — the beacon read
`tenshu/sync/service announced=4 floor=1 current=3`, and the banner said *"Photos is newer than
Tenshu Control on this device"*. Not this track's doing (`:sync-service` was bumped upstream) and
exactly House Rule 9's lockstep working: installing the matching Control cleared it to
`admitted=true`. Worth knowing before any later device pass — **the viewer will not run against an
older Control**, and the failure is a banner rather than anything that looks like a Photos bug.

⭐ What the pass confirmed on real data: PH-11b's on-device EXIF parse read `Taken` and `Software`
out of a screenshot's own bytes, and showed **no** exposure/aperture rows — a screenshot has no
camera settings, and the omit-rather-than-guess rule held against a real file rather than a fixture.

### 9.3c ⚠⚠ Twelve string templates that were never templates (2026-08-31)

The same device pass, checking the crash buffer afterwards, found Photos had died:

```
FATAL EXCEPTION: main   app.tenshu.photos.android
java.lang.IllegalArgumentException: Key "year:$year" was already used.
```

The literal `$year` is the whole diagnosis. The id was written `"year:${'$'}year"` — the
**escaped-dollar idiom**, which produces a literal `$`, not an interpolation. Sweeping the module
found **twelve** of them, all from PH-2 and PH-3 (2026-08-24), and they are three different bugs:

| Site | What it actually did |
|---|---|
| `AlbumsViewModel` year + place ids | Every year (and every place) shared one id → duplicate `LazyVerticalGrid` key → **the tab crashed on open** for any library spanning two years |
| `TenshuPhotosRepository.createAlbum` | Every album ever created got the **same id**; `saveAlbum` is an upsert, so making a second album silently **renamed the first** |
| `TenshuPhotosRepository.trash` | Every trashed photo resolved to **one `moveFile` destination**, so trashing a second moved it on top of the first |

The subtitles were literals too, so the tab read `"${photos.size} photos"` back to the user.

⚠⚠ **The lesson is not "check your templates" — it is that every one of these had a test,
and all of them were green.** Each passed for its own reason, and each reason is reusable:

- `eachYearWithPhotosBecomesASection_newestFirst` already had a **two-year fixture** — the one
  thing needed to collide — and asserted `title` and `photos.size`. It never read an `id`. The
  fixture was right and the assertion was looking somewhere else.
- `anAlbumWrittenThroughTheRealOpsIsReadBackByTheRealRepository` creates **one** album and asserts
  the id it was handed equals the id it reads back. Both sides are the same constant, so it is green
  for *any* id, including a broken one. One row can never collide with anything.
- `aTrashedPhotoWaitsInTheTrashWithWhereItCameFrom` asserts `path.startsWith("/Trash/")` — and the
  literal *does* start with `/Trash/`. A **prefix assertion is blind to the interpolated remainder**,
  which was the entire defect.

⚠ In two of the three the KDoc beside the line stated the invariant the code was violating
(*"two photos called `IMG_0001.jpg` … cannot collide"*, *"a collision would merge two albums the
user made in the same millisecond"*). The comment described the intent; nothing tested it.

The three guards added here are falsified — each one reintroduces its bug and goes red, while every
pre-existing test stays green, which is what proves they were covering nothing. Two ids that must
differ need **two rows and an assertion past the prefix**; that is the shape to copy.

⚠ The sweep was repo-wide. Outside Photos the only live hit was `MiaTestProxy`, whose `newId`
mints confirmation tokens and `commandId`s — test-only, but it meant Mia's token identity was
untested. Fixed; its suite is unchanged, so nothing was leaning on the collision.

### 9.4 Not in this slice

- **Drag up to open.** The sheet opens from ⋯ → About; the drag handle is currently decorative. The
  gesture belongs with PH-15, where it is decided together with zoom and drag-to-dismiss (D4) rather
  than three times.
- **The map and *Open in Maps*** — PH-12, and it needs a `PlaceMap` lifted into `:ui-map` first (§1).
- **Expandable Details cards.** §3.2 wants a card per subject with a chevron, the shape Google uses.
  The rows are flat for now; the content is right and the container is not.
- **The caption and the two ✎ pencils** — D6. The caption is worth having; editing capture time
  moves the feed's keyset cursor and owes its own slice.

## 10. As built — PH-15: gesture priority, decided once (2026-08-31)

Shipped in two passes on the same day — zoom + pan first, then drag-to-dismiss (this slice).
⚠ The split was a delivery convenience, **not** a second decision: D4's rule was written into the
`PhotoViewerScreen` KDoc for all four gestures in the first pass, and the second implemented against
a rule that was already fixed. That ordering is the whole of D4's advice.

| Gesture | Owner |
|---|---|
| Two fingers | the zoom, always — a pinch is never a page swipe |
| One finger, magnified | the pan |
| One finger **down**, at fit-scale | the dismiss |
| One finger **sideways**, at fit-scale | the pager |

⚠ **Upward is deliberately unclaimed.** §3.2 makes drag-up the About sheet's own gesture, so
dismissing on an upward drag too would spend a direction the sheet is owed.

### 10.1 ⚠⚠ The whole of this is *who consumes what*

`detectTransformGestures` is the obvious tool and it is the wrong one: it consumes a one-finger pan
as soon as it passes touch slop, which at fit-scale would take **every page swipe** with it and stop
the pager working at all. So the gesture loop is hand-written, and consumes only when it should own
the gesture:

| Pointers | Scale | Direction | Consumed? |
|---|---|---|---|
| Two or more | any | any | **Yes** — a pinch is never a page swipe |
| One | zoomed in | any | **Yes** — D4: while zoomed in, the drag belongs to the pan |
| One | fit-scale | mostly **down** | **Yes** — it is a dismiss |
| One | fit-scale | anything else | **No** — the pager sees it |

⚠⚠ **`travel.y > abs(travel.x)` is the axis lock, and it is the line that let the dismiss be added
without costing the pager its swipe.** A drag that is mostly sideways is never claimed, so it stays
unconsumed and reaches the pager. Deleting that one comparison turns
`atFitScaleAOneFingerDragStillBelongsToThePager` red — i.e. the *existing* test now guards the
*new* gesture, which is the property worth having.

⚠ Getting this wrong is not hypothetical: §9.3b is the same surface, where PH-10's pager lock left a
drag unconsumed and it arrived as a *click* that dismissed the entire chrome. One gesture changed
and an unrelated one broke. Consumption is the entire arbitration.

### 10.1b ⚠⚠ The owner is latched, and the previous version's was not

The first pass re-read `pressed > 1 || scale > 1f` on **every event**, so a pinch that happened to
pass back through exactly `1f` mid-gesture stopped consuming and handed the remainder to whoever
else wanted it. Nothing noticed, because at fit-scale the only other claimant was the pager and the
result looked like a slightly sticky swipe.

Adding a third claimant made that unacceptable — a gesture that could change owner under the finger
is precisely the failure D4 exists to prevent — so ownership is now decided **at most once per
gesture** (`Claim.Undecided → Transform | Dismiss`) and never revised until the finger lifts.
⭐ This is a fix the dismiss forced but the zoom already needed.

**The rest of the dismiss, briefly:** the threshold is 18% of page height, read **after** the finger
lifts (the value is still being written by the animation coroutine, so the decision is about where
the photograph *is*, not where the last event was); travel is clamped downward, so dragging back up
past the start returns it rather than carrying it off the top edge; and release either dismisses or
springs back with `Animatable.animateTo`.

⚠ `Animatable` rather than plain state is not decoration: the value has two writers with different
needs — the finger sets it directly, the release animates it — and mixing a direct write with
`animateFloatAsState` yields a value that fights itself mid-drag.

⚠ **The feedback is a shrink, not a fade.** Google fades the ground out to reveal the grid; our
viewer's ground is opaque black with nothing composed behind it, so fading would reveal the window
rather than the feed. The photograph pulling away from the frame is the honest version of the same
sentence.

### 10.2 The two lock states are not the same lock

`userScrollEnabled = !state.infoOpen && !zoomed` — the pager is locked by an open About sheet (§9.2,
so a sheet cannot describe one photograph while the pager shows another) **and** by a magnified page
(D4). ⚠ `zoomed` is hoisted to the pager and reported by the page through `onZoomChanged`, gated on
`page == pagerState.currentPage`: a neighbour is composed ahead by `beyondViewportPageCount` and
reports its own reset state, and letting that through would unlock the pager mid-pinch.

⚠ The flag is also cleared on every page change. Without it, a photograph zoomed and then swiped
away from once back at fit-scale could leave the flag set and silently lock the pager on the *next*
page — a viewer that stops paging for no visible reason.

### 10.3 ⚠⚠ What the tests do **not** guard, stated plainly

`ViewerZoomGestureTest` pins the fit-scale contract: the pager still pages, a drag is not a tap, a
tap still is one. The image is real (a decodable 1×1 PNG) so the gesture modifier is genuinely in
the tree — ⚠ an earlier version passed `loadHero = { null }`, rendered `HeroState.Absent`, composed
no `Image` at all, and was therefore testing an empty page while appearing to pass.

⚠ **This was weaker in the first pass and is worth recording as changed.** With only the zoom, the
consumption rule was *not* falsifiable here — making the loop consume unconditionally left all three
tests green, so the KDoc said plainly that the arbitration was device-verified rather than
test-verified.

⭐ **Adding the dismiss made most of it falsifiable**, because the new gesture competes on the axis
the pager uses. Five independent breakages each turn exactly one test red:

| Break | Test that objects |
|---|---|
| Threshold unreachable | `aLongDownwardDragDismissesTheViewer` |
| Threshold zero | `aShortDownwardDragSpringsBackInsteadOfDismissing` |
| Sheet no longer gates it | `theDismissIsOffWhileTheAboutSheetIsOpen` |
| Claimed drag not consumed | `aDownwardDragIsNotAlsoATap` |
| **Axis lock removed** | **`atFitScaleAOneFingerDragStillBelongsToThePager`** |

⚠⚠ What is *still* not falsifiable is the **two-finger** branch: the pinch cannot be synthesised in
this harness, so zoom itself remains device-verified only.

### 10.3b ⭐ The device pass — every claim in the table above, on hardware (2026-08-31)

Run on a OnePlus CPH2581 against the **merged** build (`f24b2e86`), not a branch build. A one-finger
vertical drag *can* be synthesised with `adb input swipe`, so unlike the pinch this half is
checkable without a human hand:

| Check | Input | Result |
|---|---|---|
| The axis lock holds | swipe 900→200 horizontally | **paged**, `w2b-roundtrip.png` → `Screenshot_2026-08-30-17-49-31…jpg` |
| Below threshold springs back | 150 px down (threshold ≈ 428 px) | stayed on the same photograph |
| …and is not a tap | same gesture | **the chrome was still up** afterwards |
| Above threshold dismisses | 1000 px down | returned to the feed |
| The sheet gates it | About open, 1250 px down from above the sheet | **sheet still open, still in the viewer** |
| …and un-gates | Back to close the sheet, then drag | dismissed |

⚠ Zero crashes across the pass. ⭐ The Back step also re-confirms §9.3b's nested `BackHandler`: one
press closed the sheet, the drag then dismissed the viewer.

⚠⚠ **Both halves of PH-15 now have both kinds of evidence, and they arrived in opposite orders** —
zoom was device-verified before it was testable, the dismiss testable before it was device-verified.
Neither order is better; what matters is that a gesture claim resting on only one of the two is a
claim with a known blind spot, and this table is what closes the dismiss half's.

### 10.4 Not in PH-15

- **Drag up to open the sheet** — ⚠ not a gap in this phase: §3.2 assigns it to the bottom sheet's
  own gesture rather than making it a fourth thing to arbitrate here. The drag handle is still
  decorative, and the direction is left unclaimed for it.
- **Dismissing from a magnified photograph.** Google lets a pull-down at the top edge of a zoomed
  photo dismiss; D4 says the drag belongs to the pan, full stop, and that is what shipped. A
  deliberate divergence, not an oversight.
- `MAX_ZOOM` is 5×, a product choice: past that a synced thumbnail is all upscale and the user is
  inspecting compression rather than their photograph. ⚠ The viewer loads `HERO_PX = 1024`, so on a
  large display the ceiling is reached before the pixels run out — the original is not fetched.

## 11. As built — the panel is part of the page (2026-08-31)

⚠⚠ **This supersedes §3.2's "sheet over the photograph" and retires §9.2's pager lock.** PH-10 built
About as a modal overlay reached from ⋯; the maintainer noticed on a device that Google Photos and
Apple's viewers behave differently, and driving Google Photos on the same phone settled what the
difference actually is.

### 11.1 ⭐ What the reference app does, measured rather than described

Google Photos was already installed on the test device, so this is observation, not recollection:

| Gesture | Result |
|---|---|
| Swipe **up** on the photograph | the info panel comes up |
| Swipe **down** with it up | back to the photograph |
| Swipe **down** on the photograph | dismissed to the grid |
| ⋯ → **About** | **exactly the same state as the drag** |
| Swipe **sideways** with it up | **pages, and the panel follows** |

⚠⚠ **The photograph does not shrink, and getting this wrong nearly cost a rebuild in the wrong
direction.** The first reading of the screenshots was "it scales down to a band at the top". The
maintainer disagreed, and the pixels settled it: on a portrait photograph the image spans y≈250→1810
full-screen and y≈0→615 with the panel up — the top edge moves to −945, a **pure translation of
~1195 px**, and what remains on screen is the *bottom* of the picture. A landscape photograph fooled
the eye because it is short enough to fit the band whole. ⚠ Same mechanism, different aspect ratio;
the honest test is a portrait photograph.

So it is not a sheet at all. It is **one scrolling column** — photograph at viewport height, details
below it — and "About" is a scroll offset. ⋯ → About is `animateScrollTo` to the same offset.

### 11.2 What that makes possible: the lock was the price of the wrong architecture

§9.2 called `userScrollEnabled = !state.infoOpen` *"the correctness rule the whole sheet-not-screen
decision rests on"*, because an overlay describing one photograph above a pager showing another puts
every field on the wrong picture. That reasoning was sound **for an overlay**. Once the details are
the page's own content, paging swaps photograph and details together and the staleness is
unrepresentable — so the lock has nothing left to protect and is gone.

⚠⚠ The guarantee moved rather than vanished: `about` is passed **only to the page that is current**.
A neighbour composed ahead by `beyondViewportPageCount` gets `null` and is simply a photograph until
`onPhotoShown` loads its own metadata. `onlyThePageOnScreenIsGivenItsDetails` is the test that
replaced the lock's, and it is the load-bearing one now — without it, page N's details would render
under page N+1's picture, the same bug moved rather than fixed.

### 11.3 ⚠⚠ Three things the tests caught that a device pass would have called something else

- **A nested `verticalScroll` is a crash, not a layout quirk.** `AboutSheet` kept its own scroll from
  its overlay days; inside the page's column that is *"measured with an infinity maximum height
  constraints"* and an exception. Fifteen tests went red at once and named it.
- **`assertDoesNotExist` had to become `assertIsNotDisplayed`.** Closed is now a scroll position, not
  an absence — the panel is composed all along. ⚠ The weaker-sounding assertion is the truer one: it
  describes what a reader sees, and holds under either architecture.
- **`useUnmergedTree` is not optional here.** Merged semantics resolve `onNodeWithText` to an
  ancestor whose bounds are the whole screen, so "is it displayed" silently becomes "is the page on
  screen" — which answers yes for a row that is a full viewport below the fold.

### 11.4 The panel's own changes

`fillMaxHeight(0.72f)` and `verticalScroll` are both gone (see above); the background is now **opaque
black** rather than `alpha = 0.92f`, because a little translucency read as depth on an overlay and as
a bright photograph bleeding through the text once the panel stood on its own — visible on a device
against a saturated picture. The drag handle stops being decorative: it now describes a gesture that
exists.

⚠ `setInfoOpen` replaces `toggleInfo` as the view model's entry point for the binding. The panel's
state and the scroll offset drive **each other**, and a toggle on the reporting side would close a
panel the user had just dragged open. Both directions are idempotent — `distinctUntilChanged` on the
flow, an early return in the setter — which is what stops the pair oscillating.

### 11.5 Falsified

| Break | Test that objects |
|---|---|
| Flag no longer drives the scroll | `openingScrollsThePanelIntoView` |
| Photograph no longer fills the viewport | `thePanelSitsBelowTheFoldUntilItIsOpened` |
| Every page gets the details | `onlyThePageOnScreenIsGivenItsDetails` |
| (inverted from §9.2) pager locked again | `thePagerStaysFreeWithThePanelOpen` |

### 11.6 ⚠⚠ The device pass, and the bug only it could find

On a OnePlus CPH2581. Every position of the stack behaves:

| Check | Result |
|---|---|
| Drag up | the panel scrolls in, opaque, with EXIF read from the file (`Taken 2026:08:31 15:50:20`) |
| **Page with the panel open** | **pages, and the panel follows with the new photograph's data** |
| Drag down from the panel | back to the photograph |
| Drag down from the photograph | dismissed to the feed |
| ⋯ → About | the same state as the drag |
| Crashes | none |

⚠⚠ **The bug: paging with the panel open closed it — and the culprit was the page being LEFT.**
As a page stops being current it loses its `about`, its column collapses, `maxValue` drops to
nothing, and its scroll→flag predicate flips `true → false`. That is a perfectly genuine later
emission: `distinctUntilChanged` passes it on and it closes the panel the user still has open.

⚠⚠ **The first diagnosis was wrong, and fixing it changed nothing.** The obvious suspect was the
*incoming* page reporting its fresh `0`; a `drop(1)` was added for it and the phone behaved exactly
as before. What settled it was a **probe rather than more reasoning**: the ⋯ label read "About"
instead of "Hide about", so the flag genuinely had been set false by *something* — and only two
pages could be talking. The fix is a gate, `isCurrent`, so only the page on screen may report at
all. (`drop(1)` stays: it is the right guard for the incoming page, just not the bug.)

⚠⚠ **No test catches this, and the guard that looks like it does, does not.**
`thePagerStaysFreeWithThePanelOpen` was extended to assert the panel is still displayed after
paging — and **removing the fix leaves it green**. The desktop harness does not reproduce the
timing. The assertion was kept because it states the contract, but it is not evidence: *this
behaviour is device-verified only*, and a green run says nothing about it.

### 11.7 Still open

- ~~The anchor is a guess.~~ **Measured — §11.9.**
- ~~No detent.~~ **Built in §11.9**: release settles to whichever end of the travel is nearer.
- The panel still renders under a photograph whose bytes are absent, where Google shows nothing.
- ~~The top bar is illegible over a bright photograph.~~ **Fixed — see §11.8.**
- ~~Our anchor leaves more of the photograph showing than Google's.~~ **Settled in §11.9** — the
  anchor is now a fraction of the *screen* (`PANEL_TOP_FRACTION = 0.31f`), measured off the
  reference app, and the photograph is positioned relative to it.

### 11.8 ⚠⚠ The scrim the KDoc had claimed for three phases (2026-08-31)

The device pass showed the bar's white title unreadable over a bright photograph — §11's scroll
makes it likelier, because it brings the brightest part of a picture up under the bar.

⚠⚠ **`ViewerTopBar`'s own KDoc already said it could not happen:** *"this one is transparent by
construction and legible because its contents are white with a scrim behind them."* The only
occurrence of the word "scrim" in the file was that sentence. **There was no scrim.** The comment
described an invariant nobody had built, and it read as reassurance for three phases — the same
shape as PH-2's *"two photos … cannot collide"* beside the code that collided them (§9.3c).

Now built: a `verticalGradient` from `Black.copy(alpha = 0.55f)` to transparent, **outside**
`windowInsetsPadding`. ⚠ The inset placement is the part worth stating — the photograph is
edge-to-edge and runs under the status bar, so a scrim clipped to the safe area would leave the
system clock and icons sitting on bare picture, which is exactly where it looks worst.

⚠ Google's answer to the same problem is different: it drops the title, star and ⋯ once the panel is
up and keeps only a back arrow on a circular scrim. A gradient was chosen instead because it fixes
the fullscreen case too — a bright photograph with no panel open had the same problem, and hiding
the title would not have touched it.

## 11.9 ⚠⚠ The photograph is positioned by the panel, not scrolled with it (2026-08-31)

The maintainer opened a **landscape** photograph and found the §11 build wrong: the picture was
clipped at the top and there was a screen of black between it and the panel. Their reading — *"the
image basically stays the same size, but due to it scrolling up you only see the bottom of it …
this really seems to be only the case for landscape pictures, to make sure we don't get a black
only preview"* — was the right instinct, and driving the reference app pinned the actual rule.

### 11.9.1 The rule, and the arithmetic that confirms it

**The panel's top edge rests at a fixed fraction of the screen (~31%), and the photograph is
translated so its BOTTOM edge meets it — never moving down.** In one line:

```kotlin
val heroShift = minOf(0f, letterbox - reveal)   // letterbox = (viewport - heroHeight) / 2
```

⚠⚠ **One rule, both aspect ratios, and it is falsifiable by prediction.** Measured on the reference
app against a portrait photograph: bottom edge at rest 1810, panel top 617, so the model says the
picture must travel **−1193 px**. Observed: **−1195**. For the landscape cat the same model says
−655, and the whole picture stays visible because it is only 532 tall.

So Google is **not** special-casing landscape, and it is not "hiding the top" as a separate
behaviour: clipping the top is simply what bottom-alignment *does* when the photograph is taller
than the space above the panel. While the panel is still rising through the black margin below a
letterboxed picture, the picture does not move at all — which is exactly the "moves down" the
maintainer described, relative to a naive constant scroll.

### 11.9.2 ⚠⚠ Why §11's scrolling column could not express this

§11 put `[box of viewport height, photograph centred inside it][panel]` in one `verticalScroll`.
The letterbox margin is **inside** that box, so the distance from the picture's bottom to the
panel's top is fixed at `(viewport − heroHeight) / 2` and **no scroll offset can close it**. A
landscape photograph therefore opened onto a screen of black — a clear regression against the
overlay it replaced, and the reason this was fixed before the §11 About panel shipped rather than after.

The two have to move at **different rates**, so there are now two derived offsets driven by one
`reveal` value, and no scroll container spanning both. The panel keeps its *own* scroll for content
taller than its space, and the two are reconciled in §11.10.

### 11.9.3 What else fell out

- ⭐ **The detent exists now.** Release settles `reveal` to whichever end of its travel is nearer,
  so a half-open panel is no longer a thing the screen can be left in. §11.7 had this as open.
- **`Claim.Dismiss` became `Claim.Vertical`.** One claim covers the whole axis: the drag closes the
  panel first, and what the reveal cannot absorb falls through to the dismiss — so one continuous
  downward drag walks the photograph back and then out, which is what the reference app does.

⚠ Device-verified on the landscape case (whole cat, panel at ~625, no gap — against Google's ~620).
The portrait case is guaranteed by the same arithmetic (`letterbox ≈ 0` ⇒ `heroShift = −reveal`)
but was **not** re-checked on the phone after this change.

## 11.10 ⚠⚠ The panel drags the panel — and a claim I made without testing it (2026-08-31)

§11.9 shipped with only the photograph draggable: a drag on the panel scrolled its content and
nothing else, so a panel filling three quarters of the screen could not be pulled shut from
anywhere a thumb naturally rests. The maintainer caught it immediately.

⚠⚠ **The code comment justifying that split read *"Simple, and it is what the reference app does
too."* It is not, and I never checked.** Driving Google Photos afterwards: dragging down on its
panel closes it. The simplification might still have been defensible; **the appeal to the reference
app was fabricated**, and it is the third comment in this file's history to assert an invariant
nobody had verified — after PH-2's *"two photos … cannot collide"* (§9.3c) and `ViewerTopBar`'s
scrim (§11.8). ⚠ The pattern is worth naming: a confident sentence in a comment is *evidence of
nothing*, including when it is mine and ten minutes old.

**Built as a `NestedScrollConnection`, because the two directions are not symmetrical:**

| Drag | Hook | Order |
|---|---|---|
| **Up** | `onPreScroll` | raise the panel first; its content scrolls only once it is fully up |
| **Down** | `onPostScroll` | the content scrolls back to its top first; only the remainder lowers the panel |

⚠ That ordering is the whole feel. Reversed, the panel would collapse under you while you were
still reading half way down a list of EXIF rows. `onPostFling` settles to the same detent the
photograph's own gesture loop uses, so a drag released half way does not leave it stranded.

⚠⚠ **Device-verified: dragging down on the panel closes it** — on a short panel and on the longest
one the app can currently produce.

⚠⚠ **The `onPreScroll` half is unreachable today, and that is a finding rather than a gap in the
testing.** The maintainer uploaded a full-EXIF camera capture specifically to exercise it
(2304 × 4096, place row, exposure, aperture, ISO, focal length, lens, bias, taken). It **still
fits**: the content ends around y≈1560 of a 2000-unit screen, so `panelScroll.maxValue` is `0`,
the child consumes nothing, and every drag falls through to the connection.

So the *ordering* between the panel's own scroll and the reveal — content scrolls back to its top
first, then the panel lowers — **cannot be exercised by any photograph in the library**. It is not
untested so much as not yet reachable.

⚠ It is still worth having, and it stops being theoretical the moment any of these lands:

- **PH-12's map** or **PH-16's caption** — either adds enough height to overflow.
- A **shorter screen** than a 1080 × 2376 phone.
- A **lower anchor** (`PANEL_TOP_FRACTION` closer to Google's, leaving the panel less room).

⚠⚠ Until then the `verticalScroll` on the panel is doing nothing, and the connection is carrying
the whole interaction on its own. **Whoever adds the first section that overflows owns checking
that ordering** — it is the one path here that has never run.

⭐ Incidentally the same capture confirms two things the panel had never shown on real data: the
**Place** row (the venue and its city, so the `:place-service` lookup resolves) and the top bar's place
subtitle (time · city).

