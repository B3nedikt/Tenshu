# Drive — the `XXLARGE` rung and the progressive large preview

**Status:** v0.3 — **decisions D-12…D-20 SETTLED 2026-09-17** (the maintainer took every recommendation, §5; **D-19, re-opened after review, settled by the maintainer as inline publishing**); **X-0 built** (§9.1); **X-2 built** (§9.2, §9.3); **X-1 built** (§9.4); **X-3 built** (§9.5); **X-4 built** (§9.6); **X-5 built** (§9.7); **X-6 built** (§9.8) — the rung exists, the Photos viewer draws it, a photograph nobody minted one for can get one on a dwell, and the web lightbox reads it with the original as its fallback; every surface in the set now draws it. Opened 2026-09-16 from item 4 of the retina-Mac report.
The maintainer settled the direction in `drive-synced-thumbnails.md` §17.4: **a larger stored rung is
wanted**, not just a fallback; ~0.5 MB per photograph is acceptable; a progressive swap is worth
designing. This doc turns that into decisions **D-12 … D-20** (§5) and phases **X-0 … X-6** (§7).
⚠⚠ **§3 is the part to read before building** — and §9.1 is what X-0 left of it. A new rung touched
30-odd places, three of them hand-written lists or `else` branches that stayed silent when a class
was missing; X-0 folded those into one table, and §9.1 lists what is still by hand.
⚠⚠ **§4.7 is a prerequisite that is easy to skip.** Nothing evicts stored thumbnails locally, and a
~0.5 MB rung pulled by every viewer turns that from a slow leak into gigabytes.

**Owner:** Benedikt

**Amends:** [`drive-synced-thumbnails.md`](drive-synced-thumbnails.md). It adds a fifth rung to the
ladder defined there (§10.2, §12) and follows its §15.6 recipe. That doc remains the ladder's home;
this one owns the rung, its consumers and the progressive preview. Same split as
[`photos-video-support.md`](photos-video-support.md) owns `MOTION`.
**Builds on:** `photos-app.md` §34 (the viewer asks for the size it draws); `drive-web-parity.md`
§1.2i (the web reads stored classes by name, never `File.Preview`); `retention-quota-model.md` §2
(tier 3); `content-plane-refcount.md` (declared edges).

---

## 1. The gap `XLARGE` leaves

After `drive-synced-thumbnails.md` §17 and `photos-app.md` §33/§34:

- **Grid tiles are covered.** A tile never exceeds 240 dp, so the 1024 px rung fills even a 2×2
  feature on a 2x panel.
- **A viewer on a device without the original is not.** It asks for the size it draws, and nothing
  above 1024 px can answer:

| Viewer | Drawn long edge | Served today | Upscale |
|---|---:|---:|---:|
| 16" MacBook, maximised, 4:3 landscape | 2979 px | 1024 px | **2.9×** |
| Phone 1080 × 2400, 3:4 portrait | 1440 px | 1024 px | 1.4× |
| Phone 1080 × 2400, 9:16 portrait | 1920 px | 1024 px | **1.9×** |
| Web lightbox (`FilePreview.tsx`) | — | the **whole original** (`getData`) | — |

⚠⚠ **Phones are in the table too.** A portrait photograph fills a phone's height at 1.4–1.9× the hero
rung. This is not a desktop-only rung, which is D-15's whole argument (§5).

The web row is the other half of the maintainer's reasoning. The web lightbox avoids blur by
downloading the original, which is 6.7–9.5 MB for the phone photographs below and, per the
maintainer, 18 MB for a DSLR frame. `drive-synced-thumbnails.md` §12.1 says why that cannot scale: a
derivative's weight is set by its own pixels, the original's by the camera.

---

## 2. Measured — what a 2048 px rung weighs

Two real phone photographs from the maintainer's library, plus a 1080 × 2376 screenshot-shaped
image. JVM ImageIO, bilinear downscale, the encoder the desktop minter uses.

| Long edge | Landscape 4096 × 2304 (6.7 MB original) | Portrait 2248 × 4000 (9.5 MB original) | 1080 × 2376 (2.2 MB) | Decoded bitmap |
|---:|---|---|---|---:|
| 1024 (`XLARGE`) | q80 218 KB · q65 158 KB | q80 126 KB · q65 89 KB | q80 49 KB | ~2 MB |
| 1600 | q80 477 KB · q65 344 KB | q80 253 KB · q65 182 KB | q80 100 KB | ~5 MB |
| **2048** | **q80 683 KB · q65 494 KB** | **q80 364 KB · q65 264 KB** | **q80 147 KB** | **~8–9 MB** |
| 2560 | q80 981 KB · q65 717 KB | q80 506 KB · q65 368 KB | q80 199 KB (source-capped) | ~14 MB |

Readings:

- **A 2048 px rung costs 4–10 % of the original** for these phone frames, about 3× `XLARGE`. ⚠ Read
  off the table above (683 KB/6.7 MB = 10.0 %, 364 KB/9.5 MB = 3.7 %, 147 KB/2.2 MB = 6.5 %, all
  q80); this line said "5–7 %" until the X-1 review recomputed it, and `File.kt`'s KDoc had copied it.
- ⚠ **The detailed landscape frame is 683 KB at q80**, over the 512 KB `XLARGE` cap. The new rung
  needs its own cap (D-13).
- 2560 px adds ~45 % bytes over 2048 for a difference only a retina desktop can show.
- Encode time at 2048 px was 46–55 ms on the JVM. ⚠ The minter does not share a decode between
  rungs: `renderStill` runs `GetPreview` with `GENERATE_FRESH` per class, so each rung re-reads and
  re-decodes the original (119–212 ms for these frames).
- ⚠ Not measured here: a DSLR source and a real library's average. §15.8's budget is still a guess
  for the same reason.

### 2.1 The four encoders, on identical pixels (2026-09-18, X-1's prerequisite)

§8 Q2 asked whether the other platforms' encoders weigh the same. They do not. Every platform encoded
**the same generated frames** — one smooth gradient, one with seeded per-pixel noise and hard edges —
so the encoder is the only variable. Real photographs sit between the two, much closer to smooth.

| 2048 px, same pixels | JVM (ImageIO) | Android (`Bitmap.compress`) | Browser (`convertToBlob`) | iOS (`UIImageJPEGRepresentation`) |
|---|---:|---:|---:|---:|
| smooth q80 | 90 KB | 50 KB | 51 KB | **156 KB** |
| detailed q80 | 1727 KB | 1682 KB | 1683 KB | **2813 KB** |
| detailed q65 | 1254 KB | 1199 KB | 1199 KB | **2197 KB** |
| detailed q50 | 961 KB | 902 KB | 902 KB | **1605 KB** |

- ⭐ **Android and the browser are the same encoder** to within a kilobyte (both Skia/libjpeg-turbo),
  and the JVM is within a few per cent of them on detailed content.
- ⚠⚠ **iOS runs ~1.6–1.7× heavier at the same nominal quality.** `UIImageJPEGRepresentation(0.8)` is
  not libjpeg's q80 — Apple's scale is its own. So an iPhone lands roughly **one step-down lower**
  than the other platforms for the same picture: §2's detailed real frame (683 KB at q80 on the JVM)
  is ~1.1 MB on iOS, over the cap at q80 and admitted at q65.
- ⚠ Android measured on the emulator (`Bitmap.compress` through the same Skia), which is sound for
  *bytes* — unlike the timing figures `inference-service.md` §15.1 forbids taking there. Worth one
  confirmation on the OnePlus when it is next attached.
- Method: a scratch harness per platform, not committed — the JVM one through the real
  `JvmGetPreviewImplementation`, the others calling each platform encoder directly.

---

## 3. What a new rung touches — the inventory

Surveyed 2026-09-16 on master `cfc334c8d`. Grouped by whether the change is picked up automatically.

**Picked up once the class is in `FileBytesPropertyWriter.THUMBNAIL_PROPERTY_IDS`** (`:194-199`):
`supports`, `resolveExternalPath`, per-class `resolveContentId`, `resolveContentHash` → null,
`thumbnailPath`, `derivedThumbnailContentId`, `WantedThumbnailClasses.notePull`, the declared edge
inside `SyncObserver` emits, `ProxyLiveContentIds`, the schema-driven content walk, the orphan-sweep
protection of `/.thumbnails`, and the minter's quality step-down (`STEP_DOWN_QUALITIES`).

**Must be added by hand:**

1. `GetStoredThumbnail`: `CLASS_XXLARGE`, `XXLARGE_PX`.
2. `File.kt`: a `dynamicProperty(Syncable(OnDemand))` `ThumbnailXXLarge` beside `ThumbnailXLarge`
   (`:657-661`, getter `:683`), and its registration in `FileServiceCommonModule` (`:723-724`).
3. HR #9: `FileService` v12, an empty `schemaMigration(fromVersion = 11)`, a new row in
   `property-kind-baseline.txt`, and the regenerated shape baseline.
4. `UploadFileImplementation`: the render (`:320-340`), the emit (`:498-515`) and a cap constant.
5. `ThumbnailPreferences.DECLARABLE_CLASSES` (`:61-64`).
6. ⚠⚠ **`ProxyDeclarableContent.THUMBNAIL_PROPERTIES` (`:85-90`) is a second, hand-written list**,
   although its KDoc says it is derived from the writer's table. It feeds the declarable-content walk
   that re-declares content edges the relay lacks, so missing it means that pass skips the rung. The
   emit still declares inline, so the gap only bites content whose declaration was lost.
7. ⚠⚠ **`BackfillThumbnailClassesImplementation` has three silent defaults.** `canMint`
   (`:177-188`) falls to `else -> false`, and `mint` (`:212-230`) to `else -> Declined`: the backfill
   would never mint the rung. `capFor` (`:250-253`) falls to `else -> MAX_XLARGE`: a quota check at
   the wrong cap.
8. All three `GetPreview` actuals: `storedThumbnail` and `coveringStoredThumbnail` (JVM `:265-314`,
   Android `:271-320`, iOS `:396-445`).
9. Client pulls, every one named explicitly:
   - Photos: `heroThumbnail`, `asksForHeroByName`, `renderEdgeOnByteHolder`.
   - Timeline: `heroFidelity`.
   - Web: `FileSystem.readPreviewJs` / `cacheThumbnailsJs` (`:442-476`) and the lightbox.
10. Tests with hand lists:
    - `MotionThumbnailClassTest` (**asserts exactly three stills**)
    - `DeclaredContentIsWalkableTest`
    - `WantedThumbnailClassDeclarationTest`, whose not-declarable example is literally `"xxlarge-typo"`
    - the backfill tests
    - `JvmGetPreviewImplementationThumbnailFallbackTest`'s band tests
    - `SyncedThumbnailSystemTest`
11. Threat model: A5 (`threat-model.md:517-537`, generic derivatives); the A6 wanted-class row
    (`:839-858`) already names *"a future `xxlarge`"*.
12. Stale counts to correct:
    - `content-plane-refcount.md` says *"four thumbnail classes"* and *"5 content-bearing properties"*.
    - ~~`UploadFileImplementation`'s `MOTION` KDoc still says `XLARGE` is 256 KB.~~ Fixed in X-0.

**Not wired where you might expect:**
- The pull hook is not wired on iOS or in the JS runtime.
- The backfill schedule is registered on Android and desktop only.
- There is no census anywhere: nothing counts files missing a class or bytes per class.

---

## 4. Design

### 4.1 The rung

`XXLARGE` is a **2048 px** longest-edge JPEG, minted from the same sources as `XLARGE`: an image, a
video's poster frame, a PDF's first page. Its cap is **1 MB** (D-13), after the minter's existing q65/q50
step-down. With that floor a 2048² noise frame (~1.7 MB at q50, extrapolated ×4 from §17.3's 420 KB at 1024²) still declines, so this cap still
filters, unlike `XLARGE`'s since §17.3.

A photograph smaller than 2048 px renders at its own size, exactly as the other rungs do.

### 4.2 The ladder with a fifth still rung

Today's resolver:
- serves the smallest local class that covers a request;
- stretches `XLARGE` up to `COVER_TOLERANCE` (2.0);
- above that, renders from a local original or falls back to the best local class.

With `XXLARGE` the rule stays the same and gains one row (D-14). ⚠ "Stretches" is per rung, not
"the largest rung": `XLARGE` keeps its tolerance once `XXLARGE` exists, so a device holding only
`XLARGE` still answers 1025–2048 px (the ≤ 2048 row). `LARGE` never stretches (§14.2 of the ladder doc).

| Request | Local classes the resolver may answer with, in order |
|---|---|
| ≤ 100 | `LIST` — the list-row path (`best`), which reads only `LIST` when it is local |
| 101–256 | `LARGE`, then `XLARGE`, then `XXLARGE` |
| ≤ 1024 | `XLARGE`, then `XXLARGE` |
| ≤ 2048 | **`XXLARGE`**, then `XLARGE` within tolerance |
| ≤ 4096 | `XXLARGE` within tolerance — ⚠⚠ live since X-3, and **only** because `File.ViewerPreview` can decline it (§9.5) |

⚠ Each row falls through to the **larger covering** rungs when the preferred one is absent: too big
beats reading the whole original, as `covering` does today and `StoredThumbnailLadderTest` pins
against a synthetic fourth rung. ⚠ The rows are what may answer **instead of a render**. When none of them is local and no render is
possible (no original here), `best` still answers with the best smaller class, a stand-in below the
request (§4.5 step 1). A missing, declined or unpulled larger rung never blanks a preview.
| above | a render from the original; else the best local class |

⚠⚠ **Local only, as always.** `GetPreview` cannot fetch (`drive-synced-thumbnails.md` §14.1). A
Timeline feed row asking for 1536 px gets `XXLARGE` only if something else already pulled it, so the
rung cannot leak into feed bytes through the resolver. The leak path is a *pull*, and §4.4 says who
may pull.

⚠ **The byte-holder render changes meaning.** `photos-app.md` §34's `renderEdgeOnByteHolder`
asks past `XLARGE × COVER_TOLERANCE` to force a render from the original. Once `XXLARGE` exists
locally on the uploading device, a render beats it only above 2048 px, and a stretch of
`XXLARGE` covers up to 4096. D-17 decides whether a device holding the original still renders above
the rung (sharpest) or takes the rung (cheapest).
⚠⚠ **Only once the rung is local.** A byte holder can lack `XXLARGE`: an old file not yet
backfilled, a declined mint, or a rung evicted by X-2. Without the rung, D-14 would stretch `XLARGE`
over an original the device holds, so the forced render stays in that case.
⚠⚠ **And the force must be explicit, not an inflated request.** `renderEdgeOnByteHolder` forces the
original by asking just past the largest stretch. Once `XXLARGE` exists, that threshold is 4097 px,
so a 2979 px desktop view would render and encode a 4097 px image (~64 MiB of pixels, square) only
for the UI to downsample it. It already over-renders today: a 1500 px view renders at 2049. D-17
therefore adds an optional `GetPreview` param, and ⚠⚠ **the proxy applies D-17's condition, not the
client**. The client knows only `HasLocalData` and gets bare bytes back, so it cannot tell whether
`XXLARGE` is local. With the param set, the proxy serves a local rung that **covers** the request
(a downscale, never a stretch). Otherwise, with the original local, it **renders at the requested
edge**. In `StoredThumbnailLadder` terms that is `covering` without its tolerance step. X-3 sets the
param on a byte holder and deletes `renderEdgeOnByteHolder`.

### 4.3 Who mints, and when

- **At upload, for every image and PDF, and for video where a frame decoder exists, like `XLARGE`**
  (D-15). ⚠ The JVM and JS have no video frame decoder, so a desktop or browser video upload mints
  no still rung (a declined render, `drive-synced-thumbnails.md` §18). A renderer-capable device's backfill mints
  it later.
  - §15.5's D-8 option was to gate on the wanted set. It would save nothing here: §1 shows phones
    want the rung too, so every realm with Photos declares it.
  - The extra cost is one more render per upload (a re-read, a decode of ~0.1–0.2 s for these frames,
    and a ~50 ms encode) and ~0.3–0.7 MB of relay bytes. Sharing one decode across the rungs is an
    optimisation X-0's table makes easy, not a prerequisite.
- **History by the backfill**, unchanged in mechanism (§15.3 D-6), once a device declares `xxlarge`.
  ⚠ **The budget needs to move** (D-16).
  - The per-run limit is 32 MiB and 250 files, which at ~0.5 MB a frame is ~64 photographs a
    night: **~25 nights** for the maintainer's 1575-file library.
  - The pass stops at the quota. ⚠⚠ Its idle + charging + unmetered constraints hold **only under a
    driver that enforces them** (WorkManager with Control closed). `ScheduleDrainLoop` never reads
    `ConstraintSpec`, so on desktop, and on Android while Control runs, the pass fires on an active,
    battery-powered device (`drive-synced-thumbnails.md` §15.10). `ThumbnailBackfillSchedule`'s KDoc
    accepts that *because* the per-run budget is small.
  - Proposed: **512 MiB and 1000 files** per run, i.e. two to three nights for that library, **only
    under a constraint-enforcing driver**. The in-process drain keeps today's 32 MiB / 250 files
    (D-16, amended after review).
- ⚠⚠ **An existing defect must be fixed first: a generation call can answer from stored classes.**
  `renderStill` passes `GENERATE_FRESH` and stores whatever comes back. Yet the actuals' fallbacks
  return `storedThumbnail(…)` regardless of that flag:
  - the JVM's video branch, which has no frame decoder, and its no-bytes path;
  - Android's no-bytes path, a failed poster extraction and a failed PDF render;
  - iOS's no-bytes path.

  Confirmed 2026-09-17 by a JVM probe: `GENERATE_FRESH` at 1024 px for a local `.mp4` returned
  the stored `LARGE` bytes verbatim. So **desktop's backfill already publishes a video's 256 px
  `LARGE` under the `XLARGE` name**. The class then reads as present and is never re-minted, and
  every device that pulls it gets a 256 px poster. With `XXLARGE` it would publish an `XLARGE`
  under the larger name.

  ✅ **Fixed** (`drive-synced-thumbnails.md` §18): a generation call answers `null`, never a
  stored class. The `canRenderVideoPoster` gate proposed here was not needed. A desktop now declines
  each video once, reading no bytes, and the backfill records the decline.

  Blobs already published under the wrong name are §8 Q5.
- **Browser uploads mint it for images only.** They run the shared `UploadFileImplementation`, and
  the canvas honours the quality step-down. ⚠ `JsGetPreviewImplementation` returns null for video
  and PDF, because `createImageBitmap` cannot decode them. JS runs no backfill either, so in a
  web-only realm those files never get `XXLARGE`, exactly as they get no `XLARGE` today. D-15's
  scope holds for native uploads.
- ⚠ **iOS mints at upload but runs no backfill** (not registered; route 1's shared device id makes
  the marker a race, §15.9). Nothing changes there.

### 4.4 Who pulls it

A rung reaches a device only when a client names it (§14.5). Named by:

1. **The Photos viewer**, for a request above 1024 px, on a device without the original. It replaces
   `heroThumbnail`'s `XLARGE` with `XXLARGE → XLARGE` (§4.5). ⚠⚠ **And for a video on a platform
   with no frame decoder, original or not.** `JvmGetPreviewImplementation` only serves stored posters
   for video, which is why `asksForHeroByName` pulls the hero rung for videos even when `HasLocalData`
   is true. Gating the pull on "no original" would leave a desktop that downloaded a video with
   whatever poster rung it cached.
2. **The web lightbox**, through a new SDK read that names `XXLARGE` (§4.8).
3. **Timeline's moment hero** (`heroFidelity`, a 1600 px request), which also needs its only-on-null
   trigger fixed. It is the same defect `drive-synced-thumbnails.md` §16.2 fixed in Photos, still
   present in `TimelineProjectionRepository.photoBytes`.

⚠⚠ **Never a grid tile, never a feed row.** Tiles are capped at 1024 px by `photos-app.md` §33.
Anything that names `XXLARGE` for a list surface puts ~0.5 MB per row on the wire, which is §14.2's
trap at 3× the cost.

**Declaration** (§15.2 W-1): Photos and Timeline add `xxlarge` to
`declareWantedThumbnailClassesAtLaunch`. The pull itself declares it too, through `notePull` on
Android and desktop.

### 4.5 The progressive large preview

The viewer's question is *"the best picture of this photograph, as soon as possible"*. It becomes a
**stream** in place of today's one-shot `preview()`:

```
viewerPreview(path, requestPx): Flow<PhotoPreview>
  1. emit the best LOCAL answer at once (File.Preview — a stand-in if it is below the request)
  2. if a larger named rung is wanted (§4.4): select it — local-first, pulled otherwise — and emit
  3. if no device ever minted it and the mint-on-view policy allows (§4.6): mint here, and emit
```

⚠ **An upgrade identical to what is on screen is not emitted.** Step 1's `File.Preview` can already
return a local `XXLARGE`, and it returns bare bytes, so the flow cannot know the class. Step 2's
local-first select would then emit the same bytes again, and the UI decodes every emission (8–16 MiB,
briefly twice). The flow compares the bytes (length, then a hash) before emitting. That needs no API
change.

⚠⚠ **Step 3 needs a confirmed absence, never a failed read.** Today's `heroThumbnail` uses
`getOrNull()`, so its `null` means either "never minted" or "the fetch failed". Only a **confirmed**
absence reaches step 3. A failure (relay down, proxy restarting) keeps the stand-in and retries on
the next open. Otherwise a transient error, after the dwell, turns into a 6–18 MB original download
and a duplicate mint.
⚠⚠ **The select's `TResult` cannot carry that.** `materialisePendingOnDemandProperties` suppresses
`OnDemandResolution.resolve` failures, leaves a `PendingOnDemandProperty`, and returns the entity as a
successful select, and the decoder collapses the placeholder to `null`. X-3 therefore pulls the rung
through a dedicated operation on the ordinary typed channel (`docs/agent.md`):
- `TResult.Success(bytes)` when the rung is there;
- `TResult.Success(null)` when it is confirmed absent;
- `TResult.Failure` for a relay, decryption or proxy failure. Never a `Failed` payload member.

Only `Success(null)` may reach step 3.

Cases, and what the user sees:

| Case | Step 1 shows | Upgrade |
|---|---|---|
| **A** — `XXLARGE` exists on the relay | the local `LARGE`/`XLARGE` | swaps when the pull completes (~0.3–0.7 MB) |
| **B1** — not minted yet; the byte holder's backfill will | the local best | ⚠ **no signal**; the next open is sharp |
| **B2** — not minted; mint-on-view allowed | the local best | swaps when this device has minted it (§4.6) |
| **C** — this device holds the original | the D-17 answer | none needed |

⚠⚠ **No cross-device signal is needed for A or B2.** The upgrade is the tail of this device's own
coroutine, and a pull or a mint that completes *is* the signal. Only B1 has no signal, and it
stays so deliberately. §16.2 recorded that a class landing on the content plane changes no `File`
row, and the survey confirms there is no content-presence stream to observe. Building one (a
per-file presence row) is out of scope. B1 converges on the next open, which is §16.2's accepted
limitation.

**UI** (Photos). `PhotoPage` already keeps the picture on screen while a larger one loads
(`photos-app.md` §34, `HeroState.Ready.path`). It collects the flow instead of calling a suspend
loader. Each step decodes; a stand-in that never upgrades costs one decode.

⚠⚠ **Memory: budget against a square photograph, not §2's 16:9 frames.** A 2048 px rung decoded at
its own size is 9 MiB at 16:9, 12 MiB at 4:3 and **16 MiB square** (4 bytes a pixel). The pager keeps
three pages composed, so the steady state would be 36–48 MiB before the stand-in and its upgrade
briefly coexist during a swap: an OOM risk on a low-heap Android device. X-3 therefore:
- decodes to the **requested** edge (the viewer's measured size, `photos-app.md` §34), never the
  rung's own 2048 px. A ~1440 px phone view is ≤ 8 MiB square;
- releases the stand-in's bitmap as soon as the upgrade is on screen.

⚠ **Not `PreviewCache`.** The tile cache stays bounded to tile-sized entries; nothing large is kept
between compositions.

### 4.6 Mint on view — §12.6's T-2 / §15.4's W-3, made concrete

For case B2: an operation the client requests and the proxy performs end to end.

```
EnsureThumbnailClass(path, class) : IoOperation, TerminalOperation      // HR #3: non-suspend interface
  proxy (AsyncOperationImplementation):
    1. present? → return it                                                // a stat, like the backfill
    2. declined under MINT_FORMAT? → return null                           // the backfill's sentinel
    3. hasRoomFor(XXLARGE)? no → return null, nothing downloaded           // the backfill's quota gate
       metered and no USER_REQUESTED param? → return null                   // enforced HERE, not only in the UI
    4. onDemandResolution.resolve(File.Data)   → the original on disk      // OfflineKeeper's own call
    5. ThumbnailClassMinter.renderStill(…XXLARGE…) → store()               // the one mint, emit included (D-19)
    6. return the rung's bytes
```

- ⚠⚠ **Quota first, before the download** (step 3). At or near quota the relay would reject the
  rung, so minting it would download the original and journal ~1 MiB for nothing. The gate is the
  backfill's own `hasRoomFor` / `ensureWithinQuota`. If the emit is still refused, the rung stays
  local and unpublished, exactly as a quota-stopped backfill mint does today.
- **Feasible with existing doors.** `onDemandResolution()` is already called proxy-side by
  `OfflineKeeperImplementation`, `ShareLinkImplementation` and `ReadSharedFileImplementation`. The
  minter's `renderStill` and `store` are what the backfill uses. ⚠ The original never crosses IPC,
  which is §12.4's requirement.
- ⚠ **Side effect to keep.** `resolve()` calls `notePull` for every call. Resolving `File.Data`
  declares nothing, and that is correct.
- **Policy (D-18).** The trigger is a **dwell**: a page on screen for ≥ 1 s, never a swipe past.
  ⚠⚠ **"On screen" is the pager's settled current page, not a composed one.** `PhotoViewerScreen`
  sets `beyondViewportPageCount = 1`, and every composed `PhotoPage` starts its loader at once. A
  dwell timed from composition would therefore fetch two *unseen* originals (6–18+ MB each) and
  mint their rungs whenever one photo is open for a second. The flow's steps 1–2 may run for
  neighbours, as preloading does today. Step 3 (§4.5) runs only for the page whose index equals the
  pager's `settledPage` for the full dwell, and is cancelled when that changes.
  The download must be **unmetered**, or the user explicitly asked (zoom, a "sharpen" action).
  ⚠⚠ **The proxy enforces it, not only the UI.** Content pulls are not stopped by the sync metered
  gate (`SyncSchedulerGateTest.closedMeteredGate_stillPulls`). Without the check in the op, a network
  that turned metered after the UI's check, or any other caller, silently downloads a 6–18 MB
  original over cellular. An explicit `USER_REQUESTED` param is the override for zoom and sharpen.
  ⚠ The download is the whole original: 6.7–9.5 MB for these phone frames, far more for a DSLR.
- **Afterwards this device holds the original.** It is now a byte holder for that file, can serve
  later sizes by render, and its backfill mints any other wanted class.
- ⚠⚠ **Privacy (HR #2).** No per-file demand signal syncs, so §10.3's rule is kept. What the relay
  can learn, corrected 2026-09-17 after review (this bullet had claimed *"the addresses do not
  link"*, which is false):
  - ⚠⚠ **The rung links to its original at the edge layer.** A declared content edge is
    `(holderHash, propertyId, contentId)` with `propertyId` **in the clear**
    (`threat-model.md`, the content-plane refcount residual). The rung's edge and the file's
    `File.Data` edge share one `holderHash`. The rung's *ref* is unlinkable
    (`derivedThumbnailContentId`); its *edge* is not.
  - That grouping is **not new to mint-on-view**. Every upload-time and backfill-minted rung already
    declares the same edge, and the threat model records the grouping as an accepted sharpening.
  - The original's `GET` already dates the read, as every open of an original does today.
  - What a view mint adds is only *"this device produced the derivative"*. A byte holder's backfill
    discloses exactly that about every file it mints.
  - ⚠⚠ **What a delay can and cannot hide.** It cannot hide *which* file owns the rung; the edge
    joins them. It **can** weaken one thing: publishing right after the original's `GET` marks that
    read as a viewer dwell, rather than an offline-keeper fetch or a download.
  - ⭐ **D-19 settled: inline** (the maintainer, 2026-09-17), accepting that residual. The rejected
    alternative, a delay, weakens only that correlation. It would cost:
    - a bounded pending state, which cannot be exempt from eviction or a long viewing session
      fills the disk;
    - a publish that does not need the original, which may be evicted meanwhile;
    - a re-mint path for an evicted pending rung.

    ⚠ The residual goes into the threat model with X-4 (§6).

### 4.7 Prerequisite — stored thumbnails are never evicted locally

`File.kt:647`: *"That eviction wiring is still not built."* `LocalCacheEviction` is fed only `File`
paths; nothing ever removes a `/.thumbnails/<contentId>/<class>` blob. `retention-quota-model.md`
classes these as tier 3 (a re-fetchable cache), and the ceiling has been theoretical while rungs were
4–150 KB.

**`XXLARGE` makes it real.** A desktop whose viewer is swiped through 5 000 photographs keeps ~2.5 GB
of rung blobs forever. ⚠⚠ **Mint-on-view also keeps originals, and nothing bounds them.** They are
`File.Data`, so the local-cache ceiling governs them in principle — but `MaxLocalBytes` defaults to
`0`, meaning *no ceiling*, on the stated condition that the default would move "when its settings UI
does". The UI has since shipped and the default has not, so the only always-on pressure is the
free-disk floor, which acts when the disk is nearly full — the worst moment to begin evicting.

⚠⚠ **This is X-4's one accepted regression, and it is deliberately not fixed here** (§9.6). Moving
that default is a change to what every existing install does to bytes already on disk, and it owes
more than a constant: per-file `drive.offline.files` pins are **not** folded into
`ProxySyncRules.readFileSyncRuleSet`, so "files you keep offline don't count towards this" — the
sentence shipped in `strings.xml` and `SyncPreferencesView.swift` — is false for them today. It needs
its own change, with grandfathering and the copy corrected.

**Proposed (D-20):**
- An LRU bound on `/.thumbnails/` classes at or above `XLARGE`, by bytes, per device.
- ⚠⚠ **The ceiling is a number: 1 GiB by default**, as a device setting
  `FileSyncPreferences.MaxThumbnailBytes` beside Face B's `MaxLocalBytes`
  (`retention-quota-model.md`). 1 GiB is ~2 000 `XXLARGE` or ~7 000 `XLARGE` blobs, more than a long
  viewing session, and it bounds the 2.5 GB above.
  - ⚠⚠ **Not 1 GiB on the web.** `IndexedDbFileDatabase.open` reads every persisted entry into an
    in-memory `MutableMap<String, ByteArray>` at runtime start, so a 1 GiB rung cache would be
    1 GiB of browser heap before eviction could run. The JS runtime gets a small ceiling (e.g.
    64 MiB) until that eager hydration is replaced. X-5's pulls and browser-minted images both fill
    it.
  - ⚠ **Unlike `MaxLocalBytes`, `0` does not mean unlimited here, and it is not the default.** A
    file's cache holds bytes the user asked for; these blobs are pure re-fetchable derivatives.
- **Face B's free-disk floor applies too** (`MinFreeSpaceBytes`, shared): below it, rungs are evicted
  under the ceiling.
- The selection reuses `selectCacheEvictions`' shape: coldest first, stop once under.
- ⚠⚠ **Recency must be recorded on read.** `selectCacheEvictions` orders by
  `FileDatabase.lastModifiedMs`, which is the write or fetch time; no platform database updates it on
  `readBytes`. Reused as is, a rung viewed daily would stay the coldest and be evicted and re-pulled
  over and over. X-2 adds a portable touch on a successful rung read (a sidecar stamp, or setting the
  file's modified time). Its test reads an old rung and asserts that a newer, unread one goes first.
- ⚠⚠ **A rung with a deferred emit is evictable, but not as "done".** Every `store` journals a
  deferred content emit when the metered gate is closed or an upload fails. The journal has no
  ceiling, so exempting those blobs would leave them unbounded. Evicting one writes **no** `.evicted`
  sentinel and drops its journal entry. The class reverts to unminted, and a byte holder's backfill
  re-mints and re-emits it later. The `.evicted` sentinel is only for a rung already on the relay.
- ⚠⚠ **The `.evicted` sentinel is trusted only while the relay still has the rung.** It stops the
  byte holder re-minting, and nothing else would restore the rung if the relay copy were later
  reclaimed or lost. `ContentRepair` deliberately excludes thumbnail properties, expecting
  regeneration, and a byte-less device cannot clear another device's sentinel. So the sentinel
  carries its write time. Once it is older than a revalidation interval, the backfill checks that
  the rung is still **fully resolvable** on the relay (batched, like the declarable walk), before
  counting it as present:
  - resolvable: the stamp is renewed;
  - not resolvable: the sentinel is deleted and the class re-minted;
  - unknown (offline, relay error): the sentinel is kept, and the check retried next pass.

  ⚠ A manifest alone is not enough. Reclamation deletes chunks before the manifest, so an
  interrupted one leaves a manifest over missing chunks. `SyncObserver.contentResolvableOnRelay`
  already checks every chunk for exactly that ordering. ⚠ It is private and returns a `Boolean`
  today, so X-2 exposes it with a tri-state result. X-2 picks the interval.
- ⚠ **And the backfill does not mint while it cannot publish.** A pass with the content gate closed
  would otherwise render up to its budget every tick into blobs that can only wait or be evicted.
- `LIST` and `LARGE` are exempt: rows re-blank without them, and they are small.
- The access time is the pull or the read (recorded by the touch above).
- ⚠⚠ **An evicted rung must not look unminted to the backfill.** `ThumbnailClassBackfill` computes
  `wanted − present − declined`, and `present` is a local stat. On a device that still holds the
  original, an evicted `XLARGE`/`XXLARGE` would be re-rendered, re-stored and re-emitted on the next
  tick, every tick: up to the backfill budget each time, refilling the cache over its own ceiling.
  Eviction therefore writes an **`.evicted` sentinel beside the blob**, the same shape as §15.11's
  `.declined` one:
  - local only, never synced;
  - keyed by `contentId`, so a re-upload starts clean;
  - counted as present by the backfill;
  - removed when a pull or a mint stores the class again.
  The relay copy is unaffected: the file's declared edge is still live (`content-plane-refcount.md`).
- ⚠⚠ **Built before X-1**, not only before the first consumer pull. X-1 already mints `XXLARGE` at
  every upload and in the backfill (512 MiB a constrained pass), so a large library accumulates
  gigabytes with no viewer involved.
- **Acceptance:**
  - past 1 GiB, the coldest rung blobs go first until the total is under, and no `LIST` or `LARGE`
    blob is touched;
  - under the ceiling but below the free-disk floor, rungs are still evicted;
  - on a byte holder, a backfill pass after an eviction mints and emits nothing for the evicted class.

### 4.8 The web

- **SDK.** A new `FileSystem.getLargePreview(path, px)`:
  - names `XXLARGE → XLARGE` by relation, never `File.Preview`, per `drive-web-parity.md` §1.2i;
  - falls back to the existing LIST/LARGE read;
  - ⚠⚠ **returns which class supplied the bytes**, and whether the `XXLARGE` read *succeeded* with
    nothing there or *failed*. The JS bridge returns bare bytes today and `readPreviewJs` discards
    the class. Bytes alone cannot drive the lightbox's fallback below: it would stay blurry forever,
    or download the original on every open;
  - leaves `readPreviewJs`'s LIST/LARGE selector for list rows untouched.
- **Lightbox.** `FilePreview.tsx` shows the large preview first and loads the original only for
  "download" or an explicit full-resolution view, **or when the result is undersized**: the SDK
  reports `XXLARGE` confirmed absent **and** the class it did return is smaller than the requested
  `px`. A ≤ 1024 px lightbox served `XLARGE` is covered and downloads nothing. A failed read keeps the
  stand-in, as in §4.5.
  ⚠⚠ That last fallback is load-bearing. Nothing on web registers the backfill schedule (§3: Android
  and desktop only), so in a web-only realm an old file never gets the rung. Without the fallback,
  X-5 would replace today's original-backed lightbox with an `XLARGE` that never sharpens. In that
  case `XLARGE` is the stand-in while the original loads, as today.
- ⚠ `FileThumbnail.tsx` hard-codes `image/webp` for bytes that are JPEG. It happens to decode, but a
  sniffed type should replace it while this file changes.
- **Declaration.** The web neither declares wanted classes nor wires `notePull`.
  ⚠⚠ **This bullet said "wire the launch declaration in the JS SDK (X-5)" and X-5 could not do it —
  the blocker is a missing service, not a missing call.** Both halves write a `DeviceSetting` row,
  and the **preference service is not registered on web at all**: `FirstPartyPlugins` lists seven
  services and that is not one of them, and `:file-service-proxy`'s dependency on
  `:preference-service` is `implementation`, so the types are not even on the JS SDK's classpath.
  Registering a service on web is a schema-list change with announce and version consequences
  (`inference-service.md` §17), not a wiring line, so it is its own change.
  ⚠ **Shipped 2026-09-21 (§9.10)** — the preference service is registered on web now, and both
  halves are wired.
  ⚠⚠ Wiring `notePull` alone would have been **worse than leaving it**: `WantedThumbnailClasses
  .declare` swallows every `Throwable`, so on web it would fail silently for ever while looking
  wired — the exact shape `JsRuntime`'s own KDoc records four times ("a default that answers
  'nothing' is indistinguishable from an unwired seam"). Left unwired, visibly.
  ⚠ It only ever helps where a native device runs the backfill, and produces nothing in a web-only
  realm — which is why the lightbox fallback above is what makes X-5 correct without it.

---

## 5. Decisions

Numbered after `drive-synced-thumbnails.md`'s D-1…D-11. ⭐ **All settled 2026-09-17**: the maintainer took every recommendation below as written. Review later amended several, and re-opened D-19, which the maintainer then settled as inline; each amendment is marked in its row.

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D-12** | A larger stored rung exists | ⭐ **Settled** by the maintainer (§17.4) | Web and DSLR originals; photo-heavy users hold the storage subscription |
| **D-13** | Edge and cap | **2048 px, 1 MB cap** after the q65/q50 step-down — ⭐ **locked 2026-09-18** once §2.1 measured all four encoders | 2048 covers phones fully and retina at 1.45×; 2560 costs ~45 % more for retina only (§2). ⚠⚠ The cap holds on every platform **because of the step-down**, not on its own: iOS encodes ~1.65× heavier at the same nominal quality (§2.1), so a detailed frame that fits at q80 elsewhere fits at q65 there. A real photograph is admitted everywhere; the frames the cap refuses are noise-like ones nobody photographs |
| **D-14** | Ladder rule | smallest covering local rung; else the largest local **hero** rung (`XLARGE` and up) within tolerance 2.0; render above | One more row in an existing rule; local-only keeps feed rows safe. ⚠ Worded "on the largest" until 2026-09-17, which contradicted §4.2's table; the table was the intent (§9.1). ⚠⚠ The `≤ 4096` row shipped **inert** in X-1 (`stretches = false`) and X-3 turned it on, because D-17 is what makes it safe and D-17 had no caller until then — §9.5 |
| **D-15** | Mint at upload | **unconditionally, like `XLARGE`**, no D-8 gating; video only where a frame decoder exists | Phones want it (§1), so every Photos realm declares it anyway |
| **D-16** | Backfill budget | **512 MiB / 1000 files per run under a driver that enforces idle + charging + unmetered; today's 32 MiB / 250 files under the in-process drain** | ~25 nights → ~3 for a 1575-file library. ⚠⚠ Amended 2026-09-17 after review: the in-process `ScheduleDrainLoop` enforces no constraint (§4.3), so 4× the budget there is minutes of decode and encode on an active, battery-powered device. X-1 picks the mechanism: enforce `ConstraintSpec` in-process, or hand the budget to the op per driver |
| **D-17** | Byte holder above 1024 px | **render from the original above 2048 px, and whenever `XXLARGE` is not local**; the local rung between | Drops the forced render only while the rung is local, and forces through an explicit `GetPreview` param at the requested edge, never an inflated request (§4.2). ⚠ Chosen cheap-and-uniform over always-the-original; between 1024 and 2048 px the rung is a downscale, never a stretch. ⚠⚠ Amended 2026-09-17 after review: without the local rung, D-14 would stretch `XLARGE` over the original the device holds (§4.2). ⚠⚠ Amended again 2026-09-18 after the X-1 review: a tolerance band nobody can decline is not a default, it is the whole rule. **Built in X-3** as `File.ViewerPreview`, a second property over the same resolver rather than a third parameter on `File.Preview` (§9.5) |
| **D-18** | Mint on view | **yes, on dwell ≥ 1 s + unmetered**, or on an explicit zoom | The only route for a file whose byte holder is offline or gone |
| **D-19** | When a view-minted rung publishes | ⭐ **Inline, like any mint** — settled by the maintainer 2026-09-17 after review re-opened it; quota-gated before the download | First settled as "next backfill pass", on the premise *"the addresses do not link"*, which review showed false: the declared edge joins the rung to its original by `holderHash` in the clear (§4.6). A delay would still weaken *"that read was a view"*, but only at the cost of a bounded pending state; inline accepts that residual, recorded in the threat model with X-4 |
| **D-20** | Local rung eviction | **LRU by bytes for `XLARGE` and up, ceiling 1 GiB per device (a device setting) plus Face B's free-disk floor — X-2, landing before X-1** | Without it the rung is an unbounded local cache (§4.7). ⚠ The ceiling was unspecified until review on 2026-09-17, which left X-2 without an acceptance test. ⚠⚠ Eviction leaves an `.evicted` sentinel the backfill counts as present, or a byte holder re-mints and re-uploads every evicted rung each tick |

---

## 6. House rules

- **HR #1.** Unaffected: another ciphertext object per file on the content plane.
- **HR #2.** Amend A5 with the phase that mints:
  - a sharper at-rest derivative on every device that pulls it;
  - the relay sees one more object per file (size-bucketed ciphertext, ~0.3–1 MB).
  - With X-4, record §4.6's analysis: the rung's edge joins its original by `holderHash`, as every
    rung's does, and a view mint adds *"this device minted it"*. ⚠⚠ **Also record the timing
    residual D-19 accepts**: a rung published right after the original's `GET` marks that read as
    a viewer dwell. The contract must not omit a known relay-visible access pattern.
  - The A6 wanted-class row needs no change; it already names `xxlarge`.
- **HR #3.** `EnsureThumbnailClass` is an `IoOperation` with a non-suspend interface and an
  `AsyncOperationImplementation`, the `ReadSharedFile` shape.
- **HR #8.** X-1 touches Android's upload render and the actuals, so the smoke gate runs.
- **HR #9.** X-1 is **FileService v12** with an empty migration, the property-kind baseline row, and
  the regenerated shape baseline. The property is dynamic, so the shape baseline is expected to report
  only the version column. Prove that silence, as §13.2 of the video doc did.
  ⚠ D-17's optional `GetPreview` param is the exception: an optional op param **does** reshape, as
  `QUALITY` did at v11. It rides the same v12 bump so it does not cost a second lockstep.
  ⚠ The client lockstep that applied to v11 today applies again: every device updates together.

---

## 7. Phases

| # | Slice | Notes |
|---|---|---|
| **X-0** ✅ | **One table for the still rungs**: class id, property, px, cap, format, category gate. `ProxyDeclarableContent`, the backfill's `canMint` / `mint` / `capFor`, the resolver ladder and the test hand-lists read it. | No behaviour change. Removes §3's three silent `else` defaults *before* a fifth rung depends on them. `MotionThumbnailClassTest`'s "exactly three stills" becomes "every still in the table". **Built — §9.1.** |
| **X-1** ✅ | **Prerequisites: X-2 landed, and §4.3's generation-call defect fixed. The rung**: constant, property, FileService v12, mint at upload, 1 MB cap (**after** measuring Android, iOS and browser encoders at 2048 px, §8 Q2), declarable, ladder row (D-14), backfill budget (D-16), and D-17's force-original `GetPreview` param (it reshapes the op, so it rides the same bump). | HR #2/#8/#9. **Built — §9.4.** |
| **X-2a** ✅ | **Eviction** (D-20): the ceiling, the sweep, the read stamp, the `.evicted` sentinel. | ⚠⚠ **Lands before X-1** (order X-0 → X-2 → X-1 → X-3…): X-1's upload and backfill mints fill the disk on their own (§4.7). **Built — §9.2.** |
| **X-2b** ✅ | **The sentinel's revalidation** (§4.7): a tri-state resolvability check on the relay past an interval, and a backfill that does not mint while it cannot publish. | Both needed a new seam on `FileContentSyncEmitter`. **Built — §9.3.** |
| **X-3** ✅ | **The Photos viewer**: `viewerPreview` flow, `XXLARGE → XLARGE` named pull, declaration at launch, D-17, decode at the requested edge (§4.5), **and D-14's top row, which X-1 left off**. | HR #2/#8/#9. **Built — §9.5.** |
| **X-4** ✅ | **Mint on view** (`EnsureThumbnailClass`, D-18, D-19). | Inline publish (D-19). Proxy-enforced unmetered unless `USER_REQUESTED`, settled-page dwell, quota gate; threat-model note (§4.6). **Built — §9.6.** |
| **X-5** ✅ | **Web**: `getLargePreview`, lightbox with the original as fallback when no `XXLARGE` exists (§4.8). ⚠ The launch declaration did **not** ship — web does not register the preference service at all (§9.7). | Web Drive has the only full-screen surface on web today. **Built — §9.7.** |
| — | ⚠ **Every phase that dispatches a new operation adds it to the dispatching app's bundled manifest** (`BundledAppManifestSource`): ~~X-3 (Photos: the rung pull)~~ ⚠ **wrong — a rung pull is a relation projection over ops Photos already dispatched, so it adds no operation and owes no entry (§9.6)**, X-4 (Photos: `EnsureThumbnailClass`), X-6 (Timeline: the rung pull), with their parity-test expectations. Control derives an app's capabilities and "Services used" from those entries, so an unlisted op leaves an original download undisclosed. | |
| **X-6** ✅ | **Timeline hero**: named `XXLARGE` pull and the only-on-null trigger fix (§4.4). | Separate because Timeline's feed must stay tile-sized. **Built — §9.8.** |
| — | **Census** (optional): a local op reporting per class the files present, declined and missing, plus bytes. | Replaces the one-off counts in §15.10 / §17.1 and informs D-16. |

---

## 8. Open questions

1. ~~**D-17** is a product choice~~ — **settled 2026-09-17**: the local rung up to 2048 px, a render
   from the original above it or when the rung is not local.
2. ~~**Android, iOS and browser encoders at 2048 px**~~ — **measured 2026-09-18, §2.1.** Android and
   the browser match the JVM; **iOS is ~1.65× heavier at the same nominal quality**, so it lands one
   step-down lower for the same picture. D-13's cap is locked. ⚠ Left open by it: whether iOS should
   be *calibrated* (asking for a lower nominal quality there so a "q80" means the same thing on every
   platform) — that would shrink today's `LIST`/`LARGE`/`XLARGE` on iPhone too, so it is its own
   decision, not X-1's.
3. **Does B1 need a signal after all?** If the maintainer finds the "sharp on next open" behaviour
   poor on a phone that backfills overnight, the smallest signal is a local per-file "a larger rung
   arrived" row written by `FileContentManifestApplier`. It would be observed by a viewer that is
   open at that moment, and it is a new composite (HR #9).
4. **D-8 for `XXLARGE` on a realm with no Photos at all** (Drive-only users). §1 argues Photos makes
   the rung universal, but a Drive-only realm would pay ~0.5 MB per uploaded image for nothing. If
   that matters, gate on the wanted set for this rung only.
5. **Remediating mislabeled rungs already published** (§4.3's defect). A desktop that backfilled
   `XLARGE` for local videos stored smaller classes under that name. They cannot be told apart
   without decoding each blob's edge. Options: a one-off pass that decodes the edge and re-mints the
   undersized ones (on a device with a frame decoder), or accept them until the file changes.
6. ~~**D-19: publish a view-minted rung inline or delayed?**~~ — **settled 2026-09-17: inline** (§4.6,
   §5). The relay learns *"that read was a viewer dwell"*; X-4 records it in the threat model.
7. **`DeleteFile` leaves a file's thumbnail blobs on disk.** It deletes the file's own bytes and
   records the orphaned content ids, but never the `/.thumbnails/<contentId>/*` blobs, and the
   keeper's sweep cannot see them once the row is gone (§9.2). Pure local leak; no relay effect.

*Raised by the X-1 review (2026-09-18) and deliberately not fixed in that PR — each is real, none
blocks the rung:*

8. ⚠⚠ **The 1 MiB cap was measured against photographs, and the class that broke the last cap was
   dense text.** §2.1's set contains no high-detail 2048 px source, and §17 is the record of
   `XLARGE`'s cap refusing exactly those — a 4K screenshot of text, a scanned page, and **a PDF first
   page, which all three actuals rasterise at exactly `MAX_PDF_RENDER_PX = 2048`**, i.e. always at
   full rung size. Scaling §17.3's measured dense-text frame suggests ~0.94–1.2 MB at q50 on
   JVM/Android and ~1.55–2.0 MB on iOS, which the cap would decline outright. ⚠ On iOS that decline
   is terminal: no backfill is registered there (§4.3), so the upload is the only attempt that device
   ever makes. One measurement settles it; until then D-13 is locked on photographs only.
9. **A source below 2048 px mints two identical blobs.** No render upscales, so a 1200 px photo
   produces byte-identical `XLARGE` and `XXLARGE` pictures under two derived content ids — two
   manifests, two chunk `PUT`s, two quota charges. A rung whose edge the source cannot fill could be
   skipped, but a skipped class reads as *missing* to the backfill, which would retry it forever;
   the fix needs a sentinel or a presence rule, not a guard.
10. **The upload pays the fourth still render before the entity is saved.** `MOTION` was deliberately
    moved *after* `saveData` because minting first delayed the row every peer waits on; the stills
    loop still runs before it. Measured at 119–212 ms per render (§2), ×3 when the step-down runs, and
    on Android each pass is a full-resolution `BitmapFactory` decode inside the Control proxy, which
    has OOM history. §4.3's "the extra cost is one more render per upload" does not count the
    step-down multiplier.
11. **The web mints a rung nothing there can evict.** Browser uploads mint the full ladder, but JS
    registers no `OfflineKeeper` (§4.7 / X-2a), so ~0.65 MB of `XXLARGE` accumulates per upload in
    IndexedDB with no ceiling. X-5 owns the web surface; the *accumulation* starts with X-1.
12. **Both upload quota pre-flights count only the original's bytes.** Pre-existing — the derived
    thumbnails were never counted — but the per-file overshoot grows by up to 1 MiB, roughly 3× the
    previous derived total.
14. **The backfill's presence walk probes classes the run does not want.**
    `presentClasses` iterates every class in `FileBytesPropertyWriter.THUMBNAIL_CLASSES`, not the
    run's `target`, and a class missing locally with an `.evicted` sentinel older than a week spends
    one of `MAX_REVALIDATIONS_PER_RUN`'s 50 relay checks (§9.3). After X-1 that is reachable without
    anyone declaring the rung: an uploading phone mints `XXLARGE`, the keeper evicts it under the
    ceiling, and seven days later the pass revalidates a sentinel for a class nothing wants. ⚠ It
    delays rather than starves — classes are probed in ladder order per *file*, so `XLARGE` takes its
    slot before `XXLARGE` on the same row and the budget covers ~25 files instead of ~50 — and X-3
    removes the waste by putting the class in `target`. The fix if it is wanted sooner is to hand
    `presentClasses` the target set, which is exactly equivalent (`target - present` discards the
    rest anyway) and changes a port signature, so it wants its own PR.

13. **`best`'s last-resort pass is largest-first**, so a row with no `LIST` but a pulled `XXLARGE`
    hands a 100 px Files row a 2048 px blob (~8 MB decoded). Pre-existing shape, doubled by the new
    rung, and mitigated in practice because `LIST` is minted at every upload. ⚠ Changing the order
    would break X-0's proven equivalence, so it needs its own argument, not a tweak.


---

## 9. As built

### 9.1 X-0 — one rung table (2026-09-17)

**The table.** `ThumbnailClasses` (`file-service-proxy/.../proxy/ThumbnailClasses.kt`) holds every class
as a `ThumbnailRung`: `Still(class, property, px, maxBytes, backfilled, stretches)` or
`Motion(class, property, maxBytes, backfilled)`. `STILLS` is ordered smallest edge first; `ALL` is
`STILLS + MOTION`. `canCarry(category, path, canRenderMotion)` is the category gate.

**What reads it now.**
- `FileBytesPropertyWriter`'s property map, and through it everything §3 listed as automatic.
- `ProxyDeclarableContent.THUMBNAIL_PROPERTIES`. ⚠ §3 item 6's second hand list is gone.
- The backfill's `canMint`, `mint` and `capFor`. ⚠ §3 item 7's three `else` defaults are gone: a
  class the table does not know is declined by lookup, and a backfilled rung is minted by its kind.
- The upload's still renders and emits, and the `MOTION` render and emit.
- `StoredThumbnailLadder.best` / `.covering`, which replace the three `GetPreview` actuals'
  identical private copies (§3 item 8). ⚠ Both resolve lazily (below).
- `MotionThumbnailClassTest` and `DeclaredContentIsWalkableTest` (§3 item 10, first two).
- The JS SDK's list-class selector reads `GetStoredThumbnail.LIST_PX` instead of a private `100`.

**Deviations from the X-0 row.**
- ⚠ **No format column.** Every still is JPEG and `MOTION` has its own encoder, so the column would
  hold one value. Add it the day a rung needs a different format.
- ⚠⚠ **A `stretches` flag was added, and D-14's wording changed with it.** A rule that stretched
  "the largest rung" is exactly equivalent today, but at X-1 it would silently stop stretching
  `XLARGE` and contradict §4.2's table: a device holding only `XLARGE` would read the whole original
  for 1500 px. `covering` now takes the smallest covering local rung, then the largest local rung
  that `stretches` within tolerance. `LIST` and `LARGE` do not stretch, `XLARGE` does.
  `StoredThumbnailLadderTest` asserts §4.2's table against a synthetic fourth rung **now**, so X-1's
  row is proven before it exists.
- ⚠⚠ **`best` reads lazily**. The copies resolved every class before choosing.
  `best` serves every ≤ 100 px Files row, so on a device holding a file's `XLARGE` each row read that
  blob (up to 512 KB) and discarded it. `XXLARGE` would have doubled that. The answers are unchanged;
  a row whose `LIST` is local now reads only `LIST`, and no class is read twice.

**How "same answers" is proven.**
- `StoredThumbnailLadderTest` compares both rules with a verbatim copy of the old code. It covers
  15 request edges × all 8 presence combinations. The reads are pinned separately: each class at
  most once, and only when the rule could serve it.
- `ThumbnailClassesTest` pins:
  - edge order;
  - unique classes and properties;
  - the writer round trip;
  - `stretches` being a suffix of `STILLS`;
  - the backfilled rungs equalling `ThumbnailPreferences.DECLARABLE_CLASSES` (in `:file-service`,
    which cannot see the table);
  - `canCarry`.
- Nine mutations were run, and the intended test caught every one:
  - each band edge;
  - the backfilled flag;
  - eager reads in `covering` and in `best`;
  - re-reads in `best`'s fallback;
  - a motion read in `best`;
  - the `stretches` guard;
  - the lower-bound filter.

**What X-1 still does by hand** — §3, reduced. ⚠⚠ X-1 lands after X-2 (§7):
1. `GetStoredThumbnail.CLASS_XXLARGE` / `XXLARGE_PX`.
2. The `File.kt` property and its `FileServiceCommonModule` registration.
3. HR #9 (§6), including D-17's optional force-original `GetPreview` param in the same v12 bump. With the param set, the resolver runs `covering` without its tolerance step, then renders from the original (§4.2).
4. **One `ThumbnailClasses` row** plus its cap constant, the cap locked only after §8 Q2's Android, iOS and browser measurements, and §4.3's generation-call defect fixed first: `backfilled = true`, `stretches = true`,
   appended to `STILLS`. Upload, backfill, declarable walk, writer and resolver follow.
5. `ThumbnailPreferences.DECLARABLE_CLASSES`. ⚠ `ThumbnailClassesTest` fails until it matches.
6. The backfill budget (D-16), including its in-process half.
   ⚠⚠ **Not the client pulls** (§3 item 9). They belong to X-3, X-5 and X-6, after X-2's eviction:
   D-20 requires the bound to exist before any consumer pulls the rung.
7. ⚠ Counts outside `:file-service-proxy`, which cannot see the table:
   - `SchemaDrivenContentWalkTest`'s `5` (`:sync-service-proxy`);
   - `ContentRepair`'s "four thumbnail" KDocs;
   - `content-plane-refcount.md` (§3 item 12).
8. The band tests that enumerate today's rungs:
   - `JvmGetPreviewImplementationThumbnailFallbackTest`;
   - `SyncedThumbnailSystemTest`;
   - the actuals' `storedThumbnail` KDocs.
9. The threat model (§6).

### 9.2 X-2a — the thumbnail cache has a ceiling (2026-09-17)

**The decision.** `selectThumbnailEvictions` (`file-service-proxy/.../impl/ThumbnailCacheEviction.kt`),
the twin of Face B's `selectCacheEvictions` one plane over: coldest first, stop at the first blob that
brings the total under, unknown recency last, ties by path. Two pressures, whichever demands more —
the ceiling and Face B's shared free-disk floor.

**The ceiling.** `FileSyncPreferences.MaxThumbnailBytes`, a device setting defaulting to 1 GiB.
⚠ As D-20 asked, `0` means *keep no cache* rather than *unlimited*; a **negative** value is the
no-ceiling escape hatch. No UI yet — `MinFreeSpaceBytes` is the precedent for a proxy-only setting.

**What it evicts.** Of **unpinned** files, exactly the **backfilled** rungs (`XLARGE` and `MOTION`
today, `XXLARGE` at X-1):
the classes a byte holder can re-mint and any device can pull again. That is one predicate,
`ThumbnailCacheSidecars.isEvictable`, reading the rung table rather than a second list of names.
`LIST` and `LARGE` are never offered.
⚠⚠ **A `KEEP_OFFLINE` file's classes are exempt**, unlike the rung's own ceiling elsewhere: "keep this
offline" cannot mean "and blank its picture", and the hub pins `/` and runs this pass every backup —
without the exemption it would trim the one archive that exists to hold everything.

**Where it runs.** A third half of the `OfflineKeeper` pass, after the download and the file-cache
evict halves, so the floor sees the disk as they left it.
- ⚠⚠ **The candidates are derived, not enumerated.** `FileDatabase` cannot list a directory on any
  platform, and a rung blob has no `File` row, so the sweep walks the paths the keeper already
  collected and asks each row's `contentId` × the evictable classes — the shape the backfill's
  `presentClasses` already uses. Cost: one `contentId` resolve and two stats per file per pass.
- ⚠ A blob whose file row is gone is therefore invisible to it. `DeleteFile` never deletes a file's
  thumbnail blobs either, so those leak on disk today; that is its bug to fix, and it is recorded in
  §8 Q7 rather than smuggled into this slice. ⚠ They are also outside the ceiling's denominator, so a
  device can hold its 1 GiB of visible rungs plus whatever the leak left.
- ⚠ Cost per pass: a `contentId` resolve and two stats per file, plus a stat (and, when present, a
  read) of the `.read` stamp per existing blob. The journal probe is paid **only** for the blobs
  actually being evicted, not for every candidate.
- ⚠⚠ **A delete is verified, not assumed.** `FileDatabase.deleteFile` returns `Unit`, its interface
  default is a no-op and the file-backed impls discard `File.delete()`'s result, so the sweep re-stats
  the path: a blob that survived must not be marked evicted, or the backfill is told it is safely on
  the relay while this device freed nothing.

**Recency is recorded on read.** `GetStoredThumbnailImplementation` writes a `.read` stamp beside the
blob, at most once per `READ_STAMP_INTERVAL_MS` (6 h) — and never on a stamp from the future, which a
clock jump would otherwise freeze forever — and only for evictable classes — a list row's
`LIST` read still costs exactly one read. The sweep prefers the stamp and falls back to
`lastModifiedMs`, so a blob pulled and never opened is colder than one rendered daily.
⚠ The stamp is an epoch-ms number in the file, not a filesystem mtime: `FileDatabase` cannot set one,
`InMemoryFileDatabase`'s is a write counter, and the JS store has none at all.

**The sentinel.** An eviction writes `<class>.evicted` beside the blob (stamped with the time), and
`BackfillThumbnailClassesImplementation.presentClasses` counts it as present. Without it the next
backfill tick re-renders and re-emits everything the sweep just freed, up to the whole budget.
- ⚠⚠ **A blob whose bytes the relay has not taken yet leaves no sentinel.** The sweep reads
  `FileContentSyncEmitter.queuedContentBytesFor` to tell. It is still evictable — the journal has no
  ceiling of its own — but nothing published it, so a byte holder should mint it again rather than
  believe it is safe on the relay. The drain drops the orphaned intent when it finds the file gone.
- ⚠⚠ **The sentinel is cleared on the read path, not the write path.** A mint clears it
  (`ThumbnailClassMinter.store`), but a *pulled* class never reaches an `ExternalPropertyWriter`:
  since C7 it is a content-plane blob that `FileContentManifestApplier` promotes with a `moveFile`.
  So the self-heal that actually fires is in `stampRead` — a blob being read is by definition not
  "deliberately gone". The hook in `FileBytesPropertyWriter.write` is belt-and-braces for a path no
  peer takes today.
- ⚠ All three sidecars (`.declined`, `.evicted`, `.read`) live under `/.thumbnails/`, which
  `OrphanedBytesPolicy.isReservedNamespace` already excludes from the orphan sweep.

**Verified.** `ThumbnailCacheEvictionTest` (the decision: both pressures, ordering, unknown recency,
`0` vs negative), `OfflineKeeperThumbnailEvictionTest` (the wiring: the preference is read, the
coldest blob goes — ⚠ its fixture writes the *warm* blob first, so a sweep that ignored the `.read`
stamps would fail it — `LIST`/`LARGE` and pinned files are spared, queued bytes leave no sentinel, an
undeletable blob is not marked evicted, and a read clears a stale sentinel),
`ThumbnailReadStampTest` (stamped once per interval, never for a small class),
`BackfillThumbnailClassesOpTest.aClassTheKeeperEvictedIsNotMintedAgain`. Five mutations — an
always-sentinel, a presence probe blind to the sentinel, a no-op stamp, an evictable `LIST`, and a
warmest-first order — were each caught by the intended test.

**Known, and accepted.**
- ⚠ Under sustained disk pressure the **floor** can demand more than the whole cache, so a pass
  evicts all of it; a viewer then pulls a rung back and the next pass drops it again. That is what a
  cache under pressure does — the file cache's half behaves the same — but here each eviction also
  leaves a sentinel, so the byte holder stops re-minting rather than re-rendering on every pass.
- ⚠ A realm that stops wanting a class keeps its sentinels: nothing enumerates them for cleanup.

**Deliberately not in this slice.**
- **X-2b** (§9.3): the sentinel's revalidation against the relay, and the backfill not minting while
  the content gate is closed. Both needed `FileContentSyncEmitter` to answer questions it could not
  when X-2a was written.
- **The web ceiling** (§4.7): the JS runtime registers no keeper schedule at all, so there is nothing
  to give a smaller ceiling to yet. X-5 owns it.

### 9.3 X-2b — the sentinel is only believed while the relay still has the class (2026-09-17)

**Two seams on `FileContentSyncEmitter`**, both with defaults that make an emitter which cannot
answer harmless:
- `contentResolvability(targetEntityId, contentId): ContentResolvability` — `RESOLVABLE` / `ABSENT` /
  `UNKNOWN`, defaulting to `UNKNOWN`;
- `canPublishContentNow(): Boolean`, defaulting to `true`.

**The tri-state.** `SyncObserver.contentResolvableOnRelay` was private and `Boolean`; it is now the
public `contentResolvability`, and `ensureContentOnRelay` is `== RESOLVABLE`, so **its behaviour is
unchanged** — anything but a complete yes still hands the bytes off.
- ⚠⚠ Every no-answer path says `UNKNOWN`: a locked realm, no realm context, no store, an
  undecryptable manifest, a manifest naming other content, an empty chunk list, and any unknown from
  a chunk presence check. Only the relay *answering* "not here" is `ABSENT`.
- ⚠ `getChunk` cannot tell a missing blob from a failed request, so when the manifest read fails the
  code asks `hasChunk` — **only on that path**, so a healthy check still costs one round trip.
- ⚠ One definite `false` among the chunks is `ABSENT` (the content cannot be assembled either way);
  an unknown among them keeps the whole answer `UNKNOWN`.

**The revalidation.** `BackfillThumbnailClassesImplementation` treats an `.evicted` sentinel as
present only while it is younger than `EVICTED_REVALIDATE_INTERVAL_MS` (**7 days**, against a daily
pass). Past that it asks the relay about the rung's *derived* content id:
- `RESOLVABLE` → the stamp is renewed, buying another interval;
- `ABSENT` → the sentinel is deleted and the class minted again, with a `report` line naming it;
- `UNKNOWN` → the sentinel stays and the next pass asks again.

⚠ Only a device that could **re-mint** the class ever clears its sentinel: elsewhere an `ABSENT`
answer would drop the mark and still mint nothing, which is strictly worse than keeping it.

⚠ Bounded at `MAX_REVALIDATIONS_PER_RUN` (**50**) relay checks per pass — reset at the top of each
run, not left to the fact that production builds the implementation per dispatch: each is a manifest read plus
a presence check per chunk, and a library of evicted classes must not turn one scheduled pass into
thousands of round trips. What waits keeps its sentinel — the cost of deferring is a class staying
evicted, which it already is.

**The publish gate.** `ThumbnailClassBackfill` takes a `canPublish` port, checked before anything is
**rendered** (the realm's wanted set is read first, which is one store read), that ends the run with `skipped = "content cannot be published right now"` and no
marker. ⚠ It ends the run rather than skipping a file: a pass that mints into a closed metered gate
or a quota-refusing relay fills the disk with blobs that can only wait in the journal or be evicted,
up to the whole budget, every tick the gate stays shut.

⚠ `presentClasses` is now a `suspend` port on the pass, because presence can reach the relay.

**Verified.** `EnsureContentOnRelayTest` gains the tri-state cases (present, manifest gone, chunk
gone, unreachable — twice, once for each read that can fail — locked realm) and the publish gate's
two halves; its five original cases still pass unchanged.
`BackfillThumbnailClassesOpTest` gains: a young sentinel costs no relay traffic, a stale one whose
class the relay still has is renewed (and the renewal buys another interval), a class the relay lost
is minted again with its sentinel cleared, an unreachable relay keeps the sentinel, and nothing is
minted or latched while content cannot be published. `ThumbnailClassBackfillTest` pins the last of
those on the pure pass. Five mutations — UNKNOWN treated as absent, no age check, an ignored publish
gate, a manifest miss read as absent, and an unknown chunk read as absent — were each caught.

**Still open here.**
- ⚠⚠ **iOS evicts but never repairs.** It registers the keeper schedule and no backfill (§15.9: route
  1's shared device id makes the marker a race), passes no `objectStore`, and wires no pull hook — so
  a sentinel written on an iPhone is never revalidated or cleared by anything. Eviction itself is
  still sound there (the relay holds the bytes and a viewer re-pulls), but the repair X-2b adds is
  Android/desktop only. `ThumbnailBackfillWiringTest` audits those two and should be extended to
  state iOS's absence deliberately.
- ⚠ A realm that wants no classes returns before enumerating, so its sentinels are never re-checked.
- ⚠ `canPublishContentNow` is `false` while the relay's 507 latch is set, and that latch clears only
  on a **successful emit**. A device that goes over quota and then has nothing else to upload keeps
  the backfill — including its revalidation — parked until some other upload succeeds or the proxy
  restarts.
- ⚠ An `UNKNOWN` answer does not restamp, so a relay that is reachable but unhealthy re-checks the
  same first 50 sentinels every pass and never reaches the rest.
- A rung the relay loses while its file's row is gone is unreachable either way (§8 Q7).

### 9.4 X-1 — the rung exists (2026-09-18)

**The class.** `GetStoredThumbnail.CLASS_XXLARGE` / `XXLARGE_PX = 2048`, `File.ThumbnailXXLarge`
(dynamic, `Syncable(OnDemand)`), registered in `FileServiceCommonModule` beside its siblings, and one
row in `ThumbnailClasses`: `px = 2048`, `maxBytes = 1 MiB`, `backfilled = true`, **`stretches = false`
until X-3** (below), appended to `STILLS`. ⭐ **X-0's promise held**: the upload's render and emit, the backfill, the
declarable walk, the writer's property map and both resolver rules picked the rung up from that one
row, with no further edits.

**HR #9 — `FileService` v12**, with an empty migration registered for `fromVersion = 11`. Two reasons
are owed and only the second is visible to the baseline: the **sync semantics** of a class a v11 peer
has never heard of, and the **positional reshape** of `GetPreview`'s parameter object by D-17's flag.
⚠ A dynamic property reshapes no row, so the shape baseline moved the version column on every
file-service-owned shape **and one row besides**: `getPreview` gained `preferOriginal@…boolean?`,
appended last, which is the only positionally safe place for it. The property-kind baseline gained
one `CHILD_ROW` line.
⚠⚠ **`:hub` is a `FileService` peer and this bump moves it.** `HubRuntime` builds `buildProxyModule()`, which registers `fileServiceCommonModule`, so a hub left at v11 is fenced by the announce gate against every updated client — and it is deployed **by hand**, so nothing in CI or the smoke gate will say so. ⚠ The smoke gate is structurally blind to this class of skew anyway: its lockstep canaries (Fit, Contacts) do not register the file service. **Redeploy the hub with this release, or its backups stop silently.**
⚠⚠ **Forgetting `FileServiceCommonModule`'s `dynamicProperty(...)` line is not a quiet omission**: the
property never initialises, so the schema never learns it, and the failures land far away — the
content walk finds five classes instead of six, and two unrelated folder-merge tests go red.

**D-17's flag.** `GetPreview.Params.PREFER_ORIGINAL` (optional, absent ⇒ today's ladder) makes
`StoredThumbnailLadder.covering` run with `allowStretch = false`: a rung that **covers** the request
still wins, a stretched one is refused, and the device holding the bytes renders at the size asked
for. ⚠ On a device without the bytes it changes nothing — `best` answers as before, because there is
nothing to render from. No client sets it yet; X-3 does, and deletes `renderEdgeOnByteHolder`.

⚠⚠ **And that is why the top rung ships with `stretches = false`** — the X-1 review's finding 1,
confirmed by two reviewers independently. D-14 gives `XXLARGE` a tolerance band to 4096 px and D-17
is the byte holder's way out of it, but the way out is an **op param**, and `File.Preview(width,
height)` resolves through the three-argument `GetPreview` interface method, which cannot carry one.
So the band would have applied to every caller unconditionally, and `MAX_HERO_PX` is exactly
`XXLARGE_PX × COVER_TOLERANCE` — **no** hero request could have escaped it. A 3072 px viewer on the
device holding a 4096 px original would have been handed the 2048 px rung upscaled: the exact case
§1 opens with, made worse on the one device with the best possible source.
⚠ **Gating it costs the byte-less device nothing**, which is what makes this a deferral and not a
retreat: above 2048 px `best` already returns `XXLARGE` (the request clears the rung below it), so
only the route changes — `best` after a null `GetData` instead of `covering` before it.
`StoredThumbnailLadderTest.theByteLessAnswerIsTheSameWithOrWithoutTheTopRungsStretchBand` runs both
ladders over every band edge and presence set and asserts they agree. What the band would add is
D-2's economy for a byte holder that never asks for the original — the same trade master makes today.
⚠⚠ **X-3 flips the flag, sets the param and deletes `renderEdgeOnByteHolder` in one PR.** Any two of
those three re-open the regression. ✅ **Done — §9.5**; everything above this line is X-1's record, so
read `stretches = false` here as history, not as the current table.

**The cap is locked at 1 MiB** on §2.1's measurement of all four encoders. ⚠⚠ It holds *because of*
the step-down: iOS encodes ~1.65× heavier at the same nominal quality, so a detailed frame that fits
at q80 elsewhere fits at q65 there.

**What X-1 deliberately does not do.**
- ⚠⚠ **Nothing declares the class**, so no device pulls it and the backfill mints it for no existing
  file. Photos' and Timeline's declarations are X-3 and X-6 (§4.4). What *does* happen from this
  phase on: **every new upload mints and publishes one** (D-15), which is the byte cost the decision
  accepted.
- ⚠ **The backfill budget is unchanged** (250 files / 32 MiB). D-16 raises it only under a driver
  that enforces idle + charging + unmetered, and no such driver is distinguishable from inside the
  op: `ScheduleDrainLoop` enforces no `ConstraintSpec`, and nothing tells the pass which driver woke
  it. Raising it regardless is what §4.3 warns against. The raise waits on in-process constraint
  enforcement (bgproc Ph6) — until then a library backfills at ~64 rungs a night once X-3 declares.
- The counts outside `:file-service-proxy` that X-0 listed are updated (`SchemaDrivenContentWalkTest`
  now expects six, `ContentRepair`'s KDocs and `content-plane-refcount.md`'s prose say five classes).

**Verified.** `StoredThumbnailLadderTest`'s equivalence cases still pass unchanged — the fifth rung
alters no answer for the four that existed — and its table test now asserts §4.2 against the **real**
rungs plus D-17's no-stretch mode. `JvmGetPreviewImplementationThumbnailFallbackTest` gains the two
byte-holder cases: with the flag a 1500 px request renders at 1500 px, and a covering rung is still
taken. Both baselines regenerated; the full system-test suite is green.

Four more tests came out of the review, each pinning a claim this section made that nothing checked:
- `UploadMintsTheFullStillLadderTest` — the upload really does store **and emit** all four still
  classes, on a real 2400 px JPEG through the real JVM `GetPreview`, with the top rung decoding at
  2048 px and inside its cap. §9.4 asserted this; the suite could not have noticed a skipped rung.
- `ThumbnailClassesTest.theTopRungsEdgeAndCapAreTheOnesTheMeasurementsLocked` — D-13's 2048 px and
  1 MiB. ⭐ "Locked" was prose only: a mutation to 256 KiB or 4 MiB passed the entire suite.
- `ThumbnailClassesTest.theTopRungDoesNotStretchWhileNoCallerCanDeclineIt` and
  `StoredThumbnailLadderTest.theByteLessAnswerIsTheSameWithOrWithoutTheTopRungsStretchBand` — the
  gate above, and the equivalence that makes it safe.
- `SyncedThumbnailSystemTest`'s photograph is now **2600×1950**, above the top rung. At 1600 px the
  source was smaller than `XXLARGE`, so that rung's blob was a 1600 px copy of the original and the
  byte-holder guards read 1600 whether they were served a render or the rung — they passed without
  being able to fail (⚠ the viewer's asks-for-the-size-it-draws guard among them). The test now also asserts the 3072 px retina
  read renders at 2600 px, which is finding 1's regression stated as an assertion.

### 9.5 X-3 — the viewer draws it (2026-09-18)

**D-17 is a property, not a parameter.** `File.ViewerPreview(width, height)` resolves through the
same `GetPreview` as `File.Preview`, one flag apart: it passes `PREFER_ORIGINAL`, so a covering rung
still wins and a *stretched* one is refused. ⚠⚠ The alternative — a third parameter on
`File.Preview` — was rejected on inspection: a dynamic property's parameter list is **positional
schema**, so widening it moves Drive, Photos, Timeline and the web SDK in lockstep for a flag only
the viewer wants. Additive is what v12's `ThumbnailXXLarge` did for the same reason.

⚠ **The seam was one interface and one `Ro`.** The proxy actuals already read the flag out of the
operation's parameter map (X-1 built that half), and they do not implement the `GetPreview` Kotlin
interface at all — so the whole client route was `GetPreview.getPreview(..., preferOriginal)` plus
four lines in `GetPreviewRo`. ⚠⚠ It writes the key **only when true**: absence means today's ladder,
and a `false` on the wire would be a new field for every `File.Preview` caller in the system.
`GetPreviewRoPrefersOriginalTest` pins both halves, because nothing else would fail if the flag
silently stopped being written — the ladder would just start stretching again.

**The viewer no longer probes.** `renderEdgeOnByteHolder` is **deleted**, and with it the
`HasLocalData` stat that fed it: `PREFER_ORIGINAL` is a no-op on a device without the bytes (there is
nothing to render from), so the viewer sets it unconditionally and the proxy decides. That is §4.2's
"the proxy applies D-17's condition, not the client" arriving in code. ⭐ It also ends the
over-render the old arithmetic forced: a 1500 px view rendered at 2049 px. ⚠ The 4097 px figure §4.2
uses is a counterfactual about a *re-tuned* `renderEdgeOnByteHolder` that never existed — the deleted
function returned `maxOf(requested, 2049)`, so a 2979 px view rendered at 2979. Deleting it removes
the inflation at the bottom of the band, not a 4097 px render anyone ever paid for.

**`viewerPreview(path, requestPx): Flow<PhotoPreview>`** — §4.5's steps 1 and 2. The best local
answer first (marked `standIn` while a larger rung is still wanted), then `XXLARGE → XLARGE` pulled
and emitted. ⚠ An upgrade identical to what is on screen is **not** emitted; the comparison is
`contentEquals`, not §4.5's proposed "length then a hash", because at ~0.5 MB an exact compare is
cheap and a hash could collide.
⚠⚠ **Step 2 re-asks the ladder rather than emitting what it pulled** — a review fix. This layer gets
bare bytes and cannot tell a 1024 px class from a 2048 px one, so emitting the pulled blob let a
transiently-failed `XXLARGE` read fall to `XLARGE` and replace a picture with a smaller one. The pull
now makes a class *reachable* and `StoredThumbnailLadder` decides what is drawn.

⚠⚠ **And on its own that fixed nothing at the hero floor — the more useful half of the story.** At
exactly `XLARGE_PX` the ladder *agrees with* the downgrade: `covering` serves the **smallest** class
that covers a request, so a device holding `XXLARGE` but not `XLARGE` drew its 2048 px copy, pulled
`XLARGE`, and then correctly preferred the 1024 px class that had just landed. A second review proved
the "fix" green against the defect with a two-line probe. The actual fix is **ordering**: at or below
the hero rung, pull first and answer once, because the class that answers such a request *is* the
class the pull fetches — there is nothing to be progressive about. Above it the pulled class is the
largest that exists, so the re-read can only equal or better what is on screen.
⚠ `SyncedThumbnailSystemTest` now also asks at the hero floor, and that assertion fails with
`[2048, 1024]` against the unfixed code — verified by reverting the fix, not by reading it. The
3072 px assertion could never have caught this: it takes the `XXLARGE` arm and never enters the
fallback, which is why a regression test must be shown to fail before it is trusted.

⚠⚠ **Two costs this ordering accepts**, both dissolving when the typed pull lands (X-4): a
byte-holding *video* pays its whole-file read twice per viewer open (`asksForHeroByName` is `true`
for videos even on the byte holder, so step 1 renders a poster, the pull runs, and step 2 renders it
again to compare), and every byte-less open costs three chain round trips rather than two — the
middle one fetching a rung whose bytes are then discarded. Both go away once the pull returns the
class it served instead of bare bytes. ⚠⚠ `XXLARGE` is named only above 1024 px — never a tile, never a
feed row (§4.4).

⚠⚠ **Step 3 (mint on view) and its dedicated operation are X-4, not X-3.** §4.5 assigned the typed
`TResult` pull here, and it moved for one reason: **nothing in X-3 branches on the difference.**
A confirmed absence and a failed read both keep the stand-in and retry on the next open, so building
the distinction now would ship an untestable claim — there would be no behaviour to assert it
against. X-4 is where "mint it here" first makes the two outcomes differ, and `namedRung`'s KDoc
carries the warning at the call site. ⭐ The machinery is ready:
`OnDemandResolution.resolve` already returns `TResult<Data?>` with exactly §4.5's three outcomes —
`Failure` for network or decode, `Success` when the resolver ran — and it is
`materialisePendingOnDemandProperties` that flattens them. X-4 needs an operation that does not.

**The UI collects.** `HeroLoader` is now `(path, requestPx) -> Flow<ByteArray>` and `PhotoPage`
collects instead of awaiting once; each emission replaces the picture in place, which is the upgrade
the user sees. ⚠ `PhotoViewerPagerTest.aSecondEmissionUpgradesThePageInPlace` observes that without
reaching into the private `HeroState`: `emit` suspends until the collector has processed the value,
so the flow reaching its *second* emit is proof the first was applied.

**Decoded at the drawn size.** `decodePreviewBitmap(maxEdge = requestPx)`. ⚠⚠ The trap here is the
opposite of the one in §4.5: decode sampling is powers of two, so the obvious "halve until under the
ceiling" loop lands a 2048 px rung at 1024 for a 1440 px viewport — an upscale of a picture the
device had sharp, which is this track's own bug arriving by the back door. `decodeSampleSize` stops
one step **above** the target and the remainder is an exact scale; `DecodeSampleSizeTest` sweeps
every source/target pair that matters. Android samples at decode and recycles the intermediate; the
Skia platforms scale afterwards, which bounds the **retained** size — the pager keeps three pages
composed, so ~24 MiB instead of ~48 MiB.

⚠⚠ **Two things the review caught here, both shipped broken in the first cut of this phase and fixed
before merge.** They are recorded because each is a trap the next person can walk into:
- **A drawn size and a hard ceiling are different numbers.** The first rule knew only the target, so
  a caller passing the *default* — a bound, not a size it draws — decoded a 6000 px original at full
  resolution to land "just above" 4096 and held a scaled copy beside it: ~141 MB peak against the 24
  MB the naive code managed, on a `minSdk` 24 heap with no `largeHeap` anywhere in this repo. Drive's
  Android image preview hands whole originals to that default, so a 50 MP photograph was an
  `OutOfMemoryError` nothing catches. ⚠ The first version of the *test* asserted the broken
  behaviour, so it blessed the defect rather than catching it. `decodeSampleSize` now takes both
  numbers and the ceiling wins.
- **skiko's default sampling is point sampling.** `drawImageRect(image, dst)` passes
  `SamplingMode.DEFAULT`, which is `FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)` — so the
  desktop and iOS downscale dropped every fourth row and column of a 2048 px rung drawn at 1536 px,
  aliasing the one surface this phase exists to sharpen, while Android's
  `createScaledBitmap(filter = true)` was bilinear. Both now pass `SamplingMode.LINEAR` explicitly,
  and close the intermediate `Image`/`Surface` rather than leaving them to skiko's cleaner.

⚠⚠ **X-5 inherits a loaded gun.** `fileServiceCommonModule` is registered in the browser runtime, so
`File.ViewerPreview` now exists there — and `JsGetPreviewImplementation` reads `WIDTH`/`HEIGHT`/
`FORMAT`/`QUALITY` only, so it would silently ignore `PREFER_ORIGINAL`. The param is schema-optional,
so nothing rejects it; the call would simply not do what its name says. Harmless today because
nothing on web projects either preview property (§1.2i of `drive-web-parity.md` forbids it, by
convention rather than by code), and the JS resolver serves no stored rung at all. **X-5 must either
honour the flag in the JS actual or not use the property.**

**Declaration.** Photos adds `xxlarge` to `declareWantedThumbnailClassesAtLaunch`, beside `xlarge`
(kept: it is the fallback for a photograph no device has minted the larger rung for). ⚠ This is what
finally makes the backfill mint the class for existing files — until X-3, §9.4's "nothing declares
it" meant only the uploading device ever held one.

**HR #9 — `FileService` v13**, empty migration for `fromVersion = 12`. ⭐ And this time the silence
is **proved, not asserted**: normalising the version column out of `schema-shape-baseline.txt` makes
the old and new files byte-identical, so the bump moved nothing else. (X-1's §9.4 claimed the same
and was wrong — `getPreview` had gained a parameter.) The property-kind baseline gains one
`CHILD_ROW` line. **HR #2**: A5 records that the exposure it described is now live on one surface,
and A6 that `xxlarge` has joined the wanted-class vocabulary.
⚠⚠ **`:hub` must be redeployed for v13, exactly as §9.4 said for v12** — `HubRuntime` registers
`fileServiceCommonModule`, it is deployed by hand, and neither CI nor the smoke gate can see the
skew: the gate's un-built canaries (Fit, Contacts) do not register the file service, and every app
that does is reinstalled from the tree. ⚠ `hub/build.gradle.kts`'s own comment claims the hub only
uses standalone `:file-service-client` helpers; that is wrong and worth fixing when it is next
touched.

**Verified.** Full gate green; Android smoke green. New: `GetPreviewRoPrefersOriginalTest`,
`DecodeSampleSizeTest`, `PhotoViewerPagerTest.aSecondEmissionUpgradesThePageInPlace`, and
`SyncedThumbnailSystemTest` now asserts **both surfaces on the same byte holder** — `preview` takes
the stretched rung (2048 px), `viewerPreview` renders the original (2600 px), and between the rungs
both take the covering class. That pairing is the D-17 trade written as a test.

### 9.6 X-4 — mint on view (2026-09-18)

`EnsureThumbnailClass(path, class, userRequested?)` — an `IoOperation` the client asks for and the
proxy performs end to end, the `ReadSharedFile` shape. ⭐ **A new operation owes no version bump**:
`SchemaShapeBaseline` gains one row and `FileService` stays at v13.
⚠⚠ But the precedent §9.6 first cited for that — `BackfillThumbnailClasses` — is **not the same
case**: it has no client `Ro` and Control dispatches it to itself, one APK, no skew window. This op
is dispatched by **Photos across Binder**, so the hazard is a *new caller against an old proxy*:
Photos updating before Control. The announce gate compares service *versions* only, so that pairing
looks healthy and every call fails as `UnsupportedOperationError` — which `ensureViewerRung`
discards, making it silent and indistinguishable from the op's four legitimate nulls. The rule (no
bump for a new op) is right; the reassurance was not. ⚠ `:hub` is the same story and needs its
redeploy for the same reason §9.5 gave.

**The gates, in order**, each guarding the same thing — a whole-original download: already here (a
stat) → already declined → pull → **relay confirmed absent** → quota → metered → **size** → the
original → render and store, which publishes inline (D-19 by construction:
`ThumbnailClassMinter.store` is write-and-emit in one).

⚠⚠ **"A failed pull never falls through to the mint" was dead code, and this is how.** The first cut
checked `OnDemandResolution.resolve` for `TResult.Failure` — reasoning from the interface's KDoc.
The *implementation* cannot produce it for a content-addressed thumbnail: `getChunk` collapses a
missing blob and a failed request into one null, `fetchManifestCiphertext` `as?`-casts that away, and
`resolve` returns `Success` regardless. So an unreachable relay read as "nobody ever minted this",
and every dwell during an outage would have spent a whole original proving it. The fix is the
tri-state this track already built for X-2b: `FileContentSyncEmitter.contentResolvability`, where
only **ABSENT** licenses a mint and UNKNOWN is an unreachable relay. ⚠ The test that "proved" the old
gate was a false positive — it drove a `Failure` production never emits — and has been deleted rather
than adapted; `anUnreachableRelayIsNotAnAbsence` drives the two answers a broken relay really gives.

⚠⚠ **A ceiling bounds what is kept; a size gate bounds what is fetched.** Neither the relay quota
(1 MiB, the *rung*) nor the metered check says anything about the transfer, and `canCarry` admits
video and PDF for every still rung while the viewer has no video branch — so a one-second look at a
4K clip fetched the whole clip to render a poster frame. `MAX_ORIGINAL_BYTES_FOR_A_VIEW_MINT` is
64 MiB: every photograph **§2** measured clears it (§2.1 is the four-encoder table on generated frames and lists no original sizes), a DSLR raw fits, a video does not. ⚠ An unknown
size counts as too big, because the alternative is an unbounded fetch on a guess. ⚠⚠ It applies
**only when a transfer would happen**: a large file this device already holds costs nothing to render
from, and refusing it would be a limit on rendering rather than on downloading.

⚠⚠ **The originals still have no ceiling, and that is the phase's accepted regression.** A 2 GB
`MaxLocalBytes` default was built here and then **pulled back out**: it changes what every existing
install does to bytes already on disk, and review found it owed three things this phase does not
carry — per-file `drive.offline.files` pins are not folded into `ProxySyncRules.readFileSyncRuleSet`,
so the shipped copy *"files you keep offline don't count towards this"* is false for them; there is
no grandfathering for a device already over the new number; and `:hub` is a schema peer. The
always-on pressure remains the free-disk floor. See §4.7.

**Step 3 lives in the viewer's flow, not beside it.** It was briefly a parallel `LaunchedEffect`
writing to its own state, which broke three ways at once and is worth recording because all three
were invisible to the tests that existed:
- on a device **holding the original** it replaced a render at the requested size with the smaller
  stored rung — the retina case this whole document was opened for, regressed by its own last phase;
- it re-decoded bytes the flow had already drawn, because the flow's comparison never saw them;
- when steps 1–2 produced nothing — **mint-on-view's own headline case** — its result was
  unreachable, so the page kept saying the photograph was not downloaded while a rung was minted and
  published behind it.
The dwell is now a `mayMint` signal the loader receives; the result arrives as another emission
through the same comparison, the same state and the same ordering. After a mint the device holds the
original, so the re-read renders at the full requested size rather than taking the 2048 px rung.

⚠⚠ **And the licence must be *observed*, not captured — the rework's own regression.** Folding step
3 into the flow moved the dwell from a composable `if (isSettled)`, which re-enters on every
recomposition, into a value read once when the producer starts. `produceState` is keyed on the path
and the size and must not also be keyed on settling, or settling would restart the whole load — so a
neighbour composed *before* the swipe froze `isSettled = false` and never re-evaluated it. The effect
was **mint-on-open**: one photograph per viewer session, whatever the user then looked at. ⚠ Both
existing dwell tests stayed green through it, because both only exercise the page the viewer opened
on; the third case swipes, and fails with `[p0.jpg]` against the captured version. Reading settling
through `snapshotFlow` rather than capturing it is what fixes it.

⚠⚠ **The licence's own two defects, found in review and fixed after that.** The first cut built it as
`snapshotFlow { settled }.transformLatest { delay(DWELL_MS); emit(Unit) }`, which is wrong twice:

- **the clock ran from *subscription*, not from settling.** The loader does not subscribe until
  §4.5's steps 1–2 have been to the relay and back, so the wait was really
  `time-to-reach-step-3 + DWELL_MS` — the dwell on a fast network, several seconds on a slow one, and
  in neither case the stated policy. It errs safe (never *earlier* than a second), which is why
  nothing caught it. A `LaunchedEffect` beside the page starts the clock when the page settles,
  whoever is or is not listening, and latches once earned;
- **it never completed.** `mayMint.firstOrNull()` returned on the emission, so this was invisible —
  but the contract the repository actually relies on is that a licence which *completes without
  emitting* means "never", and it is the only route to the `Absent` verdict. The documented default,
  `emptyFlow()`, completes; the real one did not, so the two callers had opposite semantics. `take(1)`
  settles it: at most one emission, then done.

⚠ The comment above the old form claimed it "emits once, `DWELL_MS` after it settles". Neither half
was true — it was multi-shot and timed from subscription — and only `firstOrNull()` on the far side
made it behave. `theDwellRunsFromSettling…` and `theLicenceCompletesOnceSpent` fail against the old
form and pass against this one; the three original dwell tests are green either way, because all
three subscribe instantly and none observes completion.

**The dwell is the pager's settled page** (D-18): `PagerState.settledPage`, its first use here.
`beyondViewportPageCount = 1` composes a neighbour either side and every composed page starts its
loader, so a dwell timed from composition would fetch **two unseen originals** each time one
photograph is looked at. ⚠ `ViewerDwellMintTest` was once observed failing under a cold multi-module run, reporting the
settled page **plus both composed neighbours** — the same output as deliberately breaking the gate —
and then passed ten times. Treat a red there as a real signal about scheduling pressure before
dismissing it as flake, because its flake signature and its defect signature are the same string.

⚠ **`USER_REQUESTED` is plumbed and has no caller.** There is no zoom or "sharpen" action yet, so on
a metered network there is currently no route to a large rung at all, and the op's KDoc describes an
override that exists only in the schema. ⚠ The row is already paid for — `schema-shape-baseline.txt` carries `userRequested@…boolean?` — so what is missing is the caller, not the schema.

**Disclosure.** Photos' bundled manifest gains the op — the Apps screen is where a user learns that
opening a photograph may spend their data.

**HR #2.** A5 records D-19's residual as one of **timing, not linkage**, plus the sharpening X-4
actually adds: before it, nothing in the viewer ever resolved `File.Data`, so a whole-original fetch
from a non-holding device is a new signal that separates *looked at* from *scrolled past*.

⚠⚠ **Not fixed here, and reachable from this phase.** Two concurrent `File.Data` resolves for one
file race on a fixed `.tenshu-partial` temp path whose integrity gates are computed from in-memory
plaintext rather than read back, which can promote a zero-prefixed file and have it believed for
ever. Pre-existing in `FileContentManifestApplier`; X-4 supplies the first everyday gesture that can
fire two at once (swipe away and back). Recorded in `tech-debt.md` rather than patched here, because
the fix belongs in the applier. ⚠ The op has **no dedupe or single-flight** of its own either — two
dwells on one photograph mint it twice, publishing the same derived content id twice.

⚠⚠ **The eviction guard had no test, and neither did the size gate's exemption** — both found in
the same review. Deleting the keeper's journal probe outright left `:file-service-proxy:jvmTest`
green, because `OfflineKeeperEvictionTest` built its proxy with no emitter at all and so could not
reach the guard by construction; the fake it needed already existed one file over. That is the one
change in this phase whose failure mode is the permanent loss of a user's only copy. It now has two
tests, and the harness takes the emitter holder it always could.

⚠⚠ **And the hole it closes is live on master, not one the ceiling would have opened** — which is
why the guard stays after the ceiling was pulled. `MinFreeSpaceBytes` is on by default at 1 GiB and
`selectCacheEvictions` takes `max(overCeiling, overFloor)`, so on any device under a gigabyte free
this sweep has been evicting files whose only copy is local and whose emit is still journalled. No
ceiling is required to reach it; the ceiling would only have made it reachable on a full disk too.

⚠⚠ **And the probe asked the wrong question.** `queuedContentBytesFor` is a *quota* figure: it
answers `0` both when nothing is journalled and when a live intent weighs nothing — an empty file, or
one whose `filePath` no longer sizes. For the quota gate those are the same; for a byte holder
deciding whether a delete loses the only copy they are opposites. `FileContentSyncEmitter` gains
`hasQueuedContentFor`, which answers on the *entry*, and both sweeps ask it — the thumbnail plane had
the same misreading, where it costs a wrongly-stamped "deliberately gone" sentinel rather than a
file. ⚠ The guard excludes these paths from the **candidate set**, so their bytes are not counted
toward the ceiling either: the same rule the pinned exemption follows, because counting bytes
eviction may not spend makes the ceiling unsatisfiable. A device holds `pinned + unsent + at most the
ceiling`, and bounding the total is the free-disk floor's job.

⚠ **The 64 MiB size gate is asked only when a transfer would happen**, so a 780 MB scan already on
disk still renders — the number bounds the *download*, not what may be rendered from. That exemption
now has its own pair of tests; deleting it turns them red and leaves the two refusal tests green.

**Still open after X-4.** The mint answers case B2 (§4.5); **B1 still has no signal** — a photograph
the byte holder's backfill will eventually mint still converges only on the next open, which §16.2
accepted. And the two costs X-3's ordering accepts (a byte-holding video's doubled whole-file read, a
third chain round trip on every byte-less open) are unchanged here: they dissolve when the *viewer's*
pull returns the class it served, which this operation does for its own call but not for `viewerPreview`'s.


### 9.7 X-5 — the web lightbox (2026-09-19)

`FileSystem.getLargePreview(path, px)` names `ThumbnailXXLarge` / `ThumbnailXLarge` **by relation**
— never `File.Preview`, and never X-3's `File.ViewerPreview`, which resolves through `getPreview`
and is therefore caught by the same §1.2i prohibition despite postdating it. Read → pull → re-read,
the shape `previewBytes` already uses, because the classes are `Syncable(OnDemand)`.

⚠⚠ **It answers three things, not bytes.** `bytes`, the `thumbnailClass` that supplied them, and
`coveringRung` ∈ `present | absent | unknown` for *the largest class that could cover this request*
— `XXLARGE` above 1024 px, `XLARGE` at or below, so a small viewport never pulls ~0.5 MB for a
picture `XLARGE` covers and still learns when `XLARGE` itself is missing. Only **`absent`** licenses
downloading the original; `unknown` keeps the stand-in. That tri-state is the third time this track
has needed one (X-2b's `ContentResolvability`, X-4's journal probe), and `JsResult.kt` already
states the rule it follows: *never return null for two different reasons.*

**The policy lives in the app, not the SDK.** `needsOriginal` is in `web/drive/src/lib/`: the SDK
reports what it found, Drive decides what to do about it. That split also keeps the rule free of the
Kotlin→JS bundle, so it is testable without building one.

⚠⚠ **§4.8 named the wrong file for the lightbox change.** `FilePreview.tsx` is a presentational
component; the original download is in `useFileOpener.tsx`, which is what changed.

⚠⚠ **Showing a rung would have broken Download, and that is worse than the blur.** `FilePreview`
renders `<a href={preview.url} download={preview.name}>`, so swapping the display URL for a 2048 px
class would have silently saved that class under the original's filename. `Preview` now carries
`standIn`, and that case fetches the real bytes before saving.

⚠⚠ **A version-behind apex would have stopped files opening at all.** `fs.getLargePreview` is a new
bridge method; a browser holding a cached older apex gets a rejection, and it can be *synchronous*,
so a `.catch(...)` handler never sees it. Unguarded, "your apex is one version behind" became "this
file will not open" — on the app's primary action, which worked before X-5 existed. Now `try/catch`
with a fall-through to the original, pinned by
`still opens the original when the apex does not know getLargePreview`. ⚠ Two existing tests caught
this; it was not a mock gap, and reading it as one would have shipped the defect.

⚠ `FileThumbnail`'s hard-coded `image/webp` is replaced by `sniffImageMime`, which reads the magic
bytes (every stored rung is JPEG since 2026-08-31). ⚠ `queryKeys.preview` gained its size, which was
a latent collision rather than a live one — nothing caches the large read yet.

**The launch declaration did not ship, and the reason is structural.** ⚠⚠ Both halves write a
`DeviceSetting` row, and **web does not register the preference service**: `FirstPartyPlugins` lists
seven services and that is not among them, while `:file-service-proxy`'s dependency on
`:preference-service` is `implementation`, so the types are not even on the JS SDK's classpath.
Adding a service to web's schema list carries announce and version consequences and is its own
change. ⚠⚠ Wiring `notePull` alone was considered and **rejected as worse than nothing**:
`WantedThumbnailClasses.declare` swallows every `Throwable`, so it would have failed silently for
ever while looking wired.

⚠⚠⚠ **X-5's first cut rebuilt X-4's dead gate, one phase after fixing it.** `pullHeroClassesJs`
decided the pull had failed from `resolve()`'s `TResult` — and `OnDemandResolutionImpl.resolve`
returns `Success` unconditionally once the property has a `contentId`, which a thumbnail class
always has. So a 503, a captive portal and "nobody ever minted this" arrived identically, and the
`absent` that licenses a 6–18 MB download was minted from a signal that cannot carry failure: every
open during an outage would have spent an original proving it. **This is §9.6's defect verbatim**,
in the function whose KDoc promised it was impossible, written by the same author who had fixed it.
The fix is the probe X-4 already built — `contentResolvability`, reached through the emitter
`FirstPartyPlugins` **already publishes on web** — so `ABSENT` is now the only value that licenses a
download and is minted at exactly one place. ⚠ Pinned by
`aRuntimeThatCannotReachTheRelayNeverClaimsTheClassIsAbsent`, verified red when the inference
returns. The lesson is not "check the KDoc": it is that *a phase's own as-built is the first place
to look when the next phase asks the same question.*

⚠⚠ **`covering` ignored a locally-held larger class below 1024 px.** With `XXLARGE` present and
`XLARGE` absent, a 1024 px request fell through to a 256 px stand-in *and* an `absent` licence —
downloading the original with a 2048 px copy on disk. Anything at or above the request covers it,
largest first. Latent only because the one caller passes a constant 2048; live the moment `px`
tracks the viewport, which is what the parameter is for.

⚠⚠ **The Download guard had no test, and the happy path had none either.** Disabling the `standIn`
branch — the data-loss case — passed 222/222. And every mock answered `bytes: null`, so nothing
asserted that a present rung is drawn or that `getData` is *skipped*: the phase's entire purpose was
unverified. Both now exist and both go red when removed.

⭐ **`needsOriginal` follows the ladder's own cover band, not a stricter rule** (maintainer's call).
A strict `edge >= px` made web fetch a whole original at 2048 px whenever only `XLARGE` was present,
where all three native platforms serve the 1024 rung — `COVER_TOLERANCE = 2.0`, chosen against a
measured retina worst case of 1.64×. ⚠⚠ The band is **per rung**: `LIST` and `LARGE` ship
`stretches = false`, so applying the tolerance uniformly would let a 256 px row icon claim a 512 px
lightbox. Web mirrors those flags by hand; `ThumbnailClassesTest.theCoverBandWebMirrorsIsStillPerRung`
is what fails when they drift.

**Still open after X-5.** The declaration above. And ⚠ the fallback remains load-bearing precisely
because of it: nothing on web runs the backfill, so in a web-only realm an old file may never get an
`XXLARGE` and the lightbox correctly falls back to the original every time.


### 9.8 X-6 — the Timeline moment hero (2026-09-19)

⚠⚠⚠ **The first cut of this phase made the hero sharp and every other surface permanently
expensive, and review caught it.** The early return skipped `cachePhotoThumbnails` — the only thing
in Timeline that pulls `LIST` + `LARGE` — and because it is gated on `readPhotoPreview` being null,
which `XXLARGE` makes permanently false, skipping it once skipped it **forever**. The photo then
held only the 2048 px class, and `StoredThumbnailLadder.covering` serves the smallest locally
present covering class **verbatim, with no downscale**: every later 256 px feed row was answered
with the hero blob. Measured at **20.7× the bytes** on the fixture (3,003 B → 62,304 B), ≈33× on a
real photograph, plus a ~12.6 MB ARGB decode per frame instead of a 256 px one.
⚠ Reachable without contrivance — `MomentDetailScreen` draws its hero before any tile-sized read of
that photo, so a deep link, a cross-app open, or a row whose fetch a fast scroll cancelled all
arrive there first. ⚠⚠ **The phase's own test could not see it**: it reads the feed first, which is
precisely the ordering that masks it. `aMomentOpenedBeforeItsFeedRow_stillLeavesTheSmallClassesPulled`
reverses that order and fails without the fix.

⚠⚠ **§16.2's defect was still live in Timeline, eleven months after Photos was fixed.**
`TimelineProjectionRepository.photoBytes` pulled the hero rung inside `cachePhotoThumbnails`, and
called it **only when `readPhotoPreview` came back `null`** — which on a byte-less device it is not,
because the feed has already pulled `LIST` + `LARGE`. So `File.Preview` answered a 1600 px hero from
the 256 px class, a **~6× upscale**, while the rung sat on the content plane. The fix is the same
shape Photos took: name the rung *before* `File.Preview` — `XXLARGE`, then `XLARGE` — local-first,
falling back to `File.Preview` only when no device ever minted either.

⭐ **`asksForHeroByName` is now shared, not copied.** It was `internal` to `TenshuPhotosRepository`;
Timeline needed the same decision, and duplicating it is exactly the divergence that produced X-5's
`COVER_TOLERANCE` bug — two surfaces answering one question differently. It lives in
`:file-service-client` beside `declareWantedThumbnailClassesAtLaunch`, and both apps call it. ⚠ The
ladder's *numbers* are still copied in four places (`drive-web-parity.md` §1.2i); its *policy* is
now in one.

⚠ **Videos never reach this path in Timeline.** `photosCapturedBetween` filters to
`FileCategory.Image`, and `moment.photos` comes only from that op — so `asksForHeroByName`'s video
arm is unreachable from Timeline and exists for Photos. Keep it in the shared rule; do not describe
it as something Timeline's desktop hero depends on.

⚠ **Timeline declares `xxlarge` as well as `xlarge`.** Declaring only the smaller told the realm's
byte holders to mint a class the app then stretched 1.56×.

⚠⚠ **The guard is a real two-device test, because nothing smaller can see this defect.** On one
device the original is local, so `asksForHeroByName` returns false by design and old and new
behave identically — the difference exists *only* on a joiner. `SyncedThumbnailSystemTest` gains
`joinerOpensAMomentAfterTheFeed_andTheHeroIsServedTheLargeRung` beside the Photos case, with
`:timeline-shared-data` as a `testImplementation` (the precedent `:photos-shared-logic` set).
⚠ It asserts **`XXLARGE` specifically**, not "bigger than `LARGE`": 1600 px sits inside `XLARGE`'s
2× cover band, so a version naming only `XLARGE` would pass a naive bar while still upscaling.
Verified red against master's behaviour.

⚠ **`hasLocalData` is a new dispatch for Timeline and owes a manifest entry.** It is an O(1) stat
that reads no bytes, and nothing gates it — but the Apps screen derives *"Services used"* from
`BundledAppManifestSource`, so omitting it under-reports what the app does
(`new-op-permission-entry`'s honesty defect).

⚠⚠ **The rung pull owes no *new* entry — but not for the reason §7's footnote and an earlier draft
of this section gave.** *"A relation projection is not an operation"* is **false**: projecting a
rung dispatches `tenshu/file/getStoredThumbnail`, observable in a joiner's dispatch list. It owes
nothing here because master's `cachePhotoThumbnails` **already projected** `File.Thumbnail` /
`ThumbnailLarge` / `ThumbnailXLarge`, so Timeline was already dispatching that op — undeclared,
exactly as Photos does. ⚠ That pre-existing gap is real and unaddressed: both apps dispatch
`getStoredThumbnail` without declaring it.


### 9.9 `ThumbnailRung` → `ThumbnailClass` (2026-09-21)

A rename, no behaviour. The type was the **only** place the ladder metaphor reached a type name, and
it was the one place the metaphor is not true: `MOTION` is a `ThumbnailClass.Motion` with no `px`, no
`stretches` and no position in `STILLS` — it is not a rung, and `MOTION_PX`'s own comment says so.
Meanwhile the rest of the system already says *class* everywhere — `CLASS_XXLARGE`,
`thumbnailClass: String`, `EnsureThumbnailClass`, `BackfillThumbnailClasses`, `DECLARABLE_CLASSES`,
`wantedThumbnailClasses` — so the type name was the outlier, not the convention.

⭐ **"Ladder" is not ours and is worth keeping.** It is the standard term in adaptive-bitrate video
(*encoding ladder*, *bitrate ladder*) for exactly this shape: an ordered set of pre-encoded
renditions a client steps between. The neighbouring vocabulary is domain-split — *rendition* is
formal in DAM and CMIS (`cmis:thumbnail`), *variant* in Rails ActiveStorage and HLS, *image size* in
WordPress, and `srcset`'s `w` descriptors are the closest standard analogue to our *covering* rule.
So the metaphor stays in the prose and in `StoredThumbnailLadder`, where the ordering genuinely is
the mechanism; only the type is named for what it is.

⚠ The constructor property became `id` rather than `thumbnailClass`: a `ThumbnailClass` with a
`thumbnailClass` field reads badly, and `id` lines up with `property.id` beside it. ⚠ Two
`associate`/`map` lambdas now read `thumbnailClass.property.id to thumbnailClass.id` — the parameter
is named rather than `it`, because two bare `.id`s meaning different things is worse than the
verbosity.

⚠ Cheap by construction: `ThumbnailClass` is `internal` to `:file-service-proxy`, so the whole
rename is one module, no wire or stored shape, and no HR #9 obligation. Rides with it: the
`ThumbnailMotion` KDoc that still listed the still ladder without `XXLARGE`, stale since X-1.


### 9.10 The web declaration — registering a service on web (2026-09-21)

X-5 owed this and could not pay it: both halves of W-1 write a `DeviceSetting` row and **web
registered no preference service at all** (§9.7). `PreferencePlugin` adds it — `preference-service`,
`-common`, `-client` and `-proxy` were already `js { browser() }` and the proxy is commonMain-only,
so nothing had to be ported; the gap was four missing dependencies and a plugin.

⚠⚠ **Checked before writing anything: adding a service to a store's schema list does not wipe it.**
That was the risk worth pricing — "wipes every store" is this area's worst failure. `StoreSchemaMigrator
.migrateStoreAtOpen` is explicit: a service the recorded vector has never seen is *stamped at
current, touching no rows* — the wipe paths are `recorded < floor` and `recorded > declared`, and an
unrecorded service is neither. Its KDoc says the converse too: the migrator walks **declared**
services, so a recorded-but-undeclared one is never itself a reason to wipe.

**Both halves are wired**, including the `notePull` that X-5 deliberately refused: `declare` swallows
every `Throwable`, so without a preference plane it would have failed silently for ever while
looking wired. With the plane it works, so it goes in.

⚠⚠ **A guard that introduced the very defect it was guarding against.** `client.preferenceService`
is `getService(PreferenceService.ID)`, which **throws** for an unregistered service — and it is
evaluated as an *argument*, outside `declareWantedThumbnailClassesAtLaunch`'s own try/catch, so
launch-time bookkeeping could take down client construction. Wrapping the call in `runCatching`
fixed that and quietly created a second problem: it discarded the `Result`, so a missing preference
plane would have looked exactly like a working one. It ends in `.onFailure { Diagnostics.e(...) }`
now, matching the announce block beside it. ⚠ The `Job` is still dropped, on purpose.

⚠ **The mutation test is what found both.** Unregistering the plugin originally broke the *whole
runtime*; it now fails only `WebPreferencePlaneTest`, by name. ⭐ That test asserts a **round trip
through the real runtime** — write a row, read it back — rather than that the plugin is in a list,
because the failure it guards is exactly the one a list check would pass against.

⚠ `webCiPaths.test.ts` went red on the four new `api(project(...))` lines, as designed: it derives
the web CI path filters from `tenshu-sdk-js/build.gradle.kts` rather than freezing them, so a new
dependency that CI would not rebuild for is a test failure. Both the `push` and `pull_request`
blocks now cover the preference modules. ⭐ This is the pattern `tech-debt.md` names as the fix for
the undeclared-operation gap, and it works.

**Still true after this.** Nothing on web runs the thumbnail backfill, so a declaration only helps
where a *native* device does — which is why the lightbox keeps its original-fallback (§4.8), and why
`wantedThumbnailClassesInRealm` returning `null` for the JS `IndexedDbObjectStore` costs web nothing.
