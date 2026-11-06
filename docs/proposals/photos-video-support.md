# Photos — videos: a motion preview class, a single rotating player, and the viewer

**Status:** v0.4 — **M0, M1, M3, M4 and M5 shipped (M4 device-verified §14.9; M5 device-verified §15.9 — D22/D23 came out of that pass); M2 and M6–M8 open.** ⚠ Q4 is still owed. Opened 2026-09-04 from a
maintainer request ("Photos should also support videos … a preview like Google Photos' looping
thumbnail"). Decisions D1–D8 are proposed and **D1/D2/D5 are settled by the maintainer** (§7);
D10–D17 are M4's (§14.8), D18–D21 are M5's (§15.8). ⚠⚠ **M5 (§15) is the rotation and the tile
player, built 2026-09-09 in a container with no device — the grid has never been seen to move.** ⭐ **M4 (§14) ran on a device 2026-09-09 (§14.9)** — it works, and the pass
found three defects, all fixed: the byte headroom sat below the encoder's own spread so D13's retry
was routine, a fragmented MP4 got no class at all, and §12.6's 1.0 s clip missed the floor by 14.6 ms
because the floor was applied to the nominal window. ⚠ Still owed: §12.5's PSNR table on
`MediaCodec`, and 4K / 10-bit sources. ⚠⚠ **§3 is the constraint that shapes
everything and should be read first:** House Rule #1 forbids the mechanism Google actually uses, so
"do what Google Photos does" is only reachable on a device that already holds the bytes — which is
never the device the feature is *for*. ⚠⚠ **§12 is M0, and it falsified §4.5's model**: only *duration* and *bitrate* cost bytes —
resolution, frame rate and content complexity are all byte-neutral, because the encoder is
bitrate-targeted. So the cap is satisfiable by construction and D5 is restated at §12.4.
⭐ **The cap is LOCKED at §12.5 — `96 * 1024`**, half what §4.5 estimated, from seven real sources
spanning a 67× complexity range and an objective PSNR curve. ⚠ AVFoundation only; M4 re-runs it on
`MediaCodec`. ⚠⚠ **§11 is an amendment that falsifies this doc's own §2**: the `0:06` badge was *not*
"free" — every Photos read filtered `category equalTo FileCategory.Image`, so no video ever reached
a tile and the badge was inert. Admitting videos needed a fourth core predicate (`oneOf`), because
the chain DSL had no way to say "either of these". Read §11 before §2.

**Owner:** Benedikt

**Amends:** [`drive-synced-thumbnails.md`](drive-synced-thumbnails.md) — adds a fourth rung
(`MOTION`) to the thumbnail-class ladder defined there (§10.2) and extended by its §12. That doc
remains the ladder's home; this one owns the rung and its consumer.
**Builds on:** [`drive-content-preview.md`](drive-content-preview.md) §5 (poster frames + playback),
[`drive-streaming-preview.md`](drive-streaming-preview.md) S1–S7 (the seekable decrypt source),
[`photos-app.md`](photos-app.md) (the feed, the tile, the viewer).
**House rules engaged:** #1 (§3), #2 (§9.1), #8 (§9.3), #9 (§9.2).

---

## 1. What Google Photos actually does — measured, not assumed

Both experiments were run on the maintainer's OnePlus (CPH2581) against the installed Google Photos,
2026-09-04, on the same library whose videos are also in the realm.

### 1.1 The tile plays the real video

Six back-to-back `screencap`s of one 0:06 tile showed **entirely different scenes** (a bag strap,
then a train floor) — motion, not a crossfade. A 14-frame capture at ~1.45 s intervals put frames
1, 5 and 9 at visually the same point in the clip: a **~5.8 s period** against a 0:06 duration.

⭐ **So it is not a new thumbnail format.** The poster still and the `0:06` badge are the thumbnail;
the motion is an ordinary player attached to the visible cell, playing the file itself.

### 1.2 One player, rotating — and the "loop" is a special case of it

With three videos in one row (0:05, 0:04, 0:06), 26 timestamped frames were cropped per tile and
consecutive crops compared; a byte-identical crop means that tile did not move.

| frame | t (s) | tile 1 (0:05) | tile 2 (0:04) | tile 3 (0:06) |
|---:|---:|:--:|:--:|:--:|
| 02 | 1.28 | – | – | **moving** |
| 05 | 5.25 | **moving** | – | **moving** |
| 06 | 6.56 | **moving** | – | – |
| 09 | 10.57 | **moving** | **moving** | – |
| 10 | 11.87 | – | **moving** | – |
| 12 | 14.54 | – | **moving** | **moving** |
| 13 | 15.87 | – | – | **moving** |
| 17 | 21.18 | **moving** | – | – |

Exactly one tile moves at a time. The order is document order, wrapping: **3 → 1 → 2 → 3 → 1 → 2**.
Each turn lasted ~5.2 s / ~5.3 s / ~4.0 s / ~6.6 s / ~4.1 s / ~5.4 s against clip durations of
6 / 5 / 4 s — i.e. **each tile plays its clip through once and hands over** (±1.3 s, the sample
interval). Full cycle measured 13.3 s and 16.2 s against a predicted 5+4+6 = 15 s.

⭐ **This unifies both experiments under one rule, and it is the rule to implement:**

> One player. It plays a visible video tile through once, then hands over to the next visible video
> tile in document order, wrapping. **With one video on screen, "next" is itself — which is why
> §1.1 looked like a loop.** Looping is not a separate behaviour.

### 1.3 Why the rotation is the right shape (the maintainer's question)

It is not only an aesthetic choice; it buys four things, and the last is the one that matters most
for *us*:

1. **One decoder session, ever.** Concurrent `MediaCodec` instances are a limited, device-dependent
   resource; a grid that allocates a player per visible video is where media apps fall over. A
   rotation never allocates a second.
2. **Cost independent of how many videos are in view** — decode buffers and memory do not scale
   with the grid.
3. **No churn on a fling.** The player binds to a *rotation slot*, not to a tile's lifecycle, so
   tiles recycling under a fling never create or destroy a player.
4. ⚠⚠ **For us: at most one preview blob in flight.** Photos already learned this lesson the
   expensive way — `PreviewCache` exists because tile-lifecycle-bound fetching measured **17 loads
   for 10 distinct photographs** (`photos-app.md` PH-G9). A player per tile would repeat that
   mistake with a payload one to two orders of magnitude larger. The rotation makes the grid's
   video traffic a constant instead of a function of library composition.

It also makes "which video is interesting enough to animate?" a non-question — every one gets a
turn.

---

## 2. What already exists — more than the gap suggests

| Piece | Where | State |
|---|---|---|
| `FileCategory.Video`, `Photo.isVideo` | `File.kt`, `photos-shared-logic/.../Photo.kt` | ✅ derived on upload, carried in the feed model |
| Play glyph on the tile | `PhotoTile.kt:131` | ✅ already drawn, bottom-end |
| **`File.DurationMs`** | `File.kt:314`, set at `UploadFileImplementation.kt:295`, registered `FileServiceCommonModule.kt:688` | ✅ **stored, synced, and already read by Photos** (`TenshuPhotosRepository.kt:1046`) |
| **Video poster frames — Android** | `AndroidGetPreviewImplementation.extractVideoPoster` | ✅ frame ~1 s in (nearest keyframe) via `MediaMetadataRetriever` over an in-memory `MediaDataSource`, then the image scale/encode path |
| **Video poster frames — iOS** | `IosGetPreviewImplementation.extractVideoPoster` | ✅ `AVAssetImageGenerator`, the Android twin |
| Video poster frames — JVM/desktop | `JvmGetPreviewImplementation.kt:138` | ⚠ **cannot generate** (no frame decoder); *serves* a poster synced from a phone |
| Thumbnail-class ladder | `GetStoredThumbnail` `CLASS_LIST`/`LARGE`/`XLARGE`; `File.Thumbnail`/`ThumbnailLarge`/`ThumbnailXLarge` | ✅ the extension point; all `dynamicProperty(Syncable(OnDemand))` on the content plane since C7 |
| Player seam + streaming | `drive-shared-ui/.../VideoPlayback.kt`, `VideoPreviewScreen`, `VideoPreviewViewModel`; S1–S7 (`FileChunkDecryptor`, `StreamingContentSession`, `OpenContentStream`/`ReadContentRange`, Android proxy fd + `StreamingVideoProvider`, iOS `AVAssetResourceLoaderDelegate`, macOS `AVPlayer` over FFM) | ✅ built — ⚠⚠ **all of it inside `drive-shared-*`** (§6) |
| Photos viewer video playback | `PhotoViewerScreen.kt:130` | ❌ its own KDoc: *"⚠ Still not here: video playback"* |

⚠ **A stale claim corrected.** `drive-content-preview.md` §5 says *"iOS poster generation
(`AVAssetImageGenerator`) is deferred (no `iosMain` GetPreview yet)"*. That has not been true for
some time — `IosGetPreviewImplementation.kt` exists and generates image, PDF **and video** previews.
Two of three generating platforms can already pull frames out of a video, which is what makes §4.4
cheap. That doc's §5 should be corrected when this work lands.

⭐ **The `0:06` badge is free.** `File.DurationMs` is stored, synced and already projected into
Photos. Rendering it on the tile is a `PhotoTile` change and nothing else — no schema, no bytes, no
encoder. It is deliberately phased first (M1) so the tile stops under-describing videos long before
any of the expensive work lands.

---

## 3. ⚠⚠ The constraint: Google's mechanism is closed to us

Google Photos can "just play the file" for an item whose bytes are not on the device because its
**servers hold low-resolution transcoded renditions** of every upload. A grid tile streams a few
hundred kilobytes of a purpose-made rendition, never the original.

**House Rule #1 forecloses that permanently.** `:sync-relay-server` sees ciphertext only; no
server-side process can decode, transcode or even inspect a video. There is no rendition to stream
and there never will be.

That leaves the mechanism only where the bytes already are:

| Device | Bytes present? | "Play the file" |
|---|---|---|
| The phone that shot the video | yes | works, and is what Google does |
| Any other device the realm is on | **no** (`File.Data` is `OnDemand`) | would fetch the **whole original** to animate a ~110 px square |

⚠⚠ The second row is the feature's entire purpose. A user opens Photos on their desktop or second
phone precisely to see a library whose bytes live elsewhere; a design that animates only on the
capture device animates on the one device where the user has the least need of it.

Two candidates survive, and one of them is what the ladder was built for:

- **B — stream the original through S1–S7.** Bounded memory, seekable, already built, and correct.
  But it pulls real chunks of a full-resolution video per tile and decodes them to paint a
  thumbnail. Acceptable for a single tile the user has chosen to open; ruinous as a grid default.
- **C — a new `MOTION` thumbnail class.** A small, purpose-made derivative minted once by the device
  that holds the bytes and delivered like every other class.

⭐ **C, and `drive-synced-thumbnails.md` §12.1 already made the argument in general form:** *a
derivative's weight is set by its own dimensions, not the source's.* A 3 s, 256 px clip costs about
the same whether the source is a 6 s phone clip or a twenty-minute 4K recording. B degrades without
bound as cameras grow; C does not. This is the same reasoning that produced `XLARGE`, applied to the
time axis.

**Recommendation: C for the grid, B (via the existing Drive stack) for the viewer.** They are not
rivals — C is a rung, B is the ladder's floor, exactly as §12.3 frames it.

---

## 4. The `MOTION` class

### 4.1 The rung

| Class | Size | Encode | Sync | Eviction |
|---|---|---|---|---|
| `LIST` | 100 px | JPEG | OnDemand (content plane) | never |
| `LARGE` | 256 px | JPEG | OnDemand | never |
| `XLARGE` | 1024 px | JPEG | OnDemand | evictable |
| **`MOTION`** | **256 px, 3 s** | **H.264 / MP4, no audio** | **OnDemand** | **evictable, coldest-first — no class priority** |

- New handle `File.ThumbnailMotion`, a `dynamicProperty(Syncable(OnDemand))` resolving through
  `getStoredThumbnail(GetStoredThumbnail.CLASS_MOTION)` — structurally identical to
  `File.ThumbnailXLarge`, which is the template to copy.
- `CLASS_MOTION = "motion"` on the existing `GetStoredThumbnail` op. **No new operation.**
- ⚠ Minted **only** for `FileCategory.Video`. Every other category never has one, and its absence is
  the normal case rather than a failure.
- ⚠⚠ **Not part of `GetPreview`'s ladder.** `GetPreview` answers "give me a bitmap", and a clip is
  not a bitmap. `MOTION` is fetched by its own explicit read (§5.3), and a video's *still* preview
  continues to resolve through the existing three rungs exactly as it does today. Folding motion
  into `GetPreview` would put a video decoder behind a property every list row touches.

### 4.2 ⭐ Where the window comes from — align it with the poster

The clip starts at **the same offset the poster frame is taken from** — ~1 s in, nearest keyframe,
the offset `extractVideoPoster` already computes on both generating platforms — and runs 3 s.

Two things fall out, and the first is the reason to do it:

- **The loop's first frame is (nearly) the still already on screen**, so the hand-over from static
  poster to motion has no visual pop, and the hand-*back* at the end of the turn returns to the
  frame it started from.
- The offset logic is **already written and already tuned** (it exists to avoid a black or
  lens-capped opening frame). No new heuristic.

⚠ For a clip shorter than the window, take from the poster offset to the end; for one shorter than
the offset itself, take the whole clip. The class simply carries less than 3 s, and the rotation's
"play it through once" rule needs no special case.

**Audio is stripped, always.** A grid must never make noise, and it saves bytes for nothing.

### 4.3 D3 — why a video codec and not an animated image

The tempting alternative is a **sprite sheet** (N frames tiled into one JPEG, drawn as sub-rects on
a timer). It is genuinely attractive on one axis: the consumer needs **no codec on any platform**,
which would close the desktop and web gaps in §4.4 for free, and it would reuse frame extraction
rather than introducing an encoder.

It loses on quality-per-byte, decisively and for a structural reason: **a sprite sheet spends bytes
on (resolution × frame count) with no inter-frame prediction**, which is the exact thing a video
codec exists to avoid. At an equal ~150 KB budget the rough shapes are:

| | frames | per-frame px | effective fps over 3 s |
|---|---:|---:|---:|
| H.264, 256 px | ~36 | 256 | 12 |
| JPEG sprite sheet | ~16 | ~176 | 5.3 |

**~2× the frames at ~1.5× the resolution, for the same bytes.** Optimising the payload for
desktop/web portability at that cost is the wrong trade when Android is the primary surface, macOS
desktop already has a player (§4.4), and Photos on web does not exist yet.

**Decision: H.264/MP4.** ⚠ The sprite sheet is recorded here rather than dismissed — it is the
escape hatch if M0 finds the Android encoder work disproportionate, and it is the *better* answer
should Photos-on-web ever want motion tiles.

### 4.4 Generation — per platform, and where the holes are

⚠⚠ **All of this is proxy-side** (`drive-synced-thumbnails.md` §12.4): Control's `ProxyService` on
Android, the packaged Control daemon on desktop, and the in-process runtime on iOS route-1. **An app
never touches video.** It rides `UploadFileImplementation.renderThumbnail`, beside the three
existing classes.

| Platform | Extract frames | Encode the clip | Verdict |
|---|---|---|---|
| **Android** | ✅ already — `MediaMetadataRetriever` | `MediaCodec` + `MediaMuxer`, framework-only | ✅ the real work; ~300 lines of a well-trodden recipe, **no new dependency** (consistent with choosing `VideoView` over Media3) |
| **iOS** | ✅ already — `AVAssetImageGenerator` | `AVAssetExportSession` + `videoComposition` + `timeRange` | ✅ near-trivial |
| **macOS desktop** | ❌ JVM has no frame decoder | possible via the `:macos-helpers` FFM bridge that already drives `AVPlayer` | ⚠ deferred (M7) — not on the critical path |
| **Windows/Linux desktop** | ❌ | ❌ | ⚠ **mints nothing**; *serves* a class synced from a phone |

⚠ This asymmetry is **not new and not a regression** — it is exactly the poster-frame situation
today (`JvmGetPreviewImplementation.kt:138` serves posters it cannot generate). A video uploaded
from a Windows desktop gets no motion class and its tile stays a static poster everywhere, which is
the current behaviour for every video, everywhere.

**Playback of the class**, which is a separate question from generating it:

| Platform | Plays a 256 px H.264 MP4 |
|---|---|
| Android | ✅ `MediaPlayer`/`VideoView`, already wired |
| iOS | ✅ `AVPlayer` (⚠ no `:photos-ios` yet — §8 M8) |
| macOS desktop | ✅ `MacVideoPlayer` over FFM, already built |
| Windows/Linux desktop | ❌ static poster, honestly |
| Web | ✅ `<video>` — if Photos ever ships there |

### 4.5 ⚠⚠ The constants are estimates — M0 replaced them (§12)

> ⚠⚠ **Superseded by §12.** M0 ran on 2026-09-05 and falsified this section's *model*, not
> just its numbers: bytes are set by duration and bitrate alone. Read §12 first; what
> follows is kept because it records the reasoning the measurement overturned.

Calibrated against the ladder's real caps (`UploadFileImplementation`):
`MAX_THUMBNAIL_BYTES` 64 KB · `MAX_LARGE_THUMBNAIL_BYTES` 48 KB · `MAX_XLARGE_THUMBNAIL_BYTES`
256 KB (measured typical ~139 KB).

| Parameter | Proposed | Reasoning |
|---|---|---|
| Longest edge | **256 px** | the `LARGE` rung's size — the resolution a grid tile already renders at on a byte-less device |
| Duration | **3.0 s** | under ~2 s reads as a stutter when it loops (§1.2's single-video case); over ~4 s the byte cap forces a bitrate at which 256 px visibly smears on motion — and motion is the point |
| Frame rate | **12 fps** | 36 frames; enough to read an action at this size |
| Target bitrate | **400 kbps** | generous at 256 px; 3 s × 400 kbps ≈ 150 KB |
| **Hard cap** | **`MAX_MOTION_THUMBNAIL_BYTES` = 192 KB** | below `XLARGE`'s 256 KB, so ⭐ **a video's motion preview costs less than a photo's hero still** |

**Cap beats duration, and the existing drop rule is the backstop.** The encoder targets
`(cap × 8) / duration`; if the muxed result still exceeds the cap, retry once at a 2 s window; if it
*still* exceeds it, **do not store the class** — precisely the
`if (thumb != null && thumb.size <= MAX_THUMBNAIL_BYTES)` pattern the other three rungs use. The
tile falls back to its static poster. An over-cap blob is never stored.

⚠⚠ **Every number above is an estimate from first principles, not a measurement**, and this project
has a standing rule about building on an unmeasured premise. **M0 encodes a spread of real videos
from the maintainer's realm** — short handheld clips, a long recording, a low-light one, a
high-motion one — at 2/3/4 s × 192/256 px, records actual bytes and looks at them, and the
constants are locked from that table. This mirrors `drive-synced-thumbnails.md` §12.1, whose
measured ladder is what made `XLARGE` obviously right.

---

## 5. The grid — one rotating player

### 5.1 The rule (from §1.2)

One player instance for the whole feed. It plays one visible video tile through once, then hands
over to the next visible video tile in document order, wrapping.

- ⚠ **Only tiles whose `MOTION` blob is already in hand are in the rotation.** Never hand the player
  to a tile that would then have to fetch — that is what turns a rotation into a stall.
- ⚠ A video with no motion class (not generated, over cap, uploaded before this ships) is simply not
  a rotation member. Its tile is a static poster forever, which is today's behaviour.
- ⚠ Zero eligible tiles ⇒ nothing animates and the grid is exactly what it is today. The feature is
  **additive by construction**; there is no state in which it makes the grid worse.
- The static poster stays underneath at all times. The player draws over it and is removed on
  hand-over, so a tile between turns is never blank — the §4.2 alignment is what makes that
  invisible.

### 5.2 Ownership

The rotation is a small piece of **feed state**, not tile state — a tile cannot know what the next
tile is, and a tile-owned player would re-create the PH-G9 flood in a more expensive form (§1.3).
`PhotosFeedViewModel` (or a `MotionRotation` helper beside it) holds: the visible video rows, the
current index, and the single fetch slot.

⚠⚠ **The `previewsEnabled` precedent applies directly.** `PhotoTile` already takes a *flag* rather
than a swapped loader, because swapping the loader left tiles permanently blank when the
`produceState` key did not change (`PhotoTile.kt`'s KDoc — a shipped bug). The motion seam must be
the same shape: the tile takes `motionSource: MotionSource?` as a **keyed input**, never a lambda
that silently returns nothing.

### 5.3 Fetching

⚠⚠ **`MOTION` must not join the feed's bulk thumbnail pull.** `TenshuPhotosRepository.cacheThumbnails`
today pulls `File.Thumbnail, File.ThumbnailLarge` for a tile that scrolls into view. Adding
`ThumbnailMotion` there would fetch a clip for every video the user ever scrolls past — the exact
opposite of §1.3's point.

Instead: **one blob in flight, pipelined with the rotation.** While tile *N* plays (~3 s), fetch
tile *N+1*'s blob; on hand-over it is already there. A miss is not an error — that tile is skipped
this cycle and picked up on the next. `PreviewCache`'s rule carries over verbatim: **a `null` is
never remembered**, because on a byte-less device it means "has not arrived yet".

⚠ The clips are held in a small dedicated bounded cache (~3–4 entries, bytes-bounded like
`PreviewCache`), **separate from `PreviewCache`** — mixing a 150 KB clip into a cache tuned for
4 KB thumbnails would evict the grid's stills to hold one video.

### 5.4 D5 — autoplay is on, with one switch and no conditions

**Settled by the maintainer:** on by default, and **not** gated on Wi-Fi or battery saver.

The reasoning is ours rather than Google's, and it only holds because of the rotation: **at most one
~150 KB blob is in flight at any moment, regardless of library size or how many videos are on
screen.** That is a bounded, small, user-visible cost — roughly one hero still every three seconds
while the user is actually looking at a grid of videos. A network-condition matrix would be
formalising a knob around a cost that does not vary.

One boolean in the existing Photos Settings screen (`photos-app.md` PH-4a) — *"Play videos in the
grid"* — for users who simply dislike motion. ⚠ Not a policy matrix, and explicitly not a
per-network or per-battery rule.

---

## 6. The viewer — reuse Drive, but it is a lift, not an import

The maintainer's instinct is right and needs one correction: `photos-shared-ui` does **not** depend
on `drive-shared-ui`, and the player seam is not free-standing —
`drive-shared-ui/.../common/VideoPlayback.kt` references `app.tenshu.drive.data.StreamingContentHandle`,
which lives in `drive-shared-logic`, as does `FilesRepository.openStreamingContent`.

Nothing in either is Drive-specific; they are per-platform player plumbing and a client-side handle
over platform ops (`OpenContentStream`/`ReadContentRange`/`CloseContentStream`) that ended up in an
app module because Drive was the only consumer. The move:

| Moves | To | Why |
|---|---|---|
| `isVideoPlaybackSupported()`, `VideoPlayer`, `VideoPlaybackState`, `VideoSource` | `:ui-core` | Compose player seam; Photos already depends on `:ui-core` |
| `StreamingContentHandle` + `openStreamingContent` | beside the ops they wrap (a shared client module) | it is a client-side wrapper over platform ops, not Drive data |

⚠ Drive keeps `VideoPreviewScreen` / `VideoPreviewViewModel` — those *are* Drive's screens. Photos
gets its own viewer branch reusing the moved seam. ⚠⚠ Widening a seam across modules means
**compiling every implementor**, including the `androidMain`/`desktopMain` actuals and the four
test fakes that implement `openStreamingContent`.

⭐ **The tile player is not this player.** The grid needs muted, looping, chrome-free, no controller,
no spinner, no error surface — a much smaller composable over the same platform primitive. Do not
try to make one composable serve both; the viewer's `MediaController` and `VideoPlaybackState`
spinner are wrong in a 110 px cell.

---

## 7. Decisions

| # | Decision | State |
|---|---|---|
| **D1** | **One player, rotating in document order, wrapping; each tile plays through once. Looping is the one-video degenerate case.** | ⭐ **SETTLED** — maintainer-observed, measured §1.2 |
| **D2** | **Autoplay on by default; one Settings boolean; no network or battery conditions.** | ⭐ **SETTLED** (§5.4) |
| **D3** | Payload is **H.264/MP4, no audio**, not an animated image or sprite sheet | proposed (§4.3); sprite sheet kept as the escape hatch |
| **D4** | The clip window **starts at the poster's offset**, so the loop's first frame is the still already on screen | proposed (§4.2) |
| **D5** | **3 s, 256 px, 12 fps, cap `96 * 1024`**; bitrate **derived from the cap**, not chosen | ⭐ **MEASURED and LOCKED — §12.4 + §12.5.** ⚠ AVFoundation; M4 *ships against it* (§14.5) but the `MediaCodec` re-run is still owed (§14.7) |
| **D6** | `MOTION` is a ladder rung on `GetStoredThumbnail`, **not** part of `GetPreview` | proposed (§4.1) |
| **D7** | Fetched **one blob at a time, pipelined with the rotation** — never in the feed's bulk thumbnail pull | proposed (§5.3) |
| **D8** | The grid tile player and the viewer player are **different composables** over one platform seam | proposed (§6) |
| **D10–D13** | M4's: the mint is a **seam, not an operation**; rotation is **baked into pixels**; bitrate from the **nominal** 3 s; **one proportional retry** before the drop | ⭐ built — §14.8 |

---

## 8. Phases

⚠ M1 and M2 are independent of everything below them and of each other; either can ship alone.

| # | Phase | Depends on | Notes |
|---|---|---|---|
| **M0** | **Measure** — ⭐ **DONE 2026-09-05, §12.** Model settled (§12.1), D5 restated (§12.4), **cap locked at 96 KB (§12.5)**. | — | ⭐ **M3 is unblocked.** ⚠ M4 re-runs the curve on `MediaCodec` |
| **M1** | **The `0:06` badge.** Render `File.DurationMs` on the tile beside the existing play glyph. | — | ⭐ no schema, no bytes, no encoder — the data is already there (§2) |
| **M2** | **The viewer lift.** Move the player seam to `:ui-core` and the streaming handle beside its ops; route `FileCategory.Video` in `PhotoViewerScreen` to a Photos video branch. | — | ⚠ compile every implementor + the four test fakes (§6) |
| **M3** | **The `MOTION` class — schema + read path.** ⭐ **DONE 2026-09-05, §13.** `FileService` v9→v10; ⚠ the *property-kind* baseline is the one that moved (§13.2). | M0 | HR #9 (§9.2) |
| **M4** | **Android generation.** ⭐ **BUILT 2026-09-08, §14** — `MediaExtractor` → `MediaCodec` → `MediaMuxer` behind the `MotionThumbnailRenderer` seam, at §12.5's settings. ⭐ **Device-verified 2026-09-09 (§14.9)** on a Snapdragon 8 Gen 3 plus a software-encoder emulator. | M3 | the real engineering |
| **M5** | **The rotation + the tile player.** ⭐ **BUILT 2026-09-09, §15** — `MotionRotation` in the feed view model, one blob in flight, `MotionTilePlayer` over `MediaPlayer` / AVFoundation, the `photos.grid.playVideos` switch. ⭐ **Device-verified 2026-09-09 (§15.9)**, which is where D22 (`AndroidView` invokes its factory once per node, so the rotation died after one clip) and D23 came from. ⚠ Did **not** need M2 (D18). | M3, M4 | D1/D2/D7 |
| **M6** | **iOS generation.** `AVAssetExportSession`. | M3 | ⚠ macOS-only gate; `iosKotlinCheck` |
| **M7** | **macOS desktop generation** via the `:macos-helpers` FFM bridge. | M3 | optional; desktop *plays* from M5 regardless |
| **M8** | **Photos on iOS.** | M6, `:photos-ios` existing | ⚠ blocked — there is no `:photos-ios` module yet |

---

## 9. House rules this engages

### 9.1 HR #2 — threat model, in the same PR as M3

`File.ThumbnailMotion` is a **new synced derivative of user content**, so it lands under the same
A5 derivative framing as the existing thumbnail classes and rides the same content-plane
confidentiality argument (sealed chunks, relay sees ciphertext). ⚠ It is worth stating explicitly
that a motion preview is a *strictly richer* derivative than a still: a 3 s clip of a private moment
discloses more than one frame of it. The conclusion does not change — both are E2E-sealed and the
relay learns neither — but the amendment should say so rather than imply a clip and a still are
equivalent.

### 9.2 HR #9 — the schema bump ships with M3

Adding `File.ThumbnailMotion` reshapes `File`, and positional composites make even an additive field
store- and peer-breaking. M3 therefore carries, in one PR: the `service(id, version = N)` bump for
`:file-service`, the registered `SchemaMigration` step, and a regenerated `SchemaShapeBaseline`.
⚠ **Not** `TENSHU_STORAGE_FORMAT_VERSION` — that is `:core`'s alone and moving it wipes every store.
⚠ No relay redeploy: `RELAY_PROTOCOL_VERSION` is untouched.

### 9.3 HR #8 — the smoke gate

M1, M2, M4 and M5 all touch `androidMain`, `:control` or an Android app module, so each runs
`scripts/android-smoke.sh`. ⚠ Its packaging step is flaky ~1 run in 3 in a *different* module each
time; retry once, and a second failure **on the same task** is something else.

---

## 10. Open questions

> ⭐ Q1 and Q2 are answered by M5 (§15.5); Q4 is the device pass's (§15.7). **Q3 is decided
> (2026-09-09): [`drive-synced-thumbnails.md`](drive-synced-thumbnails.md) §15** — *wanted classes*:
> the byte holder backfills the classes some device in the realm has declared it renders, and the
> wanting device mints for itself only where no byte holder can. Its W-2 is what makes this grid move
> on an existing library.

- **Q1 — does the rotation pause off-screen?** It must (a backgrounded app decoding video is a
  battery bug), but the exact seam — Compose lifecycle vs. the feed's existing `previewsEnabled`
  plumbing — is an M5 detail. ⭐ The scrubber's flag is the obvious carrier and already exists.
- **Q2 — what does the rotation do during a fling?** Suspending it while the list is settling is
  almost certainly right (nobody reads a moving tile), and `previewsEnabled` already tracks exactly
  this condition for stills. Confirm against a real device rather than by reasoning.
- **Q3 — backfill.** ~~Nothing regenerates thumbnails after upload~~ — **closed by
  `drive-synced-thumbnails.md` §15 (W-1 + W-2, 2026-09-09; as-built §15.9):** the first pull of
  `File.ThumbnailMotion` by this grid declares the class for the device, and every Android byte
  holder in the realm then mints the clip for the videos it holds, idle and charging, behind a
  per-run budget. ⚠ Unverified on a device: the M5 device pass (§15.7) is where the library should
  start moving one night at a time; until it has, "only videos uploaded after M4 carry a clip" is
  still what a phone shows.
- **Q4 — is 3 s the right turn length with *many* videos in view?** A row of three cycles in 9 s,
  which is brisk; a screen of nine cycles in 27 s, by which time the user has scrolled. Google's
  variable-length turns (4–6 s) have the same property. Possibly the rotation should prefer tiles
  nearer the viewport centre rather than pure document order — measure before adding the rule.

---

## 11. Amendment (2026-09-04) — M1 was not "free", and the reason was a chain-DSL gap

§2 said the `0:06` badge was free because `File.DurationMs` is already stored, synced and read by
Photos. That was true about the *data* and wrong about the *feature*, and the on-device pass is what
exposed it — the badge was built, tested and completely inert.

### 11.1 What the device showed

The badge never appeared on a real library. Every read in `TenshuPhotosRepository` filtered to
stills:

```kotlin
.filter<File> { category equalTo FileCategory.Image }   // feedWindow, feedMembers, searchByName
```

⚠⚠ **`Photo.isVideo` was therefore always `false` on real data**, and `toPhotoOrNull`'s own comment
had said so all along: *"The category predicate already kept videos out of this read; the flag is
here for the day the feed asks for both."* Nobody had read that as a statement about the feature.

⭐ **The lesson worth keeping: I verified the data was available and that the tile did not draw it,
and never checked that a video reaches the tile.** Both halves were locally true and the conclusion
was false. Two independent signals on the device agreed — no play glyph anywhere in the feed, and no
"Videos" smart album in Collections (`CollectionsViewModel` builds it with
`takeIf { it.isNotEmpty() }`, so an empty set renders as *absence*, not as an empty album).

### 11.2 Why it was not a one-word fix

`feedWindow` already carried the reason:

> ⚠ Separate filter steps because the chain DSL has no boolean combinator — `equalTo`, `contains`
> and `inRange` each return a `TBoolean` and nothing consumes two of them.

So `category equalTo Image OR Video` was **unsayable**. Separate `filter` steps are an *and*.
Confirmed by search: no `oneOf`, `anyOf`, `notEqualTo` or boolean `or` existed anywhere. Merging two
reads client-side was not available either — the feed is a keyset-paged single ordered chain, and
two-source paging is the same unsolved problem `core-ordered-window-fusion.md` lists as outstanding
for Drive's `foldersFirst`.

### 11.3 D9 — `oneOf`, not `or`, and a plain helper, not a property

**Built:** a fourth core predicate, `OneOf` (`tenshu/core/protocol/oneOf`), plus
`fun File.isMedia()` as a plain helper over it. Three call sites switched.

⚠⚠ **Why not the `or` combinator, which was the maintainer's first instinct and the more obvious
design.** A predicate is a **flat callstack** evaluated left-to-right against one row
(`FilterImplementation` hands each element to `evaluateCallstack` as `contextData`). `a or b` is a
*tree*: both operands need the row. But an operation's `execute(…, caller, params)` receives the
**current** data — by the time an `Or` ran, the row would already have been reduced to the left-hand
`TBoolean`, and the row itself is gone. Supporting it means threading `contextData` into the
operation contract or teaching `evaluateCallstack` a fifth peephole. Membership needs none of that:
the receiver is an already-resolved value and the candidates are a param, so it is exactly
`equalTo`'s shape with a list where the single `other` was. It is also cheaper per row — one
`category` resolution instead of two.

⚠ `oneOf` is therefore **narrower than `or` on purpose**: one field against many values, never two
different fields. A genuine cross-field disjunction still has no expression and should stay unbuilt
until a second real caller needs one.

⚠ **A helper, not a `Property.Dynamic`.** A derived `isMedia` property would put a Photos-shaped
concept into the stored schema and cost a service bump, to express something the predicate can
already say. `notTrashed()` is the precedent.

### 11.4 What this cost in schema terms — less than expected

`SchemaShapeBaseline` reported **one new type and no reshape**:

```
+tenshu/core/protocol/oneOf|tenshu/app/service|2|…/candidates@tenshu/core/list,…/<return>@tenshu/core/data
```

Purely additive, sitting among its three sibling predicates, with no existing composite's positional
layout moved. ⭐ So **House Rule #9 is not engaged**: no stored row reshaped, no service version
bump, no migration. The baseline is regenerated and that one line is the whole diff.

### 11.5 The phasing this corrects

M1 in §8 read *"the `0:06` badge"*. It should read **"admit videos to the feed, then badge them"**,
and the badge is the easy half. The corrected M1 is what shipped: `oneOf` + `isMedia()` + the three
call sites + the badge.

⚠ Everything downstream is unchanged — M0 still gates M3, and M2 (the viewer lift) is still
independent.

### 11.6 An adjacent finding, deliberately not acted on

All four `evaluateCallstack` peepholes — including `core-ordered-window-fusion.md`'s F2 — are gated
on `operationContext.executionStack.isEmpty()`. The feed has **three pending filters** at its
`sortByDescending`, so it currently receives **none of them** and sorts in memory. That is unchanged
by this work and by any of the options considered, but it means F2 is not reaching the Photos feed
at all. Worth its own investigation; not this slice's business.

---

## 12. M0 — measured (2026-09-05), and D5 was the wrong shape

M0 exists because §4.5's constants were estimates from first principles and this project does not
build on unmeasured premises. The measurement did not merely refine them — it **falsified the model
they were derived from**, so D5 is restated below rather than adjusted.

**Harness.** A throwaway Swift `AVAssetReader` → `AVAssetWriter` encoder (`MotionEncode.swift`,
scratch only, not committed), H.264 baseline, no audio, frame-decimated to a target fps, window
starting at the poster offset (§4.2). AVFoundation on purpose rather than ffmpeg/x264: it is the
framework the iOS generator (M6) would use, so the numbers describe a path we could actually ship
instead of a better encoder than we will ever have.

⚠⚠ **Android's `MediaCodec` is a different encoder and is NOT covered by this.** Everything in §12.1
is a property of *bitrate-targeted encoding* and should hold for any such encoder; §12.3's quality
knee is the part most likely to move on Android hardware.

### 12.1 ⭐ The finding: only duration and bitrate cost bytes

18 encodes across {192, 256} px × {2, 3, 4} s × {250, 400, 600} kbps, one 720×1280 / 24.9 s source:

| | 192 px | 256 px | Δ |
|---|---:|---:|---:|
| 3 s @ 400 kbps | 157,539 B | 157,900 B | **0.2 %** |
| 4 s @ 600 kbps | 309,750 B | 308,310 B | **0.5 %** |

Against the theoretical `bitrate × duration`, every one of the 18 landed within a container overhead
of **2.4 %–7.2 %**, shrinking monotonically with duration as the fixed header amortises:

```
bytes ≈ bitrate × duration × (1 + overhead),   overhead ≈ 7 % at 2 s → 2.4 % at 4 s
```

An fps sweep at a fixed bitrate (8/12/15/20 fps, 256 px, 3 s) moved bytes by under 4 %, and four
different 3 s windows of the same source varied by under 1.4 %.

⚠⚠ **So resolution, frame rate and content complexity are all byte-neutral.** The encoder is
*bitrate-targeted*: it spends the bits it was told to spend and varies quality to do it. §4.5 was
written as though pixels and seconds both bought bytes, which is how a *quality*-targeted encoder
behaves. They do not.

### 12.2 What that changes

- ⭐ **The cap is satisfiable by construction.** Set `bitrate = cap × 8 / (duration × 1.07)` and the
  blob lands under the cap for any input. §4.5's retry-at-2 s-then-drop ladder was designed against
  an overshoot that a bitrate-targeted encoder cannot produce. ⚠ Keep the drop as a backstop for a
  *failed* encode, but it is no longer the expected path, and the doc should stop implying it is.
- ⭐ **A motion clip is a fixed-size object.** Every one costs the same, so per-realm storage is
  exactly `videos × cap` — an unusually easy thing to reason about, and it makes the class's cost
  predictable in a way no other rung is.
- ⭐ **Resolution is free, so take the larger.** 256 px cost 0.2 % more than 192 px and is visibly
  sharper at tile size. ⚠ This also means 320 px is worth measuring before the constant is locked —
  §4.5 never asked, because it wrongly assumed pixels cost bytes.
- ⭐ **Duration is nearly free too.** At an equal ~192 KB budget, 2 s @ 748 kbps and 4 s @ 374 kbps
  were near-indistinguishable at tile size. The trade is real but shallow, which argues for the
  **longer** clip — more of the video shown, for no byte cost and little visible cost.

### 12.3 ⚠ The quality knee — provisional, and the one thing M0 did not settle

At 256 px / 12 fps / 3 s, stepping the bitrate down:

| bitrate | size (3 s) | verdict at tile size |
|---:|---:|---|
| 100 kbps | 38 KB | visibly soft and blocking |
| 150 kbps | 58 KB | soft |
| 250 kbps | **96 KB** | close to 499 kbps; the knee |
| 374 kbps | 144 KB | no visible gain over 250 |
| 499 kbps | 192 KB | no visible gain over 250 |

That puts the knee at roughly **half the proposed 192 KB cap**.

⚠⚠ **But the sample is one source and it is low-motion throughout** — four windows sampled across
its 24.9 s were all the same near-static shot type. High motion is precisely the case that needs
more bits for the same perceived quality, so a cap derived from this sample alone would be too
tight. **This is the one M0 question still open**, and it needs two or three motion-varied clips
(a pan, a walk-and-shoot, a low-light scene), which the device pass could not supply — the phone
holding them was disconnected.

### 12.4 D5, restated

| Parameter | Was (estimated) | Now (measured) | Confidence |
|---|---|---|---|
| Long edge | 256 px | **256 px**, and test 320 px | ⭐ measured free |
| Duration | 3.0 s | **3.0 s**, prefer longer if pressed | ⭐ measured nearly free |
| Frame rate | 12 fps | **12 fps** | ⭐ measured byte-neutral |
| Bitrate | 400 kbps *(input)* | **derived**: `cap × 8 / (duration × 1.07)` | ⭐ inverted |
| Hard cap | 192 KB | **128 KB provisional** (96 KB knee + motion headroom) | ⚠ one low-motion source |

⚠ The structural half (§12.1–§12.2) is settled and M3 can be designed against it. **The cap number
is not**, and M3 should not hard-code one until a motion-varied sample has been run — the whole
point of M0 gating it.

### 12.5 The cap, locked (2026-09-05) — 96 KB

§12.3 left the cap open because the only source available was low-motion. Seven sources were then
pulled from the maintainer's phone, spanning a **67× range of source bitrate** — the best available
proxy for scene complexity:

| source | duration | resolution | source bitrate |
|---|---:|---:|---:|
| `VID20250427144808` | 1.0 s | 1080×1920 | **20.1 Mbps** |
| `VID20251230163005` | 2.7 s | 1080×1920 | 17.3 Mbps |
| `VID20250107161004` | 7.0 s | 1920×1080 | 16.1 Mbps |
| `VID20260901222119` | 6.0 s | 1080×1920 | 15.6 Mbps *(the realm's 0:06 clip)* |
| `VID20250429140342` | 5.3 s | 1920×1080 | 14.7 Mbps |
| `PYOAPPLEc107` | 24.9 s | 720×1280 | 10.3 Mbps |
| `VID_20250604_083535_872` | 21.2 s | 478×580 | **0.3 Mbps** *(re-compressed)* |

**§12.1 held across all of them.** At 256 px / 3 s / 12 fps / 374 kbps the output measured
**48.0–53.2 KB per second of clip — a 1.11× spread over a 67× input range.** Byte cost is a property
of the *encode settings*, not of the footage.

#### Method — and why still frames were not enough

⚠⚠ The §12.3 knee was eyeballed from still frames, and **a still cannot show motion smearing**,
which is the artifact a *looping* tile would actually expose. So quality was re-measured objectively:
each clip was encoded at a 2000 kbps near-lossless reference and compared to it frame by frame
(mean luma PSNR, `Psnr.swift`, scratch only). A smeared frame differs from the reference, so the
metric sees temporal artifacts that a single frame hides.

| bitrate | size (3 s) | mean PSNR ‡ | worst frame ‡ |
|---:|---:|---:|---:|
| 100 kbps | 42–47 KB | 32.2 dB | 29.2 dB |
| 150 kbps | 60–63 KB | 33.7 dB | 30.2 dB |
| 200 kbps | 79–82 KB | 35.0 dB | 31.4 dB |
| **250 kbps** | **98–104 KB** | **36.0 dB** | **32.8 dB** |
| 374 kbps | 145–152 KB | 38.2 dB | 34.3 dB |
| 499 kbps | 192–200 KB | 39.9 dB | 36.0 dB |

‡ across the two most demanding sources, which agreed within **0.2 dB at every rung**. The easy
source ran ~5 dB better throughout.

⭐ **There is no knee.** PSNR rises smoothly at roughly **+3.8 dB per doubling** of bitrate, so the
cap is a judgement about "good enough for a 110 dp looping tile", not a discovered break point. What
the curve *does* settle is that the hard cases cluster tightly — a fixed cap generalises, because
the sources that would blow it do not disagree with each other.

#### Locked

| Parameter | Value | Basis |
|---|---|---|
| Long edge | **256 px** | byte-free vs 192 px, visibly sharper (§12.2) |
| Duration | **3.0 s** | byte cost is linear in it; 3 s reads as a clip, 2 s as a stutter |
| Frame rate | **12 fps** | byte-neutral (§12.1) |
| **`MAX_MOTION_THUMBNAIL_BYTES`** | **`96 * 1024`** | 36.0 dB mean / 32.8 dB worst frame on the hardest real sources |
| Bitrate | **derived**: `cap × 8 / (duration × 1.07)` ≈ 245 kbps | §12.2 — the cap is the input, not the bitrate |

⭐ **Half the 192 KB originally proposed**, and it sits sensibly in the ladder: `LIST` 64 KB ·
`LARGE` 48 KB · **`MOTION` 96 KB** · `XLARGE` 256 KB (512 KB since `drive-synced-thumbnails.md` §17). A video's motion preview costs well under a
photo's hero still, which was the sanity anchor §4.5 reached for and got wrong by 2×.

⚠ **Still Android-unverified.** Every figure here is AVFoundation. `MediaCodec` is a different
encoder; §12.1's byte model is a property of bitrate-targeted encoding and should carry, but the
PSNR-per-bitrate curve is the part most likely to shift, and M4 should re-run this table on a real
device before the constant is treated as final.

#### 12.6 ⚠ A real edge case the spread exposed

`VID20250427144808` is **1.0 s long** — shorter than the poster offset itself. It produced no motion
window at all and the harness refused it outright.

§4.2 already specifies the behaviour ("for one shorter than the offset itself, take the whole clip"),
but this confirms the branch is **not hypothetical**: a real library contains such a clip. ⚠ M4 must
implement it, and a clip that yields no window must fall back to the static poster rather than
storing an empty or one-frame blob.

---

## 13. M3 as-built (2026-09-05) — the class exists, and nothing mints one yet

The `MOTION` rung is declared, registered, versioned and addressable. **Generation is M4**, so on
every device today `File.ThumbnailMotion` resolves to `null` and every video tile shows the static
poster it already showed. The slice is inert by design.

### 13.1 What landed

| Piece | Where |
|---|---|
| `CLASS_MOTION = "motion"` | `GetStoredThumbnail.kt` |
| `File.ThumbnailMotion` — `dynamicProperty(Syncable(OnDemand))` | `File.kt`, resolving through `getStoredThumbnail(CLASS_MOTION)` |
| Property registration | `FileServiceCommonModule.kt` — `dynamicProperty(fromId = File.ID, …)` |
| Blob routing | `FileBytesPropertyWriter.THUMBNAIL_PROPERTY_IDS` |
| **`FileService` v9 → v10** + `schemaMigration(fromVersion = 9) {}` | `FileService.kt`, `FileServiceCommonModule.kt` |

### 13.2 ⚠⚠ HR #9 — and the baseline that mattered was the *second* one

`SchemaShapeBaseline` reported **no diff at all**, because a dynamic property is not a positional
field. ⚠⚠ **That silence is exactly the shape of a much worse bug** — a type that regenerates to no
diff is usually one that was never registered and is therefore outside the walked service graph,
neither pinned nor serialisable (`core-schema-evolution.md`; the `RuntimeIdentity` case). So the
silence had to be *proved* benign rather than assumed.

⭐ **The property-kind baseline is what proved it.** It flagged the property by name:

```
1 new propert(ies) are not pinned yet:
  - tenshu/file/thumbnailMotion (on tenshu/file)
```

and regenerated to exactly one added line, `CHILD_ROW`, alongside its three siblings — **no
deletions, no `INLINE`↔`CHILD_ROW` flips**. A registered-but-positionally-invisible property is
precisely what should produce that pair of results, so the two baselines together are the evidence
that the shape baseline's silence was honest.

⚠ **A schema change in this repo owes two baselines, not one**, and the property-kind gate only
fires in a full-suite run — a module-scoped green proves nothing for it.

**The version bump is owed for the sync semantics, not for the store.** The migration step is empty
because nothing reshaped; what the registration buys is the derived readable floor that keeps a v9
store migrating rather than wiping, and the bump is what fences a *sync peer* that has never heard
of this property — the argument v4 made for `ThumbnailXLarge`, inherited here rather than re-derived.

⚠ **What the smoke gate actually showed, which is a narrower claim.** Its lockstep beacon reads the
**global** `TENSHU_STORAGE_FORMAT_VERSION` (v20, correctly *not* bumped for a service-owned type),
so a service-version move does not register there: the two un-rebuilt canaries stayed `v20 == source`
and every app — including those still on `FileService` v9 — was admitted by a v10 Control. That is
local app↔Control IPC admission, a different mechanism from peer fencing over the relay, so it
neither confirms nor contradicts the paragraph above. ⚠ It does mean **the smoke gate's lockstep
assertions do not cover service-version skew**, and nothing in this slice verified peer fencing
end-to-end.

### 13.3 The invariant M3 pins, and why it needs a test

`MOTION` shares `GetStoredThumbnail` with the still classes because storage and delivery are
identical. ⚠⚠ **That adjacency is the hazard**: it makes folding the class into `GetPreview`'s
"smallest class that covers the request" ladder look natural, and a still request resolving to an
H.264 clip would hand a decoder a video and the caller a corrupt bitmap.

`MotionThumbnailClassTest` pins it from both directions — the motion property routes to the motion
class and its own blob path, and each still property is asserted **not** to resolve to
`CLASS_MOTION`. The still ladder is pinned as exactly `ThumbnailClasses.STILLS`, so the motion class
cannot join it quietly.

### 13.4 What M4 owes

> ⭐ Paid by §14 (2026-09-08), except the on-device items, which §14.7 carries forward.

- Generation: `MediaCodec` + `MediaMuxer` on Android, at §12.5's locked settings. → §14.3
- ⚠ **Re-run §12.5's PSNR table on-device** — every figure there is AVFoundation, and `MediaCodec`
  is a different encoder. → **still owed**, §14.7
- ⚠ **The short-clip branch (§12.6).** A clip at or shorter than the poster offset yields no window;
  it must fall back to the static poster, never store an empty or single-frame blob. A real library
  contains such a clip. → §14.6
- `MAX_MOTION_THUMBNAIL_BYTES = 96 * 1024` lands with generation, not here — an unused constant is
  noise. → `UploadFileImplementation`, pinned by `MotionThumbnailClassTest`

---

## 14. M4 as-built (2026-09-08) — Android mints, and the device pass is still owed

The Android proxy now mints the `MOTION` class at upload, for every video, at §12.5's locked
settings. ⚠⚠ **It has never run.** This slice was built and compiled in a Linux container with no
emulator and no device, so everything below the platform seam is verified by *compilation and by
reading*, and everything above it by JVM tests. §14.7 says exactly which is which and what the
device pass has to do.

### 14.1 What landed

| Piece | Where | Verified by |
|---|---|---|
| `MotionThumbnailRenderer` — the platform seam (D10) | `file-service-proxy/…/proxy/MotionThumbnailRenderer.kt` | `UploadMintsMotionThumbnailTest` (a fake through the real upload path) |
| `MotionClipPlan`, `FrameDecimator` — window, bitrate, size, decimation | `…/proxy/motion/MotionClipPlan.kt` (commonMain, pure) | `MotionClipPlanTest` |
| `YuvPlaneScaler`, `YuvFrame` — area-average scale + rotation, packed I420 | `…/proxy/motion/YuvPlaneScaler.kt` (commonMain, pure) | `YuvPlaneScalerTest` |
| `AndroidMotionThumbnailRenderer` — extractor → decoder → scaler → encoder → muxer | `file-service-proxy/src/androidMain/…/AndroidMotionThumbnailRenderer.kt` | ⚠ **compiles; never executed** |
| `InMemoryMediaDataSource` — lifted out of `AndroidGetPreviewImplementation`, shared with the poster path | `androidMain` | compiles |
| Mint + emit in the upload core, `MAX_MOTION_THUMBNAIL_BYTES = 96 * 1024` | `UploadFileImplementation.renderMotionThumbnail`, `emitThumbnailClass(File.ThumbnailMotion …)` | `UploadMintsMotionThumbnailTest`, `MotionThumbnailClassTest` |
| The poster offset is one rule | `MotionClipPlan.posterOffsetUs`, now called by `extractVideoPoster` | `MotionClipPlanTest` — D4 by construction, not by two copies of `min(1 s, d/2)` |
| Wiring: `androidMotionThumbnailRenderer()` into **both** file factories | Android `PlatformProxyModule` | `MotionThumbnailRendererWiringTest` (source-text audit, the `ReclamationRecorderWiringTest` shape) |
| `GetStoredThumbnail.MOTION_PX = 256` | `:file-service` | Kotlin constant, not schema — both baselines unchanged |

### 14.2 D10 — a seam, not a fourth operation

§4.1 said *no new operation* about the read path, and the same holds for the mint. The still
classes are minted through the `GetPreview` op because they *are* previews; `MOTION` cannot ride it
(a clip is not a bitmap, and `MotionThumbnailClassTest` pins the ladder to the still rungs), so the
mint needed its own way to reach platform code. A schema operation would have put a generation
primitive on the wire and in the `SchemaShapeBaseline` for something no client should ever call;
an `expect`/`actual` would have needed a JS actual and been un-fakeable. A plain `fun interface`,
injected as an optional constructor argument, costs nothing on the wire and lets a JVM test drive
the real upload path with a fake.

⚠⚠ **The cost is the one `reclamationRecorder` already paid for**: an optional-null argument that
compiles when omitted. On Android the upload core is reached through *two* factories in one module
— the Android one for descriptor-backed `UploadSourceFile`, the common one for the in-memory
`UploadFile`/`ReplaceFile` that Takeout import and backup ingest use — so wiring one and not the
other would mint for the picker and silently not for the importer. `MotionThumbnailRendererWiringTest`
reads the module source and fails if either call lacks `motionThumbnailRenderer =`.

### 14.3 D11 — the pipeline is buffers, not surfaces, and rotation is pixels

The textbook Android transcode (decoder → `SurfaceTexture` → GL quad → encoder input `Surface`)
was rejected for two reasons, the first of which is a correctness bug rather than a preference:

1. **A surface's frame timestamp is the render clock.** Frames drawn onto an encoder's input
   surface are stamped when they are posted, so the clip's length would depend on how fast the
   phone drew it — a 36-frame clip could mux as 200 ms. The buffer path stamps each frame with its
   *source* presentation time (`FrameDecimator.relativeUs`), so the window is exact and a 10 fps
   source plays at 10 fps rather than 1.2× fast.
2. **~1 000 lines of EGL plumbing to save a few milliseconds.** At 256 px the area average over
   three planes is trivial CPU work, and it is a pure function `YuvPlaneScalerTest` pins on the JVM
   — including padded rows, NV12-style interleaved chroma, and a short final row.

Both codecs run in ByteBuffer mode over `COLOR_FormatYUV420Flexible`, which every video codec has
supported since API 21; the encoder is fed through `getInputImage`, so plane strides are the
codec's business, not ours. The decode pass keeps its ≤ ~40 scaled frames in memory (≈ 2 MB at
256 × 144), which is what makes the retry in §14.5 encode-only.

**Rotation is baked into the pixels** rather than carried as `setOrientationHint`: every player the
class will meet — Android, `AVPlayer`, a browser `<video>` — honours the container hint a little
differently, and "the portrait clips play sideways on macOS" is exactly the kind of thing only a
device pass finds. The scaler rotates for free; `outputSize` is in *displayed* orientation, so a
phone's portrait recording (a 1920 × 1080 track with `rotation-degrees` 90) becomes 144 × 256.

### 14.4 D4 exactly, not nearly

The extractor seeks `SEEK_TO_CLOSEST_SYNC` to the poster offset — the same *closest-sync* rule the
poster's `getFrameAtTime` uses — and the window is then re-derived from the sync frame's real
timestamp (`MotionClipPlan.windowFrom`) instead of the nominal offset. So the clip's first frame *is*
the poster's keyframe, the clip still runs a full 3 s from there, and the hand-over from still to
motion has nothing to pop. A sync frame that leaves less than §14.6's floor (a keyframe-sparse clip
whose nearest sync sits near the end) is a video with no motion class, not one with a different
start.

### 14.5 D12 + D13 — bytes: the nominal 3 s sets the bitrate, and there is one retry

`targetBitrateBps = cap × 8 / (3 s × BYTE_HEADROOM)` (⚠ `1.07` as shipped, **1.20** since §14.9) ≈ 218 kbps, **from the nominal window, not the actual
one**. A 1.5 s clip therefore comes out at ~48 KB, not sharpened to fill 96 KB: §12.3 measured
bitrate above ~250 kbps as invisible at tile size, so spending the cap on a short clip would buy
nothing and cost every device that fetches it. Constant quality, variable size, cap never exceeded.

§12.2 retired §4.5's shorten-the-window ladder and said *keep the drop as a backstop*. M4 keeps the
drop but puts **one proportional retry** in front of it (`retryBitrateBps`: `bitrate × cap / actual
× 0.9`). The reason is the one overshoot a bitrate-targeted encoder *can* produce: a hardware
encoder that ignores its target on the opening I-frame. On such a device "just drop" would make the
class silently inert for every upload — the failure mode this project keeps re-learning — while a
second encode of 36 cached frames costs milliseconds. The retry is logged (§14.7), so the device
pass will show whether it ever fires.

### 14.6 §12.6 — the floor is 0.5 s and two frames

⚠⚠ **Superseded in part by §14.9.** The floor and `MIN_FRAMES = 2` stand; *where they are applied*
does not. M4 shipped them on the **nominal** window, and this paragraph's worked example — "the real
1.0 s clip gets its second half, and since its only keyframe is at 0, `windowFrom(0)` then makes
that the whole clip" — is what the device falsified: the nominal window was refused before the
extractor was ever consulted, so `windowFrom` never ran and the clip minted nothing. §14.9 moves the
floor onto the window that actually decoded, which is what this section describes happening.

### 14.7 ⚠⚠ What is verified, what is not, and the device pass

**Verified in this container (JVM):** the plan, the scaler, the upload path with a fake renderer
(store + emit, category gate, over-cap drop, decline, throw, no renderer), the cap's place in the
ladder, the Android wiring audit; `androidMain` of `:file-service-proxy` and `:control-shared-proxy`
compile against SDK 37; the JS lane compiles; both schema baselines report no change.

⚠⚠ **This section is M4-as-shipped. The pass it asks for was run on 2026-09-09 — read §14.9 for
what it found; three of the items below are closed and two are not.**

**Not verified — nothing here has run on Android:**

- ⚠⚠ **The encoder.** `AndroidMotionThumbnailRenderer` has never executed. The places a real codec
  can disagree with the code: the `size` passed to `queueInputBuffer` for `Image`-fed input
  (`width × height × 3/2`, the conventional value); an encoder that reports the tile size unsupported
  (`findEncoder` walks to the software encoder, but that walk is untested); a decoder whose flexible
  output is not `YUV_420_888` (10-bit HEVC — declined by design, so an HDR clip gets no class).
- ⚠ **House Rule #8.** `scripts/android-smoke.sh` was not run — no emulator. It must pass before
  merge: this touches `androidMain` and Control's DI.
- ⚠ **§12.5 on `MediaCodec`.** Still the AVFoundation table.

**The pass.** Install Control + Drive, `adb logcat -s MotionThumbnail`, then upload the seven §12.5
sources (`VID20250427144808` … `VID_20250604_083535_872`) through Drive. Each upload logs one line:
bytes against the cap, the bitrate used, whether the retry fired and from what, frames kept, the
window, source geometry and rotation, and wall time. What to read off it: (1) every clip well under
96 KB with no retries ⇒ §12.1 carries to `MediaCodec` and D5 stands; (2) retries firing ⇒ the
opening-I-frame overshoot is real — note by how much, the cap still holds; (3) `DROPPED over cap`
⇒ the model does not carry, and the cap or the derivation needs the measurement §12.5 did, on this
encoder; (4) `not minted: …` ⇒ read the exception. Then open Photos on the phone and on a second
device: every video tile still shows its poster (M5 has not consumed the class yet — that is
correct), and the 1.0 s clip's line shows a `0-1000000 us` window. The PSNR half of §12.5 needs the
clips pulled off the device (`/.thumbnails/<contentId>/motion` under Control's file database) and
compared to a 2000 kbps reference as §12.5 did.

### 14.8 Decisions

| # | Decision | State |
|---|---|---|
| **D10** | The mint is an injected **`MotionThumbnailRenderer` seam**, not a schema operation and not `expect`/`actual` | ⭐ built (§14.2) |
| **D11** | **ByteBuffer + flexible YUV** end to end, scaling in Kotlin; **rotation baked into pixels**, no orientation hint | ⭐ built (§14.3) |
| **D12** | Bitrate derived from the **nominal** 3 s, so a shorter clip is smaller, not sharper | ⭐ built (§14.5) |
| **D13** | **One proportional bitrate retry** before the over-cap drop | ⭐ built (§14.5); ⚠ it fired on 2 of 5 real clips at `1.07` — §14.9 raised the headroom so it is the exception again |
| **D14** | The byte headroom covers the **encoder's spread around its target**, not just the container — `BYTE_HEADROOM = 1.20`, measured | ⭐ built (§14.9 F1) |
| **D15** | An **undeclared** duration means unknown, not empty: the window's end is left open and EOS bounds it | ⭐ built (§14.9 F3) |
| **D16** | The 0.5 s / 2-frame floor is applied to the window that **decoded**, never the nominal one | ⭐ built (§14.9 F4) |
| **D17** | Every decline states a reason and is logged; the success line names the encoder | ⭐ built (§14.9 F2) |

### 14.9 ⭐ The device pass (2026-09-09) — it runs, and it found three things

Snapdragon 8 Gen 3 (OnePlus 12, Android 16 / SDK 36, `c2.qti.*`), plus an `arm64` emulator on
`c2.android.*` for the software-encoder half. Control + Drive from the M4 branch; the seven §12.5
sources uploaded through Drive's folder upload; every clip pulled back out of
`/.thumbnails/<contentId>/motion` and its boxes parsed.

**What was already right.** All of it, structurally. Rotation is baked into pixels and the matrix is
identity, on `rot 90`, `rot 180` and `rot 0` sources — the path no JVM test can reach. HEVC sources
decode to 8-bit `YUV_420_888` and re-encode to AVC. D4 holds end to end: the clip's first frame *is*
the poster, verified by pulling `large` and the clip side by side on a `rot 180` source. One
keyframe per clip, source frame timings preserved (variable `stts`, not `index × interval`), 36
frames over 3 s. `c2.qti.avc.encoder` — the **hardware** encoder — accepted 144 × 256 and 210 × 256,
which closes §14.7's `findEncoder` and `queueInputBuffer`-size questions. 0.55–1.3 s wall time per
clip at 1080p, no crash, no ANR, no OOM, no temp file left behind.

**F1 — the byte headroom was below the encoder's spread, so D13's retry was doing routine work.**
Five phone clips, and the ones that did *not* retry still landed at 89–90 % of the cap; two overshot
to ~100.6 KB and retried. Measuring what the encoder actually delivered against what it was asked
for gave the reason:

| clip | requested | delivered (file) | ratio | container |
|---|---:|---:|---:|---:|
| `rot 0` 1080p hevc | 244 994 | 236 167 | 0.96 | 3.8 % |
| `rot 90` 1080p hevc | 244 994 | 233 846 | 0.95 | 3.8 % |
| `rot 180` 1080p hevc | 215 642 | 244 276 | **1.13** | 3.6 % |
| `rot 90` 1080p hevc, 1.5 s | 244 994 | 290 978 | **1.19** | 6.5 % |
| 720p avc, software encoder | 217 450 | 240 618 | 1.11 | 3.6 % |

⭐ **The container is not the cost — the encoder's spread is.** MP4 overhead measures 3.6–3.8 % at
3 s (6.5 % at 1.5 s), so `CONTAINER_OVERHEAD = 1.07` was spending most of itself on a container that
costs half as much and leaving ~3.4 % of slack against a `MediaCodec` VBR that ranges 0.95×–1.19× of
its target. A bitrate target is a target, not a ceiling. The constant is now **`BYTE_HEADROOM =
1.20`** — it covers every ratio measured (worst 1.146 at the clip's real length), costs ~218 kbps
instead of 245 (**−0.6 dB** by §12.5's +3.8 dB/doubling, and *higher* than the 215 kbps the retry was
actually delivering), and puts D13 back to being the exception it was designed as. Re-measured:
0 retries in 4 clips, and the 24.9 s source produced a byte-identical 92 238 B in **687 ms instead of
1158**, because it now encodes once.

**F2 — a decline was silent, and that hid F3 and F4 for an hour.** `decodeClip(…) ?: return null`
and `encode(…) ?: return null` both returned before the `Log.i`; only the `catch` logged. Two of
seven sources minted nothing and said nothing, and finding out why meant pulling them off the phone
and parsing their boxes by hand. §14.7's four readings all assume every attempt logs a line. Every
`return null` now carries a reason and every attempt logs — `motion class not minted: decoded 1
frames spanning 0 us — not a loop, 166 ms` — and the success line names the **encoder**, which is the
single most useful field on a phone and was missing.

**F3 — a fragmented MP4 got no motion class, and that is a whole family.**
`VID_20250604_083535_872.mp4` is `mvex` + 5 × `moof` + `sidx`, and its `moov` declares **0** in
`mvhd` *and* in every track's `mdhd`. `MediaExtractor`'s `KEY_DURATION` is therefore 0, and
`window(0)` refused on `durationUs <= 0` — while the file decodes perfectly. This is what Telegram,
WhatsApp and everything DASH-derived produce, and §12.5's own seventh source is one. ⚠ An undeclared
duration now means *unknown*, not *empty*: the end is left open at the nominal length and the
decoder's EOS bounds it. On the phone: `37 frames, window 0-3000000 us of 0 us, 476x580 rot 0 ->
210x256`.

**F4 — §12.6's 1.0 s clip minted nothing, 14.6 ms below the floor, on the wrong duration.**
`VID20250427144808.mp4` declares 1 002 700 us in `mvhd` and **970 744 us on its video track** (its
audio track is the longer one), and the code reads the track — correctly. The nominal second half is
then 485 372 us, short of `MIN_WINDOW_US` by 14.6 ms, so `window()` returned null *before the
extractor was consulted* and the sync-frame re-derivation §14.6 relies on never ran. ⚠⚠ **The floor
belongs on the window that decoded, never on the nominal one** — it is the only check that can see a
source whose declared duration is absent, wrong, or measured on a different track. Moved to
`storesAsLoop(frameCount, spanUs)`, applied after decoding. On the phone the clip now mints:
`9 frames, window 208100-970700 us of 970700 us` — a 0.76 s loop starting on a real keyframe, not
the `0-1000000 us` §14.7 predicted (it has more than one keyframe, so neither the old doc nor the PR
body had it right).

**Still owed.** §12.5's PSNR table on `MediaCodec` (the byte model carries; the quality curve is
still AVFoundation's). A 4K source — none of the seven is one, so the pure-Kotlin scaler's cost is
measured only to 1080p. A 10-bit HDR source, to see the declared-by-design refusal actually refuse.
⚠ Uploads are gated by `ensureWithinQuota` *before* any thumbnail renders, so an over-quota realm
mints nothing and says nothing — budget storage before repeating this pass.

### 14.10 The mint moved behind the row (2026-09-09)

The device pass left one review point open: the mint ran *before* `saveData(fileEntity)`, so a
clip that costs 0.55–1.3 s at 1080p (and is unmeasured at 4K) delayed the entity every peer is
waiting on by that much, for a derivative only a grid ever reads — and a cancelled `UploadFileBg`
could not interrupt it. `renderMotionThumbnail` now runs next to its own `emitThumbnailClass`, after
the row and the file's bytes have landed, and checks `ensureActive()` before it starts. ⚠ It still
cannot interrupt a mint in progress; the renderer's own time budget is what bounds that. Nothing
observable changed for a completed upload, which is why `UploadMintsMotionThumbnailTest` needed no
edit.

---

## 15. M5 as-built (2026-09-09) — the rotation and the tile player

The grid now has its one rotating player, exactly the rule §1.2 measured: one player, a visible
video tile through once, hand over in document order, wrap, a lone tile hands over to itself. It
was built and tested on the JVM **in a container with no device**; §15.7 is the pass it owed, and
§15.9 is that pass — which found the rotation stopping dead after one clip.

### 15.1 What landed

| Piece | Where | Verified by |
|---|---|---|
| `MotionRotation` — the rule, the single fetch slot, the miss handling | `photos-shared-logic/…/feed/MotionRotation.kt` (+ `MotionRotationRules`, `MotionClipStore`) | `MotionRotationTest` (16 cases) |
| `MotionTurn` — path + bytes + serial | `photos-shared-logic/…/model/MotionTurn.kt` | the same |
| `PhotosRepository.motionClip(path)` — the class's own read, outside the bulk pull | `PhotosRepository`, `TenshuPhotosRepository`, `MockPhotosRepository` | ⚠ the real read is unexecuted |
| `PhotosFeedViewModel.motion` | the feed view model | `FeedSelectionTest` still green (constructor unchanged) |
| `MotionTilePlayer` + `isMotionPlaybackSupported()` — the seam, D8's *other* composable | `photos-shared-ui/…/components/MotionTilePlayer.kt`, `.android.kt`, `.desktop.kt` | ⚠ **compiles; the Android and macOS actuals have never executed** |
| `PhotoTile(motion = …, onMotionFinished = …)` — the keyed input | `PhotoTile.kt` | `PhotoTileMotionTest` |
| `PhotosFeedScreen` reports the visible video paths; none while scrubbing; empty on leave | `PhotosFeedScreen.kt` | `PhotosFeedScreenTest.visibleVideoTilesAreReportedInDocumentOrder` |
| The shell drives it: the switch, the platform gate, the lifecycle | `App.kt` | ⚠ compiles only |
| `PhotosPreferences.PlayVideosInGrid` (`photos.grid.playVideos`, DEVICE, default on) + `GridMotionSettings` | `photos-shared-logic/…/settings/GridMotionSettings.kt` | `SettingsViewModelTest` |
| The Settings row *"Play videos in the grid"* | `SettingsScreen.kt`, `SettingsViewModel.kt` | `SettingsViewModelTest` |

### 15.2 D18 — M5 did not need M2, and the earlier reading of D8 was wrong

D8 says the grid tile player and the viewer player are *different composables over one platform
seam*, and the first plan read that as "lift Drive's seam to `:ui-core` first". It is not required.
The viewer needs `StreamingContentHandle`, the proxy fd and a content provider because it plays an
**original**; the tile plays a ≤ 96 KB blob **already in hand** (§5.1's rule guarantees it). On
Android that is a `MediaPlayer` over an in-memory `MediaDataSource` drawn through a `TextureView` —
no temp file, no provider, no `VideoView`, no controller; on macOS it is the same AVFoundation
bridge Drive's viewer uses, over a temp file the turn owns. The "one platform seam" the two
composables share is the platform's own player, not a Kotlin type. M2 stays independent and is now
purely the viewer's business.

### 15.3 D19 — the rotation is feed state with one fetch slot, and a miss is not an answer

`MotionRotation` lives beside the view model (§5.2) and owns three things: the visible video
paths, the current `MotionTurn`, and the single in-flight fetch. The rules are pure functions
(`MotionRotationRules.turnAfter` / `prefetchTarget`) so the walk is pinned without a scope:

- Only a tile whose clip is **in hand** (`MotionClipStore`, a 4-clip LRU separate from
  `PreviewCache` — §5.3) is a member. Nothing in hand ⇒ nothing animates, and the grid is what it
  was: additive by construction.
- While tile *N* plays, tile *N+1*'s clip is fetched — **one** at a time, by construction
  (`exactlyOneFetchIsInFlightAtAnyMoment`). `MOTION` never joins `cacheThumbnails`' bulk pull.
- ⚠⚠ A `null` from the repository is **never remembered**. The tile is skipped for the rest of the
  cycle and asked again on the next wrap and on any change of the visible set — which is how a
  clip that the content plane delivers while the grid is open joins the rotation without anyone
  refreshing anything.
- A scroll that keeps the current tile on screen never restarts it; one that takes it off screen
  hands over at once. A stale `turnFinished` from a tile that no longer holds the player is ignored.

### 15.4 D20 — a turn that cannot play still ends, and the shell gates the platform

`MotionTilePlayer`'s contract: **`onFinished` exactly once per turn, always** — after a clip that
played, one that failed to open, one the platform could not decode. A player that stays silent on
failure stalls every other video on screen. A failure *waits out a nominal turn* (3 s) before
reporting, so a platform where every clip fails degrades to a static grid at the turn cadence
rather than spinning through the rotation as fast as it can recompose (`PhotoTileMotionTest` pins
both halves: not at once, and once). Independently, the shell gates the rotation on
`isMotionPlaybackSupported()` — Android `true`, desktop `MacVideoPlayer.isAvailable` behind a
`runCatching` (the `desktopTest` toolchain cannot load the FFM bridge; that is a `NoClassDefFoundError`,
which is why it is `Throwable` that is caught), Windows/Linux `false` — so a platform with no player
is never handed a turn in the first place.

### 15.5 D21 — the switch, and Q1/Q2 as built

- **D21.** `photos.grid.playVideos` is a **DEVICE**-scoped `Preference`, default **on**, read
  through `GridMotionSettings` exactly as `IngestSettingsStore` reads its rows. One row, no
  network or battery condition (§5.4) — the rotation's cost is one clip in flight regardless of
  library size, which is not a thing to put a policy matrix around.
- **Q1 — off-screen / backgrounded.** The shell pauses the rotation from `LifecycleResumeEffect`
  and resumes it there; the feed screen reports an empty visible set on leaving composition (the
  viewer, another tab). Paused means the turn is cleared and the player disposed; resume continues
  from the tile after the last one, in document order.
- **Q2 — scrubbing / fling.** The visible set is reported as **empty while the scrubber's thumb is
  held** — the same carrier `previewsEnabled` uses, and for the same reason. ⚠ A plain fling is
  **not** paused: the visible set simply changes under the rotation, and the current tile keeps its
  turn as long as it stays on screen. `gridState.isScrollInProgress` is the one-line alternative if
  the device pass says a moving tile under a fling reads as churn — measure, do not reason.

### 15.6 What the tile does with a turn

`PhotoTile` takes `motion: MotionTurn?` as a **keyed input** in the `previewsEnabled` shape — the
feed hands every tile the same turn and each keeps only its own — never a lambda that resolves to
nothing (that KDoc records why as a shipped bug). The poster stays underneath at all times; the
player draws over it inside a tagged box and is removed on hand-over, so a tile between turns is
never blank. The player is keyed on the *turn*, and `MotionTurn`'s identity includes a serial
precisely so that a second turn on the same tile (the lone-video case) is a fresh player at frame
one. On Android the `TextureView` is given a centre-crop transform from the reported video size,
so the clip is framed exactly as the poster's `ContentScale.Crop` — D4's alignment is only
invisible if the two are framed identically.

### 15.7 ⚠⚠ What is verified, what is not, and the device pass

**Verified here (JVM):** the rotation's rule, the one-fetch-in-flight invariant, miss handling,
pause/resume/off, scroll-away and scroll-keep, the store's LRU, the settings row, the tile's keyed
input and the failed-turn contract, the feed's visible-video report; `:photos-shared-ui` compiles
for Android and desktop.

**Not verified — nothing has run on a device:**

- ⚠⚠ **No tile has ever moved.** `MotionTilePlayer.android.kt` (`MediaPlayer` over a
  `MediaDataSource`, `TextureView` transform, surface hand-off, completion) and
  `MotionTilePlayer.desktop.kt` (AVFoundation over a temp file, end-of-clip by position) have
  never executed.
- ⚠ The real `motionClip` read — `select { relations(File.ThumbnailMotion) }` answering with the
  bytes — is inferred from `cacheThumbnails`' shape, not observed.
- ⚠ The visible-set derivation off `layoutInfo` on a real paged grid (partially visible rows,
  placeholders during a load).
- ⚠ Q4 — whether 3 s turns read right with many videos in view.
- ⚠ HR #8 — `scripts/android-smoke.sh` not run (no emulator here); `androidMain` of
  `:photos-shared-ui` changed.

**The pass.** On the OnePlus, with the seven §12.5 sources in the realm (the only videos that carry
a clip — Q3): open Photos and watch the row of video tiles. Expect: exactly one moving at a time;
document order; each plays ~3 s and hands over; the hand-over shows no pop (the clip's first frame
is the poster); a lone video on screen loops. Then: hold the scrubber thumb (everything stops,
resumes on release); open the viewer and come back (stops, resumes); background the app (stops,
resumes); Settings → *Play videos in the grid* off (stops at once, nothing fetched — `adb logcat`
shows no `MediaPlayer` activity), on again. Watch `adb logcat -s MediaPlayer:* MediaCodec:*` for
errors, and the app's memory across a minute of rotation (one `TextureView` + one codec at a time
is the design; two would be a leak). Q4: put nine videos in view and say whether the cadence reads
as brisk or as chaos. On a Mac, the same on the desktop app — the one place the AVFoundation actual
runs.

### 15.8 Decisions

| # | Decision | State |
|---|---|---|
| **D18** | M5 needs **no M2**: the tile player is its own primitive over bytes in hand, not the viewer's streaming stack | ⭐ built (§15.2) |
| **D19** | The rotation is feed state with **one fetch slot**; a miss is skipped for the cycle and retried on a wrap or a visible-set change, never remembered | ⭐ built (§15.3) |
| **D20** | A turn that cannot play **still ends**, after a nominal 3 s; the shell gates on `isMotionPlaybackSupported()` | ⭐ built (§15.4) |
| **D21** | `photos.grid.playVideos` — one DEVICE-scoped boolean, default on, no conditions | ⭐ built (§15.5) |
| **D22** | **One `TextureView` per tile, a fresh player per turn** — the view is never keyed on the turn, because `AndroidView` runs its factory once per node | ⭐ built (§15.9) |
| **D23** | **Every turn is bounded** by `MOTION_TURN_CEILING_MS`, not only one that failed to open — a player that goes silent must not hold the slot | ⭐ built (§15.9) |

### 15.9 ⭐ The device pass (2026-09-09) — the grid moves, after one defect

Run on the OnePlus against the real library, on top of `drive-synced-thumbnails.md` §15's backfill
(the two passes are one — a rotation needs a clip, and only one video on this device had bytes to
mint one from).

**⚠⚠ D22 — a tile played exactly one clip per mount, then the rotation died.** The hand-over was
correct and the stall was total: `MediaPlayer` for turn N+1 was constructed and never started, so
no completion and no error ever arrived, `onFinished` never fired, and the rotation held the slot
for ever. Only a remount — scrolling the tile off screen and back — bought one more clip. The
lone-video case is where it bites, because "next is itself" never disposes the node.

The cause is a Compose interop rule, not a media one: **`AndroidView` invokes its factory once per
composition node and never again**, so the `remember(turn) { TextureView(...) }` built for each new
turn was never in the hierarchy. Its `onSurfaceTextureAvailable` therefore never fired, and
`prepareAsync` is on that path. The fix is one `TextureView` per tile with a fresh `TilePlayback`
per turn attached from a `DisposableEffect`, plus two consequences of a view that now outlives the
turn: `attach` must start from an **already-available** surface (the listener only fires on the
transition), and `onSurfaceTextureAvailable` is reachable twice per turn, so preparing is gated —
`prepareAsync` on a prepared player throws.

**⚠⚠ D23 — the ceiling.** §15.4's "a turn that cannot play still ends" was armed only for a clip
that failed to *open*. D22 was a turn that opened fine and then went silent, which that guard
cannot see — and the tile has no way to tell, so the rotation waits. Every turn is now bounded by
`MOTION_TURN_CEILING_MS` (10 s, well above a 3 s clip), which is what makes a D22-shaped defect a
skipped beat rather than a dead grid.

**Verified on the device after the fix:** 9 `MediaPlayer start called` in 45 s, one every ~3.1 s
(the clip's length plus hand-over), sustained; 6 of 6 rapid screenshots distinct, against 8 of 8
identical before. No crash, no `MediaCodec` error.

⚠ **`photos-shared-ui` has no Android test source set** — only `desktopTest`, which reaches the
desktop actual's "could not open" path. The Android actual has no unit lane at all, so the logcat
turn sequence above *is* the evidence, and the smoke gate is the only automated cover.

⚠ **Still owed from §15.7:** Q4 (nine videos in view), the scrubber / viewer / background
pause-resume round trip, the desktop AVFoundation actual (never run), and memory across a long
rotation. ⚠ The desktop actual carries a D22-shaped risk of its own — its frame loop has no
ceiling, so a session whose `durationSeconds` stays null spins without ever handing over.
