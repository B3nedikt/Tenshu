# Photos — the creation features: collage, animation, cinematic photo, highlight video

**Status:** v0.1 — **sweep, no code.** Opened 2026-09-07 from a maintainer request ("check the
creation features under Google Photos' `+` … I feel like the others are mostly relatively easy to
build with our existing/in-progress AI and model features — basically just another model we would
need to add and call differently"). §1 is measured on the device; §3 is the finding that answers the
request, and it should be read before the per-feature costing in §4.

⚠⚠ **§3 is the constraint and it inverts the framing of the request.** The model *is* now the cheap
part — IN-3b's backend is in production DI and a second model is a catalog row plus a `supports()`
branch. What is missing is a **media writer**: this repository can decode pixels on five platforms
and cannot encode anything but a single still image. Three of the four features output video or
animation, so the encoder — not the model — is the schedule.

⚠⚠ **The encoder is already specced and already unblocked, in another doc.**
[`photos-video-support.md`](photos-video-support.md) M4 (Android `MediaCodec` + `MediaMuxer`) exists
for motion thumbnails; M0 and M3 are done. Every video-shaped feature here is a *second consumer* of
M4, not a second build — which is the single most schedule-relevant fact in this document.

⚠ **Photo books, prints and the two "Get photos" entries are out of scope**, per the request.

**Owner:** Benedikt

**Builds on:** [`photos-app.md`](photos-app.md) (the app, the feed, `:photo-service`),
[`photos-video-support.md`](photos-video-support.md) (§4.4's per-platform generation table and M4 —
the dependency this whole doc turns on), [`inference-service.md`](inference-service.md) (the model
seam, IN-2's distribution, IN-3m's on-phone number), [`models.md`](../models.md) (the catalog, and
§2's "what each row must answer before it ships"),
[`drive-content-preview.md`](drive-content-preview.md) §5 (why desktop has no video decoder).

**House rules engaged:** #1 (§3.4 — why the server route is closed to us), #4 (a creation is a
schema type), #8, #9 (§7.2).

---

## 1. What is actually there — measured, not assumed

Driven on the maintainer's OnePlus **CPH2581**, Google Photos **7.90.0.971743778**, 2026-09-07, via
`uiautomator dump` + `screencap`. Every row below was walked to its output, not read off the menu.

The `+` sheet offers, under **Create new**: Album, Collage, Highlight video, Cinematic photo,
Animation, Photo books; under **Get photos**: Share with a partner, Import from other places.

⚠ **The `+` is the smaller of two surfaces.** The bottom navigation has a whole **Create tab**
carrying the same four tools as tiles plus a collage template gallery, and the home feed carries
**Creations** and **Featured memories** — the unprompted half. §5.3 argues that half is the cheaper
one and we should not let the `+` frame hide it.

### 1.1 Collage — a layout editor, and no model anywhere

Picker says **"Select up to 6 photos"**. Three selected → **Create** → a slot-grid editor:
`Templates`, `Borders`, a slot counter with `+`/`−` (opened at 3, with `Add slot` / `Remove slot`
accessibility actions), *"Tap a photo to edit or swap it out"*, plus *"Pinch a photo to zoom"* and
*"Long press and drag a photo to change its position"* as rotating hints. Output is a **still
image**, via `Save` or `Share`.

⭐ **Nothing here is inference.** It is compositing and a layout catalogue.

### 1.2 Highlight video — a real timeline editor behind a themed picker

Two tabs.

**Templates** — a carousel of themed presets, each with a target duration and a recommended item
count: *Birthday tribute* (0:21, < 17), *Party* (0:22, < 16), *Pet* (0:19, < 12), *Weekend photo
dump* (0:16, < 8), *Before and after* (0:18, < 12), *Wedding*, *Anniversary*, … *Trip recap* (0:18,
< 13), *Monthly recap*. Roughly nine to eleven.

**Select content** — a semantic picker: people (`Add B3nedikt`), labels (`Baking`, `Cars`,
`Christmas`, `Dancing`), places (`Munich`, `Paris`, `Koto City`), years (`2026`), months (`August
2026`), plus `Add a person` and `Add a date range`. Selecting one narrows the remaining suggestions.

⭐ **This tab is a search index with different chrome** — see §2.2.

`Munich` → **Preview** produced, in a few seconds, a **0:16** edit and dropped into a **timeline
video editor**: a visual track of image clips, an audio track with a music waveform, and a toolbar
of `Resize`, `Music`, `Text`, `Adjust`, `Filters`, `Audio`. `Save` renders it.

### 1.3 Cinematic photo — one still becomes a short video

Picker says **"Select up to 1 photo"**. One selected → **Create** → *"Creating your Cinematic
photo…"* with a `Cancel`. The result is a **0:07 video** with a scrubber and `Cancel` / `Save`: the
still under a slow simulated camera move.

⚠ **The wall time is not bounded by this experiment.** Create was tapped at ≈17:42 and the result
was on screen at 17:46, but the accessibility tree went **stale** once the video surface rendered —
the dump still read *"Creating your Cinematic photo…"* in the same sample whose screenshot showed the
finished clip. So "under ~4 minutes" is an upper bound and nothing tighter is available from these
dumps. ⚠ Whether the work is local or a server round-trip is **unmeasured** — see Q1 (§6).

### 1.4 Animation — the cheapest thing in the menu

Picker says **"Select 2 to 50 photos"**. Three selected → **Create** → done in **under 8 seconds**,
with **no editor, no options and no save step**: the result is already an item in the library, badged
`Animation`, with the ordinary `Share` / `Add to` / `Bin` chrome.

⚠ Whether the artifact is a GIF or a short MP4 was not determined; §4.2 costs both routes because the
answer changes ours, not theirs.

### 1.5 Album

A named collection. We have it — §2.1.

---

## 2. What we already have

### 2.1 ⭐ Album is shipped, and it is running on the same phone

Confirmed on device: our Photos → **Collections** shows `Favourites`, `Duplicates`, `Places & times`,
per-year sections and a **`New album`** action. `:photo-service` carries `Album`, `AlbumEntry`,
`PhotoFlags` and `TrashedPhoto` with `SaveAlbum` / `DeleteAlbum` / `AddToAlbum` / `RemoveFromAlbum` /
`SetPhotoFlags` (`photos-app.md` PH-2a/PH-2b).

⚠ The gap against Google's album is **sharing**, not creation — a collaborative album is
[`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) adapted to a collection, and
`photos-app.md` §7 already routes it there. It is not a creation feature and this doc does not claim
it.

### 2.2 The selection half of Highlight video is most of the way there

§1.2's *Select content* is people + labels + places + dates. We have places and dates on screen today
(`Places & times`), `MediaLabel` rows from IN-3a, the IN-4b vector sink and `photosLike`. People is
IN-6 (faces), which is off by default and Settings-only and ships with its own threat-model
amendment — so a first cut of this picker would offer three of Google's four axes and is honest doing
so.

### 2.3 ⭐ The inference substrate is real, and better than CLAUDE.md's row suggests

`OnnxInferenceBackend` is registered in **production DI**, not just tests — in both
`control-shared-proxy`'s `androidMain` and `desktopMain` `PlatformProxyModule.kt`, under
`single<List<InferenceBackend>>(named("inferenceBackends"))` — over the pinned SigLIP vision tower,
measured at **121 ms/image on this exact phone**
(`inference-service.md` §15.12, CPH2581, 562 images, zero failures). IN-2/IN-2b shipped the signed
catalog, the per-artifact digest gate, the durable store and the Control install screen.

⚠⚠ **But no device has ever run a model.** The registration's own comment: *"The store is empty on
every device today (no release key ⇒ no catalog ⇒ no downloads)"* — `supports()` is false while the
files are absent and `photosLike` answers `NO_BACKEND`. So **adding the second model is cheap;
delivering the first one is an unfinished operational step** (a release signing key and a published
catalog), and any plan here that says "just add a model" inherits that step.

⚠ The shipped backend claims **`SEMANTIC_IMAGE_SEARCH` only** — `CONTENT_TAGGING` is false until R3
lands the text tower and vocabulary.

### 2.4 Still-image encode already exists, per platform

`GetPreview` decodes → scales → **encodes** on every platform that has an implementation: Android
`Bitmap.compress` (JPEG / WEBP\_LOSSY / WEBP), JVM `ImageIO.write`, iOS via ImageIO. This is the
raw material for §4.1, and it is why a collage is the cheapest *image* feature.

---

## 3. ⚠⚠ The constraint: the missing primitive is a media writer, not a model

### 3.1 What the tree actually contains

| Capability | State |
|---|---|
| Decode a still, every platform | ✅ `decodeToImageBitmap` (`:ui-core`), four actuals |
| Encode a **single** still, per platform | ✅ §2.4, inside `GetPreview` |
| Decode a video frame | ⚠ Android + iOS only (`MediaMetadataRetriever` / `AVAssetImageGenerator`); **desktop none** |
| **Composite N images into one surface** | ❌ nothing — `decodeToImageBitmap` is one-way and has no `encode` twin |
| **Encode an animation** (GIF / animated WebP) | ❌ nothing |
| **Encode a video** | ❌ nothing — no `MediaCodec`, `MediaMuxer`, `AVAssetWriter` or ffmpeg anywhere |
| **Audio: assets, mixing, muxing** | ❌ nothing; no `composeResources` audio in any module |

The one place video encoding is mentioned is a comment declining it:
`JvmGetPreviewImplementation.kt` — *"Desktop/JVM has no built-in video frame decoder (FFmpeg is
out of scope — drive-content-preview.md §5)"*.

⚠ Citations here name a **file and a symbol, never a line**: the two `PlatformProxyModule.kt` line
numbers this doc originally carried both rotted inside a single afternoon, on an unrelated rebase,
and `photos-video-support.md` §4.4's own `JvmGetPreviewImplementation.kt:138` is already off by one.

### 3.2 ⭐ Why this is a much better position than it looks

The encoder gap has already been designed against. `photos-video-support.md` §4.4 costed generation
per platform for the motion-thumbnail rung, and that table is **exactly** the table this doc needs:

| Platform | Encode a clip | Verdict (that doc's) |
|---|---|---|
| **Android** | `MediaCodec` + `MediaMuxer`, framework-only | ✅ ~300 lines of a well-trodden recipe, **no new dependency** |
| **iOS** | `AVAssetExportSession` + `videoComposition` | ✅ near-trivial |
| **macOS desktop** | via the existing `:macos-helpers` FFM bridge that already drives `AVPlayer` | ⚠ deferred (M7) |
| **Windows/Linux desktop** | ❌ | ⚠ **mints nothing** |

And M0 (2026-09-05) already **measured** encoding on AVFoundation: bytes are `bitrate × duration ×
(1 + overhead)` with resolution, frame rate and content complexity all byte-neutral, so a size cap is
satisfiable by construction. M3 shipped the schema. **M4 is unblocked and on the roadmap already.**

⭐ **So the recommendation in §5 is not "build an encoder for the creation features". It is "M4 is
the shared dependency; do it once, and three features become cheap."**

### 3.3 ⚠⚠ The desktop hole costs us more than it costs Google

`photos-video-support.md` §4.4 rightly calls the Windows/Linux asymmetry *"not new and not a
regression"* — a video uploaded from a Windows desktop already gets no motion class. That reasoning
**does not transfer to a creation feature**, and this is the one place this doc disagrees with its
parent:

- A *motion thumbnail* is derived, per-device, re-derivable and invisible when absent.
- A *creation* is a *document the user made*. It is a realm row that syncs. A user who makes a
  collage on a phone and cannot make one on their desktop experiences a **missing feature**, not a
  degraded thumbnail — and one who is told the button exists only on some of their devices
  experiences the platform as unfinished.

⭐ This is the strongest argument for sequencing **Collage first**: it is the only feature here whose
output is a still image, so it works on **all five targets including web**, with no encoder at all.

### 3.4 House Rule #1 closes the escape hatch

Whatever Google does server-side for §1.3 is not available to us: the relay sees ciphertext only, so
there is no "render it in the cloud" tier. Every creation feature here is **on-device or absent** —
the same shape as `inference-service.md` IN-D11's decision for phone/desktop STT. That is a
constraint, and it is also why the depth model in §4.3 must be one we can actually run on a phone.

---

## 4. The four features, costed

⚠ Sizes are **relative** and stated as such; this project does not treat an estimate as a
measurement (`models.md` §2). Each row names what would have to be measured before it ships.

### 4.1 Collage — small/medium, no model, works everywhere

**What it needs.** An offscreen composite: draw N decoded `ImageBitmap`s into slot rectangles on one
surface, then encode. That is one new platform seam — `encodeToBytes`, the exact mirror of
`decodeToImageBitmap` — plus a layout-template model, a slot editor, and `UploadFile`.

⭐ **The seam is nearly free on three of five targets.** Skia is already on the classpath for desktop
and iOS (`ui-core/src/iosMain/.../ImageDecode.ios.kt` imports `org.jetbrains.skia.Image`), and
`drive-shared-ui`'s desktop tests already round-trip a `Surface` to PNG via `EncodedImageFormat`.
Android has `Bitmap` + `Canvas`. Web is the one to check.

**Cost:** weeks, and mostly UI — the editor is the work, not the pixels.
**Risk:** low. **Model:** none. **Platforms:** all five.

### 4.2 Animation — small, *after* M4

Two routes, and the choice is ours regardless of what Google emits:

- **Reuse M4.** An animation is the motion-thumbnail encoder with a different frame source — N
  photographs instead of N decimated frames of one video. If M4 exists, this is close to free on
  Android and near-trivial on iOS. It inherits M4's desktop hole.
- **A GIF encoder in `commonMain`.** LZW, no platform seam, works on desktop and web too. Worse
  bytes-per-quality by a wide margin (`photos-video-support.md` §4.3 measured the analogous
  trade for sprite sheets), but it is the only route that covers all five targets.

⚠ These are not exclusive: the second is the honest fallback on a platform that cannot do the first,
and §4.3's argument about creations-that-sync applies here too.

**Cost:** small, and dominated entirely by whether M4 has landed.
**Risk:** low. **Model:** none.

### 4.3 Cinematic photo — medium; the model half is genuinely the easy half

This is the feature the maintainer's framing fits best, and it fits.

**The model.** Monocular depth estimation — one inference per photograph, not a batch pass. It drops
into the shipped machinery as: one `models.json` entry, one `InferenceCapability` value, one
`supports()` branch, one backend method. On a phone that runs SigLIP at 121 ms this is well within
budget.

⚠ **Candidate and size are unverified.** Depth Anything V2 (small) is the obvious first candidate,
but its export size, quantised accuracy and licence must clear `models.md` §2 before it is a plan —
and IN-3m is the precedent that a size class is a measurement, not an assumption. ⚖️ Licence is a
gate, not a footnote: the MobileCLIP branch is the one that turned an engineering track into a
commercial negotiation, and it was closed by measuring rather than by hoping.

**The render.** A 2.5D mesh warp along a slow camera path — a shader, not research.

**The output is a video**, so it inherits M4.

**Cost:** medium. Model + render is the smaller half; the encoder is the schedule.
**Risk:** medium — concentrated in the depth model's licence and quality, not in the code.

### 4.4 Highlight video — a different order of magnitude; defer it

Selection is nearly free (§2.2). Everything after it is not: a timeline document model, transitions,
per-clip Ken Burns, text overlays, filters, a music bed, and then muxing video **with audio**. Two
things make it worse than the sum of its parts:

- ⚖️ **Music is a licensing problem before it is an engineering one.** Google ships a licensed
  library. Our options are redistributable production music (CC0 or equivalent, cleared), or
  silent-by-default with a user-supplied track. That is a decision to take *before* code, and it is
  the kind this project routes to counsel.
- ⚠⚠ **Desktop cannot generate it at all**, and §3.3 says why that matters more for a creation than
  for a thumbnail. A highlight video is also the most *effortful* creation in the list — the one a
  user is least willing to have stranded on one device.

**Cost:** months. **Risk:** high, and mostly non-technical.

---

## 5. Decisions — proposed, none settled

| # | Decision | State |
|---|---|---|
| **CR-D1** | **The blocker is the media writer, not the model.** Any plan that sequences these features by model difficulty is sequencing on the wrong axis | proposed (§3) |
| **CR-D2** | **M4 is the shared dependency and is done once**, for motion thumbnails; Animation and Cinematic photo are second consumers of it, not second builds | proposed (§3.2) |
| **CR-D3** | **Collage ships first** — no model, no encoder, all five platforms | proposed (§3.3, §4.1) |
| **CR-D4** | **A creation is a synced document, so a platform that cannot make one has a missing feature, not a degraded derivative.** ⚠ This amends `photos-video-support.md` §4.4's "not new and not a regression" for *created* content only | proposed (§3.3) |
| **CR-D5** | **On-device or absent** — HR #1 forbids a server render tier, so a depth model that cannot run on a mid-range phone is not a candidate | proposed (§3.4) |
| **CR-D6** | **Highlight video is deferred until its music licensing has an answer**, not designed around | proposed (§4.4) |
| **CR-D7** | `encodeToBytes` lands in **`:ui-core`** beside `decodeToImageBitmap`, as one seam with per-platform actuals — not a new module | proposed (§4.1) |

---

## 6. Open questions

- **Q1 — Is Google's cinematic photo local or a server round-trip?** Unmeasured (§1.3). ⚠ It does
  **not** gate us — HR #1 means our answer is on-device either way — so this is curiosity, not a
  blocker. The experiment is one airplane-mode run, and it was not taken because it disrupts the
  maintainer's phone for a fact that changes nothing here.
- **Q2 — What does Google's Animation actually emit, GIF or MP4?** §4.2 costs both; the answer
  informs nothing but a sanity check.
- **Q3 — Which depth model, and does it clear `models.md` §2?** The real gate on §4.3.
- **Q4 — Does the web target have an `encodeToBytes`?** §4.1 assumes a canvas encode exists; it was
  not checked, and CR-D3's "all five platforms" claim depends on it.
- **Q5 — Where does a creation live?** A collage is a new image `File`; is it *also* a first-class
  row (so it can be re-edited, and so `Creations` in §5.3 has something to list), or just a
  photograph like any other? This is a schema question and HR #4 says it is answered before code.
- **Q6 — Is the unprompted half worth more than the `+` half?** §1 notes Google's Create is a whole
  tab and that Creations/Featured memories arrive unasked. The still-image kinds of that need **no
  encoder and no model** beyond what §2.2 already has. ⭐ This may be the highest-value item in this
  document and it is deliberately left as a question rather than smuggled in as a phase.

---

## 7. Phases

⚠ Nothing here is scheduled; this is the order the sweep argues for, not a commitment.

| # | Phase | Depends on | Notes |
|---|---|---|---|
| **CR-0** | Answer Q3 (depth model) and Q4 (web encode) | — | ⚠ Both are cheap and both can return *"not this way"* |
| **CR-1** | `encodeToBytes` in `:ui-core` + the collage compositor | Q4 | no model, no encoder |
| **CR-2** | Collage: templates, slot editor, save | CR-1 | the UI is the work |
| **CR-3** | Animation | `photos-video-support.md` **M4**, or the `commonMain` GIF fallback | ⭐ near-free once M4 lands |
| **CR-4** | Cinematic photo: depth capability + 2.5D render | CR-0/Q3, M4 | ⚖️ licence gates it |
| **CR-5** | Highlight video | ⚖️ music licensing, desktop generation (M7) | deferred; design only |

### 7.1 What this track does **not** own

The encoder. `photos-video-support.md` owns M4/M6/M7 and should keep owning them — this doc is a
consumer stating why that track's schedule matters beyond motion thumbnails.

### 7.2 House rules any PR here carries

- **HR #4** — a creation is cross-platform data; Q5 is answered in the schema, not in an app.
- **HR #9** — if Q5 adds or reshapes a stored/wire type, the owning service's version bumps and its
  `SchemaMigration` ships in the same PR; regenerate `SchemaShapeBaseline`.
- **HR #8** — Photos is in the smoke gate's built set; anything touching `androidMain` runs it.
- **HR #2** — none of §4 adds an asset class (a creation is synced ciphertext like any other row).
  ⚠ IN-6 (faces) would, and §2.2 deliberately scopes the picker to three axes to avoid pulling it in.
