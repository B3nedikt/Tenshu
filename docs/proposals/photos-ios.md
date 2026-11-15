# Photos on iOS — a SwiftUI shell at parity with Android, AI included

**Status: v1.3 — 2026-10-02. PIO-0 built (§8.1); PIO-1 compiled and running on a real iPhone over a
real library (HANDOFF H1, §8.5); **PIO-2 built** — densities, re-anchor, sticky header, on a
`UICollectionView` grid, a photograph's identity its PATH, the Mosaic's 2×2 features, the scrubber
with year pills and a date bubble, multi-select and motion tiles — verified on the simulator
(§8.6–§8.10), plus the return-hang fix (§8.11). **PIO-3 built** — the viewer's chrome, favourite, add to
album and trash (§8.12), zoom and drag-to-dismiss (§8.13), the About sheet (§8.14). Both installed on
the iPhone for the device pass, HANDOFF H2. PIO-4 slice 1 — the tabs and the Collections tab — built
(§8.15); slice 2 — an album's rename, delete and remove — built (§8.16); slice 3 — the Trash (§8.17)
and Duplicates (§8.18), each from Collections — built; slice 4 — the Search tab — built (§8.19), so
**PIO-4 is complete** on the simulator (H3, the device pass, still owed). The Library during a sync —
follow the top, diffed layouts, paced draws — §8.20. Open from H1: the 2 000-photo memory note.**

⚠⚠ **Nothing iOS compiles off macOS** — not Swift, not `iosMain`, and GitHub's iOS lane is dark
for billing (every `ios.yml` run fails in ~7 s). A green cloud gate says nothing about this app.
Every phase below therefore puts its decisions into tested `commonMain` and keeps Swift to
rendering and Apple-only APIs — Calendar's CA-8a doctrine (`calendar-app.md` §11), which is the
only reason a Linux-authored iOS app has ever compiled on its first Mac pass.

Companions: [`photos-app.md`](photos-app.md) (the app, as built on Android/desktop — its §8
obligations apply here too), [`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md)
§9 (the transport every first-party iOS app runs on), [`ios-fit-timeline-maps-parity.md`](ios-fit-timeline-maps-parity.md)
§8 (standing rules D1–D12, which bind this doc), [`inference-service.md`](inference-service.md) and
[`photos-people-and-pets.md`](photos-people-and-pets.md) (the AI tracks), [`photos-video-support.md`](photos-video-support.md)
(M6/M8 are this doc's video half).

---

## 1. The finding that shapes the plan

> **Photos is the best-prepared app any iOS slice has started from — and one module boundary
> away from being usable.** `:photos-shared-logic` is Compose-free and already declares
> `iosArm64()` + `iosSimulatorArm64()`; all eight view models, the repository, the feed layout
> maths and the scrubber index live there. `initPhotoService()` already runs in the iOS
> `PlatformProxyModule`. The PhotoKit binding (`IosMediaSourceProvider`) already ships in
> `:tenshu-sdk-ios` for Drive's backup.

What is missing is small in lines and large in consequence:

1. **The DI graph lives in the Compose module.** `photos-shared-ui/.../di/PhotosModules.kt` +
   `PhotosClientModule.kt` hold the reachability wiring, the VM factories and the launch-time
   thumbnail-rung declaration (W-1). iOS cannot reach them. ⚠ *Corrected in PIO-0:* the schema
   list (§18.4 of `photos-app.md`) is **not** owed on iOS — route 1 runs the whole proxy graph
   in-process, so every schema is registered by construction. What iOS needed was the rung
   declaration, which is now shared (§8.1).
2. **The zone functions live in the Compose module too.** `PhotosFeedViewModel` takes
   `sectionStartOf` / `yearOf` by injection; the real ones are in `photos-shared-ui/.../DayLabels.kt`.
   A Swift shell that fell back to the defaults would put photographs taken on New Year's Eve
   under the wrong year — silently.
3. **The iOS paging bridge drops placeholders.** `TenshuPagingPresenter.snapshot` is `items`
   without placeholders (Drive's lists turn them off). The Photos feed turns them **on**
   (`seekablePagingConfig`) because the scrubber spans the whole library over a total (PH-17c).
   Driven through today's presenter, the iOS feed would render, scroll and page — and its
   scrubber would span only what had loaded. No crash; a wrong control.
4. **Obj-C name clashes.** `Photo` (Timeline also exports one), `SettingsViewModel` (Control),
   and possibly more. Per D12, the newcomer takes `@ObjCName("Photos…")`; exporting unannotated
   silently renames a *shipped* shell's type to `Photo_`.
5. **No `NotConnectedPhotosRepository`.** The shipping default must be honest (D4) and live in
   `commonMain` so it is tested — `MockPhotosRepository` behind the gate is the `timeline-ios`
   mistake its C1 slice retired.

Everything in that list can be written **and verified** in the cloud. That is PIO-0.

---

## 2. What parity means — the Android inventory

Swept 2026-09-23. "Logic" = where the behaviour lives; everything marked *shared* is reused as-is.

| Surface | Android today | Logic | iOS owes |
|---|---|---|---|
| **Library feed** (S1) | Keyset-paged, day-sectioned, sticky headers, mosaic with 2×2 heroes, Day/Month/Year densities + zoom control, grid widens with window | shared (`PhotosFeedViewModel`, `FeedLayout`, `FeedDensity`, `GridWidthScale`) | SwiftUI grid over `FeedLine`s; pinch between densities |
| **Scrubber** (PH-17) | Track spans library, year pills, date bubble | shared (`FeedYearIndex`, `yearMarks`) | Placeholder-aware paging (§1.3) + a drag rail |
| **Multi-select** | Trash + add to album, partial-failure dialog, failures stay selected (§27) | shared (`BulkOutcome`) | Selection mode, bottom toolbar |
| **Motion tiles** (M5) | One video tile plays at a time, muted loop | shared (`MotionRotation`) | `AVPlayerLayer` tile player |
| **Viewer** (S3, PH-8) | Pager over the caller's list, pinch-zoom, drag-to-dismiss, chrome toggle, favourite, add to album, trash; size-driven hero rung (§34) | shared (`PhotoViewerViewModel`, `HeroPreviewSize`) | Pager + zoom + dismiss; ⚠⚠ hero bytes per page, never in the VM (§21.3) |
| **About panel** (PH-9–11) | Capture time, place, albums, details, on-device EXIF | shared | A sheet/detent under the photo |
| **Collections** (S4) | Favourites, Duplicates, Things (labels), Videos / Places / Years, user albums; create / rename / delete | shared (`CollectionsViewModel`) | List/grid sections |
| **Album detail** (S5) | Grid, remove, Favourites pseudo-album | shared (`AlbumDetailViewModel`) | Grid screen |
| **Search** (S2) | One bar, three sources (name / label / semantic), source badge, "search is off" ≠ "no results" | shared (`PhotoSearchViewModel`) | `.searchable` screen |
| **Trash** (PH-5) | Restore, delete for good, empty | shared (`TrashViewModel`) | Screen |
| **Duplicates** (PH-6) | Groups, keep-only, delete-permanently switch | shared (`DuplicatesViewModel`) | Screen |
| **Settings** (S7) | Sources + status, bucket picker, skip-duplicates, play-videos-in-grid, link to Trash | shared (`SettingsViewModel`); bucket picker needs a `MediaSourceProvider` | PhotoKit album picker (provider exists) |
| **Reachability wall** | Not installed / schema skew / fenced | shared | `ConnectToControlView` (§4 D4) |
| **Video in viewer** (M2) | ⚠ **Not built on Android either** | — | Shared phase with M2 (§5 PIO-6) |
| **Motion generation** (M4/M6) | Android `MediaCodec` renderer | proxy seam `MotionThumbnailRenderer` | **M6** — `AVAssetExportSession` in `file-service-proxy` `iosMain` |
| **Share out** (PH-G11) | ⚠ Deliberately absent (§27.1) | — | Parity says absent; see §7 Q3 |
| **AI** | Semantic search shipped (off by default); labels inert; People F1/F2a in code, gated | inference track | §3 |

Not built on either platform, and not owed by this doc: editing (PH-G12), album link sharing,
creation features, a map in Photos, the hidden/archive flag.

---

## 3. The AI features — what iOS reuses, and where it deliberately differs

The maintainer's steer: *depend on the AI track only partially, because the iOS implementation will
differ anyway.* The code supports exactly that split, because the platform seam is narrow and the
sync boundary is sharp.

### 3.1 What syncs and what does not (IN-D5, people §3.1)

| Artefact | Synced? | Consequence for iOS |
|---|---|---|
| `MediaLabel` rows — content labels, the `__indexed` marker, **person names** (`capability=faceClustering`) | ✅ `Eager`, LWW | iOS **reads** them with no model at all |
| SigLIP vectors (`EmbeddingStore`) | ❌ device-local | Semantic search needs an iOS index |
| Face templates, boxes, clusters | ❌ device-local | Unnamed-cluster suggestions need iOS face models |
| Models | ❌ per-device cache | iOS downloads its own, filtered by `supportedModelRuntimes` |

### 3.2 Three tiers, three different dependencies

| Tier | What the user gets | Depends on the AI track for | iOS-specific work |
|---|---|---|---|
| **AI-R — read** | Things albums, People *names* on photos, label hits in search | **Only the `MediaLabel` schema** — already synced, already read by `searchByLabel` / `allLabels` | None. Falls out of PIO-4. ⚠ Empty until Android/desktop produce labels (IN-D9 calibration, F3). |
| **AI-L — local index** | Search by description (`photosLike`), later zero-shot tags produced on iOS | Shared: `InferenceBackend` interface, `MediaIndexer`, `EmbeddingStore`, catalog/verifier/downloader, consent, ops, tokenizer | A Core ML backend in Kotlin/Native (no ONNX Runtime on iOS) and a second, fp16 catalog artifact; `IosImageDecoder` (ImageIO); `IosIndexingConditions`; `BGProcessingTask` scheduling; a Models + consent screen; single-owner stores (§3.3) |
| **AI-F — faces** | People section with unnamed clusters on iOS | Shared: `alignFace`, `clusterFaces`, `FaceTemplateStore`, the index pass's third half | Detector choice (§7 Q5), ORT for the embedder, the wipe hook on route 1. ⚠ Gated on Android F2 + ⚖️ counsel + threat model A40 |

**Where iOS will differ, by design** (and where "shared" stops):
- **Runtime.** Native Core ML through Kotlin/Native's `platform.CoreML`, not ONNX Runtime — decided
  from a phone measurement, never assumed (IN-D13 as amended §15.12.3 — NNAPI lost to CPU on
  Android; on the iPhone the fp16 Core ML towers ran ~19 × faster than ORT's CPU provider and ORT's
  CoreML provider failed). **Not** the same files: the same checkpoint exported a second time as two
  fp16 `.mlpackage` towers, a second catalog entry under the runtime `coreml`
  ([`ios-on-device-inference.md`](ios-on-device-inference.md) §2.1, §2.2, §3.4).
- **Scheduling.** `BGProcessingTask` with `requiresExternalPower`, plus *Index now* in the
  foreground. iOS decides when; `IndexingConditions` is advisory there, not a gate we own.
  ⚠ As built so far (`ios-on-device-inference.md` §14.19, 5e-1): the foreground half only, with
  the manual bar (in front, not hot, not Low Power Mode, not a low battery) read per photo and a
  pass cancelled when Photos leaves the foreground. ⭐ **And the overnight wake (PR-6, §16 there,
  behind the same flag):** leaving Photos with a half's two switches on and photos left submits a
  `BGProcessingTaskRequest` (`app.tenshu.photos.processing`, `requiresExternalPower`, no network);
  when iOS grants it (charging, idle), the wake takes the shared store's task token, re-arms,
  and runs the scheduled pass — the scheduled bar says yes **only inside the wake**, and there not at
  `.serious`, in Low Power Mode or off the charger. A leave during the wake stops nothing; the wake's
  end (or its expiry, which stops the pass at its next photo) lets both models go. A completed
  library withdraws the request. 📱 its three nights are owed (`how-to/photos-processing-wake.md`).
- **OCR (IN-5).** Apple Vision's `VNRecognizeTextRequest` per IN-D13 — the one capability where
  iOS can plausibly **lead** Android, since it needs no model delivery at all.
- **Where the UI lives.** Control-on-iOS has no Models screen; see §7 Q2.

### 3.3 ⚠⚠ The shared-container hazard nobody has written down

`IosFileDatabase` roots at the App-Group replica. `DefaultEmbeddingStore` appends to
`/inference/embeddings.log` under that root and serialises with an **in-process** `Mutex`;
`MediaIndexRunner` is single-flight **per process**. Under route 1 every first-party app runs the
same proxy graph. The day two apps bind an `InferenceBackend`, two processes append to one log with
no lock between them. So AI-L starts with a rule, not a feature: **exactly one app owns the
inference stores on iOS** — Photos — and they live in its private container, not the App Group
(§7 Q2, settled 2026-10-13; what that leaves other apps is `ios-on-device-inference.md` §3.1a). Every other app's `photosLike` answers **`NO_BACKEND`**, honestly, until a
cross-app answer exists: those processes bind no backend, and `photosLike` checks for one before it
looks at the store (`PhotosLikeImplementation.kt:65-70`), so `NOT_INDEXED` is unreachable there.
The copy for that refusal on iOS points at Photos (`ios-on-device-inference.md` §1.8).

✅ **Built (PIO-7 PR-3, 2026-10-07; [`ios-on-device-inference.md`](ios-on-device-inference.md) §13).**
The first paragraph describes the hazard as it was; this is what stands now.
- Photos' graph binds the vector log, the face-template log, the model bytes and the model index
  over a database in **Photos' own container** (`<Application Support>/photos-private`, excluded from
  backup). The App Group holds none of them.
- Every other iOS graph, Control's included, binds stores with no file behind them. They cannot
  write, so the constructor scrub and the realm-wipe clear that made a second writer are gone.
- Photos' stores carry a stamp of the realm they were built for, checked at install, on every
  return to the foreground, at the start of a pass and before `photosLike`. ⚠ It is the owner id
  alone until S0b-1.
- ⚠ No backend is bound yet. That is PR-5, and it must not land before this.

---

## 4. Decisions

| # | Decision | Why |
|---|---|---|
| **PIO-D1** | `photos-ios` is a SwiftUI app, **born on route 1**, over `:photos-shared-logic`. No Compose reaches iOS. | Parity doc D7; B-1 |
| **PIO-D2** | Every decision goes into tested `commonMain`; Swift renders and calls Apple APIs. When a PR needs a new judgement in Swift, move it down first. | Nothing iOS compiles in the cloud (CA-8a) |
| **PIO-D3** | Navigation: `AdaptiveAppShell` with **Library · Collections · Search** tabs; Settings is a toolbar sheet from Library, the way Apple's Photos puts account/settings behind a button, not a tab. Trash and Duplicates open from Collections › Utilities (Apple's placement), and also from Settings. | Look of Apple Photos + our shell (sidebar ≥ 1100 pt on iPad for free) |
| **PIO-D4** | Posture is the **wall** (`ConnectToControlView`), not the banner. A photo library has nothing legible without data, and "no photos" for an unreachable proxy tells the user their library is gone (`photos-app.md` §10.3). The storage banner stays. | ADR 0022 §Decision 4; Android's wall |
| **PIO-D5** | The backup **drain stays in Drive** on iOS, as on Android. Photos' Settings writes the same selection rows and shows status. | Two route-1 processes draining one PhotoKit cursor into one replica = double uploads |
| **PIO-D6** | The newcomer takes the prefix: every clashing top-level Photos name gets `@ObjCName("Photos…")`, found by a script, not by eye. | Parity doc D12 |
| **PIO-D7** | The feed uses a **placeholder-aware** presenter mode in `:core-paging`, tested on the JVM. Drive's mode stays as it is. | §1.3 |
| **PIO-D8** | Hero and tile bytes are decoded Swift-side with ImageIO downsampling (`kCGImageSourceThumbnailMaxPixelSize`) and cached per path in an `NSCache`; the VM never holds bytes. | `photos-app.md` §10.2, §21.3 |
| **PIO-D9** | The AI track is a dependency at its **seams** (schemas, ops, `InferenceBackend`, `MediaIndexer`), never at its Android runtime. §3.2's three tiers ship independently. | Maintainer's steer; §3 |
| **PIO-D10** | iOS inference stores have exactly one owner (Photos) until a cross-process lock exists. | §3.3 |
| **PIO-D11** | Floor stays **iOS 16.0**. `ContentUnavailableView` is 17+ — copy a sibling's hand-rolled empty state (the gotcha has cost five Mac passes). | Every sibling |

---

## 5. Phases

`☁` = done in the cloud and verified there. `🖥` = needs the Mac (Swift / `iosMain` compile,
simulator). `📱` = needs the iPhone. Every 🖥/📱 step is a **handoff PR** (§6).

| Phase | What | Where | Gate |
|---|---|---|---|
| **PIO-0** | Kotlin prep, no Swift: zone functions (`PhotoDates`) and the W-1 rung declaration out of `:photos-shared-ui` into `:photos-shared-logic`; `NotConnectedPhotosRepository`; `TenshuPagingPresenter.window`; `scripts/check-objc-clashes.py` + two `@ObjCName`s. **BUILT** — §8.1. | ☁ | JVM gate. `commonMain`-only change to `:photos-shared-ui`, so HR #8 is not triggered |
| **PIO-1** | The shell: `photos-ios/` (project.yml, entitlements, icons, README), `PhotosLocalRuntime` + `PhotosSurface`, umbrella export, `ios-smoke.sh` / `app-catalogue.sh` / `regenerate-xcode-projects.sh` / root `generatePhotosIosProject`. Screens: the wall, Library grid (Day density, plain grid), a read-only viewer pager. **WRITTEN** — §8.2; nothing of it has compiled. | ☁ writes, 🖥 compiles | **Handoff H1** (§8.3) — first compile, simulator, first iPhone launch |
| **PIO-2** | Library parity: mosaic, Day/Month/Year densities + pinch, sticky headers, scrubber with year pills + date bubble, multi-select (trash / add to album, partial failures), motion tiles (`AVPlayerLayer`). | ☁ + 🖥 | Batched into **H2** |
| **PIO-3** | Viewer parity: zoom (`UIScrollView`-backed), drag to dismiss, chrome toggle, favourite / add to album / trash, About sheet (details, EXIF, place, albums), size-driven hero rung. | ☁ + 🖥 | **H2** — plus a 📱 feel pass on a 20 000-photo library |
| **PIO-4** | Collections (all sections, album CRUD, album detail, Favourites), Trash, Duplicates, Search tab. AI-R lights up here with no extra work. | ☁ + 🖥 | **H3** |
| **PIO-5** | Settings: sources + status, the PhotoKit album picker (limited-library access handled), skip-duplicates, play-videos, "backup runs in Drive" copy + open-Drive link. | ☁ + 📱 | **H3** — PhotoKit needs a real library |
| **PIO-6** | Video: M6 (`AVAssetExportSession` motion rung in `file-service-proxy` `iosMain`) and viewer playback with `AVPlayer`, designed with Android's M2 so both land one design. | ☁ + 📱 | **H4** |
| **PIO-7** | AI-L: a Core ML backend in Kotlin/Native over a second, fp16 catalog artifact (no ONNX Runtime on iOS), `IosImageDecoder`, `IosIndexingConditions`, `BGProcessingTask` + `BGTaskSchedulerPermittedIdentifiers`, Photos-owned stores (PIO-D10), Models + consent screen, *Index now*. Starts with **IN-3m-iOS**: the latency harness on an iPhone, on the real corpus with decode timing (⚠ **still open**: a partial run of 2026-10-07 read 33 ms a photo with decode in one short pass per row; the cold / warm / +20 min runs, the battery run, the post-reboot launch and the HEIC sample are owed, so H5 is not answered; `ios-on-device-inference.md` §9.7). **Planned in [`ios-on-device-inference.md`](ios-on-device-inference.md)** (PR-0 and PR-0b device spikes, done; then PR-1…PR-7; D1–D14; PR-7, *Index now* continuing on iOS 26+, built behind the flag, §15 there; PR-6, the overnight `BGProcessingTask`, built behind the flag, §16 there). ⭐ The spikes decided the runtime: native Core ML fp16 at ~6 ms an image, with retrieval that matches fp32 (§2.1, §2.2 there). ⚠⚠ Every 📱 step after the harness waits for the App Group build to be installable (`ios-scheduler-lease.md` S0b) | 📱 first, then ☁ | **H5** — measurement can say "do not ship" |
| **PIO-8** | AI-F on iOS (people F5), after Android F2 + counsel + A40. OCR (IN-5) through Vision as an optional iOS-first capability. | ☁ + 📱 | ⚖️ + **H6** |
| **PIO-9** | Ship: iPad pass (sidebar, Split View ⅓), Dynamic Type, VoiceOver labels, device matrix, then Drive's P7 route (App Attest, TestFlight). | 📱 | `ios-drive-parity.md` §19 |

⭐ **PIO-2 → PIO-5 need no AI and no model**, so they never wait on the inference track. PIO-7
waits on a phone, not on Android: the model and the catalog already exist.

---

## 6. The cloud ↔ device loop

The session that owns this track runs in the cloud. A phase that needs a Mac ends in a **draft PR
titled `photos-ios: HANDOFF Hn — …`** whose body is the runbook below. A local session on the Mac
picks it up, fixes compile breaks in place (they are expected; CA-8a found two), records findings
in the phase's as-built section here, pushes, and hands back. The cloud continues from the doc,
never from memory.

**Every handoff PR body carries:**
1. **Build** — `./gradlew iosKotlinCheck`, `./gradlew :tenshu-sdk-ios:iosSimulatorArm64Test`,
   `scripts/ios-smoke.sh --build-only photos`, then `scripts/ios-smoke.sh --build-only` (all apps:
   an export can rename a sibling's type, D12).
2. **Simulator** — the phase's screens, and an iPad screenshot at full width and at ⅓.
3. **Device** (📱 phases) — `scripts/ios-smoke.sh --build photos` with `TENSHU_IOS_DEVICE`, then
   the phase's functional checks as a `| Check | Signal | Result |` table.
4. **What to write back** — the as-built section's *Verified* / *⚠ Not verified* block, and any
   defect found, as a numbered finding.

⚠ **The free personal team caps at 3 apps per device** (7-day profiles). Photos needs Control +
Photos at minimum on the phone; Drive too for PIO-5. §7 Q4.

---

## 7. Open questions for the maintainer

1. ~~**Q1 — Tabs.**~~ ✅ **Settled 2026-09-30 — PIO-D3 as written.** Library · Collections, the naming
   Google Photos and Apple Photos share, with **Search as iOS 26's detached round button**
   (`Tab(role: .search)`, the pattern Apple's own Photos uses — checked on the maintainer's iPhone,
   iOS 26.6.1) and Settings behind a toolbar button on Library. On iOS 16–17 the same tabs are a plain
   tab bar.
2. ~~**Q2 — Inference host on iOS.**~~ ✅ **Settled 2026-10-13 by the maintainer, as recommended:**
   Photos owns the index, the model store and the
   Models + consent screen, in its private container (PIO-D10). ⚠ What other iOS apps can then use
   of the model is an open question with an expected direction, recorded in
   [`ios-on-device-inference.md`](ios-on-device-inference.md) §3.1a. The alternative, a Models screen in
   Control-on-iOS writing into the App Group, needs the cross-process lock first.
   ⚠ How that is built: four locks (only Photos can hold a model, only Photos' `appOverrides` bind the
   backend and conditions, the stores move to Photos' backup-excluded private container, and every
   other app binds a store that never writes), plus a realm stamp so a reset Photos did not see is
   cleared before Photos reads. See
   [`ios-on-device-inference.md`](ios-on-device-inference.md) §3.1 and its D7.
   ✅ The store half is built (PR-3, §13 there): locks 3 and 4, the stamp, and the model index
   beside the bytes. The Models screen and the backend are PR-5.
3. **Q3 — Share.** Parity says no Share button until PH-G11 (`photos-app.md` §27.1). On iOS the
   system share sheet over the original bytes is ~one `ShareLink`, and its absence is conspicuous
   next to Apple's Photos. Build PH-G11's byte-share for both platforms in PIO-3?
4. **Q4 — Signing.** Is there a paid team for the device passes, or do we rotate apps under the
   3-app cap?
5. **Q5 — Face detector on iOS.** People §7a recommends MediaPipe everywhere (one landmark
   convention); Vision is free on iOS but gives a per-platform template. Decide before PIO-8.

---

## 8. As built

### 8.1 PIO-0 — the shared half (2026-09-23)

| Change | Where | Why |
|---|---|---|
| `PhotoDates` — every zone function (section start, year, header / viewer / About labels) | `photos-shared-logic/.../time/PhotoDates.kt` (was `photos-shared-ui/.../DayLabels.kt`, deleted) | §1.2. ⚠ Adds `kotlinx-datetime` to `:photos-shared-logic` — the `:calendar-shared-logic` precedent. Each function has an `internal` zone-taking twin, so the New Year cases are tested in Tokyo and New York |
| `photosWantedThumbnailClasses` + `declarePhotosWantedThumbnailClasses` | `photos-shared-logic/.../data/PhotosWantedThumbnails.kt` | The W-1 declaration was a lambda in the Compose client module. iOS passes `motionPlaybackSupported = false` until PIO-2's tile player exists — declaring `MOTION` before anything can draw it makes every device mint clips for nobody |
| `NotConnectedPhotosRepository` | `photos-shared-logic/.../data/` | §1.5. Typed reads answer `Unreachable`, the feed fails with `FeedReadException(unreachable = true)`, writes are refused. ⚠ `controlUnreachableReason` stays `null` — absence is the shell's question, never inferred |
| `TenshuPagingPresenter.window` + `PagedWindow` | `core-paging` | §1.3. `snapshot` unchanged, so Drive is untouched |
| `PhotosFeedBinding` + `FeedStatus` | `photos-shared-logic/.../feed/PhotosFeedBinding.kt` | View model + presenter + `FeedLayout` + a four-way status, for a shell with no `collectAsLazyPagingItems()`. ⚠⚠ Paging's `LoadState` is not on the umbrella's surface, so the *loading ≠ empty ≠ unreachable ≠ failed* rule is decided here, not re-derived in Swift |
| `scripts/check-objc-clashes.py` | `scripts/` | D12 without a Mac. Found `Photo` (would have renamed **timeline-ios's** `Photo` to `Photo_`) and `SettingsViewModel` (vs Control). ⚠ The umbrella already carries 15 older clashes (Fit/Timeline `DistanceForm`, Fit/Mia `OnboardingViewModel`, …) that shipped with `_` names; a bare run lists them and exits 0 |
| `@ObjCName("PhotosPhoto")`, `@ObjCName("PhotosSettingsViewModel")` | `photos-shared-logic` | The newcomer takes the prefix (PIO-D6). ⚠ Swift therefore spells Photos' photograph `PhotosPhoto` |

**Verified:** see §8.4.

### 8.2 PIO-1 — the shell, as written (2026-09-23)

- `photos-ios/` — `project.yml` (from `calendar-ios`, templates Shell + Onboarding + Storage, no
  Basemap, no PhotoKit usage string yet), entitlements (sim + device), icons, README, and five
  Swift files (`PhotosIosApp`, `LibraryScreen`, `PhotoTileView`, `PhotoViewerScreen`, `PhotosTheme`).
- `tenshu-sdk-ios`: `PhotosLocalRuntime` (no new service wiring; one reachability engine and one
  `RealmContentIndex` per app, as on Compose — the index was removed in `photos-app.md` §40), `PhotosSurface` (feed binding, viewer VM, tile and
  hero bytes as `NSData`), `PhotosLocalRuntimeTest`, and `export(":photos-shared-logic")`.
- Registrations: `ios-smoke.sh` (`spec_for` + default list), `app-catalogue.sh` (iOS columns filled;
  selftest green), `.githooks/regenerate-xcode-projects.sh`, root `generatePhotosIosProject`.
- ⚠ **One screen, not the shell's three tabs** — `AdaptiveAppShell` arrives with PIO-4, when
  Collections and Search exist (the Android PH-1 call, `photos-app.md` §10.2).
- ⚠ **The icon was drawn, not rendered from source.** `docs/design/tenshu-icons/` is gitignored, so
  the three PNGs were redrawn from the Android vector (`ic_launcher_foreground.xml`) at the geometry
  measured off `calendar-ios`'s icon (pictogram unit 10.2 px, centred). Coverage passes; **fidelity
  must be run on the Mac** (`scripts/check-icons.py` with the design source present) and the PNGs
  regenerated from `foreground/photos.svg` if it disagrees.

**⚠ Not verified — nothing in §8.2 has compiled.** No Swift, no `iosMain`, no `iosTest`. The SKIE
spellings (`FeedLineHeader`, `setDensity(value:)`, `layout.get(line:)`, `PhotoDates.shared`,
`photo.pixelWidth?.int32Value`) are the expected bridge shapes, not checked ones.

### 8.3 HANDOFF H1 — the first Mac + iPhone pass

For the local session. Fix compile breaks in place (expected — CA-8a's first pass found two), then
record what was found in a §8.5 *"What the Mac pass found"*.

1. **Kotlin (iOS):** `./gradlew iosKotlinCheck` and `./gradlew :tenshu-sdk-ios:iosSimulatorArm64Test
   --tests '*PhotosLocalRuntimeTest*'` — the four route-1 tests.
2. **Swift:** `scripts/ios-smoke.sh --build-only photos`, then **`scripts/ios-smoke.sh --build-only`
   for all ten apps** — the export must rename nothing in a sibling (D12). Specifically confirm
   `timeline-ios` still spells `Photo`, not `Photo_`.
3. **Icons:** `scripts/check-icons.py` with `docs/design/tenshu-icons/` present (fidelity half).
4. **Simulator**, Control provisioned: the wall when unprovisioned → the Library after; headers read
   like Android's ("17 December 2024"); scrolling past the first page grows the grid; a tap opens the
   viewer at that photograph and swiping pages both ways; a photo taken in Drive while Photos is
   backgrounded appears on return.
5. **iPhone** (`scripts/ios-smoke.sh --build photos`): the same, on a real library, plus a scroll to
   the bottom of ≥ 2 000 photographs — note memory in Xcode's gauge and any stutter.
6. **iPad:** a screenshot at full width and at ⅓ Split View.

| Check | Signal | Result |
|---|---|---|
| `iosKotlinCheck` | green | |
| `PhotosLocalRuntimeTest` | 4/4 | |
| `ios-smoke --build-only` (all) | 10 PASS | |
| timeline-ios `Photo` | not renamed | |
| icon fidelity | pass | |
| wall → library | as described | |
| paging past page 1 | grid grows | |
| viewer pager | both directions | |
| co-resident photo on return | appears | |
| 2 000-photo scroll | memory / stutter noted | |

### 8.4 Verification (cloud, 2026-09-23)

**Verified:**
- `:photos-shared-logic:jvmTest` — 35 classes, 0 failures, fresh timestamps (not UP-TO-DATE).
  New: `PhotoDatesTest` 5, `PhotosFeedBindingTest` 4, `NotConnectedPhotosRepositoryTest` 4,
  `WantedThumbnailClassesTest` 2.
- `:core-paging:desktopTest` — `TenshuPagingPresenterTest` 8/8 (3 new: mid-list window, paging from a
  window position, placeholders-off parity with `snapshot`).
- `:photos-shared-ui:desktopTest` — 20 classes green, so the Compose shell still compiles and renders
  over the moved `PhotoDates` and the shared W-1 declaration.
- `scripts/check-objc-clashes.py --only-involving :photos-shared-logic` — clean;
  `app_catalogue_selftest` — green; `scripts/check-icons.py` — coverage green (289 checks),
  fidelity skipped (source gitignored).

**⚠ Not verified in the cloud:**
- **Anything iOS** — §8.3 is the whole of it.
- **The full `scripts/run-gate.sh`.** Maven Central rate-limited this container (HTTP 429) for most of
  the session and the cache started empty, so only the modules touched were run. ⚠ The Android
  compile of `:photos-shared-ui` was not run either; the change there is `commonMain`-only, which the
  desktop compile covers, and HR #8 is not triggered — but run the gate before merging.
- A finding from getting there: `PhotosFeedBindingTest` first timed out 4/4 because its helper reset
  `Dispatchers.Main` before `vmTest` closed the view models, whose `viewModelScope` then could not
  join. Fixed by the `PhotosFeedViewModelTest` pattern (Main set per test, reset in `@AfterTest`).

### 8.5 What the Mac pass found (2026-09-24)

Run on a Mac and on the maintainer's iPhone, after rebasing onto master `f6d0f21ee`.

| Check | Signal | Result |
|---|---|---|
| `iosKotlinCheck` | green | ✅ green |
| `PhotosLocalRuntimeTest` | 4/4 | ✅ 4/4 (`iosSimulatorArm64Test`) |
| `ios-smoke --build-only` (all) | 10 PASS | ✅ 10 PASS. Photos' Swift compiled on the first attempt; every SKIE spelling §8.2 guessed was right |
| timeline-ios `Photo` | not renamed | ✅ `Photo` stays; Photos exports `PhotosPhoto` / `PhotosSettingsViewModel`; `check-objc-clashes.py` is clean |
| icon fidelity | pass | ✅ after re-rendering. The three hand-drawn PNGs were 18 421 channels / 6 671 px / 6 671 px adrift. They are now rendered from `foreground/photos.svg` + `android/themed/photos.svg` with `check-icons.py`'s own recipe (369/369) |
| wall → library | as described | ✅ wall on the simulator when unprovisioned; Library on the iPhone once Control had published |
| headers | "17 December 2024" | ✅ read like Android's (iPhone) |
| paging past page 1 | grid grows | ✅ (iPhone) |
| viewer pager | both directions | ✅ (iPhone) |
| co-resident photo on return | appears | ✅ with the `renderStill` fix applied: a new photo Drive backed up shows its thumbnail in Photos (iPhone, 2026-09-25). ⚠ Drive backs up only in the foreground or on an iOS-granted background wake (≥ 15 min, discretionary), so "on return" means after Drive has run |
| 2 000-photo scroll | memory / stutter noted | ✅ no stutter seen; memory not yet read off Xcode's gauge |
| iPad full width + ⅓ Split View | screenshots | ✅ iPad Pro 13" simulator over the `-photosFixture` harness: full width draws 6 columns (Compose's count at that width) after the fix below; ⅓ is an **emulation** (`-photosCompactWidth`, 320 pt, compact size class) and keeps the phone's 3. ⚠ Real Split View needs a gesture `simctl` cannot make |
| `scripts/run-gate.sh` | green | ✅ 115 test tasks, 1 709 classes, JS and iOS lanes green |

**What it found:**

- **The rebase broke `:photos-shared-logic`, and the gate caught it.** Master's People work (F3a/F3b)
  added `faceGroups` and `nameFaceGroup` to `PhotosRepository`, and `NotConnectedPhotosRepository`
  implemented neither. The gate reported three modules that "produced test results last run and
  NONE now", and the iOS lane named the class. Both methods now answer the try-again arm
  (`PeopleStatus.Failed`, `NamingOutcome.Failed`), never the consent arm, for the same reason
  `searchSemantic` does. The test covers both.
- ⚠⚠ **A lockstep install is Control + Drive + Photos, and Photos costs a slot.** Free-team signing
  caps a device at three apps (`MIFreeProfileValidatedAppTracker`, `ApplicationVerificationFailed`).
  With Control, Drive and Maps installed, Photos was refused until Maps was removed. Every route-1
  co-resident at the old version must move with it (HR #9): the phone was at `file/service` 14 and
  master is at 15.
- ⚠⚠ **The first device build linked a stale XCFramework, although its prebuild had just rebuilt
  the framework.** Photos went onto the phone declaring `file/service=14` beside Control and Drive at
  15. The binary lacked `PendingMove`, and a relaunch did not migrate the stamp. A second build was
  correct; after reinstalling, the store migrated 14 → 15 with all 8 709 rows kept. Before any device
  install, `strings -a <App>.app/<App>.debug.dylib | grep -c <newest symbol>` must be non-zero.
- ⚠⚠ **A photo the iPhone backs up reaches Photos without a thumbnail — a Drive defect, now
  fixed.** From the hero-rung step-down change (2026-09-16) iOS minted no thumbnail at upload: `renderStill` threw a
  Kotlin/Native `ClassCastException` after rendering, and a catch-all hid it. Drive-iOS draws such a
  photo from its local original, so it looked fine there; Photos, which holds no original, drew a grey
  tile. Uploads from that window stay thumbnail-less (`tech-debt.md`).
- **"Slowly" is the free team, not Photos.** Without an App Group there is no shared replica on the
  device, so a photo Drive backs up reaches Photos' own store through the relay, not the file system.
- **The viewer span for ever on such a photo.** The hero stream completes empty, and the page showed
  its spinner whenever it had no image. It now takes Compose's `HeroState.Absent` and says "This photo
  hasn't been downloaded to this device yet."
- ⚠⚠ **The iPad drew the phone's grid.** `PhotosFeedBinding` took a column *count* the shell never
  set, so a 1032 pt window drew three 340 pt tiles — each an upscale of a rung a third that size, the
  exact defect `GridWidthScale` exists to prevent on Compose. The binding now takes the grid's
  *width* (`setGridWidth`) and derives the count with `columnsAt`, Compose's own rule, so a density
  change cannot leave it stale; `LibraryScreen` reports its measured width.
- **`-photosFixture` / `-photosCompactWidth`** (`PhotosIos-Dev`, `DEV_HARNESS` only): 96 generated
  photographs over `MockPhotosRepository`'s real paging, skipping the route-1 gate. It proves layout,
  not the data path — the `-contactsScreen` precedent.
- `-genesisAutoComplete` creates a realm but does not publish the route-1 provisioning; the real
  onboarding (`provisionDriveSession` (⚠ deleted 2026-11-05 with the co-resident EPC handoff, cleanup D; `ensureDriveProvisioned` is the onboarding call now)) does. The simulator therefore cannot reach the Library without
  taps, and a fresh realm would have no photos to show anyway. The provisioned checks ran on the
  iPhone.

### 8.6 PIO-2 slice 1 — densities, re-anchor, sticky header (2026-09-26)

**What Android actually has.** No pinch: one toolbar button that *cycles* the grouping
(`photos-app.md` §32; `App.kt` records pinch as deliberately unbuilt — the grid owns the vertical
drag). So parity is that button, and pinch stays out of PIO-2 (⚠ the §5 row said "+ pinch").

| Change | Where | Why |
|---|---|---|
| `FeedDensity.description` | `:photos-shared-logic` (was private in `App.kt`) | The button's label, one string for both shells |
| `FeedLayout.labelAt` | `:photos-shared-logic` (was private in `PhotosFeedScreen.kt`) | The sticky header's text; Compose's overlay and scrubber use the same function |
| `PhotosFeedBinding.setViewport` / `scrollRequests` / `cancelReanchor` / `labelAt` | `:photos-shared-logic` | The re-anchor, decided in Kotlin where the JVM tests it: a density or width change carries the TOP photograph's library position across (Compose's `DensityAnchor`), retrying as the rebuilt stream's pages land |
| Density button, sticky overlay | `LibraryScreen.swift` | ⚠ Cycles Day → Month → Year: `Mosaic` is skipped until slice 2 draws `FeedLine.Feature` |
| ⭐ The grid is a `UICollectionView` (`FeedGridView.swift`), one item per `FeedLayout` line, delegate-sized, cells drawn with `UIHostingConfiguration` | `photos-ios/Sources/` | The re-anchor and the sticky header need the EXACT top line while pages re-cut the lines; SwiftUI's `LazyVStack` could not give it (findings 2–4, 12 below). Layout attributes are exact, `scrollToItem` lands exactly, and one code path serves iOS 16 and 17 |
| `-photosDemo <line>`, numbered fixture tiles, `[photos-probe] top=#N` | `PhotosIos-Dev` only | `simctl` cannot scroll or tap; the demo scrolls, then presses the button twice, and each tile carries its index so a screenshot names the photograph on top |

**What the simulator found (four defects, all fixed before commit):**
1. ⚠⚠ **A scroll request indexes ITS layout.** Scrolling before that layout was drawn resolved the line
   against the old lines. `ScrollRequest` now carries its `FeedLayout` and the shell waits for it.
2. ⚠⚠ **SwiftUI's `ScrollViewReader.scrollTo` lands by LazyVStack's height ESTIMATE** for unbuilt
   lines — headers and rows differ — and put photo 299 on top for photo 274.
3. ⚠⚠ **`scrollPosition(id:)` (iOS 17) is exact to WRITE, stale to READ**: when a page lands above and
   inserts headers, SwiftUI keeps the pixel offset and the bound id stays put.
4. ⚠ **Preference-reported frames lived in the wrong space** (~53 pt off, under the navigation bar).

**Eight more, all in the re-anchor, all fixed with a test that fails without
its fix:**
5. The viewport line was resolved against the newest layout, not the one drawn —
   `setViewport(…, layout)` now carries it.
6. Cancelling cleared the pending pass but not the published request; the shell re-checks a queued
   scroll, and (7) re-checks again at iOS 17's second asynchronous hop.
8. A tile tap never crosses the drag threshold — leaving the Library now cancels.
9. ⚠⚠ **Settling must recognise the rebuilt GENERATION.** It first waited for a second distinct
   layout — but a rebuilt window equal to the old one is swallowed by the window `StateFlow`, so the
   anchor stayed live. A presentation counter was then fooled by an OLD generation presented late:
   a re-collect makes `cachedIn` replay it (reproduced in the test with a held-back feed). Now each
   `FeedItem.Tile` carries the density it was cut for, and the anchor settles when the window on
   screen says it was cut for the new density and holds the photograph. No `:core-paging` change.
10. A page replacing the layout between "ready" and the scroll running made the request's line name
    another photograph. Dropping such a scroll lost the place (the probe measured it); the request
    now carries the photograph and `lineIn(drawn)` re-resolves it at the last hop.

11. ⚠⚠ **The shell must not mirror the decision.** Swift copied the request through async
    observers, so a queued scroll saw a cancelled request as live, and a request whose exact layout
    was conflated away was never released. `PhotosFeedBinding.scrollTarget(drawn:)` now decides —
    synchronously, at every hop — whether to scroll and where; the Swift side only triggers. PIO-D2
    said this from the start: the arithmetic is decided where a JVM test can see it.

12. Frame measurements did not say which layout drew them, and — found by the probe, not review —
    `LazyVStack` keeps cached rows whose `GeometryReader` frames go STALE after a re-phase: at rest the
    report put line 111 (photo 256) at y = 122 while photo 277's row was on screen, so the sticky header read
    "2 January 2025" over a 31 December row. The previous commit showed it too.

⭐ **Decision (maintainer, 2026-09-26): the grid moved to `UICollectionView`.** Findings 2–4 and 12 are
one fact — SwiftUI's lazy stack does not know where its lines are while pages re-cut them — and no
further patch closed it. With the collection view the top line comes from `layoutAttributesForItem`
after every scroll and reload, the re-anchor is `scrollToItem(at: .top)`, a drag is
`scrollViewWillBeginDragging`, and cells read their tiles off the layout they are DRAWING
(`FeedLayout.itemAt`), never the binding's newest. The binding's decisions (`scrollTarget`,
`labelAt(line, layout)`, `setViewport(…, layout)`) are unchanged.

**Codex on the collection-view grid (forty-five P2, all fixed or superseded):**
13. `reloadData()` keeps the PIXEL offset, so a page landing above — headers added, lines re-cut —
    changed the photograph under the user's thumb; the binding re-anchors only density and width.
    The grid now keeps the top photograph, its header-or-row, and the offset into its line across
    every reload (`FeedGridView.restore`). Probe: after the jump, photo 277 stays on top through the page
    loads, where the SwiftUI grid's top wandered 120 → 146 → 111 → 120.
14. A tap sent the tile's index in the layout that DREW it, and the viewer resolved it in the newest
    list. The tap now carries the photograph and `PhotosFeedBinding.positionOf(path)` finds it where
    it is now — JVM-tested with a grouping that really shifts the index; the probe opens photo 277 after
    two regroupings.
15. The sticky header took touches, so a swipe starting on it neither scrolled nor cancelled a
    re-anchor — it no longer hit-tests.
16. `loadedPhotoPositions()` read the presenter's window while `photoAt` read the layout derived from
    it a dispatch later; `positionOf` (and the viewer, since PIO-1) paired the two. Positions now come
    from `FeedLayout.loadedPhotoPositions()` — the same snapshot `itemAt` reads. ⚠ The test pins the
    invariant; the race itself (a window update caught mid-dispatch) is closed by construction, not
    reproduced.
17. The reload kept the top photograph by its library ORDINAL, which a backup burst or a deletion
    ahead of it shifts — restoring another photograph. It now keeps the PATH and resolves it in the
    new layout (`FeedLayout.photoOrdinalOf`), the ordinal only as a fallback when the photograph is
    not loaded there. Tested on two layouts with four photographs inserted ahead.
18. That identity survived one reload only: when the new generation did not hold it yet, the next
    reload captured whatever tile sat at the fallback ordinal. An unresolved identity is now carried
    across reloads until found, a drag, or the binding's re-anchor takes over.
19. Every emission counted against the re-anchor's pass limit, so a slow regrouping whose OLD
    generation kept presenting gave up before its own arrived. Only the awaited generation's passes
    count; and a width change during a density re-anchor keeps waiting for that generation. ⚠ The
    test pins the settle but cannot make this static source's old generation APPEND — that needs a
    real Pager.
20. The viewer route still carried only a position, re-read when the viewer was built; a page landing
    in between, or a move changing the path, opened another photograph. The route carries
    `contentId` (move-stable) and the path; the viewer resolves them against its own snapshot.
21. Only the disposable grid coordinator remembered a performed re-anchor; a grid recreated after an
    error and a retry replayed it. `PhotosFeedBinding.acknowledgeScroll` consumes it in the binding.
22. Restoring the offset inside `updateUIView` fired `scrollViewDidScroll`, which wrote SwiftUI state
    mid-update; reporting is silenced while restoring and runs on the next turn.
23. The reload anchor kept the path but not `contentId`, so a photograph moved while photographs
    arrived ahead could not be found and the stale ordinal won. It resolves `contentId`, then the
    path (`FeedLayout.photoOrdinalOf(contentId, path)`) — tested with a rename plus four arrivals.
24. The grid marked a re-anchor handled when it was QUEUED; if the layout advanced before the hop ran,
    `scrollTarget` rejected the stale layout and nothing retried it. Only a performed scroll is handled
    now; an unperformed one is offered again on the next drawn layout.
25. The binding's own pending re-anchor kept only the ordinal, so photographs arriving between the
    Day and Month generations made Month read it as another photograph — and its scroll overrode
    the grid's identity restore. `Pending` and `ScrollRequest` carry the photograph's identity and
    resolve it in each layout; an anchor with an identity settles only once it is found. Reproduced
    with a feed whose regrouped generation holds four new photographs ahead.
26. ⚠⚠ **`contentId` is not a photograph's identity — it names the BYTES, and a copy shares it**
    (`file-content-addressing.md`). Content-first lookup opened or restored the first copy for the
    second. The exact PATH decides first; `contentId` only when exactly one loaded photograph carries
    it (a moved photograph), else nothing and the caller's fallback.
27. A deleted top photograph stayed "unresolved" forever and kept forcing its stale ordinal. It now
    expires once a layout's loaded window covers where it was and it is not there.
28. The drawn layout's density came from a cache of the last six layouts; more newer ones evicted it
    and a Day row got a Month label. `FeedLayout.density` carries it (tested with eight newer layouts).
29. ⚠⚠ **Conclude only from a COMPLETE layout.** Three heuristics drew conclusions from the loaded
    window while placeholders hid the rest: a `contentId` "unique among loaded rows" (a copy can be
    unloaded), a photograph "gone" because its old ordinal was loaded (a burst also moves it pages
    down, leaving the ordinal to another), and an unresolved identity spending the pass limit before
    its page arrived. `FeedLayout.isComplete` (no placeholders) is now the only licence for either
    conclusion; otherwise the identity stays unresolved until found, a drag, or a newer change.
30. A queued re-anchor whose drawn layout lacked the identified photograph fell back to its stale
    ordinal and was acknowledged. `ScrollRequest.lineIn` now returns no line for an unresolved
    identity (retried on a later layout), and the binding publishes no request for one — and
    withdraws the one it had already published for a layout that still held it.
31. A tap on a photograph the binding's newer window lacked was dropped. The tile passes the position
    it was drawn at; the route falls back to it and the viewer re-resolves on arrival.
32. …but the viewer model took only that position and resolved it once. `ViewerModel` now keeps the
    tapped identity and resolves it against every layout until found; the drawn position is only a
    place to stand meanwhile — and (33) not shown: while the identity is pending the viewer shows a
    loading state, since that position can hold another photograph.

⭐ **Decision (maintainer, 2026-09-26): a photograph's identity is its PATH** — the platform's rule
(`file-content-addressing.md` §3.3.1: `File` is keyed by path; a move is a new identity, migrated by
`DataIdMigrator`; the stable `fileId` field shipped and was deleted, §3.3.2). Findings 23, 25–26
and 29's `contentId` half had put `contentId` in front of the path — but it names the BYTES: a copy
shares it and an edit changes it, and every copy / "unique among loaded rows" edge after that came
from there. **Superseded:** `positionOf`, `FeedLayout.photoOrdinalOf`, the pending re-anchor,
`ScrollRequest`, the grid's reload anchor, the tap and `ViewerRoute` all resolve by path only. A moved
photograph is a new one: the grid treats it as gone (absent from a complete layout) or waits for it,
like any other photograph.
34. With that, the viewer's wait also (a) asks for pages around where the tap was drawn — the grid is
    gone, so nothing else does — and (b) ends with "This photo is no longer in your library" once a
    COMPLETE layout lacks the path, instead of spinning for ever. (35) It pages toward BOTH edges of
    the loaded window, not only around the stale fallback — that region loads and then stalls, while
    the photograph may be pages further on. (36) Once open, the viewer keeps the path of the photograph ON SCREEN —
    updated on every swipe, re-resolved on every layout — so a refresh shifting indices does not
    silently switch photographs; gone from a complete layout, it moves to the nearest photograph
    (next, else previous) — the old index can be a header or past the end — or shows the missing
    state if none is left. Moved into an UNLOADED part of the list, it hides behind the loading
    state — the old index shows another photograph — and is looked for like a stale tap. Gone, it
    gives way to its NEIGHBOURS by identity (remembered from the snapshot it was shown in): a
    deletion's refresh can shift everything ahead, so the old index cannot find "the next one". A pager
    page reused for a new photograph clears the previous occupant's image when its path changes — and
    so does a reused grid tile. A tile reloads when its SIZE changes too (Year → Day would keep the
    tiny Year thumbnail), keeping its picture meanwhile. The grid's failure branch also cancels the
    binding's re-anchor, and the grouping button reads the binding's density, not a mirror (two quick
    taps lost one).
37. While the grid's kept identity is unresolved it pages toward both edges of the loaded window, as
    the viewer does — sitting on the fallback ordinal never reaches the prefetch boundary. And the
    viewer's lookup watches the feed's status: if paging fails, it offers Try Again instead of a
    spinner that no layout will ever end — through `PhotosFeedBinding.pagingFailed`, since `status`
    reports only the refresh and the lookup pages toward the edges. The grid's unresolved anchor lets go
    when an edge load fails — observed on its own (`LibraryModel.pagingFailed`), since a failed load
    changes no layout — rather than keep restoring a stale ordinal it can no longer correct. The
    viewer's failure resets whenever a lookup ends or a new one begins. After Try Again, a stale failure replayed by
    that StateFlow is ignored until the new attempt shows a non-failed value — and Try Again calls
    Paging's own retry (`TenshuPagingPresenter.retry` → `PhotosFeedBinding.retryPaging`): the old
    `retry()` re-collected, which behind `cachedIn` replays the failed generation and restarts nothing. The
    viewer observes ONCE, on a container around its branches: a branch-scoped observer was cancelled
    and recreated across Try Again's branch swap and missed the retry's Loading → Error.

**Verified:** `PhotosFeedBindingTest` 21/21 (8 new over the first draft; the older-layout guard
checked by mutation) (3 new — a density change and a width change keep the top
photograph; a row's label is its section's); the re-anchor tests fail against a mutant that scrolls
to line 0. `:photos-shared-ui:desktopTest` 20 green (Compose on the moved helpers). Simulator,
`-photosDemo 120` on the 1 500-photograph fixture:

| Check (collection-view grid) | iPhone 16 Pro | iPad Pro 13" |
|---|---|---|
| sticky header after the jump, at rest — correct section, never a duplicate | ✅ "31 December 2024" over photo 277 | ✅ |
| Day → Month keeps the top photograph (photos 277 / 146) | ✅ exact — photo 274's row at the top | ✅ exact — photo 140's row |
| Month → Year keeps it | ✅ exact — photo 274's row | — |
| a tile opens the viewer at that photograph | ✅ photo 277, with the back button | — |
| columns | 3 / 5 / 7 | 6 / 10 / — |

**⚠ Not verified / open:**
- **iOS 16** — no 16 runtime on this Mac. The collection view has one code path, so this is now a
  runtime check of the same code, not an untested branch.
- A real library on the iPhone, and a real finger (tap, drag-to-cancel) — H2.

### 8.7 PIO-2 slice 2 — the Mosaic (2026-09-27)

Swift only: `FeedLayout` already cut `FeedLine.Feature` for Compose, so the slice is drawing it and
letting the density button reach it.

| Change | Where | Why |
|---|---|---|
| Mosaic is the default density, and the button cycles all four | `LibraryScreen.swift` | Parity with Android: the shared `PhotosFeedViewModel` starts at `Mosaic`; slice 1's skip is gone |
| A feature line is two rows tall (`2·edge + spacing`) and draws each 3-tile block as a hero beside two stacked tiles | `FeedGridView.swift` (`featureBlock`) | Which tile is the hero and which side it sits are `FeedLine.Feature`'s (`isHero`, `heroLeftIn(block:)`) — Swift only places them |
| The sticky copy hides over a fully visible header with half a point of slack | `FeedGridView.reportViewport` | ⚠ The settled inset at the top of the feed is `2.8e-14`, not `0`; slice 1's exact `>=` drew the copy over the first header (only visible now that Mosaic opens at the top) |

⚠ **The rhythm runs through the library, not per day** (`FeedLayout`'s KDoc): features sit at slots 0
and 3 of every seven full rows, `slot = photo / columns`. A screen of rows without a feature — photo 277
to photo 290 on the phone sits in slots 1, 2, 4 and 5 — is the rule, not a missing render.

**Verified (simulator, 1 500-photograph fixture):**

| Check | iPhone 16 Pro | iPad Pro 13" |
|---|---|---|
| top of the feed: hero-left feature, one header, no sticky copy over it | ✅ #0 hero, #1/#2 stacked | ✅ |
| a slot-3 feature puts the hero on the right | ✅ photo 305 hero, photos 304/306 left | — |
| two blocks per line on the wider grid, alternating sides | — | ✅ #3 left, #7 right |
| `-photosDemo 120`: Mosaic → Day → Month keeps the top photograph | ✅ photo 277, then photo 274's row | — |

**⚠ Not verified:** a feature's tile opening the viewer (`simctl` cannot tap); a real library on the
iPhone.

### 8.8 PIO-2 slice 3 — the scrubber (2026-09-27)

Compose's `FeedScrubber` (`photos-app.md` PH-17) on iOS: a thumb over the grid's trailing edge that
seeks through the whole library, with the year pills and the date bubble while it is held.

| Change | Where | Why |
|---|---|---|
| ⭐ `scrubberPills(marks, track, thumb, pill, thumbFraction)` → `[ScrubberPill(year, top)]` | `FeedYearIndex.kt` (`:photos-shared-logic`), 4 tests | Which pills survive (thinned, the one under the thumb hidden) and where they sit. ⚠⚠ Compose's `FeedScrubber` now draws the same function: two copies of that arithmetic drift into a pill naming a year the thumb is not at |
| `FeedScrubberView` + `ScrubberState` | `photos-ios/Sources/` | The thumb rests at `FeedLayout.progressAt` of the top line, a drag seeks to `lineAtProgress` — both in PHOTOGRAPHS, the pills' unit — and the bubble is `labelAt` of the line the grid SHOWS, not the one the finger aimed at (`FeedScrubber.dateAt`) |
| `ScrubberState` and `PreviewGate` are their own objects | same | ⚠ Written at scroll rate; published through `LibraryModel` they would rebuild the screen and the grid representable every frame, and the gate would re-render every tile |
| Previews pause while held; a cached tile still shows | `PhotoTileView` (`PreviewGate`) | Compose's `previewsEnabled = !scrubbing`. The task is keyed on the pause, so letting go loads what the scrub skipped |
| Grabbing the thumb cancels a waiting re-anchor | `FeedGridView.userTookTheList` | ⚠ The seek is `scrollToItem`, which no UIKit drag callback hears — the same hole Compose closes in `onScrubbingChange` |
| Wakes on the user's scroll only, fades 1.2 s after | `ScrubberState.moved` | `isDragging \|\| isDecelerating` — a re-anchor or restore moving the list is not the user, as Compose's `isScrollInProgress` also distinguishes |
| `-photosScrub <p1> <p2>` | `PhotosIos-Dev` only | `simctl` cannot drag: grabs the thumb, seeks to `p1` in ten steps, holds, seeks to `p2`, lets go; logs `scrub held= rest= bubble= marks=` |

**What the simulator found:** the chevrons, stacked at `spacing: -6`, fused into a diamond at
footnote size; and the capsule vanished over light tiles. Fixed before commit (spacing 2, a thick
material with a shadow).

A press-and-hold sent no movement, so no seek reported the viewport and the bubble
stayed empty until the finger moved. Grabbing the thumb now reports at once
(`FeedGridView.scrubberGrabbed`); the harness logs `scrub grabbed bubble=20 January 2025` before any
movement.

A drag to the bottom left the grid clamped at its end, where the top line is
rows short of the last photograph — so the resting thumb, read from `progressAt` alone, jumped up on
release. At the end of the scroll range the thumb now rests at 1, on **both** shells (Compose's
`customProgress` had the same gap). Pinned by `FeedScrubberTest.aDragToTheBottomLeavesTheThumbAtTheBottom`,
which fails without the fix (892 → 875 px on release); iOS logs `scrub released rest=1.0`.

A touch iOS cancels — backgrounding, Control Center, the view going — never
reaches `DragGesture.onEnded`, so the scrub stayed held with every preview paused. A `@GestureState`
(reset by SwiftUI on cancellation) and `onDisappear` now end it; `endDrag` is idempotent. ⚠ **Not
verified:** `simctl` cannot cancel a touch, and the harness drives `ScrubberState`, not the gesture.

**Verified (simulator, 1 500-photograph fixture — two years, 2025: 274, 2024: 1 226):**

| Check | iPhone 16 Pro | iPad Pro 13" |
|---|---|---|
| a seek lands where the thumb is (`rest` read back from the grid) | ✅ 0.3→0.2995, 0.6→0.598 (photo 897 ≈ 0.6·1 499), 0.95→0.950 (photo 1424) | ✅ 0.5→0.497, 0.95→0.948 |
| bubble = the sticky header's date | ✅ "19 December 2024", "17 November 2024", "11 October 2024" | ✅ "27 November 2024" |
| pills while held — 2025 at the top, 2024 at 274/1 499 | ✅ | ✅ |
| previews paused while held, loaded on release | ✅ | ✅ |
| thumb fades after release | ✅ `awake=false paused=false` | ✅ |

**⚠ Not verified:** a real finger (the drag gesture itself — the harness drives `ScrubberState`, not
`DragGesture`), the wake on a user scroll (needs a drag), a pill thinned out by crowding (the fixture
has two years), and a real library on the iPhone.

### 8.9 PIO-2 slice 4 — multi-select (2026-09-27)

Android's S1 (`photos-app.md` §27) on iOS. The selection, the bulk actions and their contract —
successes leave the selection, **failures stay selected** so they can be retried — were already
`PhotosFeedViewModel`'s; the slice is gestures, chrome and a sheet.

| Change | Where | Why |
|---|---|---|
| `BulkOutcome.title` / `message`, per `BulkAction` | `:photos-shared-logic`, `FeedSelectionTest` | The report's sentences move out of Compose so both shells say the same thing. ⚠ Now singular-aware: the iOS pass read "1 could not be, and **are** still selected" (and "1 photos updated") — fixed for Android too |
| `PhotosSurface.albums()` → `AlbumsResult` | `:tenshu-sdk-ios` | ⚠⚠ The sheet keeps the three cases apart — `Unreachable` is **not** "no albums" |
| Long press toggles; while a selection exists a tap toggles instead of opening | `SelectableTile` (`FeedSelection.swift`) | As Android. ⚠ Empty means not selecting — no separate mode flag. ⚠ The tap reads the view model's selection synchronously, not the async mirror (the density button's lesson, PIO-2 slice 1) |
| A selected tile is inset 6 pt with a checkmark | `PhotoTileView` | Inset, not tinted, as Android draws it (`PhotoTile`) |
| "N Selected" + Cancel in the navigation bar; Add to Album and Move to Trash in a bottom bar | `LibraryChrome` | Replaces the density button while selecting — Android's bar replaces its header for the same reason. ⚠ No Share (§7 Q3, PH-G11). No confirmation on Trash: it is recoverable |
| Album sheet: albums / *No Albums Yet* / *Can't Reach Your Albums* + Try Again / failed | `AlbumPickerSheet` | ⚠ The empty copy names no tab: iOS has no Collections yet (PIO-4) — Android's copy pointed at a renamed tab once (`photos-app.md` §24) |
| The outcome report, always — the all-succeeded case too | `LibraryChrome` alert over `SelectionState.outcome` | Android's `lastBulk`: a bulk action with no feedback leaves the user counting tiles |
| `SelectionState` is its own object | `FeedSelection.swift` | A toggle re-renders the marks, not the screen or the grid representable |
| `-photosSelect`; the fixture gains two albums and a trash that fails every fourth photograph | `PhotosIos-Dev`, `PhotosScreenshotFixture` | `simctl` cannot long-press, and a mock whose every write succeeds cannot show the partial report — the case the report exists for |

**Three more, all in the SHARED view model, so Android had them too:**
1. ⚠⚠ **A second tap mid-flight raced the first** — both snapshotted the same selection and moved the
   same paths. `act` is now single-flight, and `acting` lets both shells disable the bar.
2. ⚠⚠ **The result was ASSIGNED** (`selected = failures`), so a Cancel or a toggle made while the writes
   ran was overwritten. It now removes the successes from the selection as it stands at the end.
3. ⚠ **"Could not be moved" for a failed album add** — adding moves nothing. `BulkOutcome` carries
   its `BulkAction`, and the copy says what happened ("moved to the trash" / "added to the album").

4. ⚠ **A snapshot photograph deselected and picked again mid-flight was dropped** by the subtraction
   (second pass). Paths the user toggles while an action runs are theirs: `touchedWhileActing` keeps
   them out of the subtraction, so the latest intent wins.

5. ⚠ **The report promised "still selected so you can try again" after a Cancel** (third pass) —
   Cancel stays enabled while writes run. `BulkOutcome.failedStillSelected` counts the failures
   selected at the END, and the sentence offers the retry only when they all are.

Pinned by `FeedSelectionTest` (17): one test per fix; 1, 2, 4 and 5 checked by mutation (dropping the
guard fails one test; the old assignment fails two; subtracting without `touched` fails the reselect
test; dropping the new sentence fails the Cancel test).

**Verified (simulator, fixture):**

| Check | iPhone 16 Pro | iPad Pro 13" |
|---|---|---|
| #1–#4 selected: marks, inset, "4 Selected", Cancel, bottom bar | ✅ | ✅ (the hero #3 inset too) |
| album sheet lists Holiday (12) and Family (40) | ✅ | ✅ |
| add to album → "Done / 4 photos added to the album.", selection cleared | ✅ | ✅ (run before the per-action copy — it read "updated") |
| trash with #3 failing → "Some photos could not be moved to the trash / 3 moved to the trash. 1 could not be, and is still selected…", **#3 still selected** ("1 Selected") | ✅ | ✅ #3 kept (run before the copy fixes) |

**⚠ Not verified / open:**
- A real long press and tap (`simctl` has neither — the harness calls `SelectionState`, which is what
  the gestures call), and a long press inside the scrolling collection view on a device.
- ⚠ **A trashed tile stays on the fixture's grid**: the mock records the trash but does not take the
  photograph out of the feed. The real repository drops it through the chain's change signal (the
  path Android's bulk trash relies on too) — unverified on iOS until a device pass.
- The *Can't Reach* and failed sheet states are written, not rendered — the fixture's albums always load.



### 8.10 PIO-2 slice 5 — motion tiles (2026-09-27)

`photos-video-support.md` §5 (M5) on iOS. The rotation — one player for the feed, a visible video
tile played through once and then the next in document order, wrapping; only a tile whose clip is
already in hand is ever handed the turn; one clip in flight — is `MotionRotation`'s, shared and
tested. The slice reports what is on screen, pauses when nobody can see it, and plays one turn.

| Change | Where | Why |
|---|---|---|
| The grid reports its visible video tiles, in document order, on every viewport report (deduplicated) | `FeedGridView.reportVisibleVideos` | ⚠⚠ Per ROW of a feature: a two-row line on screen does not put its lower small tile there (`FeedLine.Feature.rowOf` — Compose's rule, mirrored) |
| None while the scrubber is held; re-reported on release | same, `ScrubberState.released` | Compose's Q2: nobody reads a moving tile under a scrub |
| Paused behind the viewer and in the background | `MotionState.setAppeared` / `setSceneActive` | Compose's `DisposableEffect` + `LifecycleResumeEffect`: a backgrounded app decoding video is a battery bug |
| `MotionTilePlayer` — `AVPlayerLayer`, muted, no chrome, played once | `FeedMotion.swift` | ⚠⚠ Reports finished EXACTLY ONCE PER TURN AND ALWAYS: at the end, 3 s after a failure (a nominal turn, so all-failing degrades to a still grid), and at a 10 s ceiling if the player goes silent — the rotation's clock (`MotionTilePlayer`'s contract). Torn down without reporting: a tile that left the screen is heard through the visible set |
| Only VIDEO tiles observe the turn (`MotionSlot`) | same | A hand-over re-renders them, not every tile |
| `PhotosSurface.motionClipData(turn)` | `:tenshu-sdk-ios` | One `NSData` copy per turn rather than a bridge call per byte |
| ⭐ The clip plays from MEMORY — a custom-scheme `AVURLAsset` whose `MemoryClipLoader` serves the bytes | `FeedMotion.swift` | ⚠⚠ Never a file: a clip is a decrypted derivative of the user's video, and a temporary file outlives a crash or kill with nothing left to delete it — plaintext accumulating against the threat model's local-minimisation rule, I8. Reproduced before the fix: four `motion-*.mp4` orphans in the app's `tmp`, one per `simctl terminate` mid-turn; none after |
| `MOTION_PLAYBACK_SUPPORTED = true` | `PhotosLocalRuntime` | ⚠⚠ Moves WITH the player (P1): left `false`, iOS never declares `MOTION` wanted, so an unminted video's clip is never backfilled by the devices that can mint it, and its tile stays a poster. ⚠ Declaring asks PEERS to mint — iOS itself cannot until M6 (PIO-6) |
| "Play videos in the grid" keeps its default — on | — | ⚠ A DEVICE setting (`PhotosPreferences.PlayVideosInGrid`); iOS has no Settings until PIO-5, which owns the switch |
| `-photosMotion`: every seventh fixture photograph a video with a 3 s test clip (a bar sweeping), encoded by `AVAssetWriter` at launch; the probe logs `motion visible=` and `motion turn=` | `PhotosIos-Dev`, `PhotosScreenshotFixture` | No ffmpeg, no committed binary; the plain fixture is unchanged |

**Verified (simulator, iPhone 16 Pro):**

| Check | Evidence |
|---|---|
| one video on screen hands over to itself, a turn per ~3 s clip | `visible=#5`, turns #5 ×6 in ~18 s; two frames show the bar at different positions |
| the in-memory clip PLAYS — turns end at the clip's end, not the 3 s failure hold | `motion finished why=end` on every turn (the probe logs end / failed / ceiling) |
| two videos rotate in document order and wrap | `visible=#68,#75` → #68, #75, #68, #75 |
| the set widens with the grid (Month) | `visible=` photos 68, 75, 82, 89, 96, 103, 110 |
| a scrub empties the set; release restores the new screen's | `visible=` on grab → photos 901, 908 visible, rotation resumes |
| background pauses, foreground resumes | 3 turns in 9 s; 0 more in 9 s in Settings; 3 more in 8 s back |
| behind the viewer it stops | 0 turns in 9 s with photo 277 open |

**⚠ Not verified / open:**
- **Real `MOTION` clips on a real library** — H2. The test clip is 128 px, 3 s, H.264 without audio,
  the class's own shape (`photos-video-support.md` D3), but not a minted one.
- The failure and ceiling paths (a clip that will not open, a player that goes silent) — written to
  the contract, not exercised: the fixture's clip always plays.
- iPad: the same code, not run.

### 8.11 The return hang — Back from the viewer froze the app (2026-09-29)

⚠⚠ **Found on the PIO-3 simulator pass, and on master since PIO-2 slice 1.** Going Back from the viewer
to the Library put the app in a layout loop that never ended: the grid's safe-area top flipped
52 pt ⇄ 0 and its content offset −52 ⇄ 0, every flip firing `scrollViewDidScroll`, at 100 % CPU with
the main thread never returning to its run loop (> 70 s observed). 52 pt is the large title
"Library": the navigation bar tracks the collection view to collapse the title, SwiftUI resizes the
grid for the new bar, and UIKit's automatic content-inset adjustment moved the offset again — each
side answering the other. None of our state took part (sticky label and top line constant through
the loop); our viewport reports only made each iteration expensive.

**Fix:** `contentInsetAdjustmentBehavior = .never` on the grid (`FeedGridView`). SwiftUI already lays
the grid out below the bars, so UIKit's adjustment only double-counted the title.
⚠ **Superseded by §8.21 (2026-10-06):** the grid now lies UNDER the bars and UIKit insets it
(`.always`). The loop needed the title to resize the grid; a grid that fills the window cannot be
resized by it. `-photosBackDemo` is still the check.

**Why nothing caught it:** every PIO-2 demo ended IN the viewer or never opened it — none came back.
`-photosBackDemo` (open #3, then Back) is that missing journey; its probe line `back: returned, main
thread free` prints only if the main thread is free after the return.

| Check | master | fixed |
|---|---|---|
| CPU 9 s after Back | 100 % | 0 % |
| `back: returned, main thread free` | never | ✅ |
| `-photosDemo 120`: photo 277 on top, sticky "31 December 2024", re-anchor keeps photo 274 (Month) | — | ✅ unchanged |
| `-photosScrub 0.6 1.0`: rest 0.598 / 1.0, bubbles, release | — | ✅ unchanged |

⚠ Not verified: a real finger's Back (edge swipe) on a device — the same pop, but H2 should include
it.

### 8.12 PIO-3 slice 1 — the viewer's actions (2026-09-29)

Android's viewer bar (`photos-app.md` S3, PH-9) on iOS: a tap toggles the chrome, a star, and a
menu with Add to Album and Move to Trash. The size-driven hero rung the PIO-3 row lists was already
built in PIO-1 (`PhotosSurface.heroPreview` → `heroPreviewRequestPx`), so it is not in this slice.

| Change | Where | Why |
|---|---|---|
| `PhotoViewerViewModel.trash` / `addToAlbum`, each reporting whether it HAPPENED; `PhotoViewerUiState.writing` while any viewer write runs | `:photos-shared-logic`, `ViewerActionsTest` (7), `ViewerFavouriteAndPlaceTest` (10) | ⚠⚠ The viewer closes only on `true` — `TrashViewModel`'s `/Trash` incident. Acts on the photograph ON SCREEN, never the seed. ⚠ Single-flight (multi-select's lesson, §8.9), checked by mutation. Android reaches these through `TrashViewModel` and `CollectionsViewModel` — whole-screen view models the iOS viewer need not build — and its add-to-album drops the result |
| `TenshuPagingPresenter.refresh` → `PhotosFeedBinding.refresh` | `:core-paging` (+1 test, checked by mutation), `:photos-shared-logic` | After a trash the file is in `/Trash`, which the feed excludes; the grid is refreshed before it is seen again — Android's `feedItems.refresh()` in the same place |
| `PhotoPlace.displayName` — the city, else the label | `:photos-shared-logic` (+1 test); Compose's viewer now reads it | The subtitle's place was a rule written only in Compose |
| A tap on the photograph toggles the chrome; the navigation bar and the status bar hide TOGETHER | `PhotoViewerScreen` | §3.1b: one flag for both, or the layout describes bars that are not there |
| ♥ disabled — not hollow — for a photograph with no content id; Add to Album behind the same gate | same | PH-9: the flag ops refuse such a file, and a star that fails on tap is the quiet lie |
| A refusal is SAID ("This photo couldn't be moved to the trash.") and the viewer stays | same | Never swallowed |
| The title is drawn white | same | ⚠ Found here: the viewer's bar has no background, so light mode drew the date black on black — only the caption showed, since PIO-1. `toolbarColorScheme(.dark)` does not reach a bar without a background |
| `-photosViewerDemo` (`ViewerProbe`), run from an UNSTRUCTURED task | `PhotosIos-Dev` | ⚠ `simctl` cannot tap. And a demo that pushes the viewer from the Library's `.task` is cancelled by that push — every `Task.sleep` then returns at once and the whole script ran in 0.2 s, which first read as a one-step state lag |

**Verified (simulator, iPhone 16 Pro, fixture):**

| Step | Result |
|---|---|
| open #3 | ♡, chrome on |
| star | ♥ (`favourite=true`) |
| tap | chrome and status bar hidden |
| trash #3 (the fixture refuses every fourth) | "This photo couldn't be moved to the trash.", viewer stays on #3 |
| move to #4, trash | viewer closes, Library back, CPU 0 % (after §8.11) |

The single-flight guard dropped a second write WITHOUT calling back, so a choice
made while one ran — another album, or Trash after Add to Album — vanished without a word. The view
model now exposes `writing`, and the viewer disables its menu while it is `true` (`ViewerActionsTest`,
7; the new test and the single-flight one both fail when `writing` is never set).

The star was enabled while the favourite read was still in flight
(`favourite == null`). A tap treated the unknown as "not favourited" and wrote `true`; the read then
landed `false` over it — the star and the store disagreed, the state PH-9's revert exists to prevent.
`toggleFavourite` now ignores a tap while the flag is unknown, and BOTH shells keep the star disabled
until it is known (the fix is in the shared view model, so Compose had it too). Pinned by
`ViewerFavouriteAndPlaceTest.aTapBeforeTheFlagIsKnownIsNotTaken_soTheStarNeverDisagreesWithTheStore`,
which fails without the guard ("the star says what the store holds — expected true, was false").

**Checked and not a defect here:** the concern was that the status bar keeps DARK
indicators over the viewer's black in light mode. On the simulator in light appearance the indicators
are drawn light on black and legible with the chrome shown (a full-resolution crop of the top 180 px);
no override is needed. ⚠ Re-check on the device in H2 — status-bar styling is where a simulator and a
device can differ.

**Three more, all fixed:**
1. ⚠⚠ **The view model followed the INDEX, not the identity.** When a deletion slid the next photograph
   into the same position, `current` did not change, `onPhotoShown` never ran, and the star and menu
   acted on the deleted photograph under its neighbour's picture. `shownPath` now tells the view model
   whenever the identity on screen changes. ⚠ Not exercised on the simulator: the fixture's mock never
   removes a photograph from the feed.
2. ⚠⚠ **Two quick star taps raced**, ending in whichever write finished last. `writing` moved ONTO
   `PhotoViewerUiState` (one field both shells already observe) and covers the star too: a second write
   is not taken while one runs, and both shells disable the star and the menu on it. Pinned by
   `ViewerFavouriteAndPlaceTest.aSecondTapWhileTheFirstIsWritingIsNotTaken_soTheStoreEndsWhereTheStarSays`,
   which fails without the guard.
3. ⚠ **Trash was offered for a file with no content id**, which the op always refuses. It is now behind
   Add to Album's gate, in both shells; an iOS menu with nothing in it is disabled.

**Two more, both fixed:**
1. ⚠⚠ **The album pick filed the photograph on screen AT PICK TIME**, and a live refresh can move the
   viewer to a neighbour while the sheet is open. `addToAlbum` now takes the photograph, and the sheet
   captures the one whose menu opened it (`ViewerActionsTest`, 8). Compose's viewer already did
   (`pendingAlbumFor`).
2. ⚠⚠ **A successful favourite write changed nothing**, so A → B → A while it ran — which clears the star
   and reads the flag again, possibly the OLD value — left a hollow star over a stored favourite. The
   write's answer now stands on success as on refusal, and a read of that photograph landing mid-write
   is ignored. `ViewerFavouriteAndPlaceTest.aWriteThatSucceededStands_whenASwipeBackReadTheOldFlag`
   (11) fails with the refusal-only repair.

**⚠ Not verified:** a real tap and the menu opened by a finger; Add to Album from the viewer (the
sheet is slice 4's, reused); a trashed photograph leaving the grid on a real library.

### 8.13 PIO-3 slice 2 — zoom and drag to dismiss (2026-09-30)

Compose's gesture priority (PH-15 / D4), on iOS: *while zoomed in, the drag belongs to the pan*.

| Gesture | Owner |
|---|---|
| two fingers | the zoom, always — a pinch is never a page swipe |
| one finger, magnified | the pan — the pager AND the dismiss are unreachable |
| one finger down, at fit-scale | drag to dismiss; the photograph follows and shrinks |
| one finger sideways, at fit-scale | the pager |

| Change | Where | Why |
|---|---|---|
| ⭐ `ViewerGestures` — `MAX_ZOOM` 5, `DISMISS_FRACTION` 0.18, `DISMISS_TRAVEL` 0.5, `DISMISS_SHRINK` 0.25; `clampZoom`, `dismissScale`, `dismisses` | `:photos-shared-logic`, `ViewerGesturesTest` (4) | Compose held these as private constants; two copies of "how far is far enough to close" drift into two apps that close at different distances. Compose's viewer now reads them (its `ViewerZoomGestureTest`, 7, green on them) |
| `ZoomableImage` — a `UIScrollView` per page | `photos-ios/Sources/ZoomableImage.swift` | Native pinch and pan, `maximumZoomScale` from `ViewerGestures`. ⚠⚠ The pager rule falls out of UIKit's nesting: magnified, the view always bounces horizontally and keeps the drag; at fit-scale it has nothing to scroll and lets a sideways drag through to the pager |
| A pan recogniser that begins only at FIT-SCALE and only for a mostly DOWNWARD drag | same | Decided once when the gesture begins — Compose latches its owner the same way (an owner that changes under the finger is what D4 exists against). The photograph follows the finger and shrinks by `dismissScale`; on release `dismisses` closes, else it springs back |
| No double-tap zoom | — | Parity: Compose has none, and a double-tap would delay the single tap that toggles the chrome |
| ⭐ `PhotosIosUITests` + the `PhotosIos-UITests` scheme | `photos-ios/UITests/`, `project.yml` | `simctl` can neither pinch nor drag; XCUITest can. Drive's precedent (`DriveIos-UITests`): the DEV_HARNESS app on `-photosFixture`, screenshots `.deleteOnSuccess` |

**Verified — `ViewerGestureUITests`, 7 of 7 (the last four from Codex, below), real gestures on the iPhone 16 Pro simulator; each checked
by mutation:**

| Test | Asserts | Mutant that fails it |
|---|---|---|
| `testMagnifiedTheSwipePansInsteadOfPaging` | pinch #3 → swipe → still #3 (22:00); pinch out → swipe → #4 (21:35) | zoom capped at 1× — "still #3 — the swipe panned the photograph" |
| `testADragDownPastTheLineCloses_andAShortOneDoesNot` | a 5 % drag keeps the viewer; a 45 % drag closes it — back on the grid | — (the arithmetic is `ViewerGesturesTest`'s) |
| `testMagnifiedADragDownPansInsteadOfClosing` | magnified, a long drag down pans, the viewer stays | the fit-scale guard removed — "the viewer is still open" |

**Two more, both fixed:**
1. ⚠⚠ **The zoom was reset by every new bitmap**, so the sharper rung landing after a pull snapped a
   pinch back to fit-scale mid-gesture. The zoom now belongs to the photograph's IDENTITY (its path, as
   Compose keys it): another photograph on the page starts at fit-scale, a sharper rung of the same one
   only swaps the pixels. `testTheZoomSurvivesTheSharperRungLanding` pins it, the fixture now sending a
   second rung 6 s after the first. ⚠ At 2.5 s the test PASSED against the bug — the upgrade landed
   before XCUITest had finished pinching — so the delay is part of the test, not incidental.
2. ⚠ **The dismiss recogniser accepted two fingers**, so a pinch whose centre drifted downward at
   fit-scale could start it. It is limited to one finger. Not tested: XCUITest cannot make a drifting
   pinch.

⚠⚠ **The zoomed view was the whole page**, transparent letterbox included, so a
landscape photograph zoomed on a portrait phone could be panned into its own empty margins — partly
or entirely off screen. The zoom view is now the photograph's aspect-fitted rectangle, centred by
insets. `testZoomedThePhotographCannotBePannedIntoItsLetterbox` pans a zoomed #3 (every fixture
picture is 4:3 landscape, letterboxed on the phone) to its top edge and samples the screenshot there:
photograph, not the black ground. Against the page-sized view it fails.

⚠ **A rotation or Split View resize WHILE magnified** kept the base frame fitted
for the old page — the photograph the wrong size, with wrong pan limits, until the user returned to 1×.
The page now refits on any size change, keeping the relative zoom and the point the user was looking
at. `testARotationWhileMagnifiedRefitsThePhotograph` rotates a magnified #3 to landscape and reads the
page's reported base size: it must be the NEW page's aspect-fit. Without the refit it read
`zoom=2.64 base=402x301` against an expected width of 449.

⚠⚠ **The refit kept the zoom but not the focal point** — it derived the focus
with the new page's half-size against the old layout. Fixing the arithmetic was NOT enough: by the time
`layoutSubviews` sees the new size, UIKit has already moved `contentOffset` for it (instrumented: 313
where the focus needed ~605), so no calculation there can recover the old focus. The focus is now
RECORDED while the page is still the size its layout was fitted for, and the refit reads that.
`testARotationWhileMagnifiedKeepsTheFocalPoint` pans off centre, rotates, and asserts the old focus —
clamped to what the new page can centre without showing past the photograph's edge (x 0.760 → 0.684
is exactly that clamp; y 0.484 kept). The offset-derived version fails it (x 0.484).

**⚠ Not verified:** a real finger on a device (the H2 pass) — XCUITest synthesises touches through the
same recognisers, but not the feel; the upward drag into About is slice 3.

Run: `xcodebuild test -project photos-ios/PhotosIos.xcodeproj -scheme PhotosIos-UITests -destination
'id=<simulator>'` (≈ 45 s for the three once built).

### 8.14 PIO-3 slice 3 — the About sheet (2026-09-30)

Compose's About panel (PH-7, PH-9 – PH-11) on iOS: the capture time, the file, its albums, the named
place, the details and the camera settings.

| Change | Where | Why |
|---|---|---|
| ⭐ `AboutSheetContent.of(state, formatCapturedAt)` — title, file name, album lines, place, `AboutRow`s, and `AboutSettings` (`Reading` / `Silent` / `NotOnDevice` / `Rows`) | `:photos-shared-logic`, `AboutSheetContentTest` (6) | ⚠⚠ Which rows exist, what they are called, how an album line reads ("1 item") and what the settings section says all lived INSIDE Compose's `AboutSheet`; the iOS sheet would have held a second copy. Compose now draws from it — `AboutSheetTest` (10), `PhotoViewerExifRowsTest` (5) and `PhotoViewerInfoSheetTest` (5) green unchanged, so the move changed nothing on Android |
| `PhotosSurface.aboutSheet(state)` | `:tenshu-sdk-ios` | Builds it with `PhotoDates::capturedAt` — the formatter Compose passes — so the two apps title one photograph the same way |
| `AboutSheetView`, a sheet with medium and large detents | `photos-ios/Sources/AboutSheetView.swift` | Driven by the view model's `infoOpen`, never around it: opening is what reads the details, the albums and — only for an open sheet — the file's EXIF |
| Opened by **About** in the menu, and by an upward drag at fit-scale | `PhotoViewerScreen`, `ZoomableImage` | Compose's "upward scrolls into About" (§11). The page's vertical recogniser now takes both directions — down dismisses, up past 60 pt opens About — still only at fit-scale, so magnified the drag stays the pan's |
| Menu enabled for any photograph; Add to Album and Trash still behind the content-id gate | `PhotoViewerScreen` | About needs no content id; slice 1's empty-menu rule no longer applies |

⚠ Not built here, and deliberately: Compose's About is a panel the photograph SCROLLS into (the page is
one tall column); iOS presents a sheet over the photograph instead — the platform's idiom for the same
content. The pager lock the view model imposes while `infoOpen` is honoured by the sheet's modality.

**Verified — `ViewerGestureUITests`, 10 of 10; the new three each checked by mutation:**

| Test | Asserts | Mutant that fails it |
|---|---|---|
| `testASwipeUpOpensAbout` | the sheet, describing #3 ("IMG_10003.jpg", "Details"), and the settings section's one-line fact ("Camera settings need the original…" — the fixture holds no original) | `onSwipeUp` never called |
| `testTheMenuOpensAbout` | the same, from More → About | — |
| `testMagnifiedASwipeUpPansInsteadOfOpeningAbout` | magnified, no sheet | the fit-scale guard removed |

Two albums may share a name and a count, and the sheet listed its album lines keyed
by their WORDS — duplicate identities for SwiftUI's `ForEach`, which can drop or misassociate rows. The
shared content now carries each album's id (`AboutAlbum(albumId, line)`) and the list is keyed on it
(`AboutSheetContentTest.twoAlbumsWithTheSameNameAndCountStayTwoRows`).

**⚠ Not verified:** a real library's EXIF rows (the fixture has no originals, so only the
`NotOnDevice` state is exercised — `AboutSheetContentTest` covers `Rows`), albums and a named place on a
device; the feel of the upward drag. H2.

### 8.15 PIO-4 slice 1 — the tabs and the Collections tab (2026-10-01)

**PIO-4 in four slices:** (1) the tabs + Collections + a collection's grid; (2) album CRUD — rename,
delete, remove a photograph (§8.16); (3) Trash and Duplicates, from Collections › Utilities (§8.17,
§8.18); (4) the Search tab (§8.19). All four built.
⚠ Each surface arrives WITH its destination: a tile or a tab with nothing behind it is worse than none
(`control-cross-app-open.md` §5g.2) — so Search's round button and the Duplicates tile wait for theirs.

| Change | Where | Why |
|---|---|---|
| ⭐ `CollectionsLayout.of(state, groupingRunsHere)` — `Loading` / `Failed` / `Content` (refresh-failure line, tiles, People row, the always-present Albums row, Places/Things/Years rows, empty copy) | `:photos-shared-logic`, `CollectionsLayoutTest` (8) | ⚠⚠ Every rule of the tab — which sections show, which may show while EMPTY (People, with its why-line; Albums, holding "New album"), when *View all* is offered, every sentence — lived inside Compose's `CollectionsScreen`. Compose now draws from it: `CollectionsScreensTest` (24), `CollectionRowsTest` (9), `PeopleSectionTest` (12) green unchanged |
| `peopleStatusLine` and `CollectionSection` moved to `:photos-shared-logic` | same package, so no import changed | ⚠ `CollectionSection` lost `@Serializable` — the shared module has no serialization plugin, and Compose's `Destination` still serialises it (the plugin generates an enum's serializer where a `@Serializable` class references it; Android compiles) |
| `CollectionsUiState.smartAlbumFor(id)`, `AlbumDetailUiState.statusText`, `collectionGridColumns` | `:photos-shared-logic` | The id-or-alias route resolution (IN-6 F3b), the album screen's error/empty sentence and the grid's column rule were Compose's; each is now one copy |
| `PhotosTabs` — Library · Collections through the shared `AdaptiveAppShell` | `PhotosIosApp.swift` | Q1 (§7), PIO-D3: tab bar on phones, sidebar on wide iPads. Search joins in slice 4 |
| `CollectionsScreen`, `AlbumGridScreen`, `SmartGridScreen`, `SectionScreen` | `CollectionsScreen.swift`, `CollectionGrids.swift` | The tab, an album's or Favourites' grid, a derived section's grid (resolved by `smartAlbumFor`, keeping the last resolution as Compose does), and *View all* |
| ⭐ `ViewerSource` — `FeedViewerSource` / `FixedViewerSource` | `ViewerSource.swift`; `PhotoViewerScreen` | A photograph tapped in a collection opens the viewer over THAT collection — whole, nothing pages (Compose's `PhotoCollection.Fixed`) — with every viewer feature unchanged. A trash there reloads the collection |
| "New album" — a name prompt → `createAlbum` | `CollectionsScreen` | The Albums row's one action; rename/delete are slice 2 |

⚠ Deferred, and recorded so they are not mistaken for done: ~~the **Duplicates tile**~~ (shown since
slice 3b, with its screen — §8.18); ~~a person's **header, naming and "Photos with…"**, and the **face crop** on a person's card
(Compose crops to `FaceCover.region`; iOS centres the photograph)~~ — ✅ **built in F7**
([`ios-face-grouping.md`](ios-face-grouping.md) §17): the card and the header cut the shared
`faceCropSquare`, the page names through `CollectionsViewModel.nameGroup`, and the "With…" chip narrows
to photographs of both — all behind both face flags, which stay off until F8. ⚠⚠ With the flags off
none of it is drawn: People cards still show where names synced from another device give the library
people, round and centred as before F7, and a person's page is the plain grid with no naming (it could
never save there). With the flags on, a synced name keeps its whole photograph until this phone has a
face of that person to cut.

**Verified — `CollectionsUITests`, 4 of 4, on the fixture (two years, two empty fixed albums):** the tab
shows Albums, Years and Favourites and no Duplicates tile; a year opens its photographs and one of them
opens the viewer over them, and Back returns; an empty album and Favourites say the shared sentences;
"New album" creates an album that appears in the row. `ViewerGestureUITests` (10) still green over the
refactored feed source. A screenshot of the tab (`-photosStartTab collections`): Favourites, Albums with
New album / Holiday 12 / Family 40, Years 2025 (274) / 2024 (1 226).

**Two more, both fixed:**
1. ⚠ **The tab bar rode over the viewer** — every viewer pushed inside a tab inherited it, over the
   black photograph even with the chrome hidden. The viewer hides `.tabBar` — on its OUTER container,
   so the loading, error and missing states are covered too (the first fix sat on the pager alone;
   Codex's second pass). The year-to-viewer UI test asserts it, and fails with the line removed.
2. ⚠⚠ **A refused "New album" was silent** — `createAlbum` dropped the repository's `null`, so the
   dialog closed and the album never appeared. It reports whether it happened (`onDone`, default a
   no-op so Compose is unchanged; `AlbumViewModelsTest.aRefusedAlbumIsReported_andAMadeOneToo`), and
   iOS says "Couldn't Create the Album".
3. ⚠ **The last-resolved person was kept by id only** (third pass): a person's id can stay put while
   their name and photographs change, so a later alias loss fell back to the FIRST version. The grid now
   keeps the whole latest resolution (`onChange(of: resolved)`, Kotlin's data-class `equals`).
4. ⚠⚠ **The Collections state stopped updating under a pushed grid** (fourth pass): the model observed
   from the tab's `.task`, which SwiftUI cancels when a route is pushed — the same lifecycle the viewer
   demo hit (§8.12). A refresh after a trash in a year's viewer then updated Kotlin's state while the
   year's grid kept the trashed photograph. `CollectionsModel` now observes for its whole life.
   ⚠ Not exercised: the fixture's trash never removes a photograph from the feed (§8.9).
5. ⚠⚠ **The tabs bypassed the shared shell** (fifth pass): a plain `TabView` never becomes a sidebar on a
   wide iPad. PIO-D3 says `AdaptiveAppShell` (`ios-shell`), which Calendar and the others use: a tab bar
   on phones and narrow iPads, a sidebar from 1100 pt, with each tab's path surviving rotation. Photos
   uses it now. ⚠ It builds on the classic `tabItem` API, so slice 4's detached Search button needs the
   shell to learn `role: .search`. ⚠ The sidebar itself is the shell's, verified there, not re-run here.
6. ⚠⚠ **A refresh asked for while one ran was DROPPED** (fifth pass): an album made while a walk was in
   flight was reported made, then overwritten by the older walk's snapshot. `CollectionsViewModel.refresh`
   now queues one rerun instead (`AlbumViewModelsTest.anAlbumMadeWhileARefreshRunsIsShown_…`, which fails
   with the request dropped; ⚠ the test holds the walk with `runCurrent`, because `advanceUntilIdle`
   skips virtual time past the refresh's own timeout and tests that instead).
7. ⚠ **The first appearance refreshed twice** (sixth pass): the view model's initialiser refreshes, and
   with (6) the tab's `onAppear` refresh was QUEUED behind it — two full library walks on every first
   open. Only a later return refreshes now.
8. ⚠ **People's *View all* took the raw order** (sixth pass), largest cluster first, so unnamed clusters
   buried the names; Compose sorts named-first. `CollectionsUiState.peopleNamedFirst` is that rule, and
   both shells use it (`CollectionsLayoutTest.namedPeopleComeFirstInViewAll_…`).
9. ⚠⚠ **Album reads raced** (seventh pass): `AlbumDetailViewModel.refresh` had no guard, so the
   initialiser's read, the grid's first `onAppear` and a return from the viewer overlapped, and the one
   that finished LAST won — an older read could put a removed or trashed photograph back (Android too).
   It is single-flight with one queued rerun now, like the Collections one;
   `AlbumViewModelsTest.anAlbumReadStartedBeforeARemovalNeverPutsThePhotographBack` releases the NEWER
   read first and fails without the guard (⚠ with one shared gate it passed against the bug — the older
   read finished first anyway). iOS also stops asking twice: no first-appearance refresh, no refresh
   from the viewer callback (the return re-reads).
10. ⚠ **Covers decoded on the main actor** (eighth pass): a populated tab resumes many cover tasks at
    once, each running a synchronous ImageIO decode there. They decode in a detached task now, as the
    viewer's hero does.
11. ⚠⚠ **(9)'s latch could stick** (ninth pass): a lost Android reply never returns, so the `finally`
    never ran and every later refresh only queued — the album sat on its spinner until rebuilt. The
    wait is bounded like the Collections one (`REFRESH_TIMEOUT_MS`), the hung read is cancelled and
    abandoned, and a read returns state rather than writing it, so a late answer changes nothing
    (`AlbumViewModelsTest.aReadThatNeverAnswersTimesOut_andTheNextRefreshStillRuns`).

**⚠ Not verified:** a real library's Places and People on a device; the iOS 26 tab-bar look on the
simulator (the runs used iOS 18.5) — the phone, H2.


### 8.16 PIO-4 slice 2 — an album's edits: rename, delete, remove a photograph (2026-10-01)

| Change | Where | Why |
|---|---|---|
| ⭐ `AlbumDetailViewModel.rename(name, onDone)` / `delete(onDone)` / `remove(photo, onDone)`, `AlbumDetailUiState.writing` | `:photos-shared-logic`, `AlbumEditsTest` (6) | ⚠⚠ Each reports whether it HAPPENED, because the shell acts on the answer: the screen closes only on a delete that happened (the viewer's trash rule, PIO-3), and the title changes only on a rename that did — an optimistic title left after a refusal names an album that does not exist. ⚠ Single-flight (the viewer's `write`, PIO-3): a rename and a delete in flight together end in whichever lands last. ⚠ A blank name is refused here, not only by the disabled Save. ⚠ Favourites: every edit absent, not refused |
| `AlbumEdit` — `Rename` / `Delete` / `Remove`, each with its refusal's title and message; `ALBUM_DELETE_CONFIRMATION` | same file | One copy of every sentence. ⚠⚠ Each says what did NOT happen to the photographs — `DeleteAlbum` / `RemoveFromAlbum` cannot reach `:file-service`, and that is the fear behind every one of these |
| The album's ⋯ menu — *Rename Album* (a name prompt), *Delete Album* (a confirmation saying the photographs stay); a tile's context menu — *Remove from Album* | `CollectionGrids.swift` | iOS Photos' own placement. Removal is at once, as Android's long-press is: it costs nothing a re-add cannot undo |
| *View all* re-reads on a return | `SectionScreen` | An album renamed or deleted from that page must not still be on it — it showed the Collections state as it was when opened |
| The fixture's albums differ on purpose | `PhotosScreenshotFixture` | **"Family"** is real in the mock (six photographs) and every edit works on it; **"Holiday"** is a fixed row the mock does not know, so it reads empty and REFUSES every edit — the refusal alerts are shown by the same fixture as the successes |

⚠ **Android has none of rename or delete.** `CollectionsViewModel.renameAlbum` / `deleteAlbum` exist,
but no Compose screen calls them — only long-press *remove* is wired (`AlbumDetailScreen.onRemove`).
iOS is ahead here, by design rather than drift: the decisions are in the shared view model, so
Android's menu is rendering only when it comes. Compose still compiles against the widened `remove`
(its `onDone` defaults).

**Further findings:**
1. ⚠⚠ **A removed tile stayed until the re-read landed** — the edit unlocked when the write answered,
   so on a slow read the same tile could be removed twice, and the second write (nothing left to
   remove) reported a refusal of a removal that worked. The photograph now leaves the grid the moment
   its removal succeeds, and stays off until a read that STARTED after it lands: one already in flight
   snapshotted the album with it (`AlbumEditsTest.aRemovedPhotographLeavesTheGridAtOnce_…` — both
   halves mutation-checked).
2. ⚠⚠ **(1) crashed every album on iOS** (caught by `AlbumEditsUITests` before push): the new fields sat
   below `init { refresh() }`, and on K/N `viewModelScope` is `Main.immediate`, so the first read ran
   inside the constructor and dereferenced a field not yet initialised (`SIGSEGV` in
   `MutableMap.entries`). The JVM tests' `StandardTestDispatcher` defers that read and passed.
   Fields now precede `init`, and `AlbumEditsTest.theFirstReadMayRunInsideTheConstructor` builds the
   view model on an eager Main — the phone's shape — and fails with the order reverted.
3. ⚠ **Android offered a removal the view model would drop** — Compose's long-press stayed live while
   an edit ran, and the single-flight guard ignored it: a removal that silently never happened. The
   long-press now waits on `writing`, as the iOS menu item does
   (`CollectionsScreensTest.aLongPressRemovesOnlyWhileNoEditRuns`, mutation-checked).
4. ⚠⚠ **An edit whose reply was lost held the lock for good** — `refresh`'s lesson again, for the new
   lock: every later edit disabled until the screen was rebuilt. The wait is bounded
   (`REFRESH_TIMEOUT_MS`); the hung write is abandoned and reported as not having happened — ⚠ which
   it may have, so the album is re-read and the grid shows the truth rather than the guess
   (`AlbumEditsTest.anEditThatNeverAnswersTimesOut_andTheNextOneRuns`, mutation-checked).
5. ⚠⚠ **A read published a copy of the whole state** — answered before a rename and published after
   it, it put the old title back (and could clear `writing` under an edit that had just started). A
   read now writes only its own fields — photographs, loading, error — into the LATEST state
   (`AlbumEditsTest.aReadNeverPutsBackATitleARenameSet` — fails against the previous view model).
6. ⚠⚠ **(4) called every lost reply a refusal** — but the write may have committed. A rename then kept
   its old title, a deleted album stayed on screen, and the retried delete was refused. After a lost
   reply each edit now asks the store whether it is in effect — rename and delete through `albums()`
   (⚠ an unreadable list proves nothing, so it counts as no), removal through the album's photographs
   — and only a yes counts as done (`AlbumEditsTest.anEditWhoseReplyIsLostButWhichLandedCountsAsDone`).
7. ⚠ **Leaving before an edit landed left the card stale** — Back stays enabled while an edit runs, so
   the Collections tab's return refresh could read BEFORE the write, keeping the old name, count or
   album. The album screen now tells its parent when an edit happened (`AlbumGridScreen.onEdited` →
   `CollectionsViewModel.refresh`); the answer still arrives after the screen has gone, because the
   view model is not cleared with it. Proved with a new fixture flag, `-photosSlowEdits` (album edits
   land 6 s late): `AlbumEditsUITests.testLeavingBeforeARenameLandsStillUpdatesTheCard` asserts the
   card still read "Family" on return — the race really happened — and "Kin" after; it fails with the
   wiring removed.
8. ⚠⚠ **(6) cancelled a write that cannot be cancelled** — a Binder transaction runs to the end, so a
   slow write committed after the timeout's single check, and the screen, told "refused", never
   learned it had happened. The write is never cancelled now. An edit ends `Done`, `Refused` or
   ⭐ **`Unconfirmed`** (`AlbumEditOutcome`) — no answer in time and not in the store yet — which is
   never worded as a refusal ("Still Waiting for Tenshu Control" — see note 16 for its sentence).
   When the write does answer, the view model publishes what the STORE then holds (the store's name,
   not the one this edit meant — the lock released, so another edit may have run). So both effects
   that must survive a late answer live where a late answer can reach them: `deleted` is STATE (the
   screen closes on it), and the parent refresh is the view model's `onEditLanded` hook — ⚠ not state
   the screen observes, because by then the user may have left it (`AlbumEditsTest.aSlowEditThatLands…`;
   mutation-checked: re-adding the cancel, and calling unconfirmed a refusal, each fail it).
9. ⚠⚠ **(8) released the lock at the first timeout, and watched for ever** — two findings pulling
   opposite ways. P1: a write that has not answered can still commit, so a conflicting edit run beside
   it reorders them — a late rename (`SaveAlbum` is an upsert) re-creates an album a later delete
   removed, its entries already cascaded away. P2: a lost reply never returns, and a watcher per retry
   held the view model for good. Both now go through one shared `boundedWrite`
   (`ui/screens/common/BoundedWrite.kt`, `WriteOutcome` replacing `AlbumEditOutcome`): after the first
   bound the edit reports *Unconfirmed* but KEEPS the lock, waits one more bound for the real answer,
   then abandons it and releases — so edits stay ordered while one can still land, and at most one
   watcher exists, always ending. (`noEditRunsBesideOneStillSettling`,
   `anEditThatNeverAnswersIsAbandoned_andTheNextOneRuns` — mutation-checked: releasing at
   Unconfirmed, dropping the abandonment, and an unbounded settle each fail one.)
   ⚠ **Residual, recorded rather than hidden:** a write that commits after BOTH bounds (~4 min) can
   still reorder against a later edit. Only server-side ordering — a conditional or versioned
   `SaveAlbum` / `DeleteAlbum` — closes that; it is not this slice's to build.
10. ⚠⚠ **A check true BEFORE the write confirmed it** — a rename to the name the album already has
    "landed" at once under (9), released the lock while its upsert could still commit, and a following
    delete could then be undone by it. `boundedWrite` now asks the check FIRST: already true → Done
    with nothing written; unanswered → the check is never trusted afterwards; only a NO before the
    write lets a YES after it confirm one. iOS also disables Save on the unchanged name
    (`aRenameToTheNameItHasWritesNothing_soNothingCanLandLate`; mutation-checked). ⚠ The cost: one
    read before every edit — the price of a confirmation that means something.
11. ⚠ **A rename reset the album's cover** — `SaveAlbum` REPLACES the row, and `renameAlbum` passed no
    cover, so the default `""` was written and the card fell back to the first photograph. It reads
    the row's `coverContentId` and carries it; ⚠ a row that cannot be read is a refusal, never a rename
    with the cover guessed. Pinned on the real stack, not the mock — the mock's rename is a `copy`
    that cannot lose a field (`RealRepositoryReadTest.aRenameKeepsTheChosenCover`, chosen cover the
    SECOND photograph so the fallback cannot pass for it; mutation-checked).
12. ⚠⚠ **The checks' timeouts did not bound them** — `boundedWrite` timed `landed()` directly, and a
    Binder read cannot be interrupted, so `withTimeoutOrNull` waited for ever on the cancellation, and
    the lock with it. Each check now runs in its OWN job and only the wait is timed (`boundedRead`),
    `CollectionsViewModel.refresh`'s rule; abandoned, it is unknown. ⚠ A caller must therefore not wrap
    `boundedWrite` in `coroutineScope { }`, which would wait for the abandoned read
    (`aReconciliationReadThatNeverReturnsCannotHoldTheLock` — its read ignores cancellation, as a Binder
    call does; mutation-checked).
13. ⚠⚠ **No value confirms a timed-out write** — the third P1 on one idea. After (10) a rename was still
    confirmed by "the store has this name" once its reply was late, and a PEER writing the same name
    meanwhile made that true: the lock released while our upsert could still commit. A value cannot
    carry operation identity, so the post-timeout check is gone. Only the write's own answer confirms
    it; without one it ends Unconfirmed and the screen publishes what the store holds (`reconcile`:
    the store's name, `deleted` if the album is gone, a re-read) WITHOUT claiming this write put it
    there. The pre-check stays — "nothing to do" is decided before anything is sent
    (`aPeersIdenticalRenameNeverConfirmsOurs`, `anEditWhoseReplyIsLostShowsWhatTheStoreHolds_withoutClaimingIt`;
    mutation-checked: re-adding the value check, and skipping `reconcile`, each fail them).
14. ⚠⚠ **Two more ways a Binder call outlived its bound** — (a) the late `reconcile` read ran unbounded
    inside the lock, so its lost reply kept `writing`; the lock is now released the moment the WRITE
    settles, and `reconcile` runs bounded in its own job (`boundedRead`, now shared). (b) An abandoned
    write ignores cancellation, and as a structured child it kept the edit's job — the view model, the
    repository, every closure — alive for good, one more per retry; writes and check reads now start
    detached from the caller's job (`detached`), holding only their own call
    (`aLateReconciliationReadThatNeverAnswersCannotHoldTheLock`, `anAbandonedWriteDoesNotKeepTheEditAlive`;
    mutation-checked: an attached write, and releasing after an unbounded reconcile, each fail one).
15. ⚠⚠ **A late YES and a stuck call's closure** — (a) a late definitive success was handled like no
    answer, relying only on a reconciliation read; unreadable, it left a renamed album under its old
    title. A late YES now applies its effect (the answer is proof), then observes the store
    (`aLateYesIsAppliedEvenWhenTheStoreCannotBeRead`). (b) Detaching (14b) freed the edit's job but not
    what the stuck call's CLOSURE held — `repository`/`albumId` reached through `this`, so the whole
    view model, once per retry. Every closure handed to `boundedWrite` now closes over locals only,
    and reconciliation splits into a VM-free `observe` and a `publish` on the view model
    (`AbandonedWriteRetentionTest`, JVM: a weak reference to a closed screen whose rename never
    returns must clear — it does not when the closure captures `this`). ⚠ What a stuck call still
    holds is its own closure and the shared repository, which a Binder transaction pins anyway.
16. ⚠ **"Still waiting" promised an update it could not deliver** — once a write is let go of nothing
    watches it, so "this screen will update if it is" was untrue for a change landing later. It says
    to open the album again later (`stillWaitingPromisesNoUpdateItCannotDeliver`).
17. ⚠⚠ **"Not on the page" is not "not in the album"** — `albumPhotos` drops an entry whose file cannot
    be resolved, so the removal's pre-check skipped a removal whose ENTRY still existed, and the
    photograph came back once its file resolved. A removal is idempotent and is now always sent
    (`aRemovalIsSentEvenWhenThePageNoLongerShowsThePhoto`; mutation-checked). Rename and delete keep
    their pre-checks: they read the album ROWS, and an unreadable list counts as no. (The Trash learnt
    the same thing about its own listing, §8.17 note 11.)
18. ⚠⚠ **The lock was the screen's, and (7) let the user leave it** — Back stayed enabled while an edit
    ran, and a reopened album is a NEW view model whose lock is free: a delete there could succeed
    while the first screen's rename — `SaveAlbum` is an upsert — could still land and re-create the
    album without its entries. There is no leaving while an edit runs: the Back button and the system
    back on Android/desktop, the button and the swipe on iOS (`thereIsNoBackWhileAnEditRuns`,
    mutation-checked; `AlbumEditsUITests.testThereIsNoLeavingWhileAnEditRuns`, which replaces (7)'s
    UI test — its race can no longer be reached). ⚠ What this does not cover: a write let go of after
    both windows releases `writing`, so Back returns while that write may still land.
19. ⚠ **The pre-check had a window of its own** — timed before the write's two, so with both replies
    lost (18) held the user for three windows and said nothing until the second had passed. The
    pre-check and the write's first wait now share ONE window; a pre-check that never answers leaves
    the write the settle window, and "still waiting" is said when the first ends
    (`aCheckThatNeverAnswersCountsAgainstTheEditsBound`, mutation-checked). ⚠ So an edit whose
    pre-check was lost but whose write answers at once is reported as "still waiting" and then lands
    like any late YES — true to what the user waited through.
20. ⚠ **A refused edit re-reads the album** — Android's long-press removal passes no `onDone`, so a NO
    did nothing there: no message, and no read to say Control was unreachable. It re-reads, as it did
    before the bounded writes (`aRefusedRemovalReReadsTheAlbum`, mutation-checked). ⚠ Android still
    shows no sentence for a refused removal; iOS does.

### 8.17 PIO-4 slice 3a — the Trash, from Collections › Utilities (2026-10-01)

**Slice 3 split in two:** 3a the Trash, 3b Duplicates (its tile and screen). Each needed the same
rework — every sentence out of Compose, every result reported — and two smaller reviews loop faster.
Each still arrives WITH its destination (§8.15).

| Change | Where | Why |
|---|---|---|
| ⭐ `boundedWrite` / `WriteOutcome` (from slice 2, §8.16 note 9) now carry the Trash's writes too | `ui/screens/common/BoundedWrite.kt` | Every lesson the album edits took seven Codex passes to learn, applied from the start: bounded, never cancelled, the lock held through one settle window, Unconfirmed never worded as a refusal |
| ⭐ `TrashViewModel` — `restore` / `deleteForever` / `emptyTrash` single-flight, each outcome a `TrashUiState.report`; reads single-flight, bounded, publishing only their own fields; a done item off the grid at once | `:photos-shared-logic`, `TrashWritesTest` (16) | ⚠⚠ **Every result used to be dropped** — a refused restore or delete looked exactly like one that worked: the `/Trash` incident's shape (`photos-app.md` §22). ⚠ A report is STATE, not a callback, so Compose shows it too — Android dropped them as well. ⚠ An empty that leaves some behind says how many, as OUTCOMES and never as a location ("1 photo of 3 couldn't be deleted" — note 13: a refused or unanswered delete may have half-happened) |
| `TrashCopy`, `TrashUiState.statusText` / `canEmpty` | same file | One copy of every sentence; ⚠ "1 photos will be deleted" — Compose's empty confirmation never counted |
| `onChanged` hook | `TrashViewModel`, `PhotosSurface.trash` | A restore or delete changes the Collections tab's counts and years; ⚠ a hook on the view model, because a write can settle after the user has left (§8.16 note 8) |
| ⭐ `CollectionsLayout.Content.utilities` — *Utilities › Trash*, always present | `CollectionsLayout`, both shells | PIO-D3 (Apple's placement). ⚠ Present over an empty library too: the trash can hold photographs the library no longer shows. Android draws it as well (Settings › Trash stays) |
| The Trash screen — tap restores, context menu *Restore* / *Delete for Good* (a confirmation that names what is lost), *Empty* | `TrashScreen.swift` | Compose's rules: the safe action is the tap, destroying takes the menu AND a dialog |
| The fixture's trash holds photos 1490–1497; every fourth photograph (1491, 1495) refuses restore and delete | `PhotosScreenshotFixture` | The Trash has something in it on launch, and the refusals are shown by the same fixture as the successes |

**Verified:** `TrashWritesTest` 16/16 and `TrashViewModelTest` 9/9, eight guards mutation-checked (the
refusal report, the at-once removal, the present-item check, single-flight, the empty's report, a
read's own fields, the settle hold, fields before `init`); `TrashScreenTest` 7/7 (report drawn,
nothing offered while writing — both mutation-checked); `CollectionsLayoutTest` 10/10.

**Further findings:**
1. ⚠ **Android's Trash route had no hook** — DI built `TrashViewModel(get())`, so `onChanged` stayed a
   no-op and Collections came back stale. The route passes it (`parametersOf`), whichever route opened
   the Trash; only the module can show that (`TrashRouteWiringTest`, built through `viewModelsModule`).
2. ⚠⚠ **A NO is checked before it is believed** — the real ops can answer `false` having half-happened
   (`restore` moves the file then fails to clear its record; `deleteForever` clears the record even
   when the file delete fails). If the trash no longer lists the item it counts as done
   (`aNoThatHappenedAnywayIsNotCalledARefusal`).
3. ⚠ **A late success takes down "still waiting"** (`aLateSuccessTakesDownTheStillWaitingReport`).
All three mutation-checked.
4. ⚠⚠ **Back during a write lost its ending on Android** — popping the entry clears its
   `ViewModelStore`, cancelling the view model's scope, so a write that then landed never reached
   `onChanged`. Back is disabled while a write runs (the system Back consumed, the arrow disabled —
   `TrashScreenTest.nothingIsOfferedWhileAWriteRuns`); iOS needs neither, its view model is not
   cleared with the screen.
5. ⚠⚠ **The refusal's re-check was timed directly** — §8.16 note 12's bug in the Trash's own read. It
   runs in its own job, only the wait timed, and `boundedWrite` is called on `viewModelScope`, never
   inside `coroutineScope { }` (`aRefusalsCheckThatNeverReturnsCannotHoldTheLock`).
6. ⚠⚠ **iOS left during a write too** — Back and swipe-back stayed live, and a pop and reopen built a
   second view model whose own single-flight guard let a restore race a delete still able to land.
   `.navigationBarBackButtonHidden(writing)` — the button and the swipe
   (`TrashUITests.testThereIsNoLeavingWhileAWriteRuns`, over `-photosSlowEdits`).
7. ⚠⚠ **An abandoned write left its item actionable** — after both windows the tile came back, and a
   restore could race the unanswered delete still able to land. `TrashUiState.uncertain` locks just
   that item (`canAct`), both shells disable its tile, Empty skips it, and it leaves the set once a
   read no longer lists it (`anItemWhoseWriteWasAbandonedIsLocked_untilATrashReadShowsItSettled`). ⚠
   Reopening the Trash much later is a new view model — §8.16's recorded residual.
8. ⚠⚠ **A read cleared the lock** — (7) unlocked an item once a listing omitted it, but `trashed()`
   drops a row whose own read failed, so omission is not settlement and a later read brought the tile
   back unlocked. The lock now lasts the screen's life. ⚠ And Empty's confirmation counted the locked
   items it skips — "2 photos will be deleted" over one delete; `TrashUiState.emptyCount` is what both
   shells say now.
9. ⚠ **Every closure handed to `boundedWrite` closes over locals** — §8.16 note 15's rule, applied here
   (`TrashRetentionTest`, JVM).
10. ⚠ **No "the trash will update"** — §8.16 note 16 for the Trash's two sentences: an abandoned write
    is watched by nothing, so both now say to open the trash again later.
11. ⚠⚠ **The trash listing is never evidence of a write** (P1) — `trashed()` drops a row whose own read
    failed, and `deleteForever` clears the record even when the file delete fails, so "no longer
    listed" was proof of nothing. As a pre-check it could skip a write; turning a NO into a yes (note 2)
    could hide a failed delete. Both are gone: the Trash asks no pre-check (the item is on screen —
    there is something to do), and only the write's own answer counts. A NO — which may have
    half-happened — is reported WITHOUT claiming where the photo is, and the grid and Collections re-read
    the store, which shows it (`aNoIsReported_withoutClaimingWhereThePhotoIs_andTheGridFollowsTheStore`,
    `aListingThatOmitsAnItemNeverSkipsItsWrite`). ⚠ Note 2 is superseded by this.
12. ⚠ **The refresh's read was a structured child** — stuck in a Binder call, it kept the view model's
    job alive past the timeout. It runs through `boundedCall` (detached, over a local)
    (`aStuckRefreshReadKeepsNoJobOfTheViewModelAlive`). All three mutation-checked.
13. ⚠ **Empty, against a hung transport** — it now stops at the first unanswered delete (each further
    item held the user, Back disabled, for two more windows, and left one more write that might
    commit), says how many were not tried, and its report states OUTCOMES ("couldn't be deleted",
    "didn't answer"), never "still in the trash". An unconfirmed write also tells Collections, as Done
    and a NO already do (`anEmptyStopsAtTheFirstUnansweredDelete`,
    `anUnconfirmedRestoreTellsCollectionsToo`, `anEmptyThatLeavesSomeBehindSaysHowMany`; all three
    mutation-checked).
14. ⚠ **iOS: `.disabled` stopped the tap, not the long-press** — a context menu's buttons are their
    own, so while a write ran (or over an `uncertain` item) the menu still offered a Restore and a
    delete confirmation that `canAct` then dropped in silence. The menu has no content unless the
    item can be acted on, and SwiftUI shows no menu for none
    (`TrashUITests.testThereIsNoMenuWhileAWriteRuns`, mutation-checked). Compose already guarded its
    long-press.
15. ⚠ **Empty said nothing at the first bound** — its `onUnconfirmed` was a no-op, so a stalled Empty
    held the screen through the settle window with no report. It publishes the single write's "still
    waiting", and a late YES takes it down and Empty goes on
    (`anEmptyThatStallsSaysItIsStillWaiting_andGoesOnWhenTheAnswerComes`, mutation-checked).

### 8.18 PIO-4 slice 3b — Duplicates, from its tile on Collections (2026-10-02)

The second half of slice 3 (§8.17). The tile the Collections tab hid since slice 1 (§8.15) is shown —
WITH its destination. ⚠ It stays a **tile**, beside Favourites and Videos, on both platforms: that is
where the shared `CollectionsLayout` has always put it (`photos-app.md` §16.3), so PIO-D3's
"Utilities" wording holds for the Trash only.

⚠⚠ **What this slice found in the shared code — on Android today, not only on iOS:**
1. **Every write's result was dropped.** `keepOnly` looped `trash` / `deletePermanently` and threw each
   `Boolean` away: a refused move or delete looked exactly like one that worked — on the one screen
   with a permanent-delete switch (`photos-app.md` §22's shape, a third time).
2. **Nothing was bounded.** A write whose reply was lost left `busyHash` set for good; a first read
   that never answered left the spinner up for good.
3. **A confirmed keep-only could be dropped in silence** — only the busy set's row was disabled, so a
   tap on another set opened a dialog whose confirmation the view model then ignored.
4. **Back mid-loop cancelled the rest** — popping the entry cancels the view model's scope on Android,
   so the remaining copies were never tried, with nothing said.
5. **The mode was read when the write ran, not when it was confirmed**, and the group acted on was the
   one the SCREEN held, not the one the store had by then.
6. **"Could not look" had a sentence and no retry**; Collections was never told its counts changed.

| Change | Where | Why |
|---|---|---|
| ⭐ `DuplicatesViewModel.keepOnly(confirmation)` through `boundedWrite`: single-flight, the lock held through the settle window, "still waiting" said at the first bound inside the loop, the loop STOPS at the first unanswered write and says how many were not tried; every outcome short of all is `DuplicatesUiState.report` | `:photos-shared-logic`, `DuplicatesWritesTest` (20) | Findings 1–3. The Trash's rules (§8.17), applied from the start. ⚠ Reports state OUTCOMES ("1 copy of 2 couldn't be deleted", "didn't answer for 1 copy"), never where a copy is: `trash` moves the file and can then fail to record it. ⚠ No pre-check, and only the write's own answer counts (§8.17 note 11) |
| ⭐ `DuplicatesConfirmation` — the survivor's path, the paths it counted, the mode, and its five sentences — built by `DuplicatesUiState.confirmation(group, keep)`, handed back to `keepOnly` | same file | Finding 5. ⚠⚠ **What was confirmed is what runs, or nothing does**: a switch flipped afterwards changes nothing, and a set a re-read changed under the dialog (the survivor gone, a counted copy gone or locked) writes nothing and says "These Copies Changed". The kept path is never among the paths acted on — by construction |
| `DuplicatesUiState.uncertain` (paths), `canKeep`, `heading` | same file | A copy whose write did not answer YES — abandoned, or refused (§8.18 note 1) — is locked for the screen's life: never written again, and ⚠⚠ **never offered as the copy to keep** — it may already be gone or its delete may still land, and removing the others would then leave none. The confirmation counts only what it will act on (§8.17 note 8), and a set none of whose copies can be chosen stops saying "tap" |
| Reads: single-flight with one queued rerun, bounded (`boundedCall`, detached, over a local), publishing only their own fields; `retry()`; `DuplicatesUiState.status` | same file | Finding 2 and 6. ⚠⚠ An error REPLACES the list even when groups from an earlier read are held — a list that could not be re-read is not one to act on (`canKeep` is false over it). ⚠ Fields before `init` (§8.16 note 2) |
| `DuplicatesCopy` — every sentence, pluralised; `status`, `summary`, `canLeave`, `canSetDeletePermanently` | same file | One copy for both shells. ⚠⚠ The two modes never share a word that matters: *"Move the other copies to the trash? … You can restore it from there."* / **Move to Trash** against *"Delete the other copies for good? … This can't be undone."* / **Delete for Good**. ⚠ "Couldn't look for duplicates. Nothing was compared — that doesn't mean there are none." (`photos-app.md` §40). ⚠ Compose's "1 sets … out of 1 files" and "1 other copies" never counted |
| A copy with no content id is not sent to the trash; the report says why | same file | The trash record is keyed on it (`photos-app.md` §16) — it used to be a silent `false` |
| `onChanged` hook — after Done, after a NO and after an unanswered write | `DuplicatesViewModel`; `PhotosSurface.duplicates`; Android's DI (`params.getOrNull`) + the route | Finding 6; §8.17 note 1 for this route (`DuplicatesRouteWiringTest`) |
| Compose `DuplicatesScreen` draws from all of it: the report dialog, *Try Again*, Back and the checkbox disabled while a write runs; `BackHandler` on the route | `:photos-shared-ui`, `DuplicatesScreenTest` (12) | Finding 4. Android behaves differently where it was wrong, and only there |
| The Duplicates screen — summary, the switch (off by default, saying what each mode costs), a row of tiles per set; a tap ASKS (`confirmationDialog`, destructive role only when it destroys); report alert; *Try Again*; `.navigationBarBackButtonHidden` while a write runs | `DuplicatesScreen.swift` | Swift renders and calls (PIO-D2). ⚠ No context menu on these tiles, so `.disabled` leaves nothing live under a long-press (§8.17 note 14) |
| The tile shown, with its symbol and route | `CollectionsScreen.swift` | §8.15's deferral, closed |
| The fixture holds three sets — photos 100–102, 200–201, 302–303 — and photo 303 refuses the trash and the permanent delete; `-photosSlowEdits` slows `trash` / `deletePermanently` too | `PhotosScreenshotFixture` | The refusal is shown by the same fixture as the successes |

**Verified (2026-10-02, on the branch head, macOS):**
- `:photos-shared-logic:jvmTest` — 510 tests in 55 classes, 0 failures (fresh results):
  `DuplicatesWritesTest` 20/20, `DuplicatesViewModelTest` 5/5, `DuplicatesRetentionTest` 1/1 (JVM: an
  abandoned trash does not retain the view model), `DuplicatesBoundedReadTest` 1/1.
- `:photos-shared-ui:desktopTest` — 203 tests in 26 classes, 0 failures: `DuplicatesScreenTest` 12/12,
  `DuplicatesRouteWiringTest` 1/1. `:photos-android:compileDebugKotlin` green.
- **27 guards mutation-checked in Kotlin**, each removed by an edit and the named test red from a fresh
  result: the report, the at-once removal, an older read's filter, the write's single-flight, the stop
  at the first unanswered write, the lock on its copy, "still waiting" at the first bound, Unconfirmed
  never titled as a refusal, the confirmed mode, the changed-set check, could-not-look, the read's
  bound (⚠ red as a hung test, not an assertion), fields before `init` (⚠ red as the NPE the phone
  would crash with), `onChanged` after a NO and an unanswered write, closures over locals, the
  unfileable copy, the locked copy never the keeper, the read's single-flight, a read's own fields,
  nothing offered over a failed re-read, a late success taking down "still waiting", the settle
  window; and in Compose: Back while writing, the checkbox while writing, the report drawn, the retry
  offered, the DI hook.
- `DuplicatesUITests` 6 of 6 and `CollectionsUITests` 4 of 4 on the simulator (iPhone, iOS 18.5): the
  tile opens the screen and it lists the three sets out of 1 492 files; keeping photo 200 asks ("Keeping
  IMG_10200.jpg. 1 other copy will move to the trash…"), and the set leaves the list; Cancel removes
  nothing; keeping photo 302 says "Couldn't Move Every Copy to the Trash" and both copies stay; with the
  switch on the dialog is "Delete the other copies for good?" / "2 other copies will be removed from all
  your devices. This can't be undone." / **Delete for Good**; while a slow write runs there is no Back,
  the switch is disabled, and neither a tap nor a long-press on another tile opens anything.
  Three Swift guards mutation-checked: Back hidden while writing, the report alert, the switch disabled.
  The whole `PhotosIosUITests` scheme, once: 31 of 31 (album edits 6, Collections 4, Duplicates 6, Trash
  5, viewer gestures 10). `:photos-shared-logic:compileTestKotlinIosSimulatorArm64` green.
  ⚠ `CollectionsUITests`' "no Duplicates tile" (§8.15) is now "the Duplicates tile, saying *Find copies*".

**⚠ Not verified:** the full gate, the emulator smoke and the iOS smoke (run once before merge, not per
round); any device; a REAL library's duplicates on iOS; the unanswered-write paths on a simulator or
phone (the fixture has no hung transport — they are JVM-tested). ⚠ The tile guard in both shells
(`.disabled` / the `canKeep` check on the tap) is not separately mutation-checked: with it removed the
tap asks the shared `confirmation`, which is `nil`, and nothing opens — it is the second of two guards.

**⚠ Residuals, recorded:** a write that commits after BOTH bounds can still land after the screen is
reopened — a new view model, whose lock is free and for which that copy is not `uncertain` (§8.16 note
9; only server-side ordering closes it). Compose's row of 96 dp tiles does not scroll, so a set of
many copies runs off a narrow window (unchanged; iOS scrolls the row).

**Further findings:**
1. ⚠⚠ **A NO was read as "still there"** (P1) — the data-loss shape the slice exists to prevent.
   `TenshuPhotosRepository.trash` answers `false` after it has MOVED the file when recording the move
   fails. The loop only counted that as refused, so once the lock came off the stale tile was
   selectable until the re-read landed: on a slow one the user could dismiss the report, pick the
   already-moved copy as the survivor, and delete the remaining live copy for good — no copy left.
   Fixed as the class, not the branch. What each outcome of each write says about WHERE the copy is:

   | write | Done | Refused (answered NO) | Unconfirmed (no answer in both windows) | not sent |
   |---|---|---|---|---|
   | `trash` | known: out of the library — moved and recorded | ⚠ **unknown**: no `/Trash` folder or the move failed → still there; moved but the RECORD failed → gone; a transport failure → either | ⚠ **unknown** — may still land | known: untouched (no content id — the write is never sent) |
   | `deletePermanently` | known: gone | ⚠ **unknown**: `deleteFile` failed → there, or already gone; a transport failure → either | ⚠ **unknown** — may still land | — |

   **The rule: a copy whose write was sent and did not answer YES is locked** (`uncertain`) for the
   life of the screen — never written again, never offered as the copy to keep, never counted by a
   confirmation. That was the abandoned write's rule; a refusal now takes it too, so `PhotosRepository`
   needs no third answer ("moved, not recorded") and Trash and album callers are untouched.
   ⚠ **No read unlocks it**, deliberately stricter than "until a read started after the write shows
   it": a NO that is really a dropped transport can arrive BEFORE the write commits, and a read that
   still lists the copy would then unlock a survivor about to disappear (⚠ inferred from
   `it is TResult.Success` folding every failure to `false` — not observed). The cost: retrying a
   refused copy means opening Duplicates again, and the report says that instead of "try again".
   A set left with no copy that may be chosen (the survivor has nothing unlocked left to remove, the
   other is locked) says *"2 identical copies — a copy here couldn't be removed cleanly; open
   Duplicates again later"* and offers nothing
   (`aCopyWhoseTrashSaidNoAfterMovingIt_isNeverOfferedAsTheSurvivor` — Codex's exact sequence, over a
   double whose trash MOVES and then says no, with the re-read held;
   `aRefusedTrashIsSaid_theCopyStays_andCollectionsIsToldAnyway`,
   `aRefusedPermanentDeleteSaysDelete_notTrash`; on the simulator
   `DuplicatesUITests.testARefusalIsSaid_andTheSetStays`). ⚠ Why it was not seen: the fixture's refusal
   refused WITHOUT moving. Mutation-checked three ways — the lock on a refusal, `canKeep`'s locked-copy
   clause, the advice's wording.
2. ⚠⚠ **The survivor was checked against the list, never against the store** — the general guard behind
   (1). "These Copies Changed" only compared the confirmation with the state, and the state is what a
   read saw EARLIER: another device, or a write an earlier screen let go of (§8.16 note 9's residual),
   can have taken the survivor since. `keepOnly` now asks the store first —
   ⭐ `PhotosRepository.holdsContent(path, contentHash)`, one row by path, `true` only on positive
   evidence — bounded and detached like every read here, inside the write lock. Not confirmed, or not
   answered: **nothing is removed**, the report says so ("Couldn't Check the Copy to Keep … nothing was
   removed"), and the list re-reads (`nothingIsRemovedUnlessTheStoreStillHoldsTheSurvivor`,
   `aSurvivorCheckThatNeverAnswersRemovesNothing_andReleasesTheLock`). ⚠ A trashed copy's path is
   `/Trash/…`, so it is not found at the path it had — pinned on the real stack
   (`RealRepositoryReadTest.theStoreSaysWhetherACopyIsStillAtItsPath_andATrashedOneIsNot`), because the
   mock's answer is a list lookup that cannot be wrong about a move. Mutation-checked four ways: the
   check skipped, the check unbounded (⚠ red as a test that never completes, not an assertion), the
   hash comparison dropped from the real read, the re-read after a refusal to remove.
   ⚠ **Residual, recorded:** the check is made once, before the first write, and the check and the
   writes are not one transaction — a survivor taken by another device BETWEEN them is still lost. Only
   a conditional delete on the server ("delete this path only while that path holds this hash") closes
   it; not this slice's to build.
3. ⚠ **Not fixed here, reported:** the same shape — a refused write's target staying actionable —
   exists in the Trash (§8.17) and the album edits (§8.16). Neither loses a photograph: a second
   `restore` / `deleteForever` on a half-happened one finds no record or no file and answers NO again,
   and album edits never reach `:file-service`. They can show a second, misleading refusal.
4. ⚠⚠ **A repository call that THROWS was not a NO — it was a held lock, or a crash** (P1, on note 2's
   own check). `.getOrNull()` folds a `TResult.Failure`; a typed error from a dropped connection or a
   failed schema lookup is a THROW and never reaches it. `boundedCall` rethrows what its detached call
   threw, the survivor check sat outside the loop's `try`, and so `keepOnly` left its launch with
   `writing` still set — Back disabled in both shells for good, no "Couldn't Check" — or took the app
   down as an uncaught exception. Both ends, because either alone leaves a hole:
   - **`TenshuPhotosRepository.holdsContent` answers `false` on a throw** — unlike the reads around
     it, which let one through as the programming error it usually is (this file's broad catches
     were removed on purpose). This answer gates a permanent delete and its contract is "`true` only
     on positive evidence" (`RealRepositoryReadTest.aSurvivorReadThatThrowsIsNotShownToHoldIt_andACancelledOneStillCancels`,
     over the real store and a transport that throws on every request).
   - **`keepOnly` has ONE `try` over every repository call it makes, the lock released in its
     `finally`**, and each call guarded: a thrown survivor check is "not confirmed" — nothing removed,
     the report says so (`aSurvivorCheckThatThrowsRemovesNothing_saysSo_andReleasesTheLock`); a thrown
     write is a Refused one — counted, its copy locked (note 1), the loop goes on
     (`aWriteThatThrowsIsCountedAndLocked_theLoopGoesOn_andTheLockComesOff`); a thrown re-read is
     "could not look", with the retry (`aReReadThatThrowsIsCouldNotLook_withARetry`).
   ⚠⚠ **Every guard is `runCatchingCancellable`, never `runCatching`**: a cleared screen's
   cancellation arrives as a throw too, and folded into a refusal the loop ran on — every remaining
   copy counted refused and a report raised over a screen that is gone
   (`aClearedScreenStopsTheLoop_cancellationIsNotARefusal`,
   `aScreenClearedDuringTheSurvivorCheckWritesAndReportsNothing`). Seven mutations, each red from fresh
   results: each of the four guards removed (`getOrThrow`), and each of the three that a caller's
   cancellation passes through swapped for a bare `runCatching`. ⚠ The `finally` itself is not
   separately mutation-checked: with the guards in place nothing reaches it that they do not catch —
   it is the second of two.
   ⚠ **Not fixed here, reported:** `boundedWrite` / `boundedCall` are exception-transparent — they
   rethrow what the detached call threw — and that is left as it is: folding a throw into `Refused`
   or "unknown" inside the helper would change the Trash and the album edits and hide a programming
   error from all three. The Trash (`emptyTrash`, `one`) and `AlbumDetailViewModel.edit` DO release
   their lock in a `finally`, so neither is left locked — but the throw then escapes their
   `viewModelScope.launch` uncaught, with no report: the crash half of this finding is theirs too.
   `TrashViewModel.trash` (the viewer's) has no lock and the same escape.

**Verified for this round (2026-10-02, the branch merged with master `1379e0528`, macOS):**
`:photos-shared-logic:jvmTest` 520 tests in 55 classes, 0 failures (fresh results;
`DuplicatesWritesTest` 28, `RealRepositoryReadTest` 20); `:photos-shared-ui:desktopTest` 203 in 26, 0
failures; `:photos-android:compileDebugKotlin`, `:photos-shared-logic:compileTestKotlinIosSimulatorArm64`
and the XCFramework green; on the simulator `DuplicatesUITests` 6 of 6 (`TrashUITests` 5 of 5 and
`AlbumEditsUITests` 6 of 6 on the round before, when the repository interface grew a method — no
shared helper changed since). Fourteen guards mutation-checked over notes 1–4, each from fresh
results. ⚠ Not verified: the full gate, both smokes, any device; `holdsContent` against a real
library; the half-happened NO on the real stack (it is read from `trash`'s code and modelled by a
double — nothing here makes the real record write fail after a real move); which typed errors a real
disconnect throws from which call (the tests throw `IllegalStateException` from a double and from the
request-chain factory).

### 8.19 PIO-4 slice 4 — the Search tab (2026-10-03)

The last PIO-4 slice. Search arrives as the shell's **search destination**, WITH its screen (§8.15): on
iOS 26 the OS draws it as the detached round button Apple's Photos uses (Q1), on iOS 18 as the trailing
magnifying-glass tab, on iOS 16–17 as a plain third tab, and on a wide iPad as a sidebar row.

⚠⚠ **What this slice found in the shared search code — on Android today, not only on iOS:**
1. **The spinner could stay for good.** A keystroke un-settled the screen, and the pipeline then
   dropped a query equal to the last one it ran (`distinctUntilChanged`) — so nothing ran. "dog" and
   then a space was enough; so was a letter typed and taken back inside the debounce.
2. **A failed read said "No photos match".** `searchByName` / `searchByLabel` folded a failed walk into
   an empty list — "could not look" read as "nothing matched", `photos-app.md` §40's shape for search.
3. **A throwing source never settled** — and on a device left `viewModelScope` uncaught (§8.18 note 4).
4. **Nothing was bounded, and searches ran one behind the other**: a lost reply wedged the pipeline,
   and every later query waited behind it.
5. **The double cited photographs that are gone** — `MockPhotosRepository`'s label and "looks like"
   joins returned trashed and deleted photographs, so a screen tested on it could ship doing the same.
   (The real joins resolve each path, so a deleted file drops out; a trash path did not — note below.)
Findings 1 and 3 were checked against the previous view model with a throwaway test (both red there).

| Change | Where | Why |
|---|---|---|
| ⭐ `PhotoSearchLayout.of(state)` — `Idle` / `Searching` / `Results` (hits, the cap footer) / `Empty` / `CouldNotLook`, plus `notes` above the body and `retryInNotes`; `PhotoSearchCopy` — every sentence, the source badge | `:photos-shared-logic`, `PhotoSearchLayoutTest` (10) | PIO-D2: every decision and sentence was Compose's. ⚠⚠ Three answers kept apart: **nothing matched** (every source asked answered, none had a hit), **off** (a source cannot run here — beside the body, never instead of it, and kept up while a newer search runs because it is true of the device), **could not look** (asked, no answer — never Empty, always with *Try Again*). ⚠ "Off" is not looking: names and tags both failing beside an off source is "nothing was looked through", not "nothing turned up" |
| ⭐ `PhotoSearchViewModel` rewritten: an explicit job per search, debounced; a newer query CANCELS the older and starts at once; a generation check behind the cancel; a question already answered keeps its answer; each source `boundedCall`-ed (detached, over locals, `SEARCH_TIMEOUT_MS` 60 s), a throw is could-not-look, a cancellation thrown by the CALL is a failed read (only a superseded search ends silently), the sources after the first unanswered one are not asked; a search writes only its own fields; `retry()` (single-flight), `refresh()` (quiet, for a return from the viewer); `PhotoSearchUiState.couldNotLook`, `.photos` (what the viewer pages over) | same, `PhotoSearchViewModelTest` (22), `SearchRetentionTest` (1, JVM) | Findings 1–4; §8.16 notes 5, 12, 14, 15 and §8.18 note 4 applied from the start. ⚠ Fields before anything can run, and a test on an eager Main (`aSearchMayStartInsideTheCallThatAskedForIt`) — there is no `init`, but `retry()` runs inline on K/N |
| `DescriptionSearch.AskThisDevice` / `NotBuilt(device)`; `SemanticSearchStatus.NotBuiltHere(device)` | same | ⚠⚠ iOS has no index until PIO-7 (§3.2). `PhotosSurface.search` builds `NotBuilt`: the source is **never asked**, and the screen says *"Search by description isn't available on this iPhone yet."* — never "no results", and never the proxy's `noConsent` sentence, which names a switch in Control's Settings → Models that Control-on-iOS does not have. ⚠ Today's iOS proxy would answer `noBackend` (it checks the backend before consent — read in `PhotosLikeImplementation`, not run); relying on that order to stay honest was rejected. ⚠ A distinct arm, not an `Unavailable` reason: those are the proxy's answers |
| `PhotosRepository.searchByNameRead` / `searchByLabelRead` — `null` = could not look (defaulting to the plain reads); `NotConnectedPhotosRepository` answers `null` | `PhotosRepository`, both implementations | Finding 2 — §36's `…Read` shape. ⚠ `by delegate` forwards an interface's DEFAULT methods to the delegate too, so a double that overrides only `searchByName` is never reached (the existing counting double was fixed for that) |
| ⭐ `TenshuPhotosRepository.citablePhotoAt` — `null` for a path with no live `File` row OR under `/Trash`; `citableLabelMatches` — sentinel, trash paths (before the cap), one row per file | same | ⚠⚠ **P0g's Photos half, for search.** A label row and a vector outlive their file; the joins already dropped a dead path (a path lookup), but a trashed photograph HAS a live row at `/Trash/…`, and D14's move hook (P0f-b) will key typed names there — this filter must already be in place when it does (`RealRepositoryReadTest.aSearchHitMayOnlyCite…`, real stack). ⚠ Not done here: the **Things** read (`allLabelsRead`) is still unfiltered — P0g's other Photos reader; and `photosLike`'s own filter rides P0f-b's wiring |
| Compose `PhotoSearchScreen` draws the layout: notes with *Try Again*, the could-not-look body with its button, the badge; `onReturn` re-reads on a return from the viewer | `:photos-shared-ui`, `PhotoSearchScreenTest` (14) | Android behaves differently where it was wrong, and only there |
| ⭐ `AdaptiveAppShell(…, search:)` — optional; with it, the iOS 18 `Tab` builder and `SwiftUI.Tab(role: .search)` on 18+, an ordinary last tab on 16–17 | `ios-shell/Sources/AdaptiveAppShell.swift` | ⚠⚠ **Additive**: a `TabView` takes `Tab`s or `tabItem` views, never both, so an app with no search destination keeps N6's `tabItem` body verbatim (the branch is fixed for the life of the process — no container swap, paths survive). Only Photos passes one. `SwiftUI.Tab` qualified, because the file compiles into every app target |
| `SearchScreen` — `.searchable` (OS placement on 26, pinned in the nav bar below), the notes, the could-not-look retry, `PhotoGrid` with the badge as a caption and the cap footer; a result opens the viewer over the RESULTS (`FixedViewerSource`); a trash there, and every return, re-reads | `SearchScreen.swift`, `CollectionGrids.swift`, `PhotosIosApp.swift` | Swift renders (PIO-D2). ⚠ The bar's text is owned in Swift and pushed down — bound to the Kotlin state it would drop characters typed between two emissions. ⚠ No `ContentUnavailableView` (PIO-D11) |
| Fixture: names (`IMG_10042` → #42, `1004` → #40–#49), synced tags (#10–#12 "dog", #20 "beach"), `-photosSearchFails` (the first name search could not look), `-photosSlowSearch` ("1004" answers 6 s late), `-photosStartTab search` | `PhotosScreenshotFixture`, `PhotosTabs` | The tags also give the Collections tab a Things row — the AI-R read, with no model |

**Verified (2026-10-03, macOS):**
- `:photos-shared-logic:jvmTest` — 545 tests in 57 classes, 0 failures (fresh results):
  `PhotoSearchViewModelTest` 22, `PhotoSearchLayoutTest` 10, `SearchRetentionTest` 1,
  `RealRepositoryReadTest` 21, `NotConnectedPhotosRepositoryTest` 4.
- `:photos-shared-ui:desktopTest` — 208 in 26 classes, 0 failures: `PhotoSearchScreenTest` 14.
  `:photos-android:compileDebugKotlin`, `:photos-shared-logic:compileTestKotlinIosSimulatorArm64` and the
  XCFramework green.
- **Mutation-checked, each removed by an edit and red from a fresh result:** 20 Kotlin guards — the
  answered question, null as could-not-look, the throw guard, the call's own cancellation, the bound
  (⚠ red as a test that never completes), skipping after an unanswered source, the cancel and the
  generation check TOGETHER (⚠ each alone stays green — the second of two guards; the cancel alone is
  red through the debounce tests), a search's own fields, `NotBuilt` never asked, `citablePhotoAt`'s trash
  check, the label trash filter and its place before the cap, could-not-look never Empty, off is not
  looking, the retry beside the notes, failure notes not shown while searching, the double's citable
  join, the quiet refresh, the retry's single flight; 4 in Compose — the return re-read, both *Try
  Again*s, the notes drawn; 2 in Swift — the re-read after the viewer, the notes drawn.
- On the simulator — **iPhone 16 Pro, iOS 18.5**, so the **`Tab(role: .search)` path** as iOS 18 draws it
  (a trailing tab): `SearchUITests` 8 of 8 — the tab opens search and says the idle line; `IMG_10042` finds
  #42 alone, beside "isn't available on this iPhone yet", it opens the viewer, and Back returns with the
  query intact; "dog" finds #10–#12 with `tagged "dog"`; "giraffe" says "No photos match" AND the off
  line, with no route to Settings; a trashed photograph (photo 1490) is not found; `-photosSearchFails` says
  "Couldn't search everything…", never "no results", and *Try Again* finds it; `-photosSlowSearch`: "10042"
  typed after "1004" is answered at once and the older answer, landing 6 s later, never replaces it; a
  photograph trashed from the results leaves them; a viewer pushed from Search survives a rotation, and a
  tab switch keeps the query.
- The same `SearchUITests`, 8 of 8, on an **iPhone 17 simulator, iOS 27.0**.
  ⚠⚠ **Finding: iOS 27.0 does NOT detach the search tab.** Screenshots there show Library · Collections ·
  Search as three items in ONE Liquid Glass bar, Search trailing — not the round button Q1 recorded on
  the maintainer's iOS 26.6.1 Apple Photos. Checked three ways, by throwaway edits reverted afterwards:
  as built; without `.sidebarAdaptable`; with `.searchable` on the `TabView` (which only added a search
  field to Library). No `UIDesignRequiresCompatibility` opt-out is set. So either iOS 27 dropped the
  detached search tab, or Apple's Photos draws its button by other means — **unknown, and not decided
  here**: the role is the documented API, it draws a correct trailing search tab on 18.5 and 27.0, and a
  hand-placed button would be the "right on one OS, wrong on the next" the shell avoids. The look on
  iOS 26 is owed to the device pass (H3).
- The whole `PhotosIosUITests` scheme, once, on the iOS 18.5 simulator: **39 of 39** (album edits 6, Collections 4, Duplicates 6, Search 8, Trash 5, viewer gestures 10) — the 31 before this slice unchanged, though the fixture now has tags and a Things row.
- `scripts/ios-smoke.sh --build-only calendar` (two tabs, no search destination) and `--build-only drive`
  (the most destinations) PASS — the shell change compiles in apps that never pass `search`.

**⚠ Not verified:** the full gate, the emulator smoke and the all-apps iOS smoke (once before merge);
the other shell apps (Contacts, Control, Fit, Home, Mia) were not built; any device; ⚠ **the iOS 26
detached round button** (above — not reproduced on 27.0, and there is no 26 runtime here), and the
16–17 plain-tab path ran on nothing (no 16/17 runtime); the wide-iPad sidebar placement; Search on
a REAL library (name walk cost on 20 000 photographs, real synced tags); the unanswered and hung paths on
a simulator (JVM-tested — the fixture has no hung transport).

**⚠ Residuals, recorded:** a result can go stale while it is on screen (another device trashes it): the
re-read on every return catches it, and a tap before that opens the viewer, which says *"This photo
hasn't been downloaded to this device yet."* rather than a blank page — true-ish, not precise. A
REPLACED file at a label's path is still cited (P10). Superseded scans are abandoned, not cancelled — at
most one per pause in typing can still be running in the proxy. The Things row (`allLabelsRead`) is not
trash-filtered (P0g's remaining Photos reader).

### 8.20 The Library during a sync — follow the top, apply differences, pace the draws (2026-10-05)

**Reported on the iPhone, while photographs synced in:** the Library flickered, and the new photographs
only showed after a scroll up by hand. Both were the grid doing what §8.6 built: every `FeedLayout`
was a `reloadData()` — every visible row rebuilt, per photograph delivered — followed by
`restore(kept)`, which put the top photograph back *even at the very top*, so the newest-first arrivals
landed above the viewport.

Three rules, one per cause.

**1. At the top, the grid follows the top.** Before a layout is drawn, the grid asks whether it is
resting at the top inset (within 1 pt) with nobody holding it: not tracking, dragging or decelerating,
the scrubber not held, no kept photograph still unresolved, no re-anchor queued, and
`PhotosFeedBinding.reanchorPending` false (a re-anchor waiting for pages, or a request published and not
yet performed). Then it keeps no anchor and rests at the top once the layout is drawn. ⚠⚠ Everywhere
else nothing changed: the photograph under the thumb is kept by its path, exactly as §8.6 finding 13
and its successors left it.

**2. A layout is applied as a difference.** `FeedLayout.changesFrom(old)` — shared Kotlin, JVM-tested,
there for Compose to adopt — answers the lines to delete, insert and redraw, and the grid applies them
as one batch update plus `reconfigureItems`. A line is the same line when its identity is; a kept line
is redrawn only when its content differs:

| | Identity (`lineIds`) | Content (`lineStamps`) |
|---|---|---|
| Header | `day-<section start>` | the grouping (its label is formatted for it) |
| Photographs | the PATH of the line's first photograph (`key`'s rule) | shape (row / feature and its hero side) + every photograph's row, loaded or not |
| Placeholders | `lead-<n>` from the top, `tail-<n>` from the bottom | how many slots |

- ⚠ **The shape is content, not identity** — a row that becomes a feature when its last photograph loads
  is the same line, reconfigured (and re-measured: the layout is invalidated after every apply).
- ⚠⚠ **Placeholder lines are counted from the library's nearer END, never by the index they draw.** By
  index, one arrival at the head renames every placeholder line in the library, and a page that shifts
  the cut's phase (60 photographs at 7 columns) does too. Counted from the ends, a landing page removes
  the lines beside the window and renames none.
- ⚠ Identities are distinct even over a list that repeats a path — two lines sharing a name would be
  one line to `changesFrom`, a batch update traps on a miscount, and a row served twice across a page
  seam must not be a crash.
- ⚠⚠ **The per-draw work is the loaded window's, never the library's** — see *The cost
  of a draw* below. `lineIds()` / `lineStamps()` name every line and are the SPECIFICATION the tests
  replay `changesFrom` against, never a per-draw call.
- ⚠⚠ **The batch is checked against what the view last counted**, so the grid asks
  `numberOfItems(inSection:)` while the OLD layout is still the drawn one: a view that has not loaded
  yet would otherwise load the new lines and then be told to insert them again.
- ⚠⚠ **A row can outlive the layout it was drawn from.** It carries that layout with its line, so what it
  draws stays consistent; the one thing that must be current — the position a tap hands the viewer — is
  resolved at the tap against the layout drawn then (`FeedLayout.positionOf`).
- Reloaded rather than diffed when every row is redrawn anyway (another grouping, column count or tile
  edge), when kept lines changed places (a batch of deletes and inserts cannot say "moved"), or when
  more than `MAX_LINE_CHANGES` = 400 lines come and go (a jump across a long library is thousands of
  placeholder lines — decided from the two counts, without listing them).
- `PhotoTileView` asks the decoded-tile cache in `body`, not only in its task: a rebuilt row otherwise
  starts grey and fills a frame later — the flash on a photograph that was on screen a moment ago.
- `topLine` reads the layout's attributes for the band at the top, not `indexPathsForVisibleItems` —
  the visible cells are a layout pass behind an offset just written.

**The cost of a draw.** The first version of rule 2 was a
`UICollectionViewDiffableDataSource` keyed by `lineIds()`. That is an identifier per LINE, and most
lines of a long library are placeholders: every draw named, bridged, hashed and diffed all of them, and
the flow layout underneath asked the delegate for every line's size besides — which it had done since
§8.6, on every `reloadData()`. Measured on the simulator (debug build), a 60-photograph window, the
24-arrival script, median main-thread ms for a draw that builds no row:

| Library | Lines | Full reload, as §8.6 drew | Diffable + flow layout | **As built** |
|---|---|---|---|---|
| 2 000 | 693 | — | 8.7 | **2.1** |
| 10 000 | 3 350 | — | 23.1 | **1.1** |
| 50 000 | 16 690 | 18.2 (every draw rebuilds ~6 rows) | 106.1 | **2.7** |

Working out what changed and applying it (everything but building rows) went from a median 97 ms a draw
at 50 000 photographs to **0.9 ms, the same at 2 000**. Two changes, because there were two O(library) terms:
- **No per-line identifiers.** `changesFrom` diffs the two LOADED windows by identity and treats each
  placeholder run as a count; a run that grew or shrank is a range beside the window. Pinned by
  `theChangeSetCostsTheLoadedWindow_notTheLibrary`, which diffs two layouts with a billion unloaded
  photographs on each side. (JVM, for scale: naming every line was 0.04 / 0.14 / 0.41 ms at 1 000 /
  10 000 / 50 000 photographs — the Kotlin was never the cost; bridging 2 × 16 700 strings and diffing
  them in UIKit was.)
- **`FeedLinesLayout` instead of a flow layout.** Only the loaded lines are measured; the unloaded runs
  are `count × row`, and finding the lines in a rectangle is arithmetic or a search of the loaded
  window. ⚠ Still exact — every line's frame is known, never estimated — which is what §8.6 chose a
  collection view for; `scrollToItem`, `layoutAttributesForItem` and the scrubber's seek are untouched
  and index lines as before.
- ⚠ **What still costs: building a row.** A draw that builds rows is dominated by them — a median ~25 ms
  per NEW row in this debug simulator build (`UIHostingConfiguration`'s first render) — so the scenario's
  total is 1.0 s of main thread for the 24 arrivals against 2.2 s for full reloads, not 30 ms. Proportional
  to the rows that changed, not to anything else; not measured in a release build or on a device.

**3. Draws are paced.** `FeedLayoutPacer` (shared, a pure state machine — the shell supplies the clock
and the wake-up): the first layout after a quiet spell is drawn at once; within 300 ms of the last draw
the newest is HELD, replacing any held before it, and drawn when the interval is up.
- ⚠⚠ **The last layout is always drawn** — the wake-up is an unstructured task, because the screen's
  `.task` is cancelled when the viewer is pushed.
- ⚠⚠ **Nothing the user did waits.** Drawn at once: another density or column count; the layout after a
  width change; any layout while a re-anchor is pending, while a finger or the scrubber is on the list
  (the pages their own scroll asked for), or while the grid is still looking for a kept photograph.
- ⚠ **A scroll request draws the held layout first**: a request names a line in the newest layout, and
  `scrollTarget` offers nothing against an older one.
- A selection change is not a layout — `SelectionState` is observed by the tiles — so it never meets the pacer.

**What the Mosaic's re-cut means for "untouched".** An arrival re-cuts every row of the section it lands
in (each starts one photograph earlier), so those are other lines — deleted and inserted, not kept.
Every later section keeps its lines by name, but whether a line is a feature follows its photograph's
position in the LIBRARY (§32's rule that nothing moves when a page lands), so a section whose position
crosses a row boundary has its features move down a line. Measured in
`anArrivalReCutsItsOwnSection_andMovesTheFeaturesOfSomeLaterOnes` on a 30-day library of 4–26 photographs
a day: **one arrival leaves 168 of 181 later lines untouched, three leave 106 of 181.** So the honest
claim is not "only the new row is drawn": it is *unchanged rows are untouched, changed rows are
reconfigured in place, and nothing is reloaded*.

**Verified (2026-10-05, macOS, iPhone 16 Pro simulator, iOS 18.5).** `-photosFixture -photosArrivals
-photosArrivalsDemo 60`: the fixture's library behind a real `Pager` (`seekablePagingConfig`, so
placeholders and a refreshed window as in production); twelve photographs arrive 80 ms apart at the top,
then the grid is scrolled to line 60 and twelve more arrive. Each arrival invalidates the pager.

| | Before (`-photosReloadAll -photosNoPacing -photosNoFollow`) | Diff, unpaced (`-photosNoPacing`) | As built |
|---|---|---|---|
| At the top, after 12 arrivals | top = #-5, 628 pt down — **7 new photographs above the viewport** (4 of 4) | top = #-12, offset 0 (9 of 9) | top = **#-12, offset 0** (6 of 6 runs) |
| Scrolled to photo 127, after 12 more | photo 127 in 2 runs, **photo 124 in 2** | photo 127 in 7 of 9, **a neighbour in 2** | **photo 127, 0 pt into its line** (6 of 6) |
| Layouts drawn for the 24 arrivals | 40–48 | 30–39 | **13–14** |
| Rows configured by those draws | 223–266 | 89–164 | **40–50** |

Screenshots of each step are the evidence for the first two rows. `-photosDemo 120` (photo 277, then photo 274 at
Month) and `-photosScrub 0.5 0.9` are unchanged. `PhotosIosUITests`: 39 of 39.

**⚠ Not verified:** a real iPhone, a real sync's timing and a real library — the fixture's arrivals are
evenly spaced and its reads instant; a finger (the "not while dragging" half of rule 1 is by inspection:
`simctl` has no drag); iPad widths; Android, which gains only the shared functions.

**⚠ Residual, seen and NOT fixed here — the kept photograph can slip to a neighbour under a burst.**
Seen four times in the scrolled half, always UNPACED: in the old configuration (2 of 4 runs: photo 127 →
photo 124), and in the unpaced diff (2 of 9: photo 127 → photo 143 once, → photo 124 once — the second after `topLine` read
the layout, so that change is not the cure). Never in a paced run (0 of 22, the size runs included) —
fewer draws are fewer chances — but no cause is removed and none is claimed. A candidate worth testing:
the anchor is the top LINE's first photograph, and while a refreshed window has its section's header
unloaded above it the section is cut from the window's edge (§32), so the kept photograph can sit second
or third in its line and the next capture keeps that line's first instead. That would also be why the
old grid at the top ended on #-5 rather than #0. Anchoring on the photograph itself rather than its
line's first would close it; `tech-debt.md` *"The feed's density re-anchor drifted"* is the neighbouring
open sighting, and this PR leaves both alone.

### 8.21 The chrome — the Library lies under the system's bars (2026-10-06)

**Reported on the iPhone (iOS 26.6):** nothing showed through the Library / Collections bar, and the
header was far less transparent than Apple's Photos, where the photographs fade out under the title.

**The cause was one thing, and it was not a background.** The tab bar is the system's (`AdaptiveAppShell`
→ `TabView`) and no bar had a custom background, material or `UIBarAppearance`. But the Library's grid
was laid out INSIDE the safe area with `contentInsetAdjustmentBehavior = .never` (§8.11's fix), so it
ended where the bars began. On iOS 26 the bars are Liquid Glass floating over the content layer; with no
content behind them they showed the window's background — a white slab under a glass capsule. Collections
(a SwiftUI `ScrollView`) never had the fault. The sticky date added a second slab: a `.bar` strip
directly under the navigation bar.

**What Apple documents** (fetched 2026-10-06):

| Rule | Source |
|---|---|
| Tab bars float on Liquid Glass "that allows content beneath to peek through"; full-screen content extends "underneath sidebars, toolbars, and tab bars" | HIG [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars), [Layout](https://developer.apple.com/design/human-interface-guidelines/layout) |
| "Instead of applying a solid or semi-opaque background color beneath controls, use a scroll edge effect"; custom bar backgrounds "might overlay or interfere with Liquid Glass or other effects … such as the scroll edge effect" | HIG Layout; [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass) |
| The fade is the **scroll edge effect**: `automatic` by default, `soft` "a subtle, blurred boundary", `hard` "a linear, nearly opaque boundary". iOS 26+ only | [`ScrollEdgeEffectStyle`](https://developer.apple.com/documentation/swiftui/scrolledgeeffectstyle), [`UIScrollView.topEdgeEffect`](https://developer.apple.com/documentation/uikit/uiscrollview/topedgeeffect), [`UIScrollEdgeEffect`](https://developer.apple.com/documentation/uikit/uiscrolledgeeffect), HIG [Scroll views](https://developer.apple.com/design/human-interface-guidelines/scroll-views) |
| A search tab is `Tab(role: .search)`; the system places it | Adopting Liquid Glass, *Search* |

**The rules — what is the system's, and what must never come back:**

1. **The grid fills the window under both bars** (`LibraryScreen.grid`: `.ignoresSafeArea(.container,
   edges: .vertical)`), and UIKit insets its content (`contentInsetAdjustmentBehavior = .always`).
   `adjustedContentInset` is what clears the bars, never the frame. ⚠⚠ Never a grid inside the safe area
   *with* automatic adjustment — that pair is §8.11's hang. Extending the grid removes the hang's cause
   (the title no longer resizes the grid), so `.never` is gone; `-photosBackDemo` still passes.
2. **No background on a bar, and none drawn under one**: no `toolbarBackground`, no
   `UIBarAppearance`, no material strip. The bars' glass and the edge effects are the system's.
3. **The top edge effect is `.soft`** on iOS 26+ (`FeedGridView`), the fade Apple's Photos shows. ⚠ A
   deliberate departure from the HIG's "prefer automatic" — automatic and `.hard` both draw a flat
   slab with a line under it. The bottom edge stays automatic.
4. **The date of the top row sits under the title** on iOS 26+ (`LibraryTitleStyle`), where Apple's
   Photos has it. Before iOS 26 the bars are the translucent kind and the `.bar` strip continues them
   (`LibraryStickyDate`); it draws nothing on 26+.
5. **Overlays stay inside the safe area** — the scrubber's track, the pre-26 date strip, the sync
   capsule (12 pt above the tab bar, unchanged). Only the grid extends.

**Insets, exactly:** the grid's frame went from *below the navigation bar … above the tab bar* to the
whole window; `adjustedContentInset` went from zero to the bars' heights; at rest the offset is
`−inset.top` instead of 0. `FeedGridView` already read every offset against `adjustedContentInset`
(the top line, follow-the-top, the restore clamp, the scrubber's end), so no arithmetic changed.
`SyncCapsuleUITests` now also asserts the arrangement: frame top 0, frame height the window's, both
insets positive, the first row resting just below the bar.

**The title — large, leading, and white over the photographs** (2026-10-07, iOS 26+ only; the
maintainer compared ours with Apple's Photos and found the small centred black title over a whitish
fade the harder one to read):

6. **The fade is dark in both appearances, and the title is white over it.** ⚠⚠ The edge effect has no
   tint of its own: it dissolves the content into the scroll view's **`backgroundColor`**. Clear over
   a white page gave a white wash, and neither `toolbarColorScheme(.dark)` nor a dark
   `overrideUserInterfaceStyle` on the grid changes that — each turned the text white and left the
   wash white, which is worse than black on it. So the grid's `backgroundColor` is black and the page
   is its `backgroundView` (`systemBackground`), which covers the black everywhere but in the effect.
   The effect is still the system's; nothing is drawn over the photographs. In the dark appearance
   nothing changed — that is the look the light one now shares.
7. **⚠⚠ The bar's colour follows the BACKDROP, never the appearance.** The effect is drawn only while
   something lies under the bar (it is fully there 3 pt into a scroll). At rest the bar is over the
   page, so the bar is dark-schemed (`toolbarColorScheme(.dark)`: white text, dark glass) exactly while
   `FeedGridView` reports content under it, and the appearance's own otherwise. An unconditional
   white title is no title at rest in the light appearance.
8. **The title is a leading toolbar item, not the navigation title** — `.title` bold, the date under
   it, `sharedBackgroundVisibility(.hidden)`. ⚠ The system's own pieces do not hold still:
   `toolbarTitleDisplayMode(.inlineLarge)` is this exact look at rest and collapses to the small
   centred title on the first scrolled point; `.large` also grows the bar, and with it
   `adjustedContentInset.top`. The item keeps the bar at its inline height, so **no inset, frame or
   rest offset changed** and the probes below hold as they were. `navigationTitle` stays (VoiceOver,
   the viewer's back button); `toolbar(removing: .title)` only stops it being drawn. While a selection
   exists the system's inline "N Selected" is back, white over the same fade.
9. ⚠ The title and date carry a faint shadow while over the fade: the soft effect has all but ended
   where the date sits, and over a white photograph that line was white on light grey. That shadow is
   the one thing here that is ours and not the system's.
10. **A section's own date dissolves before it reaches the bar** (`FeedGridView.headerAlpha`, the
    row's `contentView.alpha` over the 22 pt ending 10 pt below the bar). Blurred by the effect it
    read as a second date behind the title's. ⚠ Alpha of the rows on screen only — nothing is
    invalidated at scroll rate (§8.11) — and full whenever the header is the top line, which is the
    one moment the title shows no date.

**The bar's controls over photographs** (2026-10-10; reported on the iPhone, iOS 26.6: the density
button was "transparent white with four transparent white squares"):

11. **While photographs lie under the bar, a control in it is clear glass over a 35 % dim with a
    white label** (`BarButtonOverPhotos` + `barItemOverPhotos`, `BarControlOverPhotos.swift`) — the
    density button, the selection's Cancel and Settings' gear (where the build has one); at rest each is the plain system item. ⚠⚠ Why: on
    iOS 26.x toolbar glass **adapts** to the luminance behind it (Apple's Photos, Safari and a stock
    toolbar all flip, 0.3–0.5 s after the backdrop changes; it is luminance, not motion), but rule 7's
    forced dark scheme **pins the label white** while the glass still turns light — 1.4–2.7:1 over
    bright and mid-tone photographs on 26.5. iOS 27.0 no longer flips, and the same item read 3:1
    there. The pattern is the HIG's own for this case
    ([Materials](https://developer.apple.com/design/human-interface-guidelines/materials)): the clear
    variant is "for components that float above media backgrounds — such as photos and videos", it
    does not adapt, and "if the underlying content is bright, consider adding a dark dimming layer
    of 35% opacity". The dim is 60 % under Increase Contrast and 80 % under Reduce Transparency.
    - ⚠⚠ **One signal**: the title's `underBar`, nothing derived beside it. The swap is a cut — one
      frame, never both glasses and never none.
    - ⚠⚠ **The glass is a background that overhangs the label**, so the label is laid out as the
      system's item lays it out and neither the control nor the bar's height can change with the
      backdrop. The metrics are the system's, measured, and differ between 26 and 27 for a text
      label (`textInset`); `BarControlUITests` pins both controls' centres, a tap 16 pt off centre
      as a hit target, and the label's contrast over `-photosTileTone white` (it reads 1.55:1 with the
      glass taken out). ⚠ On 26.5 the item's hit frame is the system's 36×36, so the glass's outer
      ~4 pt are not tappable; 27.0 widens it.
    - ⚠ **Beside the Settings gear**: both sit in `.topBarTrailing`, gear first, with a fixed
      `ToolbarSpacer` between them. Without the spacer (or with the density item in
      `.primaryAction`) the system groups them into one pill at rest and splits it into two
      circles over photos, so the gear moves sideways (4–6 pt). The test pins the gear's centre and
      an 8 pt gap in both states.
    - ⚠ **Not the alternative that looks idiomatic**: dropping the forced scheme lets the system
      flip the item by itself (it is what Apple's Photos does on 26.x), but 26.x then leaves the
      status bar black on the dark fade — unreadable — and nothing sets it apart from the scheme.
      Nor can one item escape the scheme: a colour scheme on its label pins it to that scheme.
    - A new control in this bar is a `BarButtonOverPhotos`, or it has the defect again.

Seen, not fixed: over a WHITE backdrop the fade is a mid grey (not Apple's near-black) and the system
keeps the status bar's clock black on it — legible, low contrast. Over the fixture's mid-tone
photographs the status bar turns white. Before iOS 26 nothing changed: no edge effect exists to be dark, so the title
stays the system's and black. Collections and Search keep the system's title.

Verified 2026-10-07 on the simulators (iPhone 17 / iOS 27.0, iPhone 16 / iOS 18.5; still no iOS 26
runtime): screenshots at rest, over photographs, over the white page and over the black one, in
both appearances; the four probes of the table below with the same numbers; `SyncCapsuleUITests`
3 of 3 with its assertions untouched; the whole scheme 40 of 42. ⚠ The second red on 27.0 is
`ViewerGestureUITests.testARotationWhileMagnifiedKeepsTheFocalPoint` (x 0.604 for 0.653) — it fails
identically against master's sources. ⚠ Not looked at: a real photograph library (the fixture's
tiles are flat mid-tones), Increase Contrast / Reduce Transparency, an iPad, a real iPhone.

**Not matched, on purpose:**
- Apple's Photos collapses its tab bar to one button beside a Years / Months / All control, and has
  a Select pill in its header. Both are Apple's own composition, not system defaults. The nearest system pieces are `tabBarMinimizeBehavior(.onScrollDown)` and `tabViewBottomAccessory`
  (both iOS 26+). ⚠ Minimising changes the bottom safe area while the user scrolls, which moves
  `adjustedContentInset` under the grid's anchors — a change to make with the probes, not with this one.
  Our densities stay on the toolbar button.
- The detached round Search button: still not drawn on the iOS 27.0 simulator (§8.19's finding stands).

**Verified (2026-10-06, macOS, simulators — iPhone 17 on iOS 27.0 and iPhone 16 on iOS 18.5; there is
no iOS 26 runtime on this Mac):**

| Check | iOS 27.0 | iOS 18.5 |
|---|---|---|
| Photographs visible through the tab bar and under the navigation bar while scrolling (screenshots) | ✅ glass + soft fade | ✅ translucent bars |
| `-photosBackDemo`: `back: returned, main thread free`, CPU ≈ 0 % afterwards (§8.11) | ✅ | ✅ |
| `-photosDemo 120`: photo 277 on top, re-anchor keeps photo 274 | ✅ | ✅ |
| `-photosScrub 0.5 0.9`: rest 0.499 / 0.899, bubbles, release | ✅ | ✅ |
| `-photosArrivals -photosArrivalsDemo 120`: follows the top (IMG_9988), keeps IMG_10307 scrolled | ✅ | ✅ |
| `SyncCapsuleUITests` (3, with the new under-both-bars assertion) | ✅ | ✅ |
| Whole `PhotosIosUITests` scheme | 38 of 39 ⚠ | 39 of 39 |

⚠ The one red on 27.0 is `DuplicatesUITests.testBackingOutOfTheConfirmationRemovesNothing` (no "Cancel"
button in the confirmation) — **it fails the same way on master**, checked by running it against
master's sources; nothing here touches that screen. ⚠ On 18.5 two tests first ended in a runner restart
while another session shut the simulator down (load average 30–55); both passed re-run on a second
simulator.

⚠ **Not verified:** a real iPhone, and iOS 26 itself — the report came from 26.6 and the nearest
runtime here is 27.0. Reduce Transparency / Increase Contrast, and an iPad's sidebar placement, were
not looked at. The `RealmStorageBanner`, when it has text, sits above the tabs and the grid then starts
below it — unchanged, and not exercised by the fixture.

### 8.22 The tab switch — a return to Collections froze the app (2026-10-07)

**Reported on the iPhone (iOS 26.6, a library of thousands, Debug build):** switching between Library
and Collections is "really laggy" — noticed with the Library's large-title change installed, and suspected of it.

**It was neither the large title nor §8.21's glass bars.** Measured with `-photosTabSwitch` (below) on a 5 000-photograph
fixture, Library at rest and scrolled, main-thread CPU per switch (median of 5):

| Swift sources | → Collections, rest | → Collections, scrolled | → Library, rest | → Library, scrolled |
|---|---|---|---|---|
| `721067fb0` (before the glass bars) | 2 648 ms | 2 770 ms | 30 ms | 45 ms |
| `444d6fa68` (master, with the glass bars) | 2 694 ms | 2 703 ms | 35 ms | 45 ms |
| `1c4981c5f` (the large title) | 2 666 ms | 2 781 ms | 35 ms | 39 ms |
| this fix | 65 ms | 61 ms | 43 ms | 50 ms |

(iPhone 17 simulator, iOS 27.0, load average 13–23 throughout. The same fix measured at a load of ~500
read 163 / 179 ms — CPU time moves little with load, a wall clock would not have survived it.)

**The cause** is the walk, not the switch. `CollectionsScreen` refreshes on every return
(`onAppear`), the refresh groups the whole library by year on the main thread
(`CollectionsViewModel.loadSections`, `viewModelScope` = `Main.immediate` on Kotlin/Native), and
`PhotoDates.yearOf` resolved the device's zone **per photograph**: on Darwin
`TimeZone.currentSystemDefault()` reads and parses the zone's tzdb file each call. A sample of the
frozen main thread had 2 205 of 2 467 frames in that one function. It scales with the library — 10.5 s
at 20 000 — and it has been there since the Collections tab shipped (PIO-4 slice 1); the per-call lookup
itself since PIO-0. The feed's separators (`sectionStart`) pay the same price per row.

**The rule:** `PhotoDates` reads the zone through `HeldZone` — resolved at most once a second, so a
walk pays once and a zone change still reaches the next render. ⚠⚠ Never
`TimeZone.currentSystemDefault()` in a function that runs per photograph.

**The guards:** `HeldZoneTest` (one resolution per walk; a change is seen when the hold ends), and
`TabSwitchUITests`, which runs the probe and bounds the main thread's CPU per switch at 1 000 ms —
⚠ CPU time, not a wall clock, so it holds under load. `-photosTabSwitch <n>` with
`-photosLibrarySize <n>`, `-photosArrivals` and optionally `-photosTabSwitchScroll <line>` is the
probe by hand.

#### 8.22.1 The second half — the reads themselves were on the main thread (2026-10-10)

**Reported on the iPhone again, with the zone fix installed:** the switch to Collections is still
"super slow". The view model's own line, six returns in a row:
`allPhotos=1632ms places=1632ms allLabels=1632ms albums=1632ms favourites=1632ms faceGroups=1726ms`.

**Five reads do not finish in the same millisecond.** One thread was held for 1.63 s and all five
answers were delivered when it let go. On iOS the repository is the in-process route-1 runtime: a
read is a store query **on the thread that calls it**, and `viewModelScope` is `Main.immediate`. So
`refresh()` — called from `onAppear` — ran all six reads one after another on the main thread, and
then grouped the library inside `_state.update`. The zone fix above removed only the grouping's
dearest call. The simulator could not see any of it: its fixture answers from memory, which is how a
1.65 s hold on a phone measured 65 ms here.

**The rules:**

1. **The iOS shell hands `CollectionsViewModel` a background `workDispatcher`** (`PhotosSurface.collections()`).
   The reads run there, and so does every grouping of the library (`derived { … }` — never inside a
   state update). ⚠⚠ State is still published from `viewModelScope`; only values cross back.
   `null` (tests, the Compose shells) keeps the caller's context.
2. **The return no longer waits for the refresh**: the tab draws the state it has, and the sections
   replace themselves as their reads land. ⚠ When this was written the tab still walked the library
   on EVERY return, because nothing told it whether anything had changed. It now walks only when
   something may have — §8.22.3.
3. **The timing line says where the time went.** Each read reports how long it RAN; when its answer
   arrived more than 50 ms after that it adds `(answered after N ms)`, and the line starts with
   `reads=background` or `reads=caller`. The phone's line above would now have read
   `reads=caller albums=40ms(answered after 1632ms) …` — one held thread, not five slow reads.

**The guards:**

- `-photosHeldReads <ms>` makes each of the fixture's six reads burn that much CPU on the thread
  that calls it. `TabSwitchUITests` runs with 300: main-thread CPU per switch to Collections was
  **1 847 ms** before this change and **54 ms** after (5 000 photographs, load average 24–66), and
  the unfixed line reproduced the phone's signature exactly — `allPhotos=1886ms places=1886ms …`.
- `CollectionsWorkOffTheCallerTest`: every read and the grouping run on the worker; `refresh()`
  returns before any read has run; and the contrast (no worker: all six inside the call). ⚠ Seen red:
  with `derived` made to run inline, the first of these fails.

⚠ **Still open:** the walk itself holds the shared replica's lock a page at a time, and a read that
needs the same lock (a tile, the feed's next page) waits for that page. How often it runs: §8.22.3. ⚠ Every other screen: §8.22.2.
⚠ **Not verified here:** the simulator cannot run the route-1 store; the held-read fixture
reproduces the shape of the cost, not its size.

#### 8.22.2 Every other screen — no repository call runs on the thread that makes it (2026-10-10)

**The phone, with §8.22.1 installed** (eight returns to Collections):
`reads=background … allLabels≈1ms places≈1ms favourites≈1ms albums≈2ms faceGroups 8–107ms allPhotos 1701–2170ms`.
So the cost is not "a read", it is **a walk of the library — ~2 s**; a point read is a millisecond.
Collections was one caller of that walk on the main thread. It was not the only one.

**The audit** — every repository call the iOS shell makes, and where it ran before this change:

| Screen | When | What ran on the main thread | Walk? | Now |
|---|---|---|---|---|
| Library — the feed's pages | first load; every page while scrolling; every scrubber jump; every sync invalidation | `PagingSource.load`: the page (`traverse` + top-N), the count beside it (once per generation), the rows' mapping | **yes, per page** | off — `feedPaging(readContext)` |
| Library — the scrubber's year index | once per launch, after the first page | `feedCaptureTimes` (every capture time) and `feedYearMarks` (a calendar year for each) | **yes** | off — the repository, and `PhotosFeedViewModel.workDispatcher` for the index |
| Library — selection: Trash / Add to Album | the bar's buttons | one write per selected photograph, in a row (`trash` is create-folder, move, record) | per item | off — the write lane |
| Library — motion tiles | each visible video's turn | `motionClip` (the clip's bytes) | no | off |
| Viewer | open; every swipe; About opened; star, Trash, Add to Album | `isFavourite`, `places` (the whole Place table, once per open); with About: `details`, `photographicSettings` (**reads the file's bytes**), `albumsFor` (every album entry); the writes | no (point reads + one file read) | off |
| Collections | appear, return | §8.22.1 | **yes** | §8.22.1 |
| Collections — New album, naming a person | tap | `createAlbum`, `nameFaceGroup`, then a refresh | no | off |
| Album, Favourites | open; return from the viewer; after each edit | `albumPhotos` (every entry, a lookup each — a **walk per entry whose file moved**), `favourites` (the flags, then a filtered walk); rename / delete / remove and their `albums()` checks | **yes** | off |
| Trash | open; after each write; Empty | `trashed` (a lookup per record); restore / delete; Empty is one write per item | per item | off |
| Duplicates | open; Try Again; after a keep-only | `duplicates` (a scan of every file's content hash), `holdsContent`, one trash or delete per copy | **yes** | off |
| Search | each query (250 ms after the last keystroke); Try Again; return from the viewer | `searchByNameRead` (a walk), `searchByLabelRead` (every label) — typing stalled for both, per pause | **yes, two per query** | off, and a superseded query's read is cancelled |
| Selection's album sheet | sheet opens | nothing — `PhotosSurface.albums()` is called from Swift, and SKIE runs a suspend function it is handed off the caller (the red run below shows no `albums` for it) | — | through the repository like the rest |
| Tiles, the viewer's hero | — | nothing: `tilePreview` hopped already, and a Flow collected from Swift runs on SKIE's own dispatcher | — | unchanged |

**Left on the main thread, deliberately** — each is a pass over what is already in memory, bounded by
a page or the loaded window, never by the library: the feed's separators (`sectionStartOf`, twice per
row of a page), `FeedLayout.of` on each window change (measured, §8.20), `positionOf` /
`loadedPhotoPositions` on a tap, the search's merge of ≤ 60 + 30 hits, `nearestPlace` per swipe, and
the album / Trash / Duplicates filters over their own lists.

**The mechanism is one object, installed once.** `PhotosSurface` wraps whatever repository it is
given — route 1's, the not-connected one, the fixture — in `OffCallerPhotosRepository`, and every
view model and every Swift call reaches the library through it. No view model changed to get there:
its coroutine stays on `viewModelScope`, reads and publishes its own state there, and *suspends* at a
repository call where on iOS it used to block — which is what each of them already does on Android,
where the call is IPC. The rules:

1. **Reads run side by side; writes run one at a time, on a lane of their own.** A write is several
   store operations (`trash`: make the folder, move the file, record it; `addToAlbum`: read the last
   position, save the next), the store's lock covers each operation and not the sequence, and what
   kept two sequences apart was only that both ran on the main thread. The lane gives that back. ⚠ A
   read may still see a write half-made, as a tile's read always could; every screen re-reads after
   its own writes.
2. **The feed's pages are asked to move, they are not wrapped** — Paging loads them inside the
   `PagingSource`, where nothing outside reaches. `feedPaging(pageSize, readContext)` hands the
   context to `:core-paging`, and only the read, the count and the row mapping run there: the keys
   and the order pages are placed in stay Paging's, on the thread they were on. ⚠ Not `cachedIn` on a
   background scope: that would move the change observer and the separators too, for no gain.
3. **A pass over a whole library is the view model's to move**, with the same dispatcher —
   `CollectionsViewModel.workDispatcher` (§8.22.1) and `PhotosFeedViewModel.workDispatcher` (the year
   index). ⚠ `withContext` around the arithmetic, never `flowOn` under a `stateIn`: the two fuse, and
   the state would be published from the worker.
4. ⚠⚠ **`PhotosRepositoryDecorator` names every member and has no `by` delegation.** Delegation
   forwards what the class does not name straight to the inner repository, in the caller's context:
   a read added to the interface later would compile, pass, and run on the main thread. Written out,
   it is a compile error.
5. **A superseded search lets go of its read** (`boundedCall` cancels the call when its caller is
   cancelled). A walk that has not started never does; before, on a pool, a walk per pause in typing
   would have piled up behind the one that counts.

**What the move exposed, and what was done about each** — the calls were always safe *from one
thread*; this is the first time most of them are made from several:

- **The store is safe**: every `DatabaseImpl` method runs under the `DatabaseLock` (on iOS a
  re-entrant lock plus the cross-process `flock`), the schema, observer and client registries are
  atomic copy-on-write, and the repository holds no state of its own. ⚠ The lock is held for a whole
  list materialisation, so a walk on a worker still makes a main-thread tile read wait its turn.
- **`TenshuEnvironment.services` was a plain map** — every `client.fileService` / `photoService` /
  … getter went through an unlocked `getOrPut`. Now `ConcurrentMemo` (atomic copy-on-write).
- **The `-photosFixture` repository was not thread-safe at all** (plain maps, a check-then-write
  `seed()`), and only ever worked because every call came from the main thread. It now confines its
  own state to one lane, *inside* the layer that notes where a call arrived.
- ⚠ **Not fixed, recorded in `tech-debt.md`:** `InMemoryPreviewCache` (iOS's preview cache — a plain
  map every tile read goes through, already from a pool before this change) and `StreamRegistry`'s
  listener maps, which a write's change fan-out reads on the writing thread. Neither is new, and
  the write lane means this change adds one writer thread at a time, as the main thread was.

**The guards:**

- `ReadsOffMainUITests` walks the Library and its pages, the viewer and About, Collections, an
  album, Favourites, the Trash and a restore, Duplicates, a search and the selection's album sheet,
  then reads `reads-on-main` — the fixture's list of calls that arrived on the main thread — and
  asserts it is `none`. ⚠⚠ A **count, not a time**: the fixture answers from memory, so no clock
  could see this, and one call is enough to fail. `-photosHeldReads` now holds *every* call, not
  Collections' six, so the same defect is also felt.
  ⚠ Seen red, with the decorator taken out of `PhotosSurface` — all four tests, and the lists are
  the audit above as the simulator saw it:
  `library, pages, viewer, About: feedPaging.load feedCaptureTimes feedPaging.load isFavourite places setFavourite details photographicSettings albumsFor`,
  `collections, album, favourites, trash, duplicates: … albumPhotos favourites trashed restore trashed duplicates`,
  `search: … searchByNameRead searchByLabelRead`, `selection, album sheet, add to album: … addToAlbum`.
- `PhotosCallsOffTheCallerTest` (worker = a queue the test runs by hand, main = immediate, as on
  Kotlin/Native): every member runs on the worker; each screen's trigger returns before any call has
  run and its state still arrives; an older search that answers *last* — an order a pool can produce
  and the main thread could not — is not published over a newer one; a cancelled caller's read never
  starts. `OffCallerWriteLaneTest` (a real pool): writes never overlap, reads do.
  `RealRepositoryReadTest`: the real feed's pages are the same pages in the same order when read on
  a lane, and rows are mapped and counted there. `ConcurrentMemoTest`. ⚠ Each seen red.

**Verified (2026-10-10, iPhone 17 simulator, iOS 27.0, load average 11–22).** With every call held
200 ms where it runs, the unfixed walk through the Library, the viewer and About made nine calls on
the main thread — 1.8 s of it held — and the fixed one makes none. `PhotosIos-UITests`: 45 of 47, the
two reds being master's. The probes read as before: `-photosDemo 120` photo 277 then photo 274,
`-photosScrub 0.5 0.9` rest 0.499 / 0.899 with both year marks, `-photosBackDemo` returns free,
`-photosArrivals -photosArrivalsDemo 120` keeps IMG_10307 in 14 layouts, and `-photosTabSwitch 4`
(5 000 photographs, every call held 300 ms) costs the main thread 48 ms a switch, either way.

⚠ **Still open.** (1) Android and desktop are unchanged — they install no decorator and pass no
dispatcher: their calls are IPC and suspend, and what stays on their main thread is the grouping
after a walk. (2) Drive-on-iOS builds its listings on `Dispatchers.Main` over the same in-process
runtime (`DriveReadSurface.create`) and is not touched here; nobody has measured a folder's page
there. ⚠ **Not verified:** a real iPhone. The simulator cannot run the route-1 store; what
these guards prove is *where* each call runs, not what it costs there.

#### 8.22.3 A return walks the library only when something changed (2026-10-10)

Off the main thread, a walk is still ~2 s of store reads on a phone, a page of the shared replica's
lock at a time — and the tab made one on **every** return, changed or not.

**The signal is the repository's: `PhotosRepository.libraryWatch()`**, two `StateFlow`s and one
promise — *while `watching` is true, nothing the Collections tab reads can change without `revision`
changing*. It errs one way only: a revision may move for nothing (one wasted walk); where it could
stay still across a change, `watching` is false and the tab behaves as if there were no watch.
`null` — the default, and every repository but route 1's — is "cannot tell".

What feeds it (`TenshuPhotosRepository`, given a `watchScope`):

| What Collections reads | How a change is seen |
|---|---|
| The files — years, videos, and what places / Things / People are joined against | **The feed's own change observer.** `:core-paging` calls `onReload` once per reload signal; the first is "observing from here", each later one a change. ⚠ Not a second observer: a signal-only stream re-evaluates the whole list per change (`tech-debt.md`), and two observers of one chain share a stream id. |
| Albums, album entries, flags (favourites), places, labels | A signal-only stream each, over **the projection the read itself uses** (one definition, used by both), kept for the process. |
| People (`faceGroups`) | No list to observe — the clusters are this device's. ⚠⚠ **Not the watch — the index runner** (2026-11-11, as-built at the end of this section): the Core ML backend claims search and faces, not tagging, so a pass writes **no** `MediaLabel` marker and the watch never moves for it. The view model's `indexChanges` takes the installed graph's `faceChangingPasses(runner.faceChanges)` instead — a **counter**, never the conflated standing — treated as an edit (`toldChanged`). |

⚠⚠ **So `watching` needs a collected feed**, and the promise is exact: **Collections is never staler
than the Library's own grid.** Opened before the Library has ever been built (`-photosStartTab
collections`), nothing vouches for the files and every return walks, as before. ⚠ A density change
rebuilds the feed's observer: watching drops and returns, the revision moves, and the next return
walks once for nothing. ⚠ A file changed in place (same path) signals nothing to the feed either;
nothing Collections draws from a file changes in place today.

**The rules** (`CollectionsViewModel`):

1. **`refreshIfChanged()` is what a return calls.** It walks unless the watch is watching, no change
   was told, and the revision is the one the last walk **began** at — and that walk read every
   section. ⚠ Taken when the walk begins, never when it ends: a change that lands during a walk
   moves the revision past it, and that walk's answer is not called clean. ⚠ A walk with a failed
   section is not clean either: the return retries it.
2. **A change while the tab is on screen is walked for; one while it is not waits for the return**
   (`setShown`). One walk per *burst*: 500 ms of quiet, 3 s at most — the feed's own ceiling, or a
   long sync would show nothing until it ended — and never beside a walk already running.
   ⚠⚠ **Coming back on screen after a change counts as a change** (`setShown`): the smart album's
   grid only says it is shown again, so a photo trashed from the viewer pushed over it — noted while
   nothing was shown — would otherwise wait on a race with the trash's own signal. A surface that also calls `refreshIfChanged()` still walks once: the tick finds the walk
   it started.
3. **An edit this app made is `changed()`, not `refresh()`.** The write's own signal is on its way
   too; walking at once started before it landed, was overtaken by it, and walked twice. Noted, it is
   one walk when the signals settle (shown) or on the return (covered). ⚠ This also ended a double
   walk that predates the watch: coming back from an album edit ran the edit's refresh and the
   return's, one queued behind the other. ⚠⚠ **A noted edit is owed its walk even if the watch
   stops first**: it was only noted *because* the watch was expected to deliver
   it, so a watch that stops inside the settle wait still walks for it.
4. ⚠⚠ **Only a CHANGE may start a walk nobody asked for.** "Not clean" is not enough: a failed walk
   is not clean, and walking for that retried a failing read twice a second for as long as the tab
   stayed up. A failure waits for Try Again or a return.
5. `refresh()` is unchanged and unconditional — Try Again, a realm change, the launch-time walk, and
   the Compose shells, which call nothing else.
6. The console says which it was: `Collections -> walk=skipped (clean)`, or the read timings.

**What can still be missed, and how the user recovers.** (a) ~~A re-clustering of faces that writes no
label~~ — **closed 2026-11-11** (as-built at the end of this section), including a pass that ends
between the view model's first walk starting and the runner being resolved. (b) A change the feed itself never hears of — then
the Library is stale too. (c) A teardown: the watch stops watching when its scope is cancelled, so
this fails safe; so does an observer that throws (caught, and the watch stops vouching — uncaught,
it would end the process on Kotlin/Native). (d) ⚠ **A list stream that breaks mid-way keeps the
watch vouching until it is back**: `changes()` retries a failed stream silently and
says nothing to its collector, so for the outage `watching` stays true over an unobserved list. The
resubscribe's own tick moves the revision, so it heals; and on route 1 the proxy is in-process, so an
outage is one where the reads fail too. The cheap tightening is a `changes(onInterrupted = …)` hook
in `:core` that drops `watching` until the next subscribe — not done here. There is **no pull-to-refresh** on the tab; what always walks is leaving the app
(a relaunch), a section's *Try Again* when one has failed, and any edit made in the app.

**Android and desktop are unchanged**: they pass no `watchScope`, so there is no watch, and their nav
item calls `refresh()` as before. The same rule would be right there — every tap on their nav item is a
walk in Control — but their Collections view model is created at the app level and their
feed's observer is IPC; nobody has checked that the reload hook and the five streams hold up over
Binder, so it is not switched on by reading the code.

**The guards:**

- `CollectionsWalkUITests` counts the fixture's own tally of walks (`library-walks`) — never a time:
  four returns with nothing changed walk nothing; one arrival while the Library is up walks nothing
  until the return, then once; a burst of twelve with the tab shown is one walk; an album made on
  the tab is one walk. ⚠ Seen red against the old `onAppear`:
  *"four returns with nothing changed: 5 walks, expected 1"*.
- `CollectionsWalksOnlyWhenChangedTest`: the same counts on the view model, and the cases a UI test
  cannot stage — changes during a walk are exactly one rerun; an overtaken walk with the tab hidden
  waits for the return; a watch that stopped, restarted or began late vouches for nothing; a failed
  section is retried by the return and by nothing else; an edit is walked for even when the watch
  stops before its walk. ⚠ Each seen red; the failure rule's red is a
  test that never ends.
- `RealRepositoryReadTest`: over the real streams, the watch is false without a collected feed, true
  with one, and moved by an arrival, a new album, a rename, an entry, a favourite and its undoing,
  a trash, a place and a label — and not moved by nothing. ⚠ Seen red without the reload hook, and
  without the places or the labels observer (each alone).
  ⚠ It does **not** pin the projection: it passes with albums and flags observed bare as well.

⚠ **Not verified:** a real iPhone, where the signals are the store's and a sync is a sync.
⚠ **Not measured:** what the five streams cost. A signal-only stream re-evaluates its projection per
change (`tech-debt.md`), the labels are the long list, and another device's indexer delivers them by
the thousand. They are subscribed off the main thread, on one lane, and kept for the process — so
the cost, whatever it is, is paid whether or not Collections is ever opened again.
⚠ **Since PIO-7 5e-1 this device indexes too** (behind its off flag). ⚠ Corrected 2026-11-11: the
Core ML backend does not claim tagging, so an iOS pass writes **no** marker — the cost named next applies
only where a tagging backend is bound (and is why People needed the runner, as-built below). As first
written: the foreground pass writes one
`MediaLabel` marker per file, each re-evaluating the labels projection on the watch lane — roughly
quadratic against the replica's lock over a pass — and with Collections on screen the tab walks about
every 3.5 s (the 3 s ceiling + 500 ms settle) for as long as the pass runs. Measure this before the
flag flips.
⚠ **The feed is counted as observed on its FIRST reload**, which can be the 10 s first-load deadline
or a failed subscribe rather than the observer's confirmation (`PagingUtil.firstLoadAfterTheObserver`).
If that confirmation is then lost for good, `watching` stays true with no file observer — the Library
is stale too, so the promise above still holds, but the cheap tightening is for `onReload` to say
whether the tick was the confirmation, and count only that (`:core-paging`; not done here).

**As built — People after an index pass (2026-11-11).** Found on the maintainer's iPhone with both face
flags on: `faces.log` held templates, and every return logged `Collections -> walk=skipped (clean)` over
the pre-pass People until a relaunch — a pass wrote templates (device-local) and no label (the Core ML
backend does not tag), so nothing the watch observes moved.
- `CollectionsViewModel(indexChanges: Flow<Unit>?)` — each emission is `indexChanged()`: `toldChanged`
  is set **whether or not the watch is watching** (it cannot see this change at all), walked for through
  the burst wait while shown, owed to the return while not. ⚠ Unlike `changed()`, never an immediate walk
  with nobody looking, even watchless. ⚠ The change loop now runs when there is a watch **or** an
  `indexChanges`.
- iOS feeds it `PhotosSurface.faceIndexChanges()` → the installed `PhotosFaceGrouping.passChanges` →
  `faceChangingPasses(MediaIndexRunner.faceChanges)` (`:inference-service-proxy`), the runner resolved on
  `Dispatchers.Default` at first collect; the count at subscribe is the baseline.
  - `MediaIndexer.faceStoreWrites` counts every change to the template store **at the write**: a
    record accepted (with faces, ⚠⚠ **or the empty record of a faceless photograph** — a store of only
    those answers People "nobody found", not `NOT_INDEXED`), a clear, a reconcile that dropped rows.
  - `MediaIndexRunner.faceChanges` moves once per pass across which that count moved, in the pass's own
    `finally` — so a pass that was stopped or threw counts what it wrote, and one that wrote nothing
    (an iOS backgrounding over a current library) is no walk.
  - ⚠⚠ **Not the standing** (as first built): `standing` is a conflated StateFlow, so a pass's
    `Idle(ending)` followed at once by the next `Running` delivered only the latter to a reader on Main,
    and an ending is published only over the pass's own `Running` (compare-and-set), which a cancel
    racing a start loses. Either way pass N's change was lost and N+1 found everything current. A
    conflated counter loses nothing: any later value still differs from the last seen.
  - A grouping installed after the first (a graph rebuild) is itself one emission: the old graph's
    last pass may have written after the new runner's baseline.
  - ⚠⚠ **Resolving the runner is itself one emission**, never a silent baseline: the view model's
    first refresh starts before the off-main lookup returns, so a pass ending in that window would
    sit inside the baseline, and later passes over the rows it wrote move nothing — People stale on
    every clean return. Cost: one extra walk per Collections construction (and per rebuild).
    `aPassThatEndedBeforeTheRunnerResolvedIsStillWalkedFor`.
  ⚠ The tab redraws when a pass ENDS, not during it: a first index of hours shows its People at the end.
- Android/desktop pass `null`: no watch there, every return walks.
- Tests: `CollectionsWalksOnlyWhenChangedTest` (the `aPass…`/`withoutAWatchAPass…` cases, and the
  Android/desktop construction with no `indexChanges`), `RunnerFaceChangesTest` (templates, a faceless
  first pass, stopped after/before writing, a refused clearing pass, the baseline, two passes with no
  dispatch between), `MediaIndexerFacesTest` (`…MayHaveChangedPeople`, `aWithdrawalMidPassIsReportedAsCleared`),
  `PhotosPeopleGroupingTest` (`aFaceStoreChangingPassOnTheRealGraphIsExactlyOneEmission` over the real
  graph and chain, `aGroupingThatReplacesAnotherIsOneEmission`). ⚠ Each guard seen red once.
- The same pass's `MediaIndexPass ->` line now carries `faces=on|off(noStore)|off(noBackend)|off(noConsent)|notAsked
  facesSkipped=N`: with search running and faces not, those photographs were counted `current`, and the
  line could not tell "faces not ready" from "faces done". In-process only — `IndexPassResult` is no
  schema type, so no version moves.
- ⚠⚠ **And the face half has its own failure counters** (`facesFailed`, `facesRejected`; found on an
  Android phone the same day): its latch counted into the vector half's `embedFailed`/`rejected`, so a
  pass with SigLIP fine and only the detector latched printed `embedded=1 … embedFailed=29`. Now
  `embedFailed`/`rejected` are the vector half's only (`aLatchedFaceDetectorBesideAWorkingEmbedderIsTheFaceHalfsFailure`,
  each of the three guards seen red).
⚠ **Not verified on the phone yet** — owed to the next device pass (`ios-face-grouping.md` §17.6).

### 8.23 People name suggestions on the person page (IN-6 F6c-s, 2026-11-11)

The SwiftUI person page draws the shared `PersonPage.suggestedName` / `unconfirmedCount`
(`photos-people-and-pets.md` §6a [F6c-s as built](photos-people-and-pets.md#f6c-s--name-suggestions-as-built-2026-11-11))
as `NameSuggestionView` under the header — *"Is this Mia?"* with Yes / No on an unnamed group, or
*"N photos matched by face on this device — not yet named Leo."* with Confirm / Not Leo on a display
fold — answering through `CollectionsViewModel.acceptSuggestion` / `rejectSuggestion`. ⚠ Only where
`PhotosSurface.personPage` reports a person, so only where `groupingRunsHere()`, and not while
"With…" narrows the grid (`nameId` is nil then, as for naming). Every word is `PersonPageCopy`'s.
Fixture: `-photosPeople suggest`; UI test `CollectionsUITests.testAnUnsureGroupIsAskedAndASureOneIsFoldedUntilNo`.
⚠ Not verified on the phone yet — `ios-face-grouping.md` §17.6.
