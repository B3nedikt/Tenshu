# On-device inference on iOS — PIO-7 (AI-L) for Photos

**Status:** v0.4 — 2026-10-04. **The plan is written around its decided backend: native Core ML fp16
on iOS, no ONNX Runtime** (D1, §7). D1's condition, PR-0b, passed (§2.2). PR-0 and PR-0b were
throwaway device spikes on the iPhone 17e and are done (§2.1, §2.2); nothing of them is on any branch.
**PR-1 is built (§9): the export script, the Core ML session, the model cache, the image decoder and
the IN-3m-iOS harness. Nothing binds them yet.** ⭐ **IN-3m-iOS's §4.4 sequence has run** (§9.7:
partial 2026-10-07, completed 2026-10-22/23, figures in `inference-service.md` §15.14): 33 ms a
photo on JPEG and 61 ms on the phone's own HEIC, `.serious` after ~4 700 photos on the charger with
no slowdown, the first open slow only after a long gap unused. ⭐ **Passed** (maintainer, 2026-10-23): the
backend's footprint and a floor-class phone stay owed under D10 / D12, not under this gate (§7).
⭐ The heat stop at `.serious` is kept as built, with no chunking (2026-10-23, §14.19.8).
**The retrieval gate is in the repo
(`scripts/inference/retrieval-gate.py`) and the current export passes it (§9.6, 2026-10-06).** **PR-5 lands as five slices (§14): 5a (the binding and the readiness state), 5b (the runtime preflight and its probe), 5c (the download, §14.11, and the prepare step, §14.17) and 5d (the Models screens, Photos' own and Control's shared view model, §14.18) are built, behind a build flag that is off; 5e (the foreground pass) is two PRs, 5e-1 (the conditions, the pass and its stop) and 5e-2 (the indexing section, Mia's iOS lines and Photos' privacy manifest) both built (§14.19), and what must hold before the flag flips is §14.18.16. A load of the towers Photos does not survive is counted, and two make the phone `Unsuitable` (§14.5 item 0, D12).** ⭐ **2026-11-09, on the iPhone 17e with the flag on in a development build:** the download, *Preparing*, a passing preflight, consent, *Index now* over a 22-photo library and "cat" finding all 5 cats — not yet H5's ≥ 200 photos and "dog". The same day §14.18.16 items 3, 7, 10 and 11 were closed off the phone (7 with a finding: a launch passes `AwaitingVerdict` for one publication) (*Try again* is one press per key); **the flag still waits for the ordered phone list at the top of `how-to/pio7-phone-session.md`, and for D12 ⓪'s iOS 17 load, which Xcode 27.2 cannot run (no iOS 17 runtime).** **PR-7 (*Index now* continues on iOS 26+, a `BGContinuedProcessingTask`) is built behind the same flag (§15), continuing under the shared store's **task token** (§15.4, Q1 resolved 2026-11-05: `ios-shared-store-sqlite.md` B.23).** **PR-6 (the overnight processing wake, a `BGProcessingTask` on the charger) is built behind the same flag (§16, 2026-11-11); its three device nights are owed (`how-to/photos-processing-wake.md`).** **PR-6b (new arrivals indexed while Photos is in front, `inference-service.md` IN-D6a) is built behind the same flag (§17, 2026-11-11); its 📱 pass is owed.** Five PRs remain (§5). Fourteen decisions (§7):
D2–D4 are **superseded** by D1, D8 is **built** as PR-6 (§16; its S0b-2 precondition met since `ios-shared-store-sqlite.md` B.22), D13 is **decided** (its label half on 2026-10-11, `inference-service.md` §39.1), D14 is **new and open**, D10 is **reopened**.

v0.4 in one list:
- **The backend** is Kotlin/Native over `platform.CoreML`, in `inference-service-proxy` `iosMain`. No
  Swift implementation, no third-party binary, `.cpuAndNeuralEngine` for both towers (§3.2).
- **The model** is a second catalog entry for the same checkpoint under the runtime `coreml`: one
  uncompressed tar per `.mlpackage` tower plus the shared vocabulary, ~709 MB (§3.4).
- **The compile** (`MLModel.compileModel`) runs on the phone after the download, into Photos' private
  container, behind a marker written last (§3.4).
- **Nothing in a schema is reshaped** (§3.4).
- **What went with ORT:** the fetch script, the cinterop, the C shim, the link flags, ~17 MB, the
  execution-provider constant and the Photos-only *linkage* argument (§3.5). PIO-D10's Photos-only
  *binding* and store ownership stay (§3.1).
- §2's spike table and §2.1's ORT rows are kept as measurements of the route not taken.

Earlier versions: v0.3 recorded D1 and PR-0b's result. v0.2 was the amendment after independent
review: the realm stamp (owner id + the SQLite store's `meta.epoch`, §13.2) re-checked at four points; a fourth
lock, so non-Photos graphs bind a store that never writes; the model index out of the Keychain; PR-5
cancelled on background and behind a build flag, with the overnight wake deferred; the
device-capability gate (D12).

⚠⚠ **Two things this plan cannot get around:**
- A product build of Photos on a phone needs the App Group build, and that build is **not installable**
  until `ios-scheduler-lease.md` S0b (a)–(d) exist (`ios-background-scheduling.md` §4.1.1). So only the
  harness (PR-1), which needs no App Group, can run on the iPhone before then. Every later 📱 check waits
  for S0b.
- **A background pass also waited for S0b-2** (the gate and the quiesce step). The pass reads the shared
  replica under its file lock, and iOS kills an app that is suspended while holding that lock
  (`0xdead10cc`). ⭐ **Met:** the SQLite store is on in every build since `ios-shared-store-sqlite.md`
  B.22 (2026-11-05, no switch), and a `BGTask` holds its **task token** (B.23). The background passes
  are PR-7 (§15) and PR-6 (§16), both behind the AI flag.

Parent: [`photos-ios.md`](photos-ios.md) §3.2 (AI-L), PIO-D9, PIO-D10, §5's PIO-7 row and §7 Q2.
Inputs: [`inference-service.md`](inference-service.md) (IN-D2, IN-D3, IN-D5, IN-D13, §15 IN-3m, §32,
§34, §36), [`media-processing-pipeline-sweep.md`](media-processing-pipeline-sweep.md) (D3, D4, D5, F6,
P3, P7, §6.4.0 item 6), [`ios-scheduler-lease.md`](ios-scheduler-lease.md) (S0b, S1, §10.3 S2),
[`sync-push-wake.md`](sync-push-wake.md) (S4).

Marks: **[C]** read in code at the cited line · **[M]** measured by the spike in §2 · **[D]** stated
in another doc · **[I]** inferred, not verified.

---

## 1. What exists, and what is missing — per PIO-7 item

### 1.1 The runtime on iOS

| | State |
|---|---|
| Core ML in any iOS build | **None.** No Kotlin in the repo imports `platform.CoreML` [C: none found], and `:inference-service-proxy` has no `iosMain`: its iOS targets (`inference-service-proxy/build.gradle.kts:88-90`) compile `commonMain` alone [C]. |
| What Kotlin/Native exposes | `platform.CoreML` ships in the Kotlin 2.4.10 distribution for `ios_arm64`, with the symbols the backend needs (§3.2) [C: symbol search in the klib]. No cinterop, no `.def` file. |
| ONNX Runtime on iOS | **Not used** (D1). ORT stays the JVM and Android runtime, pinned at `onnxRuntime = "1.20.0"` (`gradle/libs.versions.toml:80`) [C]. `onnxMain` compiles into `androidMain` and `jvmMain` only (`inference-service-proxy/build.gradle.kts:123-124,132-133`) [C]. |
| Why `onnxMain` cannot be reused | It *is* the ORT **Java** API (`OnnxSessionFactory.kt:3-4`, `OnnxInferenceBackend.kt:3-9`: `java.io.File`, `java.nio.FloatBuffer`). The tokenizer needs `java.text.Normalizer` (`SiglipTextTokenizer.kt:3`), and the vocabulary reader needs `java.io.DataInputStream` (`UnigramVocabulary.kt:3-4`) [C]. The tokenizer and the reader move to `commonMain` (§3.2); the session code gets an iOS twin over Core ML. |
| What the model is | Not the ONNX files. A second export of the same checkpoint: two fp16 `.mlpackage` towers, delivered through the same signed catalog under the runtime `coreml` (§3.4). |
| What it costs in the binary | Core ML is a system framework, so no app bundles a runtime. The backend's Kotlin is KBs [I: not measured]. v0.3's ORT route cost Photos ~17 MB (§2) [M]. |
| Where the Kotlin lives | In the umbrella. `TenshuSdk` is **one static XCFramework** linked into every app (`tenshu-sdk-ios/build.gradle.kts:35,47-53`), and it reaches `:inference-service-proxy` through `:control-shared-proxy` (`:274`) [C]. So every app's binary contains the backend's code, and only Photos **binds** it (§3.1). ⚠ Whether all ten apps link `CoreML` and `ImageIO` without a `project.yml` change is [I]; PR-1 builds all ten. |

### 1.2 The image decoder (`IosImageDecoder`)

- **Missing.** No Kotlin in the repo imports `platform.ImageIO` [C]. Photos decodes its tiles and hero
  Swift-side (PIO-D8), and that path cannot feed a proxy-side backend.
- The seam it fills is `ImageDecoder.decode(bytes, size, centreCrop): IntArray?` (`VisionModel.kt:80-81`)
  [C]. The output is packed ARGB, exactly `size²` entries. The arithmetic after it is shared
  (`ImagePreprocessing.preprocessPixels`, `ImagePreprocessing.kt:65-86`) [C].
- **Shape:** `CGImageSourceCreateWithData` →
  `CGImageSourceCreateThumbnailAtIndex(kCGImageSourceThumbnailMaxPixelSize = <the long edge of the
  rule below>, kCGImageSourceCreateThumbnailWithTransform = true,
  kCGImageSourceCreateThumbnailFromImageAlways = true)`. Then draw into a `side × side` RGBA `CGBitmapContext` at high interpolation quality. SigLIP
  squashes and does not crop, so the draw squashes; when `centreCrop` is set, crop the source rect
  first. Last, repack RGBA to ARGB. The input is bounded the way `AndroidImageDecoder` bounds it with
  `inSampleSize` (`AndroidImageDecoder.kt:17-30`) [C]. HEIC and ProRAW previews decode natively [I].
- **The decode size: Android's short-edge rule, plus a long-edge cap** (decided 2026-10-05; v0.4
  said *2·side on the long edge*, which is withdrawn).
  - Android halves while the short edge would still be at least two sides
    (`AndroidImageDecoder.kt:22-23`: `while (min(w, h) / (sample * 2) >= size * 2) sample *= 2`) [C].
    The decoded short edge is then in `[2·side, 4·side)`, so the squash only downsamples. iOS computes
    the same `sample` from the image's pixel dimensions and asks ImageIO for a long edge of
    `max(w, h) / sample`. A 12 MP 4032 × 3024 photo decodes at 1008 × 756 on both.
  - ⚠ A long-edge bound of `2·side` left a 6:1 panorama 512 × 85, **upsampled** threefold before the
    squash.
  - ⚠ **The cap is iOS's own: the long edge never exceeds `16·side`** (4096 px at side 256).
    Android has no cap [C: none in `AndroidImageDecoder.kt`], and AWT does not bound its decode at
    all (`AwtImageDecoder.kt:24-27`) [C]. With the short edge under `4·side`, the decoded RGBA image
    is at most `16·side × 4·side × 4` bytes = 16.8 MB, against the ~88 MB a pass was measured at
    (§9.4) [M*]; uncapped, a 20:1 strip is 84 MB by itself.
  - The cap engages above an aspect ratio between 4:1 and 8:1, depending on where the halving left
    the short edge. The short edge stays at or above the model's side up to **16:1**. Past 16:1 it
    is upsampled, which is accepted for a strip. Below the cap iOS and Android decode the same
    size; above it they differ, and vectors are device-local (IN-D5).
- ⭐ **EXIF orientation comes free, and differs from Android on purpose.** `…WithTransform` applies
  orientation. Android and AWT apply none (sweep F6, `AndroidImageDecoder.kt:15-49`) [C]. The vectors
  differ, which is allowed because they are device-local (`ImagePreprocessing.kt:51-61`, IN-D5) [C]. iOS
  therefore starts where the sweep's P7 is going, and owes no re-index when P7 lands.
- ⚠ **Bytes in, not a path.** The interface takes a `ByteArray`, so a 48 MP ProRAW original is read whole
  before ImageIO bounds it [I]. P7's `IndexImage` seam (sweep D12) is where a path-based source belongs.
  It is not a PIO-7 change.

### 1.3 Indexing conditions (`IosIndexingConditions`)

- **Built in 5e-1** (§14.19.1, as built there; the table below is the proposal it came from).
  Photos' graph alone contributes them; every other route-1 graph keeps `IndexingConditions.Never`.
- **Shared policy:** `indexingConditionsAllow(onExternalPower, batteryLow, inUse)`. An unknown is a no
  (`IndexingConditions.kt:62-63`) [C]. The Android binding reads `isCharging`, the OS low-battery flag and
  `isInteractive` (`AndroidIndexingConditions.kt:20-35`) [C].
- **iOS mapping** (proposed):

  | Input | iOS signal | Note |
  |---|---|---|
  | `onExternalPower` | `UIDevice.batteryState ∈ {.charging, .full}` | Needs `isBatteryMonitoringEnabled`. `IosBackupEnvironment.kt:57` already reads it [C] |
  | `batteryLow` | `ProcessInfo.isLowPowerModeEnabled`, or `batteryLevel < 0.2` | iOS has no "battery low" broadcast. Low Power Mode is the OS's own "save power" answer [I] |
  | `inUse` | `UIApplication.applicationState != .background` | No third-party "screen on" API exists. A `BGProcessingTask` is only granted while the device is idle [D: Apple] |
  | thermal (sweep D5) | `ProcessInfo.thermalState ≥ .serious` ⇒ pause | D5 already names `.serious` / `.critical` for iOS (sweep §5) [D] |

  ⚠ `UIApplication.applicationState` is main-thread-only, and the conditions are read per image on the
  media lane. Cache the state from lifecycle notifications. Never `dispatch_sync` to main from the lane
  [I].
- ⚠⚠ **What this means on iOS:** `allowIndexing()` (scheduled) can be true **only inside a processing
  wake**, because any foreground state is "in use". So the scheduled pass runs in a `BGProcessingTask`
  or not at all. `allowManualIndexing()` (*Index now*) drops `inUse` and keeps battery-not-low plus
  thermal. That matches the inference row's "Index now keeps only battery-not-low" (§34) [D].

### 1.4 `BGTaskScheduler` and permitted identifiers

- **Missing for Photos** [C]. `photos-ios/project.yml:68-96` declares no `UIBackgroundModes` and no
  `BGTaskSchedulerPermittedIdentifiers`. Drive's precedent is `UIBackgroundModes: [fetch]` +
  `BGTaskSchedulerPermittedIdentifiers: [app.tenshu.drive.refresh]` (`drive-ios/project.yml:117-118`) [C].
  ⚠⚠ The keys go in `project.yml`. `Info.plist` is generated and gitignored.
- **The identifier to use:** `app.tenshu.photos.processing`. The lease doc reserves
  `app.tenshu.<app>.processing` for S2 (`ios-scheduler-lease.md` §7.3) [D]. The mode is `processing`, not
  `fetch`. The request is a `BGProcessingTaskRequest` with `requiresExternalPower = true` and
  `requiresNetworkConnectivity = false` (sweep D7: `NETWORK_NONE`) [D].
- **The shared runner is not built.** `IosPlatformScheduler` / `TenshuBackgroundWake` (S1) are design only
  (DRAFT v0.14, not buildable), and S2 (`BGProcessingTask`) is a paragraph (`ios-scheduler-lease.md`
  §10.3) [D]. The registry item `tenshu/inference/mediaIndexPass` **is** registered in every route-1
  process (`PlatformProxyModule.kt` iosMain `:763`) [C]. It defers there and has never run anything,
  because no process contributes conditions (§36) [C]. The lease doc did not trace where the embedding
  store lives (§3.2 footnote ²) [D]. **It is Photos-local** once PIO-D10 is built (§3.1), so that
  footnote resolves to *app-local*.
- **S4 (APNs wakes) is not a route for indexing.** A content-free push wakes the app for a short budget
  meant for a sync drain (`sync-push-wake.md`) [D]. A library pass is minutes of compute [I].

### 1.5 Photos-owned stores — PIO-D10, mechanically

What every route-1 process does today [C]:

| Store | Where | Who opens it |
|---|---|---|
| `EmbeddingStore` (`/inference/embeddings.log`) | `DefaultEmbeddingStore(get(), …)` over the **shared** `FileDatabase` (`ProxyModule.kt:241`). That is `IosFileDatabase`, rooted in the App Group container (`IosFileDatabase.kt:51-60,76-78`; bound at `PlatformProxyModule.kt` iosMain `:649`) | every route-1 process that resolves it |
| `FaceTemplateStore` (`/inference/faces.log`) | same (`ProxyModule.kt:248`) | same |
| `ModelStore` bytes | `modelDeliveryComponents(fileDatabase = get(), …)` (`ProxyModule.kt:304-325`), so also the shared container | same |
| `ModelStore` index + garbage list | `SecureStorageDelegate` (`DefaultModelStore.kt:15,58`). Under route 1 that is a **per-app** Keychain bucket (`FirstPartyLocalRuntime.kt:113-117`, `IosSecureStorageDelegate.kt:89`) [C]. ⚠ A Keychain item survives uninstall [D: Apple] | per app |

Hazards, ranked:
1. **The logs assume one process.** `DefaultEmbeddingStore` serialises with an in-process `Mutex` and
   keeps the map **and the append offset** in memory from construction (`DefaultEmbeddingStore.kt:42-46`)
   [C]. A second writer interleaves records, and the first process's memory goes stale [I]. ⚠ **The
   constructor already writes**: `strikes.scrub()` zeroes struck records (`:57-65`) [C]. So does the
   realm-wipe hook, `EmbeddingStore.clear()` + `FaceTemplateStore.clear()` in every route-1 process
   (`PlatformProxyModule.kt` iosMain `:350-364`) [C]. Today both are harmless only because the logs are
   empty (sweep §6.4.0 item 6) [D].
2. **B1 (P0f-b PR-1) is a writer** wherever a pass can run (§36) [D].
3. **The model store's bytes are shared and its index is not.** `sweep()` deletes only the paths in its
   own garbage list (`DefaultModelStore.kt:269-275`) [C], so Drive's process cannot delete Photos'
   models today. ⚠ But staging paths are shared, and `ModelStoreOwnership.SoleProcess` is iOS's claim
   *"because no runtime is contributed"* (`ModelStoreOwnership.kt:22-29`) [C]. PIO-7 makes that claim
   false for one process, so the claim has to become true by construction.

**Missing:** everything that would make Photos the only owner. §3.1 designs it.

### 1.6 The Models + consent screen

- **The view model exists, in the wrong module.** `ModelsViewModel` (473 lines) and `ModelsRepository`
  (579) live in `:control-shared` commonMain (`control-shared/.../screens/models/`), Control's
  **Compose** module [C]. iOS cannot reach that module. The VM itself is Compose-free: its imports are
  `androidx.lifecycle.ViewModel` and coroutines (`ModelsViewModel.kt:3-15`) [C].
- `ModelsRepository` reads two **proxy-local** holders, `LastIndexCoverage` and `ModelStoreAccess`
  (`ModelsRepository.kt:3-5`; both `control-shared-proxy/.../inference/`), plus Control's
  `SyncRepository` (`:14`) [C]. On Android, Control is the proxy, so it reads them in-process. Photos on
  route 1 is in-process too, so it can resolve the same singletons from its own graph [I].
- **The consent shape to keep** (§9.2, §32.4) [D]: two switches. The realm switch syncs and is written
  first on *on*. The device switch is written first on *off*. Writes are serialised. Every write ends in
  a re-read. A device answer is never written under a placeholder id. ⚠ Under route 1, every first-party
  app shares one `deviceId`, so "this device" means the phone, which is the right reading [I].
- **The copy to change:** today's no-consent sentence names *Control's* Settings → Models, which
  Control-on-iOS does not have (`photos-ios.md` §8.19) [D].

### 1.7 Index now

- **The op and the runner exist and are shared** [C]. `StartMediaIndexPass` goes to `MediaIndexRunner`,
  a process-wide single flight (`ProxyModule.kt:366-385`), behind `allowManualIndexing()` (§34). The iOS
  op factory already routes it (`PlatformProxyModule.kt` iosMain `:1237-1258`) [C].
- **Missing:** a button, conditions (§1.3), and a pass that tolerates suspension. A foreground pass is
  suspended a few seconds after the user leaves Photos. It resumes when they return, because the runner
  is in-process [I]. But a pass suspended **mid-read of the shared replica** is the `0xdead10cc` case
  that S0b-2's gate and quiesce step exist for (`ios-background-scheduling.md` §4.1.1 (b)) [D]. So
  v0.2 does not rely on resuming in place: the pass is cancelled on background (§3.3).
- ⭐ iOS 26's `BGContinuedProcessingTask` lets a user-started job keep running in the background with
  system progress UI [I: verify against the SDK]. That is *Index now*'s natural iOS shape. It needs an
  availability check, because the floor is 16 (PIO-D11).

### 1.8 Search lights up

`PhotosSurface.search` builds `DescriptionSearch.NotBuilt(device)` (`PhotosSurface.kt:183-190`) [C]. The
day Photos binds a backend it becomes `AskThisDevice`. The *"isn't available on this iPhone yet"* line
then gives way to the proxy's own answers: `NO_BACKEND`, `NO_TEXT_EMBEDDING`, `NO_CONSENT`,
`NOT_INDEXED`.

⚠ **Mia's refusals say "this device", which is wrong on iOS.** Mia runs in its own route-1 process,
which binds no backend (§3.1), so its `photosLike` answers `NO_BACKEND` first
(`PhotosLikeImplementation.kt:60-70`) and Mia says *"I don't have a model on this device…"*
(`AskMiaImplementation.kt:805-806`) [C]. Its `NOT_INDEXED` line, *"I haven't looked through your photos
on this device yet"* (`:807-808`), and its `NO_CONSENT` line, which names Control's Settings → Models,
are just as wrong there: Photos may hold a full index. iOS gets its own wording for all three, pointing
at Photos (PR-5). **Built in 5e-2** (§14.19.11): `MiaPhotoSearchHome`, a required parameter of
`miaServiceProxyImplementationFactory`, `PHOTOS_APP` in the iOS proxy module.

⚠⚠ **As built — the first device search hid every match (2026-11-09).** Photos on the maintainer's
iPhone, flag on, Core ML fp16 towers on CPU_AND_NE, 22 photographs indexed (5 cats, 17 of Sintra):
*"cat"* answered **no results**. A temporary diagnostic (reverted, never committed) printed each
score: the five cats at cosine **0.080–0.084**, the best non-cat 0.021, query and image vectors unit
norm — ranking perfect, towers fine. The floor was wrong: `SEMANTIC_RELEVANCE_FLOOR` still sat at
cosine 0.0915, carried over from another checkpoint. It is now **cosine 0.06**, measured on the
pinned checkpoint across Core ML, ONNX and fp32 (`inference-service.md` §35.1.1), and the device's
22 cosines are a test (`theIphonesFiveCatsAreFoundAndNothingElse`). ⭐ The host's Core ML towers
reproduce the phone's scores to 1.2 × 10⁻⁷, so a host measurement of the Core ML pair is a phone
measurement.

⚠ **As built — the idle line (2026-11-09).** The empty bar said *"Search by file name."* on that same
phone. It now says *"Search by file name or by what's in a photo."* only once source 3's last answer
was that it **ran** (`PhotoSearchCopy.idle`, `PhotoSearchUiState.semanticKnown`); every refusal, a
failure, `NotBuiltHere` and the unasked seed keep the plain line. iOS asks when the screen opens and
again whenever it is shown over an empty bar (`askOnOpen`, `screenShown`: on appearance and on the
return to the foreground, one question at most in flight), so a consent switched or an index finished
in Settings → Models reaches it untyped; the in-process proxy's own answer, refusals before any
embedding. Android and desktop learn from their first query. ⚠ The remembered answer is the idle
line's alone: an emptied bar resets the note's status, so a next query shows only its own refusal,
never a stale *"switched off"*. ⚠ A query cancels an opening question in flight; the Core ML text
tower's load is serialised by its lock anyway (`CoreMlInferenceBackend.embedText`), so the two never
load it twice.

---

## 2. The ORT linkage spike (2026-10-04) — history: the route not taken

⚠⚠ **Superseded by D1.** §2 and the ORT rows of §2.1 measure the ONNX Runtime route, which iOS does not
take. They are kept because they are measurements, and because they are why D1 was decided as it was:
the ORT CPU provider is a GO at 112 ms, and the ORT CoreML provider fails in both formats. Nothing in
§3–§5 builds on this section. The Core ML measurements are §2.1's last three rows and §2.2.

**Throwaway.** It is a standalone Gradle project in the session scratchpad. It is not in the repo, on
no branch, and nothing of it is committed. Kotlin 2.4.10, Gradle 9.7.1 and ORT 1.20.0 from the pod
archive below. The model was the pinned SigLIP vision tower, `vision_model_uint8.onnx` (100 MB, side 256).
The run was on an iPhone 16 simulator, iOS 18.5, on an arm64 Mac.

| Claim | Result |
|---|---|
| cinterop parses `onnxruntime_c_api.h` (218 KB) and calls through `OrtApi` | ✅ `CreateEnv` → `CreateSessionOptions` → `SetIntraOpNumThreads(2)`, `SetInterOpNumThreads(1)`, both `allow_spinning = 0` (D4) → `CreateSession` → `Run` → `pooler_output`, **768** floats [M] |
| The Kotlin references no ORT symbol | ✅ The `.def` names headers only. `OrtGetApiBase` was resolved with `dlsym`. Static framework `nm -u` count of `_Ort` = **0**. An iosArm64 release executable **links without ORT** (758 KB) [M] |
| ORT links into a K/N iOS binary | ✅ `-framework onnxruntime -lc++ -framework Foundation -framework CoreML`, plus `-u` for the two entry symbols, so dead-strip keeps them [M] |
| Size | ✅ +16.6 MB `__TEXT`; stripped 17.4 MB vs 0.58 MB; gzip 6.2 MB vs 0.19 MB [M] |
| CPU EP output | ✅ Cosine **0.9988** against host ORT 1.29 on the same synthetic input; 0.993 against the fp32 graph [M]. ⚠ **One** input, a deterministic ramp, not a photograph (`Spike.kt:93`): a linkage check, not a corpus-wide agreement figure |
| CoreML EP (`NeuralNetwork`) | ✅ Runs. Cosine **0.9986** against CPU EP: **a different vector** [M]. ⚠ Same single synthetic input |
| CoreML EP (`MLProgram`) | ❌ *"Failed to build the model execution plan … error code: -14"* on the simulator [M]. Unknown on a device |
| Peak RSS, one session + 3 runs | CPU **293 MB** (60 MB at start), CoreML **787 MB** [M]. Simulator accounting, so indicative only. ⚠⚠ It is `ru_maxrss`, a **process-lifetime high-water mark**, read after each EP in one process that ran the EPs in turn (`Spike.kt:25`, `:139-148`). So the CoreML figure may include the CPU run before it; it is an upper bound on CoreML alone, not a measurement of it. PR-0 runs each configuration in its own process |
| Timing | CPU 79 ms at 2 threads, 37 ms at default threads; CoreML 161 ms. ⚠⚠ **A Mac's numbers. They are quoted only to say they are meaningless** (§15.1's rule) |

**Not proven by this spike, and no longer owed:** the Swift→Kotlin pointer hand-off, the entry
symbols surviving an App Store strip, and ORT under `TenshuSdk`'s real build. PR-0 covered the
device (§2.1), and D1 removed the rest.

**Also measured for the ORT route** (moved here from v0.3's §1.1, so the figures are not lost):
- **The binary.** Microsoft publishes one iOS artifact under three wrappers: the CocoaPods `onnxruntime-c` / `onnxruntime-objc` pods, the `onnxruntime-swift-package-manager` SPM package and the raw pod archive. All three point at the same zip. ⭐ The zip `https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.20.0.zip` is 44 MB, sha256 `50891a8aadd17d4811acb05ed151ba6c394129bb3ab14e843b0fc83a48d450ff` [M]. It holds a **static** `onnxruntime.xcframework` (`ios-arm64` 34 MB archive; `ios-arm64_x86_64-simulator` 74 MB fat), the C/C++ headers and an MIT LICENSE [M]. v0.3 planned a pinned, checksummed fetch into `build/`, after the libsodium precedent (`core-common/build.gradle.kts:46-85`) [C].
- **How cinterop reached it.** ⭐ **Through one symbol.** ORT's whole C API is a function table: `OrtGetApiBase()->GetApi(ORT_API_VERSION)` returns `OrtApi`, a struct of function pointers. cinterop maps the opaque handles to `cnames.structs.*` and calls the fields with `.invoke` [M]. The one exception is the CoreML EP. In 1.20 the generic `SessionOptionsAppendExecutionProvider` rejects `"CoreML"` (*"Unknown provider name"*), so that EP needs its own top-level symbol, `OrtSessionOptionsAppendExecutionProvider_CoreML` [M].
- **What linking ORT cost.** ORT adds 16.6 MB of `__TEXT` to an iOS arm64 release executable. Stripped, that is 17.4 MB against 0.58 MB without ORT, and gzip -9 gives 6.2 MB against 0.19 MB [M]. ORT also needs `-lc++`, Foundation and CoreML.
- **Photos-only linkage was feasible.** The Kotlin needed only the headers: the static framework's
  `nm -u` count of `_Ort` was **0**, and the same Kotlin linked into an iosArm64 executable with no
  ORT at all [M]. Linked into the umbrella instead, ORT would have cost every route-1 app ~17 MB.

⚠ §2.1's verdict cites v0.3's §4.5 thresholds (500 ms, a throttle ratio of 2, a 30 % margin, 1.3 ×
for the thread cap). v0.4's §4.5 keeps the first two and drops the ORT-only ones.

⚠ `Spike.kt` lives in a session scratchpad, so the two `Spike.kt` citations above are to code that is
not in the repo.

### 2.1 PR-0 device go/no-go — results (2026-10-04)

Measured [M] on the maintainer's iPhone 17e (iPhone18,5, iOS 26.6.1), on the charger at 100 %, Low
Power Mode off. One fresh process per configuration (`devicectl --terminate-existing`): 10 warm-up
runs, then 200 timed runs on the spike's fixed synthetic input, then 3 JPEGs × 5. Timings are `Run` /
`prediction` only, without decode. `phys_footprint` is sampled every 2 ms and agrees with
`ledger_phys_footprint_peak` to within 1 MB. The app, the raw JSON and the conversion script are
throwaway and live in the session scratchpad. Nothing was committed.

| Configuration | open ms | median ms | p90 ms | first-50 / last-50 median | `phys_footprint` after open / peak (MB) | `thermalState` | cosine vs `cpu-d4` (synthetic; images) |
|---|---|---|---|---|---|---|---|
| `cpu-d4` (2 threads, no spin) | 152 | **112.3** | 118.5 | 101.6 / 118.3 | 217 / **237** | nominal throughout | — |
| `cpu-default` | 72 | 102.4 | 104.1 | 99.6 / 104.0 | 221 / 243 | nominal | 1.0000; 1.0000 |
| `coreml-nn` (ORT CoreML EP) | 3198 | 221.8 | 233.5 | 214 / 226 | 558 / **1162** | nominal | ❌ **all 768 outputs NaN**; 0.79–0.88 |
| `coreml-mlprogram` (ORT CoreML EP) | ❌ *"Failed to build the model execution plan … error code: -14"*, the same as on the simulator. The uint8 dynamic-quant graph does not survive MLProgram (unbounded dims on `*_quantized_cast_output`; a non-broadcastable `ios17.matmul`) | | | | — / 163 | | |
| ⭐ `coreml-fp16-converted`, `.all` | 2271 | **5.9** | 6.2 | 5.6 / 6.0 | 149 / 202 | nominal | 0.9928; 0.888–0.966 |
| `coreml-fp16-converted`, `.cpuAndNeuralEngine` | 2195 | 5.8 | 6.2 | 5.6 / 6.0 | 18 / 19 | nominal | 0.9928 |
| `coreml-fp16-converted`, `.cpuOnly` (control) | 171 | 20.5 | 21.3 | 19.7 / 21.1 | 17 / 23 | nominal | 0.9923 |

- **Why the image cosines are low.** That is the uint8 quantisation of the ONNX artifact, not a device
  defect. Reproduced on the host [M]: the uint8 ONNX against the fp32 PyTorch model on the same 3 JPEGs
  gives 0.965 / 0.956 / 0.893. On the device, `cpu-d4` matches host uint8 at 0.989, and the fp16 Core
  ML model matches host fp32 at 0.992–0.995 (1.000 on synthetic). ⚠ An fp16 artifact is therefore a
  **different vector space** from the uint8 one. A store is only ever built with one of them: iOS takes the fp16 artifact, and its digest is in the runtime fingerprint (§3.2).
- **The conversion.** From the local HF checkpoint `siglip-base-patch16-256-multilingual` (snapshot
  `8952a4ea…`, not yet checked against a catalog revision hash), via transformers
  `SiglipVisionModel` → `torch.jit.trace` → `ct.convert` (mlprogram, FLOAT16, iOS 17 target). 176 MB.
  coremltools 9.0 with torch 2.13 is an untested pairing, and it needed a one-line numpy-2 patch in
  `_cast`. `MLComputePlan` on the device: all 307 compute ops prefer the ANE, 100 % of the estimated cost.
  Xcode's performance report has no CLI and is owed in the GUI.
- **Not measured:**
  - sustained load or a run on battery (each run was ≤ ~25 s on a charger, so D5 is untested);
  - whether the ~2.2 s Core ML open is cached across launches;
  - the 562-photo corpus, HEIC and decode time (PR-1);
  - the text tower.

**Verdict: GO.**
- `cpu-d4`'s last-window median of 118 ms is well under §4.5's 500 ms, with a throttle ratio of 1.16
  against a limit of 2. D4's fallback is not triggered: `cpu-d4` is 1.10 × `cpu-default`, under 1.3 ×.
- ⚠⚠ **The ORT CoreML EP is out in both formats.** NeuralNetwork returns NaN, is 2 × slower than CPU
  and peaks at 1.16 GB; ORT placed 515 of 908 nodes on it, in 107 partitions. MLProgram does not build.
- ⭐ **Native Core ML fp16 is ~19 × faster than `cpu-d4`** (5.9 ms on the ANE) with a lower peak. That
  is far past §4.5's 30 % bar, so D1 now turns on whether a second, fp16 catalog artifact is worth
  dropping ORT on iOS (§7 D1).

### 2.2 PR-0b Core ML gate — results (2026-10-04)

Measured [M] on the same iPhone 17e, on the charger, one fresh process per row. Throwaway, in the
session scratchpad; nothing committed.

**Text tower** (same checkpoint, same fp16 mlprogram conversion; 530 MB vs 280 MB for the int8 ONNX;
cosine 1.0000 against fp32 PyTorch):

| Compute units | median ms | p90 ms | `phys_footprint` after open / peak (MB) | `thermalState` | op placement (`MLComputePlan`) |
|---|---|---|---|---|---|
| `.all` | 4.0 | 4.2 | 380 / 397 | nominal | 280 ANE, 3 GPU, 1 CPU. The 250k-row embedding `gather` lands on the GPU, which is the 380 MB |
| ⭐ `.cpuAndNeuralEngine` | **2.8** | 3.1 | **12.6 / 13.1** | nominal | 280 ANE, 4 CPU |

⭐ **`.cpuAndNeuralEngine` is the setting to ship for both towers**: the same speed (vision 5.8 ms,
§2.1) at a fraction of the footprint (vision 19 MB, text 13 MB).

**The open cost is cached, per compute-unit setting** (each a fresh process, straight after a reinstall):

| Launch | vision open ms | text open ms |
|---|---|---|
| `.cpuAndNeuralEngine`, 1st | 2204 | 3539 |
| 2nd / 3rd | 87 / 38 | 52 / 24 |
| `.all`, 1st / 2nd / 3rd | 2312 / 87 / 55 | 3930 / 88 / 75 |

⚠ A reinstall brings the slow first open back. Whether the cache survives a reboot or an eviction is
not measured.

**End-to-end retrieval — the gate.** The 562-photo IN-3m corpus is not on the build Mac, so the
corpus was 300 public COCO test2017 images, resized once on the host to 256² so every pair saw the
same pixels. 36 queries: 16 English, 12 German, 4 Japanese, 4 Russian. Tokenised as
`SiglipTextTokenizer` does (length 64, pad id 0; 16 of 16 of the repo's golden cases reproduce).
Against fp32 PyTorch on the host, mean / worst query:

| Pair | top-1 agreement | top-5 overlap | top-10 overlap | Spearman |
|---|---|---|---|---|
| uint8 vision + int8 text ONNX (host) — what Android ships | 23 / 36 | 0.750 / 0.20 | 0.772 / 0.50 | 0.9311 / 0.8874 |
| ⭐ fp16 Core ML, **on the iPhone** (both settings identical) | **35 / 36** | 0.994 / 0.80 | 0.997 / 0.90 | 0.9998 / 0.9998 |

Phone vectors match Mac Core ML vectors at cosine ≥ 0.99999 for every image and query. The ONNX
pair's worst image cosine against fp32 is 0.802; Core ML's is 0.9996.

**Verdict: PASS. D1's condition is met; Core ML is the iOS route.**

⚠ This table is the throwaway run: pad id 0, onnxruntime 1.29, the hand-patched export. The gate now
lives in the repo, and §9.6 has its figures for the export that would ship. They differ from these
on the ONNX row, for two measured reasons given there.

- ⚠⚠ **Sustained load reaches `serious`** (vision tower back to back, charger, ~125 inferences/s):
  from nominal, `thermalState` went to 1 at 75 s and to 2 at 245 s; the median drifted 7.0 → 8.5 ms.
  A second run three minutes later started already at 2. It never exceeded 2, and a real pass is
  bounded by decode, not inference, so this is an upper bound — but D5's thermal backoff is **owed
  for the foreground pass too**, not only for a wake.
- ⚠⚠ **A finding outside PIO-7: the text tokenizer pads with the wrong id on every platform.**
  `SiglipTextTokenizer` pads with `<pad>` = id 0 (`SiglipTextTokenizer.kt:33,145`) [C]; the
  checkpoint's `tokenizer_config.json` declares `pad_token` `</s>` = id 1 [C]. In fp32 the two
  paddings give text vectors at mean cosine 0.72 and the same top-1 image for only 6 of 36 queries;
  mean top-1 cosine is 0.050 with pad 0 against 0.089 with pad 1 [M]. An eyeball check of nine
  queries found pad 0 off-topic twice and pad 1 on topic at least eight times, which is not a
  labelled evaluation [I]. The gate passes with either padding. This is search quality on Android
  and desktop **until the pad-id fix**; it was its own fix, **now merged** (pad with `</s>`; `models.md` §3.9.4), and text vectors are
  not stored, so it clears nothing.
- **Checkpoint pin: cannot be checked from the repo.** `deploy/models/models.json` pins an id and
  paths, not an upstream revision. The published catalog's digests match the local ONNX exports
  byte for byte, and the local `model.safetensors` hash equals HF's for commit `8952a4ea…` [M], but
  nothing records that the shipped ONNX files came from that snapshot. The Core ML artifact's export
  script must record its source revision.
- **Not measured:** the real corpus, HEIC and decode time (PR-1); anything on battery; a realistic
  indexing duty cycle; survival of the open cache across a reboot.

---

## 3. Design

### 3.1 PIO-D10, built: four locks on one door

1. **The model.** A backend with no compiled model claims nothing (§3.2), and only Photos can ever
   hold one: only Photos contributes `{"coreml"}`, so every other graph's catalog filter is empty and
   offers no download (`ModelCatalog.kt:159`; `modelDeliveryComponents` defaults to the empty set,
   `ModelDeliveryComponents.kt:62`) [C].
   - ⚠⚠ **This lock is weaker than v0.3's.** There, only `photos-ios` linked ONNX Runtime, so no other
     binary contained a runtime at all. Core ML is a system framework and the backend's Kotlin is in
     the umbrella, so every app's binary can construct the backend. The linker no longer enforces
     anything; locks 2–4 and PR-3's test do.
2. **The graph.** Everything that runs or writes inference is bound **only** in
   `PhotosLocalRuntime.assemble(appOverrides = …)`. That covers `inferenceBackends`, `indexingConditions`,
   `supportedModelRuntimes` (`COREML_RUNTIMES = {"coreml"}`, §3.4; never `SUPPORTED_RUNTIMES`, which is
   `{"onnx"}` and describes `onnxMain`, `ModelCatalog.kt:62-72`; never `FaceModel.RUNTIME`, which needs
   MediaPipe) and the store file database. `appOverrides` is last-wins
   (`FirstPartyLocalRuntime.kt:72,86,140`) [C]. Calendar's reminder presenter set the precedent for "one
   app owns X on route 1" (`CalendarLocalRuntime.kt:42-49,106-117`) [C]. Every other process keeps
   `Never` and empty runtimes.
3. **The container.** The stores move out of the App Group:
   - A new `single<FileDatabase>(named("inferenceFileDatabase"))` in the common `ProxyModule`, defaulting
     to the shared `get<FileDatabase>()`, so Android and desktop do not change. It feeds
     `DefaultEmbeddingStore`, `DefaultFaceTemplateStore` and `modelDeliveryComponents`.
   - Photos overrides it with `IosFileDatabase(root = <Application Support>/inference)` (as built: `…/photos-private`, §13.1). That is the
     Photos-**private** container.
   - ⇒ `ModelStoreOwnership.SoleProcess` becomes true **by construction**: only Photos' container holds
     a store anything writes to.
4. **The store that is not Photos'.** Every other route-1 graph binds an **empty store that never
   writes**: no file opened, no `scrub()`, `clear()` a no-op, `size() == 0`.
   - ⚠⚠ Without this lock, lock 3 still leaves writers. Resolving an op that falls through to the
     inference factory constructs both logs (`PlatformProxyModule.kt` iosMain `:1237-1258`, eager
     `embeddings = get()`, `faceGrouping = get()`), and the constructor runs `strikes.scrub()`
     (`DefaultEmbeddingStore.kt:57-65`) [C]. The realm-wipe hook writes too (`:350-364`).
   - So "the other processes find empty App Group logs" is not enough: they would still open, scrub and
     clear files in a shared container.
   - With no backend in those graphs, `photosLike` answers `NO_BACKEND` before it reads the store (§1.8).

**What the move owes:**
- ⚠⚠ **Excluded from backup.** Application Support goes into iCloud and Finder backups. Vectors are
  derived content. Models are ~709 MB of public weights, more with their compiled copies (§3.4). Templates, later, are biometric (threat model
  A40). So the directory is marked with `NSURLIsExcludedFromBackupKey`, with
  `IosRegionBundleStager.kt:95` as the precedent [C]. The alternative, `Library/Caches`, is purgeable
  under storage pressure, which is §7 D7.
- ⚠⚠ **The model index outlives the bytes.** The index and garbage list are in the Keychain
  (`DefaultModelStore.kt:15,58`), and a Keychain item survives uninstall [D: Apple]. The bytes move to
  Application Support, which does not. `pathOf` reads the index and never checks that the file exists
  (`:283-285`) [C]. So after a reinstall the store reports a model installed whose bytes are gone, and the
  backend's open fails on every image [I]. `Library/Caches` (D7's alternative) has the same split under
  a storage purge.
  - **PR-3 moves the index and the garbage list into the private `FileDatabase`, beside the bytes**, so
    both halves live and die together. ⭐ Recommended.
    - ⚠⚠ As built, the file index can say *I could not read this*, which the Keychain item never
      had to: an index that is there and gives no bytes is **unreadable, never empty** (§13.7).
  - The other option is to check on the first read that every indexed file exists and drop the records
    that fail. It is a repair, not a fix: the index and the bytes can still diverge in between.
- ⚠⚠ **A wipe Photos did not see.** Today the realm-wipe hook clears the stores in the process that
  observes the wipe (`PlatformProxyModule.kt` iosMain `:350-364`) [C]. With the stores private, a reset
  made from Control while Photos was not running leaves Photos' vectors for the **old** realm on disk.
  That is §34.6's cross-realm answer, and for faces it is A40 [I].
  - **Rule:** the private store carries a stamp of the realm it was built for, in a sidecar file in the
    private `FileDatabase`.
    - **Now:** the realm's owner id.
    - **After S0b-1:** plus the shared store's **`meta.epoch`**. ⚠ S0b moved to SQLite
      (`ios-shared-store-sqlite.md` §3, §6): the file store's `replica.generation` file and its random
      `instance` half are superseded, and a random `meta.epoch`, rewritten in the transaction that
      **replaces** the store (a wipe, a migration), is what now says *"this is not the store you
      opened"*. Not `meta.seq`, which moves on every write. PR-3 lands the owner-id half; the epoch
      half **depends on S0b-1** — ✅ built 2026-11-10 (§13.2). Until then a same-owner reset is not seen by the stamp, and since
      vectors and `EmbeddingStore.visited(path)` are path-keyed, a photo recreated at the same path
      could be skipped as already indexed.
  - **Checked at four points**, not only at install, because a reset can land while Photos is resident
    but suspended, with vectors in memory and a pass running:
    - at `PhotosLocalRuntime.install`, before the backend is bound;
    - on every return to the foreground;
    - at the start of every pass;
    - before every `photosLike`.
    - ⚠ The real fence for a resident process is the store's own (W18 in `ios-scheduler-lease.md:661`, carried into the SQLite design as S0b-3's `replaced` subscription, `ios-shared-store-sqlite.md`): a fenced handle
      reads nothing and its runtime is rebuilt. It is **not built**. Until it is, these four checks are
      the fence.
  - **On a mismatch:** cancel the running pass, then clear the **vectors and the face templates**.
    ⚠ Never the model store or the compiled models (§3.4): they are public weights, the same for
    every realm, and clearing them costs a ~709 MB re-download and a recompile.
  - ⚠⚠ **The stamp is written only after both clears return.** A clear that throws stops there:
    `clear()` fails loudly and re-reads memory from the file (`DefaultEmbeddingStore.kt:212-231`) [C].
    At install, Photos then **does not bind the backend**. A crash between the clears and the stamp
    leaves the old stamp, so the next launch repeats the clear. Repeating a clear is harmless.
  - Same-owner deletions need no stamp. `photosLike` and People read through the `livePaths` filter
    (`ProxyModule.kt:261-265`, `PhotosLikeImplementation.kt:92`) [C], so a deleted or trashed photo
    drops out on the next read.
  - `deprovision()` clears the vectors and templates too. Today it purges custody and leaves inference
    alone (`PhotosLocalRuntime.kt:150-163`) [C].
- ⚠ **Retire the shared registry item.** Remove `registerMediaIndexSchedule()` from the iOS
  `PlatformProxyModule` (`:763`). Photos then drives passes directly (*Index now* and its processing wake,
  §3.3). Today one realm-wide row is dispatched by every route-1 process's drain loop and deferred by all
  of them. Once Photos has conditions, a shared row with N deferring dispatchers is the cross-process
  generation question P4 left open for iOS (sweep §6.2, *Limit: iOS route 1*) [D]. Photos can drive the
  pass without the row.
- ⚠ **On-upload indexing (sweep D3/P6b) cannot run on iOS.** Uploads happen in Drive's process (PIO-D5),
  and Drive may not index (PIO-D10). On iOS the derive hook must be absent and Photos' pass is the
  catch-up. Record it on P6b.

### 3.1a Open question: other iOS apps and the model (recorded 2026-10-13; not designed, not built)

**The maintainer's stance (2026-10-13).** The Photos-only model, and background processing
arriving later (5e's foreground *Index now* first, scheduling after), are **accepted for now**.
A shared version, in which other iOS apps can use the model or its results, is expected to be
needed at some point, and is to be designed **when that need arrives, not before**. So this is
an open question with an expected direction, not a rejected alternative. The same day the
maintainer settled `photos-ios.md` §7 Q2: the Models screen stays in Photos.

**As built** ([C] read in the code, [D] decided in a doc, [I] inferred):

- **(a) Everything of the model is in Photos' private container.** The archives, the compiled
  towers, readiness's state and verdict, the embedding log and the index are bound only in
  `PhotosLocalRuntime.assemble(appOverrides = …)`, under Photos' backup-excluded private
  container; only Photos contributes `{"coreml"}`, so no other graph is offered a download; every
  other app binds a store that never writes (§3.1's four locks; `photos-ios.md` PIO-D10) [C].
- **(b) So no other iOS app can load the model or read Photos' vectors.** Another app's process
  binds no backend, and `photosLike` checks for one before it looks at a store, so it answers
  `NO_BACKEND` (`PhotosLikeImplementation.kt:65-70`, `photos-ios.md` §3.3) [C]; Mia then says its
  refusal line (`AskMiaImplementation.kt`, the `NO_BACKEND` branch) [C]. Nothing crosses
  processes on iOS: route 1 is one in-process proxy per app, with no service another app can
  call (`ios-first-party-route-1-migration.md`; AGENTS.md, *Cross-app open*: "there is no channel
  between two apps") [D].
- **(c) What reaches other apps and devices is synced rows only.** The pass's `MediaLabel`
  marker syncs; a vector does not (`EmbeddingStore.kt:56`, IN-D5) [C]. Vectors, the index, the
  preflight verdict and readiness are device-local derived data [C]. ⚠ Labels are **inert**
  today (`inference-service.md` §37: empty vocabulary, no backend claims tagging), so in practice
  nothing a model produced reaches another app yet. Face grouping does not run on iOS: no face
  backend exists there, and the feature is not offered (`OnDeviceModelsCapabilities`) [C].
  → The plan to change that, and which face rows sync: [`ios-face-grouping.md`](ios-face-grouping.md).
  ⚠ Not verified here: which face rows (the names a user typed) are synced.
- **(d) When a file is indexed on iOS.** Only by Photos' own pass: 5e's foreground *Index now*,
  a `BGProcessingTask` later (§14.18.1, `photos-ios.md` §3.2). Not at upload and not by Drive:
  Drive's process binds no backend (lock 2) [I]. So a file Drive or Files added is indexed when
  Photos next runs a pass [I].
  ⚠ **Amended 2026-11-11 by `inference-service.md` IN-D6a:** that is today's behaviour, not the
  intended one. A small batch of new arrivals is indexed while Photos is in the foreground,
  without a press (**PR-6b**); the backlog waits for PR-6's overnight wake or *Index now*.
- **(e) iOS only.** On Android and desktop Control holds the models and the index for every app
  (§14.18.0).

**The options, none chosen**

| # | Option | What it would need first, or cost |
|---|---|---|
| 1 | Each app its own copy | ~2.58 GB per app (§3.4). Impractical |
| 2 | The stores in the App Group, behind a cross-process lock | The lock (PIO-D10 exists because there is none), and it reopens the suspension-kill class: a file lock held in a shared container when the app is suspended (`0xdead10cc`) |
| 3 | One writer (Photos); finished, immutable compiled towers and / or a read-only index that other apps read, holding no file lock | Open: what a reader sees during an update or a removal, and how it learns that a model exists |
| 4 | Other apps consume **results** only, as synced rows written by Photos' pass (labels, people) | The label work (`inference-service.md` §39), and a decision on what is synced |
| 5 | A cross-app query that Photos answers | App-to-app is not available in the background on iOS |

**What today's design already leans on:** option 4, for anything that is a synced row; it is
empty in practice until labels are not inert (c).

**What makes a later move cheaper or harder** [I]:

- Nothing outside Photos should come to depend on the private-container paths: a path another
  app or an extension reads is what a move would have to keep stable.
- A result meant for other apps should be a synced row, not a file in Photos' container.
- The single-owner rule is enforced by bindings and a test, not by the linker (§3.1, lock 1):
  option 3 changes which bindings are allowed, not what can be linked.

### 3.2 The backend: `CoreMlInferenceBackend`

**The seam: Kotlin/Native over `platform.CoreML`, not a Swift implementation passed in.**
- **Where:** `inference-service-proxy/src/iosMain/…/coreml/`. A new `iosMain` source set; the module's
  iOS targets compile `commonMain` alone today (`inference-service-proxy/build.gradle.kts:88-90`) [C].
- **What K/N gives:** the Kotlin 2.4.10 distribution ships `platform.CoreML` for `ios_arm64`, and its
  klib carries every symbol this needs: `MLModel`, `MLModelConfiguration` / `setComputeUnits`,
  `MLComputeUnitsCPUAndNeuralEngine`, `MLMultiArray` (`dataPointer`, `getBytesWithHandler`),
  `MLDictionaryFeatureProvider`, `predictionFromFeatures`, `compileModelAtURL` [C: symbol search in the
  klib]. ⚠ The exact Kotlin signatures (the `NSError` out-parameter, the completion-handler form of
  `compileModel`) are [I] until PR-1 compiles them.
- **Why Kotlin, not Swift:**
  1. **The guards stay beside their twin.** The parts that are silently wrong when they drift (latch,
     shape guard, `Unreadable` vs `Failed`, the lane) are Kotlin in `OnnxInferenceBackend`. A Swift
     backend would be a second copy in a second language, and nothing in the gate compiles Swift.
  2. **It is testable where the gate looks.** The iOS lane compiles `iosMain` and `iosTest`;
     `./gradlew iosTestCheck` runs them on the simulator. A Swift backend is covered only by
     `scripts/ios-smoke.sh`.
  3. **No bridge on the hot path.** A Swift implementation would receive a `KotlinFloatArray` of
     196 608 floats per image through the ObjC export and hand one back [I: per-element boxing cost not
     measured]. In Kotlin the pixels are copied once into the `MLMultiArray`'s buffer.
  4. **It is how this repo already builds iOS seams:** Kotlin over the platform library
     (`IosBackupEnvironment.kt` over `UIDevice`, `IosRegionBundleStager.kt` over Foundation,
     `IosFileDatabase.kt`) [C]. No seam in `tenshu-sdk-ios` is a Swift object implementing a Kotlin
     interface [C: none found]. v0.3's `OrtEntry` hand-off existed only to carry two C symbols past the
     linker; Core ML is a system framework and needs no hand-off.
- **What Swift still does:** nothing for the backend. Photos' Swift keeps the UI and the
  `BGTaskScheduler` / `URLSession` drivers (§3.3, PR-5).

**Three classes** (PR-1 builds the first two, PR-2 the third):

| Class | Job |
|---|---|
| `CoreMlModelCache` | Verified archive → compiled model on disk (§3.4): unpack, `compileModel`, move, marker. Answers *"where is the compiled `<role>` for this archive digest, if it is ready?"* |
| `CoreMlSession` | One loaded `MLModel` behind a small port (`CoreMlSessionPort`), so the backend is testable with a fake. Load with the configuration below, read the model description, predict |
| `CoreMlInferenceBackend` | The `InferenceBackend`: decode, preprocess, predict, normalise, stamp. Same contract as `OnnxInferenceBackend` |

**Same contract as `OnnxInferenceBackend`**, and the guards must not drift:
- `supports` is false while there is no model (`OnnxInferenceBackend.kt:112-113`). On iOS *"there is a
  model"* means the cache's **ready marker** for the installed digest exists, not that the archive is
  installed: a store record with no compiled model must not be claimed. And it means the readiness
  state below says *ready*.
- the latched rejection (`:100-101`). ⚠ On iOS the latch is **keyed to the compiled path**. The ONNX
  one is never reset, which is right while an artifact can only change across a restart; here an
  artifact can be replaced in a running process, and a latch left over from the old one would refuse
  the new one;
- image work on `MediaWorkBudget.lane`, text off it on `Dispatchers.Default` (`:73-77`, `:156`);
- one session per path, reopened on a path change (`:181-206`);
- `Unreadable` only for a decoder refusal, `Failed` for everything else (`:118-141`);
- every vector normalised once (`:140`, `ImagePreprocessing.kt:95-100`);
- stamped with `VisionModel.PinnedSigLip.provenance`, the same stamp as Android (§3.4, D13) [C].

**The shape guard, in Core ML's terms.** `MLModel.modelDescription` gives the same facts ORT's
session info gives (`OnnxInferenceBackend.kt:246-273`) [C]:
- vision: an input named `pixel_values`, a Float32 multi-array constraint of shape `[1, 3, 256, 256]`;
  an output named `pooler_output`;
- text: an input named `input_ids`, Int32, shape `[1, 64]`; an output named `pooler_output`;
- at run time, a pooled output whose count is not 768 is a different model, not a bad photo (`:280`).
- ⚠ Those names and types exist only because the export script sets them (§3.4). A traced model
  converted without explicit names gets generated ones [I].
- ⚠ Extract the pure halves into `commonMain`: shape acceptance over `(inputShape, outputNames)` and
  the output-width check. Both backends then call one copy. Touching `onnxMain` makes this an
  Android-compiled change (smoke).

**Configuration: `.cpuAndNeuralEngine` for both towers, a build constant.**
- §2.2 measured it: the same speed as `.all` (vision 5.8 ms, text 2.8 ms) at 19 MB and 13 MB instead
  of 202 MB and 397 MB [M].
- It is never chosen at run time. Retrieval was identical under both settings in §2.2 [M], but a
  constant keeps the vector space a property of the build.
- ⚠ `ModelProvenance` has no field for it (`ModelProvenance.kt:19-31`) [C], and adding one would
  reshape a stored stamp (HR 9). It goes in the runtime fingerprint below instead.

**Image preprocessing: the shared arithmetic, unchanged.**
- `IosImageDecoder` (§1.2) returns packed ARGB of exactly `256²` entries, **squashed, not cropped**:
  `Profile.SIGLIP` has `centreCrop = false` and `mean = std = 0.5` (`ImagePreprocessing.kt:32-36`), and
  the side comes from `VisionModel.PinnedSigLip` (`VisionModel.kt:55-66`) [C].
- `ImagePreprocessing.preprocessPixels` turns that into CHW floats in `[-1, 1]`
  (`ImagePreprocessing.kt:65-86`) [C], and the backend copies them into a Float32 `MLMultiArray`.
- ⚠⚠ **The Core ML model must take that tensor as it is.** The export uses a plain tensor input,
  never `ct.ImageType`: an image input would let Core ML resize and scale by its own rules, and the
  shared arithmetic, the half that is silently wrong, would be bypassed (§3.4).
- What differs from Android is the resampler and the EXIF orientation (§1.2), as on every platform
  (`ImagePreprocessing.kt:51-61`) [C].

**The text half reaches iOS in PR-2.**
- `SiglipTextTokenizer` and `UnigramVocabulary` move from `onnxMain` to `commonMain`. Their JVM
  imports are `java.text.Normalizer` (`SiglipTextTokenizer.kt:3`) and `java.io.DataInputStream` /
  `java.io.File` (`UnigramVocabulary.kt:3-4`) [C]. The tokenizer's own note already plans this move
  (`SiglipTextTokenizer.kt:22-24`) [C].
  - NFKC goes behind `expect fun nfkc`: `java.text.Normalizer` on JVM/Android,
    `precomposedStringWithCompatibilityMapping` on iOS.
  - The vocabulary reader takes bytes (3.1 MB, read through `FileDatabase`) instead of a `File`.
  - `Charsets.UTF_8` calls become `encodeToByteArray` / `decodeToString`.
- ⚠⚠ **PR-2 builds on the pad-id fix, not on today's master.** That fix makes the tokenizer pad with `</s>`
  (id 1), the id the checkpoint was trained on; master pads with `<pad>` = 0
  (`SiglipTextTokenizer.kt:33`) [C]. §2.2 measured what that costs. The move to `commonMain` starts
  after the pad-id fix merges, and the golden token ids it regenerates (`SiglipTextTokenizerTest`) move to
  `commonTest` so iOS runs the same cases.
- ⚠ The vocabulary is a 250 100-entry `HashMap<String, Int>` (`UnigramVocabulary.kt:54`) [C]. Its
  footprint under Kotlin/Native is not measured [I], and it may be larger than both models' 32 MB.
  PR-2 measures it.
- The tokenizer yields 64 ids; the backend writes them into an Int32 `MLMultiArray` of shape `[1, 64]`.

**Opening a model costs seconds once, and that cost stays off the main thread and off a search.**
- The first open after an install is 2.2 s (vision) and 3.5 s (text); later opens are 24–88 ms; a
  reinstall brings the slow one back (§2.2) [M]. Whether a reboot or an OS update does is [I].
- **Paid at prepare time.** The Models screen's *Preparing* step (§3.4) ends by loading both models
  once and dropping them. The OS's specialised copy then exists before the user can search or index.
- **Warmed at launch.** When Photos starts with a ready model and both consent switches on, it opens
  the text model on `Dispatchers.Default` in the background (as built, §14.6). A query that arrives first suspends on
  that same open; `embedText` is a suspend function and is never called on the main thread
  (`OnnxInferenceBackend.kt:156`) [C].
- The vision model opens on the media lane at the first image of a pass, which is already background
  work.
- **Memory.** With `.cpuAndNeuralEngine` both models can stay resident together: 19 MB + 13 MB [M].
  ⚠ That supersedes v0.3's rule that the text session never sits beside the vision session. That
  rule was written for the 280 MB ONNX text session. Both are still dropped on a memory warning and
  when Photos is backgrounded with no pass running, because reopening is cheap once cached.
- Whether `com.apple.developer.kernel.increased-memory-limit` is needed is **open** (§7 D10, reopened
  2026-10-07): no measurement has both sessions open in one process beside the app.

**Readiness: a live state the backend consults per call, never a graph change.**

Photos binds the backend and the `AskThisDevice` route once, at assembly, on a phone that clears the
static gates (iOS 17+, the memory threshold, the build flag; §7 D12). Those facts cannot change inside
a process. Everything that can change is one state, `AiReadiness`, a single in Photos' graph:

| State | Means | `supports` |
|---|---|---|
| `NoModel` | no install record | false |
| `Preparing` | installed; no `ready` marker for the installed digests yet (§3.4) | false |
| `AwaitingVerdict` | marker present; no persisted preflight verdict for the current fingerprint | false |
| `Ready` | marker present and a persisted **pass** for the current fingerprint | **true** |
| `Unsuitable` | a persisted **fail** for the current fingerprint | false |

- **Why this works with the existing seams, read in code** [C]:
  - `InferenceBackendRegistry` copies the backend *list* once, but asks `supports` on **every**
    `backendFor` (`InferenceBackendRegistry.kt:18-21`). Nothing caches the answer:
    `available` and `isAvailable` recompute it (`:23-27`).
  - `photosLike` calls `backendFor` first on every query and refuses `NO_BACKEND` on `null`
    (`PhotosLikeImplementation.kt:65-70`).
  - `StartMediaIndexPass` asks `registry.isAvailable` and refuses `NO_BACKEND`
    (`StartMediaIndexPassImplementation.kt:53-59`); the indexer resolves its backend at the start of
    every pass and skips with `skippedNoBackend` when there is none (`MediaIndexer.kt:376-377,390-392`).
  - The consent check asks the registry per call too (`ProxyInferenceConsent.kt:95`).
  - It is how `OnnxInferenceBackend` already behaves: its `supports` re-reads `locate()` on every
    call, so a model that arrives after the proxy started is picked up without a restart
    (`OnnxInferenceBackend.kt:55-58,112-113`).
- **So the indexer needs no second gate.** *Index now* and a wake both reach the backend through the
  registry, and an unready backend is not there. `IosIndexingConditions` stays about power and heat.
- **How `supports` reads it.** A volatile in-memory snapshot, because `supports` is on the per-image
  path. The persisted verdict is the truth; the snapshot is loaded from it at assembly and rewritten
  by the four writers below. The Models screen observes the same state as a flow.
- **The writers, all in Photos' process:**
  1. the prepare step, when it writes the marker (→ `AwaitingVerdict`) and starts the preflight;
  2. the preflight, when it persists its verdict (→ `Ready` or `Unsuitable`);
  3. the model store's commit or removal of the checkpoint (→ `Preparing` or `NoModel`);
     ⚠ **A removal also evicts the compiled cache.** The delete operation removes the `ModelStore`
     record and knows nothing of `CoreMlModelCache`, whose directories are otherwise dropped only
     after a *different* digest is prepared — so a deleted model would leave its ~709 MB `.mlmodelc`
     behind, invisible once the row is gone. The transition to `NoModel` closes the sessions, then
     deletes `inference/coreml/<digest-tag>/`; and because that can be interrupted, the cache also
     sweeps at launch: any compiled directory whose digest no install record names is removed.
  4. the fingerprint check at launch and on every return to the foreground (a new OS build or
     constant → `AwaitingVerdict`, and the preflight runs again).
- **The preflight runs outside the registry**, on `CoreMlSession`s it opens itself from the cache's
  paths, so it can run while `supports` is false.
- ⚠⚠ **A change of state is ordered, because one stamp covers every artifact.** The old and the new
  archive share a `ModelProvenance` (§3.4), so the embedding store would accept a new artifact's
  vectors beside the old one's. Leaving `Ready`, for any reason:
  1. flip the snapshot, so `supports` is false;
  2. cancel a running pass (`MediaIndexRunner.cancelRunningPass()`, `MediaIndexRunner.kt:148`) [C]. A
     pass that reaches the backend first gets `Failed`, which the indexer latches for that pass and
     never remembers against a photo (`MediaIndexer.kt:540-543`) [C];
  3. close both sessions (below).

  Entering `Ready`: apply the fingerprint rule first (a changed vision digest clears the vectors),
  persist the verdict, and flip the snapshot last.
- ⚠ **A replacement is not current until it passes.** The store commits the new archive (verified
  bytes), and from that moment the state is `Preparing`: the old artifact is no longer served, because
  the store has already declared its files garbage for the next process's sweep
  (`DefaultModelStore.kt:29-39`) [C]. The new one is served only after its own prepare and its own
  passing preflight. A failing replacement leaves `Unsuitable`, at once, with nothing rebuilt.
- **Sessions live in the backend, and the backend owns closing them.** One `CoreMlSession` per tower,
  keyed by the compiled path, behind the same mutex that guards a prediction on that tower
  (`OnnxInferenceBackend.kt:181-206` is the twin) [C]. They are closed when the state leaves `Ready`,
  and a call after an artifact change finds a different path and opens the new model. A prediction in
  flight finishes under the mutex before the close.
- **What search shows.** The proxy's answer does not change: an unready backend is `NO_BACKEND`. Under
  `AskThisDevice` that reason reaches Photos as `SemanticSearchStatus.Unavailable("noBackend")`
  (`TenshuPhotosRepository.kt:1543`) and reads *"…needs a model on this device, and there isn't one
  yet."* (`PhotoSearchLayout.kt:170-171`) [C]. That is right for two states and wrong for one:

  | State | Line under the search bar | How |
  |---|---|---|
  | static gates fail | *"Search by description isn't available on this iPhone yet."* | `DescriptionSearch.NotBuilt`, as today; the proxy is never asked |
  | `NoModel`, `Preparing`, `AwaitingVerdict` | *"…needs a model on this device, and there isn't one yet."* | the existing `noBackend` line; the Models screen shows the download, *Preparing* or *Checking* |
  | `Unsuitable` | *"Search by description isn't available on this iPhone yet."* | the view model maps a `noBackend` refusal to `SemanticSearchStatus.NotBuiltHere(device)` when readiness says `Unsuitable` |
  | `Ready` | none; the query runs | — |

  - The mapping is one defaulted seam on `PhotoSearchViewModel`: a function it calls when a
    `noBackend` refusal arrives, which on iOS reads `AiReadiness`. The view model keeps capturing
    `DescriptionSearch` at construction (`PhotoSearchViewModel.kt:99,110-111`) [C]; nothing about it
    is rebuilt.
  - **No new status and no new refusal reason.** `NotBuiltHere` already carries the right sentence
    (`PhotoSearchLayout.kt:165`) [C], and the proxy's reasons stay the four it has.
  - ⚠ The `noConsent` line names Control's Settings → Models (`PhotoSearchLayout.kt:167-169`) [C].
    PR-5 already owes Photos' wording for it (§1.8).
  - The Models screen, not the search line, says *why* a phone is `Unsuitable` and offers to delete
    the weights.

**The runtime fingerprint.** A device-local sidecar beside the realm stamp (§3.1), never synced, so it
owes no HR 9 bump. It records what the stored vectors were made with:

| Part | A change means |
|---|---|
| The **vision archive's sha256** (from the install record) | Different weights or a different conversion. **Clear the vectors**, with the realm stamp's ordering: clear first, write the sidecar after |
| The **text archive's sha256** | Nothing for the store: text vectors are not stored. It re-runs D12's preflight |
| The **compute-units constant** | Run the probe check below |
| The **OS build** (`NSProcessInfo.operatingSystemVersionString`) | Run the probe check below |
| A **preprocessing revision**: an integer constant beside `IosImageDecoder`, bumped by hand whenever the decoder, its resampler or orientation handling, or `ImagePreprocessing` changes what the model is fed | An app update can change the input transform without touching the archives, and `ModelProvenance` is only the model id and version (`VisionModel.kt:40`) [C], so the store would otherwise keep old vectors beside new ones made differently. **Clear the vectors.** ⚠ A hand-bumped constant decays, so PR-2 pins it with a golden test: a fixed image's preprocessed tensor digest, which fails when the transform moves without the bump |

- ⚠⚠ **Does an OS update change the vectors? Not known** [I]. Core ML compiles the model for the
  device, and a new OS can ship a new compiler for the Neural Engine. The only evidence is across
  hardware, not across OS versions: phone and Mac vectors agree at cosine ≥ 0.99999 (§2.2) [M].
- **The probe check.** At the first indexed image the sidecar also stores the vectors of a few fixed
  inputs generated in code. After an OS-build or compute-units change, the backend embeds them again:
  - every cosine ≥ a threshold: keep the store and rewrite the sidecar;
  - otherwise: clear the vectors and re-index.
  - ⚠ The threshold is not set. It needs the measurement below. Until then the rule is the safe one:
    **any OS-build change clears the vectors**. Photos re-indexes at ~37 ms a photo, decode included [M: §9.7, camera originals].
  - ⚠ Synthetic probes may not move the way photographs do (§2's own caveat about one synthetic
    input) [I].
- **Owed:** PR-1's harness keeps its per-image vectors for the corpus on the phone. After the next
  iOS update the maintainer re-runs it, and the per-image cosines across the update decide the
  threshold, or decide that an update never needs a clear.
- ⚠⚠ **D12's preflight verdict is keyed on a second, wider key, not on this sidecar** (as built,
  §14.2). Two keys with different inputs:
  - the **runtime fingerprint** above decides whether stored vectors survive;
  - the **preflight key** decides whether a verdict stands. It is the fingerprint, **plus** the
    vocabulary's digest, a hand-bumped `PREFLIGHT_REVISION` for the code a pass vouches for
    (tokenizer, vector guards, the preflight itself), and the app build.
  So a new artifact, a new constant or a new OS build re-runs the preflight, and so does a new
  vocabulary or a bumped revision, neither of which clears a vector (§7 D12).

⚠⚠ **The device floor is not the measured phone.** AI-L is gated in three steps (§7 D12). Two are
static and decided at assembly: iOS 17 or later, and `ProcessInfo.physicalMemory` at or above a
measured threshold. Below either, nothing is bound, search keeps `DescriptionSearch.NotBuilt` (§1.8)
and the Models screen offers no download. The third, the persisted runtime preflight, is the live
readiness state above.

### 3.3 Scheduling on iOS

| Path | When | Gate | Ends |
|---|---|---|---|
| *Index now* (foreground) | user presses it in Photos' Models screen | `allowManualIndexing()`: not Low Power Mode, battery ≥ 20 % or charging, thermal < `.serious` | completes, **pauses for heat** (below), or is **cancelled** on `didEnterBackground` (below); the next press continues from the store |
| *Index now* continued (iOS 26+) — **PR-7, built (§15)** | user leaves the app mid-pass | `BGContinuedProcessingTask` [C: the iPhoneOS 27.2 SDK's headers] | system UI shows progress; expiration cancels at the next photo |
| Processing wake — **PR-6, built (§16)** | iOS grants `app.tenshu.photos.processing` (charging + idle) | `allowIndexing()` true **only** inside the wake, and there not at `.serious`, in Low Power Mode or off the charger (`scheduledIndexingBlock`) | the pass's end, or the expiry: the wake's job is cancelled, which cancels the runner's scheduled pass at its next photo; the task completes once it has stopped. The pass is resumable (the store is the cursor), so the next wake continues |

- ⭐ **S0b-2 is met** (`ios-shared-store-sqlite.md` B.22, B.23): foreground indexing, continued
  processing (PR-7) and the overnight wake (PR-6) are all built, behind the AI flag.
- ⚠⚠ **Cancel on background, never suspend mid-pass.** On `didEnterBackground`, Photos opens a
  `beginBackgroundTask` window, calls `MediaIndexRunner.cancelRunningPass()` (`MediaIndexRunner.kt:148`)
  [C], and ends the window once the pass has stopped. A pass suspended mid-read of the shared replica is
  the `0xdead10cc` case (§1.7). Nothing is lost: the store is the resume cursor, so the next *Index now*
  skips what is already indexed.
  - ⚠ **PR-7 changes who owns the pass.** Once a `BGContinuedProcessingTask` is running for it, this
    hook must **not** cancel: the pass belongs to the task's handler, and only that task's expiration
    cancels it. The cancel stays for a pass with no live continued task (iOS < 26, the request was
    refused, or the task already expired).

- ⚠⚠ **Thermal backoff belongs to the foreground pass, from PR-5, not only to a wake.** §2.2 reached
  `serious` on the charger with predictions back to back.
  - The gate is already consulted before a pass and again per image (`MediaIndexer.kt:394,452`) [C],
    so once `IosIndexingConditions` reports thermal ≥ `.serious` as a no, a pass stops at the next
    photo. The store is the cursor, so nothing is lost.
  - The Models screen says why it stopped (*"Paused — iPhone is warm"*), not *"finished"*.
  - While Photos stays in the foreground, its driver starts the pass again when `thermalState` falls
    back to `.fair` or below (`NSProcessInfoThermalStateDidChangeNotification`).
  - **Chunked.** §15.12.2's finding is that accumulated work, not temperature, predicts the throttle,
    and that recovery is fast [D]. So a pass rests after N photos rather than running until it is hot.
    N and the rest come from PR-1's duty-cycle run (§4.5).
- **Shape of the wake:** a thin Kotlin entry, `PhotosLocalRuntime.runProcessingWake(wakeId, task)`
  (as built, §16.3 decision 1: no `deadline`, since a `BGProcessingTask` states none). Swift
  calls it from the task's launch handler, registered in `App.init` before launch finishes
  (`ios-scheduler-lease.md` §7.2) [D]. It is shaped as S2's `run(WakeReason.PROCESSING)` handler, so S1c
  can replace the Swift driver one-for-one.
- ⚠⚠ **It holds the shared store's task token around shared-store access** (B.23,
  `SharedReplicaEntries.protectTask`). Built (§16.2).
- **The wake reuses PR-5's heat stop** (there is no chunking: decided 2026-10-23, §14.19.8), and yields
  on `.serious` rather than running to expiry: the pass stops at its next photo and the wake ends. Both
  models are dropped when the wake ends.
- ⚠ **A wake must be requested to happen.** The `BGProcessingTaskRequest` is submitted when Photos
  leaves the foreground with work left, and again at the start of every handler, because a grant is
  one-shot (`drive-ios/Sources/BackgroundSync.swift:27-42` is Drive's precedent) [C].

### 3.4 The Core ML catalog artifact

**What it is.** The same checkpoint as Android's, exported a second time: two fp16 ML Program
`.mlpackage` towers (vision 176 MB, text 530 MB, §2.1/§2.2 [M]) plus the same 3.1 MB vocabulary. It is
a **second catalog entry** under a new runtime string, `coreml`, and `supportedModelRuntimes` selects
it: `usableModels` drops every entry whose runtime a build does not contribute (`ModelCatalog.kt:159`)
[C].

```json
{
  "id": "siglip-base-patch16-256-multilingual",
  "name": "SigLIP base (multilingual)",
  "version": 1,
  "capabilities": ["semanticImageSearch"],
  "labelSetVersion": 1,
  "runtime": "coreml",
  "artifactVersion": 1,
  "licence": "Apache-2.0",
  "source": { "repo": "google/siglip-base-patch16-256-multilingual", "revision": "<commit>" },
  "artifacts": [
    { "role": "vision",     "path": "siglip-base-patch16-256-multilingual/1/coreml/vision_fp16.mlpackage.tar" },
    { "role": "text",       "path": "siglip-base-patch16-256-multilingual/1/coreml/text_fp16.mlpackage.tar" },
    { "role": "vocabulary", "path": "siglip-base-patch16-256-multilingual/1/tokenizer.bin" }
  ]
}
```

**Same `id` and `version` as the ONNX entry; only `runtime` and the paths differ** (§7 D13).
- It is the same checkpoint. `VisionModel.PinnedSigLip` (id, version, side 256, 768 dimensions and
  the calibration constants, `VisionModel.kt:55-66`) describes both artifacts, so iOS reuses it and
  stamps the same `ModelProvenance` [C].
- A separate id would make every synced label row from one platform stale on the other:
  `isStaleAgainst` treats a different `modelId` as stale (`ModelProvenance.kt:57-64`) [C]. Labels are
  inert today (§27.2), so this costs nothing yet, but it would the day they go live.
- ⚠ The price: one stamp now covers two precisions whose image vectors differ (§2.1). Vectors are
  device-local, so nothing compares them. Label thresholds are per artifact (D1's cost), and what a
  photograph's labels are when two artifacts could write them is decided in D13: the first writer's.
- ⚠ **A build must contribute one runtime per model id.** `runDownload` takes the first entry with a
  matching id (`ModelDownloader.kt:141`) and the store's index is keyed by id
  (`DefaultModelStore.kt:167,212,284`) [C]. A build contributing both `onnx` and `coreml` would see
  two entries with one id and silently take the first. Photos contributes exactly `{"coreml"}`.
- **Shipped builds are unaffected.** Android contributes `{"onnx", "onnx+mediapipe"}` and desktop
  `{"onnx"}` (`PlatformProxyModule.kt` androidMain `:638`, desktopMain `:689`) [C], so they never see
  the new entry. The vocabulary's path and bytes are the ONNX entry's own, so its digest is the same.

**What changes, file by file:**

| File | Change |
|---|---|
| `deploy/models/models.json` | The entry above, third in the list (`:5-17` is the ONNX one) [C]. **As built (§12):** each of its artifacts also states `sha256` and `byteSize`, as a pin the signer checks, and `source` carries the source-file hashes, the toolchain and the export manifest's digest |
| `ModelCatalog.kt` | **Nothing** in the parser or the filter. `SUPPORTED_RUNTIMES` stays `{"onnx"}`: it describes `onnxMain` (`:62-66`) [C]. The iOS claim is a new constant in `iosMain`, `COREML_RUNTIMES = setOf("coreml")`, added in the commit that binds the backend (the rule at `:68-70`) |
| `ModelCatalogData.kt` | **Nothing.** The roles `vision`, `text` and `vocabulary` are reused (`:26-34`); no new role [C] |
| `:model-signer` | Signs the entry as it is: an archive is a file, and the builder computes each artifact's sha256 and size from the file (`ModelCatalogBuilder.kt:91-99`) [C]. Two additions: **refuse a duplicate `(id, runtime)` pair** (nothing checks today [C: none in the file]); copy an optional per-model `source` block from the manifest into `catalog.json`. **As built (§12):** both, plus two refusals the entry needed: entries sharing an id must share `version` and `labelSetVersion`, and an artifact whose bytes are not the manifest's pinned `sha256` / `byteSize` is not signed. The **client's** catalog parser ignores unknown keys (`ModelCatalog.kt:201`) [C], so shipped clients are unaffected by `source`. ⚠ The signer's **build-manifest** parser does the opposite since review: `ModelCatalogBuilder.parseManifest` is strict, because a misspelt pin key dropped silently would sign unpinned bytes |
| `scripts/models-publish.sh` | **Nothing.** It uploads each path as one file (`:83`, `:107`) and de-duplicates paths (`:111`), so the shared vocabulary is uploaded once [C] |
| `scripts/inference/export-coreml-towers.py` | **New** (below) |
| `docs/how-to/models-pipeline.md`, `docs/models.md` | The Core ML export and its measurements |
| `SignedCatalogRoundTripTest` | A two-entry catalog with one id: the `onnx` set and the `coreml` set each see exactly one entry. **As built:** also `CoreMlCatalogEntryTest` (the tracked manifest, and the published catalog under the pinned key) and `ModelCatalogBuilderTest` (§12.4) |

**One archive per tower, not one artifact per file.** ⭐ Recommended: an **uncompressed tar** of each
`.mlpackage`.
- An `.mlpackage` is a directory: a manifest, the model spec and `weights/weight.bin` [I: coremltools'
  layout, not re-read]. The pipeline handles files only:
  - the signer requires each artifact to be a file (`ModelCatalogBuilder.kt:91-94`);
  - the publish script uploads files (`models-publish.sh:83`);
  - the downloader issues one GET and one sha256 per artifact (`ModelDownloader.kt:232,268-274`);
  - the store lands one file per role at `<id>.<role>.<digest>.model` (`DefaultModelStore.kt:22-25`) [C].
- **Per file would need** six new roles that encode a directory layout in their names (`artifact(role)`
  is one file per role, `ModelCatalogData.kt:127`), and a step that rebuilds the directory from the
  store's flat names before every compile. It saves the unpack and nothing else.
- **One archive keeps** three artifacts, three roles and one digest per tower, so *"all three or
  none"* (`ModelCatalogData.kt:79-85`) holds unchanged, and a download resumes with one `Range`
  request per tower.
- **Tar, not zip.** fp16 weights barely compress [I], iOS has no public unzip API, and an uncompressed
  tar is unpacked by a small reader in `commonMain` over `FileDatabase.readBytesRange` /
  `writeBytesRange` (`FileDatabase.kt:43,66`) [C], with bounded memory and no new dependency.
- ⚠ The reader refuses absolute paths, `..` segments and anything that is not a regular file or a
  directory. The archive's digest is checked against the signed catalog before the reader sees it,
  so this is defence in depth.
- Rejected: shipping a host-compiled `.mlmodelc`. It removes the compile step, but a compiled model
  downloaded from a server is not the path Apple documents (below), and it is tied to the host's
  compiler version [I].

**Where `MLModel.compileModel` runs: on the device, once, after the download.**
- **The path.** Apple documents exactly this: download the model, call `MLModel.compileModel(at:)`,
  and move the compiled `.mlmodelc` out of the temporary directory to a permanent one such as
  Application Support, so later launches skip the compile [D: Apple, *Downloading and Compiling a
  Model on the User's Device*].
- **The steps** (`CoreMlModelCache.prepare`, on `Dispatchers.Default`, shown as *Preparing* on the
  Models screen):
  1. check free space (`availableSpaceBytes`, `IosFileDatabase.kt:232-236`) [C];
  2. unpack the tar to `inference/coreml/tmp/<role>.mlpackage`;
  3. `compileModel`;
  4. move the result to `inference/coreml/<digest-tag>/<role>.mlmodelc`;
  5. delete the unpacked package;
  6. load the model once with `.cpuAndNeuralEngine` and drop it, which pays the first-open cost (§3.2);
  7. write a `ready` marker holding the archive digest and the OS build. **Last.**
- **Location.** Under the Photos-private, backup-excluded root of §3.1, beside the store. `<digest-tag>`
  is the archive's sha256 prefix, the same content-addressing as the store's own paths
  (`DefaultModelStore.kt:22-25`) [C]. Photos builds `IosFileDatabase(root = …)` itself
  (`IosFileDatabase.kt:76-77`) [C], so it knows the absolute root the `NSURL`s need.
- **Crash-safe by the marker.** No marker means not ready: the backend does not claim the capability
  (§3.2) and the next launch redoes the steps from 2. Directories for other digests are deleted after
  a new marker is written. It holds no lock on the shared replica, so a suspension mid-prepare is not
  the `0xdead10cc` case [I].
- **A realm reset never clears it.** Compiled models are public weights, like the store's bytes (§3.1).
- **Recompile after an OS update.** Not known to be needed [I]: apps ship models Xcode compiled at
  build time and run them on later OS versions [I]. So the design is failure-driven, not version-driven:
  a `.mlmodelc` that fails to load is deleted and recompiled from the archive once; a second failure
  is `Failed`, latched. What an OS update does cost is the first open again [I], the 2–3.5 s of §2.2.
- ~~Compile time, the `.mlmodelc`'s size and the free space the steps really take are not measured.~~
  **Measured on the iPhone 17e** (§9.4, and again in §9.7) [M]; the disk table below is built from it.

**Disk** — ⚠⚠ **from the measured prepare step since 2026-10-07; v0.4's computed table is withdrawn.**
It assumed a `.mlmodelc` about as large as its package and nothing else. The phone showed a second,
larger cost: the first open. Sizes here are decimal MB and GB, as the harness reports them; the
~709 MB used elsewhere in this doc is the same download in MiB.

What was read [M: iPhone 17e, iOS 26.6.1, free-space change per step; §9.7's reading, and §9.4's
within 1.3 MB of it]:

| | vision | text | both |
|---|---|---|---|
| Archive, as downloaded | 184.8 MB | 555.5 MB | **743.4 MB** with the 3.1 MB vocabulary |
| Unpack (the compile then adds nothing, and deleting the package frees 0.1 MB) | −184.9 MB | −562.1 MB | |
| First open | −370.1 MB | −727.9 MB | −1 098.0 MB |
| **Whole prepare step** | **−555.0 MB** | **−1 281.4 MB** | **−1 836.4 MB** |
| Compiled model on disk afterwards | 184.9 MB | 555.6 MB | 740.5 MB |

⚠⚠ **Where the first-open space is kept, and whether it is ever returned, is not measured** (§9.4).
**Every budget below treats it as NOT returned**, until a measurement says otherwise. The lower
figures, for the case that it is returned in full, are [I] and are not to be used for a check.

| | Budget | Arithmetic |
|---|---|---|
| Download | 743.4 MB [M] | 184.8 + 555.5 + 3.1 |
| **First install, additional free space** | **~2.58 GB** [M, first open counted as kept] | 743.4 + 555.0 + 1 281.4 = 2 579.8 MB |
| Peak of a first install | ~2.58 GB, the same | free space fell at every step that moved it by more than 10 MB, so the end of the text tower's step is the peak |
| Steady state, archive kept (⭐ first cut) | **~2.58 GB** [M, first open counted as kept]; ~1.48 GB if it is returned in full [I] | 2 579.8; or 743.4 + 740.5 = 1 483.9 |
| Steady state, archive dropped (D14) | ~1.84 GB; ~0.74 GB if returned [I] | 2 579.8 − 743.4 = 1 836.4; or 740.5 |
| **Update, additional free space** | **~2.58 GB**, the same as a first install [M for the sizes] | 743.4 (new archives) + 1 836.4 (new prepare) = 2 579.8 MB. ⚠ The 740.5 MB the old compiled directory would return is **not credited**: that the deletion returns it is [I]. Credited, it would be 1 839.3 MB |
| ⚠ Peak during an **update** | **~5.16 GB** on disk; ~4.42 GB if the deletion returns the old compiled directory [I] | 2 579.8 (the old generation, its first-open space counted as kept) + 2 579.8 = 5 159.6 MB; or + 1 839.3 = 4 419.1 MB |
| After the next-process sweep of the old archives | ~4.42 GB; ~3.68 GB if the deletion returned the old compiled directory [I] | 5 159.6 − 743.4 = 4 416.2 MB; or 4 419.1 − 743.4 = 3 675.7 MB |

- ⚠ **Why ~2.58 GB here against ~383 MB on Android: accepted for now (maintainer, 2026-10-13),
  and two things are owed, neither of them built.**
  1. **Unexplained:** the compiled towers and their first-open space take ~1.84 GB, and archives
     plus towers ~2.58 GB, against v0.4's *computed* 1.41 GB (§9.4, noted when PR-1 measured it;
     never explained). Owed: why. Candidates, none checked [I]: fp32 constants kept beside fp16,
     weights held twice (the unpacked package and what first open writes), a specialisation per
     compute unit. It bears on D14 and on the experiment below.
  2. **To measure, offline, as the retrieval comparison was:** compress the Core ML weights
     (8-bit linear quantisation or palettisation, `coremltools`) and read the download size, the
     compiled `.mlmodelc`'s size, retrieval agreement against fp32 (today fp16 agrees on 35 of 36
     top-1 and the uint8 ONNX pair on 23 of 36, §7 D1), label agreement
     (`scripts/inference/label-eval.py`), and per-image latency on the phone (today 5.9 ms for a
     vision predict in the spike, §2.1; 33 ms a photo end to end, §9.7). It is an option only if
     quality and speed hold. ⚠ A new export is new digests, a new catalog entry, a new preflight
     probe record and a re-published model.
  `tech-debt.md` carries both as one entry. D14 stays the maintainer's open decision.
- ⚠⚠ **The update rows carry the assumption furthest.** If the old generation's 1 098 MB of
  first-open space is never returned, each update strands that much: at least 3.68 GB after one update
  where a fresh install of the same model holds 2.58 GB. That is the conservative reading, not a finding.
  It is the reason the measurement is owed **before PR-5's update path ships** (§9.7): delete a
  prepared model's compiled directory on the phone and read the free space, at once and after a
  relaunch and a reboot.
- If deleting the old compiled directory returns the first-open space with it, an update needs
  1 839.3 − 1 098.0 = **741.3 MB** more and peaks at 3 321.1 MB [I].
- ⚠ One phone, one OS build, two readings a tower, and **no margin** in any figure above. The check
  adds its own.

- ⚠⚠ **An update reclaims what is safe to reclaim early, and budgets the rest.** A replacement is not
  served until its own preflight passes (§3.2 *Readiness*), so nothing reads the old generation once
  the new download is verified.
  - The old **compiled directory** is deleted before the new one is unpacked and compiled. It is
    derived data: if a crash resurrects the old install record, the cache re-prepares from the archive.
  - The old **archives are not deleted early.** `DefaultModelStore` reclaims garbage only after a
    *later process* has read the persisted index, because an index write is not guaranteed durable
    when it returns; deleting in-process could leave a recovered old record pointing at removed
    files. Moving the index into the `FileDatabase` does not add a durable-commit contract, and this
    plan does not invent one. The old archives stay until the next-process sweep.
  - So an update's peak is **~5.16 GB** on disk, counted without crediting the old compiled
    directory's deletion (the table above has the arithmetic; ~4.42 GB if that deletion returns its
    bytes [I]): the old generation's 2.58 GB, plus the new archives, plus the new prepare step. D14 (dropping the archive after a successful prepare) would take 743.4 MB off the
    steady state; it does not touch the first-open space, which is the larger part.
  - The free-space check runs **before the download**, and compares free bytes with the **additional**
    space the case needs, not with its total peak: free space already excludes what is installed.
    **A first install needs ~2.58 GB more** (2 579.8 MB) [M]. **An update needs the same ~2.58 GB more**:
    the new download and prepare. ⚠ The 740.5 MB the old compiled directory would give back is not
    credited until the deletion experiment (§9.7) measures how many bytes return; crediting it
    (~1.84 GB) would admit a phone that then runs out during the prepare step. Requiring the total
    peak free would refuse updates that fit. So a phone is never left 743 MB into a download it
    cannot finish. ⚠ v0.4's figures here were ~1.94 GB and ~1.24 GB, computed without the first
    open; each is about 0.6 GB too low.
  - ⚠ **This is not `CoreMlModelCache`'s own check, and that check's factor does not transfer.**
    The cache checks each tower **after** its download, for `3 ×` that archive (§9.2, §9.4)
    [C: `SPACE_FACTOR`]. Against the measurement: vision needs 555.0 MB where the factor asks for
    554.5 MB (3.003 ×, so the factor is 0.5 MB short and has no margin); text needs 1 281.4 MB where
    it asks for 1 666.6 MB (2.31 ×, 385 MB more than was used). So the factor is consistent with
    the measurement, exactly at the limit for vision and generous for text. Two things it does not
    do: it reserves nothing for the *other* tower's step, and it is not a pre-download rule. Before
    the download the need is 2 579.8 / 743.4 = 3.47 × the download, so `3 ×` there (2 230.2 MB)
    would be 349.6 MB short. **Owed as a follow-up, no code changed here:** give
    the cache's check a margin above the measured 3.003 × for vision, and have PR-5's
    pre-download check use the totals above, not the factor.
- ⚠ The Models screen must state the on-disk figure, not only the download. `InstalledModel.byteSize`
  is the sum of the installed artifacts (`InstalledModel.kt`) [C] and does not see the compiled copy.
  The iOS screen adds the cache's size to it.
  - ⚠⚠ **That sum is not what the phone holds.** `InstalledModel.byteSize` plus
    `CoreMlModelCache.sizeOnDisk()` sees the ~743 MB of archives and the ~741 MB compiled
    directories, ~1.48 GB. The measured free-space change is ~2.58 GB: the first open takes another
    ~1.10 GB that is in neither place, and where it lives is not known (above). A screen built from
    the two calls alone would understate by ~1.1 GB.
  - So until that space is located, the screen states an **estimate that includes it**: the two
    visible sizes plus a first-open allowance per tower, a named constant set from the measurement
    (370 MB vision, 728 MB text for this artifact [M]; it moves with the artifact, so it is keyed to
    the archive digest and re-measured by the harness for each export). The copy says "about", and
    says it before the download, where D6 states the size.
  - Locating the allocation (the experiment in §9.7) replaces the allowance with a measured read, or
    shows it is returned and removes it.
- Keeping the archive leaves the store's contract alone (a record means verified bytes are present)
  and makes a recompile local. Dropping it saves the ~743 MB of archives (a conservative ~2.58 GB
  becomes ~1.84 GB [M], table above; it does not halve the footprint) and turns a recompile into a
  re-download. That is §7 D14, decided with PR-1's numbers.

**How digests are verified.**
- **The archive:** unchanged. sha256 per artifact against the signed catalog, streamed during the
  download (`ModelDownloader.kt:268-274`) [C], and re-hashed from disk on a background-session
  relaunch (PR-5's journal).
- **The compiled model:** not hashed. It is derived on the device from verified bytes, in a container
  only Photos can write, and it is trusted only when the marker names the installed archive's digest.
- **Sibling towers:** the vision and text archives are in one signed entry, so a mismatched pair is
  not installable. That closes the hazard `OnnxInferenceBackend.kt:147-151` describes [C].

**The export script records where the weights came from.** §2.2 found that the repo pins no upstream
revision: `models.json` names an id and paths. `export-coreml-towers.py`:
- takes `--source` (a local checkpoint directory, never a hub tag: `models.md` R6, as
  `export-vision-tower.py:34` does) [C] and a required `--revision`;
- loads the full model and narrows it to each tower, as the ONNX scripts do
  (`export-vision-tower.py:53-56`) [C];
- wraps each tower to return `pooler_output` only, traces it, and converts with explicit names and
  types: `pixel_values` Float32 `[1, 3, side, side]` and `input_ids` Int32 `[1, length]` in,
  `pooler_output` Float32 out; `mlprogram`, FLOAT16, iOS 17 target. Plain tensor inputs, never
  `ct.ImageType` (§3.2). `side` and `length` come from the checkpoint's own configs, as the ONNX
  scripts read them;
- **checks its own output**: each converted tower against the fp32 PyTorch tower on fixed inputs, and
  refuses to write an archive below a stated cosine;
- writes each tar deterministically (sorted entries, zeroed times and owners);
- reuses the vocabulary `export-text-tower.py` packs (`:36-63`) [C];
- writes `export-manifest.json`: the upstream repo and revision, the sha256 of the source
  `model.safetensors` and config files, the versions of coremltools, torch, transformers and numpy,
  the conversion parameters, and each output's sha256.
- The repo and revision are copied into `models.json`'s `source` block, which the signer carries into
  the signed catalog. **As built (§12.1):** copied by hand from `export-manifest.json`, with the
  source-file hashes, the toolchain and the manifest's own sha256.
- ⚠ The ONNX entry gets no `source` block from this work. §2.2 established that the local ONNX files
  match the published digests and that the local `model.safetensors` matches the upstream commit, but
  nothing proves the ONNX files were exported from that snapshot. Writing a revision there would be a
  claim, not a record. A re-export is what closes it.
- ⚠ §2.1's conversion ran on coremltools 9.0 with torch 2.13, an untested pairing that needed a
  one-line numpy-2 patch. The script pins a pairing that needs no patch, or carries the patch in the
  repo. An archive from a hand-patched environment is not reproducible.
- ⚠⚠ **The archives that ship are not the ones PR-0b validated.** A different, unpatched toolchain
  makes new artifacts, and a per-tower self-check on fixed inputs does not show the pair still shares
  a search space. So §2.2's multilingual text-to-image retrieval gate is **re-run on the final tar
  contents** (unpacked, compiled, both towers) before PR-2b publishes, with the same pass rule.
  D1's 35 / 36 otherwise describes a model nobody downloads.
- **The gate is `scripts/inference/retrieval-gate.py`**, repeatable for every later export
  (how to run: [`models-pipeline.md`](../how-to/models-pipeline.md) §2a; the current export's result: §9.6).
  - **It takes an export directory and verifies it first**: `export-manifest.json` must be present and
    name the bytes of both archives and the vocabulary, and `--source` must be the checkpoint the
    manifest recorded, file for file. Then it unpacks the two tars and runs them through coremltools.
  - ⚠⚠ **The export must come from the checkpoint the yardstick is taken to come from**
    (`YARDSTICK_SOURCE` in the script: the upstream revision and the sha256 of the five source
    files). Without it, a manifest and a `--source` that agree on another shape-compatible
    checkpoint would be compared with their own fp32 weights against the original's ONNX pair: an
    easy pass for a wrong export. ⚠ The pin is the gate's **assumption**, not a proven provenance:
    §2.2 established that the local weights match the upstream commit and the local ONNX files match
    the published digests, and that nothing records the ONNX files were exported from that snapshot.
    It is waived only when the yardstick is itself not the published pair
    (`--allow-unpublished-onnx` with another ONNX file); the flag with the published files waives
    nothing.
  - ⚠ **An export made outside the pinned toolchain is refused** (`toolchain.pinned` is not `true`
    in its manifest) unless the gate is run with `--allow-unpinned-export`. Any override puts
    `OVERRIDDEN` in the printed verdict and `overridden` / `admission` in the result.
  - **The rule, as code:** Core ML ≥ the ONNX pair, against fp32, on **seven figures**: top-1
    agreement, and the mean and the worst query of top-5 overlap, top-10 overlap and Spearman. Equal
    passes. ⚠ It is relative: it bounds what Core ML costs against what Android ships, and says
    nothing about how good fp32 retrieval is.
  - **Queries**: `retrieval-gate-queries.json`, PR-0b's 36. **Tokens are padded with `</s>` (id 1)**
    by the checkpoint's own tokenizer, as `SiglipTextTokenizer` pads; the script refuses
    another pad id.
  - **Corpus**: `retrieval-gate-corpus.txt`, a manifest and never the images. It pins the sha256 of
    each **original download** (300 COCO test2017 JPEGs) and states the resize (256², bicubic,
    stretched). The original is what is pinned because a download is checked by sha256 alone, while
    the hash of a resized file depends on the JPEG decoder and the resampler. The manifest also
    records the digest of the resized pixels under the pinned Pillow, and a pinned run that sees
    other pixels is refused. The corpus id is built as the harness's is (§9.2 item 14), with no
    shared code.
  - **Toolchain**: `requirements-retrieval-gate.txt`, the export's pins plus onnxruntime, Pillow and
    sentencepiece. ⚠ onnxruntime is pinned to the version the apps ship: the int8 pair's vectors
    move with the runtime version (§9.6).
  - **Result**: a JSON beside the archives, with the artifact digests, the corpus id, the queries
    file's sha256 and the toolchain. Exit 0 pass, 1 fail, 2 refused.
  - ⚠ **A host run.** The towers run on the Mac, not on a phone. `--phone-vectors` adds a spot-check
    against the harness's vectors and never changes the verdict.
  - ⚠⚠ **A result belongs to the digests it ran on.** Two export runs give two digests, so the
    archives PR-2b publishes must be the gated ones, byte for byte, not a re-export.

**House Rule 9: nothing is reshaped.**
- `ModelEntryData` and `ModelArtifactData` are proxy-internal plain data that never cross the wire
  (`ModelCatalogData.kt:11-12`), and `runtime` and `role` are strings in them [C].
- The wire types, `AvailableModel` and `InstalledModel`, carry id, name, version, capabilities,
  byte size, label-set version and licence. Neither has a runtime or a role field
  (`AvailableModel.kt`, `InstalledModel.kt`) [C].
- The persisted index record, `InstalledModelData`, stores role strings and paths
  (`ModelCatalogData.kt:131-155`) and gains no field [C].
- `ModelProvenance` is unchanged (§3.2).
- So: **no service version bump, no `SchemaMigration`, no baseline regeneration**, and
  `SUPPORTED_ARTIFACT_VERSION` stays 1. The catalog's `source` key is ignored by every shipped client.

### 3.5 What the Core ML route removes

| Gone | Was |
|---|---|
| `scripts/fetch-onnxruntime-apple.sh`, the pinned pod archive and its sha256 | v0.3 D4 |
| The headers-only cinterop, the vendored C headers, `onnxruntime.def` | v0.3 §1.1, PR-1 |
| The C shim (`OrtEntry.c`), `OrtEntry`, `IosOrt`, `IosOrtSession`, `IosOnnxInferenceBackend` | v0.3 §3.2, §3.4 |
| `-lc++`, the `-u` entry-symbol flags, `onnxruntime.xcframework` in `photos-ios/project.yml` | v0.3 §3.4 |
| ~17 MB of binary (6 MB compressed) in Photos | §2 [M] |
| The execution-provider build constant, and CPU vs CoreML EP as a decision | v0.3 D2 |
| ORT's missing privacy manifest and its `stat`/`fstat`/`sysctl` required-reason question | v0.3 §6 |
| The Photos-only **linkage** argument: nothing third-party is linked, so there is nothing to keep out of the other nine apps | v0.3 D3 |

**What stays, unchanged:** the Photos-only **binding** and the store ownership of PIO-D10 (§3.1): the
four locks, the realm stamp, and the model index out of the Keychain. ⚠ Lock 1 is weaker than it was
(§3.1 says how), because the link-time half of it went with ORT.

§2's spike table and §2.1's ORT rows stay in this doc as measurements of the route not taken.

---

## 4. IN-3m-iOS — the latency harness, first

PR-0 and PR-0b answered *"which runtime"* on synthetic input and on 300 public images. The harness
answers what they could not: the real corpus, decode time, the compile step, and heat over a real pass.

### 4.1 What it measures, matched to Android's method

Android's IN-3m (`inference-service.md` §15, as built §15.11/§15.12, `DeviceMeasurementTest.kt`) [C/D]:
- **N ≥ 200 photos** (`MIN_IMAGES = 200`, `DeviceMeasurement.kt:27`), median overall;
- **first-50 vs last-50** medians and their throttle ratio (`WINDOW = 50`, `:30`);
- wall clock for the whole pass with decode;
- failures;
- two runs, cold then warm, plus one after 20 minutes idle;
- the §15.9 row computed from the **last-window** median;
- a report file.
- The corpus was **562 JPEGs, every 4th file of the camera roll Dec 2024 → Jun 2026**, plugged in, screen
  on (§15.12) [D].

**iOS rows:** `coreml-ane` (`.cpuAndNeuralEngine`, what ships), `coreml-all` (`.all`) and `coreml-cpu`
(`.cpuOnly`, the control). ⚠ There are **no ORT rows**: the harness links no ORT. PR-0 measured them
(§2.1) and they are not repeated.

⚠ **Comparable on corpus and method; not on device class or runtime.** Android's 121 ms is ORT on one
Snapdragon phone, the OnePlus CPH2581 (§15.12), taken at ORT's default session options
(`DeviceMeasurement.kt:73-82`) [C] before P3 capped the shipped sessions at 2 threads (`media-processing-pipeline-sweep.md` D4).
`inference-service.md` calls that phone mid-range [D]; the model number reads as the 2024 OnePlus 12,
a flagship [I]. A ratio between it and the iPhone 17e says nothing about either platform's floor
(§3.2, D12). Compare iOS rows with each other, and Android with Android.

Plus, beyond Android:

| Extra | Why |
|---|---|
| decode ms (ImageIO, §1.2) separately from predict ms | Android's timings exclude decode (`DeviceMeasurement.kt:132`). At ~6 ms a prediction in the spike (~13 ms on camera originals, with ~20 ms of decode [M: §9.7]), **decode is expected to be most of a pass** [I], so it is the number that sizes a library pass |
| `compileModel` ms per tower, the `.mlmodelc`'s size, the volume's free space before and after each step of §3.4 | Not measured by PR-0 or PR-0b. Decides D14 and the free-space check |
| open ms: first after install, second launch, **after a reboot** | §2.2 measured the first two only |
| `phys_footprint` peak per row (`task_info(TASK_VM_INFO)`), one process per row | jetsam's figure. §2.1/§2.2 measured it on synthetic input |
| `ProcessInfo.thermalState` at start, every 50, end, over a **realistic duty cycle** (decode + predict, not predict back to back) | §2.2 reached `serious` at ~125 predictions/s. A real pass is slower; this says how much thermal backoff the foreground pass needs (D5) |
| cosine of each row's vector vs `coreml-cpu` (mean and min over the corpus) | confirms the compute-units choice does not move the space (§3.2) |
| the text tower with fixed token ids: median, footprint | PR-2 owns the tokenizer; the harness needs none |
| **the per-image vectors, kept on the phone** | re-run after the next iOS update: the cosines across it set §3.2's probe threshold |
| `UIDevice.batteryLevel` delta | directional only, 5 % steps (§15.6's caveat) |

⚠⚠ **Refuse on the simulator**, as §15.1 does on the emulator. Under `targetEnvironment(simulator)`, or
when K/N reports a simulator target, print *"simulator — timings meaningless, ignoring"* and nothing
else. ⚠ The skip paths (*no model*, *no photos*) write the report, and only the simulator path stays
silent (§15.12.6 item 3) [D].

### 4.2 Where it lives

**Still a separate harness app with its own small framework, never shipped** (§7 D9). The reason
changed:
- v0.3 needed the module to link ORT into a test binary. That reason is gone.
- The reason that remains: the harness must measure the **shipped** Kotlin (`CoreMlSession`,
  `CoreMlModelCache`, `IosImageDecoder`), and Swift cannot reach it through `TenshuSdk`. The umbrella
  exports no inference module (`tenshu-sdk-ios/build.gradle.kts:59-198`) [C], and exporting one for a
  harness would put harness surface in every app.

So:
- `inference-harness-ios/`: an xcodegen project, SwiftUI with one *Run* button and a log view. Bundle
  id `app.tenshu.in3m.ios` on team `752LWAZMKZ`. No C shim, no fetched framework, no extra link flags.
- A new iOS-only module, `:inference-harness-ios`, building its own XCFramework. It depends on
  `:inference-service-proxy` and holds the measurement loop and the report writer.
- It reuses `HarnessMath` from `src/harnessShared`, which imports only `ImagePreprocessing`
  (`HarnessMath.kt:3`) [C]. `HarnessArguments` is JVM (`java.io.File`, `HarnessArguments.kt:3`) [C]
  and is not reused.
- The backend's own tests live in `:inference-service-proxy` `iosTest`, not in the harness module:
  they link nothing third-party any more.
- It is not in `ios-smoke.sh`'s default set and not in any gate (§15.8).

### 4.3 Simulator vs iPhone

| | Simulator (🖥) | iPhone (📱, maintainer) |
|---|---|---|
| Compiles; unpacks a tar; `compileModel`; opens; 768-d output | ✅ [I: Core ML on the simulator runs on the CPU] | ✅ |
| Same pixels → cosine vs host fp32 ≥ 0.99 (correctness of decode + preprocess + predict) | ✅ | — |
| Simulator refusal verified (forced off, it proceeds to *"no model"*; §15.11.1's method) | ✅ | — |
| Any timing, throttle, thermal, `phys_footprint`, ANE placement, open cache | ❌ meaningless | ✅ **only here** |

### 4.4 What it needs from the maintainer's iPhone

**Device and signing:**
- The iPhone `00008150-0011085C1E85401C`, unlocked and **plugged in** (IN-D6's condition, §15.12).
- Signed on the company team: `xcodebuild … -allowProvisioningUpdates`, plus
  `-allowProvisioningDeviceRegistration` once for a device new to the team. The harness needs **no App
  Group and no Keychain group**, so it does not wait for S0b and claims no capability.
- ⚠⚠ Never build for a device with the old personal team `FAYT82GWD7`: that permanently registers the id
  to it (`ios-background-scheduling.md` §4.1.1).

**Side-load, never bundle** (§15.3; nothing committed, the repo is public). The harness takes the
**tar archives**, so it exercises the unpack and compile steps the product runs:

```bash
DEV=00008150-0011085C1E85401C; ID=app.tenshu.in3m.ios
xcrun devicectl device install app --device $DEV <path>/In3mHarness.app
for f in vision_fp16.mlpackage.tar text_fp16.mlpackage.tar; do
  xcrun devicectl device copy to --device $DEV --domain-type appDataContainer \
    --domain-identifier $ID --source ~/tenshu-models/export-coreml/$f --destination Documents/$f
done
# the same 562-photo corpus Android used: ONLY through the driver, which writes the corpus manifest
inference-harness-ios/run-in3m.sh push-photos <corpus dir>
xcrun devicectl device process launch --device $DEV --console --terminate-existing $ID
xcrun devicectl device copy from --device $DEV --domain-type appDataContainer \
  --domain-identifier $ID --source Documents/in3m-report.txt --destination ./in3m-ios.txt
```

⚠⚠ **The corpus is what `Documents/in3m-corpus.txt` names, not what is in `Documents/photos/`**
(§9.2 item 14). `devicectl` cannot delete from a container, so photos from an earlier push stay on
the phone; the harness embeds only the named files, after checking each one's size and sha256,
and refuses a run with no manifest. As built, every step above is a mode of
`inference-harness-ios/run-in3m.sh` (`build`, `install`, `push-models`, `push-photos`, `run`,
`report`).

⚠ The bundle id goes in `--domain-identifier`, never `--username`. A locked phone fails the launch.
Whether `copy to` takes a directory is [I], and the corpus may need a per-file loop written as a bash
script, since zsh does not word-split.

⚠⚠ **The corpus is owed by the maintainer.** The 562 JPEGs are not on the build Mac (§2.2). PR-0b used
300 public images instead. PR-1's comparison with Android needs the same 562 files.
⚠ **2026-10-07 (§9.7):** the corpus was pulled again from the same phone by the same recipe. It is
554 files, not those 562, because the camera roll changed. So the same-files comparison this
paragraph asks for cannot be made any more; the comparison is same-recipe.

⚠ **HEIC.** An iPhone camera roll is HEIC, and Android's corpus was JPEG. Run the **same JPEG corpus**
first for the comparison. Then run a HEIC sample, because that is what a real iOS library decodes.

**Three runs** (cold, warm, +20 min idle), all with the phone on the charger. Then one run **on battery**,
which is what §7 D5 needs and what Android never measured. Then one launch **after a reboot**, for the
open time.

### 4.5 The decisions it feeds

| Reading | Decision |
|---|---|
| last-50 (decode + predict) > 500 ms, or throttle > 2 | §15.9 row 3 on iOS. Not expected: predict alone was 5.8 ms in the spike [M], and decode + predict ~33 ms on camera originals [M: §9.7] |
| decode ms ≫ predict ms | a pass is decode-bound: its duration is set by ImageIO, and P7's `IndexImage` seam (§1.2) is where to buy time, not the model |
| `thermalState` ≥ `.serious` inside a realistic pass on the charger | the foreground pass's backoff (PR-5): how many photos per chunk, and how long to rest |
| the on-battery run reaches `.serious` inside 200 photos, or the battery delta is steep | iOS never indexes on battery below some level, *Index now* included (§7 D5) |
| `compileModel` takes more than a few seconds, or the free-space delta is ≈ 2 × the package | *Preparing* needs a progress line and a free-space check with the measured figure; and D14 |
| the free-space delta is far below the package size | the volume clones the weights [I]; keeping the archive is nearly free and D14 closes as *keep* |
| open after a reboot is slow again | the launch warm-up (§3.2) is required, not a nicety |
| `coreml-ane` vectors differ from `coreml-cpu` beyond §2.1's 0.9928 ↔ 0.9923 | the compute-units constant is a store-clearing change (§3.2) |
| `phys_footprint` on the corpus far above 19 MB | D12's memory threshold is set from this number, not from §2.2's |

---

## 5. The PR plan

Each PR leaves master consistent. 📱 = maintainer's iPhone; 🖥 = this Mac; *smoke* = `scripts/android-smoke.sh`
(HR 8); *iOS lane* = `scripts/run-gate.sh`'s `compileTestKotlinIosSimulatorArm64` on macOS; *iOS tests* =
`./gradlew iosTestCheck` (macOS only; the gate compiles `iosTest` and does not run it).

| # | What | Files / modules | Owes | Tests | User sees |
|---|---|---|---|---|---|
| — | This plan | `docs/proposals/ios-on-device-inference.md`, `photos-ios.md`, `docs/README.md`, `docs/tech-debt.md` | `check-proposal-status.py`, `check-doc-refs.py` | — | nothing |
| **PR-0** ✅ **done: GO** (§2.1). ⭐ **Device go/no-go spike** — throwaway, **never committed** | the scratchpad spike (§2) wrapped in a minimal SwiftUI app, signed on `752LWAZMKZ`, on the maintainer's iPhone 17e. Configurations: `cpu-d4` (2 threads), `cpu-default`, `coreml-nn`, `coreml-mlprogram`, and a Core ML fp16-converted SigLIP vision tower through `MLModel` if the conversion succeeds | **📱 only.** 200 synthetic inferences per configuration: median and p90; `phys_footprint` peak with **one process per configuration** (relaunch with a launch argument, so no run inherits another's high-water mark, §2); `thermalState` sampled over time; Xcode's Core ML performance report for the Core ML rows. Results into §2.1. ⚠⚠ **Stop condition: §4.5's row** (last-window median > 500 ms, or throttle > 2): if every configuration hits it, PIO-7 stops here | none; nothing lands | nothing. The maintainer gets a go or a no-go |
| **PR-0b** ✅ **done: PASS** (§2.2). ⭐ **Core ML gate** — throwaway, **never committed** (D1's condition) | the PR-0 scratchpad app. Convert the **text tower** from the same checkpoint and in the same fp16 conversion as the vision tower; pin the checkpoint against the catalog's revision; tokenise with the pinned vocabulary | **📱 only.** Text tower: median, p90, `phys_footprint` (one process), open time on a second launch. ⭐ **End-to-end retrieval**: a fixed set of text queries over the photo corpus, the fp16 Core ML ranking compared with the fp32 reference ranking (top-k overlap and rank correlation), in at least two languages. ⚠ **Fail** = the text tower does not convert, does not fit, or retrieval diverges from fp32 more than the uint8 ONNX pair does: then D1 would have fallen back to ORT | none; nothing lands | nothing. The maintainer gets Core ML confirmed, or the ORT fallback |
| **PR-1** ✅ **built (§9); 📱 the measurement is still owed: a partial run only (§9.7, 2026-10-07; one short pass per row, 554 files by the 562's recipe).** ⭐ **IN-3m-iOS on Core ML** (the 562-photo corpus, decode timing, the compile step) | `scripts/inference/export-coreml-towers.py` (the two fp16 archives, the export manifest with the upstream revision, the self-check against fp32; §3.4); `inference-service-proxy`: a new `iosMain` with `CoreMlSession` (`.cpuAndNeuralEngine`, the shape guard, predict), `CoreMlModelCache` (unpack, `compileModel`, the `ready` marker) and `IosImageDecoder`; a `TarReader` in `commonMain`; new `:inference-harness-ios` (+ `settings.gradle.kts`); `inference-harness-ios/` xcodegen app; `scripts/regenerate-xcode-projects.sh`. ⚠ No catalog entry yet: the archives are side-loaded (§4.4) | iOS lane; `./gradlew iosKotlinCheck`; iOS tests; ⚠ `scripts/ios-smoke.sh --build-only` **all ten**: the umbrella's Kotlin now references `CoreML` and `ImageIO`, and whether every app's link line already has them is [I]; a harness build; **📱 the measurement** (§4.1, §4.4), pasted into `inference-service.md` §15 beside Android and into `models.md`. ⚠ `TarReader` is `commonMain`, so the JVM gate and `:control:mergeLibDexDebug` run too. No DI, no schema ⇒ no HR 9 | `commonTest`: the tar reader round-trips a package, refuses `..`, an absolute path and a truncated archive. `:inference-service-proxy` `iosTest` (simulator): the decoder upright on an EXIF-6 JPEG made in-test; squash, not crop; `size²` output; `null` on non-image bytes; the cache writes its marker last and a missing marker reads as not ready; the session's shape guard against a tiny fixture ML Program written by the export script's `--fixture` mode (⚠ whether a K/N test can read a fixture file by path is [I]; if not, the case skips with a printed reason); the harness's simulator refusal | nothing. The maintainer gets the numbers |
| **PR-2** ✅ **built (§10).** The iOS backend. ⚠ **Starts after the pad-id fix merges** (the tokenizer's pad id) | `CoreMlInferenceBackend` (vision + text) over PR-1's session and cache; the guards extracted to `commonMain` and called from `onnxMain` too; the tokenizer + vocabulary reader to `commonMain`, with `expect fun nfkc`; the tokenizer goldens to `commonTest`; `COREML_RUNTIMES` in `iosMain` | **smoke** (the `onnxMain`/`androidMain` compile set changes); `:control:mergeLibDexDebug` (a commonMain change has broken D8 with the gate green before); iOS lane; iOS tests; the JVM gate; the vocabulary map's footprint on the simulator, as a first reading (§3.2) | existing `OnnxInferenceBackendTest` unchanged against the extracted halves; `SiglipTextTokenizerTest`'s goldens (as the pad-id fix leaves them, pad = `</s>`) green on JVM **and** iOS; an NFKC parity table on JVM and iOS; the iOS backend with a fake `CoreMlSessionPort`: latching, `Unreadable` vs `Failed`, one session per path, text off the lane, no text without a vocabulary, **`supports` false until the marker exists** | nothing (not bound) |
| **PR-2b** The catalog entry ✅ **built 2026-10-07 (§12); signed and published by the maintainer 2026-10-11** (reported verified against the pinned key from outside; this plan's own evidence is the artifacts' headers, §14.12) | `deploy/models/models.json` (the `coreml` entry + `source`, §3.4); `:model-signer`: refuse a duplicate `(id, runtime)`, carry `source`; `SignedCatalogRoundTripTest`; `docs/how-to/models-pipeline.md`; `docs/models.md`. Then the **maintainer signs and publishes** (the key is in their Keychain): archives first, catalog last | the JVM gate (`:model-signer`, `:inference-service-proxy` jvmTest). No client code ⇒ no smoke. ⚠ Publishing is safe for shipped builds: none contributes `coreml` (§3.4). ⚠ It must precede PR-5's 📱 pass, and it follows PR-1's measurement **and the retrieval gate re-run on the final archives** (§3.4), so a bad conversion is never published | the round trip with two entries under one id; the signer refusing a duplicate pair; `DRY_RUN=1 scripts/models-publish.sh` listing the three paths | nothing |
| **PR-3** ✅ **built (§13), both halves** (the `meta.epoch` half 2026-11-10, §13.2). Photos-owned stores (PIO-D10 built) | `named("inferenceFileDatabase")` in the common `ProxyModule` (default = shared); Photos override → private, backup-excluded `IosFileDatabase`; the **fourth lock**: an empty, non-writing `EmbeddingStore` / `FaceTemplateStore` in every other route-1 graph (§3.1); the model index + garbage list moved from the Keychain into the private `FileDatabase` (§3.1); the realm stamp (owner id + the shared store's `meta.epoch`, §13.2) checked at install, on foreground, at pass start and before `photosLike`, clearing vectors and templates (never models) and written only after the clears; the runtime-fingerprint sidecar (§3.2: archive digests, compute units, OS build); `deprovision()` clears; `registerMediaIndexSchedule()` out of the iOS `PlatformProxyModule`; ⚠ `photos-ios.md` §3.3 / lease doc §3.2 footnote ² updated | **smoke** (Control DI) + `mergeLibDexDebug`; iOS lane; `ios-smoke.sh --build-only` all; threat-model note (A5/C4e: inference stores leave the App Group, narrowing; A40 for when faces follow) per HR 2 | `:tenshu-sdk-ios` iosTest: Drive's, Control's and Calendar's assembled runtimes resolve **no** backend, `Never`, empty runtimes and the App Group store path; Photos' resolves the private path; the other graphs' stores open no file, scrub nothing and clear nothing; a stamp mismatch clears vectors and templates before anything binds and leaves the model store; a clear that throws leaves the backend unbound and the old stamp; a mismatch while resident cancels the pass and clears before the next `photosLike`; a reinstall with bytes gone reports no model installed; a changed vision digest clears the vectors, and so does a changed OS build until §3.2's threshold is measured; deprovision clears. ⚠ Each guard deleted once and seen red (`test-the-guard-not-the-shape`) | nothing |
| **PR-4** ✅ **built (§11).** Models VM to a Compose-free module | `ModelsViewModel` + `ModelsRepository` from `:control-shared` → `:control-shared-logic` (already exported, iOS-capable); the two holders behind small interfaces; Compose `ModelsScreen` unchanged | **smoke** (`:control`); desktopTest; iOS lane; `scripts/check-objc-clashes.py` (parity doc D12: new exported names) | `ModelsViewModelTest`, `IndexNowButtonTest`, `ModelsSnapshotTest` move with it, green | nothing |
| **PR-5** ⚠ **Lands as five slices, 5a–5e (§14). 5a–5d are built (§14.2, §14.6, §14.11, §14.17, §14.18); 5e and the flag flip are open (§14.18.16); the flag is off.** **Photos binds AI-L** (foreground), ⚠ **behind a build flag** | Photos `appOverrides`: `CoreMlInferenceBackend` bound **once, at assembly**, on a phone that clears the static gates (iOS 17+, the D12 `physicalMemory` threshold, the build flag), and never rebound; `AiReadiness` (§3.2), the live state the backend consults on every call, with its persisted verdict and its four writers; `IosIndexingConditions` (scheduled = false outside a wake; manual per §3.3, thermal included); `supportedModelRuntimes = COREML_RUNTIMES`; ⚠ **no change to `photos-ios/project.yml`'s link settings** beyond what PR-1 found; SwiftUI Models screen in Settings (catalog, download size **and on-disk size**, download bar, *Preparing*, the two switches, *Index now*, coverage line); the prepare step after the download (§3.4) and the launch warm-up (§3.2), both off the main thread; `PhotosSurface.search` → `AskThisDevice` on a phone that clears the static gates, `NotBuilt` otherwise; the search view model's `noBackend` mapping from the readiness state (§3.2; ⚠ a defaulted seam in `photos-shared-logic` `commonMain`, so Android compiles it); copy names Photos' Settings; Mia's three iOS refusal lines point at Photos (§1.8); cancel-on-background inside a `beginBackgroundTask` window (§3.3); ⚠ **thermal backoff and chunking for the foreground pass** (§3.3; §2.2 reached `serious`); the model download on a **background `URLSession`** from the start (Drive's precedent: `drive-ios/Sources/BackgroundUpload.swift:32`, relaunch via `handleEventsForBackgroundURLSession`, `DriveIosApp.swift:299`), ⚠⚠ **with a durable download journal**: today the verified catalog entry, the per-artifact SHA accumulator, the completed-artifact map and the in-flight state live only in memory (`ModelDownloader.kt:132-176,182-208`) [C], so a transfer that finishes after Photos was terminated could deliver bytes nobody can verify and the model would stay staged forever. Drive's precedent works because it journals each upload *before* handing it to `URLSession`; PR-5 does the same for ~709 MB in three artifacts — in the private `FileDatabase` (§3.1), journal the catalog entry, each artifact's expected digest and task identifier before submitting, and on relaunch re-hash the delivered file **from disk** (no in-memory accumulator) before `commitStaged`, discarding a mismatch; a free-space check before the download, with PR-1's measured figures (§3.4: **2.58 GB** additional for a first install and, until the deletion experiment credits the old compiled directory, for an update too, plus the check's own margin); `PrivacyInfo.xcprivacy` for Photos (the repo-wide gap is `tech-debt.md`'s) | iOS lane; iOS tests; **smoke** + `:control:mergeLibDexDebug` (the `photos-shared-logic` seam is `commonMain`); `ios-smoke.sh --build-only` all + `--build photos`; Swift; `PhotosIosUITests`; a load of both towers on an **iOS 17 simulator runtime** (D12 ⓪); **📱 H5**: download over Wi-Fi, *Preparing*, consent, *Index now* over ≥ 200 photos, "dog" finds dogs; ⚠⚠ **device pass blocked until the App Group build is installable (S0b a–d)**, which is why the flag: the code lands, the flag stays off until H5 passes on the phone. App Store: §6 item 12. No new entitlement | Swift UI tests on a fixture backend: consent order, *"Couldn't save that on this device yet"*, the four refusal sentences (§34.2), search's off-vs-none; Kotlin: `IosIndexingConditions` truth table with fake probes, every unknown a no, thermal ≥ `.serious` a no for both bars; the D12 gate below and above the threshold and below iOS 17 (no download offered), and the preflight failing on a slow fake vision session, on a slow or unloadable fake text session and on an unreadable vocabulary, on a session returning NaN, zeros, or a mis-ranked probe pair (never `Ready`; and the backend refuses a non-finite vector on the indexing path), after each of which `supports` is false, `photosLike` refuses `NO_BACKEND` and search shows the *"isn't available on this iPhone"* line; ⚠ **readiness flips in both directions with no relaunch and one graph**: on the *same* assembled runtime and the *same* search view model, a passing verdict makes the next query run, and a failing one makes the next query refuse; **the update case**: a phone with a prior pass installs a replacement — not ready from the commit, then a failing preflight leaves it unsuitable, and a passing one clears the vectors **before** the first vector of the new artifact is written; **the indexer while unready**: `StartMediaIndexPass` refuses `NO_BACKEND`, a pass started directly skips with `skippedNoBackend`, and a pass running when the state flips is cancelled and remembers nothing against its photos; **sessions**: after an artifact change the old sessions are closed and the next call opens the new path, with no prediction served from the old model; `noBackend` maps to *"there isn't one yet"* with no model or while preparing, and to the `NotBuiltHere` line when unsuitable; ⚠ each guard deleted once and seen red; a prepare interrupted before the marker is redone and the backend claims nothing meanwhile; a `.mlmodelc` that fails to load is recompiled once, then `Failed`; ⚠ **a terminated-app relaunch**: a fake background session delivers every artifact to a fresh runtime with no in-memory download state, which verifies from the journal and installs, and a corrupted delivered file is discarded, not committed; deleting the model removes the archives **and** the compiled directory, and a launch sweep removes a compiled directory no install record names; an update deletes the old compiled directory before compiling and leaves the old archives to the store's next-process sweep, and a download is refused up front when free space is below the case's **additional** need (§3.4's measured budgets: a first install and an update are each refused below 2.58 GB free and accepted above it; an update is not held to its ~5.16 GB total peak); the preflight under a hot or pressured phone, or with a load error, stays `AwaitingVerdict` and is retried, and only repeated slow probes at an acceptable thermal state persist `Unsuitable`; the request built for a default download refuses cellular and expensive networks and the one built after "Use cellular data" allows them (the Wi-Fi-to-cellular transition itself is 📱); a background notification mid-pass cancels at the next photo and the next press resumes; a pass that meets `.serious` stops at the next photo and says why | ⭐ **Search by description on the iPhone**, after a download and two switches |
| **PR-6** ✅ **built (§16, 2026-11-11), behind the AI flag; 📱 the three nights owed (`how-to/photos-processing-wake.md`).** The processing wake. ⚠ As built: no `deadline` parameter, no chunking (none exists, §14.19.8), and the identifier stays `app.tenshu.photos.processing` (a processing task's need not begin with the bundle id) | `UIBackgroundModes: [processing]`, `BGTaskSchedulerPermittedIdentifiers: [app.tenshu.photos.processing]` in `project.yml`; `PhotosLocalRuntime.runProcessingWake(deadline)`; ⚠⚠ the handler holds the shared store's **task token** (`SharedReplicaEntries.protectTask`, `ios-shared-store-sqlite.md` B.23), ended before `setTaskCompleted`, never an entry's `withProtection`; Swift registration in `App.init`; ⚠ **the request is submitted, and re-armed**: a `BGProcessingTaskRequest` is submitted when Photos leaves the foreground with consent on and photos left to index, and again at the start of every handler, because a grant is one-shot (Drive's precedent: `drive-ios/Sources/BackgroundSync.swift:27-42`) [C]; PR-5's thermal backoff and chunking, reused; both models dropped when the wake ends; ⚠⚠ **an exactly-once completion bridge**: the handler calls `setTaskCompleted(success:)` from the runner's terminal outcome — true when the wake's chunk ends normally, false on a failure and on the expiration path after the cancel has stopped the pass — because a task left uncompleted stays active until its allocation expires and the system may kill the app [D: Apple, `BGTask.setTaskCompleted`]. Drive's handler completes on both paths (`drive-ios/Sources/BackgroundSync.swift:41-52`) [C]; `MediaIndexRunner.start()` returns at once, so this needs the runner's terminal outcome (scoped with PR-7's bridge; whichever of the two lands first adds it), guarded so expiry and a normal finish racing cannot complete twice | ~~Needs S0b-2 merged~~ (met: `ios-shared-store-sqlite.md` B.22). iOS lane; `ios-smoke.sh`; Swift; **📱**: the debugger's `_simulateLaunchForTaskWithIdentifier:` + `_simulateExpirationForTaskWithIdentifier:`, then **three real nights** logging wakes granted, minutes per wake and photos per wake. ⚠ A forced launch passes without a submitted request; only the real nights show the request is armed. App Review: a `processing` background mode, justified as "index photos on device while charging" | runner/expiry: an expiration mid-pass cancels at the next photo and the store resumes; no wake ⇒ `allowIndexing()` false; the handler re-arms before it works; the task is completed exactly once on a normal finish, a failure, an expiration, and an expiration racing a finish | the library finishes indexing overnight on the charger |
| **PR-6b** ✅ **built (§17, 2026-11-11), behind the AI flag; 📱 owed (§17.6).** New arrivals while Photos is open (IN-D6a, decided 2026-11-11). ⚠⚠ As built: the batch is over the **arrived paths alone**, read from the file service's modification log — never a walk (§17.3 decision 1); a batch **yields** to a running wake rather than replacing it, and an unknown battery is a no (decisions 2 and 4) | A trigger in Photos' runtime: when the library watch reports new photos and Photos is in the foreground, start a pass through `MediaIndexRunner` capped at the IN-D6a batch (50 to start), only for capabilities whose consent is on and whose model serves; debounced so a burst of arrivals is one pass; `ForegroundIndexingConditions` as for *Index now*; cancelled on leaving the foreground (it never asks for continued processing — that is *Index now*'s); progress on *Index now*'s surface. ⚠ A batch that finds more than the cap indexes the cap and leaves the rest to PR-6 or *Index now*, never chaining. ⚠⚠ People must re-read after the pass (the iOS refresh fix found in the F8 device pass), or the batch runs and nothing shows | iOS lane; `ios-smoke.sh`; Swift if the trigger lives there; 📱 upload one photo in Drive, open Photos, see it in search and People without a press | runner single-flight with *Index now*; no trigger in the background; cap honoured; no consent or no model ⇒ no pass; a burst debounced to one pass | a new photo is searchable and grouped moments after Photos opens |
| **PR-7** ✅ **built (§15), behind the flag; 📱 owed (§15.6).** *Index now* continues (iOS 26+). ⚠ The identifier's base is `app.tenshu.photos.ios.indexnow.*` (§15.3 decision 2), and it carries the pass under the store's task token (§15.4, B.23) | `BGContinuedProcessingTask` around a user-started pass, `#available`-gated. ⚠ It needs its **own wildcard identifier**: `BGTaskSchedulerPermittedIdentifiers` gains `app.tenshu.photos.indexnow.*` (a continued-processing request's base must end in `.*`, and each job submits a unique suffix, e.g. `app.tenshu.photos.indexnow.<uuid>`) [D: Apple]; ⚠ the wildcard is only the plist entry: the **fully composed** identifier, suffix included, is what is both registered and submitted, so *Index now* registers the handler for the generated identifier immediately before submitting the request with it (continued-task handlers may be registered when the user expresses intent, unlike launch-registered `BGProcessingTask`s) [D: Apple, WWDC25 session 227]. PR-6's fixed `app.tenshu.photos.processing` cannot serve it. ⚠ **`.fail`, never the default `.queue`**: a queued request may not launch its handler when the user presses *Index now*, so either the button would start nothing, or the foreground pass would start alone and be cancelled on leaving the app while the task is still queued. The request is submitted with `strategy = .fail` [D: Apple, *Choose a processing strategy*]; on acceptance the handler owns the pass, and on rejection *Index now* runs PR-5's ordinary foreground pass, which cancels on background as before. The press always starts work at once. ⚠⚠ **A progress and completion bridge**: `MediaIndexRunner.start()` returns at once, so wrapping the call exposes neither. ⚠ `IndexProgressHolder` cannot carry the outcome: it publishes the same terminal `null` for success, refusal, pause, cancellation and failure (`IndexProgressHolder.kt:64-66`), `MediaIndexRunner.start()` discards its `Deferred`, and `onPassFinished` is not reached from the exception path (`MediaIndexRunner.kt:176-196`) [C]. So PR-7 adds a **terminal outcome** to the runner — an awaitable result per started pass (completed, refused, cancelled, failed), set in the same `finally` that clears the bar — and PR-6's bridge uses it too. The handler observes `IndexProgressHolder` for progress only, maps done/total onto the task's `progress` (a task that reports none is expired by the system [D: Apple, WWDC25 session 227]), and calls `setTaskCompleted(success:)` from the terminal outcome: true on completion, false on a failure, a refusal or the expiration cancel | ~~**Needs S0b-2 merged**~~ (met, B.22): a continued pass reads the shared replica in the background. 📱 on iOS 26; Swift | leave-app-mid-pass continues with system progress; a rejected submission still starts the pass at once as a foreground pass that cancels on background; the bridge on a fake runner: progress advances with the holder, a finished pass completes the task with success, a **throwing** pass and an expired one with failure (the runner's outcome distinguishes them where the holder cannot), and none is left uncompleted; with a live continued task the background hook does **not** cancel, and without one it still does (§3.3); expiry stops at the next photo | *Index now* survives leaving the app |

**Order and why.**
PR-0 ✅ → PR-0b ✅ → PR-1 → (PR-2 ∥ PR-2b ∥ PR-4) → PR-3 → PR-5 (flag off) → *S0b-2* ✅ → PR-7 ✅, PR-6 ✅ → PR-6b.
- **PR-0 and PR-0b are done.** PR-0 was a GO (§2.1); PR-0b passed and made Core ML the route (§2.2).
- **PR-1 next:** everything after it assumes its numbers: decode time, compile time, disk, heat. It
  needs no App Group, so it runs on the phone today. Its product-graph change is `iosMain` and
  `commonMain` code that nothing calls.
- **PR-2 waits for the pad-id fix**, so the tokenizer moves once, with the right pad id.
- **PR-2b after PR-1's measurement**, so the published archive is the measured one. It needs the
  maintainer's signing key.
- PR-2, PR-2b and PR-4 are independent of each other. PR-2 and PR-4 are smoke-gated, so both are local
  sessions.
- PR-3 before PR-5, because a backend must never be bound while the stores are still shared. PR-3's
  `meta.epoch` half follows S0b-1.
- PR-5's flag stays off until its 📱 pass, which waits for S0b (a)–(d) and for PR-2b's publish.
- PR-7 and PR-6 both waited for S0b-2, which is met (`ios-shared-store-sqlite.md` B.22: the SQLite store
  is on in every build, no switch). PR-7 came first, the user-started route; PR-6 is built (§16). Both
  stay behind the AI flag, and both owe their 📱 passes.
- PR-6b (IN-D6a) follows PR-6: PR-6 takes the backlog, so the small foreground batch can stop at its
  cap. ⚠ PR-6b's trigger goes through `MediaIndexRunner` (its capped batch, `runArrivalsBatch`).
  ~~A wake running when it fires is replaced by it~~ — **amended as built (§17.3 decision 2):** the
  batch is the runner's *automatic* kind, so it **yields** to any running pass, a wake's included, and
  is tried once that pass ends; a wake that starts while a batch runs replaces the batch. ⚠ A batch
  that completes the library also makes the next leave arm no wake (§16.3 decision 4); a batch that
  stopped at its cap does not (§17.3 decision 5). Android's half of IN-D6a is its own change in Control (`inference-service.md` IN-D6a).
- **Faces (AI-F, PIO-8)** reuse every piece here. They add Vision for detection (§7 Q5 of the parent),
  a Core ML export of the face embedder, and the A40 work. Not in this plan.

---

## 6. Risks and unknowns, ranked

1. **⚠⚠ Store ownership across processes** (High). Today's logs assume one process and write in their
   constructor and their wipe hook (§1.5) [C].
   - **Mitigation:** PR-3's four locks plus the realm stamp, re-checked at four points (§3.1).
   - **Residual:** a Photos-private store goes stale if Photos never runs after a reset. It is cleared at
     the next Photos launch, before any read, so nothing is served from it.
   - ~~**Residual until S0b-1:** the stamp is the owner id alone, so a reset that keeps the owner id is not
     seen.~~ ✅ Closed 2026-11-10: the `meta.epoch` half is built (§13.2); a resident graph that met the
     wipe reads no epoch and its checks refuse until it is rebuilt.
   - **Residual from v0.4:** lock 1 is no longer enforced by the linker (§3.1). PR-3's test is the guard.
2. **⚠⚠ Background execution on a phone** (High).
   - The App Group build is not installable yet (S0b a–d).
   - ~~A background pass needs S0b-2's quiesce step.~~ Met (B.22, B.23); PR-6 is built (§16).
   - `BGProcessingTask` scheduling is opaque: when, how often and how long are all the OS's [I]. It may
     grant one window a night or none. Only PR-6's three-night log answers it.
   - Until then *Index now* is the honest route. How long a 5 000-photo library takes on iOS is **not
     known**: on camera originals predict is ~13 ms and decode ~20 ms, ~37 ms a photo wall clock [M: one ~20 s pass, §9.7], so ~3 minutes by extrapolation [I]; nothing longer than that pass was run. Leaving the app cancels it (§3.3).
   - **Mitigation:** PR-6 (D8) and PR-5 land behind a build flag; PR-6's three nights are the measurement.
3. **⚠⚠ Disk** (High). 743 MB to download (~709 MiB) and **~2.58 GB** on disk after the prepare step
   with the archive kept, against ~383 MB on Android (§3.4; measured on the iPhone 17e, with the
   first-open space counted as not returned). An update is budgeted at the same ~2.58 GB more and peaks at ~5.16 GB, until the deletion experiment allows less.
   A phone short of space fails late, after the download.
   - **Mitigation:** a free-space check before the download; the Models screen states the on-disk
     figure; PR-1 measured the deltas (§3.4); D14 would take 743 MB off the steady state, not half of it.
4. **⚠ Heat** (Medium). Predictions back to back reached `serious` in 245 s on the charger (§2.2) [M].
   A real pass is decode-bound and slower, so that is an upper bound, but the realistic duty cycle is
   unmeasured. **Mitigation:** thermal backoff and chunking in PR-5, for the foreground pass, sized by
   PR-1 (§3.3, §4.5).
5. **⚠ Model download** (Medium). ~709 MB in three artifacts.
   - **No metered gate exists in the downloader** [C: none in `ModelDownloader.kt`]. ⚠ In the shared,
     in-process one. Photos' graph no longer resolves it: its downloader is `BackgroundModelDownloader`,
     whose requests refuse cellular and expensive networks unless the download's own choice allows them (§14.11).
   - An in-process Ktor fetch stops when Photos is suspended. Staging resumes it next time
     (`DefaultModelStore.beginStaging`, `:111-125`) [C].
   - **Mitigation:** Wi-Fi only by default, with an explicit cellular confirm (§7 D6); a background
     `URLSession` with a durable journal from PR-5 on (`drive-ios/Sources/BackgroundUpload.swift:32` is
     the Drive precedent) [C].
   - ⚠ The model index must die with the bytes (§3.1), or a reinstall reports a model it no longer has.
6. **⚠ The export toolchain and its provenance** (Medium). §2.1's conversion needed a hand patch to an
   untested coremltools/torch pairing [M], an `.mlpackage` may not be byte-reproducible across runs
   [I], and the repo pins no upstream revision for any artifact (§2.2).
   - **Mitigation:** the export script pins its versions, records the revision, and checks its own
     output against fp32 before it writes (§3.4). The published digest pins bytes, not reproducibility.
7. **⚠ Vectors across an OS update** (Medium, unknown). A new OS may compile the model differently
   [I]. **Mitigation:** the fingerprint records the OS build, and until a measurement says otherwise a
   new build clears the vectors (§3.2). Re-indexing is cheap at these speeds; a wrong ranking is not.
8. **Memory and the device floor** (Medium–Low; was High). On the iPhone 17e the two models take 19 MB
   and 13 MB [M]. Unmeasured: the vocabulary map under Kotlin/Native (PR-2), the corpus run (PR-1), and
   every phone that is not the 17e. A background app's jetsam limit is lower than a foreground one's
   and undocumented [I]. **Mitigation:** D12's three gates; both models dropped when a pass ends.
9. **Readiness, and one stamp for every artifact** (Medium). The backend is bound once and a live state
   decides what it claims (§3.2). The registry asks `supports` per call, so the seam holds [C]; what
   can go wrong is the **order** of a state change. An artifact swap that lets a pass keep writing
   would mix two artifacts' vectors under one `ModelProvenance`, and nothing downstream would refuse
   them. **Mitigation:** the ordered transitions of §3.2 (not-ready first, cancel, close; clear the
   vectors before `Ready`), each pinned by a PR-5 test that is seen red once.
10. **Two backends drifting** (Medium). The guards are the part that is silently wrong when it drifts
   (computable and meaningless, §16.1). **Mitigation:** extract them (PR-2); both backends in Kotlin.
11. **One model id, two artifacts** (Medium–Low). The `onnx` and `coreml` entries share an id and a
    version (§3.4). A build that ever contributed both runtimes would take the first silently.
    **Mitigation:** the signer refuses a duplicate `(id, runtime)`; Photos contributes one runtime; the
    round-trip test pins it. The label-calibration half is D13.
12. **App Store review** (Medium–Low).
    - **The model path is the documented one:** download, then `MLModel.compileModel(at:)` on the
      device [D: Apple, *Downloading and Compiling a Model on the User's Device*]. Whether Review ever
      reads Guideline 2.5.2 (downloaded code) onto a downloaded model is [I]; a documented system API
      is a far better position than a third-party runtime interpreting a downloaded graph.
    - **No third-party SDK is embedded,** so there is no SDK privacy manifest or signature to supply.
      v0.3's ORT gap is gone.
    - Photos still owes its **own** `PrivacyInfo.xcprivacy`: its Kotlin reads free disk space
      (`IosFileDatabase.kt:232-236`) [C], a required-reason category [D: Apple]. There is no
      `PrivacyInfo.xcprivacy` anywhere in the repo [C: none found]; the repo-wide gap is `tech-debt.md`'s.
    - PR-6's `processing` background mode needs a justification [I].
    - Checked at PIO-9.
13. **Tokenizer parity on iOS** (Low–Medium). Foundation NFKC vs `java.text.Normalizer` differ only in
    Unicode version [I]; Kotlin/Native's `Regex` may treat `\s` differently [I]. And the pad id is
    wrong on master until the pad-id fix. **Mitigation:** PR-2 starts from that fix and runs the same golden token
    ids and an NFKC parity table on JVM and iOS.
14. **Kotlin/Native over Core ML** (Low). The symbols exist in the platform library [C]; the exact
    signatures, and whether all ten apps link `CoreML` and `ImageIO` without a `project.yml` change,
    are [I]. Owed to PR-1's build of all ten.
15. **Vectors compared across devices** (Low). Nothing does: embeddings are device-local (IN-D5,
    `ImagePreprocessing.kt:51-61`; IN-D13's last paragraph) [C/D]. Synced labels are thresholded, which
    does not make two artifacts' labels the same (`models.md` §3.10.5 [M]); the first writer's stand
    (D13). iOS's upright decode (§1.2) and its fp16 artifact change which labels an iOS pass would
    emit, and labels are inert today (§27.2) [D].

Retired with ORT: cinterop over ORT's C headers; binary size (Core ML is a system framework, and the
Kotlin backend is KBs [I]); the CoreML execution provider.

---

## 7. Decisions for the maintainer

| # | Decision | Recommendation | Cost of the recommendation |
|---|---|---|---|
| **D1** ⭐ **decided 2026-10-04** | Our own weights on iOS: ORT, or a converted Core ML model | **Decided by the maintainer: Core ML fp16, no ORT on iOS. Its condition, PR-0b, is met (§2.2).** PR-0 measured the vision tower at **5.9 ms vs 112 ms** for ORT's `cpu-d4`, and the ORT CoreML EP failed in both formats (§2.1). ⚠⚠ Vision alone could not decide it: search compares an `embedText` vector with the stored image vectors (`PhotosLikeImplementation.kt:100-117`) [C], and the catalog treats vision, text and vocabulary as one checkpoint (`ModelCatalogData.kt:79-85`) [C]. PR-0b therefore converted **both towers from the same checkpoint in the same conversion** and ran an end-to-end retrieval check on the phone: 35 of 36 top-1 against fp32, where the shipped ONNX pair scores 23 of 36. ⚠ v0.1's argument, *one vector space everywhere*, is not a constraint: vectors are device-local (IN-D5, `ImagePreprocessing.kt:51-61`) [C], and `supportedModelRuntimes` (`ProxyModule.kt:317`, filtered in `ModelCatalog.kt:159`) [C] already lets a platform take its own artifact. This **amends IN-D2 for iOS**, and `inference-service.md` IN-D2 carries the amendment (PR-0 / PR-0b numbers; PR-1 adds the corpus figures). Rejected: Vision feature prints (no text tower, so no search by description); MobileCLIP (English-only, licence unchecked); ExecuTorch / LiteRT (no gain over Core ML itself); ORT (`cpu-d4`, 112 ms: a GO on its own, kept as history in §2) | A **second signed catalog artifact** with its own export script and source record (§3.4); ~709 MB to download instead of ~383 MB, and more on disk (D14); **per-artifact label calibration** (cheap today: labels are inert, §27.2) |
| **D2** ~~CPU vs CoreML EP~~ | — | **Superseded by D1.** There is no execution provider. What replaces it: the compute units are a build constant, `.cpuAndNeuralEngine` for both towers (§3.2), measured in §2.2 | — |
| **D3** ~~Photos-only vs umbrella linkage of ORT~~ | — | **Superseded by D1.** Nothing third-party is linked. The backend's Kotlin lives in the umbrella and Core ML is a system framework. ⚠ The Photos-only **binding** (§3.1) is not this decision and stays | Lock 1 of §3.1 is no longer enforced at link time |
| **D4** ~~How ORT is obtained~~ | — | **Superseded by D1.** No fetch script, no vendored headers, no pinned archive | — |
| **D5** | Does iOS index on battery? | **Scheduled: never** (`requiresExternalPower`, and in-use is always true in the foreground). ***Index now*: yes**, unless Low Power Mode, below 20 % and not charging, or thermal ≥ `.serious`; revisit with the harness's battery run. ⚠ **Thermal backoff applies on the charger too**: §2.2 reached `serious` plugged in. ⚠ **IN-3m-iOS (§9.7, completed 2026-10-23) changes neither half**: the battery run triggered nothing (one 20 s pass at 100 % cannot test the 20 % line), and on the charger `.serious` came after ~4 700 photos with no slowdown, which bears on the backoff (§14.19.8), not on D5 | A user on battery must press the button; a large library will not finish on battery; a warm phone pauses a pass the user started |
| **D6** | Model download over cellular | **Wi-Fi only by default**; the screen states the size first (IN-D10: ~709 MB, and the on-disk estimate, first-open allowance included, §3.4) and offers "Use cellular data" per download. ⚠ **Enforced in the transport, not only in the copy**: the background `URLSession` request sets `allowsCellularAccess = false` (and `allowsExpensiveNetworkAccess = false`) unless that download's choice allows cellular — the default allows it [D: Apple] — so a download started on Wi-Fi **waits** when the phone moves to cellular rather than spending ~709 MB of data, and resumes on Wi-Fi | A small preference and a sentence; ⚠ a device-local preference row, not a schema change |
| **D7** | Store location | **Photos-private Application Support, excluded from backup** (§3.1) — not the App Group with a cross-process lock — **with the model index moved beside the bytes**, out of the Keychain. The compiled models live under the same root (§3.4). ⚠ `Library/Caches` is rejected harder than in v0.1: a purge deletes the bytes and leaves a Keychain index that still names them, and `pathOf` never checks (`DefaultModelStore.kt:283-285`) [C] | A realm stamp to keep honest, checked at four points (§3.1); an index port over `FileDatabase` in PR-3; the alternative is a multi-writer redesign of two logs |
| **D8** ✅ **built as PR-6 (§16, 2026-11-11)** | Background driver | ~~Deferred until after S0b-2~~ (met, `ios-shared-store-sqlite.md` B.22). Built as written: a thin Photos-only processing wake, shaped as S2's handler, rather than waiting for S1/S2 | No overnight indexing until then; later, a Swift driver that S1c deletes (as it will delete Drive's, Fit's and Control's) |
| **D9** | The harness: in the app or separate? | **Separate harness app + its own XCFramework module** (§4.2). Still needed after D1, for a different reason: the umbrella exports no inference module, so Swift cannot reach the shipped backend through `TenshuSdk` | A tenth xcodegen project and a module; nothing of it ships. Simpler than v0.3's: no shim, no fetched binary |
| **D10** ⚠ **open (was: No)** | `increased-memory-limit` entitlement | **Open since 2026-10-07.** It was *No*, on the two models taking 19 MB and 13 MB under `.cpuAndNeuralEngine` (§2.2) [M], to be reopened *if PR-1's corpus run says otherwise*. The partial run (§9.7) neither confirms nor overturns that, so the *No* does not stand on it: ① the harness ran **one tower per process**, and Photos holds the vision and the text session together, beside the app itself; ② a pass peaked at 171–190 MB in the harness, a path that reuses no buffer and is not `CoreMlInferenceBackend`'s; ③ only the iPhone 17e was measured, and it is not the floor (D12). **What closes it:** `phys_footprint` of **Photos itself**, both sessions open through `CoreMlInferenceBackend`, over a pass of camera originals with a `photosLike` query during it, read against the foreground memory limit of the phone it ran on; on the iPhone 17e, and on a phone at D12's intended floor. That is the both-sessions footprint D12's preflight ② needs too. ⚠ A product build of Photos on a phone waits for S0b, so until then the nearest reading is a harness row that opens both towers and drives the backend | Until it closes, PR-5 does not assume the entitlement is unnecessary, and claims none either |
| **D11** | Retire the iOS `mediaIndexPass` registry row | **Yes** (§3.1): Photos drives its passes directly | `ios-scheduler-lease.md` §3.2's table loses a row; S1's planner never has to classify it |
| **D12** | Which iPhones get AI-L | **Decided: gate on device capability, in two halves, after an OS floor.** ⓪ **iOS 17 or later**: the fp16 artifact is an ML Program converted with an iOS 17 deployment target (§2.1, §3.4), so an iOS 16 phone could download ~709 MB and then fail to open it. Photos keeps its iOS 16 floor (PIO-D11); **AI-L** does not — below iOS 17 the Models screen offers no download and search keeps the `NotBuilt` line, decided before catalog eligibility, not after the download. Lowering it needs an iOS 16-target conversion re-verified through §2.2's retrieval gate. Owed with PR-5: a load test of both towers on the lowest admitted OS (an iOS 17 simulator runtime at least). ① `ProcessInfo.physicalMemory` ≥ a threshold **set from measurement** (PR-0 / IN-3m-iOS `phys_footprint` plus headroom), never by model name. ⚠ Memory is *capacity*, not speed or the app's jetsam budget, and §4.1 says device classes don't compare — so ① alone admits phones nobody measured. ② A **runtime preflight** after the download and before the first pass, over **the whole search path, not vision alone**: the vision session times a short fixed batch (synthetic input, same as PR-0), the vocabulary loads and tokenises a fixed query, and the text session embeds it, each timed, with `thermalState` read and `phys_footprint` taken with **both** sessions open. ⚠⚠ **A pass also requires valid output, not only speed.** §2.1 saw a Core ML path *run* and return 768 NaNs; `ImagePreprocessing.normalise` propagates NaN, and the shape guard checks only the width. So the probe requires both vectors to be finite and non-zero with a sane norm, and runs a **fixed cross-tower check**: a bundled probe image and two fixed captions, one that describes it and one that does not, must rank in that order with a margin recorded for this artifact (`scripts/inference/preflight-probe.py`; as built, §14.6). A failure here is a wrong-output failure, treated like a load error (retried, then a failed install), never a `Ready`. The backend applies the same finite, non-zero guard to every vector it returns, so a later bad output is refused rather than stored. Description search needs all three, and `supports` is vision-only today (`OnnxInferenceBackend.kt:112-113`) [C], so a vision-only pass could enable a search whose every query fails. Above §4.5's latency row on either tower, a tower or the vocabulary that will not load, or a footprint that leaves less than the measured headroom, AI-L switches itself off on that phone with the same copy and keeps the weights deletable. ⚠ **`Unsuitable` is persisted only for what the phone *is*, never for what it was doing.** The verdict is keyed to a key (the preflight key, §14.2) that cooling down or relaunching does not change, so a transient failure persisted would switch AI-L off for good after a ~709 MB download. A probe that starts at `thermalState` ≥ serious, runs under a memory-pressure warning, or fails to *load* (a Core ML error rather than a slow result) is **retryable**: the state stays `AwaitingVerdict` and the preflight runs again on the next foreground, with a bounded backoff. `Unsuitable` needs repeated slow or over-budget probes (three, on separate foregrounds) each started at nominal or fair; a load error that repeats past its bound is reported on the Models screen as a failed install, whose way out is a removal and then a download (M12: a download of an install that is whole fetches nothing), not as an unsuitable phone. ⚠⚠ **A load the process does not survive is counted, and two of them are `Unsuitable`** (as built before 5e, §14.5 item 0): a probe, or a prepare's first open of a tower, writes an `AttemptBegun` under the preflight key into the verdict's file **before** it loads anything, and clears it on every ending the process lives through (an outcome, a failure, Photos leaving the foreground, a teardown); a record the next process finds is a load jetsam or Core ML ended, counted in `PreflightAttempts.interrupted`, feeding the backoff — **unless the scene had left the foreground it began in**: a load frozen there and ended by iOS is transient, never a kill (`SceneLeft`) — and at `INTERRUPTED_LOADS_FOR_UNSUITABLE` = 2 the key is answered `Unsuitable`, with no automatic load of it again (the prepare compiles nothing for it either), the weights deletable, and Delete unblocked because nothing holds the step lock or a pair of towers. Without it, a phone where loading the towers gets Photos killed was loaded again at every launch and every return to the foreground. That fail alone has one user-started way out (*Try again*, `AiReadiness.loadAgain`); three slow probes keep this rule's. A model not installed whose key is answered is offered no download. ⚠⚠ **The verdict is a live readiness state, never a graph change** (§3.2, *Readiness*). Photos binds the backend and the `AskThisDevice` route **once**, at assembly, on a phone that clears ⓪ and ① and the build flag, and they stay bound for the life of the process. What ② decides is whether the backend *claims* the capability: `supports` is asked on every call (`InferenceBackendRegistry.kt:20-21`) [C], and it answers from the readiness state. The verdict is **persisted** beside the runtime fingerprint (§3.2) and keyed on the **preflight key** (§14.2: that fingerprint, plus the vocabulary's digest, the hand-bumped `PREFLIGHT_REVISION` and the app build), so a new archive digest, vocabulary, compute-units constant, preprocessing revision, OS build or preflight revision re-runs the preflight, while only the fingerprint's own parts clear vectors; the preflight itself runs on sessions opened outside the registry, so it works while the backend claims nothing. **Both directions take effect at once, with no relaunch and no second graph:** a pass makes the next `photosLike` and the next *Index now* find the backend; a failure, or a replacement artifact that fails, makes them refuse with `NO_BACKEND`, and search shows the *"isn't available on this iPhone"* line. ⚠ A replacement is preflighted **before it becomes current**: from the moment the store commits it until its verdict, the state is not ready, and the old artifact is not served in between (the store has already declared its files garbage). v0.4's first draft rebuilt Photos' Koin graph on a verdict change; that is **withdrawn**: `PhotosSurface` and SwiftUI's `@StateObject`s keep the old repository (`PhotosSurface.kt:61-66,103-112`) and `deprovision()` cancels a scope without closing the graph (`PhotosLocalRuntime.kt:150-163`) [C], so a second assembly would have run a second client and model store beside the first. The threshold in ① is lowered only by measuring a phone at the new floor. The measured iPhone 17e is not the floor: iOS 17 still runs on 3 GB phones [I: Apple's support list, not re-checked]. ⚠⚠ **IN-3m-iOS (§9.7) does not give ① its number yet.** Its 171–190 MB pass peak is the harness's: it drives the session directly and reuses no buffer, where `CoreMlInferenceBackend` does (§10). Still owed before ① is set: the footprint of a pass through the backend, and a phone at the intended floor | Older phones keep *"isn't available on this iPhone"*; a phone that passes ① but fails ② is told the same after its download (one wasted download, never a slow or killed Photos); both numbers are re-justified when the model or the compute units change |
| **D13** ⭐ **decided 2026-10-07; its label half decided 2026-10-11** | The Core ML artifact's catalog identity | **Decided by the maintainer: the same `id` and `version` as the ONNX entry, a different `runtime`** (§3.4; built in §12). A client picks by its `supportedModelRuntimes`. It is the same checkpoint, one `VisionModel` constant describes both, and a second id would make each platform's synced label rows stale on the other. ⚠ **One stamp then covers two precisions.** That is harmless for vectors (device-local). For labels it was left open on 2026-10-07 and measured on 2026-10-10: per-artifact thresholds do **not** make the two platforms' labels interchangeable. ⭐ **Decided by the maintainer 2026-10-11 ([`inference-service.md`](inference-service.md) §39.1): the first writer wins.** Whichever device derives a photograph's labels first writes them under its own artifact's floor table; another device does not label a photograph already labelled for the same model id, version and label-set version, whatever artifact did it; the row records which artifact wrote it. Accepted: a borderline photograph is or is not tagged depending on which device indexed it first, and does not flip. Rejected there, with reasons: unsynced per-device labels, moving Android to an fp16/fp32-equivalent pair, and one shared threshold table. The alternative to the id decision, a separate id, stays rejected | The signer rule is built (no duplicate `(id, runtime)`; entries sharing an id share a version, §12). ⚠ **Still owed:** the rule that a build contributes one runtime per id is a convention with no check in the client (§12.5), and, before labels leave *inert*, everything `inference-service.md` §39.4 lists: first of all that a label is stamped with the **backend's** id today (`onnx-runtime` / `core-ml`), not this shared stamp, so each platform would read the other's rows as stale (§39.1.1); then recording the artifact on the row, the rule for two writers that raced, a label-set bump, a recalibrated table and the stale sweep. ⭐ **What the label half was decided on, measured 2026-10-10** ([`models.md`](../models.md) §3.10, `scripts/inference/label-eval.py`; COCO val2017, 37 object labels, host): with a floor per label **per artifact**, the ONNX pair and the Core ML pair agree on **78 %** of the (image, label) pairs either writes (69 % under one shared table) and write identical label sets for 78 % of images. ⚠⚠ **So thresholds do not make them interchangeable.** The Core ML pair agrees with fp32 on 98.8 %; the distance is the ONNX pair's quantisation, and as large between onnxruntime 1.20.0 and 1.29.0 on the same bytes. ⚠ Not measured: scenes, documents, screenshots, a phone |
| **D14** ⚠ **new, open** | Keep the downloaded archive after the model is compiled? | **Keep it in the first cut**, and decide with PR-1's numbers. Keeping it leaves the model store's contract untouched (a record means verified bytes are present) and makes a recompile local. Dropping it after a successful prepare takes the conservative steady state from ~2.58 GB to ~1.84 GB [M: §3.4, §9.7], not to ~709 MB as v0.4 computed, makes a recompile a ~743 MB re-download, and needs the store to represent *installed, source reclaimed*, which is a change to shared code (`DefaultModelStore`) | ~2.58 GB on disk instead of ~1.84 GB (§3.4: measured, first-open space counted as not returned; v0.4 computed ~1.41 GB against ~709 MB), stated on the Models screen as an estimate that includes the first-open allowance (§3.4), not as the sum of the two sizes the code can see (~1.48 GB). If PR-1 finds the volume clones the weights, the cost is near zero and this closes as *keep*. **PR-1's numbers (§9.4, repeated in §9.7):** unpacking costs the archive's full size, so keeping the archive is not free and this does not close as *keep* on that ground. ⚠ Still owed: where the further 370 MB (vision) and 728 MB (text) taken at first open are kept, and whether they are returned. Until that is measured §3.4 budgets them as kept, and the archive is the smaller part of the footprint: dropping it saves 743 MB of 2.58 GB. ⚠ The cache's `3 ×` factor is consistent with the measurement and has no margin for vision (§3.4); a follow-up is owed there, not a change here |

---

## 8. Inferred, not verified

- The `BGProcessingTask` window length and frequency on a real phone; a background app's jetsam limit.
- ~~`BGContinuedProcessingTask`'s availability and exact API (iOS 26).~~ **Read** [C: the iPhoneOS 27.2
  SDK's `BackgroundTasks` headers, §15]: iOS 26.0+, `strategy` `.fail`/`.queue` (default `.queue`),
  `submitTaskRequest(_:completionHandler:)` from iOS 27 (the throwing `submit` deprecated there), and the
  identifier's prefix must contain the bundle id. Its behaviour on a phone is still 📱 (§15.6).
- Whether `devicectl device copy to` takes a directory.
- The Kotlin signatures of the Core ML calls in `platform.CoreML`, and that all ten apps link `CoreML`
  and `ImageIO` with no `project.yml` change.
- ~~That a `.mlmodelc` is about as large as its `.mlpackage`.~~ **Measured** [M: §9.7]: 184.9 MB and
  555.6 MB compiled against 184.8 MB and 555.5 MB of archive. ⚠ The on-disk figures of §3.4 did **not**
  follow from it: the first open takes ~1.1 GB more, and whether that is returned is still unknown.
- ~~How long `MLModel.compileModel` takes.~~ **Measured** [M: §9.7]: 61 ms (vision) and 82 ms (text); the
  first open after it is 2.4 s and 3.8 s.
- That a compiled model survives an OS update without a recompile, and that the first-open cost returns
  after one.
- That an OS update leaves the vectors unchanged, or changes them.
- That Core ML runs on the simulator well enough for the `iosTest` cases, and that a K/N test can read a
  fixture file by path.
- That fp16 weights do not compress usefully.
- ~~That an `.mlpackage` is not byte-reproducible across export runs.~~ **Measured, PR-1** [M]: two runs
  of the pinned toolchain on the same checkpoint gave different vision and text archive digests (the
  vocabulary's was identical). So the signed archives are the artifact; a re-export is a new artifact
  that owes the retrieval gate again (§3.4).
- That a traced model converted without explicit names gets generated input and output names.
- ~~The vocabulary map's footprint under Kotlin/Native.~~ **A first reading, PR-2** [M: simulator]: +6.4 MiB
  of Kotlin heap, under both models' 32 MB (§10.4). The phone's `phys_footprint` is still owed.
- That the iOS 17 floor still includes 3 GB phones.
- Whether App Review reads Guideline 2.5.2 onto a model downloaded and compiled through Core ML.
- That a reinstall with a surviving Keychain index makes the backend fail on every image (§3.1), rather
  than failing once and latching.
- ~~That a background `URLSession` download can land in `DefaultModelStore`'s staging path without a
  second staging scheme.~~ **As built, 5c-1** [C, and M on the simulator's filesystem]: the delivered
  file is hashed where the session's delegate left it and then **moved** into the store's own staging
  (`ModelStore.adoptStaged`, §14.11). One staging scheme; the artifact is on disk once.
- That closing a `CoreMlSession` while Photos stays resident releases its memory and its Neural Engine
  resources, so an artifact swap in a running process leaves no old model loaded (§3.2, *Readiness*).
- That CPH2581 is the OnePlus 12 (§4.1).
- That foreground suspension mid-pass resumes cleanly in place, apart from the shared-lock case S0b
  covers.

---

## 9. As built — PR-1 (2026-10-05)

Marks as above. **[M*]** is a reading from the stand-in run below: taken, on the phone, and not IN-3m-iOS.

### 9.1 What shipped

| Piece | Where |
|---|---|
| The export script, its pinned toolchain, `--fixture` | `scripts/inference/export-coreml-towers.py`, `scripts/inference/requirements-coreml-export.txt` |
| `TarReader` | `inference-service-proxy/src/commonMain/…/catalog/TarReader.kt` |
| `CoreMlTowerSpec` (both guards, as arithmetic), `CoreMlSession` + `CoreMlSessionPort`, `CoreMlModelCache`, `IosImageDecoder`, `IOS_PREPROCESSING_REVISION` | `inference-service-proxy/src/iosMain/…/coreml/` |
| The harness: `In3mHarness`, its probes, the vector file | `:inference-harness-ios` (`inference-harness-ios/src/`) |
| The harness app and its driver | `inference-harness-ios/project.yml`, `Sources/In3mHarnessApp.swift`, `run-in3m.sh` |
| Four fixture ML Programs, 20 KB each | `inference-service-proxy/src/iosTest/resources/coreml/` |

No DI binding, no catalog entry, no schema change: nothing in a product graph calls any of it.

**The export toolchain needs no patch.** Python 3.13, coremltools 9.0, torch 2.7.0, numpy 2.2.6,
transformers 4.53.3 convert both towers unmodified [M: run on the build Mac]. The cause of §2.1's
hand patch was numpy, not torch: numpy 2.4 made the size-1-array-to-scalar conversion in
coremltools' `_cast` an error. torch 2.7.0 is also the newest torch coremltools 9.0 names as tested
(`_TORCH_MAX_VERSION`) [C]. The script refuses to run outside the pinned set unless told to, and
the manifest then says so.

- Its self-check against fp32 PyTorch, three fixed inputs a tower: vision 0.999989–0.999995, text
  0.999997–0.999998 [M]. The archives are 184.8 MB and 555.5 MB (the same sizes as §3.4's, which
  are in MiB).
- The vocabulary it packs has the sha256 of the ONNX export's `tokenizer.bin` [M], as §3.4 assumes.
- ⚠⚠ **These are new archives, from a different toolchain than PR-0b's.** §3.4's rule stands: the
  multilingual retrieval gate is re-run on them before PR-2b publishes. **Done 2026-10-06: the
  gate is in `scripts/inference/` and these archives pass it (§9.6).**
- Not in the export script: the cross-tower probe margin D12's preflight needs. It is measured
  for a finished export by `scripts/inference/preflight-probe.py`, and this export has its record
  (§14.6).

**What §8 listed as inferred and is now known** [M: compiled and run on the simulator and the phone]:
- The Kotlin signatures. `MLModel.compileModelAtURL(url, error)` is the synchronous form and is
  what the cache calls; `MLMultiArray.create(shape, dataType, error)`; several members
  (`multiArrayConstraint`, `objectAtIndexedSubscript`, `compileModelAtURL`) are extension imports.
- Core ML compiles, loads and predicts in a simulator test process (`simctl spawn`), under all
  three compute-units settings.
- A Kotlin/Native test **can** read a fixture by path: Gradle passes the directory as
  `SIMCTL_CHILD_TENSHU_COREML_FIXTURES`. Without it each fixture case prints why it skipped.
- A converted model's names and types are the ones the script sets, and the shape guard reads them
  back from `MLModel.modelDescription`.
- **All ten apps link Core ML and ImageIO with no `project.yml` change** [M: simulator SDK].
  ⚠ The plain `scripts/ios-smoke.sh --build-only` run of all ten says nothing about it: Kotlin/Native
  strips what a framework cannot reach, and until something in a product graph references the new
  classes the umbrella contains none of them (no `MLModel` symbol in `TenshuSdk`). So the ten were
  built a second time with a throwaway reference to `IosImageDecoder` and `CoreMlSession` in the
  iOS `PlatformProxyModule`: the umbrella then carried `_OBJC_CLASS_$_MLModel` and
  `_CGImageSourceCreateThumbnailAtIndex` as undefined symbols, and all ten linked. The reference
  is not in the tree. The harness app names both frameworks itself.

- **The export never writes in place.** `export-coreml-towers.py` builds into a staging directory
  inside `--out` and promotes only a complete, self-checked set, removing the old manifest first and
  writing the new one last. A run that fails part-way leaves the previous export untouched; a
  promotion interrupted part-way leaves no manifest, which a consumer must read as no export.

### 9.2 Deviations from §3–§5, and why

1. **`scripts/regenerate-xcode-projects.sh` does not exist.** The script is
   `.githooks/regenerate-xcode-projects.sh`; the harness is in its list and in the root
   `generateXcodeProjects` task.
2. **`:inference-service-proxy`'s iOS tests had to be switched on.** The root build disables the
   iOS test compile, link and run for every module named `*-proxy`. This module is now the
   exception, which also runs its 429 existing `commonTest` cases on the simulator for the first
   time; they pass.
3. **One directory per archive digest, and an explicit sweep.** Each tower has its own digest, so
   *"directories for other digests are deleted after a new marker is written"* (§3.4) would delete
   the sibling tower. `CoreMlModelCache.sweep(liveDigests)` deletes what the caller does not name.
   When to call it is PR-5's.
4. **The archive holds one top-level `<stem>.mlpackage` directory**, ustar only. The cache refuses
   anything else before compiling.
5. **`prepare` takes the tower's spec**, so its first open (step 6) runs the shape guard. A marker
   therefore means *compiled, loads, and is the pinned shape*.
6. **`CoreMlSession.open` takes the compute units**, defaulting to the shipped constant. Only the
   harness's comparison rows pass another value.
7. **The decoder draws into sRGB**, which §1.2 does not say. An iPhone photo is Display P3.
8. **The harness keeps its compiled models in `Documents/coreml/`**, not Application Support: the
   cache reads its archive from the `FileDatabase` it writes to, and `Documents` is where
   `devicectl` copies. Its vectors are a text file of hex floats, readable off the phone.
9. **The text tower is its own launch** (`tower=text`), so its footprint is not inside a vision row's.
10. **A fix outside the plan: `IosFileDatabase.readBytesRange` was not bounded** (§9.3).
11. `docs/how-to/models-pipeline.md` and `docs/models.md` are untouched: §5 gives them to PR-2b.
12. **The decode size is Android's short-edge rule with a long-edge cap of `16·side`**, not v0.4's
    `2·side` on the long edge (§1.2 has the rule, the memory bound and the ratios). The cap is the
    one place the two platforms' decode sizes differ: Android has none.
    - `IOS_PREPROCESSING_REVISION` **stays 1.** §3.2's rule is to bump it when the transform
      changes under stored vectors, and none exist: the constant has shipped in no build and
      nothing binds the decoder. The first value that matters is the one PR-5 ships.
    - ⚠ §9.4's decode figures were taken **before** this change, with the `2·side` long-edge
      bound. Its 640 × 480 files then decoded at 512 × 384 and now decode at full size, and a
      12 MP original now decodes at four times the pixels it did (1008 × 756, not 512 × 384).
      Decode time on the real corpus is therefore not predicted by that table at all.

13. **A kept vector file carries a fingerprint, and only like is compared with like.** Each file
    opens with `in3m-vectors 3` and records the tower, the row, the OS build, the archive's sha256,
    `IOS_PREPROCESSING_REVISION` and the corpus id (item 14); the digest and the revision are in its name too, so a
    re-export does not overwrite a kept run. The harness compares a run only with files of the
    same tower, archive digest, preprocessing revision and corpus: a different row or OS build is the
    comparison, a different artifact is not. Every other file is named in the report as
    *skipped*, with the reason, and never compared. That includes a file of an earlier format (1 had
    no header, and is what the stand-in run wrote; 2 had no corpus): what it was made with is unknown.
    - ⚠ Why it matters here: two export runs of the same checkpoint give different archive
      digests, so without it a cosine across a re-export would have been reported as
      compute-unit or OS drift (§3.2's probe threshold is set from exactly these numbers).

14. **The measured corpus is a manifest, not a directory.** `devicectl device` offers `copy to`,
    `copy from` and `info files` (a listing) for an app's container and **no delete**; only
    uninstalling the app clears it [C: `devicectl device --help`, Xcode's current tools]. So
    `Documents/photos/` accumulates every push, and the 562-photo corpus pushed over the stand-in's
    300 files would have been measured as their union and reported as `MEASURED`.
    - `run-in3m.sh push-photos` writes `Documents/in3m-corpus.txt` **last**: a corpus id (the
      sha256 over the `name<TAB>sha256` lines), the count, and each photo's sha256, size and name.
    - The harness embeds exactly the named files. It first checks that the manifest's id and count
      describe its own entries, then each named file's size and sha256. A missing manifest, a
      missing file or a mismatch ends the run as `CORPUS_REFUSED`, which the driver's `run` fails.
    - Files in the directory the manifest does not name are counted and listed in the report as
      *not in the corpus manifest*, and never read.
    - The report states the corpus id and count, and the id is in every vector file's
      fingerprint: one photo name in two corpora is not one photo.
    - ⚠ The price: every named file is read once to be hashed before the pass, so the pass's
      *file read* figure is warm. Decode and predict are not affected by that; the few seconds of
      hashing do precede the thermal readings.

### 9.3 ⚠⚠ Found by the harness: the bounded read held the whole file

`IosFileDatabase.readBytesRange` returned an autoreleased `NSData` per call. On a thread with no
run loop nothing drains, so a caller walking a file in 1 MiB windows held the file: the first
phone run showed a footprint of 590 MB after opening the text tower and a ledger peak of
1 148 MB, against 27.9 MB and 36.8 MB once the read was wrapped in an autorelease pool [M*].
That is every bounded caller on iOS, the content plane's chunk reads included, not only the tar
reader. Fixed in `:core-common`, with a simulator test that reads 96 MiB in windows and fails at
95 MiB of growth without the pool.

### 9.4 The stand-in run — ⚠⚠ not IN-3m-iOS

⚠⚠ **Superseded for decode and for the pass footprint by IN-3m-iOS (§9.7).** On camera originals
decode is ~20 ms, not 7 ms, and the pass peaks at 171–190 MB, not ~88 MB. Prediction read 13 ms
there, not 8.7 ms. The open times, the vector agreement and the prepare step below read the same
in that run. ⚠ That run is itself partial: IN-3m-iOS is still open.

Taken to prove the pipeline end to end (export, side-load, prepare, measure). iPhone 17e
(iPhone18,5), iOS 26.6.1 (23G83), on USB power at 100 %, `thermalState` 0 at the start and end of
every row below. **The corpus is 300 public COCO test2017 JPEGs at 640 × 480**, the files PR-0b
used: a stand-in for the 562 camera photos, and a poor one for decode, since a camera original is
12–48 MP. One process per row; a release Kotlin/Native build.

| Row | open ms | predict median / last-50 ms | decode median ms | per photo median / last-50 ms | throttle | footprint after open / pass peak (MB) | cosine vs `coreml-cpu`, mean / min |
|---|---|---|---|---|---|---|---|
| `coreml-ane`, first launch after compile | 24 | 8.9 / 8.8 | 7.4 | 16.5 / 16.5 | 1.21 | 24.3 / 90.7 | 0.99829 / 0.99558 |
| `coreml-ane`, later launch | 78 | 8.7 / 8.7 | 7.3 | 16.2 / 16.0 | 1.07 | 20.8 / 88.3 | 0.99829 / 0.99558 |
| `coreml-all` | 2 583 | 8.8 / 8.8 | 7.2 | 16.3 / 16.4 | 1.17 | 103.3 / 114.4 | 0.99829 / 0.99558 |
| `coreml-cpu` | 163 | 21.4 / 22.0 | 4.7 | 26.1 / 26.9 | 1.10 | 24.8 / 86.6 | — |
| text, `coreml-ane` (200 runs) | 26 | 2.76 / 2.76 | — | — | 1.01 | 27.9 / 36.8 | — |

`coreml-ane` and `coreml-all` vectors agree at mean 1.000000, min 0.999998. No photo failed to
decode or predict in any row.

**The prepare step**, once per tower:

| | unpack ms | `compileModel` ms | first open ms | free space, unpack | free space, first open | free space, whole step |
|---|---|---|---|---|---|---|
| vision (184.8 MB archive) | 331–414 | 58–115 | 2 370–4 715 | −184.9 MB | −370.4 MB | −555.3 MB |
| text (555.5 MB archive) | 1 047–1 272 | 64–73 | 3 687–4 182 | −568.6 MB | −727.0 MB | −1 282.7 MB |

What the stand-in run suggests, each to be confirmed by the real one:
- ⚠⚠ **The first open costs more disk than the compile.** `compileModel` is ~0.1 s and changes
  free space by nothing: the compiled model shares the unpacked weights, and deleting the package
  frees 0.1 MB. But the first open takes **370 MB (vision) and 727 MB (text)** of free space, twice
  over on the text tower's two runs. Where the OS keeps that, and whether it is ever returned, is
  [I]. Counted with the archives, the two towers took ~2.58 GB against §3.4's computed 1.41 GB.
  §3.4's disk table and D14 need the real figures; the cache's free-space check asks for 3 × the
  archive until then. **§3.4's table carries them since 2026-10-07**, with the factor reconciled
  there; the cache's code is unchanged.
- **A pass is decode-bound even on small files** (§4.5): 7.3 ms of ImageIO beside 8.7 ms of
  prediction for a 640 × 480 JPEG. A camera original will cost more.
- ⚠ **Prediction is ~8.7 ms here, not §2.1's 5.8 ms.** This loop interleaves decode with
  prediction and goes through the Kotlin session; §2.1 predicted back to back on one synthetic
  input. Which of the two accounts for the difference is not measured.
- ⚠⚠ **The ~88 MB below is not trustworthy as a pass footprint.** The harness that measured it read
  each photo with an autoreleased `NSData` on a thread with no run loop, so every photo's bytes stayed
  resident until the row ended (the stand-in corpus is roughly 50 MB of JPEGs [I]). That is fixed
  (`readFile` now drains a pool per photo; `ReadFileMemoryTest` grows 196 MiB without it), and the
  figure was **not re-measured**. The Kotlin-heap explanation that follows may account for part of it.
- ⚠ **The pass's footprint is ~88 MB, not the ~20 MB of an open model.** Each photo allocates its
  bytes, a 1 MB `IntArray` and a 0.8 MB `FloatArray` on the Kotlin heap, and the collector returns
  them on its own schedule. D12's threshold is set from the pass figure. Reusing the buffers is
  open to PR-2's backend.
- ⚠ **`.all` is not free at open**: 2.6 s on every launch and 103 MB, against 78 ms and 21 MB for
  `.cpuAndNeuralEngine`, for the same vectors. It supports §3.2's constant.
- `coreml-cpu` differs from the Neural Engine at min cosine 0.9956. Under §4.5 that is inside
  §2.1's 0.9928 ↔ 0.9923, so the compute-units constant stays a store-clearing change.
- ⚠ An earlier set of rows on the same day read `thermalState` 2 before any work and was 2 × slower
  in its first pass. The harness now says so in the report when a row starts at `serious`.

### 9.5 Still owed

- 📱 **IN-3m-iOS itself** (§4.1, §4.4) — ⭐ **§4.4 has run** (partial 2026-10-07, completed
  2026-10-22/23, §9.7; figures in `inference-service.md` §15.13–§15.14 and `models.md` §3.5.4).
  Still owed beyond §4.4: a pass through `CoreMlInferenceBackend` with both towers open (D10, D12).
  `inference-harness-ios/run-in3m.sh` is the driver.
- The per-image vectors across an iOS update (§3.2's probe threshold): the harness keeps them,
  named by OS build, and compares every file it finds.
- Xcode's Core ML performance report, in the GUI (§2.1).
- ~~The retrieval gate on these archives, and its scripts into the repo~~: done, §9.6. What it
  leaves owed is listed there.
- ⚠ `AGENTS.md` is at its byte budget, so this track has no row there yet. Its traps: the marker
  is written last; `IOS_PREPROCESSING_REVISION` is bumped by hand; the fixtures are regenerated by
  the script, never edited.

### 9.6 The retrieval gate, in the repo — and this export's result (2026-10-06)

| Piece | Where |
|---|---|
| The gate | `scripts/inference/retrieval-gate.py` |
| Its queries, corpus manifest and pinned toolchain | `scripts/inference/retrieval-gate-queries.json`, `retrieval-gate-corpus.txt`, `requirements-retrieval-gate.txt` |
| Its self-test (no model, no network, no package) | `scripts/inference/test_retrieval_gate.py` |

§3.4 has what it checks and why. No Kotlin changed.

**The export it ran on** — the PR-1 export of 2026-10-05, verified against its manifest:

| Artifact | sha256 | bytes |
|---|---|---|
| `vision_fp16.mlpackage.tar` | `a30d4ddeaa9d9cc6fc9a894c2c01617d0ed0ae6abbdac832337037a5e410d8de` | 184 842 240 |
| `text_fp16.mlpackage.tar` | `628f7f32ac0c43e7d7e25b396b5c12b7da8ad3208ca696d1a4a7d0bcfb41907d` | 555 540 480 |
| `tokenizer.bin` | `0077b8d2b79f30ff96015e1b76ff4c7d3d036667083e8a4da3bf1ee3df3d34aa` | 3 066 150 |
| `export-manifest.json` | `3fd1918106d46c76cbebbd5ef0d566c6b941458d00015370be6677e33026b6f0` | |

- Source revision `8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6`; the checkpoint directory matched the
  manifest's five source hashes [M]. That revision and those hashes (`model.safetensors`
  `00df88d8…`) are now the script's `YARDSTICK_SOURCE` pin, and the manifest says
  `toolchain.pinned = true`: the result records `checkpointBinding: pinned`, no override.
  ⚠ The pin is what the ONNX yardstick is *taken* to come from (§3.4); it is not proof that it does.
- ⚠⚠ **These bytes are kept at `~/tenshu-models/coreml-export-3fd1918106d4/` on the build Mac**,
  outside every repo, with `retrieval-gate-result.json` and the phone's vector file beside them.
  **PR-2b publishes these files, not a re-export**: a re-export has other digests and no gate result.
  ⚠ That directory is one copy on one disk.
- The yardstick: the ONNX pair the live catalog serves (`catalog.json`, build 2026-09-25; vision
  `84c7bb92…`, text `277534a4…`), which the local files match [M].
- Corpus `60b1aa83e6edeeb5cf6bf7386df07cf9c66456e0c76acb7c0db47cb27dd5a66a` (300 files, fetched from
  COCO by the script and checked; resized pixels `95ca70b7…`, bit-identical to PR-0b's PNGs [M]).
  Queries file sha256 `cbac832d234bca534263b4ab9342872f9d93aeac8d5cfa26a742eee712380251`.
- Toolchain, all pinned: Python 3.13.7, coremltools 9.0, torch 2.7.0, numpy 2.2.6, transformers
  4.53.3, onnxruntime 1.20.0, Pillow 12.3.0, sentencepiece 0.2.1. macOS 26.6.2, arm64.

**The result** [M]. ⚠ **A host run**: every pair ran on the build Mac, Core ML through coremltools
with `cpuAndNeuralEngine`. Against fp32 PyTorch, 36 queries over 300 images, pad id 1, mean / worst
query:

| Pair | top-1 agreement | top-5 overlap | top-10 overlap | Spearman | image cosine vs fp32, mean / min | text cosine vs fp32, mean / min |
|---|---|---|---|---|---|---|
| uint8 vision + int8 text ONNX (onnxruntime 1.20.0) | 25 / 36 | 0.844 / 0.40 | 0.869 / 0.50 | 0.9415 / 0.8240 | 0.9757 / 0.7998 | 0.9782 / 0.8724 |
| ⭐ fp16 Core ML, this export | **35 / 36** | 0.994 / 0.80 | 0.992 / 0.90 | 0.9999 / 0.9998 | 1.0000 / 0.9996 | 1.0000 / 1.0000 |

**Verdict: PASS**, on all seven figures. Core ML's one top-1 disagreement is *a kitchen with a
refrigerator*; the ONNX pair has eleven, in all four languages (en 12/16, de 8/12, ja 2/4, ru 3/4).

**Against §2.2's table** — what moved, and why [M: recomputed from PR-0b's saved vectors]:

| | top-1 | top-5 | top-10 | Spearman |
|---|---|---|---|---|
| Core ML, PR-0b's export, host (`all`), pad 0 | 35 / 36 | 0.994 / 0.80 | 0.994 / 0.90 | 0.9998 / 0.9998 |
| Core ML, PR-0b's export, host (`all`), pad 1 | 35 / 36 | 0.994 / 0.80 | 0.992 / 0.90 | 0.9999 / 0.9998 |
| Core ML, this export, host (`cpuAndNeuralEngine`), pad 1 | 35 / 36 | 0.994 / 0.80 | 0.992 / 0.90 | 0.9999 / 0.9998 |
| ONNX, onnxruntime 1.29, pad 0 (§2.2's row) | 23 / 36 | 0.750 / 0.20 | 0.772 / 0.50 | 0.9311 / 0.8874 |
| ONNX, onnxruntime 1.29, pad 1 | 29 / 36 | 0.878 / 0.60 | 0.892 / 0.70 | 0.9624 / 0.9426 |
| ONNX, onnxruntime 1.20.0, pad 1 (this gate) | 25 / 36 | 0.844 / 0.40 | 0.869 / 0.50 | 0.9415 / 0.8240 |

- **Core ML did not move with the re-export.** The unpatched toolchain's archives give the same
  figures, to the digits shown, as PR-0b's at the same padding, and miss the same query.
- **The padding moved Core ML by a digit and the ONNX pair a lot**: 23 → 29 top-1 on one runtime.
  That the int8 text tower is further from fp32 on pad-0 rows, which the model was never trained
  on, is the likely reason [I].
- ⚠⚠ **The ONNX pair's vectors depend on the onnxruntime version.** Same files, same inputs, 1.29
  against 1.20.0: image cosine mean 0.9938, min 0.9750; text cosine mean 0.9813, **min 0.8858**; and
  29 → 25 top-1 against fp32. The fp32 reference moved by nothing (cosine 1.000000 between torch
  2.13 and 2.7.0, transformers 5.16 and 4.53.3). So PR-0b measured its yardstick on a runtime no app ships; the apps pin 1.20.0
  (`gradle/libs.versions.toml`) and the gate now does too. ⚠ Core ML passes against either row.
  ⚠ For Android and desktop this means an onnxruntime bump is a vector-space change for the
  quantised pair, the text tower most of all. Not investigated further here, and whether 1.20.0
  on an Android phone gives the Mac wheel's vectors is not measured.
- §2.2's Core ML row was *on the iPhone* (top-10 0.997); the rows above are the host's.

**Phone spot-check — done, vision only** [M]. iPhone 17e, iOS 26.6.1 (23G83), charging,
`thermalState` 0 throughout; the harness (`run-in3m.sh`, row `coreml-ane`) with these archives over
the gate's 300 resized images, pushed as PNGs (`--dump-pixels`; harness corpus `84e5400e…`).

| | |
|---|---|
| Phone vision vectors against the host's Core ML vectors, 300 images | cosine mean **0.999556**, min **0.985338** |
| Phone vision vectors ranked by the host's Core ML text vectors, against fp32 | 34 / 36 · 0.967 / 0.80 · 0.989 / 0.90 · 0.9992 / 0.9985 |

- That row is still above the ONNX pair on every figure. It is not part of the verdict.
- ⚠ **It is not PR-0b's ≥ 0.99999.** PR-0b's spike fed the model the host's tensor. The harness
  goes through the product decoder: ImageIO, then a draw into an sRGB context at high interpolation
  (`IosImageDecoder`), even for an image already at 256². So this cosine covers the decoder's
  pixels as well as the model, and which of the two accounts for 0.985 is **[I], not measured**:
  the phone's pixels were not read back. It is a larger gap than `coreml-cpu` against the Neural
  Engine on one decoder (§9.4, min 0.9956), and far smaller than what the uint8 ONNX tower loses
  against fp32 (min 0.80).
- The harness was uninstalled afterwards.

**The two refusals added after review** [M: run on doctored copies of this export's manifest; the
kept directory was not modified]: another `source.revision` → REFUSED; another `model.safetensors`
hash → REFUSED; the wrong revision with `--allow-unpublished-onnx` and the published ONNX files →
still REFUSED; `toolchain.pinned = false` → REFUSED, and with `--allow-unpinned-export` the same
figures as above under `OVERRIDDEN PASS`.

**Not verified, still owed:**
- That the published ONNX files were exported from revision `8952a4ea…`. The gate assumes it.
- 📱 The **text tower on the phone** with these archives. The harness's text row embeds one fixed
  caption and keeps no vector; the tokenizer reaches iOS in PR-2. The gate's text figures are the
  host's.
- That the **Kotlin tokenizer** produces these 36 token rows. The gate uses the checkpoint's own
  tokenizer; `SiglipTextTokenizerTest`'s goldens pin the Kotlin one to it on other strings. (PR-0b's
  hand-padded rows from the bare `tokenizers` library are identical to the gate's [M].)
- The decoder's share of the phone cosine (above).
- An **absolute** floor. The rule is relative to the ONNX pair; a later ONNX re-export that got
  worse would loosen it.
- Nothing runs the gate automatically. It needs the weights, so it is a step in the runbook.

- **Two more bindings, added in review** [M]. ① The pinned toolchain includes the interpreter:
  `retrieval-gate.py` and `export-coreml-towers.py` both treat a Python other than 3.13 as outside
  the pins, and a gate run outside its pins reads `OVERRIDDEN`, never a bare verdict. ② A phone
  vector file is admitted only with `--phone-pixels`, the PNG directory that was pushed: the gate
  checks that directory holds this run's pixels and that the file's `corpusId` is that directory's
  (`84e5400e…` for the run above). The directory is kept beside the export, in `phone-pixels/`.
  Re-run on the kept export with both: unchanged figures, PASS; without `--phone-pixels`, and with
  one pixel altered in a copy, REFUSED.
- **Evidence goes with the bytes, added in review.** `export-coreml-towers.py`'s promotion removes
  `retrieval-gate-result.json` together with the old manifest, and the gate removes it before it
  judges anything, the toolchain included. An export directory therefore never holds a verdict
  about other archives. A malformed phone-vector file is REFUSED (status 2), not a traceback.

### 9.7 IN-3m-iOS — a partial camera-corpus run (2026-10-07), completed 2026-10-22/23

⭐ **Completed 2026-10-22/23, and passed** (maintainer, 2026-10-23; `inference-service.md` §15.14, the
runbook's Part A). §4.5's readings on the iPhone 17e:
- **Throttle (row 3):** does not fire. Three runs, the battery run and eleven back-to-back passes
  all at 32.5–33.5 ms a photo on JPEG.
- ⚠⚠ **`.serious` on the charger after ~4 700 photos (~3.5 min), with no slowdown.** The foreground
  pass's stop at `.serious` and its rest would therefore buy no speed on this phone. ⭐ Kept as built,
  with no chunking (2026-10-23, §14.19.8): the stop is for the phone in the hand.
- **On battery:** no trigger. D5's 20 % line is neither confirmed nor refuted by one 20 s pass.
- ⚠⚠ **Open time:** slow (2.7 s vision, 4.7 s text) only as the first open after about two weeks
  unused; a reboot alone costs ~100 ms. The launch warm-up (§3.2) is required, for the long-gap case.
- **Compile:** `compileModel` ~80 ms; the first open after it 3–4 s and ~1.1 GB of free space for
  both towers. §3.4's 2.58 GB budget holds. D14's *where is it kept* is still unanswered.
- ⚠⚠ **HEIC is decode-bound:** 61 ms a photo, of which decode 52 ms and predict 8 ms. The decode
  seam (§1.2), not the model, is where an iPhone library pass is won.
- **`physicalMemory`:** 8 081 145 856 bytes (7.53 GiB), above the 6.5 GiB placeholder (§14.2).

Still owed, outside the harness: the footprint through `CoreMlInferenceBackend` with both towers
open (D10, D12 ①; runbook B6), and a floor-class phone. The rest of this section is the 2026-10-07
partial run, kept as recorded.

⚠⚠ **2026-10-07: IN-3m-iOS is not done, and nothing after it may read the gate as passed.** This is one short
pass per row. Against §4.4:

| §4.4 step | State |
|---|---|
| Harness built, signed on the company team, installed; the phone unlocked | done |
| `vision_fp16.mlpackage.tar` and `text_fp16.mlpackage.tar` side-loaded, so unpack, `compileModel` and first open ran on the phone | done, once per tower |
| The JPEG corpus pushed with `run-in3m.sh push-photos`, manifest written | done, with 554 files by §15.12's recipe. ⚠ Not *the same 562 files* §4.4 asks for; those no longer exist as a set |
| One pass per row: `coreml-ane`, `coreml-all`, `coreml-cpu`, and the text tower | done; `coreml-ane` twice, nine minutes apart with another row between |
| The phone plugged in for every run | **partly**: the three later vision passes. The text and `coreml-cpu` rows report *unplugged* |
| **Three runs**: cold, warm immediately after, +20 min idle | ⭐ done 2026-10-22/23 |
| One run **on battery** | ⭐ done 2026-10-23 |
| One launch **after a reboot** | ⭐ done 2026-10-23 |
| A **HEIC** sample | ⭐ done 2026-10-23, 53 photos |
| The report copied off the phone | done |

**What the partial run does establish**, on this phone and OS build: latency per photo on camera
originals; that `thermalState` stayed nominal over a ~20 s pass on the charger; vector agreement
across compute units. ⚠ Not whether a pass throttles: the window ratios cannot say
(`inference-service.md` §15.13.1).

**The figures, the conditions and the comparison with Android are in
[`inference-service.md`](inference-service.md) §15.13.** This section is what the run means for this
plan. Marks as above; [M] here is the harness's report.

In one line: iPhone 17e, iOS 26.6.1 (23G83), the export of §9.6, 554 camera-roll JPEGs (corpus
`10b25ab76b09c3314bc683527c140ad16822ad96da6deba745d825df17983463`). The shipped row, `coreml-ane`:
**33.33 ms a photo** (decode 20.23 + predict 13.23), 36.65 ms wall clock, 554 embedded and none
failed, pass footprint peak 170.8 MB, `thermalState` 0 throughout [M].

⚠⚠ **The corpus is §15.12's recipe, not §15.12's files.** Every 4th `.jpg` in name order among the
files dated 2024-12-01 to 2026-06-30 in the OnePlus CPH2581's `DCIM/Camera`, pulled again on
2026-10-07: 554 files, where Android's run had 562, because the roll changed. JPEG only.

**§4.5's readings, one by one:**

| §4.5 reading | What the run says |
|---|---|
| last-50 (decode + predict) > 500 ms, or throttle > 2 | Did not fire in any row's one pass. Shipped row: last-50 31.25 ms and 29.93 ms, throttle 0.83 and 0.81 [M]. ⚠ A ratio below 1 is not a throttle answer, and the warm run that would answer it is owed (`inference-service.md` §15.13.1) |
| decode ms ≫ predict ms | Decode is the larger half, 20.2 ms against 13.2 ms, not ≫ [M]. A pass is sized by ImageIO first; P7's `IndexImage` seam (§1.2) is where to buy time |
| `thermalState` ≥ `.serious` inside a realistic pass on the charger | 0 at every sample of every row [M]. ⚠ Three passes of ~20 s on the charger inside ten minutes are not a library pass. §2.2's `serious` came after 245 s back to back. **PR-5's chunk size and rest are still unmeasured** |
| the on-battery run | **Not made.** The text and `coreml-cpu` rows report *unplugged* at 100 %; neither is the shipped vision row, and one pass at full charge says nothing about D5 |
| `compileModel` time and the free-space delta | Recorded once per tower again, and it repeats §9.4 [M]. Vision: unpack 316 ms, compile 61 ms, first open 2 449 ms; free space −184.9 MB at unpack, −370.1 MB at first open, −555.0 MB for the step. Text: 1 119 ms, 82 ms, 3 790 ms; −562.1 MB, −727.9 MB, −1 281.4 MB. That is 3.0 × the vision archive and 2.3 × the text archive, so §4.5's first case applies. **§3.4's budgets are re-derived from these figures**: 2.58 GB additional for a first install and for an update alike, with the first-open space counted as not returned and the old compiled directory's deletion not credited until it is measured. D14 stays open |
| open after a reboot | **Not run** |
| `coreml-ane` vectors against `coreml-cpu` | mean 0.997962, min 0.994439 over 554 photos [M]. As in §9.4, that is inside §2.1's 0.9928 ↔ 0.9923, so the compute-units constant stays a store-clearing change. `coreml-ane` against `coreml-all`: min 0.999998 |
| `phys_footprint` on the corpus far above 19 MB | It is: 170.8–189.8 MB at the pass peak, 25 MB after open [M]. ⚠⚠ **But it is the harness's figure** (below), so D12 ① is not set from it |

**What the rows show** (one or two short passes each; none of it closes a decision):
- **Nothing against `.cpuAndNeuralEngine`, the constant §3.2 already chose.** In these passes it
  gave the same vectors as `.all`, the same speed (13.23 ms against 13.60 ms), an open of 77 ms
  against 2 648 ms, and 25 MB after open against 132 MB [M].
- **For the pipeline sweep's D4 / D5 (`media-processing-pipeline-sweep.md`):** `thermalState` stayed
  nominal over a 554-photo, ~20 s pass on the charger. ⚠ Nothing about throttling, a longer pass, a
  warm phone or battery.

**What it changes in this plan's numbers:**
- ⚠⚠ **Decode: ~20 ms, not §9.4's ~7 ms.** §9.2 item 12 said the stand-in table would not predict
  it, and it did not.
- ⚠ **Prediction: ~13 ms, not §9.4's 8.7 ms or §2.1's 5.8 ms**, with the same model input size. The
  cause is **not measured**. Decode and prediction sharing the CPU on larger files, and the
  harness's per-photo allocations, are both [I]. §4.5's *"predict alone is 5.8 ms"* is the spike's
  figure, not a pass's.
- ⚠⚠ **Footprint: 171–190 MB at the pass peak, and it is the harness's.** `In3mHarness` drives
  `CoreMlSession` and `IosImageDecoder` directly and allocates each photo's bytes, `IntArray` and
  `FloatArray` anew [C: `In3mHarness.kt`, `visionPass`]. `CoreMlInferenceBackend`'s buffer reuse
  (§10) is not on that path. So this is an upper reading for a pass that reuses nothing on ~12 MP
  originals. **The backend's footprint is owed**, and D12 ① must not be set from 190 MB without
  saying so.

**How the run went.** Five rows finished, one process each, between 13:07 and 13:32: text,
`coreml-cpu`, `coreml-ane`, `coreml-all`, `coreml-ane` again. Three launches lost the console
connection (`CoreDeviceError 4016`) and kept no result: `coreml-ane` vision at 12:17, after the
phone was unplugged, then `coreml-all` at 13:17 and `coreml-ane` at 13:22. The driver reports such a
row as *did not finish (no `IN3M_DONE`)*, and none of the three left a block in the report.

**Still owed after this run** — §4.4's four steps first, then what §4.4 never covered:
- 📱 §4.4's **three runs**: cold, warm immediately after, +20 min idle.
- 📱 §4.4's run **on battery** (D5).
- 📱 §4.4's launch **after a reboot**, for the open time.
- 📱 §4.4's **HEIC** sample. An iPhone's own library is HEIC; this corpus is JPEG only.
- 📱 **Sustained load**: a pass long enough to heat the phone. Nothing here ran longer than ~25 s.
- 📱 A pass **through `CoreMlInferenceBackend`, with both towers open in one process**, for the
  footprint (D10, D12 ②). The harness cannot give it as built: it runs one tower per process.
- **D14's disk question**: where the space taken at first open is kept, and whether it is returned.
- A **floor-class iPhone** (D12). The iPhone 17e is not the floor.
- The per-image vectors across an iOS update (§9.5): this run's vector files are kept on the phone
  under corpus `10b25ab7…`, named by OS build.

---

## 10. As built — PR-2 (2026-10-06)

Marks as above. Nothing here is bound in a graph: no DI line, no catalog entry, no schema change.

### 10.1 What shipped

| Piece | Where |
|---|---|
| `CoreMlInferenceBackend` (vision + text), `COREML_RUNTIMES` | `inference-service-proxy/src/iosMain/…/coreml/CoreMlInferenceBackend.kt` |
| `TowerGuards`: the shape, pooled-output, width and finite / non-zero guards, called by both backends | `inference-service-proxy/src/commonMain/…/onnx/TowerGuards.kt` |
| `SiglipTextTokenizer`, `UnigramVocabulary` (now read from bytes), `expect fun nfkc` | `inference-service-proxy/src/commonMain/…/onnx/` (the package is unchanged) |
| `nfkc` actuals | `onnxMain/…/NfkcJvm.kt` (JVM + Android), `iosMain/…/onnx/Nfkc.ios.kt`, `jsMain/…/onnx/Nfkc.js.kt` |
| Buffer reuse: `ImagePreprocessing.preprocessPixelsInto`, `ImageDecoder.decodeInto` | `commonMain/…/onnx/`, overridden by `IosImageDecoder` |
| The tokenizer goldens, the NFKC parity table, the guard and vocabulary tests, the arithmetic golden | `commonTest/…/onnx/` (JVM and iOS simulator) |
| The backend's tests (fake port, fixture models, the real towers), the iOS preprocessing golden | `iosTest/…/coreml/` |

**The backend, against §3.2's contract:**
- One session a tower, keyed by the compiled path, opened and used under that tower's mutex. A
  call that finds another path, no path, or `ready() == false` closes the held session first.
- The latch is the rejected **path**. Only `CoreMlRejectedModelException` sets it; a load failure
  is `Failed` and is tried again. A new path is tried, and clears it.
- `supports` is `SEMANTIC_IMAGE_SEARCH` only, and only while `ready()`, `locate()` answers and the
  path is not the latched one. `CoreMlInferenceBackend.compiledTower(cache, role, digest)` is the
  locator that answers only for a ready marker.
- `Unreadable` only for a decoder refusal, and only after a session was obtained.
- `TowerGuards.unusableReason` on every vector, image and text, in the backend as well as in
  `CoreMlSession`; then one normalisation, then `VisionModel.PinnedSigLip.provenance`.
- Images on `MediaWorkBudget.lane`; text on `Dispatchers.Default`.
- The vocabulary is read before the text tower is opened, and parsed once per source.

**Two seams left for PR-5, both constructor parameters with defaults:**
- `ready: () -> Boolean = { true }`. `AiReadiness`'s snapshot goes here. Not built.
- `closeSessions()`: step 3 of leaving *ready*, and what a memory warning calls. It also drops the
  vocabulary and the two buffers.

### 10.2 Deviations from §3–§5, and why

1. **The iOS `nfkc` is not `precomposedStringWithCompatibilityMapping`** (§3.2 named it). That call
   is not NFKC (§10.3). The actual is ICU's normaliser through `stringByApplyingTransform("Any-NFKC")`.
2. **Three JVM-library calls in the tokenizer became spelled-out rules**, because each platform's
   library reads them differently and the tokenizer's measured parity (`models.md` §3.9) is the
   JVM's reading:
   - the punctuation `Replace` is a character set, not the reference's regex source: a browser's
     Unicode mode refuses `\#`;
   - `\s+` is the six ASCII characters it is on the JVM: in a browser it is every Unicode space;
   - a lone surrogate encodes as `?`, as `String.toByteArray(UTF_8)` does. Kotlin/Native encodes it
     as U+FFFD's three bytes, so an astral character outside the vocabulary would have been
     different ids on Android and iOS. ⚠ **Superseded the same day (§10.8):** the fallback no
     longer splits a pair, and an unpaired surrogate is U+FFFD before any library reads it.
   `SiglipNormaliserJvmEquivalenceTest` proves each rule against the old call over every UTF-16
   code unit. `lowercase()` needed nothing: the JVM and Kotlin/Native agree on every code point (§10.3).
3. **The whole vision call holds the mutex, decode included.** The ONNX twin decodes outside its
   lock. Here the buffers are reused and the port is not thread-safe, so one photo is in the
   backend at a time. `MediaIndexer` embeds one photo at a time already.
4. **`ImageDecoder` gained a defaulted `decodeInto`**, in `commonMain`. Android's and AWT's decoders
   are untouched and take the default.
5. **The JVM and Android `nfkc` actual is in `src/onnxMain`**: that directory is the one compiled
   into both. There is no shared JVM + Android source set in this module.
6. **`UnigramVocabulary.read` refuses an implausible piece count** before it allocates. New: the old
   reader would have sized a map from a corrupt header.
7. **Test names in `SiglipTextTokenizerTest` are identifiers now.** The cases and the golden rows
   are unchanged; a `commonTest` name cannot carry punctuation on every target.
8. **A tokenizer that cannot be built is no text, not an exception**, on iOS: a vocabulary with no
   `</s>` makes `embedText` answer `null`. The ONNX twin let that exception out. ✅ Closed in the
   follow-up (§10.7): it answers `null` too.
9. **Not done in PR-2: the ONNX backend did not apply the finite / non-zero guard.** It was asked to
   stay identical. ✅ Closed in the follow-up (§10.7).
10. **The preprocessing golden is two tests.** The arithmetic's digest is in `commonTest`, so the JVM
    gate sees a change to `ImagePreprocessing`. The decoder's is in `iosTest`, keyed by revision,
    over two BMPs built in the test: one of the model's size (nothing resampled; decided by this
    repo alone) and one that is scaled (also decided by the OS's resampler). The second will move
    with a simulator runtime; its failure message says how to tell the two causes apart.

### 10.3 ⚠⚠ Found: Foundation's "NFKC" is not NFKC

Every code point (1 112 064) and 11 200 sequences were normalised by `java.text.Normalizer` on the
build's JDK 17.0.18 and on the iOS 27.0 (24A434) simulator, and compared [M]. A throwaway harness;
the table that stays is `NfkcParityTest`.

| iOS route | Rows that differ from the JVM | What they are |
|---|---|---|
| `precomposedStringWithCompatibilityMapping` | **11 361** | 11 200 sequences: every Hangul syllable followed by a trailing consonant stays uncomposed (10 773); half-width kana with a voiced mark stays `カ` + U+3099 where NFKC is `ガ`; compatibility jamo are mapped and not composed. Plus the 161 below |
| compatibility-decompose, then canonical-compose | 161 | the 158 below, and three Kirat Rai vowels (Unicode 16) it decomposes and does not recompose |
| ⭐ `stringByApplyingTransform("Any-NFKC")`, shipped | **158** | single code points only, all modifier letters added in Unicode 14–16 (U+A7F1–A7F4, U+10781–107BA, U+1E030–1E06D). iOS maps them; JDK 17 carries Unicode 13 and leaves them alone |

- `lowercase()` and `uppercase().lowercase()`: **no** difference on any code point or sequence [M].
- ⚠ The 158 are not fixed by either side being right: they are two Unicode versions. Android's
  `java.text.Normalizer` is the phone's own ICU, so Android moves with its OS as iOS does; the
  desktop moves with its bundled JDK. A query holding one of those letters is a different row of
  ids on a desktop than on a phone. None is a letter a photo query is likely to hold.
- The 41 golden rows reproduce on the simulator with the real vocabulary [M].
- ⚠ Not compared: Android itself, and an iPhone (the simulator's ICU is the runtime's, [I] that the
  phone's is the same).

**Also found, and left as it was by PR-2; ✅ fixed since (§10.8):** on every platform the byte
fallback walked one UTF-16 unit at a time, so an astral character **outside the vocabulary** became
two `?` bytes, where the reference falls back to its four UTF-8 bytes. It predated PR-2, which
pinned it so the platforms agreed (deviation 2). The goldens' emoji are in the vocabulary and did
not reach it.

### 10.4 Measured on the simulator — ⚠ not a phone

iOS 27.0 (24A434) simulator on the build Mac, a debug Kotlin/Native test binary.

- **The vocabulary map** (§3.2 asked for a first reading): the 250 100-entry map adds **6.4 MiB** to
  the Kotlin heap after a collection, from a 3.07 MB file; parsed in 126 ms; a query tokenises in
  ~150 µs, NFKC included [M]. Not `phys_footprint`.
- **The real towers through the backend** (PR-1's export, both prepared by the cache): an all-red
  JPEG scores 0.140 against *"a plain red image"*, 0.058 against *"a plain blue image"* and −0.033
  against *"a photo of a dog"*; dog ~ puppy beats dog ~ source code by more than 0.05 [M]. One
  image, so a smoke of *one space*, not a retrieval measurement.
- **A photo of the model's own size reaches the model pixel for pixel**: no resampling, and no
  colour conversion of an untagged image [M].

### 10.5 Guards, each removed once and seen red

`test-the-guard-not-the-shape`: one mutation at a time, the suite run, the red cases read, the
code restored [M: 13 on the JVM, 24 on the simulator].

| Guard | Removed by | Red |
|---|---|---|
| The latch is set on a rejected model | not recording the path | `aRejectedModelIsLatchedAndNeverReopened`, the text twin, the fixture twin |
| `supports` reads the latch | answering `true` | `aRejectedModelIsLatchedAndNeverReopened` |
| ⭐ The latch is keyed to the path | the ONNX twin's never-reset latch | `theLatchIsAVerdictOnOnePathAndANewArtifactIsTried` |
| A load failure is not latched | latching every failed open | `aModelThatWillNotLoadIsAFailureAndIsTriedAgain` |
| `Unreadable` for a decoder refusal | answering `Failed` | `aDecoderRefusalIsUnreadable…` |
| `Unreadable` only from a backend that could run | decoding before asking for the session | `withNoModelItClaimsNothing…`, `aModelThatWillNotLoad…`, `aPathWithNoModelIsAFailure…` |
| One session per path | opening per call | `oneSessionServesEveryCallOnAPath` |
| A path change opens the new model | keeping the held session whatever the path | `aPathChangeClosesTheOldSessionAndOpensTheNew` |
| The old session is closed | dropping it unclosed | `aPathChange…`, `aModelThatIsRemovedClosesItsSession`, `whileNotReady…` |
| The output guard in the backend | returning the vector unchecked | the NaN, zero and width cases |
| One normalisation | skipping it | `everyVectorIsNormalisedOnce…` |
| Text off the lane | `withContext(lane)` | `aQueryNeverRunsOnTheMediaLane…` |
| The vocabulary before the text tower | opening the session first | `withoutAVocabulary…TheTextTowerIsNeverOpened` |
| The vocabulary parsed once per source | no cache; a cache that ignores the source | `theVocabularyIsParsedOncePerSource…` |
| `ready` in `supports`, and in the session lookup | ignoring it, each separately | `whileNotReadyItClaimsNothing…` |
| `closeSessions` closes | not closing | `closingTheSessionsClosesBoth…` |
| Only `SEMANTIC_IMAGE_SEARCH` is claimed | claiming every capability | `itClaimsOnlySemanticImageSearch` |
| ⭐ `supports` false until the marker | `compiledPath` ignoring the marker | `itClaimsNothingUntilTheReadyMarkerExists`, three of PR-1's |
| The buffers are reused | a fresh array per photo | `theBuffersAreReused…` |
| The decoder's channel order | swapping red and blue | `IosPreprocessingGoldenTest`, `IosImageDecoderTest` |
| A lone surrogate is `?` on every platform (⚠ replaced, §10.8) | plain `encodeToByteArray` | `anAstralCharacter…IsTheSameIdsOnEveryPlatform`, on iOS |
| `TowerGuards`: side, rank, dynamic axes (both ways), pooled output, finite, non-zero, width | each line removed | `TowerGuardsTest`, on the JVM |
| The punctuation set | one character dropped | `thePunctuationSetIsTheReferenceRegex` |
| The preprocessing digest | channels swapped; `/ 256f` | `theSiglipTensorOfAFixedImageHasNotMoved` |
| The pad id | padding with 0 | five tokenizer cases, the 41 goldens among them |
| The iOS `nfkc` is ICU's | it *was* `precomposedStringWithCompatibilityMapping` first | four rows of `NfkcParityTest` (§10.3) |

**Not proved:**
- `UnigramVocabulary.read`'s count check. Without it the answer is still `null`, because the
  refused allocation is caught. The test cannot see an allocation.
- The whitespace class against `\s+`: on the JVM the two are the same thing, which is what
  `SiglipNormaliserJvmEquivalenceTest` asserts. The difference is in a browser, where no test runs.
- Holding the mutex across a prediction: no test runs two calls at once.

### 10.6 Still owed, and still inferred

- **Smoke** (`scripts/android-smoke.sh`): the `onnxMain` compile set changed. Not run in this
  session; it is the orchestrator's before merge.
- The vocabulary's and a pass's `phys_footprint` on the phone, with the reused buffers: §9.4's
  ~88 MB was not re-measured.
- ⚠ `supports` through `compiledTower` reads the marker file on every call, and `supports` is on
  the per-image path. PR-5's readiness snapshot is what makes that cheap (§3.2).
- [I] That closing a `CoreMlSession` releases its memory (§8), which `closeSessions()` relies on.
- [I] That a phone's ICU answers as the simulator's did (§10.3).
- **The 158 code points (§10.3): accepted by the maintainer, 2026-10-06.** Desktop and phones may
  tokenise those Unicode 14–16 modifier letters differently; nothing is pinned. The parity test keeps
  printing what each platform does.
- **Two follow-ups decided the same day, each its own PR:** an out-of-vocabulary astral character
  becomes its four UTF-8 bytes, as the reference tokenizer has it, not two `?` (✅ §10.8); and
  `OnnxInferenceBackend` applies `TowerGuards.unusableReason` to every vector, as the Core ML backend
  does (✅ §10.7).

### 10.7 As built — the ONNX twin's output guard (2026-10-06)

Closes deviations 8 and 9. No schema change, no DI line, nothing in `commonMain` but a test.

- **The guard.** `OnnxInferenceBackend` passes every vector it returns, image and text, through
  `TowerGuards.unusableReason`, before the one normalisation. A vector that is non-finite, all
  zeros or of another width is not returned.
- ⚠⚠ **A refused vector is `ImageEmbeddingOutcome.Failed`, never `Unreadable`, and sets no latch.**
  - Not `Unreadable`: that is the one outcome `MediaIndexer` remembers against the file
    (`markVisited`), and nothing shows a NaN is a property of the photograph. The one observed case
    (§2.1) was a runtime path that answered NaN for *every* input; remembered, that would have
    marked the whole library as visited with no vector, for good.
  - No latch: `rejected` is a verdict on the artifact's shape. `supports` goes on answering `true`,
    the session is kept, and the next input is embedded.
  - For text, `embedText` answers `null`, which `photosLike` already turns into
    `SemanticSearchUnavailable.NO_TEXT_EMBEDDING`. Nothing is thrown at the caller.
- **What `Failed` costs** (changed since): `MediaIndexer` no longer stops asking for vectors
  after one `Failed`. It stands the vector half down only after `EMBED_FAILURE_STREAK_LIMIT` (3)
  consecutive failures, a success resets the count, and the failed photo is still not stored and
  not visited, so the next pass retries it (`inference-service.md` §32.3). ⚠ Accepted limit: three
  photos that fail every time, with no un-embedded good photo walked between them, still stand the
  half down on every pass. [I] Not observed: no input is known to make the CPU towers answer NaN.
- **A vocabulary with no `</s>`** is no text on ONNX too: the tokenizer's refusal is caught where
  the vocabulary is parsed, before the text tower is opened. Not latched: a repaired file at the
  same path is read by the next query. `photosLike` is `embedText`'s only caller, and nothing
  caught the old exception: it left the op as an untyped failure.
- **The seam.** `OnnxTower` (internal, in `OnnxInferenceBackend.kt`) is one opened tower as the
  values the backend reads and the two runs it makes. `OrtTower` is the only production
  implementation. The public constructor is unchanged; a test passes `open`.
  ⚠ The width check moved out of the ORT wrapper and into `unusableReason`, so it is one check.

**Guards, each removed once and seen red** [M: on the JVM, `OnnxInferenceBackendTest`,
`TowerGuardsTest`, `MediaIndexerTest`; each file's hash compared after the run]:

| Guard | Removed by | Red |
|---|---|---|
| The image vector is checked | returning the run's vector unchecked | the NaN, infinity, zero and width cases |
| The text vector is checked | the same, in `embedText` | the same four |
| A refused image vector does not latch | setting `rejected` on a refusal | the same four, and the throwing run |
| A refused text vector does not latch | setting `textRejected` on a refusal | the same five |
| The tokenizer's refusal is caught | building it bare | the no-`</s>` case |
| That refusal does not latch | setting `textRejected` on it | the no-`</s>` case (the repaired file) |
| `unusableReason`: finite, non-zero, width | each line removed | the matching backend case, and `TowerGuardsTest` |
| A `Failed` photo is not visited | `markVisited` on `Failed` in `MediaIndexer` | `aModelFailureIsNotRememberedAgainstThePhoto` |

**Not in the gate:** `OrtTower`, the wrapper over the real session. Only the real-artifact cases
reach it, and they skip without the weights. Run here with the uint8 vision tower, the int8 text
tower and `tokenizer.bin`: 16 of 16, none skipped [M].

**The smoke gate ran before merge** (emulator): passed. `onnxMain` compiles into Android.

### 10.8 As built — the astral byte fallback (2026-10-06)

§10.3's gap against the reference is closed. The measurement, the counts and the reference's
observed behaviour are `models.md` §3.9.5; this is what changed against §10.2 and §10.5.

- **The fallback's span is one code point.** In `SiglipTextTokenizer.viterbi` it is two UTF-16
  units at a surrogate pair, so a character above U+FFFF that is not a piece is its four UTF-8
  bytes. 436 of the 1 249 emoji above U+FFFF are not pieces of this vocabulary [M].
- ⚠⚠ **Deviation 2's third rule is replaced.** An unpaired surrogate is no longer the byte `?`. It
  is replaced by U+FFFD in `wellFormed`, the first step of `normalise`, and U+FFFD is a space. The
  reference refuses such a string, so this is a decision: it is the one reading no platform's
  encoder takes part in, and it is what Swift's string bridge has already done to a query on iOS.
  `utf8Of` and its `?` are gone; what reaches the fallback is always a whole code point, whose
  UTF-8 is the same on every platform.
- **Tests, in `commonTest`:** six cases over a hand-built vocabulary, and 16 new golden rows (57 in
  all; the first 41 unchanged). `SiglipNormaliserJvmEquivalenceTest`'s two encoding cases became
  one that holds `wellFormed` against the JDK's UTF-16 codec over every code unit.
- **No stored data and no schema moved.** A text vector is computed per query.

Each removed once and seen red, then restored [M: all four on the JVM, the first and third on the
simulator too]:

| Guard | Removed by | Red |
|---|---|---|
| The fix as a whole | the old fallback: one unit per span, `?` for a half, no `wellFormed` | four astral cases, `aLoneSurrogateIsASpaceOnEveryPlatform`, the goldens |
| The fallback's span is a code point | one unit per span | the same six |
| An unpaired surrogate is U+FFFD | `normalise` not calling `wellFormed` | `aLoneSurrogateIsASpaceOnEveryPlatform` |
| `wellFormed` leaves a pair alone | treating every surrogate as unpaired | nine cases, `wellFormedIsTheJdksOwnUtf16Replacement` among them |

- **Not run:** the 106 239-row comparison with the reference on the simulator (JVM only), a phone,
  Android, and a browser.
- The retrieval gate (PIO-7's) takes its rows from the reference tokenizer, never from
  this one, and none of its 36 queries holds a character above U+FFFF: `CANONICAL_TOKEN_ROWS_SHA256`
  is unmoved [M].

## 11. As built — PR-4 (2026-10-06)

A move, with no behaviour change. `ModelsViewModel`, `ModelsRepository` (with
`ClientModelsRepository` and the state types beside them) now live in `:control-shared-logic`,
in the package they had (`app.tenshu.control.ui.screens.models`), so `ModelsScreen` in
`:control-shared` imports nothing new.

**The two holders are the proxy's, and `:control-shared-logic` cannot name them** — the proxy
depends on it, never the reverse. Each is read through an interface in
`app.tenshu.control.inference`:

| Interface (`:control-shared-logic`) | Implemented by (`:control-shared-proxy`) | Carries |
|---|---|---|
| `IndexCoverageSource` | `LastIndexCoverage` | the last pass's `IndexCoverage`, which moved with the interface |
| `ModelStoreAccessSource` | `ModelStoreAccess` | the store's refusal kind and its detail; the `Refusal` enum moved onto the interface |

⚠⚠ **Both are bound under the interface in `ProxyModule` (`bind`), and that is load-bearing.**
The repository takes both as nullable and its provider reads them with `getOrNull`, so a holder
bound only under its concrete type is not an error: the screen loses its coverage line and its
read-only notice, silently. `ModelsHolderBindingTest` resolves both from the real `proxyModule`;
`ModelsRepositoryWiringTest` pins that the provider asks for those two types.

**Deviations from §5's row**

- ⚠ `IndexNowButtonTest` did **not** move: it is a Compose UI test and stays with the screen.
- `ModelsViewModelTest` and `ModelsSnapshotTest` moved, minus the assertions about what the screen
  *says* (`storeReadOnlyNotice`, `modelActionsEnabled`, the indexing and consent lines). Those
  helpers are in `ModelsScreen.kt`, so their tests stay in `:control-shared` as
  `ModelsScreenTextTest`. The two halves meet on the state's value.
- `ModelsViewModel`'s two consent fields moved above `init`. Nothing read them from there, but a
  field below an `init` that launches is the K/N crash shape.

**What PR-5 still owes before a SwiftUI Models screen**

- ⚠ `tenshu-sdk-ios` does not `export` `:inference-service`, so `InferenceCapability` (in
  `ModelsUiState.consents`, and a parameter of both consent setters) reaches Swift as
  `Inference_serviceInferenceCapability`. The moved types themselves arrive unprefixed.
- No iOS graph constructs `ClientModelsRepository` or `ModelsViewModel`. Photos' surface must
  build them, passing the two holders its own runtime holds.
- `uiState` is a `StateFlow`: Swift needs the same collector bridge the other surfaces use.
- ⚠ The sentences are still Kotlin in `ModelsScreen.kt` (`:control-shared`, not exported), and
  several name Control. PR-5's copy is its own (§5's row: *copy names Photos' Settings*).
- `OfferedConsents` and the pure join (`modelsSnapshot`) are `internal`, so Swift sees the offered
  switches only through `ModelsUiState.consents`.

## 12. As built — PR-2b (2026-10-07)

The catalog entry, the signer's rules and the runbook. **Not signed and not published** in this PR: both need
the key in the maintainer's Keychain, and `docs/how-to/models-pipeline.md` §6.1 is the block to run.
⚠ **Since done:** the entry was signed and published on 2026-10-11 (§5's row; §14.12 has what its URLs answer).
No client code changed, so no Android smoke is owed.

### 12.1 What shipped

| Piece | Where |
|---|---|
| The `coreml` entry: the ONNX entry's `id` and `version`, third in the list, with `source` and a pin on each artifact | `deploy/models/models.json` |
| `source` carried into the catalog; a duplicate `(id, runtime)` refused; entries sharing an id must share `version` and `labelSetVersion`; a pinned artifact whose bytes differ refused; `checkManifest`, the manifest-only half, callable without the files | `model-signer/.../ModelCatalogBuilder.kt` |
| The builder's refusals and its exact output for an entry without a source | `model-signer/.../ModelCatalogBuilderTest.kt` |
| The round trip with two entries under one id | `SignedCatalogRoundTripTest` (`:inference-service-proxy` jvmTest) |
| The tracked manifest against the shipped runtime sets, and the published catalog under the pinned key | `CoreMlCatalogEntryTest`, with `jvmTest/resources/catalog/published-2026-09-25/` |
| The Core ML path and the maintainer's block | `docs/how-to/models-pipeline.md` §2, §6; `docs/models.md` §1, §1.1 |

**The entry describes the kept export, verified before its digests were copied** [M]:

| Artifact | Path under the catalog base | sha256 | bytes |
|---|---|---|---|
| `vision` | `siglip-base-patch16-256-multilingual/1/coreml/vision_fp16.mlpackage.tar` | `a30d4ddeaa9d9cc6fc9a894c2c01617d0ed0ae6abbdac832337037a5e410d8de` | 184 842 240 |
| `text` | `siglip-base-patch16-256-multilingual/1/coreml/text_fp16.mlpackage.tar` | `628f7f32ac0c43e7d7e25b396b5c12b7da8ad3208ca696d1a4a7d0bcfb41907d` | 555 540 480 |
| `vocabulary` | `siglip-base-patch16-256-multilingual/1/tokenizer.bin` (the ONNX entry's) | `0077b8d2b79f30ff96015e1b76ff4c7d3d036667083e8a4da3bf1ee3df3d34aa` | 3 066 150 |

- The three files at `~/tenshu-models/coreml-export-3fd1918106d4/` hash to their
  `export-manifest.json` (itself `3fd19181…26b6f0`), and `retrieval-gate-result.json` there says
  `PASS`, `"overridden": false`, `overrides: []`, for those same three digests [M].
- **The vocabulary is the published one**, three ways [M]: the export's `tokenizer.bin` is
  byte-identical to the ONNX export's local copy (`cmp`), its digest and size are the live
  catalog's, and `CoreMlCatalogEntryTest` compares the entry's pin with the published catalog.
- `source` is the export manifest's: repo `google/siglip-base-patch16-256-multilingual`, revision
  `8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6`, the five source-file hashes, the toolchain
  (coremltools 9.0, torch 2.7.0, transformers 4.53.3, numpy 2.2.6, Python 3.13.7, `pinned: true`)
  and the manifest's own sha256. A script compared the entry with the manifest and with the gate
  result field by field [M]. ⚠ The copy is by hand; nothing in the tree re-checks it, since the
  export is on one Mac.
- The ONNX entry is untouched and has no `source` (§3.4).

### 12.2 Deviations from §3.4 and §5, and why

1. **The entry states `sha256` and `byteSize`, which the runbook said an operator never writes.**
   They are a pin, not the value: the signer still computes both and refuses a file that differs.
   Without it, §9.6's rule (*publish these files, not a re-export*) is a sentence. A re-export
   staged under the same two names would sign cleanly. The ONNX and face entries stay unpinned.
2. **A third signer rule: entries sharing an id share `version` and `labelSetVersion`.** D13's
   premise is one checkpoint under one stamp. Two versions under one id would make
   `ModelProvenance(id, version)` mean two models on two platforms.
3. **`source` carries more than the repo and revision**: the source-file hashes, the toolchain and
   the export manifest's digest. The directory name `coreml-export-3fd1918106d4` is that digest's
   prefix, so the signed catalog names the run.
4. **`CatalogModel.source` is the builder's only defaulted key**, and it is why an entry without a
   source is written byte for byte as before (the encoder omits defaults). The golden test pins it.
5. **`scripts/models-publish.sh` is unchanged**, as planned. ⚠ What §3.4 did not say: it uploads
   every path the catalog names, so a publish re-uploads the four files already on the CDN. The
   runbook's step 5 (a diff against the live catalog) is what shows the staged copies are the
   published bytes before that happens.

### 12.3 ⚠⚠ What a shipped build does with the new catalog

**It drops the `coreml` entry and sees what it sees today.** Read from the code as it shipped, not
as it is now [C]:

- **The signature covers the raw bytes of `catalog.json`** (detached Ed25519, `ModelSigner.sign`),
  and the client verifies those bytes before it parses anything (`ModelCatalog.kt:121-123`). So no
  shipped build depends on the catalog's shape for the signature to hold: a third entry and a new
  key are new bytes under a new signature from the same key. The mechanism did not change.
- **Every consumer reads the catalog through one door.** `verifyAndParse` returns
  `usableModels(…)` (`ModelCatalog.kt:126`), nothing else calls `parseModelCatalog`, and there is
  one `ModelCatalogVerifier` (`ModelDeliveryComponents.kt:70`). `AvailableModels`
  (`ModelOperations.kt:87`) and `ModelDownloader.runDownload` (`:137`) both call
  `catalogSource.load()`. Nothing persists an unfiltered catalog.
- **An unknown runtime is dropped**: `it.runtime in supportedRuntimes` (`ModelCatalog.kt:159`).
  Android contributes `SUPPORTED_RUNTIMES + FaceModel.RUNTIME` and desktop `SUPPORTED_RUNTIMES`
  (`PlatformProxyModule.kt` androidMain `:638`, desktopMain `:689`); an unbound platform gets the
  empty set (`ProxyModule.kt:320`). So after the filter there is one entry for the id, the ONNX
  one, and the id-keyed code never meets a duplicate: `ModelDownloader.kt:141`
  (`firstOrNull { it.modelId == modelId }`), `DefaultModelStore`'s index, `ModelInventory`
  (`ModelStore.kt:229,245`), and the Models list, one row per surviving entry
  (`ModelOperations.kt:94`).
- **An unknown key is ignored**: `Json { ignoreUnknownKeys = true }` (`ModelCatalog.kt:201`), so
  `source` and nothing else new is skipped.
- **Old builds.** The runtime filter landed 2026-08-15 (`ffc6f16cc`, IN-3b) and
  `ignoreUnknownKeys` with the parser itself (2026-08-12, IN-2). The first key was pinned
  2026-09-06; before that the pin was blank and every catalog was refused. So **there is
  no build that accepts a catalog and does not filter it by runtime**. At the rotation commit
  (`401788484`, 2026-09-25) the same lines read `:126`, `:159`, `:201`, with the wiring at
  androidMain `:685`, desktopMain `:723` and `ProxyModule.kt:306`; `ModelCatalog.kt` has not
  changed since. A build pinning the lost first key refuses this catalog as it refuses the live
  one: by signature.
- **The published catalog itself is a fixture now.** `CoreMlCatalogEntryTest` verifies the bytes
  served on 2026-10-07 under the pinned key with the real Ed25519: the old shape still parses, and
  the pin in the tree is the key that catalog was signed with.

D13 stands. Nothing here asks for it to be reopened.

### 12.4 Guards, each removed once and seen red

Each run was `:model-signer:test` and `:inference-service-proxy:jvmTest --tests '*catalog*'`,
cleaned, with one edit applied and then reverted. The three edited files were hashed before and
after: all three restored [M].

| Removed or changed | Went red |
|---|---|
| The duplicate `(id, runtime)` refusal | `ModelCatalogBuilderTest.theSameIdTwiceUnderOneRuntimeIsRefused` |
| The shared `(version, labelSetVersion)` refusal | `…entriesSharingAnIdMustShareAVersion` |
| The `sha256` pin check | `…aFileThatIsNotThePinnedBytesIsRefused` |
| The `byteSize` pin check | the same test, at its second assertion |
| The 40-character commit check; the source-file hash check; the blank-repo check (three runs) | `…aSourceThatDoesNotNameASnapshotIsRefused`, each time |
| `source = model.source` in the builder | `…aSourceIsCopiedIntoTheCatalogAsWritten`, and the round trip |
| The builder's encoder set to write defaults | `…anEntryWithoutASourceIsWrittenByteForByteAsBefore` |
| ⚠⚠ **The client's runtime filter** (`ModelCatalog.kt:159`) | the round trip (`twoEntriesUnderOneIdAreToldApartByRuntimeAlone`), `CoreMlCatalogEntryTest`'s shipped-builds and published-catalog tests, and four tests that were there before |
| The client's `ignoreUnknownKeys` set to `false` | the round trip (it meets `source`), and four older tests |
| `models.json`: the `coreml` entry's runtime written as `onnx` | `CoreMlCatalogEntryTest.theManifestPassesTheSignersOwnChecks`, and the rest of that class |
| `models.json`: the vision artifact's pin deleted | `…everyArtifactIsPinnedToTheGatedExport` |
| `models.json`: the `coreml` entry's `labelSetVersion` written as 2 | `…theManifestPassesTheSignersOwnChecks`, `…itIsTheSameCheckpointAsTheOnnxEntryUnderAnotherRuntime` |

**The whole catalog, built for real, with a throwaway key** [M]. The seven files were staged outside
the repo (the three from the kept export, the two ONNX towers from their local export, the two face
files fetched from the CDN; all five published ones checked against the live catalog's digests) and
`build-and-sign` ran with `--build-date 2026-09-25` and a key generated for the run and deleted.
- `diff` of the live catalog against the result is **one added block, `61a62,113`**: the `coreml`
  entry. No line of the live catalog changed, so the ONNX and face entries are byte-identical.
- `DRY_RUN=1 scripts/models-publish.sh` on that directory, with no credentials and a placeholder
  endpoint, printed nine `aws s3 cp` lines and ran none: seven artifacts (the two Core ML archives,
  `tokenizer.bin` once, and the four published files), then `catalog.json`, then `catalog.json.sig`.
- ⚠ That catalog is not the one to publish: it is signed with a key no device trusts.

### 12.5 Still owed, and still inferred

- ⚠⚠ **The maintainer signs and publishes** (`models-pipeline.md` §6.1). It must precede PR-5's 📱 pass.
- ⚠ **"A build contributes one runtime per model id" has no check in the client.** It is true of
  every graph today, and `CoreMlCatalogEntryTest` fails if `SUPPORTED_RUNTIMES` gains `coreml`.
  A graph that contributed `{"onnx", "coreml"}` by another route would keep both entries and take
  the first. A check in `usableModels` is client code and was out of this PR's scope.
- ⚠ **D13's other half**: whether one stamp may cover two precisions once labels stop being inert.
  ⭐ **Measured 2026-10-10** (`models.md` §3.10.5): per-artifact thresholds do **not** make the two
  platforms' labels interchangeable. ⭐ **Decided 2026-10-11: the first writer wins** (the D13 row;
  `inference-service.md` §39.1).
- ⚠ **The kept export is one copy on one disk**, and now a signed catalog will depend on it until
  the publish. After the publish the CDN holds the second copy.
- ⚠ **Nothing has downloaded these archives from the CDN** through the app. The dry run lists the paths; a real
  fetch of a 555 MB artifact with `Range` resumption is PR-5's first device pass.
  ⚠⚠ **Ranged requests have been made by hand since, and the text archive does not always honour
  them** (2026-10-11, §14.12): a 1 000-byte range was answered `200` with all 555 540 480 bytes.
## 13. As built — PR-3 (2026-10-07)

Marks as above. PIO-D10 is built: on iOS Photos owns the inference stores. No backend, no indexing
conditions and no model runtime are bound anywhere (PR-5). No schema'd type is reshaped: the stamp
and the fingerprint are files in Photos' container, so HR 9 owes nothing.

### 13.1 What shipped

| Piece | Where |
|---|---|
| `named("inferenceFileDatabase")`, default = the shared `FileDatabase`; feeds both logs and the model store | `control-shared-proxy/src/commonMain/…/di/ProxyModule.kt` (`INFERENCE_FILE_DATABASE`) |
| Lock 3: Photos' private, backup-excluded database, its real stores, the file index, the custody | `control-shared-proxy/src/iosMain/…/di/OwnedInferenceStores.kt` (`ownedInferenceStoresModule`, `OwnedInferenceStores`) |
| Lock 4: `UnownedEmbeddingStore`, `UnownedFaceTemplateStore`, bound by every iOS graph | `inference-service-proxy/src/commonMain/…/UnownedInferenceStores.kt`; the iOS `PlatformProxyModule` |
| The model index port: `ModelIndexStorage`, `SecureStorageModelIndex` (default), `FileModelIndexStorage` | `inference-service-proxy/src/commonMain/…/catalog/ModelIndexStorage.kt` |
| The realm stamp, the fingerprint sidecar, the gate: `InferenceStoreCustody`, `InferenceRealm`, `RuntimeFingerprint`, `InferenceStoreGate` | `inference-service-proxy/src/commonMain/…/InferenceStoreCustody.kt` |
| What this build would make a vector with | `inference-service-proxy/src/iosMain/…/coreml/IosRuntimeFingerprint.kt` |
| The two check points in shared code | `MediaIndexRunner` (`beforePass`), `PhotosLikeImplementation` (`storeGate`) |
| The other two, and deprovision | `tenshu-sdk-ios/…/PhotosLocalRuntime.kt` |
| `registerMediaIndexSchedule()` gone from iOS (the retirement of older builds' rows was removed at the 1.0.1 re-cut, item 8) | the iOS `PlatformProxyModule` |

**On disk, in Photos' container.** The `FileDatabase` root is `<Application Support>/photos-private`
(decided by the maintainer, 2026-10-07; §3.1 said `…/inference`). The stores' logical paths keep
their `inference/` prefix, so under `…/inference` the logs would have sat at
`inference/inference/…`. No migration: no build has ever written to the old path. A test pins the
resolved path (`thePrivateRootIsPhotosPrivateAndNoSegmentIsDoubled`).

Under that root:

| File | Holds |
|---|---|
| `inference/embeddings.log`, `inference/faces.log` | the two logs, unchanged in format |
| `inference/models/…` | the model bytes, and `inference.models.index.json` + `…index.garbage.json` beside them |
| `inference/realm.stamp` | `{"ownerId": …, "storeEpoch": null}` |
| `inference/runtime.fingerprint` | the five parts of §3.2's table |

**The stamp, as built:**
- **The realm is the device's, read from Control's hand-off** (`RealmKeyMaterialBridge`), not from
  the graph's `SyncManager`. A resident runtime keeps the owner it was assembled with across a
  reset it did not see, and that reset is what the stamp is for.
- **A mismatch:** not usable first; cancel the pass; clear the vectors; clear the templates; write
  the stamp. A throw anywhere leaves the old stamp and the state `ClearFailed`.
- **No stamp is a mismatch.** A store from before the stamp, and one a deprovision left, are both
  cleared before use.
- **A stale runtime is not usable even after the clear.** When the hand-off names a realm the
  graph was not assembled for, the stores are cleared and stamped for the new realm, and the state
  is `StaleRuntime` until Photos is relaunched. This is the nearest thing to W18's fence that
  exists today.
- **No realm is not usable, and clears nothing.** A hand-off that cannot be read is not a reset.
- **Every op that reads a store asks the gate first.** §3.1 names `photosLike`; `faceGroups` is
  gated the same way (deviation 9).
- **The four points:** `install` and the foreground hook (`rehydrateSharedReplica`; ⚠ since
  2026-11-05 `checkAtForeground()`, the rehydrate having gone with the file store,
  `ios-shared-store-sqlite.md` B.22.9) launch the
  check on the assembly's scope; a pass asks at its start, inside the pass; `photosLike` asks
  before it asks for a backend, and a closed gate is `NO_BACKEND`.

**⚠⚠ Usable means the realm AND the fingerprint, checked by one call under one lock**.
`InferenceStoreCustody.ensureCurrentRealm` is the whole check, and it is what all four
points run:
1. the realm stamp, as above;
2. then the fingerprint: the custody is given `currentFingerprint` (on iOS,
   `iosRuntimeFingerprint` over the installed record) and applies it before it answers.
- The first cut applied the fingerprint only from the launch and foreground check, after the
  gate had already opened on the realm. A query or a pass that got to the gate first, on a
  matching realm, read or extended vectors another archive, OS build or transform had made.
- Now a caller that gets there first runs both halves itself. It sees the stores only after
  whatever had to be cleared is cleared, or it sees a closed gate.
- A fingerprint clear that throws, and a fingerprint that cannot be read, both leave the gate
  closed for every later caller until a check succeeds.
- While a fingerprint clear is in progress on a custody that was usable, `usable()` is false. A
  check that changes nothing does not touch it: it is read per image.
- A custody with no `currentFingerprint`, and the `Unstamped` gate off iOS, behave as before.

**⚠⚠ A directory that cannot be excluded from backup is not used**.
- `OwnedInferenceStores.excludeFromBackup` makes the directory, sets the flag with an error
  pointer, and reads it back from a fresh URL. Any failure is a reason.
- With a reason, **what the directory already holds is purged first**: a directory that lost its flag, or cannot be given it again, is being backed up with
  everything in it. `OwnedInferenceStores.purge` removes the whole `inference/` tree under the
  root through the real filesystem and logs whatever it could not remove.
  - ⚠⚠ **The models and their index go too.** "Never clear the models" (§3.1) is a rule about
    realms: the weights are every realm's, so clearing them there buys nothing and costs a
    download. Here they are ~709 MB to ~1.4 GB uploaded into the user's backup, and behind the
    refusing database nothing can read them. The index goes with the bytes, so nothing reports a
    model that is gone. The cost is a re-download once the exclusion can be set.
  - A purge that throws, or leaves something, changes nothing about what follows: the store still
    comes up refusing.
- Then the graph gets a database that reads as empty and refuses to store anything. The check's
  stamp write throws, the state is `ClearFailed` carrying `NotExcludedFromBackupException`,
  `usable()` stays false, and no file is written: not a log, not the stamp, not a model.
- ⚠⚠ **Removing is never refused.** A delete, and the empty write a store's `clear` makes before
  it, reach the real directory. An empty write where nothing is creates nothing.
- ⚠⚠ **A purge that left something is retried until it is clean**.
  The third cut stopped at the line above, and the promise was unreachable: hidden reads mean the
  stores load as empty, so a consent withdrawal, which decides "nothing to delete" from the
  store's own stamp, never called the `clear` that was allowed through. Templates stayed in a
  backed-up directory until the next launch.
  - The refusing database remembers what its last purge left and **purges again at every access**
    of any kind, until one is clean. Nothing can arrive after a clean purge, so that ends it.
  - The template store built over a refusing database also retries when it is asked for its
    **stamp**: that is the one answer a store gives from memory, and it is the one the withdrawal
    paths ask. With no realm the gate reads nothing, and this is then the only thing that reaches
    the directory.
  - So a withdrawal (through `faceGroups` or a refused pass), the realm-wipe hook's clears and a
    deprovision each remove what a failed purge left, the models included.
  - A retry never throws and logs what remains whenever that changes. ⚠ A `clear` over files that
    still cannot be removed does throw: that is the store's own contract, and the wipe hook
    relies on a clear that erased nothing saying so.
  - Why this and not a store that reports existence truthfully: a truthful stamp needs the log's
    content, and the point of the refusing database is that nothing reads content from a
    directory it may not use. A healthy database gets the plain store, whose stamp is the truth.
- The directory itself is made before the flag, because the flag lives on it. Nothing is put in
  it before the flag is confirmed.
- ⚠ A deprovision erases by path through a plain database, so it does not wait on the exclusion.

**What PR-5 calls:**
- `OwnedInferenceStores.usable(koin)` / `PhotosLocalRuntime.inferenceStoresUsable()`: `false` until
  a check has finished, and after any failed clear. It belongs in `AiReadiness`'s snapshot, beside
  the verdict: the backend's `ready` must be false while it is.
- `InferenceStoreCustody.applyFingerprint(…)`: `Unchanged`, `Applied(vectorsCleared)` or `Failed`.
  Anything but `Unchanged` voids a verdict whose key held the old fingerprint. ⚠ The check
  already applies the current fingerprint at all four points, so by the time PR-5's writer 4
  looks, the sidecar is current: it must compare what its verdict was keyed on with what this
  build would make now, not wait for an `Applied`.
  - ⚠ As built (§14.2): the verdict's key is **wider** than this fingerprint. `PreflightKey` holds
    it and the vocabulary's digest, `PREFLIGHT_REVISION` and the app build. The custody is
    handed the fingerprint half only; a change to the rest voids a verdict and clears nothing.
- `iosRuntimeFingerprint(installed)` for the custody; `iosPreflightKey(installed)` for readiness.

### 13.2 ⚠⚠ The `meta.epoch` half — ✅ built (2026-11-10)

`InferenceRealm.storeEpoch` is the shared store's `meta.epoch`, as text. The `currentRealm` lambda
in `ownedInferenceStoresModule` supplies it; §3.1's same-owner residual is closed.

- **The seam.** `ReplicaEpochSource.currentEpoch()` (`core-common` iosMain) on the graph's own
  `ObjectStore`, read through `OwnedInferenceStores.replicaEpochOf`. `SharedReplicaStore`
  implements it as **one empty access**: the epoch is compared under the write lock as every
  access does (`ios-shared-store-sqlite.md` §3.1), so a sibling's wipe or migration fences the
  handle *there* and answers `null`. ⚠ Never the `epoch` field: that is what the open read, and a
  wipe since is seen only at the handle's next access. Nothing reaches into SQLite from Photos.
  The test replica (`TestReplicas`) implements the same contract (a random epoch per name, moved by
  a wipe, `null` to a handle made before it), so every Photos test runs the production reader.
- **A changed epoch** is a mismatch like an owner change: the pass is stopped, vectors and
  templates are cleared, then the stamp is written. ⚠ A **migration** moves the epoch too (its
  Start and its Finish), so a schema migration of the shared store re-indexes. Accepted: a wipe
  and a migration leave no trace that tells them apart afterwards, and a migration followed by a
  wipe before Photos runs would hide the wipe from any rule that kept vectors across one.
- **An unknown epoch** (the not-ready stand-in, a fenced handle, a refused or deferred access, a
  closed graph) is `RealmReading.CannotTell(owner)`: the third answer, beside a realm and none.
  - **Same owner as the stamp:** that check answers `false` **to its caller only** and changes
    nothing: no state, no cancelled pass, no clear, no stamp written. A query is refused; a pass at
    its start is deferred and indexes and writes nothing (the deferral path every refusing gate
    takes; `ForegroundIndexing` resumes it once, then the next foreground or *Index now* does).
  - **Another owner, or no stamp:** a change that needs no epoch to see: the pass is stopped, both
    stores cleared, and the stamp **erased** (there is no epoch to write); the next check that
    reads one stamps.
  - ⚠⚠ Never "unchanged": that would serve an old generation's vectors over a replaced store. ⚠⚠
    Never "no realm" either, which is what the first cut of this did: `NoRealm` cancels a running
    pass, and a blind read is frequent and transient (a sibling's `-wal` reset, a deferred
    admission in protected background time, protected data; every wipe passes through it twice,
    §5.1), so a search could stop indexing with nothing to restart it.
  - ⚠ **Accepted, as before:** a pass already running goes on under the answer it started with,
    which is the gap every check has between the check and the reads after it. The pass has no
    per-image custody gate; the backend's per-call `usable()` is the custody's last answer, which a
    blind read does not move. A store really replaced fences the graph's handle, and the graph's
    stop ends the pass (`ios-shared-store-sqlite.md` B.16 (241)); its rebuild reads the new epoch
    and clears.
  - ⚠ **Known, not bounded:** the check holds the custody's `Mutex` across the epoch read, one
    SQLite access, and an access whose gate is closed waits for admission with no deadline. A
    query or pass waiting there would wait on the store anyway; the one caller with a budget,
    `relinquish`, waits for the `Mutex` cancellably and falls back to erasing by path.
- **A legacy stamp** (`{"ownerId":…}`, written while `storeEpoch` was `null`) decodes with `null`,
  so the first epoch-bearing check is a mismatch and **clears once**; the next launch on the same
  epoch keeps. Adopting it was rejected: an un-recorded generation is exactly the same-owner reset
  the half exists to catch, and the cost is one re-index on a build whose flag is off.
- **The stamp's format is unchanged** (the same JSON, the field already existed). It is device-local
  and not schema DSL, so House Rule 9 does not apply: no version, no `SchemaMigration`.
- ⚠ `runtimeServes` still compares the owner only: a graph whose handle is fenced never passes a
  check, because its epoch reads `null`.
- **Guards, each removed once and seen red** (§13.5's rule): a blind read for the same owner
  changing nothing (`aBlindReadForTheSameOwnerRefusesTheAskerAndStopsNoPassClearsNothing`,
  `aMomentaryBlindReadWhileResident…`); a blind read for another owner still clearing
  (`aBlindReadForAnotherOwner…`); the blind answer refusing at all
  (`anEpochThatCannotBeToldAtLaunch…`); the seam's `null` not falling back to the graph's store; the per-access read rather than the open's field
  (`aSiblingsWipeIsUnknownToTheOldHandleAndANewEpochToTheNextOpen`); the epoch in the stamp at all
  (`aWipeOfTheSharedStoreThatKeepsTheOwnerIsClearedAtTheNextLaunch`).

### 13.3 How the launch path is bounded

- **`install` and the foreground hook never check inline.** They launch the check on the
  assembly's `Dispatchers.Default` scope. The stores are first constructed there too, which
  matters as much as the clear: a log store reads its whole file in its constructor.
- **Until the check returns nothing is usable**, so being late is safe.
- **A clear is a fixed number of small file operations** whatever the log holds: one empty write
  and one unlink per log.
- **`deprovision` is synchronous and on the launch path**, so it is bounded differently:
  - the stamp is removed first, so whatever is left is cleared by the next check before use;
  - with a graph, the custody's clear gets a 2 s budget (its one open-ended wait is a running pass
    letting go);
  - past the budget, or with no graph, the files are emptied and unlinked by path, with no read.

### 13.4 Deviations from §3–§5, and why

0. **The private root is `<Application Support>/photos-private`, not `…/inference`** (§13.1).
1. **The other graphs' test names Drive, Mia and Control, not Calendar.**
   `CalendarLocalRuntime.assemble` constructs `IosNotificationPresenter`, and
   `UNUserNotificationCenter` aborts a process with no bundle, which the headless test host is.
   Calendar's one override is that presenter. Mia is the app that asks `photosLike`.
2. **The other graphs keep the model store over the shared database**, as §5's row says (*"the App
   Group store path"*). Nothing can commit to it: no graph but Photos' will ever contribute a
   runtime. ⚠ That is lock 1, not a store that cannot write. Making the store itself refuse there
   would need a `ModelStoreRefusal` that is neither of the two that exist (§13.6).
3. **The gate is bound by default, not looked up.** `ProxyModule` binds
   `InferenceStoreGate.Unstamped` and both call sites use `get`. A `getOrNull` would read a custody
   that failed to construct as *no gate*, and open.
4. **The compute units and the OS build both clear.** §3.2 gives them the probe check; the probe
   has no threshold, so the safe rule covers both.
5. **A missing fingerprint sidecar clears the vectors.** Not in §3.2. Vectors whose making is
   unknown cannot stay beside new ones.
6. **A fingerprint failure is held apart from the realm state**, so a realm check that passes
   afterwards cannot make the stores usable again.
7. **Nothing migrates the model index out of the Keychain.** No iOS build has ever been offered a
   model, so no index exists there.
8. ⚠ **Removed at the 1.0.1 re-cut (2026-11)**: every store was wiped and every app ships together,
   so no iOS replica holds a row an older build registered. `retireMediaIndexSchedule`,
   `RetiredMediaIndexPassImplementation` and `scheduledPassRetired` are gone; what follows is the
   history. **The persisted registry row is cancelled on iOS, once per process start** (the
   first cut only removed the registration). `retireMediaIndexSchedule()` runs from the iOS proxy
   init in every graph and issues `CancelScheduled` for `tenshu/inference/mediaIndexPass`.
   ⚠⚠ **It lists first and cancels only a row that is there.** A cancel takes the shared
   replica's writer lock; the list is a plain read.
   - ⚠ The reason first given for this was a measurement that does not hold up. The second cut
     cancelled unconditionally, and `SyncActivitySharedReplicaTest` failed 4 runs of 14 against 0
     of 6 with the cancel removed and 0 of 10 with the look — but those three sets ran at
     different machine loads (~20–60, ~25, ~17–30).
   - **Measured again under one condition** (fifth round) [M: simulator]: two test binaries, one
     with both retirements and one with neither, run alternately 190 times each at a load of
     12–36. **With: 6 failures. Without: 5.** The test fails about 3 runs in 100 on its own
     (fixed `delay(200)` waits over two runtimes on one replica), and the retirement as built does
     not move that. Whether the unconditional cancel did is not known.
     ⚠ The parenthesis is wrong about the cause (2026-10-10): it was the runtime's own first
     tick settling a flag the test had raised, and the test now stops the scheduler
     (`ios-sync-activity-indicator.md` §10.3). The retirement comparison stands.
   - The look is kept: it is the cheaper call whichever is true.
   ⚠⚠ Safe because **the registry is device-local**, read in code:
   - `ScheduleItem`'s only trait is `DataIdProvider` (`ScheduleItem.kt:70-72`), and the relation it
     lives in (`ScheduleService.Collections.SCHEDULED_ITEMS`, `ScheduleStore.kt:15-24`) is a plain
     non-entity array. Neither carries `Syncable`.
   - `SyncObserver` emits a block only for a `Syncable` schema (`SyncObserver.kt:1329,1469`), and
     `TombstoneRegistry` records a tombstone only for one (`TombstoneRegistry.kt:206,222-225`). So
     a schedule row never leaves the device, and a cancel emits nothing.
   - The key is the dedup key alone, with no device or platform in it. That does not matter: the
     row is not shared with any other device. On iOS it is shared between this phone's first-party
     apps, through the App Group replica, which is what "realm-wide" means in the lease doc.
   - `CancelScheduled` removes the row from the array under the store lock and tells the platform
     scheduler (`CancelScheduledImplementation.kt:38-51`).
   So Android's and desktop's rows are their own, and they go on registering them. Nothing can
   loop: no device sees another's cancel. A test pins that neither of those two modules retires.
   ⚠⚠ **The retirement also happens where the row is dispatched**.
   The start-up look is one look, and the first-party apps update one by one: a sibling still on
   an older build registers the row again at each of its starts, after this process has looked.
   - On iOS the factory is given `scheduledPassRetired = true`, and the scheduled op
     (`MediaIndexPass`) becomes `RetiredMediaIndexPassImplementation`: it removes the row that
     dispatched it and returns. No pass, and no `ScheduledRunDeferred`, which would ask the loop
     to come back for a row that is gone.
   - So a recreated row is removed the first time any updated process's drain loop dispatches it,
     however often it comes back.
   - **Not a loop** [C]: nothing registers in answer to a cancel (`registerMediaIndexSchedule` is
     called only from a platform module's init). The row returns once per old-app start and is
     cancelled once per updated process that then dispatches it. The drain loop ticks every
     second (`ScheduleDrainLoop.kt:70`) and fires a key it has no memory of at its first tick
     (`:207-213`); a cancel during the run makes the loop forget the key (`:281-287`, `forget`), so
     the next recreation is again dispatched, and removed, at the next tick and not a period later.
   - **The start-up look stays, and is now mostly redundant**: where the drain loop runs, the
     dispatch does the same within a second. It costs one read, and it covers a graph whose sync
     runtime, and so its drain loop, has not come up.
   - ⚠ **PR-6 is not in the way of this, nor this of PR-6.** PR-6's wake is a `BGProcessingTask`
     under `app.tenshu.photos.processing` that calls `PhotosLocalRuntime.runProcessingWake`, which
     drives `MediaIndexRunner` (§5). It does not use the schedule registry, this dedup key or the
     `MediaIndexPass` op. *Index now* (`StartMediaIndexPass`) drives the runner too and is
     untouched. ⚠ What PR-6 must not do is invoke the `MediaIndexPass` op on iOS: there it is the
     retirement.
   - ⚠ A cancel still happens only for a row that is seen. `SyncActivitySharedReplicaTest` was
     measured with and without it, interleaved (above): 6 of 190 against 5 of 190.
9. **`faceGroups` goes through the same gate as `photosLike`** (the first cut left it
   to PIO-8). A closed gate is `FaceGroupsUnavailable` `NO_BACKEND`.
   ⚠⚠ **The gate decides what is served, never what is deleted** (third round). An explicit
   consent withdrawal deletes the templates *before* the gate is asked, in both places that
   deletion runs: `faceGroups`, and the index pass, where a pass the gate refuses still calls
   `MediaIndexer.deleteTemplatesOnWithdrawal`. Otherwise a gate closed by something unrelated to
   the templates (a vector clear that failed) would keep a withdrawn user's templates on disk for
   as long as it stayed closed. With no withdrawal the store is not touched ahead of the gate.
   **Every path that deletes vectors or templates, against a closed gate and against the refusing
   database:**

   | Path | Closed gate | Refusing database |
   |---|---|---|
   | Withdrawal, in `faceGroups` | runs before the gate (fixed) | the store reads as empty, so its `clear` is skipped; asking for its stamp retries the purge, which removes what is left (§13.1) |
   | Withdrawal, at pass start | runs when the pass is refused (fixed): by the gate, and since 5e-1 by the bar asked again before the walk | same |
   | Withdrawal, mid-pass | not affected: a pass that is running got through the gate | same |
   | The realm-wipe hook | not gated | its clears reach the real directory |
   | The custody's own clears, and `deprovision` | they are the gate | same; the erase by path uses a plain database |
   | ⚠ The reconcile of deleted photographs (P0f-b) | **still blocked**: it runs inside a pass. A deleted photograph's vector and template stay on disk, unread and filtered by live paths, until a check succeeds | the stores are empty |
   | Vectors on a consent withdrawal | no such path exists on any platform: a withdrawal deletes templates only | — |

   Readers:

   | Reader | Reads | Gate |
   |---|---|---|
   | `PhotosLikeImplementation` | the vectors | `storeGate`, first |
   | `FaceGroupsImplementation` | the templates | `storeGate`, first |
   | `MediaIndexer`, through `MediaIndexRunner` (*Index now*, and the scheduled op when a runner is wired) | both, and writes both | `beforePass` |
   | `MediaIndexer`, called directly by `MediaIndexPassImplementation` when **no** runner is wired | both | ⚠ none. Every platform module wires the runner; only a fixture does not |
   | `InferenceStoreCustody` | clears both | it is the gate |
   | The realm-wipe hook (`onLocalStoreWiped`) | clears both, reads nothing | none needed |

   Nothing else reads them: Mia reaches the vectors only through `photosLike`, Photos' search and
   People only through the two ops, and the Models screen's coverage line is the last pass's own
   counts (`LastIndexCoverage`), not a store read.
10. **`DefaultModelStore`'s reclamation rule is unchanged**, on purpose. A file index is no more
    durable on return than a delegate write, so the store still reclaims only against an index a
    later process read back. A test pins it over the file index.

### 13.5 Guards, each removed once and seen red

One mutation at a time, the suite run, the red cases read, the file restored and its hash
compared [M: 39 on the JVM, 9 on the desktop JVM, 28 on the simulator; the counts include the
review rounds'].

| Guard | Removed by | Red |
|---|---|---|
| The stamp is written after both clears | writing it first | the vector-clear and template-clear throw cases |
| Both stores are cleared on a mismatch | dropping either clear | five and nine custody cases |
| The pass is cancelled before the clear | no cancel | `aMismatchStopsThePassBeforeItClears`, the resident case |
| A pass never cancels itself | cancelling when `fromPass` | `thePassItselfIsNeverCancelledFromItsOwnCheck` |
| No stamp is a mismatch | reading none as current | `aStoreWithNoStampIsCleared` and ten more |
| A failed clear is not usable | `Usable` on failure | the three throw cases |
| A stale runtime is not usable | ignoring `runtimeServes` | `aRuntimeAssembledForAnotherRealm…` |
| The epoch is compared | comparing the owner alone | `theStoreEpochIsHalfOfTheStamp` |
| No realm is not usable | `Usable` with no realm | `withNoRealmNothingIsUsableAndNothingIsCleared` |
| Nothing is usable before a check | starting `Usable` | `nothingIsUsableBeforeACheckHasFinished` |
| Each of the four clearing parts of the fingerprint | each comparison removed | its own case |
| The text digest does not clear | making it clear | `aChangedTextArchiveKeepsTheVectors…` |
| A missing sidecar clears | keeping the vectors | `vectorsWithNoSidecarAreCleared` and eight more |
| A fingerprint failure blocks `usable` | not consulting it | `aFingerprintClearThatThrows…` |
| The fingerprint clear cancels the pass, and spares the templates | each | the five clearing cases |
| `relinquish` removes the stamp, and clears the templates | each line removed | the two relinquish cases |
| Erasing at rest writes nothing where nothing was | writing unconditionally | `erasingAtRestWritesNothingWhereNothingWas` |
| The runner's pass-start gate | removing the check | the two runner cases |
| `photosLike`'s gate | never asking | the three `photosLike` cases |
| Each store reads the named database | the unnamed `get()` | `a graph that binds its own inference database…` |
| ⭐ Lock 4 | the two unowned bindings removed | Drive's, Mia's and Control's cases |
| ⭐ The index beside the bytes | not passing the port | `aReinstallWithTheBytesGoneReportsNoModelInstalled` and four more |
| Lock 3 | the owner's database = the shared one | seven Photos cases |
| The gate reaches `photosLike` on iOS | not passing `storeGate` | `aResetWhilePhotosIsResident…BeforeTheNextPhotosLike` |
| The gate reaches the pass | `beforePass = { true }` | `aResetWhilePhotosIsResidentIsCaughtAtTheStartOfTheNextPass` |
| `install` checks | the launch removed | `installChecksTheStampAndSoDoesEveryReturnToTheForeground` |
| The foreground hook checks | the launch removed | the same |
| `deprovision` clears | the call removed; the custody's clear skipped | `deprovisionClears…` |
| The backup exclusion | not setting it | `photosInferenceDirectoryIsExcludedFromBackup` (first cut; the read-back now turns this into a refusal) |
| The realm is the device's, not the graph's | reading the `SyncManager` | the resident and the install cases |
| The stale-runtime rule is wired | `runtimeServes = { true }` | the same two |
| ⭐ The gate applies the fingerprint before it opens | not applying it in the check | `aQueryThatReachesTheGateFirst…`, `aQueryNeverSeesVectorsAnotherArchiveMade`, the resident and the throw cases |
| A pass is not cancelled by its own fingerprint clear | cancelling when `fromPass` | `aPassThatReachesTheGateFirst…`, `aPassNeverStartsOverVectorsAnotherArchiveMade` |
| Not usable while a fingerprint clear runs | not closing first | `aFingerprintThatChangesWhileResidentClosesTheStores…` |
| Usable again once that clear is done | not restoring the state | `applyingAFingerprintLeavesAUsableCustodyUsable…` |
| An unreadable fingerprint closes the gate | opening on it | `aFingerprintThatCannotBeReadLeavesTheGateClosed` |
| A failed fingerprint clear stays closed for the next caller | not recording the failure | `aFingerprintClearThatThrowsLeavesTheGateClosed` |
| ⭐ The fingerprint source is wired on iOS | no `currentFingerprint` | `aQueryThatBeatsTheLaunchCheck…`, `aPassThatBeatsTheLaunchCheck…`, two more |
| ⭐ A failed backup exclusion refuses | returning the real database | `aDirectoryThatCannotBeExcludedFromBackupHoldsNothingAndNothingIsUsable` |
| The refusing database refuses writes | writing through | the same |
| The private root is `photos-private` | `"inference"` again | `thePrivateRootIsPhotosPrivateAndNoSegmentIsDoubled` |
| ⭐ `faceGroups` asks the gate before it reads | never asking; the factory not passing it | the three `faceGroups` gate cases |
| The gate reaches `faceGroups` on iOS | not passing `storeGate` | `aResetWhilePhotosIsResident…BeforeTheNextFaceGroups` |
| ⭐ An upgraded install drops the old row | the retirement removed from the iOS init | `anUpgradedInstallNoLongerHoldsTheMediaIndexRow` |
| The retirement cancels the media-index key, and cannot throw | another key; a narrower catch | `MediaIndexScheduleRetirementTest` |
| The retirement cancels only a row that is there | cancelling unconditionally | `withNoMediaIndexRowNothingIsCancelled` |
| ⭐ A row recreated after start-up is removed when dispatched, every time | iOS not passing `scheduledPassRetired`; the retired op doing nothing | `aRowAnOlderSiblingRegistersAfterStartUpIsRemovedWhenItIsDispatchedEveryTime`, `whereTheScheduleIsRetiredBeingDispatchedCancelsTheRowAndRunsNoPass` |
| The scheduled op neither runs nor defers on iOS | the same | `theScheduledPassOpDoesNotRunOrDeferOnIos` |
| Off iOS the dispatched op is still the pass | the factory always retiring; the desktop module passing the flag | `whereTheScheduleStandsBeingDispatchedRunsThePassAndCancelsNothing`; the source-text test |
| Android and desktop register and never retire | each, in the desktop module | ⚠ a source-text test (`OwnedInferenceStoresWiringTest`) |
| ⭐ A withdrawal deletes through a closed gate, in `faceGroups` | the gate asked first | `aWithdrawalDeletesTheTemplatesThroughAGateAFingerprintFailureClosed` |
| …and in a refused pass | the call removed from the runner | `aPassTheGateRefusesStillDeletesTheTemplatesOfAWithdrawnUser` |
| A closed gate alone deletes nothing | deleting without a withdrawal | the two "deletes nothing" cases, and the indexer's own |
| ⭐ A directory that loses its exclusion is purged | no purge | `aDirectoryThatLosesItsExclusionIsPurgedModelsIncludedAndThenRefuses` |
| The purge takes the models too | only the logs and the stamp | the same |
| A purge that throws still yields a refusing store | no catch around it | `aPurgeThatFailsStillYieldsARefusingStore` |
| Removing is never refused | the refusing database's delete a no-op; its empty write refused | `whatAPurgeLeftCanStillBeErasedThroughTheRefusingStore` |
| ⭐ A withdrawal's own question retries the purge | the template store's stamp not retrying | `aWithdrawalWithNoRealmStillRemovesWhatAFailedPurgeLeft` |
| ⭐ Every access to the refusing database retries | not retrying on access | `theRealmWipeHooksClearsRemoveWhatAFailedPurgeLeft`, `deprovisionRemovesWhatAFailedPurgeLeft` |
| A failed purge is retried, not forgotten | marking it clean after one attempt | the five "removes what a failed purge left" cases |
| A retry never throws | no catch | `aPurgeThatFailsStillYieldsARefusingStore` |
| The purge runs at open, and stops once clean | no purge at open; never stopping | `theRefusingStorePurgesAtOpenAndStopsOnceAPurgeIsClean` |
| The custody's cancel is the runner's | an empty lambda | ⚠ a **source-text** test only (`OwnedInferenceStoresWiringTest`) |
| iOS registers no schedule | the call put back | ⚠ the same source-text test |

⚠ **Two tests passed for the wrong reason first, and were rewritten.** The pass-start case
asserted empty stores, and a pass over an empty library reconciles both logs to nothing with or
without the gate; it now asserts the stamp. The erase case looked for a file afterwards, and a
write that is then unlinked leaves none; it now counts writes.

**Not proved:**
- `excludeFromBackup`'s own two checks, the Boolean and the read-back. The real call does not
  fail on the simulator, and the test's seam replaces the whole function.
- That a pass in flight is really stopped in an assembled iOS graph. A pass there has no backend
  and ends at once, so the wiring is pinned by source text, and the order by the custody's test.
- `deprovision`'s 2 s budget and its fall-back to erasing by path after a timeout.
- That the check is off the main thread. It is by construction; nothing asserts the thread.
- Calendar's assembled runtime (deviation 1), and the six other route-1 apps. They add no
  inference binding [C], and lock 4 is in the module all of them load.
- Anything on a phone: every case ran on the simulator, where there is no App Group and the
  "shared" directory is the test host's Application Support.

### 13.6 Still owed

- **Smoke** (`scripts/android-smoke.sh`): Control's common DI changed. The orchestrator's.
- ~~**The epoch half**, after S0b-1 (§13.2).~~ ✅ Built 2026-11-10 (§13.2).
- **PR-5** wires `usable()` into readiness and binds the backend. Not before this is merged.
- **A model store that refuses in the other graphs** (deviation 2), if lock 1 alone is judged too
  weak: a third `ModelStoreRefusal`, with a sentence for a screen no other iOS app has.

### 13.7 As built — the index's read contract (2026-10-11)

Marks as above. `DefaultModelStore` is shared: Android and desktop run it in production, iOS from
5c. On an index that reads and decodes nothing changes. No schema'd type is reshaped (HR 9), and
no operation gained a parameter or a reason.

**The fault** [M: reproduced as three failing cases on `54b14e97f` before any change].
`readIndex` took `null` from the storage, and any string that did not decode, as an **empty
index**, cached that for the life of the process and ran the launch sweep against it.
- ⚠⚠ **The sweep deleted the model.** A commit declares the paths it creates, and the next
  process's first read deletes every declared path no record names. With the index read as
  empty no record named anything, so the process after an install deleted that install's bytes.
  Worse than the orphaning §14.6 recorded as owed.
- A later commit wrote back an index holding only its own record. A removal wrote back `[]`.
- On iOS the custody's fingerprint is made from the install record, and *no model* is a
  fingerprint the vectors do not survive: the next check **cleared every vector**.
- Readiness read `NoModel` for a model that was there.

**Per platform** [C unless marked]:

| Platform | Index lives in | Absent | Could not be read | Read, does not decode |
|---|---|---|---|---|
| iOS (Photos) | a file beside the bytes (`FileModelIndexStorage` over `IosFileDatabase`) | `null` | ⚠⚠ **`null` as well**: `NSData.dataWithContentsOfFile` is `nil` either way. **The fault was real here** [M: on the simulator, with the file's permissions removed, the read is `nil` and the stat still answers] | empty: real |
| Android | `SharedPreferences` (`tenshu_plain_storage`), through `SecureStorageModelIndex` | `null` | not reachable through `getString`: the file is loaded whole and answered from memory. A file the framework cannot parse is loaded as an **empty map, for every key** [I: AOSP's `SharedPreferencesImpl.loadFromDisk`, from memory, not run]; nothing at this interface can tell that from a first launch | empty: real |
| Desktop | `local-store.properties`, loaded once by `DesktopSecureStorageDelegate` | `null` | the load throws in the delegate's constructor, so the graph does not come up: loud, and not this store's | empty: real |

So *could not be read* was iOS's alone, and *does not decode* was every platform's.

**The rule: nothing is answered, written or deleted from an index assumed empty.** §14.6's rule
for the verdict file, on the store.

| The index | Is | `installed`, `pathOf` | Commit, removal, `beginStaging` | Launch sweep |
|---|---|---|---|---|
| not there, or blank | an empty index | `[]`, `null` | go through | runs |
| read, decodes | the index | as before | as before | runs, as before |
| **could not be read** (the storage threw; or the file is there and gave no bytes) | unknown | throw `ModelIndexUnreadableException` | **refused**, the same exception, before anything is staged, renamed or written | **not run**. Nothing is cached, so the next call reads again, and the first read that yields an index sweeps |
| **read, is JSON, is not this build's record** | another build's record, possibly | throw, `undecodable = true` | **refused** | **not run**. Remembered for the life of the process: nothing writes over it, so it cannot change, and `pathOf` is on the per-image path |
| **read, is not JSON at all** (a record cut short, noise) | no index, **by decision** | `[]`, `null` | go through, and the commit **writes over it** | ⚠ **not run** in that process. The process after the commit sweeps against the record it reads back |

- **`null` is not yet *absent*** on the file index: after a `null` it asks `fileSize`, as
  `AiReadiness.readVerdict` does. `ModelIndexStorage.getString` now says so: `null` is absent
  and nothing else.
- **The garbage list has the same three answers.** One that cannot be read stops the sweep for
  that process (the files wait for the next one, as after a death) and **refuses a
  declaration**, so a commit or a removal fails before it renames anything: written back from a
  list assumed empty, the earlier declarations would be lost. One that was read and does not
  decode holds no declaration: the cost is files never reclaimed, not files deleted.
- **A download is refused before its fetch.** `beginStaging` reads the index, as it already
  asks for ownership. ⚠ So a process that staged has had its first read, and the sweep, by then.
- **What readers do with the throw** [C, and M where a case is named]:
  - `AiReadiness.read()` catches it with everything else: *unreadable*. Not served, no probe,
    nothing written (§14.6).
  - `InferenceStoreCustody` catches it at `currentFingerprint`: stores unusable, **no clear**
    [M: `anInstallRecordThatCannotBeReadClearsNoVector`].
  - Android and desktop: `OnnxModelFiles.storeLocator` and `faceStoreLocator` answer **no
    path**, and not the side-loaded file. Nothing throws out of `supports`.
  - ~~`InstalledModels` and `DeleteModel` fail untyped;~~ `DownloadModel` fails as
    `TRANSPORT_FAILED` with the store's message, before any artifact is requested. ⚠ **Since
    2026-10-12** (§14.11, *What reaches the wire*) `InstalledModels` and `DeleteModel` fail the
    same way, typed: one mapping serves every model operation.

**Every path that deletes on the strength of the index** [C: by reading every caller of
`installed`, `pathOf` and the store's own deletes]:

| Path | Deletes | On an index that is unreadable or another build's |
|---|---|---|
| `DefaultModelStore.sweep`, at the first read | declared `.model` files no record names | not run |
| `DefaultModelStore.remove` | declares the model's files | refused |
| `DefaultModelStore.commitStaged` / `add` | declares the replaced generation; deletes staging files and markers | refused before any of it |
| `InferenceStoreCustody.applyFingerprint` (iOS) | the vectors, when the installed vision digest changed | not reached: the fingerprint could not be made |
| `AiReadiness` | nothing; it closes sessions | not served |
| `OwnedInferenceStores.purge` (iOS) | the whole inference tree | unrelated: it asks the backup exclusion, never the index |
| `beginStaging`, `abortStaging` | one artifact's `.part` and marker | `beginStaging` refused; `abortStaging` reads nothing |

Nothing lists the models directory, so no path deletes a file *because no index names it*
without a declaration.

**Decisions, each with its alternative:**
1. **JSON of another shape refuses; it is not read as empty.** A build that adds a field with
   no default turns every installed index into this. Refusing costs a Models screen that fails
   until a build that reads the record; reading it as empty costs every user their models.
   *Alternative:* treat it like the next row. ⚠ There is no reset for it (`tech-debt.md`).
2. **Bytes that are not JSON are no index, and the next commit writes over them.** No build
   can read them as records, and refusing would leave a desktop whose last write died with a
   Models screen that never works again. *Alternative:* refuse these too, with the same
   missing reset.
3. **No sweep against bytes that were not an index**, although they are read as empty. The
   declared files are orphans either way once a commit writes over the index, and the process
   after that commit reclaims them against a record it read. *Alternative:* sweep at once.
4. **The locators do not fall back to a side-loaded file.** The side-load path is for a store
   that holds no verified checkpoint, which is what is not known. *Alternative:* fall back, as
   the old empty index did.
5. **No new `ModelUnavailable` reason.** It is a wire vocabulary, and the screen would need a
   sentence for it. *Alternative:* `storeUnreadable`, with the client's copy (`tech-debt.md`).

**Guards, each removed once and seen red** [M: the suite run on the JVM after each, the red
cases read from fresh result files, the file restored and its hash compared; the first also on
the simulator].

| Guard | Removed by | Red |
|---|---|---|
| A file that is there with no bytes is unreadable | dropping the stat after `null` | `anIndexFileThatIsThereAndGivesNoBytesIsUnreadableNotEmpty`, `aCommitOrARemovalOnAnUnreadableIndexIsRefusedAndTheDiskIsLeftByteIdentical`, `theSweepWaitsForAnIndexThatWasReadAndThenRunsAsBefore`, `aGarbageListThatCannotBeReadStopsTheSweepAndRefusesADeclaration`, `anInstallRecordThatCannotBeReadClearsNoVector`; on the simulator also `anIndexFileFoundationCannotReadIsUnreadableAndTheModelSurvivesIt` |
| No sweep against bytes that were not an index | sweeping against the empty list | `bytesThatAreNotJsonAreNoIndexAndNothingIsSweptAgainstThem` |
| JSON of another shape throws | answering *no index* | `anIndexOfAnotherShapeIsNotEmptiedSweptOrWrittenOver`, `anIndexOfAnotherShapeIsReadOnce`, `aStoreWhoseIndexCannotBeReadRefusesTheDownloadBeforeAnyRequest`, both locator cases |
| That answer is remembered | not storing it | `anIndexOfAnotherShapeIsReadOnce` |
| A storage read that throws is the typed exception | letting it through as it is | `aDelegateReadThatFailsOnceIsRefusedOnceAndThenForgotten` |
| `add` reads the index before it stages | staging first | `aCommitOrARemovalOnAnUnreadableIndexIsRefusedAndTheDiskIsLeftByteIdentical`, `aDelegateReadThatFailsOnceIsRefusedOnceAndThenForgotten`, `anIndexOfAnotherShapeIsNotEmptiedSweptOrWrittenOver` |
| `beginStaging` reads the index | not reading it | the same first and third, and `aStoreWhoseIndexCannotBeReadRefusesTheDownloadBeforeAnyRequest` |
| The sweep stops at a garbage list it cannot read | letting the read's throw out | `aGarbageListThatCannotBeReadStopsTheSweepAndRefusesADeclaration` |
| A declaration is refused over such a list | declaring over an empty one | the same |
| `storeLocator` does not side-load | falling back | `anIndexThatCannotBeReadClaimsNothingAndDoesNotFallBackToTheSideLoadedFile` |
| `faceStoreLocator` does not throw | rethrowing | `anIndexThatCannotBeReadClaimsNoFaceModelEither` |

**Not proved:**
- Anything on a phone. In particular that a file under data protection before the first unlock
  reads as `nil` with a stat that answers [I]: the simulator case removes the file's
  permissions instead.
- The Android row's *could not be read* [I], and Android and desktop at all beyond the JVM
  suite: no emulator and no packaged desktop ran this.
- That nothing memoises a failed read, by mutation. The recovery halves of the cases assert it.
- `DefaultModelStoreConcurrencyTest.theFirstReadsRecoverySweepNeverRunsInsideACommit` now
  stages through a second store, standing for an earlier process: since `beginStaging` reads
  the index, the downloader can no longer open that window, and the lock is still what closes
  it for a commit of bytes staged before the process began.


## 14. In progress — PR-5, split into five slices; 5a, 5b, 5c-1, 5c-2, 5d, 5e-1 and 5e-2 as built

Marks as above. PR-5 as §5 writes it is one row with thirty test cases. It lands as five PRs, each
behind the build flag, each with the Kotlin decisions first (PIO-D2). **5a, 5b, 5c-1, 5c-2 and 5d (the
Models screen, 5d-1 to 5d-3) are built; 5e is two PRs, 5e-1 (the conditions, the pass and its
stop) and 5e-2 (the indexing section) both built. The flag is off and is not 5e's to move.**
§14.2–§14.5 are 5a's record; §14.6–§14.10 are 5b's; §14.11–§14.16 are 5c-1's; §14.17 is
5c-2's; §14.18 is 5d's; §14.19 is 5e's. ⚠ What must hold before the flag flips is §14.18.16. No schema'd type is reshaped (HR 9): the verdict
is a file in Photos' container, like the stamp and the fingerprint.

### 14.1 The split

| Slice | What it builds | What it proves | What it leaves inert |
|---|---|---|---|
| **5a** ✅ **built** | `AiReadiness` (the state, its four writers, the ordered transitions, the persisted verdict's file); the static gates (build flag, iOS 17, the memory threshold); `CoreMlInferenceBackend` bound once in Photos' graph over PR-3's custody; the `AskThisDevice` route and the search view model's `noBackend` seam | flag off = today's graph and today's search; each gate; bound once; `supports` false in every state but `Ready`; both transition orders; a replacement not served before its own verdict; the copy per state | **Everything a user could reach.** No runtime is contributed, so no model is offered; no indexing conditions, so no pass; no preflight, so no verdict. The state is `NoModel` on every phone |
| **5b** ✅ **built** (§14.6) | The preflight (D12 ②): both towers and the vocabulary on sessions opened **outside** the registry; output validation (finite, non-zero, a norm near the export's, the fixed cross-tower ranking probe, whose image, captions and margin `scripts/inference/preflight-probe.py` records per export); the retry rules (a hot or pressured phone stays `AwaitingVerdict`, bounded backoff; `Unsuitable` only after three slow probes on separate foregrounds at nominal or fair; three load errors or wrong outputs are a failed install, never an unsuitable phone); the attempt counts in the verdict file; the launch warm-up of the text tower; when a preflight is started (on a launch or a foreground that finds `AwaitingVerdict`; after writer 1 once 5c calls it) | never `Ready` on NaN, zeros, a wrong norm, a mis-ranked or narrowly ranked pair, other token rows or an export with no probe, on fake sessions; the retry table; `Unsuitable` is reached only the slow way; one pair of towers at most, across a teardown too; and, opt-in on the simulator, a pass on the real towers | Nothing then: no model could be installed. ⚠ Since 5c-2 the probe runs at every launch and foreground for a prepared model with no verdict, and since this fix a probe Photos does not survive is counted (§14.5, D12) |
| **5c-1** ✅ **built** (§14.11) | The download: `supportedModelRuntimes = COREML_RUNTIMES` (**bound here, not before**, §14.4 item 2); the durable journal in the private `FileDatabase`; the background `URLSession` driver and `handleEventsForBackgroundURLSession` (Drive's precedent); `allowsCellularAccess` / `allowsExpensiveNetworkAccess` on the request (D6); integrity from disk before anything is staged; the free-space check before the download with §3.4's budgets and its own margin; every commit and removal through `AiReadiness.storeChanging` (writer 3) | a terminated-app relaunch installs from the journal and discards a corrupted file; a refusal below the additional need; the request's two flags; a journal that cannot be read starts, cancels and deletes nothing | Nothing then. ⚠ Since 5d-3 Photos' Models screen starts it (the flag is still off) |
| **5c-2** ✅ **built** (§14.17) | The prepare step through `CoreMlModelCache` (writer 1) and `Preflight.prepared()`'s caller; the compiled cache evicted on a removal, swept at launch, and the old generation's directory deleted **before** a replacement compiles; a `.mlmodelc` that fails to load recompiled once | an interrupted prepare is redone; the order of delete-before-compile and the disk peak at each step; a removal leaves no compiled directory | As 5c-1 |
| **5d** ✅ **built: 5d-1, the sources (§14.18.10), 5d-2a, the shared view model (§14.18.13), 5d-2b, the iOS repository (§14.18.14), and 5d-3, the SwiftUI screens (§14.18.15)** (§14.18: its states, the wire memo, twelve decisions taken 2026-10-13, and the plan for one shared view model, §14.18.11) | The SwiftUI Models screen in Photos' Settings over `ModelsViewModel` (PR-4): the catalog row, the download size and the on-disk estimate with the first-open allowance, the bar, *Preparing* and *Checking*, the two consent switches, the coverage line, why a phone is `Unsuitable` and the delete; `:inference-service` exported from the umbrella; Photos' own wording for `noConsent`, and Mia's three iOS lines (§1.8) | Swift UI tests on a fixture backend: consent order, the refusal sentences, off-vs-none | ⚠ **No indexing section at all** (decided 2026-10-13, M5; this cell said *"Index now refuses"*): the button, the bar, pause and coverage arrive whole in 5e |
| **5e** — **5e-1 ✅ built, 5e-2 ✅ built** (§14.19, §14.19.11) | **5e-1:** `IosIndexingConditions` as `ForegroundIndexingConditions` over `IosIndexingSignals` (scheduled never outside a wake, which does not exist; manual per §3.3, thermal included, §14.19.1); cancel-on-background inside a `beginBackgroundTask` window, with both towers closed; thermal backoff with a rest; `closeSessions()` on a memory warning and on background with no pass; the process's pass slot; the bar before the walk; the backend's own opens recorded (§14.19.3). **5e-2:** the indexing section on Photos' Models screen (§14.19.6), the coverage line across an iOS update, Mia's three lines, `PrivacyInfo.xcprivacy` for Photos. ⚠ As written here before the build: *chunking* is deferred (no N, §14.19.8); the iOS 17 load of both towers (D12 ⓪) cannot run on this Mac (no iOS 17 runtime) | the conditions' truth table; a background notification cancels at the next photo and the next press resumes; `.serious` stops, rests and goes on by itself (and 5e-2 says why) | The flag. It moves only after H5 on the phone, which waits for S0b (a)–(d) and for PR-2b's publish, and after the build-numbering precondition in §14.5 |

Order: 5a → 5b → 5c-1 → 5c-2 → 5d → 5e. 5b needs nothing downloaded: it is tested on fake sessions. 5d
can start beside 5c. ⚠ 5c was cut in two when it was built (§14.11, *The cut*): the download is
one reviewable change, and the prepare step touches a different class and a different disk budget. ⚠ **The flag must not be switched on in any build before 5d**: from 5c on a
model can be `Ready`, and the proxy's next refusal is then `noConsent`, whose sentence names a
switch in Control that Control-on-iOS does not have (§3.2; the test of 5a pins that this is what
the screen would say).

### 14.2 What 5a shipped

| Piece | Where |
|---|---|
| `AiStaticGates`: the three gates and the placeholder threshold | `inference-service-proxy/src/commonMain/…/AiStaticGates.kt` |
| `AiReadiness`, `AiReadinessState`, `AiModelFacts`, `PreparedModel`, `PreflightVerdict` | `inference-service-proxy/src/commonMain/…/AiReadiness.kt` |
| `coreMlBackendOver(readiness, …)`, `coreMlModelFacts(store, cache)`, `iosAiDevice()` | `inference-service-proxy/src/iosMain/…/coreml/CoreMlReadiness.kt` |
| `photosOnDeviceAiModule(root)`, `PhotosOnDeviceAi` (what the umbrella may ask) | `control-shared-proxy/src/iosMain/…/di/PhotosOnDeviceAi.kt` |
| The gate decision, the module, writer 4 at launch and on foreground, the route | `tenshu-sdk-ios/…/PhotosLocalRuntime.kt`, `PhotosSurface.kt` |
| `onNoBackend`, a defaulted constructor seam | `photos-shared-logic/…/search/PhotoSearchViewModel.kt` |
| The flag | `photos-ios/project.yml` |

**The build flag.**
- `photos-ios/project.yml` has the build setting `TENSHU_PHOTOS_ON_DEVICE_AI: "NO"` and writes it
  into the Info.plist key `TenshuPhotosOnDeviceAi`. `PhotosLocalRuntime.onDeviceAiBuildFlag` reads
  the key and is on only for the exact string `YES`: a missing key, an empty one and an
  unsubstituted `$(…)` are all off.
- It is the repo's existing route for a per-app build-time value on iOS (`TenshuControlAppStoreId`,
  `TenshuSharedKeychainGroup`): build setting → `project.yml` → generated Info.plist → Kotlin.
  A constant generated into the umbrella was the alternative, and the umbrella is one binary
  linked into ten apps.
- To try it: `xcodebuild … TENSHU_PHOTOS_ON_DEVICE_AI=YES`. The line in `project.yml` does not move
  until H5.
- **With the flag off** `assemble` passes the same `ownedInferenceStoresModule` it passed before,
  reads nothing about the phone, binds no backend, and `PhotosSurface.search` builds
  `DescriptionSearch.NotBuilt` as before. The only code that runs and did not before is the
  Info.plist read.

**The static gates** (`AiStaticGates.decide`), in this order, at assembly:
1. the build flag;
2. `NSProcessInfo.operatingSystemVersion.majorVersion ≥ 17`;
3. `NSProcessInfo.physicalMemory ≥ PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES`.

A refusal at any of them is one outcome: no module, no readiness state, `NotBuilt`.

⚠⚠ **The memory threshold is a placeholder: 6.5 GiB (6 979 321 856 bytes). It is not measured.**
D12 ① wants it from the footprint of a pass through the backend with both sessions open, on a
phone at the intended floor. Neither reading exists (D10 and D12 are open).
- Why this value: every figure in this plan was taken on one phone, with 8 GB, and D12 lowers the
  floor only by measuring a phone at the new floor. So the placeholder admits the measured class
  and nothing below it. `physicalMemory` cannot exceed a phone's nominal RAM, so no 6 GB phone
  reaches 6.5 GiB; 1.5 GiB is left for what an 8 GB phone's kernel keeps back.
- ⭐ [M] The iPhone 17e reports **8 081 145 856 bytes (7.53 GiB)**, above the placeholder, so the gate
  admits the one phone the feature was measured on (2026-10-22, the harness's `physicalMemory` line;
  `inference-service.md` §15.14.7).

**Readiness, as built.**
- ⭐ **Per model since IN-6 F4 (2026-11-10; `ios-face-grouping.md` FD7, §14).** `AiReadiness` takes
  a `ReadinessSlot` — its verdict file, scene file and serving-open roles — and `PreparedModel` is a
  map of catalog role → path. SigLIP's slot is `ReadinessSlot.SIGLIP`, the default, with exactly the
  paths below (`/inference/preflight.verdict`, `.scene`, `.open.vision` / `.open.text`), so no phone's
  verdict moves. The face pair gets `ReadinessSlot.FACES` and its own key, counts and *Try again*;
  `Preflight` keeps the whole protocol and takes the model's probe as a `PreflightProber`
  (SigLIP's is `SiglipPreflightProber`, moved, not changed). `RuntimeFingerprint` gained
  `detectorRevision`, `null` for SigLIP and so never encoded: every fingerprint and verdict on disk
  decodes equal. Everything below is still SigLIP's, unchanged; nothing binds the face slot yet (F5).
- **Derived, never set.** Each writer re-reads the install record, the two ready markers and the
  verdict file, and moves to what they say. The verdict is `{key, passed}` and counts only when
  its key equals the one this build would make **now** (`iosPreflightKey(store.installed())`).
  Every artifact's digest is in the key, so a replacement has no verdict by construction.
- ⚠⚠ **Two keys, with different inputs**. They are two types, and neither
  is accepted where the other is wanted:

  | | `RuntimeFingerprint` (PR-3, the custody's) | `PreflightKey` (readiness's) |
  |---|---|---|
  | Answers | may the stored vectors stay? | has what would run now been checked? |
  | Holds | vision and text archive digests, compute units, OS build, preprocessing revision | that fingerprint, **plus** the vocabulary's digest, `PREFLIGHT_REVISION`, the app build |
  | A change | clears the vectors (not for the text digest) | voids the verdict; clears nothing by itself |
  | Kept in | `inference/runtime.fingerprint` | `inference/preflight.verdict` |

  - **Why they diverge.** The first cut keyed the verdict on the runtime fingerprint, as §3.2 and
    D12 word it. A catalog update that replaces the vocabulary and keeps both towers leaves that
    fingerprint unchanged, so the old pass restored `Ready` over a vocabulary no preflight had
    loaded or tokenised. The vocabulary cannot simply join the fingerprint: no stored vector
    depends on it, and a vocabulary change would then cost the library its index.
  - Readiness hands the custody `key.runtime` and nothing wider, on the way into `Ready`.
  - **Every input the preflight will validate (5b), against the key:**

    | Input | In the key? | If not, why no stale pass |
    |---|---|---|
    | Vision and text archives | yes (`runtime`) | |
    | Vocabulary | **yes, since this round** | |
    | Compute units, preprocessing revision | yes (`runtime`) | |
    | OS build | yes (`runtime`) | |
    | The compiled cache's directory | no | Its tag is a prefix of the archive digest, which is in the key. A marker for another digest is not this archive's marker, and with no marker the state is `Preparing` whatever the verdict says |
    | The marker's own OS build | no | The key holds the OS build that is running. A model compiled under an older OS and never recompiled is probed again because the key moved; whether it must be recompiled is §3.4's failure-driven rule (5c) |
    | The code a pass vouches for: tokenizer, `nfkc`, the vocabulary reader, `TowerGuards` and where the backend applies them, the session's layout and names, the preflight itself (probe image, captions, margin, latency rows, retry rule) | **yes** (`PREFLIGHT_REVISION`, hand-bumped; 1 in 5a, 2 since 5b built the preflight) | ⚠⚠ **This is the mechanism.** An app update can change any of it with every digest unchanged. ⚠ Not the image transform: `IOS_PREPROCESSING_REVISION` covers that, in the fingerprint, because it also decides whether vectors stay. One constant per question. ⚠ A hand-bumped constant decays: `PreflightRevisionPinTest` pins the token ids of nine fixed queries and the guards' answers on five vectors beside the number, on the JVM and the simulator, and its failure says to bump. It pins behaviour, not source. Since 5b it also pins the judge's answer on either side of every line it draws, the retry rule's numbers and the shipped probe's rows and margin (§14.9). It does not reach the session's layout or the order of the preflight's steps on a phone, which nothing in `commonTest` can observe |
    | The app build | yes (`CFBundleShortVersionString (CFBundleVersion)`) | ⚠⚠ **Distinct per archived build, constant per development build** (2026-11-09, §14.18.16 item 3). `scripts/ios-archive.sh` numbers every archive (the UTC minute), so a TestFlight or App Store update is a new key; a development build reads `ios-release/release.yml`'s `1`. *(As first written: every build read `1.0 (1)`, before the archive script numbered builds.)* |
    | Thermal state, memory pressure | no | They are what the phone was doing, not what it is (D12). A probe under them writes no verdict at all |
    | `physicalMemory`, the iOS floor | no | Static gates: below either, no readiness exists to hold a verdict |

  - **A verdict file of another shape is no verdict.** No field of the key has a default, so a
    file written under the first cut's shape (`{"fingerprint": …}`), under round 4's field name
    (`probeRevision`), or with any part of the key missing, does not decode, and the state is `AwaitingVerdict`. Nothing has shipped, so there
    is no migration.
- **The snapshot holds the paths.** `Ready` carries the two compiled paths and the vocabulary's,
  and the backend's three locators read them from there. `supports` therefore reads one volatile
  field and the custody's, and no file, which is what §10.6 asked of this slice. It also leaves
  one answer to *which generation*: a tower readiness does not serve has no path.
- **Prepared means all three, as the disk has them**: both towers'
  markers for the installed digests with their compiled directories, and a vocabulary **file**
  of the recorded size at the recorded path, read through the model database. Anything less
  stays `Preparing`.
  - The first cut took the vocabulary's path from the install record and looked no further. A
    record that outlived its file, with a passing verdict on disk, restored `Ready`; the backend
    claimed search and every query came back `noTextEmbedding`.
  - ⚠ Size, not digest. Hashing 3.1 MB is affordable off the main thread, but this runs at
    every launch and foreground, the bytes were verified against the signed catalog at commit,
    and whether they still parse and tokenise is 5b's preflight (D12 ②), asked before a verdict.
  - Everything else the snapshot holds was checked for the same fault. The towers: marker and
    compiled directory on disk at every read; ⚠ not the directory's contents, which a load
    failure answers (`Failed`, not latched; §3.4's recompile is 5c-2). The verdict: its file. The
    install record itself: the index file, beside the bytes since PR-3. The two digests in the
    fingerprint are the record's by design: they name what was probed. The archives are not
    read to serve, only to recompile.
- **The writers:**
  1. `markerWritten()`, and since 5c-2 its wrapper form `compiledChanging` (§14.17), which is what the prepare step calls.
  2. `recordVerdict(passed, probed)`: the bare verdict. Since 5b the preflight reaches it
     through `recordProbe`, which counts and decides under one lock and says what it did
     (§14.6). A verdict about a key that is no longer current is dropped.
  3. `storeChanging { … }`. **Called** since 5c-1 (§14.11): by the download's commit and by `DeleteModel`. It wraps the commit or the removal (§14.4 item 4).
  4. `fingerprintChecked()`. **Called**: by `PhotosLocalRuntime`, at install and on every return
     to the foreground, after the stores' own check, on the same `Dispatchers.Default` launch.
- **Leaving `Ready`:** the snapshot, then `cancelRunningPass()`, then `closeSessions()`. The
  sessions are closed even when the cancel throws.
- **Entering `Ready`:** `InferenceStoreCustody.applyFingerprint`, then the verdict file, then the
  snapshot. A fingerprint that cannot be applied, or a verdict that cannot be written, leaves
  `AwaitingVerdict`.
- **Teardown: `retire()`, terminal, in two halves**. `deprovision`
  retires readiness **before** it relinquishes the stores.
  1. **`retire()`, on the caller's thread, waits for nothing.** It sets one volatile flag: from
     there nothing is served from this graph and no writer brings it back. Not a suspend
     function, no lock.
  2. **`unloadRetired()`, behind it, with no deadline.** The rest of the leaving sequence, the
     pass and then both towers, on a scope of `PhotosOnDeviceAi` that outlives the assembly's.
  - ⚠⚠ **Which thread:** the main thread. `deprovision` is called from Photos' `adopt()`, which
    SwiftUI runs from `.task` and `.onChange(of: scenePhase)`.
  - ⚠⚠ **Why two halves.** Round 2's cut did both inside a 2 s `runBlocking`. The budget bounded
    nothing: closing a tower waits for the prediction that holds it, inside `NonCancellable`, and
    a prediction is a blocking call into Core ML that nothing interrupts. On the main thread
    that is the watchdog. What makes the old graph harmless is the flag; unloading is memory.
  - ⚠⚠ **What a prediction in flight can still do once the stores are given up: finish, and be
    thrown away.** `CoreMlInferenceBackend` asks `ready()` again **after** every prediction,
    image and text, so the vector of a graph that stopped serving while it ran never leaves the
    backend. The indexer gets `Failed`, which it stores nothing for and remembers nothing
    against the photo; a query gets no vector.
    - Without that guard the write was reachable. The relinquish cancels the pass and joins it
      inside its own 2 s budget; a pass stuck in a prediction outlasts the budget, the logs are
      then erased by path, and when the prediction returned the pass could still reach one
      `put`: cancellation is not checked on a `withContext` that does not change dispatcher,
      nor on an uncontended `Mutex`. The record would have sat in a file with no stamp, cleared
      by the next check before any read, so never served; it is now never written.
    - The same guard covers a realm reset or a fingerprint clear that lands under a prediction.
  - ⚠⚠ **The next runtime loads no model until the old one has unloaded**. Neither the teardown nor the next `install` waits for the unload, so without more a new
    graph could open its pair while the old pair is still held by a prediction: two pairs
    resident, the jetsam case.
    - `PhotosOnDeviceAi` holds **one process-level handle**, the latest pending unload. It is not
      threaded through any graph: a process has one set of models whatever graphs exist.
      Teardowns stack, so each unload first joins the one before it, and the handle is cleared
      when its job completes unless a later one has replaced it.
    - The wait is where memory is committed: `CoreMlInferenceBackend` awaits `beforeLoad` at
      the start of every embed, image and text, before the tower's lock. In Photos' graph that
      is `awaitPendingUnload()`. The new graph assembles, reconciles and is `Ready` meanwhile.
    - **A query or a pass on the new graph waits; it is not refused.** It is bounded by its
      caller: search gives a source 60 s, then says it could not look and offers a retry; a pass
      is cancelled like any other. With nothing pending it costs one atomic read.
    - ⚠ If the old prediction never returns, the new graph never loads a model in this process,
      and search by description reads as "could not look" until Photos is relaunched (§14.5).
  - ⚠⚠ Why terminal: the Koin graph outlives its runtime. `deprovision()` cancels a scope and
    closes no graph, and a SwiftUI `@StateObject` keeps the old repository, so the old backend
    stays reachable (the reason D12's graph reassembly was withdrawn). After `retire` its
    backend has no path to open, so a re-provision cannot end with two pairs of towers loaded.
  - The first cut had no teardown of the model at all. `deprovision` cleared the search route
    and closed the custody, which made the old backend claim nothing; its sessions stayed open
    until something called it, which after a sign-out nothing does.
- **The stores closing under a `Ready` model** (same review). The custody closes at a gate, on
  its own schedule: a realm mismatch, a stale runtime, a failed clear, a directory that lost its
  backup exclusion. The model is still the right one, so the state stays `Ready`; `serves()` is
  false at once, and the backend closes a tower when it is next called. So that the towers do
  not stay loaded for as long as nobody calls, writer 4 (launch, every foreground) also stands
  the model down when it finds `Ready` over unusable stores: cancel, close, state unchanged.
  - ⚠ Residual: a custody that closes at a pass's or a query's gate, with no call into the
    backend afterwards, keeps the towers loaded until the next foreground. ~32 MB (§2.2).
  - A fingerprint change needs no step of its own: a new archive goes through writer 3, which
    leaves `Ready` first; a new OS build or build constant is a new process, which loads nothing
    before writer 4 has found the verdict void.
- **The verdict file** is `inference/preflight.verdict` under Photos' private root, beside the
  stamp and the sidecar. A realm reset and a deprovision leave it: it is about the phone and the
  public weights, not about a realm. The purge of a directory that lost its backup exclusion
  takes it, with the models.

**How readiness relates to PR-3's custody.** The custody decides whether the *stores* may be read
or written (which realm, which runtime made the vectors). Readiness decides whether the *model*
may run. Neither repeats the other's check:
- the backend's `ready` is `AiReadiness.serves()`: `Ready` **and** `custody.usable()`, read at the
  call. The custody's answer is not copied into the snapshot (§13.1 suggested it): it changes on
  its own schedule, at a gate, and a copy would be stale exactly then;
- the only thing readiness asks the custody to *do* is `applyFingerprint`, once, on the way into
  `Ready`. It never clears anything itself, and it never reads the sidecar;
- ⚠ a consequence: `StartMediaIndexPass` asks the registry before the pass reaches its gate, so
  between a launch and the end of the first store check a `Ready` phone answers `NO_BACKEND`.
  That is the safe side, and it lasts as long as the check.

**What search shows**, per §3.2's table, on one view model for the life of the screen:

| Bound? | State | Status | Line |
|---|---|---|---|
| no (flag, iOS, memory) | — | `NotBuiltHere`, the proxy never asked | *"…isn't available on this iPhone yet."* |
| yes | `NoModel`, `Preparing`, `AwaitingVerdict` | `Unavailable("noBackend")` | *"…needs a model on this device, and there isn't one yet."* |
| yes | `Unsuitable` | `NotBuiltHere`, mapped from `noBackend` | *"…isn't available on this iPhone yet."* |
| yes | `Ready` | the proxy's next answer | none, or that answer's own line |

**Threat model (HR 2).** Nothing moves and nobody new can read anything. The verdict file holds
two archive digests, an OS build string, two build constants and a Boolean, in the container the
stamp and the sidecar already live in (A5 / C4e as amended by PR-3). No amendment.

### 14.3 Guards, each removed once and seen red

One mutation at a time, the class run, the red cases read from the result XML, the file restored
and its sha256 compared [M: 36 on the JVM, 24 on the simulator; the teardown rows were re-run in round 3; round 4 added 4 and 3, round 5 added 4, round 6 added 6]. Every row went red; every file was restored.

| Guard | Removed by | Red |
|---|---|---|
| The flag is decided first | ignoring it | `withTheFlagOffNothingIsAdmittedAndThePhoneIsNotAsked` |
| With the flag off the phone is not read | reading the phone before the flag | the same |
| The iOS 17 gate | removed | `aPhoneBelowIos17IsRefusedWhateverItsMemory` |
| The memory gate | removed | `aPhoneBelowTheMemoryThresholdIsRefused` |
| The placeholder admits no 6 GB phone | 6 GiB | `thePlaceholderThresholdAdmitsNoSixGigabytePhone` |
| ⭐ Only a `Ready` snapshot holds a model | `Unsuitable` carrying one; `AwaitingVerdict` carrying one | `aFailOutlivesTheProcess`; `onlyReadyServes` and two more |
| ⭐ `serves` reads the custody | not asking `storesUsable` | `aReadyModelIsNotServedWhileTheStoresAreUnusable` |
| ⭐ The verdict is keyed on the **whole** preflight key | the runtime fingerprint alone | `aVocabularyOnlyReplacementVoidsTheVerdictAndKeepsTheVectors`, `aNewPreflightRevisionOrANewAppBuildVoidsTheVerdictAndKeepsTheVectors` |
| A late probe is dropped on the whole key too | comparing the runtime half | `aVocabularyOnlyReplacementVoidsTheVerdictAndKeepsTheVectors` |
| ⭐ The custody is handed the fingerprint, never the key's other parts | the vocabulary folded into what it is handed | the two cases above (*cost the library its index*) and four entering cases |
| The key carries the vocabulary's digest | `null` | `aVocabularyChangeMovesThePreflightKeyAndNotTheVectorsFingerprint`; in the graph, `aVocabularyOnlyReplacementIsNotReadyOnTheOldPassAndTheVectorsStay` (two runs) |
| The key carries the app build | a constant | the first of those, `thisProcessHasAnAppBuildOfSomeKind`. ⚠ It proves the field is wired, not that it varies: it does not (§14.2) |
| ⭐ A tokenizer change without a bump is loud | padding with `<pad>`; no lower-casing (two runs) | `PreflightRevisionPinTest`, with the instruction to bump, beside the tokenizer's own cases |
| A guard change without a bump is loud | all-zero vectors accepted | `PreflightRevisionPinTest`, beside `TowerGuardsTest` |
| A bump revisits the pin | `PREFLIGHT_REVISION = 2` alone | `PreflightRevisionPinTest` (second assertion) |
| A verdict of another shape is none | defaults on the key's fields **and** the narrow compare, together | `aVerdictWrittenUnderAnotherKeyShapeIsNoVerdict`, and the two cases above |
| ⭐ The verdict is keyed (round 0) | any verdict counts | `aReplacementIsNotServedUntilItsOwnVerdict`, `aNewOsBuildVoidsTheVerdict…`, two more |
| A verdict about a fingerprint that moved is dropped | keeping it | `aReplacementIsNotServedUntilItsOwnVerdict` |
| ⭐ Entering: the flip is last | flipping before the fingerprint; flipping before the verdict | `enteringReadyAppliesTheFingerprintThenPersistsTheVerdictAndFlipsLast` and four more; the same and two more |
| Entering: the fingerprint is applied | not applying | `aPassingReplacementClearsTheOldVectorsBeforeItIsServed` and four more |
| A fingerprint that cannot be applied is not `Ready` | ignoring `Failed` | `aFingerprintThatCannotBeAppliedIsNotReadyAndPersistsNothing` |
| A verdict that cannot be written is not `Ready` | ignoring the failed write | `aVerdictThatCannotBePersistedIsNotReady` |
| ⭐ Leaving: the flip is first | cancel and close before it | the three `…LeavesReadyInOrder` cases a reconcile reaches |
| …also for a failing verdict | the same, in `recordVerdict` | `aFailingVerdictLeavesReadyInOrder`, `aCancelThatThrows…` |
| ⭐ Leaving: cancel, then close | close first | four `…LeavesReadyInOrder` cases, `whatCannotBeReadIsNotServed` |
| Leaving cancels the pass | no cancel | six cases |
| Leaving closes the sessions | no close | six cases |
| The sessions close even when the cancel throws | no `finally` | `aCancelThatThrowsStillClosesTheSessions` |
| Nothing is stopped for a model nothing served | stopping on every change | `aChangeBetweenStatesThatDoNotServeStopsNothing` and five more |
| ⭐ Writer 3 stops serving before the store moves | not leaving first | `aRemovedModelLeavesReadyInOrder` |
| Never straight from one model to the next | entering without leaving | `aModelThatMovedUnderTheSameVerdictIsLeftBeforeItIsEntered` |
| What cannot be read is not served | staying `Ready` | `whatCannotBeReadIsNotServed` |
| A failing verdict is persisted | not writing it | `aFailOutlivesTheProcess`, `aFailingVerdictLeavesReadyInOrder` |
| Nothing is read at construction | reading the facts there | `itStartsWithNoModelAndReadsNothingUntilAWriterRuns` and one more |
| ⭐ A `noBackend` refusal is put to the device | never asking the seam | `aNoBackendRefusalReadsAsNoModelYet_untilTheDeviceSaysItIsUnsuitable` |
| Only `noBackend` is | every `Unavailable` | `onlyTheNoBackendRefusalIsRemapped` |
| The seam is asked when the refusal arrives | asking once, at construction | both |
| ⭐ `ready` is readiness's, not only the locators | `ready = { true }` in `coreMlBackendOver` | `whileTheStoresAreUnusableNothingIsClaimedAndNoSessionIsKept` (the text session stayed open) |
| ⭐ The locators read the snapshot | a fixed path | `leavingReadyClosesBothSessionsAndTheReplacementIsOpenedOnlyAfterItsOwnVerdict` |
| Prepared needs the text tower | vision alone | `oneTowerAloneIsNotPrepared`, `aMarkerForAnotherArchiveIsNotThisOnes` |
| Prepared needs the vocabulary | both towers alone | `bothTowersWithoutAVocabularyAreNotPrepared` |
| A digest that is not a sha256 is no model, not a throw | no catch | `anArchiveWhoseDigestIsNotASha256IsNotPreparedAndDoesNotThrow` |
| ⭐ The gates decide what `assemble` binds | always admitted | the four not-bound cases: flag off, the Info.plist, iOS 16, memory |
| ⭐ The route is the assembly's | always `AskThisDevice` | the same four, and the one-view-model case |
| ⭐ Bound once | `factory` for the backend | `onAnAdmittedPhoneTheBackendIsBound…`, `theBackendIsBoundOnce…` |
| Writer 4 is wired at install and on foreground | not called | `installAndEveryReturnToTheForegroundRecheckReadiness` |
| `Unsuitable` reaches the surface | `unsuitable()` always false; the surface never remapping (two runs) | `theBackendIsBoundOnceAndTheSearchLineFollowsReadinessOnOneViewModel` |
| The compiled cache is under `inference/` | `base = "coreml"` | the prepare case below, and three cases that need a prepared model |
| ⭐ `prepare` finds the store's archive through Photos' binding | the first cut's re-rooted database, put back | `aPrepareThroughPhotosOwnBindingFindsTheStoresArchiveAndKeepsItsOutputUnderInference`|
| A teardown returns to `NotBuilt` | keeping the route | `aDeprovisionedPhotosIsBackToNotBuilt` |
| ⭐ No runtime is contributed | contributing `coreml` | `onAnAdmittedPhoneTheBackendIsBoundAndWithNoModelClaimsNothing` |
| Only the exact `YES` | any non-blank value | `onlyTheExactYesSwitchesTheFlagOn` |
| ⭐ The graph's readiness reads the custody | `storesUsable = { true }` in the module | `aReadyModelIsNotOfferedUntilTheStoresAreUsable` |
| ⭐ `deprovision` retires readiness | not calling it | `deprovisionUnloadsTheModelAndTheOldBackendNeverReopens` (the tower stayed open) and the two other teardown cases |
| ⭐ The next runtime opens no tower while an unload is pending | `awaitPendingUnload` not joining; the handle never set (two runs) | `aNewRuntimeLoadsNoModelWhileTheOldOneIsStillHeldByAPrediction` |
| The handle is cleared when its unload is done | never clearing | `provisionDeprovisionProvisionNeverHasTwoModelsLoaded` (its last assertion) |
| Stacked teardowns chain | an unload not joining the one before | `aSecondTeardownDoesNotHideAnUnloadThatIsStillWaiting`. ⚠ Survived first: no case stacked two teardowns |
| Both towers await `beforeLoad` before opening | image not awaiting; query not awaiting (two runs) | `noTowerIsOpenedBeforeTheLoadIsAllowed` |
| ⭐ `deprovision` does not wait for the unload | joining it on the caller's thread | `aDeprovisionDoesNotWaitForAPredictionAndItsVectorIsThrownAway` |
| `unloadRetired` runs the leaving sequence | the flip alone | `retiringLeavesReadyInOrder`, `retiringStopsServingAtOnce…` |
| ⭐ `retire` is terminal | setting no flag; `recordVerdict` not reading it (two runs) | `noWriterBringsARetiredReadinessBack`, and for the first the two above |
| `retire` stops serving by itself, before any unload | `serving()` not reading the flag | `retiringLeavesReadyInOrder`, `retiringStopsServingAtOnceEvenWhileAWriterHoldsTheLock` |
| ⭐ A vector that outlasted its runtime never leaves the backend | no second `ready()` after the prediction, image and text (two runs) | `anImagePredictionThatOutlastsATeardown…`, `aQueryThatOutlastsATeardown…` |
| ⭐ The vocabulary is on disk, at its recorded size | the path alone; any size (two runs) | `aVocabularyTheRecordNamesAndTheDiskDoesNotHoldIsNotPrepared`, `aVocabularyThatIsNotTheRecordedSizeIsNotPrepared`; the second alone |
| A check that finds the stores closed unloads the model | no stand-down | `aCheckThatFindsTheStoresClosedUnloadsTheModelAndKeepsTheState` |

⚠ **Two things these runs showed that the rows do not say.**
- *Close before flip* (cancel and close ahead of the snapshot) was run again with the teardown
  cases in place: red on four leaving cases, **not** on `retiringLeavesReadyInOrder`. On
  `retire` the flip is the flag, set first, so that one mutation leaves it serving nothing.
  The order on teardown is held by two things, and removing either alone is survived there.
- ⚠ **The first run of *not calling `retire`* in round 3 hung the simulator host** and had to be
  killed: the blocked-prediction case failed an assertion before it released its latch, and
  its scope then waited for the prediction for good. Both latch cases release in a `finally`
  now, and the mutation was run again to a clean red.
- ⚠ *A verdict of another shape is none* is held twice: the missing field fails the decode, and
  the whole-key compare would refuse what a lenient decode let through. Neither mutation alone
  turns that case red; the row above removes both.
- *No vocabulary needed* (round 0) removed the role from the **record**. It could not see a
  record that names a file the disk does not hold: the path was all the code read.
- With `retire` not called, `provisionDeprovisionProvisionLeavesOneLoadedModel` was red in two
  runs and green in one (round 2, when it did not yet wait on the unload).
- ⚠ **That case then passed for a reason the product does not have** (round 6, Codex): from
  round 3 it joined the first runtime's unload before installing the second, which nothing in
  Photos does. It is now `provisionDeprovisionProvisionNeverHasTwoModelsLoaded`: no join, the
  second runtime installed and asked at once, and the most towers ever loaded together counted
  at each open. A launch check still in flight when the stores are relinquished can
  reach the stand-down above and close the towers by itself. That is a race, not a guarantee;
  `retire` is the guarantee.

⚠ **Two mutations survived first, and each found a missing test.** A derived `Unsuitable` that
carried a model was not caught: the only `Unsuitable` asserted on came from `recordVerdict`, which
publishes its own. And entering a second model without leaving the first was not caught: no case
moved the paths under one fingerprint. Both have a case now, and both mutations are red.

⚠ The mutation runs were made at a machine load between 20 and 600 (other sessions were
building). Every red above is a named assertion read from the result XML, not a timeout.

### 14.4 Deviations from §3–§5, and why

1. **The compiled cache is bound at `inference/coreml`, through a `base` parameter on
   `CoreMlModelCache`** (default `coreml`, so the harness's layout does not move).
   The cache wrote under `coreml/` of whatever root it was given, and §3.4 puts it at
   `inference/coreml/<digest-tag>/`. Given Photos' root as it is, the compiled models would sit at
   `photos-private/coreml/`, outside the tree `OwnedInferenceStores.purge` removes: ~740 MB left
   in a directory that is being backed up.
   - ⚠⚠ **The first cut re-rooted the database instead** (a `FileDatabase` that prefixed every
     path with `inference/`), and that was wrong. `prepare` reads the archive
     through the same database, by the model store's own key, which already begins with
     `inference/models/`. The prefixed view looked for it at `inference/inference/models/…`, so a
     prepare would have reported *no archive* and never written a marker once a download existed.
     Nothing in 5a calls `prepare`, which is why no test saw it. The wrapper is deleted.
   - The other readers of this slice were checked for the same fault: the marker reads and the
     compiled paths go through the cache (`<root>/inference/coreml/…`), and the vocabulary is
     read by the store's key through the unprefixed database. One case now runs a prepare
     through Photos' own module with an archive at the store's real path and asserts where
     everything lands, that the vocabulary reads back, and that a purge removes all of it.
   - It goes through the same database as before, so a refusing one still refuses the cache's
     writes.
2. **`supportedModelRuntimes` is not contributed.** §5's row lists it with the binding. Contributing
   it is what makes the catalog offer the download, and the only downloader in the graph today is
   the in-process one, with no journal, no Wi-Fi-only request and no free-space check. It moves to
   5c, with that transport. **As built in 5c-1** (§14.11): contributed in the same module as
   the downloader that may carry it and the store-change wrapper, all three or none.
3. **The snapshot is not loaded at assembly.** §3.2 says it is. Loading it constructs the model
   store, which reads its index, and assembly is on the launch path. It is loaded by writer 4 on
   the launch's background check; until then the state is `NoModel`.
4. **Writer 3 wraps the store's change**, where §3.2 has it follow the commit. The store declares
   the old files garbage when it commits, so a writer called afterwards leaves a window in which
   the snapshot still serves the old paths. `storeChanging` stops serving, runs the change, then
   re-reads.
5. **A failing verdict is persisted between the flip and the cancel**, not after the close: the
   flip is what stops serving, and the verdict should not wait on a pass letting go. ⚠ Since
   5b's review the flip is to *not served*, and `Unsuitable` is published only once the write
   succeeded (§14.6, *A write that fails*).
6. **What cannot be read is not served.** Not in §3.2: a model index or marker that throws moves
   a `Ready` state to `AwaitingVerdict`, in the leaving order.
7. ⚠⚠ **The verdict is not keyed on the runtime fingerprint**, which is what §3.2, D12 and
   §13.1 said until this slice. It is keyed on `PreflightKey` (§14.2). Those three passages are
   edited in place and now describe both keys. What changed, and why:
   - round 4: a vocabulary-only catalog update left the fingerprint unchanged, so the old pass
     stood over a vocabulary nothing had checked; and the vocabulary could not join the
     fingerprint without clearing vectors it does not affect;
   - round 5: the app build was added as the guard against a code change, and it is constant on
     every build of this repo. The guard is `PREFLIGHT_REVISION`, hand-bumped and pinned by a
     behaviour test; the app build stays in the key and is documented as inert.
8. **No `IosIndexingConditions`, no Models screen, no download, no preflight, no warm-up, no
   `PrivacyInfo.xcprivacy`, no change to Mia's lines.** They are 5b–5e (§14.1).

### 14.5 Still owed, and not proved

- **0. ⚠⚠ A load the process does not survive** (the integrated review's blocking finding,
  fixed before 5e). `Preflight` persisted nothing before it measured, and `CompiledCache` opened
  a tower before its marker, both at every launch and every `.active` (`recheckOnce`): a phone
  where loading the towers gets Photos killed (jetsam, a Core ML abort) was killed again at each.
  **As built:**
  - *What is persisted, and where.* `AttemptBegun(key, kind, role, process, serial, atEpochMs)`
    in the verdict's file (`/inference/preflight.verdict`, Photos' private `FileDatabase`, never
    synced, no schema's — HR 9 does not reach it), beside whatever record the file holds, written
    atomically (`NSDataWritingAtomic`) under readiness's lock **before** the load:
    `AiReadiness.attempting` runs the probe (`Preflight.measuredAndRecorded`), each tower's
    compile (unpack → move, kind `COMPILE`) and each tower's first open (`CompiledCache` →
    `LoadRecording`, from `ModelPreparer`) inside it. The record carries the foreground of the
    scene it began in.
  - *Keyed by* the whole `PreflightKey` of what would run now (both archive digests, the
    vocabulary's, the OS build, the app build, the revision). A record of another key is not
    counted against this one: an OS update or a new install starts at zero.
  - *Cleared* on every ending the process lives through, by the outcome itself (`recordProbe`
    writes the count or the verdict over it) or in `attempting`'s `finally` (a failure, Photos
    leaving the foreground, a teardown, a refused write): **a stop leaves no trace**. A clear that
    fails leaves the record; this process's next read clears it (the `AttemptLedger` says it
    ended), or the next process counts it — never the other way round.
  - ⚠⚠ *A load frozen when Photos leaves the foreground is not a kill*. A probe
    or a first open is one blocking Core ML call that nothing can stop; suspended under it, the
    app keeps the record, and iOS then ends a suspended app freely (a half-loaded ~1 GB model is
    a prime jetsam target). Counted, two such visits made a capable phone `Unsuitable` for good,
    and a swipe-away or a 0xdead10cc in the background took the same path. They **can** be told
    apart at the time: the process is alive when the scene leaves. So the scene's change writes
    `SceneLeft(process, leftForeground)` to its own file, `/inference/preflight.scene`, at once
    and with no lock (`AiReadiness.sceneChanged`, from `PhotosLocalRuntime.modelTransfersSettleNow`
    through `PhotosOnDeviceAi.sceneChanged`, before the scene moves); coming back clears it. A
    record whose process left the foreground it began in (or that began in none) is counted as
    **transient**: the backoff holds, nothing is decided. A kill in the foreground (the scene had
    not left, or came back) is still `interrupted`. A scene file that cannot be read is not read
    as either: the reading is unreadable and nothing is loaded.
    - ⚠⚠ *One record per process* (5e carry-over 1). The file was one
      record, and the **next** process wrote its own over it at its first scene change, before
      its first read of the verdict (the launch's check runs after the stores' check), so the
      earlier process's frozen load was counted as a kill. It is now `SceneRecords`: this
      process's record, and the one of the process whose load the verdict's file still holds
      (read without the lock: a settle reads the scene before it clears the load, so either
      interleaving keeps what it needs). A verdict that cannot be read keeps the newest eight; a
      scene file that cannot be read keeps none (an earlier frozen load is then a kill: the
      conservative side). The one-record file of the hardening before 5e is read as one record.
    - ⚠⚠ *A swipe-away of an app that is still running* (5e carry-over 2). From the app switcher
      the scene goes only to `.inactive`, which is ignored on purpose (Control Center, a call or
      the switcher itself is a foreground, and a jetsam there is a kill in front of the user), and
      a running app ended there gets no `.background`. `PhotosAppDelegate.applicationWillTerminate`
      writes the record instead: iOS calls it for a running app the user ends, never for a
      jetsam or a crash, so neither is forgiven by it.
    - ⚠⚠ *Leaving is idempotent*: a leave that names no foreground (the scene
      was not active: `applicationWillTerminate` after `.background`) writes nothing, or it would
      erase the foreground the first leave recorded while loads are still in the window.
    - ⚠⚠ *The records named by a tower's open record are kept too*: the
      backend's opens (§14.19.3) live in their own files, so the next process's scene write keeps
      the record of any process an open record names, read without the lock.
    - ⚠ *A write that fails* is tried once more. On **leaving**, a record that still does not
      land makes a frozen load a kill (conservative). On **coming back**, the stale record of the
      foreground left makes a load begun before it left and killed after the return transient:
      the unsafe side, bounded to that one load (5e carry-over 3).
    ⚠ Decided against: a
    `beginBackgroundTask` around the load. It would let most loads finish and clear, at the cost
    of ~1 GB held in the background; with the scene record the outcome is already right.
  - *Read at the next launch* by every reading of the file (`AiReadiness.settled`): a record of
    another process (`AttemptLedger.process` is per process) for the current key is counted in
    `PreflightAttempts.interrupted`, which feeds the backoff (its start is the record's time), and
    **written before it is believed**: a count that cannot be written makes the reading
    unreadable, and nothing is loaded. A record of this process whose load is still running (in
    this graph or another) is left alone. An **unreadable** file is not *no attempt*: nothing is
    probed (`PreflightOutcome.Unreadable`) and nothing compiled (`AiReadiness.loadGate` →
    `PrepareOutcome.Unreadable`), so no load runs that could not be counted; an **undecodable**
    one is, as before, no record and written over (another build's shape).
  - *After N kills.* `INTERRUPTED_LOADS_FOR_UNSUITABLE` = 2 (fewer than the three of a slow or
    failed probe: each is Photos killed in front of the user; kept at 2 by the maintainer,
    2026-10-14). The second makes the key `Unsuitable` — also over an install that never reached
    its markers (`AiReadiness`'s target reads a fail before *prepared*) — so nothing of it is
    probed or compiled again (`loadGate` → `ANSWERED`). The count is the phone's, like the slow
    one: a removal does not clear it.
  - ⚠⚠ *The way out* (round 2). That fail, and only that one (`PreflightVerdict.stoppedByInterruptedLoads`:
    a fail with fewer than three slow probes), is its own standing, `Standing.StoppedLoading`:
    *"Photos stopped trying after it closed twice while loading the model on this iPhone."*
    (placeholder copy, M11) with **Try again** (`Action.Kind.LOAD_AGAIN` → `AiReadiness.loadAgain`):
    the fail is withdrawn and the count set one below the bound, so the next interrupted load
    marks it again. Three slow probes keep D12's rule and the old sentence. ⚠⚠ **One press per
    key** (2026-11-09, §14.18.16 item 10): `PreflightAttempts.loadedAgain`, written before the load;
    a second stop is D12's plain `Unsuitable`, with no *Try again*. The other exit is a new key:
    every archived build carries its own build number (§14.18.16 item 3), so an app update starts
    the counts again.
  - ⚠⚠ *No download the key would answer at once.* For a model that is not installed the row
    asks the verdict's file about the key the catalog entry would have once installed
    (`iosPreflightKeyOf`: its digests and this OS build; equal to the installed key, pinned by
    `theKeyOfAnEntryAsItWouldBeInstalledIsTheKeyOfItsInstall`). Answered `Unsuitable`, the row
    says so and offers no Download; answered by interrupted loads alone, it offers Try again. A
    file that cannot be read offers Download as before: it concludes nothing either way.
  - *One lock for the file in the process* (round 2): `AttemptLedger.lockFor(path)`. Readiness's
    lock was per graph, and a retired graph and its successor could lose each other's
    read-modify-writes of the one file (a load's clear and writer 3's reset, interleaved: a false
    count after a later kill, or a missed one). Every reader and writer of the file now takes,
    inside its graph's own lock, the process's lock of the file (graph lock → file lock, always
    in that order); the order of §14.17.0 is unchanged (step lock → cache mark → readiness lock).
    ⚠⚠ Not `unloadRetired`: it touches no file and waits, with no deadline, for a prediction of
    its own graph. Made process-wide whole, the readiness lock kept a successor graph from reading
    its own state behind an old graph's prediction (`PhotosOnDeviceAiTest`'s two teardown cases
    went red), so the graph lock stays the graph's.
  - ⚠ *A verdict file that does not decode forgets its kills*, as it forgets a verdict: bytes
    another build wrote are no record (accepted).
  - *Delete* runs in that state: no probe and no prepare holds the step lock or a pair of
    towers, and the removal's eviction deletes the half-prepared directory too
    (`anInstallThatKilledPhotosCanBeRemovedAndIsNotLoadedAgainWhenFetchedAgain`).
  - *Tests* (common, so also on the simulator): `PreflightTest`'s cases under *a load the process
    did not survive*, *frozen*, the shared lock, a cancelled load and *the way out*, and
    `ModelPreparerTest`'s, each killing the process by making the disk ignore every write from the
    moment of the load and starting a new process (a new ledger) over it. ⚠ **That is as close as a
    test gets**: no real jetsam ran here; a phone must show that iOS kills at the moments assumed,
    and that the scene's write lands before the suspension.
  - ⚠ **Still open:** a crash elsewhere in the app during a load in the foreground counts as a
    kill (it is one, to the user); the backend's own tower opens after `Ready` (`warmText`, the
    index pass) are not bracketed (§14.18.16).
- **Smoke** (`scripts/android-smoke.sh`): owed. `PhotoSearchViewModel` is `commonMain` in
  `:photos-shared-logic`, which Android compiles, and it gained a defaulted constructor parameter.
  `:control:mergeLibDexDebug` ran here and passed. The orchestrator's, before merge.
- **Slices 5c-2–5e** (§14.1). Writer 1's caller is 5c-2's, built (§14.17). Writer 2's caller is 5b's (§14.6). Writer 3's two callers, the commit and the removal, are 5c-1's (§14.11).
- ✅ ~~**Before the flag moves (with H5, §14.1's 5e row):** every shipped build carries a distinct
  `CFBundleVersion`, **or** it is written down that `PREFLIGHT_REVISION` is the only thing standing
  between a code change and a stale pass.~~ **Both, 2026-11-09** (§14.18.16 item 3): every archive
  is numbered by `scripts/ios-archive.sh` (the UTC minute), and for development builds, which read
  `1`, `PREFLIGHT_REVISION` is written down as the only guard.
- ⚠ **The threshold** (§14.2): D10 and D12 ①. The iPhone 17e's `physicalMemory` is read: 7.53 GiB,
  above the placeholder (2026-10-22, `inference-service.md` §15.14.7).
- ⚠ **Nothing on a phone.** Every case ran on the simulator. `iosAiDevice()` is asserted only to
  return a plausible version and size.
- ⚠ **The flag was never built on.** `ios-smoke.sh` builds with the setting as it is, `NO`, and the
  built Info.plist was read back as `NO`. The `YES` path is covered from Kotlin, through
  `assemble`'s seam and the parse of the key, not by an app built with the setting.
- **Not proved:**
  - that nothing resolves the readiness state on the main thread before the launch check. It is
    lazy by construction (the database, the custody and the model store are each asked for by the
    first writer); nothing asserts the thread;
  - `storeChanging`'s re-read after a cancellation of its caller (`NonCancellable`);
  - that `deprovision` retires **before** it relinquishes. The relinquish closes the custody
    first by itself, so no black-box case tells the two orders apart;
  - that an unload ever finishes behind a prediction that never returns. ⚠ This now has a
    consequence a user can see: the towers stay loaded, the handle stays pending, and **every
    later runtime in that process waits at its first embed and loads nothing**. Search by
    description answers "could not look" after its 60 s bound, each time, until Photos is
    relaunched. Accepted over the alternative, a second pair loaded beside a stuck one. No
    prediction is known to stall; nothing here detects one or gives up on it;
  - the put a stalled pass could reach after a relinquish (§14.2). It was read from the code,
    not reproduced: the cases prove the vector is thrown away at the backend, with a blocked
    fake prediction, not that the indexer would otherwise have stored it;
  - two towers in one teardown through the assembled graph: the graph cases load the vision
    tower only (no packed vocabulary is visible to that module's tests). The backend-level case
    closes both;
  - a real `CoreMlSession` behind `coreMlBackendOver`: the cases use a fake port, and the graph
    cases never open a model (a `Ready` phone is refused for consent before a tower is opened);
  - the vocabulary read through the private database at the store's logical path. A fake supplies
    the bytes in the backend's cases. 5b's preflight reads it through that database in the graph
    cases, with a five-piece vocabulary; the real 3.1 MB one is read from the host in the opt-in
    case (§14.9), not through the private database;
  - Calendar's and the six other route-1 apps' graphs: Drive and Mia stand for them, for §13.4's
    reason.
- [I] That closing a `CoreMlSession` releases the model (§8), which leaving `Ready` relies on.

### 14.6 What 5b shipped (2026-10-11)

Marks as above. Nothing here is reachable on a phone: the flag is off, and with it on no model
can be installed before 5c. No schema'd type is reshaped (HR 9).

| Piece | Where |
|---|---|
| The probe script, its records (one per export), the probe image | `scripts/inference/preflight-probe.py`, `preflight-probe-records/<id>.json`, `preflight-probe-image.png`, `test_preflight_probe.py` |
| `PreflightProbe`, `PreflightProbeSet`, `PreflightProbes`, `PreflightProbeLookup`, `PreflightProbeImage` | `inference-service-proxy/src/commonMain/…/PreflightProbe.kt` |
| Each record as constants, and the list of them, generated | `inference-service-proxy/src/commonMain/…/PreflightProbeRecord_<id>.kt`, `PreflightProbeRegistry.kt` |
| `Preflight`, `PreflightJudge`, `PreflightBudget`, `PreflightPolicy`, `PreflightMeasurement`, the three ports | `inference-service-proxy/src/commonMain/…/Preflight.kt` |
| `PreflightAttempts`, `ProbeResult`, `ProbeRecorded`, `ProbeTarget`; `awaitingProbe`, `recordProbe`; the verdict file's new shape | `inference-service-proxy/src/commonMain/…/AiReadiness.kt` |
| `CoreMlPreflightTowers`, `IosPreflightDevice` | `inference-service-proxy/src/iosMain/…/coreml/CoreMlPreflight.kt` |
| `CoreMlInferenceBackend.warmText()` | `inference-service-proxy/src/iosMain/…/coreml/CoreMlInferenceBackend.kt` |
| The binding, `recheck(koin, launch)`, the teardown's wait | `control-shared-proxy/src/iosMain/…/di/PhotosOnDeviceAi.kt`, `tenshu-sdk-ios/…/PhotosLocalRuntime.kt` |

**The probe, and the export it belongs to.** Decided by the maintainer: the probe is computed
for the **existing** kept export (§9.6), with no re-export.

- **The image is a formula:** a red disc (188, 0, 45), radius 77 px, centred on white, 256 × 256.
  Synthetic on purpose: no licence and no person. Not a COCO image.
  - `preflight-probe.py`'s `probe_pixels` and Kotlin's `PreflightProbeImage` draw it with the same
    integer arithmetic, in doubled coordinates so the rim is exact on every platform.
  - The phone feeds its vision tower that array through `ImagePreprocessing`. **No file, no
    decoder, no resampler**: what differs from the host run is the Core ML runtime alone. §9.6's
    phone cosine of 0.985 included ImageIO's pixels; this probe does not.
  - `PreflightProbeImageTest` recomputes the record's two digests (RGB bytes, and the float32
    tensor) in Kotlin, on the JVM and the simulator. They match bit for bit [M].
  - The committed PNG is that image, for a reader to look at. The script refuses any other
    pixels: the phone could not reproduce them.
- **The captions:** *"a red circle on a white background"* describes it; *"a city street at
  night"* does not.
- **The figures** [M: host, coremltools 9.0, macOS 26.6.2, the pinned toolchain of §9.6]:

  | Execution path | cosine, describes | cosine, does not | margin |
  |---|---|---|---|
  | `cpuAndNeuralEngine` (the app's constant) | +0.1404 | −0.0326 | **0.1730** |
  | `cpuOnly` | +0.1397 | −0.0343 | 0.1740 |
  | The simulator's Core ML, through `Preflight` itself (`CoreMlRealArtifactTest`, opt-in) | | | 0.1732 |

  - Raw norms on the host: vision 17.48, text 21.12 and 21.70.
  - For scale: with this image, seven captions that do not describe it (four flags, a dog, a
    bowl of fruit, and the probe's own street) scored between −0.033 and +0.050, so two wrong
    captions can stand 0.083 apart [M: an exploration run on this export, not in the record].
- **The threshold a phone must reach: 0.0865**, half the narrowest host margin.
  - ⚠ **Half is a choice, not a measurement, and a trade.** It leaves half the margin for what
    an iPhone's Neural Engine does differently from this Mac. It also puts the threshold at the
    0.083 spread among wrong captions and no higher.
  - ⚠ **What one pair cannot do.** A tower that returned unrelated directions of the right
    width and norm would rank this pair right by 0.0865 a few times in a hundred [I: cosines of
    random 768-vectors spread by about 0.036, so the margin by about 0.05; computed, not
    measured]. The probe stands beside five other checks (width, finite, non-zero, norm, the token
    rows); it is there for the towers that answer NaN, zeros or another space, not as a proof.
    Raising the share to 0.75 would cut that to under one in a hundred and leave a phone 0.04.
  - ⚠⚠ **The on-phone value is unmeasured.** No phone has run this probe. The nearest evidence is
    three host paths that agree to 0.001. The record says `"phoneMeasured": false`.
  - The script refuses to record a probe whose narrowest margin is under 0.12.
- **Where it ships: as constants compiled into the app**, generated from the records: one
  `PreflightProbeRecord_<id>.kt` per committed record and `PreflightProbeRegistry.kt`, which
  lists them. `<id>` is the first twelve characters of the export manifest's sha256
  (`3fd1918106d4` for the kept export). `test_preflight_probe.py` fails when the Kotlin is not
  exactly the committed set: a record with no file, a file with no record, a record the registry
  does not list.
  - Not a bundled resource: a Kotlin/Native framework carries none, so a file would have to be
    added to each app's `project.yml` and found at run time, and a missing one would be a new
    failure. The image needs no file at all.
  - Not the signed catalog: that is a wire and schema change (HR 9), and the catalog entry for
    this export is already built (§12).
- ⚠⚠ **How it is tied to the artifact.** The record holds the sha256 of both archives and of the
  vocabulary, read from the export's manifest after the manifest was verified against the bytes.
  `PreflightProbes.forKey` hands a probe out only for a `PreflightKey` that names **all three**.
  - Any other export, or these towers with another vocabulary, has **no probe**. The outcome is
    `NoProbe`: nothing is loaded, nothing is counted, the state stays `AwaitingVerdict`.
  - ⚠⚠ **So a build that carries no record for an export never serves it.** A second export needs
    its record in a shipped app build before it is published.
- ⚠⚠ **One record per export a phone may still hold** (round 4).
  - **The fault.** The first cut kept one record in one generated file, and its runbook
    regenerated that file for the next export and bumped `PREFLIGHT_REVISION`. The app update
    would have voided every verdict and, in the same release, removed the only probe that could
    renew them: a phone still on the earlier export would sit at `NoProbe` until ~709 MB had
    come down over Wi-Fi.
  - **Adding an export is additive.** The script writes that export's JSON and Kotlin file
    under its own id, regenerates the registry, and refuses to overwrite a record that exists
    (`--replace`) or to add a second record for the same three artifacts. `PreflightProbeSet`
    refuses two records with one id or one export at class load.
  - **Adding needs no bump of `PREFLIGHT_REVISION`.** The verdict's key holds both archive
    digests and the vocabulary's, so a new export cannot stand on an old verdict; a bump would
    void every phone's verdict for the exports it still holds, for nothing. A bump is owed when
    an **existing** record's content changes, or the judging code does.
    `PreflightRevisionPinTest` pins each record by id: a new id asks for a line and says *no
    bump*; a changed digest under an old id asks for the bump.
  - **Removal: never as part of adding another, and only by the maintainer's decision.** What a
    phone that still holds the export then experiences: a pass it already has keeps standing
    (a `Ready` model is not probed again, and removing a record changes no key); the day that
    verdict is voided (an iOS update, a revision bump) it has no probe, stays `AwaitingVerdict`,
    search by description says *"needs a model on this device"* and its vectors are kept, until
    it downloads an export the build has a record for. The time to remove one is when the
    catalog no longer offers the export **and** a release has gone out in which phones were
    moved to its successor (`models-pipeline.md` §2b).
  - [M] Run again on the kept export after the change: with the committed record in place the
    script refuses (status 2, nothing written); into empty directories it writes a record and
    Kotlin **byte-identical** to the committed ones. The export's listing is unchanged.
  - The script also refuses an export whose `retrieval-gate-result.json` is missing, is about
    other bytes, or is not a clean `PASS`: a probe record is what lets a phone call an artifact
    ready.
- **The kept export was not written.** The script unpacks into the system's temporary directory,
  refuses an output path inside the export, and compares the directory's listing before and
  after [M: names, sizes and modification times identical around the run].
- **Its refusals, run in the pinned toolchain** [M: on an APFS clone of the export; the kept
  directory was not modified]: no gate result → REFUSED; a gate result reading `FAIL` → REFUSED;
  `--out` inside the export → REFUSED; a probe image with one bit changed → REFUSED. Status 2
  each time, and no record written.
- **Toolchain:** the retrieval gate's own pins and venv (`requirements-retrieval-gate.txt`); no
  new requirements file. The image is read and written with the standard library.

**One probe, in order** (`Preflight.probeOnce` and `measure`):
1. `AiReadiness.awaitingProbe()`: only a prepared model with no verdict for the current key,
   on a readiness that is not retired. It reconciles first, under readiness's lock, so a model
   that was `Ready` a moment ago has had its sessions closed.
2. The probe for the key's three digests, or `NoProbe`.
3. The stops: a failed install is not loaded again; a foreground that has had its slow probe
   has no second; the backoff.
4. `beforeLoad`, which in Photos' graph is `PhotosOnDeviceAi.awaitPendingUnload()`.
5. The vocabulary is read and parsed, and both captions tokenised, each timed. **The rows must
   be the record's**, which the checkpoint's own tokenizer made: that is the check of the
   downloaded vocabulary and of `SiglipTextTokenizer` on the phone. A mismatch fails before a
   tower is opened.
6. Both towers are opened through `CoreMlPreflightTowers`: `CoreMlSession.open` with the shipped
   compute units and the session's own shape guard, never through the backend.
7. Eight predictions a tower, each timed: the probe tensor on the vision tower, the two rows
   alternately on the text tower.
8. `phys_footprint` and what the process may still allocate, read **with both sessions open**;
   `thermalState` at the start and the end; whether a memory warning arrived in between.
9. Both towers are closed. Then `PreflightJudge` decides, and the result is recorded.

**What a probe amounts to** (`PreflightJudge`, in this order):

| Finding | When | Counted as | State after |
|---|---|---|---|
| `Failed` | the vocabulary or a tower would not load; other token rows; a prediction threw; any vector of the batch fails `TowerGuards` (width, finite, non-zero) or has a norm outside ½×–2× the export's; the pair is ranked by less than the threshold, on the worst pairing of the batch | `failed` | `AwaitingVerdict` |
| `Slow` | the median prediction of either tower is above 500 ms; or less than the memory line is left with both towers open | `slow` | `AwaitingVerdict`, and `Unsuitable` at the third |
| `Passed` | none of the above | | `Ready` |
| `Transient` | **any finding but a pass**, when the probe started at `thermalState` ≥ serious or a memory warning arrived while it ran | `transient` | `AwaitingVerdict`, always |

- The output is judged before the speed: a tower that answers NaN quickly is not slow, and a
  slow one that answers NaN is not an unsuitable phone.
- ⚠ A hot or pressured phone **may pass, and may not fail**. The probe is run on it rather than
  skipped: after a ~709 MB download and a compile the phone may well be warm, and a pass there
  is still a pass.

**The retry rules** (`PreflightPolicy`, `PreflightAttempts`), D12's, as built:
- **`Unsuitable`: three `slow` probes, each on its own foreground.** `Preflight` counts a
  foreground per `foregrounded()` call and runs no second probe in one that has had a counted
  slow one. The third is counted and made the verdict **in one call under readiness's lock**
  (`AiReadiness.recordProbe`), in one write.
- **A failed install: three `failed` probes.** Never `Unsuitable`. The state stays
  `AwaitingVerdict`, the outcome is `InstallFailed`, and nothing is loaded again for that key,
  in this process or the next. `Preflight.outcomeFor(key)` is what 5d's Models screen reads to say
  so and offer the download again.
  - ⚠ Writer 3 clears the `failed` count: a commit or a removal that went through is a new
    install. Without it a re-download of the same archive, which has the same key, would still
    read as failed. The `slow` count stays; it is the phone's.
- **`transient` decides nothing**, however many there are.
- **The backoff:** after a probe with no verdict, the next is not before one minute, doubling
  per miss of any kind, one hour at most. A retry still happens only on a trigger (a launch, a
  foreground, the prepare step): there is no timer.
- **The counts live in the verdict file, under the verdict's key.** `PreflightVerdict` is now
  `{key, passed, attempts}`, with `passed` null until there is an answer. No field has a
  default, so 5a's `{key, passed}` does not decode and is no verdict. A new archive, vocabulary,
  OS build, app build or `PREFLIGHT_REVISION` is another key: its counts start at zero.
- **A result about a key that moved is dropped**, a pass and a miss alike, and a miss is never
  written over an answer. A miss that cannot be written is not counted, and does not spend the
  foreground's slow probe.

**What is reported is what readiness did** (round 1).
- ⚠⚠ **The fault.** The first cut counted the third slow probe in one readiness call and
  recorded the verdict in a second, and concluded `Unsuitable` from the count. A replacement
  committed between the two had its verdict dropped, rightly, and was still reported unsuitable.
  A pass had the same hole from the other side: a pass for a model replaced under the probe was
  reported `Passed` when the replacement happened to be `Ready` on a verdict of its own. Both
  were reproduced as failing cases on that head before the fix.
- **One call.** `AiReadiness.recordProbe(result, probed, at)` reads, checks the key, counts and,
  at the third slow probe, applies the verdict, all under readiness's lock, and returns what it
  did: `Dropped`, `Counted(attempts)` or `Decided(passed, state, attempts)`. A store change takes
  the same lock (writer 3), so there is no moment between the count and the answer.
- **Every outcome the preflight reports is one readiness handed it:**

  | Outcome | From |
  |---|---|
  | `NotAwaiting`, `NoProbe`, `Waiting`, `InstallFailed` before a probe | `awaitingProbe()`: the key, the model and the counts, read together under the lock |
  | `Passed` | `Decided(passed = true)`, which readiness returns only when the state it reached is `Ready`. A pass whose fingerprint could not be applied or persisted is `Dropped` |
  | `Unsuitable` | `Decided(passed = false)` |
  | `Retry`, and `InstallFailed` after a probe | `Counted`, from the counts it returned |
  | `NotRecorded` | `Dropped`: the key moved, the model went, the runtime was retired, there was an answer already, or the write failed. ⚠ It says nothing about the model installed now |
  | `Abandoned` | the probe saw `isRetired()` between two of its steps |
  | `Busy` | another probe holds the preflight. Returned, never stored |

- ⚠⚠ **The last report is keyed.** `Preflight.lastReport` is `{key, outcome}`, and
  `outcomeFor(key)` answers only for that key. After a replacement it cannot describe the
  previous model: asked about the new key it says nothing, and the next call replaces it. A
  dropped result, a retirement and *nothing to probe* are stored with no key, so a removed
  model's failed install stops being reported at the next call.
- The slow-probe-per-foreground mark is set only on `Counted` or `Decided`.

**A write that fails: memory never gets ahead of the disk** (round 2).
- ⚠⚠ **The fault.** At the third slow probe a failed write (a full disk, an I/O error) was
  caught and the state left `Unsuitable` in memory, and the caller was told `Decided`. The
  phone was reported unsuitable on a verdict the next launch would not find, search was off for
  the rest of the process, and a count with the same failed write was, rightly, dropped.
- ⚠⚠ **Two rules, in opposite directions.**
  1. **An answer or a count is written before it is believed.** State that must outlive the
     process is published in memory only after its write succeeded. A failed write leaves the
     state and the counts as they were and returns `Dropped`.
  2. **Serving stops before anything is written** (5a's rule, unchanged). Leaving `Ready`
     flips first and stays flipped whatever the write then does.
- **Every transition against them:**

  | Transition | Written? | Order | If the write fails |
  |---|---|---|---|
  | Pass → `Ready` (`recordProbe`, bare `recordVerdict`) | the verdict | apply the fingerprint, **write, then flip** (5a's order, confirmed through `recordProbe`) | `AwaitingVerdict`, not served; `Dropped`, and the preflight reports `NotRecorded`. A fingerprint already applied stays applied: vectors of another archive are cleared either way |
  | Third slow probe → `Unsuitable` | the count and the verdict, one write | **write, then publish** | `AwaitingVerdict`; the file still holds two; `Dropped`; the foreground's slow probe is not spent. The next slow probe is the third again |
  | A slow, failed or transient count | the counts | write; nothing in memory holds a count (they are read from the file each time) | the file as it was; `Dropped` |
  | Bare failing verdict from `AwaitingVerdict` | the verdict | write, then publish | `AwaitingVerdict` |
  | Bare failing verdict from `Ready` | the verdict | **rule 2 first:** flip to not served, write, publish `Unsuitable` if written, then cancel and close | not served, pass cancelled, sessions closed; the state is `AwaitingVerdict`. ⚠ The disk still holds the pass, so the next reconcile enters `Ready` again: the failing verdict did not happen. No production caller reaches this (the preflight only probes `AwaitingVerdict`) |
  | Writer 3's reset of the failed count | the counts | write only | the count as it was: the install still reads as failed until the next commit resets it |
  | The key changed (void) | nothing | derived at the next read; the old file is overwritten by the next result | — |
  | `retire` | nothing | a flag in memory, by design: terminal for this graph, and the disk is the next graph's | — |
  | Leaving `Ready` on a reconcile, an unreadable store, closed stores | nothing | rule 2: flip, cancel, close | — |

- **The write went through and a later step throws.** After a verdict's write there is one
  step that can throw, on one path: cancelling the pass when a failing verdict leaves `Ready`.
  `Unsuitable` is published before that step, so memory and disk agree, the sessions are closed
  in a `finally`, and the exception reaches the caller. After a pass's write and after a
  count's write nothing follows but an assignment. In `Preflight`, nothing is persisted after
  `recordProbe` returns.

**A read that fails: nothing is written from a record assumed empty** (round 3).
The read side of the same contract.
- ⚠⚠ **The fault.** `readVerdict` caught a failed read and answered `null`, the same answer as
  *no file*. A persisted pass then read as `AwaitingVerdict`, the preflight probed, and a slow
  or failed result was written, with counts starting at zero, over the pass.
- ⚠⚠ **Three results, and a fourth that is a decision.**

  | The verdict file | Is | State derivation | A probe | A later write |
  |---|---|---|---|---|
  | not there | `None` | `AwaitingVerdict` (if prepared) | runs | writes the first record |
  | there, read, decodes | `Held` | what it says, if its key is current | per its counts | updates it |
  | there, read, **does not decode** (empty, not JSON, another key shape, 5a's `{key, passed}`) | `None`, **by decision** | `AwaitingVerdict` | runs | **overwrites it**: bytes that were read and are not this build's record can never become a verdict, so there is nothing in them to protect |
  | **unreadable** (the read threw; or `null` for a file a stat says is there) | `Unreadable` | 5a's rule: not served. `Ready` in memory goes to `AwaitingVerdict`; nothing on disk is touched | **not run** (`Awaiting.Unreadable` → `PreflightOutcome.Unreadable`); a result already in hand is `Dropped` | **none**, from any writer |

- ⚠⚠ **`null` is not yet *absent*.** `IosFileDatabase.readBytes` answers `null` for a file it
  could not read as for one that is not there (`NSData.dataWithContentsOfFile` is `nil` either
  way) [C]. So after a `null` the verdict's reader asks `fileSize`: a file that is there with
  no bytes to give is unreadable.
- **One rule for every caller:** `AiReadiness.read()` returns `null` when the facts, the key or
  the verdict could not be read, and every caller stops there. `fingerprintChecked`,
  `markerWritten`, `storeChanging`'s reconcile and a bare `recordVerdict` fail closed and write
  nothing; `recordProbe` answers `Dropped`; `awaitingProbe` answers `Unreadable`, so the
  preflight loads no tower for a result it could not record, and 5d can tell *could not read*
  from *nothing to do*. Writer 3's reset writes only over a record it read.
- **Every file readiness trusts:**

  | File | Reader | Absent | Unreadable | Undecodable | Could a later write clobber good data on a failed read? |
  |---|---|---|---|---|---|
  | Verdict (`inference/preflight.verdict`) | `AiReadiness.readVerdict` | no verdict | unreadable: not served, no probe, no write | no verdict, by decision | **No**, since this round |
  | Install record / index | `ModelStore.installed()` over `FileModelIndexStorage`, for the facts and for the key | `NoModel` | the store throws, for a read that throws and for a file that is there and gave no bytes (§13.7): unreadable, as above | JSON that is not this build's record: the store throws, unreadable. Bytes that are not JSON: an empty index, by the store's decision (§13.7) | **No.** Readiness never writes the index, and the store refuses a commit or a removal on an index it could not read (§13.7) |
  | Ready markers | `CoreMlModelCache.compiledPath`, through `coreMlModelFacts` | `Preparing` | a read that throws now propagates: unreadable (5a caught it with the digest check and answered `Preparing`). `null` on iOS: `Preparing` | a marker for another digest: `Preparing` | Not by readiness. ⚠ By 5c's prepare step: with no marker it removes the digest's directory and compiles again. Regenerable from the archive, at seconds and ~740 MB of writes |
  | Vocabulary (stat) | `files.fileSize`, in `coreMlModelFacts` | `Preparing` | a stat that throws now propagates: unreadable (5a answered `Preparing`). `null` on iOS: `Preparing` | size differs: `Preparing` | No write follows |
  | Vocabulary (bytes) | the preflight, the backend | a failed probe (`the vocabulary did not load`); `embedText` answers `null` | the same as absent: it would not load | the same | No. The count it adds is written from a record that was read |
  | Runtime fingerprint sidecar | `InferenceStoreCustody.readFingerprint` (PR-3), on the way into `Ready` | clears the vectors, then writes the sidecar | **read as absent** [C: one `runCatching`]: clears the vectors and writes | read as absent | ⚠ **Yes, the vectors**, by PR-3's own rule (*a missing sidecar clears*): the safe direction for a store that must not mix vectors, and re-indexable. Not changed here |

- ⚠ **What the iOS database cannot say.** For every file but the verdict and the model index,
  a read that fails without throwing is indistinguishable from an absent file at
  `FileDatabase`'s interface. The rows above say what each then does; each is on the
  not-served side.

**Thresholds, and which of them are placeholders.**

| Constant | Value | Source |
|---|---|---|
| `PreflightBudget.MAX_MEDIAN_PREDICT_MS` | 500 ms | §4.5's row, as D12 directs. ⚠ That row is decode **and** predict on a photograph; a probe predicts on a tensor, so this is the row at its most lenient. The measured phone predicts in 13 ms (§9.7). No slower phone was measured to say where the line belongs |
| `PreflightBudget.PREDICTIONS_PER_TOWER` | 8 | A choice: D12's "short fixed batch". The median is judged |
| ⚠⚠ `PreflightBudget.PLACEHOLDER_MIN_AVAILABLE_BYTES` | 256 MiB | **Placeholder.** D12's "measured headroom" does not exist (D10 is open). The value is the largest pass anyone has seen, 190 MB at the peak in the harness (§9.7), rounded up. **Replaced by:** the footprint of a pass through the backend with both towers open, plus a stated margin, on a phone at the intended floor |
| `PreflightProbe.minMargin` | 0.0865 | Half the narrowest host margin. ⚠ The half is a choice; the phone's value is unmeasured |
| `PreflightProbe.normBand` | 2 × | A choice |
| `PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE` | 3 | D12 |
| `PreflightPolicy.FAILED_PROBES_FOR_FAILED_INSTALL` | 3 | ⚠ A choice. D12 says "its bound" and gives no number |
| `PreflightPolicy.RETRY_BASE_MS`, `RETRY_CAP_MS` | 1 min, 1 h | ⚠ Choices. D12 says "a bounded backoff" |
| `AiStaticGates.PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES` | 6.5 GiB | Unchanged from 5a; still a placeholder (§14.2) |

- ⚠⚠ **The memory line is read through `os_proc_available_memory()`, looked up by name.**
  Kotlin/Native's platform libraries do not carry `<os/proc.h>`, so `IosPreflightDevice` finds
  the symbol with `dlsym` and calls it. A missing symbol and the 0 it returns where there is no
  limit both read as *unknown*, and the judge does not hold an unknown against the phone.
  - On the simulator it reads unknown [M]. **On a phone it has never been read** [I: that the
    symbol resolves and returns a figure there].
  - So until a device run shows a figure, the memory line may be inert on a phone. The
    footprint is logged either way.

**Who holds a tower when.** The preflight's sessions are **closed, not handed to the backend.**
- By memory: the backend holds nothing while the state is not `Ready` (leaving `Ready` closes
  its sessions, and `sessionFor` closes on `!ready`). So during a probe exactly one pair is
  loaded, the preflight's. It is closed **before** `recordVerdict`, and a pass is what lets the
  backend open its own. There is never a second pair.
- A hand-over would save one warm reopen, 24–88 ms (§2.2), and would add a second way into the
  backend's per-tower state, which is keyed by path under the tower's own lock.
- Three waits keep it to one pair:
  1. the preflight awaits `PhotosOnDeviceAi.awaitPendingUnload()` before it opens anything, as
     the backend does;
  2. the backend's `beforeLoad` also awaits `Preflight.awaitSessionsClosed()`: its own graph's
     probe may still hold a pair if the model became `Ready` by another route;
  3. a teardown's pending unload awaits the retired graph's probe too. The probe reads
     `AiReadiness.isRetired()` between its steps, records nothing and closes; until then the
     next runtime loads nothing.
- Nothing blocks the main thread: a probe runs on `Dispatchers.Default`, from the launch
  check's own coroutine; `retire` still waits for nothing.

**The warm-up** (`CoreMlInferenceBackend.warmText`): `beforeLoad`, then under the text tower's
lock the `ready` check, the vocabulary, the session. It predicts nothing. `PhotosOnDeviceAi.recheck`
calls it at launch and after a pass, when the model is `Ready` **and both consent switches for
search by description are on**.

**Consent** (two switches, absence is NO: `inference-service.md` IN-1, IN-D3).
- **How it is read** [C]. `ExecutionContext.hasInferenceConsent(capability, deviceId)`
  (`ProxyInferenceConsent.kt`) reads two preference rows by id straight off the proxy's database:
  the `RealmSetting` for the capability and the `DeviceSetting` keyed `deviceId/preferenceId`.
  From the `ExecutionContext` it uses `context` and `database` and nothing of a running task.
- **It can be read outside an operation.** An `ExecutionContext` is not a Koin single; it is
  minted per client by `ClientRegistry.getExecutionContext(clientId)`, which is how
  `ProxyModule`'s task executor gets one for every operation and how `ControlSurface` gets one
  outside any. Photos' in-process runtime has both: its registry and its `platformPackageName`.
  The device id is `SyncManager.currentDeviceId`, the value `ProxyModule` hands `MediaIndexer`
  and `photosLike`.
- **So the warm-up asks** (`PhotosOnDeviceAi.searchConsented`): the same function, the same
  capability (`SEMANTIC_IMAGE_SEARCH`), the same device id as `photosLike`. No row, one switch,
  a switch written off, a locked or unprovisioned realm, a registry or device id that will not
  resolve, a read that throws: each is NO, and no tower is opened. The first consented query
  then opens the tower itself.
- ⚠⚠ **The preflight does not ask for consent, and the plan does not require it to.** D12 puts
  the preflight "after the download and before the first pass" and names no consent; §3.2's
  *Paid at prepare time* likewise loads both models once at the end of *Preparing*. As built,
  a probe loads both towers for a model the user downloaded from the Models screen, embeds a
  drawn image and two fixed captions, touches no photograph and no query, and closes both
  towers before it reports. What consent gates is unchanged: `photosLike`, the indexer and now
  the warm-up. ⭐ **Decided by the maintainer 2026-10-11: the probe stays ungated** (§14.7
  item 14). The consent rule's scope is *no photograph and no query is processed without both
  switches*; the probe processes neither. The alternative was one check at the top of
  `Preflight`'s trigger in `recheck`.

**When a probe starts.** `PhotosOnDeviceAi.recheck(koin, launch)`, at install and on every
return to the foreground: writer 4, then `Preflight.foregrounded()`, then the warm-up.
`Preflight.prepared()` is the trigger after writer 1; its caller is `ModelPreparer` (5c-2, §14.17).

**Threat model (HR 2).** The verdict file gains three small counts and the time of the last
probe, in the container it already lived in (§14.2). Nothing moves and nobody new can read
anything. No amendment.

### 14.7 Decisions taken in this slice, each with its alternative

| # | Decision | Alternative |
|---|---|---|
| 1 | The probe image is drawn by a formula on both sides; no file reaches the phone | A bundled PNG decoded by `IosImageDecoder`. It would add ImageIO's pixels to the difference from the host (§9.6: min 0.985) and a resource to ten `project.yml`s |
| 2 | A red disc and two English captions | A flag: "the flag of Japan" scored +0.124 on the same image, four other flags 0.13–0.14 against their own captions, margins of 0.17–0.20. No wider in kind, and a flag adds meaning the probe does not need |
| 3 | The threshold is half the host margin | A larger share (0.75 → 0.13), which a wrong tower passes less often and a differing phone fails more often; or a measured phone margin with its own headroom, not available before a device run |
| 4 | The records are constants in the app, one per export, looked up by three digests; no record means `NoProbe` | The record in the signed catalog entry, so a new export brings its own probe. A wire and schema change |
| 5 | A hot or pressured phone is probed, and only a pass counts | Not probing at `thermalState` ≥ serious at all, as D12's wording can also be read. Simpler; a warm phone then waits for its verdict |
| 6 | Three failed probes are a failed install | Any other bound; D12 names none |
| 7 | One minute, doubling, one hour | Any other bounded backoff |
| 8 | The backoff applies after a slow probe too | Only "separate foregrounds", as D12 says. Three app switches in ten seconds would then be a verdict |
| 9 | The preflight's sessions are closed | Handing them to the backend (above) |
| 10 | A failed install adds no readiness state: `AwaitingVerdict`, with `Preflight.outcomeFor(key)` saying why | A sixth `AiReadinessState`. It would touch 5a's table of what search shows, for a state search words the same |
| 11 | Writer 3 clears the `failed` count | The Models screen clearing it explicitly on a re-download (5d) |
| 12 | The memory line is headroom (`os_proc_available_memory`), by `dlsym` | A cinterop definition for `<os/proc.h>`; or a line on the absolute `phys_footprint`, which says nothing without the phone's limit |
| 14 | ⭐ **Decided by the maintainer 2026-10-11: the preflight stays ungated by the two consent switches**; the warm-up, `photosLike`, the indexer and everything after them are gated (§14.6, *Consent*). The probe processes no photograph and no query, runs once per verdict on a model the user downloaded (a failed probe is retried, items 6–8), and unloads. So the consent rule's scope is *no photograph or query is processed without both switches* | Gating the probe on consent too. Rejected: a user could then switch the feature on and only afterwards learn the phone is unsuitable, after a ~709 MB download |
| 13 | `PreflightProbe` and `PreflightProbeLookup` are public, and the graph takes a lookup from Koin when a test binds one | Keeping them internal. The graph cases could then never reach `Ready` through the preflight: their models have invented digests |

### 14.8 Deviations from §3–§7, and why

1. **The export script does not record the probe**, which D12 and §14.1 said it must. A separate
   script does, for a finished export: the maintainer decided the kept, gated export is not
   re-exported, and a re-export is a new artifact (§8). D12 and §9.1 are edited in place.
2. **A probe that starts hot is run**, where D12 says such a probe "is retryable". Its failure is
   not counted; its pass is (§14.7 item 5).
3. **The warm-up asks for the two consent switches, as §3.2 requires: no deviation.**
   ⚠ The first cut warmed without asking, on the claim that consent is readable only inside
   an operation. That was wrong: the read needs an `ExecutionContext`'s database, and
   the client registry mints one outside an operation too (§14.6, *Consent*). Without consent
   nothing is loaded for search, and the first consented query pays the open itself.
4. **The warm-up also runs after a pass**, not only at launch: the user is then one tap from
   search.
5. **Wrong output and a load error share one count.** D12 says a wrong output is "treated like a
   load error"; here they are literally the same bucket.
6. **`thermalState` is read at the start and the end only**, and the start decides. D12 asks for
   no more.
7. **Every vector of the batch is validated and the pair is ranked on its worst pairing**, where
   D12 describes one image and two captions. A tower that answers once and then drifts is caught.
8. **5a's verdict file does not decode.** Nothing shipped, so there is no migration; the
   preflight simply runs again.

### 14.9 Guards, each removed once and seen red

As §14.3: one mutation at a time, the affected classes run, the red cases read from the result
XML, the file restored and its sha256 compared. [M: 90 on the JVM (`:inference-service-proxy:jvmTest`,
the `Preflight*` and `AiReadiness*` classes; all re-run on the tree after 5b's round 3, the probe-set rows after round 4), 19 on the simulator, 9 on the probe script (its own test module).] Every row went red; every file was
restored. *pin* means `PreflightRevisionPinTest` was red as well, with its instruction to bump.

| Guard | Removed by | Red |
|---|---|---|
| ⭐ Both towers are closed after a probe, before the verdict is on disk | not closing the vision tower; not closing the text tower (two runs) | `aPassMakesTheModelReadyAndBothTowersAreClosedBeforeItIsServed` and at least sixteen more |
| ⭐ A probe is for all three digests | dropping the vocabulary's; the vision archive's; the text archive's (three runs) | `anExportThisBuildHasNoProbeForIsNeverCheckedAgainstAnotherExports`, `theProbeThisBuildCarriesIsOnlyForItsOwnThreeDigests` |
| ⭐ A probe is looked up by the key among every record (round 4) | the first record whatever the key; the newest record only (two runs) | `withTwoExportsRecordedEachFindsItsOwnProbeAndNeitherTheOthers`, `aPhoneStillHoldingTheEarlierExportIsProbedAgainstItsOwnRecordAfterASecondIsAdded` |
| Two records with one id, or for one export, are refused | no check (two runs) | `twoRecordsThatCouldBeTakenForEachOtherAreRefused` |
| ⭐ An existing record that changes owes a bump | another margin in the generated file | `noRecordThisBuildCarriesHasChangedUnderThisRevision`, with the instruction to bump; and the script's `test_the_committed_kotlin_is_the_committed_records` |
| The Kotlin is exactly the committed records (script) | ignoring a Kotlin record with no JSON; a missing file; a registry short of a record; not comparing content (four runs) | `test_a_kotlin_record_with_no_committed_record_fails`, `test_a_record_with_no_kotlin_and_a_record_not_in_the_registry_fail`, `test_a_record_registered_and_then_deleted_fails`, `test_adding_an_export_changes_no_other_exports_file` |
| An export's record is not overwritten unasked; one record per export; filed under its own id (script) | no check (four runs) | `test_an_exports_own_record_is_not_overwritten_unasked`, `test_a_second_record_for_the_same_three_artifacts_is_refused`, `test_a_record_filed_under_another_id_is_refused` |
| Regenerating removes a file no record stands behind (script) | leaving it | `test_a_record_registered_and_then_deleted_fails`. ⚠ Survived first: the case checked the complaint, not the regeneration |
| ⭐ No probe, nothing probed | falling back to another export's probe | the first of those |
| The token rows are the record's | never comparing | `aVocabularyThatTokenisesTheCaptionsDifferentlyFailsBeforeATowerIsOpened` |
| ⭐ Every vector is guarded (width, finite, non-zero) | no `TowerGuards` call | `aVisionTowerThatAnswersNaNIsNeverReady`, `theOutputIsJudgedBeforeTheSpeed`, one more; *pin* |
| A tower with no vector fails | accepting none | `aTowerWithNoVectorOrAnUnusableOneFails` |
| The norm band | the low side; the high side (two runs) | `theNormBandIsTheProbesOwnOnEveryTower`, and for the low side `aCollapsedOrAnExplodedNormIsNeverReady`; *pin* |
| ⭐ The pair must be ranked by the margin | never comparing | `aMisRankedProbePairIsNeverReady`, `theMarginLineIsTheProbesOwn`, two more; *pin* |
| …on the worst pairing of the batch | the first image vector only; the first describing vector only (two runs) | `theMarginIsTheWorstPairingOfTheBatch` |
| A load or prediction failure is judged first | not reading it | `theOutputIsJudgedBeforeTheSpeed`, one more; *pin* |
| A prediction that throws is a failed probe | no catch | `aPredictionThatThrowsIsAFailedProbeAndBothTowersAreClosed` |
| The latency line, vision; text | no check (two runs) | `aSlowVisionTowerIsASlowProbe` with `theLatencyLineIsTheMedianAndFiveHundredMillisecondsPasses`; `aSlowTextTowerIsASlowProbe` with the same |
| It is 500 ms | 600 | `theLatencyLineIsTheMedianAndFiveHundredMillisecondsPasses`, both slow-tower cases; *pin* |
| It is the median | the fastest reading | `theLatencyLineIsTheMedianAndFiveHundredMillisecondsPasses` |
| A tower that was never timed is not fast | 0 for no readings | `aTowerThatWasNeverTimedIsNotFast` |
| The memory line | no check | `tooLittleLeftWithBothTowersOpenIsASlowProbe`, `theMemoryLineIsThePlaceholder`; *pin* |
| The footprint is read with both towers open | the reading from before | `aProbeIsTheWholeSearchPath` |
| ⭐ A hot start excuses a miss | no rule | `aSlowProbeOnAHotPhoneDecidesNothingHoweverOftenItHappens`, `aLoadErrorOnAHotPhoneIsNotCountedAgainstTheInstall`, one more; *pin* |
| Serious is the line; fair is judged | critical only; fair too (two runs) | the first of those and the judge's case; `aFairPhoneIsJudged`. ⚠ The first survived by name at first: the cases spelled the line with the constant under test, and only the pin was red. They now say 2 and 1 |
| ⭐ A memory warning excuses a miss | no rule | `aSlowProbeUnderAMemoryWarningDecidesNothing`, the judge's case; *pin* |
| Only a warning during this probe | any warning ever | `aWarningBeforeTheProbeStartedIsNotThisProbes` |
| A hot or warned phone that passes is ready | excusing a pass too | `aHotOrWarnedPhoneThatPassesIsReady`, the judge's case; *pin* |
| ⭐ A failed probe is not a slow one | counted as slow | `aMisRankedProbePairIsNeverReady` and fourteen more |
| ⭐ A transient probe is not a slow one | counted as slow | `aSlowProbeOnAHotPhoneDecidesNothingHoweverOftenItHappens`, `aSlowProbeUnderAMemoryWarningDecidesNothing` |
| ⭐ `Unsuitable` takes three | two | `threeSlowProbesOnThreeForegroundsMakeThePhoneUnsuitable` and four more; *pin* |
| ⭐ The third slow probe is the verdict | never deciding | `threeSlowProbesOnThreeForegroundsMakeThePhoneUnsuitable`, `theThirdSlowProbeIsCountedAndDecidedInOneWrite`, two more |
| ⭐ …counted and decided in **one** write | a write of the count before the verdict's | `theThirdSlowProbeIsCountedAndDecidedInOneWrite` |
| ⭐ A dropped third slow probe is not reported `Unsuitable` | concluding it from the count read earlier | `unsuitableIsReportedOnlyWhenReadinessAppliedIt` and three more |
| ⭐ A dropped pass is not reported `Passed` | reporting the finding | `aPassForAnOldKeyIsNotReportedAsThePassOfAReplacementThatIsReady`, `aPassForAKeyThatMovedUnderTheProbeIsDroppedAndNotReported`, three more |
| ⭐ `Unsuitable` is believed only once written (round 2) | published whatever the write did | `aThirdSlowProbeThatCannotBeWrittenIsNotUnsuitable`, `aFailingVerdictThatCannotBePersistedIsNotBelieved`, one more |
| ⭐ An unwritten third slow probe is `Dropped` | `Decided` whatever state was reached | `aThirdSlowProbeThatCannotBeWrittenIsNotUnsuitable` |
| ⭐ A pass is `Ready` only once written | flipping after a failed write | `aPassThatCannotBeWrittenIsNotReadyAndTheNextOneIs`, `aVerdictThatCannotBePersistedIsNotReady` |
| ⭐ A failing verdict stops serving **before** its write | no flip ahead of the write | `aFailingVerdictThatCannotBePersistedStillStopsServing`, `aFailingVerdictLeavesReadyInOrder` |
| A written verdict is believed before the stop that may throw | publishing after the cancel | `aVerdictThatWasWrittenIsBelievedEvenWhenStoppingThenThrows` |
| The pass is cancelled and the sessions closed on that path | no stop | the same, the two above and `aCancelThatThrowsStillClosesTheSessions` |
| ⭐ A verdict read that throws is unreadable, not empty (round 3) | answering *no verdict* | `aPersistedPassSurvivesAReadThatFailsAndIsReadyAgainAfterwards`, `theCountsSurviveAReadThatFailsAndNoProbeIsRunMeanwhile`, `aReadThatFailsOnlyWhenTheResultIsRecordedDropsTheResult` |
| ⭐ `null` for a file that is there is unreadable | answering *no verdict* | the same three |
| ⭐ An unreadable verdict makes the whole reading unreadable | a reading with no verdict | the same three |
| ⭐ Unreadable is not *nothing to probe*; it is not probed and is reported so | `NothingToProbe` from readiness; `NotAwaiting` from the preflight (two runs) | the first two of those |
| What cannot be read is not served, when the preflight meets it first | no fail-closed there | `aReadThatFailsFirstUnderThePreflightStopsServingAsWell`. ⚠ Survived first: every case had a readiness writer meet the failure before the preflight did |
| Bytes that do not decode are no verdict, not unreadable | answering *unreadable* | `aFileThatWasReadAndDoesNotDecodeIsNoVerdictAndIsWrittenOver`, `aVerdictWrittenUnderAnotherKeyShapeIsNoVerdict`, one more |
| A vocabulary stat that throws is not a missing vocabulary (simulator) | caught, as 5a had it | `aVocabularyStatOrAMarkerReadThatThrowsIsNotAModelThatIsNotPrepared` |
| A marker read that throws is not *not prepared* (simulator) | every throwable caught with the digest check | the same |
| A pass readiness could not apply is not a pass | `Decided` whatever state was reached | `aPassThatReadinessCouldNotApplyIsNotReportedAsReady` |
| A dropped result is reported about no model | keyed to the probed model | the same and two more |
| ⭐ The last report is keyed | answering for any key | `aReportIsAboutTheModelThatWasProbedAndNoOther` and six more |
| Nothing to probe replaces the last report | keeping the old one | `withNothingLeftToProbeTheLastReportIsAboutNoModel` |
| An abandoned probe is reported about no model | keyed | `aRuntimeRetiredUnderTheProbeClosesItsTowersAndRecordsNothing` |
| A slow probe is counted as slow | as transient | `aSecondSlowProbeInOneForegroundIsNotRun` and twelve more |
| ⭐ One slow probe a foreground | no check | `aSecondSlowProbeInOneForegroundIsNotRun` |
| A foreground is counted | never counting | the same and four more |
| A counted slow probe spends the foreground; a dropped one does not | never marking it; marking on a drop (two runs) | `aSecondSlowProbeInOneForegroundIsNotRun`; `aMissForAKeyThatMovedUnderTheProbeIsNotCounted` |
| A failed install takes three | two | `threeFailedProbesAreAFailedInstallAndNeverAnUnsuitablePhone` and two more; *pin* |
| …is reported at the third | never reported | the same, `installingAgainStartsTheFailedCountAgainAndKeepsTheSlowOne`, `aReportIsAboutTheModelThatWasProbedAndNoOther` |
| ⭐ …and is not loaded again | no check | `threeFailedProbesAreAFailedInstallAndNeverAnUnsuitablePhone` |
| The backoff | no check | `aProbeThatDidNotPassIsNotRunAgainBeforeItsBackoff` |
| Every kind of miss counts towards it | not the failed ones | the same, `theBackoffDoublesAndIsBounded` |
| It is capped | no cap | `theBackoffDoublesAndIsBounded`; *pin* |
| The doublings are capped before the shift | shifting by the count | `theBackoffDoublesAndIsBounded`. ⚠ Survived first (below) |
| A clock that went back does not wait | no clause | `aClockThatWentBackwardsDoesNotWaitForItsOldFuture` |
| ⭐ `beforeLoad` is awaited before a tower is opened | not awaiting | `noTowerIsOpenedBeforeTheLoadIsAllowed`, `aRuntimeRetiredBeforeTheFirstTowerOpensNone` |
| ⭐ A retirement is seen before the first tower; between predictions | no check (two runs) | `aRuntimeRetiredBeforeTheFirstTowerOpensNone`; `aRuntimeRetiredUnderTheProbeClosesItsTowersAndRecordsNothing` |
| ⭐ Whoever waits for the towers waits while they are open | never marking them held | `whoeverWaitsForTheTowersWaitsUntilBothAreClosed`. ⚠ Survived first (below) |
| ⭐ The probe target is handed out only after a reconcile | reading the target without it | `aModelThatWasReadyHasItsSessionsClosedBeforeTheProbeOpensItsOwn` and twenty-two more |
| A retired readiness hands out no target; takes no result | no check (two runs) | `nothingIsProbedUnlessAPreparedModelAwaitsItsVerdict`; the same and `aRuntimeRetiredAfterTheLastPredictionRecordsAndReportsNothing` |
| ⭐ A result for a key that moved is dropped | taking it | `aStoreChangeBetweenTheLastPredictionAndTheRecordIsSeenForEveryKindOfResult`, `aMissForAKeyThatMovedUnderTheProbeIsNotCounted`, two more |
| A result is never written over an answer | writing it | `aMissIsNeverWrittenOverAnAnswer` |
| A miss that cannot be written is not counted | counting it | `aMissThatCannotBeWrittenIsNotCounted` |
| ⭐ The counts are keyed like the verdict | the file's counts whatever its key, when handing out the target; when counting (two runs) | `aNewKeyStartsTheCountsAtZero` |
| Counts with no answer are no verdict | read as a fail | `aSlowVisionTowerIsASlowProbe` and twenty-eight more |
| The counts stay beside the verdict | zeroed when the probe's verdict is written; when a bare verdict is (two runs) | `theCountsStayBesideTheVerdictTheyLedTo`; `aBareVerdictKeepsTheCountsToo`. ⚠ The second survived first: no case wrote a bare verdict over counts |
| Writer 3 forgets the failed probes | not forgetting | `installingAgainStartsTheFailedCountAgainAndKeepsTheSlowOne` |
| …and keeps the slow ones | forgetting everything | the same |
| A bump revisits the pin | `PREFLIGHT_REVISION = 3` alone | `PreflightRevisionPinTest` (second assertion) |
| ⭐ The launch check starts the preflight | never calling it | `aPreparedModelIsProbedAtInstallBecomesReadyAndItsTextTowerIsWarmed`, `aModelThisBuildCarriesNoProbeForIsNeverLoadedAndNeverReady` and three more |
| ⭐ The warm-up asks for consent | not asking | `theWarmUpLoadsNoTowerUntilBothConsentSwitchesAreOn`, `aPassWithoutConsentLoadsNothingForSearch`, `aConsentReadThatThrowsIsNo` |
| ⭐ A consent read that fails is NO | `true` on a failure | `aConsentReadThatThrowsIsNo` |
| Consent is this device's | another device's id | `theWarmUpLoadsNoTowerUntilBothConsentSwitchesAreOn` (its last step) and three more |
| Consent is the one for search by description | face clustering's | the same four |
| The warm-up is called | never | `aPreparedModelIsProbedAtInstallBecomesReadyAndItsTextTowerIsWarmed`, `theWarmUpIsTheLaunchsAndNotEveryForegrounds`, two more |
| …at launch, not on every foreground | always | `theWarmUpIsTheLaunchsAndNotEveryForegrounds` |
| …and after a pass | at launch only | `aPassOnALaterForegroundWarmsTheTextTowerToo` |
| An install is a launch | `launch = false` from `install` | `aPreparedModelIsProbedAtInstallBecomesReadyAndItsTextTowerIsWarmed` (its relaunch half) |
| ⭐ A teardown's unload waits for the probe's towers | not waiting | `aRuntimeTornDownUnderAProbeKeepsTheNextOneFromLoadingUntilItsTowersAreClosed` |
| ⭐ The preflight waits for a pending unload | an empty `beforeLoad` in the binding | the same |
| ⭐ The backend's load waits for its own graph's probe | not waiting | `theBackendLoadsNoTowerWhileItsOwnGraphsProbeStillHoldsAPair` |
| The warm-up awaits `beforeLoad` | not awaiting | `theWarmUpWaitsForTheLoadToBeAllowed` |
| The warm-up asks `ready` first | no check | `theWarmUpOpensNothingForABackendThatDoesNotServe` |
| The warm-up opens no tower without a vocabulary | no check | `theWarmUpOpensNoTowerWithoutAVocabulary`, `theWarmUpOpensTheTextTowerAndAQueryThenFindsItOpen` |
| Memory warnings are observed | never registering | `aMemoryWarningDuringAProbeIsCounted` |
| The vision tower is opened as the vision tower | the text tower's spec | `itsTowersAreSessionsOverTheCompiledModelsAndCarryTheSessionsGuards` |

⚠ **A simulator mutation that was withdrawn with its line:** `recheck` first asked whether the
model is `Ready` before the warm-up. Removing that check changed nothing, because `warmText`
asks the backend's own `ready` and opens nothing otherwise (`theWarmUpOpensNothingForABackendThatDoesNotServe`).
One guard, the backend's; the duplicate is gone.

⚠ **Two mutations survived first, and each found a test that proved nothing.**
- *Never marking the towers held.* The case launched its waiter from inside a prediction on an
  unconfined dispatcher, which queued it until the probe had finished, and asserted inside the
  prediction, where the probe's own `catch` turned the assertion into "a prediction failed". It
  now starts the waiter undispatched and reads, after the probe, how many towers were open
  when the wait returned.
- *Shifting by the count.* A `Long` shift takes its distance modulo 64, so the one large count
  the case tried (100 000 misses) happened to shift into the cap. 61 misses shift the minute
  out to zero and 65 shift it by nothing. The case now checks every count from 7 to 200.

⚠ **Not mutated:** the `tryLock` that makes a second trigger `Busy` (removing it deadlocks, it
does not fail); the order *close, then record*, which is the function's structure rather than
a line (the two close rows stand for it: with either tower left open the verdict is written
with a tower open, and the first case is red on exactly that).

### 14.10 Still owed, and not proved

- **Smoke** (`scripts/android-smoke.sh`) and the full gate: owed, the orchestrator's before
  merge. `commonMain` of `:inference-service-proxy` changed; `:control:mergeLibDexDebug` ran
  here and passed.
- ⚠⚠ **Nothing on a phone.** In particular:
  - the probe's margin on an iPhone's Neural Engine. Every figure in §14.6 is a Mac's;
  - that `os_proc_available_memory` resolves and returns a figure on iOS [I]. If it does not,
    the memory line is inert there;
  - that `UIApplicationDidReceiveMemoryWarningNotification` reaches the observer in Photos. The
    case posts the notification itself;
  - any timing. The simulator's vision median, about 0.2 s, is a Mac CPU's.
- **Not proved:**
  - `Preflight.prepared()` in a graph: called by `ModelPreparer` since 5c-2 (§14.17), where the probe is a stand-in; the real preflight after a real prepare has not run;
  - that the runtime's foreground hook passes `launch = false`. `recheck`'s two modes are
    exercised directly; the hook's argument is read from the code;
  - the route by which the backend could load beside its own graph's probe. It needs writer 3
    under a running probe (writer 3 has callers since 5c-1, §14.11; nothing drives one under a
    probe); the case reaches the state by calling `recordVerdict` by hand and says so;
  - a store change (writer 3) landing under a running probe. A result for a moved key is
    dropped; a removal and re-install of the **same** archive under one probe is not exercised;
  - a probe whose prediction never returns. As in §14.5, the pending unload then never
    finishes and the next runtime in that process loads nothing;
  - writer 3's reset under a failed write, by mutation: no memory holds a count, so there is
    no line to remove. The case (`aResetOfTheFailedCountThatCannotBeWrittenLeavesTheInstallFailed`)
    pins the behaviour;
  - a second **real** export's record. The two-record cases use a synthetic second record (the
    first under other digests); production carries one, for the kept export;
  - that adding a record leaves `PINNED_DIGEST` alone, by an actual second export: it follows
    from the digest no longer reading any record, and the per-record pin is what was mutated;
  - a read failure of the **real** `IosFileDatabase`, for the verdict. The cases use a database
    whose read throws, and one that answers `null` for a file a stat finds. The model index has
    one case in which Foundation itself fails (§13.7);
  - ⚠ **owed outside this slice:** `InferenceStoreCustody` reads an unreadable sidecar as a
    missing one and clears the vectors. It is PR-3's, it is listed in §14.6's table, and it is
    not changed here;
  - a write that fails **part-way**. The cases' database refuses the whole write. What a real
    `FileDatabase` leaves behind after a torn write is its own; a file that does not decode is
    no verdict and no count, which is the safe side;
  - `Busy`: a second trigger while a probe runs returns at once and is not stored. Both are
    asserted; the `tryLock` was not mutated, because removing it deadlocks rather than fails;
  - a store change **between the count and the verdict** of the third slow probe. There is no
    such moment to interleave any more (one call, one lock, one write); what is asserted is the
    one write, and the change at the two moments that do exist: during the predictions, and
    between the last prediction and the record;
  - the consent rows as the Settings screen will write them. The cases write the two rows
    straight into the proxy's database, as 5d's screen has no iOS build yet;
  - that `ClientRegistry.getExecutionContext` at launch has no cost worth naming on a phone. It
    is the call every operation makes; it now also runs once per launch check;
  - the real vocabulary through Photos' private database. The opt-in case reads it from the host;
  - that the archives in the opt-in case's directory are the record's. It is handed the kept
    export and does not hash 740 MB.
- ⚠ **Found on the way:** `CoreMlFixtures.digestFor` gives `fixture_vision` and `fixture_text`
  stand-in digests that share their first 16 characters, and the cache keeps a model under
  exactly that prefix. Preparing the second fixture removes the first. No 5a case predicted on
  the first after preparing the second, so none failed. 5b's case passes a digest per role; the
  helper is unchanged.
- [I] That closing a `CoreMlSession` releases the model (§8). The preflight relies on it as
  leaving `Ready` does.

### 14.11 What 5c-1 shipped (2026-10-11): the download

Marks as above. Nothing here is reachable on a phone: the flag is off, and no screen starts a
download. No schema'd type is reshaped and no operation gained a parameter or a reason (HR 9).

**The cut.** §14.1's 5c row was one slice. It is built as two:
- **5c-1, this section:** the runtime contribution, the journal, the transport, integrity, the
  free-space check, and writer 3's two callers.
- **5c-2, built since (§14.17):** everything that touches `CoreMlModelCache`: the prepare step and writer 1,
  `Preflight.prepared()`'s caller, eviction on a removal, the launch sweep of compiled
  directories, delete-before-compile on an update, the recompile of a `.mlmodelc` that fails to
  load.
- Why here: the download ends at the store's commit and the prepare step begins there; they
  share no code and no disk budget. Writer 3 is in 5c-1 although §14.1 listed it with the cache,
  because a download that commits beside readiness is wrong the day it exists (§14.4 item 4).
- ⚠ What the cut leaves between the two: a model that is installed and never prepared. It reads
  `Preparing`, the backend claims nothing, and its ~743 MB stay on disk.

| Piece | Where |
|---|---|
| `BackgroundModelDownloader`, `ModelDownloadKey`, `ModelDownloadOutcome`, `ModelDownloadRefusal`, the typed refusals | `inference-service-proxy/src/commonMain/…/catalog/BackgroundModelDownloader.kt` |
| `ModelTransferPort`, `ModelTransferRequest`, the journal (`ModelDownloadJournal`, `JournaledDownload`) | `…/catalog/ModelTransfers.kt` |
| `CoreMlDiskBudget` | `…/catalog/CoreMlDiskBudget.kt` |
| `ModelStore.adoptStaged`, `ModelStoreChanges` | `…/catalog/ModelStore.kt`, `DefaultModelStore.kt` |
| `ModelDownloaderFactory`; `downloaderFactory` and `storeChanges` on `modelDeliveryComponents` | `…/catalog/ModelDeliveryComponents.kt`; `control-shared-proxy/src/commonMain/…/di/ProxyModule.kt` |
| `DeleteModel` inside the wrapper | `…/ModelOperations.kt` |
| The three bindings (runtime, downloader, wrapper); `recheck` resumes | `control-shared-proxy/src/iosMain/…/di/PhotosOnDeviceAi.kt` |
| `PhotosModelDownloads`, `PhotosModelTransfers` | `control-shared-proxy/src/iosMain/…/di/PhotosModelDownloads.kt` |
| `ModelTransferBridge`; the five hooks on `PhotosLocalRuntime` | `tenshu-sdk-ios/…/ModelTransferBridge.kt`, `PhotosLocalRuntime.kt` |
| `ModelDownload` (the background session), `PhotosAppDelegate` | `photos-ios/Sources/ModelDownload.swift`, `PhotosIosApp.swift` |

**What is bound, and when.** `photosOnDeviceAiModule`, which a graph gets only past the build
flag and the static gates, now also binds, together:
- `supportedModelRuntimes = COREML_RUNTIMES`, exactly `{"coreml"}`;
- a `ModelDownloaderFactory` that builds `BackgroundModelDownloader`. `modelDeliveryComponents`
  uses a contributed factory and otherwise the in-process `DefaultModelDownloader`, as before.
  Photos' graph therefore never resolves the in-process fetch;
- `ModelStoreChanges` = `AiReadiness.storeChanging`.

With the flag off none of the three exists: the catalog's filter is empty and offers nothing, and
`PhotosIosApp` creates no session and installs no bridge (`modelDownloadsOffered()`).
`models-pipeline.md` §6.1's condition holds by construction: no iOS source set contains an ONNX
runtime to contribute, and a test pins the set to exactly `{"coreml"}`.

**One download, in order** (`BackgroundModelDownloader.begin`, `settle`, `commit`):
1. The verified catalog entry. No catalog and an unknown id fail as they do in-process.
2. No transport installed is a **refusal**. Nothing falls back to the in-process fetch, which
   cannot be held to Wi-Fi.
3. The journal is read. Unreadable is a refusal (below).
4. What an earlier attempt left in the store's staging is **hashed from disk, and not touched**
   (`ModelStore.stagedBytes`, a read). An artifact staged whole under the right digest, or
   installed under it, is neither fetched nor budgeted for. ⚠⚠ **Nothing is changed before the
   download is admitted**, which is step 6: until its record is written it may still be refused,
   and the staging it would clear may be a running download's (*Two operations on one model*).
   Wrong bytes are dropped, and a marker written, only by the settle that follows admission.
   ⚠ Bytes of the right length that could not be read to the end are left, then as before,
   and nothing is fetched in their place.
5. The free-space check (below). A refusal ends here: nothing journalled, nothing fetched.
   ⚠ It is asked again at **every** later start of a transfer of this download (*What a
   download was admitted under*, below).
6. **The journal is written.** A write that fails throws, and no transfer has been started.
7. One transfer per missing artifact is handed to the transport.
8. As each is delivered: its size, then its sha256 over every byte **from disk**, then it is
   moved into the store's staging (`ModelStore.adoptStaged`). A file that fails either check is
   deleted and never staged. A file that could not be read to the end is left where it is and
   nothing is concluded from it.
9. When every artifact is staged **and was hashed by this process**: `commitStaged`, inside
   `ModelStoreChanges` (writer 3). Then the journal record is removed.

**The journal.** One JSON file, `inference/downloads/journal.json`, in Photos' private
`FileDatabase`: under the `inference/` tree PR-3 purges whole, in the backup-excluded root.
- It holds, per download: the whole verified catalog entry (id, version, runtime, licence and
  the rest), each artifact's role, path, sha256, byte size and transfer ref, and the cellular
  choice. No field has a default.
- **It holds no state.** Where an artifact stands is derived each time from three things: the
  delivered file, the store's staging, the transport's live transfers. Nothing in memory, and
  nothing in the journal, can say an artifact arrived when the disk does not.
- **Never cached.** Every step reads it again.
- A ref is `<modelId>.<role>.<first 16 of the sha256>.<wifi|any>`, built from the catalog's text with
  everything outside `[A-Za-z0-9_-]` replaced. ⚠⚠ **The cellular choice is part of it** (second review): a transfer keeps the request it was started with, and a transfer the session
  still holds is never started again, so a ref naming the artifact alone would let a
  Wi-Fi-only download be carried by a transfer an earlier one had started with cellular
  allowed. Under the other choice the same artifact is another ref, and the old transfer is
  one no record names: the sweep cancels it. It is the task's description in the session
  (which survives a relaunch) and the name of the delivered file, `<ref>.delivered`.

**What a download is, and what is kept when only the entry moves** (third review).
- ⚠⚠ **The fault** (Codex, P2). A download's identity was the model id and the artifacts'
  digests. A signed catalog that changes `modelVersion`, `labelSetVersion` or `licence` and
  leaves the bytes alone was therefore *the same download*: `begin` joined the old journal
  record and committed the old entry, and a model already installed under those digests was
  taken for done. The store kept the old version, label set and licence for good.
- **Every field of an entry, where it is kept, and whether it is identity:**

  | Field | Journal | Install record | Identity (`ModelDownloadKey`) | Why |
  |---|---|---|---|---|
  | `modelId` | yes | yes | **yes** | |
  | artifacts' `sha256` | yes | yes | **yes** | the bytes |
  | `modelVersion`, `labelSetVersion`, `licence` | yes | yes | **yes, since round 3** | the store keeps them and `InstalledModels` reports them |
  | artifacts' `role`, `byteSize` | yes | yes | **yes, since round 5** | ⚠ Round 3 called them *not identity*, on the ground that they follow from the digest. The role does not: a catalog can give the same bytes to another role, and `pathOf(role)` is how a backend finds a tower |
  | artifacts' `path` | yes | no | no | where the bytes are fetched from. A transfer under way keeps its URL; the bytes are checked whatever served them |
  | `name`, `capabilities`, `runtime`, `artifactVersion` | yes, to rebuild the entry for the commit | **no** | no | nothing persists them, so nothing can go stale. The catalog is asked each time they are shown |
  | the cellular choice | yes | no | no; it is in the transfer **ref** | |

- ⚠⚠ **Identity is the whole install record, compared whole** (fifth review). The field list
  above was kept by hand twice and was short twice. `ModelDownloadKey` now holds every field of
  an install record but the file paths, and the question *is what is installed what this entry
  would install?* is one `==` between two records: the stored one, and the one the store's own
  builder (`installedRecord`) makes from the entry, placed at the stored one's paths. A field
  added to the record is compared without anyone remembering to; a case pins the record's
  field names, so adding one also asks for a line in the per-field case.
  - **The same bytes under another role:** installed under that role. ⚠ The store keeps one
    file per role (`<id>.<role>.<digest>.model`), so the renamed artifact is fetched again;
    the others are not.
- **The same bytes under a newer entry fetch nothing.** Three cases, one rule: bytes are named
  by digest, so they are kept, and the **record** becomes the newer entry.
  - *Mid-download:* the journal record is rewritten with the newer entry; its refs are the
    same, so no transfer is cancelled or started and nothing staged is dropped. The commit
    installs the newer entry, for whoever asked first as well.
  - *Everything staged:* the same, and the commit follows at once.
  - *Installed:* an artifact the store holds installed under the same digest, its file there
    at its recorded size, counts as present (`installedHolds`). Nothing is fetched or staged
    for it; `commitStaged` adopts the resident file, as `DefaultModelStore` always does for a
    path that holds the whole artifact, and writes the newer record. ⚠ By the record's digest
    and a stat, not by hashing: a record is the store's word that verified bytes are there.
    A record whose file is gone is not taken for the bytes: not for one artifact
    (`installedHolds`), and not for the whole record. ⚠⚠ The second half was missing until the
    whole-file review: asking for an entry the install record already *is*, field for field,
    answered *installed* on the record alone, before any file was looked at, and a lost tower
    was never fetched again. `settleOne` now takes that shortcut only when every file the
    record names is there at its recorded size. (The case that was meant to pin the rule
    passed because it asked for a renumbered entry, which never reached the shortcut.)
  - ⚠ That last rule is per artifact, so an **update that shares an artifact** (the vocabulary
    is the ONNX entry's own file, and need not change with the towers) does not fetch it again.
- **It is a commit like any other: through writer 3.** `storeChanging` leaves `Ready` first
  (the pass is cancelled, the sessions closed), the record is rewritten, and readiness
  reconciles.
  - ⚠ **`labelSetVersion` is in neither key** [C: `iosPreflightKey`, `iosRuntimeFingerprint`].
    `PreflightKey`'s `vocabularySha256` is the vocabulary **artifact's** digest. So a
    metadata-only rewrite leaves the runtime fingerprint as it was (**the vectors stay**) and
    the preflight key as it was (**the verdict stands**): the model is `Ready` again as soon as
    the reconcile has read the store, with nothing probed and nothing cleared. What it costs is
    a running pass, cancelled once.
  - ⚠ Labels are inert (§27.2). The day they are not, a new `labelSetVersion` is what makes
    stored label rows stale (`ModelProvenance`), and that is `inference-service.md` §39.4's.
- ⚠ **Who asks.** Nothing rewrites a record by itself: it happens when `download` is called for
  a model whose catalog entry moved. Photos' Models screen offers it since 5d-3 (*Update*, row
  11b), and a refused or failed update says why on its row since the hardening before 5e; bytes
  that failed their check are not offered again (I5). ⚠ Control's Android screen reads an
  installed older version through `ModelInventory` as *installed* (`tech-debt.md`).
- **The shared `DefaultModelDownloader` (Android, desktop) is not stale, and is wasteful** [C,
  not run]: it has no journal and looks only at its own staging, so the same call there
  fetches every artifact again (~383 MB) and then commits; the store adopts the resident files
  and writes the newer record. Not changed here (`tech-debt.md`).

**Two operations on one model** (fourth review). Four rounds of review each found one
pair of operations that interleaved wrongly, so this is the whole table, once. One model id;
the states it can be in; what each operation does there; the case that pins it.

States: **S0** nothing · **S1** a journal, mid-download · **S2** a journal, every artifact
staged, not committed · **S3** installed, no journal · **S4** installed, and a journal for an
update (other bytes).

| Operation | S0 | S1 | S2 | S3 | S4 |
|---|---|---|---|---|---|
| `download`, the entry as it is journalled or installed | journal, then transfers ✓¹ | joins: no second transfer ✓² | joins, commits ⚠ᵃ | fetches nothing, commits nothing ✓³ | joins the update ⚠ᵃ |
| `download`, the same bytes under newer metadata | — | record rewritten, transfers kept ✓⁴ | record rewritten, committed ✓⁵ | record rewritten, nothing fetched, no space asked ✓⁶ | the update's record rewritten ⚠ᵃ |
| `download`, other bytes, **admitted** | — | takes over: old record replaced, then its transfers cancelled (not ones the two share) and its staging dropped ✓⁷ | the same ⚠ᵃ | an update: the installed model untouched until the commit ✓⁸ | as S1; installed untouched ⚠ᵃ |
| `download`, other bytes, **refused** (no room, free space unknown, no transport, journal unreadable) | nothing | **nothing of the running download is touched**: its staging, marker, record, transfers ✓⁹ | the same ⚠ᵃ | nothing | as S1 ⚠ᵃ |
| `download`, the other cellular choice | — | refused; nothing touched ✓² | refused ⚠ᵃ | — | refused ⚠ᵃ |
| `cancel` | nothing | record, then **its staging**, transfers and delivered files ✓¹⁰ | the same ⚠ᵃ | nothing ⚠ᵃ | as S1; installed untouched ⚠ᵃ |
| `DeleteModel` | `NOT_INSTALLED` ✓¹¹ | `NOT_INSTALLED`; **the download goes on** ⚠ᵇ | the same ⚠ᵇ | removed, inside writer 3 ✓¹² | **the download is ended first** (record, transfers, delivered files), then the model removed inside writer 3; its staging goes with it ✓¹³ |
| `DeleteModel`, the download cannot be ended (journal unreadable; graph torn down) | — | — | — | not removed ✓¹⁴ | **not removed** ✓¹⁴ |
| `resume` (launch, foreground) | sweeps what no record names ✓¹⁵ | stages what arrived, starts again only what the session lost ✓¹⁶ | commits ✓¹⁷ | nothing; a record a commit left behind is removed ✓¹⁸ | as S1; the commit replaces the installed model inside writer 3 ✓⁸ |
| A transfer's report | unnamed: its file is discarded ✓¹⁹ | staged; the last one commits ✓¹ | — | as S0 ✓¹⁹ | as S1 ✓⁸ |
| `isDownloading`, in a process that has resumed nothing yet | false | **true**, with a bar ✓²⁰ | true ⚠ᵃ | false | true ⚠ᵃ |
| Teardown | — | nothing touched; waiters told; progress ends ✓²¹ | the same ✓²² | — | the same ⚠ᵃ |

✓ pinned by: ¹ `aDownloadIsJournalledBeforeAnyTransferAndInstalledOnceEveryArtifactIsVerified` ·
² `aSecondCallJoinsTheRunningDownloadAndAnotherCellularChoiceIsRefused` ·
³ `askingAgainForAModelThatIsInstalledAsTheCatalogHasItFetchesNothing` ·
⁴ `aNewerEntryOverTheSameBytesMidDownload…` · ⁵ `aNewerEntryOverBytesThatAreAllStaged…` ·
⁶ `aNewerEntryOverBytesThatAreInstalled…` ·
⁷ `aNewerCatalogEntryReplacesTheDownloadThatWasRunning`, `aNewerEntryThatIsAdmittedTakesOverAndTheOlderStagingGoes` ·
⁸ `anUpdateNeedsTheSameAsAFirstInstall` ·
⁹ `aNewerEntryThatIsRefusedTouchesNothingOfTheDownloadThatIsRunning` (once per reason) ·
¹⁰ `aStalledTransferInstallsNothingAndCancelEndsIt`, `aCancelWhoseRecordCouldNotBeRemovedCancelsNothing`, `aCancelledDownloadLeavesNothingStagedAndAFailedOneKeepsWhatArrived` ·
¹¹ `deletingSomethingAbsentFailsRatherThanSucceedingQuietly` ·
¹² `aModelWithNoDownloadIsSimplyRemoved`, `deletingRunsInsideTheStoreChangeWrapper`, and in Photos' graph `deletingTheModelRunsInsideReadinessWriterThree` ·
¹³ `deletingAModelEndsTheUpdateThatWasDownloadingSoNothingBringsItBack`, `deletingIsHandedToTheDownloaderWhichEndsADownloadOfThatModelFirst` ·
¹⁴ `aModelWhoseDownloadCannotBeEndedIsNotRemoved` · ¹⁵ `theSweepRemovesOnlyWhatNoRecordNames` ·
¹⁶ `aRelaunchMidTransferStartsNothingTwiceAndStartsAgainOnlyWhatTheTransportLost` ·
¹⁷ `aRelaunchWithNoMemoryOfTheDownloadInstallsItFromTheJournal` ·
¹⁸ `aCommitWhoseJournalWriteDidNotLandIsNotFetchedAgain` ·
¹⁹ `aTransferNoRecordNamesIsDiscardedWhenItReports` ·
²⁰ `aDownloadIsRunningFromTheMomentItIsAskedForAndOnlyInTheProcessThatWorksOnIt` ·
²¹ `whoeverWaitsOnAGraphThatIsTornDownIsToldAtOnceAndTheDownloadIsLeftAlone` ·
²² `aGraphRetiredOnceEverythingIsStagedDoesNotCommit`.

⚠ᵃ **Not pinned in that state.** The code takes the same path as the pinned state beside it
(S2 as S1 with nothing left to fetch; S4 as S1, the installed record not consulted until the
commit); no case sets the state up.
⚠ᵇ **Intended, and a gap for 5d.** `DeleteModel` removes an *installed* model and refuses
anything else; it is not a cancel. No operation cancels a download: `cancel` is a method of the
downloader, reachable in Photos' process and not over the wire. 5d's screen needs it for S1
and S2, or an operation that carries it.

- ⚠⚠ **Fault 1** (Codex, P2): `DeleteModel` in S4 removed the model beside its update. After a
  relaunch nothing on a screen said an update was running, and the next transfer to be settled
  committed the model again. **The rule:** a removal is handed to the downloader
  (`ModelDownloader.withoutDownloadOf`), which holds its own lock from the journal read to the
  end of the removal:
  1. the journal is read **from disk**, not from what this process remembers;
  2. a record for the model: removed, then its transfers cancelled and its delivered files
     deleted, exactly as `cancel` does; whoever waited on it is told;
  3. the removal, inside writer 3 and **after asking again there** (`Acts.changingTheStore`;
     sixth review), which also drops the model's staging.
  - **If the download cannot be ended, the model is not removed.** An unreadable journal throws
    `ModelDownloadJournalUnreadableException`; a graph that was torn down throws
    `ModelDownloadCancelledException`. On the wire both are `TRANSPORT_FAILED` with their own
    sentence, as they are from `DownloadModel` (*What reaches the wire*, below). ⚠ The first
    cut left them untyped, and this section said so as if it were a property; it was the defect.
  - ⚠ A cancel that reaches the journal and not the session (it has not answered yet, or the
    process dies) leaves transfers no record names: their files are discarded as they report,
    and the next resume cancels them.
  - **Android and desktop are unchanged** [C]: `withoutDownloadOf`'s default runs the removal
    and nothing else, and the in-process downloader does not override it. ⚠ So there the same
    interleaving exists while the process lives: a model deleted under its own running update
    is committed again when that download ends (`tech-debt.md`). It cannot outlive the process.
- ⚠⚠ **Fault 2** (Codex, P2): a newer entry's `begin` asked the store what was staged with
  `beginStaging`, which clears staging for any other digest, **before** the free-space check.
  Refused, it left the running download's record and transfers alive over staging it had
  deleted. **The rule:** nothing is changed before admission. `begin` reads (`stagedBytes`,
  `readStaged`, the install record, the free space) until the record is written; every
  refusal comes before that write. The four refusals that can follow a running download are
  each pinned (⁹); the fifth, the other cellular choice, is on the path that changes nothing
  at all.
- **`isDownloading` after a relaunch.** It answered from memory, and a relaunched process
  remembers nothing until its launch check has resumed the journal, which waits for the
  stores' own check and may sit behind a hash. A screen that asked first was told *not
  downloading* over live transfers, which is what made fault 1 easy to reach. Now the first
  question a process is asked **reads the journal once** and tracks what it names: *running*,
  with a bar, from then on. It reads and tracks; it starts, settles and writes nothing.
  ⚠ The bar shows nothing received until the session reports or the resume has settled.
- ⚠⚠ **What is tracked in memory is a copy of the journal, and a copy can drift** (eighth
  review). That first look read the journal and tracked what it read **outside** the
  downloader's lock: a record committed, cancelled or replaced between the two was tracked
  after it had ended, and the model stayed *running*, its finished bar restarting, until the
  process was replaced. And a record that replaced another left the other's refs tracked.
  - **A ref is tracked in one place, under the lock, from a record a step has just read or
    just written** (`noteTheJournal`, `track`); tracking a model's record drops every other
    ref tracked for that model; nothing is tracked on a graph that is gone.
  - **`isDownloading` never waits.** The first look takes the lock with `tryLock`. When a
    step holds it, the look is not made and is tried again at the next question; nothing is
    lost, because **every step tracks what it read before it does anything long**. A step can
    hold the lock for the length of a hash or of the session's answer, and the question is
    asked twenty times a second.
  - For the same reason **the catalog is fetched before the lock is taken**: it can take
    tens of seconds, it needs nothing of the journal, and it held up every other step.
  - **Every other copy of durable state kept in memory, and its verdict** [C]:

    | Copy | Of what | Written | Verdict |
    |---|---|---|---|
    | `tracked` | the journal's refs | under the lock only (this round); cleared by `tornDown` | fixed |
    | the progress holder's entries | `tracked` | with it, under the lock; `transferProgressed` only moves a figure of an entry that exists; ended by `finish`, `release`, `tornDown` | follows `tracked` |
    | `verified` | which refs this process hashed and staged | under the lock; dropped with the record on every path that ends one, and by a commit the store refused as not committable | ⚠⚠ **had a gap, found by the whole-file review, closed.** "Nothing changes a record's staging but the steps that hold the lock" was true and beside the point: an artifact marked here *from the install record* left whatever an earlier download had staged for its role, under another digest, and the commit renamed that onto the resident file (§14.15, finding 1). Now a role the install record holds has its staging dropped when it is marked, the store promotes only staging whose marker names the committed digest, and never judges a resident file by the length of what is in staging |
    | waiter entries, `lastReport` | nothing durable | — | not a copy |
    | `journalLookedAt` | whether the journal was noted | under the lock | — |
    | `DefaultModelStore`'s cached index | the index file | the store's own lock | ⚠ per store instance: two graphs of one process each have one (§14.16, the commit window) |
    | the ledger's holders | the session's tasks | at a start, a cancel, a listing, a report, a settlement; one lock | ⚠ lags the session **on purpose** between a completion and its settlement (tenth review), and never leads it. A hold is released by a completion that is not reported, or by the settlement of one that is. A task that vanished with no completion, or a report never acknowledged, would keep its ref held until the process is replaced [I: the session always delivers a completion; C: the downloader acknowledges in a `finally`]. Not run in Swift |
    | Swift's `ownTasks` | this process's task objects, by task identifier | set when a task is made, cleared when it ends | a miss costs an immediate cancel, which the session's answer then makes. ⚠ **`reported` is gone** (ninth review): this table called it a convenience, and it decided what a user saw. Kept by ref, it outlived its task; it is the ledger's now, per task |

**The state the matrix above leaves out: a call asked for and not yet journalled** (round 14). A call of `download` is fetching the catalog (tens of seconds) or waiting for the
step lock. It is in `awaiting` and has no record, so every operation that looks for a record
found "nothing", and the call began afterwards.

| Operation, while a call is in that state | Before | Now |
|---|---|---|
| `DeleteModel` (`withoutDownloadOf`) | ⚠⚠ found no download, removed the model; the call then journalled, downloaded and **installed the model again** | the call is void: told `ModelDownloadCancelledException` when it reaches `begin`, nothing journalled or started |
| `cancel` | ⚠⚠ found nothing to cancel; the download **started afterwards** | the same: void |
| a second `download`, the other cellular choice | whichever reaches `begin` first journals; the other is refused `OtherChoiceRunning` | unchanged, and right |
| a second `download`, the same choice | the second joins the first's record | unchanged |
| teardown | released at once (round 5); its step stops at `alive()` | unchanged |
| `resume`, a transfer's report | act on the journal, which does not name the call | unchanged: the call begins after them |

- **One mechanism, by order of asking.** A per-model count of endings made on purpose
  (`endings`), moved when a cancel or a removal is **asked for**, before it waits for the
  lock. A call notes the count when it is asked for; `begin` refuses it when the count has
  moved. So a call made *before* the cancel is void, and a call made *after* it is a new
  request even when the cancel has not run yet (the whole-file review's finding 3, kept).
- ⚠⚠ **The rule, in one sentence: an ending and the downloads around it take effect in the
  order they were asked for.** The count gave half of it (a download asked for before an
  ending is void). The other half was missing until Codex round 17: an ending is counted and
  then waits for the lock, and a download asked for *after* it could reach the lock first,
  journal and start; the ending, arriving, ended "the record of this model", which was the
  new request.
  - **Now a download does not begin while an ending asked for before it is still on its way**
    (`pendingEndings`): it waits, outside the lock, for every ending numbered at or below the
    count it noted. Counting an ending and adding it to the pending set is one block with a
    call's noting of the count, so a call is on one side of an ending or the other.
  - **Chosen over stamping each record with the count it began under.** The stamp tells a
    cancel which record to leave alone, and gets a removal wrong: *delete, then download
    again*, with the download reaching the lock first, is answered "installed" from the model
    the delete has not removed yet, and is then left with nothing. Ordering gives the outcome
    the order of asking says: the model is removed, then downloaded and installed.
  - An ending is taken off the pending set in a `finally`: one that throws, or whose caller
    left, holds nothing up. A download waiting on one is released by `tornDown` like any
    other (its entry is completed; its step stops at `alive()` when the ending has ended).
  - The other direction still holds: a download asked for *before* an ending that reaches the
    lock *after* it is void by the count; one that reaches the lock before it journals and is
    ended by it. And the review's finding 3: a call asked for after an ending is not told of it.
- ⚠ A cancel or a removal that then fails (an unreadable journal) has still voided the calls
  that had not begun. The user asked for the end; what failed is ending what had begun.
- ⚠ Only a call of this graph: a pending call on another graph of the process is not voided
  (its graph is being torn down, and its step stops at `alive()`).

**What becomes of a download's bytes when its record ends** (seventh review). Once a
record is gone nothing names what it had staged, and the store cannot list its own staging. So
every path on which a record stops being the current one says what happens to all three kinds
of bytes, and none leaves it to chance.

| The record ends by | Staged in the store, per artifact | Delivered, not yet staged | Transfers in flight |
|---|---|---|---|
| the commit | consumed: renamed into place, markers deleted. ⚠ Only staging whose marker names the committed digest is renamed; whatever is staged beside an artifact the store adopts where it is installed is deleted, whichever digest it was for | none are left | none are left |
| `cancel` | **dropped** (this round: it kept them, with nothing to reclaim them) | deleted | cancelled |
| `DeleteModel` over an update | **dropped, for every role of the update's record** (this round); the store's removal drops the installed record's roles, which need not be the same | deleted | cancelled |
| a transfer that failed; a file that was not the catalog's bytes | **kept, deliberately**: what arrived is what makes the retry fetch only the rest. Reclaimed by that retry, or role by role by the next download of the model (fetched roles by `beginStaging`, roles it finds installed by dropping them). ⚠ By nothing if neither comes (`tech-debt.md`) | the wrong one discarded where found; the others deleted | cancelled |
| a newer entry taking over (other bytes) | kept where the two share an artifact; replaced by the newer entry's own settle for every role it **fetches** (`beginStaging` for its digest); ⚠⚠ **dropped by that settle for a role the install record already holds** under the newer entry's digest (whole-file review: this cell said "for every role it has", which was not true of that role, where nothing touched the staging and the commit renamed it onto the installed file); **dropped for a role it does not have** (this round) | deleted, but for a shared artifact | cancelled, but for a shared artifact |
| a newer entry over the same bytes | kept: it is the same download under another record | kept | kept |
| a newer entry that is **refused** | nothing ends: untouched | untouched | untouched |
| a journal that is not this build's, swept | ⚠ **left**: the roles it named cannot be read. Replaced role by role by the next download of that model; otherwise not reclaimed | deleted (no record names them) | cancelled (no record names them) |
| a journal that could not be read | nothing ends: untouched | untouched | untouched |
| a teardown | nothing ends: the next graph's | the next graph's | the next graph's |

- ⚠ **`cancel` now costs a retry its staged artifacts.** A screen that changes the cellular
  choice by cancelling and starting again (§14.14 item 8) fetches everything again. It was
  the price of having no reclaimer at all for a cancelled download's staging.
- ⚠ Android and desktop: the in-process downloader has no `cancel`, and a failed download
  keeps its staging there as here (`tech-debt.md`).

**What reaches the wire** (seventh review). One function, `typed` in `ModelOperations.kt`,
turns whatever the store or a downloader throws into a `ModelUnavailable`, for every model
operation; no operation keeps a list of catches of its own. **No reason was added**: each
failure maps to a reason the vocabulary already has.

| Thrown, by the downloader or by a store path it reaches | Reason | `DownloadModel` | `DeleteModel` | `InstalledModels` |
|---|---|---|---|---|
| `ModelCatalogUnavailableException` | `NO_CATALOG` | ✓ | — | — |
| `UnknownModelException` | `UNKNOWN_MODEL` | ✓ | — | — |
| `ModelIntegrityException` | `INTEGRITY_FAILED` | ✓ | — | — |
| `ModelStoreNotOwnedException` | `STORE_NOT_OWNED` / `STORE_LOCK_UNAVAILABLE` | ✓ | ✓ | — |
| `ModelIndexUnreadableException` | `TRANSPORT_FAILED` ⚠ | ✓ | ✓ (was untyped) | ✓ (was untyped) |
| `ModelDownloadJournalUnreadableException` | `TRANSPORT_FAILED` ⚠ | ✓ | ✓ (was untyped) | — |
| `ModelDownloadCancelledException` (cancelled, replaced, torn down) | `TRANSPORT_FAILED` | ✓ | ✓ (was untyped) | — |
| `ModelStorageInsufficientException`, `ModelFreeSpaceUnknownException`, `ModelTransportUnavailableException`, `ModelDownloadChoiceConflictException` | `TRANSPORT_FAILED` ⚠ | ✓ | — | — |
| `ModelDownloadException`, an I/O error, anything else | `TRANSPORT_FAILED` | ✓ | ✓ | ✓ |
| `NOT_INSTALLED`, any other `TenshuException` | itself | — | ✓ | — |
| a cancellation of the caller | itself; never mapped | ✓ | ✓ | ✓ |

- A ✓ is a cell a case runs (`everyFailureHasTheSameTypedReasonWhicheverOperationMeetsIt`);
  — is a cell the operation cannot reach.
- ⚠ marks a failure for which `TRANSPORT_FAILED`, the retryable bucket, is the nearest
  reason and not a right one. A reason of its own is the wire-vocabulary change of §14.14
  item 1, still the maintainer's to decide.
- **What else calls into the downloader, and cannot throw to the wire:** `DownloadProgress`
  asks `isDownloading` (it reads the journal once and catches its own failures) and collects
  the progress holder; `AvailableModels` asks the catalog source, which answers `null` and
  does not throw. `cancel` is reached by no operation (⚠ᵇ above).
- ⚠ **This changes Android and desktop too**, in one way: an index that cannot be read now
  fails `InstalledModels` and `DeleteModel` as `TRANSPORT_FAILED` with the store's sentence,
  where it was an internal error with no reason. `ModelsRepository` reads either as *nothing
  installed* (`tech-debt.md`).

**What a download was admitted under holds again at every start** (ninth review).
- ⚠⚠ **The fault** (Codex, P2). `begin` checked the disk and the transport before it
  journalled anything; a record then lives for days, and the starts made for it later (a
  transfer the session lost, restarted at a relaunch) checked nothing. A disk that had filled
  meanwhile took hundreds of megabytes it could not turn into a model.
- **The rule: no transfer is started without every admission condition being asked in that
  same step, against what stands now.** It is asked in the one function that decides what to
  start (`settleOne`), with the one budget function the admission uses (`noRoomFor`).

  | Condition of `begin` | At a later start | |
  |---|---|---|
  | free space for what is not staged, the prepare step of what is not installed, the margin | **asked again**, from the current standings | ⚠ an artifact still in flight is counted whole: the check errs towards refusing |
  | free space could be read | **asked again**; unknown refuses, as at admission | |
  | a transport is installed | **asked again** | |
  | the journal could be read | the step does not run otherwise | |
  | the store may be written, its index read | every look at staging asks; a refusal is told to whoever waits | |
  | the graph is alive | every act asks (*A retired graph*) | |
  | the cellular choice | not a condition: the record's own choice is on every request it starts | |
  | the catalog still offers this entry | ⚠ **deliberately not asked.** A resume needs no network of its own, and the bytes are checked against the journalled digest whatever the catalog says now. An entry the catalog has dropped finishes downloading; `download` is what compares the catalog |
  | what the session holds is known | unknown starts nothing and **says nothing**: it is not a refusal, the next resume asks again | |

- ⚠⚠ **A start that is refused ends nothing.** The record, what is staged, and the transfers
  that are still running all stay: the download is not over, it cannot go on *now*. Decided
  against ending the record, which would throw away what was fetched for a condition the
  user can lift.
  - `outcomeFor(key)` is `Refused(InsufficientStorage(needed, free))`, `Refused(FreeSpaceUnknown)`
    or `Refused(NoTransport)`; whoever waits is told with the same typed exception.
  - `isDownloading` stays true and the bar stays where it is: a record exists, and its other
    transfers may be running. ⚠ So 5d's screen must read the outcome to say *waiting for
    space*; the bar alone looks stalled.
  - The next resume (a launch, a return to the foreground) asks again, and starts.

**When the journal is rewritten** (eighth review). ⚠⚠ Whenever the record that **would** be
written is not the record that **is** there, compared whole, as two values of the one type.
- The fault was the third of its kind (after the install record's, rounds 3 and 5): `begin`
  skipped the write when the **identity key** had not moved, and the journal holds more than
  identity. A signed catalog that only **moved an artifact** (another `path`; the same role,
  digest, size and metadata) left the old path in the journal. The call at hand fetched from
  the new place; a relaunch, which has only the journal, asked the old URL.
- The key is now used for the two questions it is for: *join or replace* (the same bytes?)
  and *installed already?*. Whether to write is not one of them.
- **A transfer already running for the moved artifact is kept.** Its ref names the digest
  and the cellular choice, not the URL, and the bytes are checked whatever served them. If
  the old place still serves them, it finishes and nothing is fetched twice; if it does not,
  that transfer fails, the download fails as a transport failure, and the retry reads the
  new path from the journal.

**The journal's read contract.** §13.7's five cases, on a file that guards less than the index:

| The journal | Is | `download` | Settle, commit | `cancel` | Launch sweep |
|---|---|---|---|---|---|
| not there, or blank | no download | goes through | nothing to do | nothing to do | runs |
| read, decodes | the journal | joins or starts | runs | runs | runs |
| **could not be read** (the read threw; or `null` for a file a stat finds) | unknown | **refused**, `ModelDownloadJournalUnreadableException`, before the free-space check | **not run**: no file moved, staged or deleted, no transfer started | **refused**, nothing cancelled | **not run**. Nothing is remembered, so the next call reads again |
| **read, is JSON, is not this build's record** | no journal, **by decision** | goes through, and writes over it | nothing to do | nothing to do | **runs** |
| **read, is not JSON at all** | no journal, by decision | the same | the same | the same | **runs** |

- ⚠⚠ **The last two rows differ from the index's, on purpose** (§14.14 item 4). The index names
  verified, installed bytes, so one that is another build's record is refused and never
  written over. The journal names transfers and unverified files. Without its digests a
  delivered file can never be verified, so nothing in an undecodable journal can ever be
  installed, and refusing would leave a phone that was updated mid-download with a Models
  screen that never works again (the index's own missing reset, `tech-debt.md`).
- **`null` is not yet *absent***: after a `null` the reader asks `fileSize`, as the verdict's
  and the index's readers do.

**Every path that deletes, cancels or starts on the strength of the journal** [C: every caller
of `readForAction`]:

| Path | Does | On a journal that could not be read |
|---|---|---|
| `begin` | writes the record, starts transfers; on a newer catalog entry cancels and deletes what only the old record named | refused first |
| `settleFinished` for a ref no record names | deletes that ref's delivered file | not run: the file stays |
| `settleFinished` for a failed transfer | removes the record, then cancels its transfers and deletes its delivered files | not run |
| `stand` (size or digest wrong) | deletes that delivered file | not reached |
| `fail` | removes the record, then releases as above | reads again; unreadable → nothing changes, nothing is reported |
| `cancel` | the same | refused |
| `sweep` (launch, foreground) | cancels live transfers no record names; deletes delivered files no record names | not run |
| `finish` (after a commit) | removes the record | the record stays; the next settle finds the model installed with the record's digests and removes it then |

- **The record goes first, everywhere.** A transfer is cancelled and a delivered file deleted
  only after the record that named them is off the disk. If that write fails nothing else
  changes and **nothing is reported**: the journal still says the download is running.
- The sweep's two halves are each gated on their own knowledge: transfers are cancelled only
  when the transport said what it holds, files are deleted only when the directory could be
  listed. A name that could not be a ref is never turned into a path.

**The transport** (`ModelTransferPort`; in Swift, `ModelDownload`).
- A background `URLSession`, identifier `app.tenshu.photos.model-download`,
  `isDiscretionary = false`, `sessionSendsLaunchEvents = true`. One `URLSessionDownloadTask`
  per artifact, its `taskDescription` the ref.
- ⚠⚠ **D6, in the request:** `allowsCellularAccess` and `allowsExpensiveNetworkAccess` are both
  set to the download's own choice, which is `false` unless `download(modelId, allowsCellular =
  true)` was called. `ModelDownloader.download(modelId)`, all the `DownloadModel` operation can
  call, is Wi-Fi only. The session's configuration keeps its defaults: a request can only
  narrow what its session allows [D: Apple, not run].
- **The first choice stands.** A second `download` of the same model with the other choice is
  refused (`ModelDownloadChoiceConflictException`); the caller cancels and starts again.
- **Three calls back into Kotlin**, on `PhotosLocalRuntime`:
  - `onModelTransferDelivered(ref, temporaryPath, statusCode)`, **synchronously inside
    `didFinishDownloadingTo`**, because the session's file is gone when that returns. It moves
    the file to `<root>/inference/downloads/<ref>.delivered` and does nothing else. Only a 2xx
    response is taken, only under a ref-shaped name, and only after the root's backup exclusion
    was set and read back;
  - `onModelTransferCompleted(ref, statusCode, errorMessage)`, from `didCompleteWithError`;
  - `onModelTransferProgress(ref, bytesWritten)`, about once a MiB.
- ⚠⚠ **A cancelled transfer reports nothing** (`NSURLErrorCancelled` is dropped in Swift). Kotlin
  cancels a ref and may start the same ref again at once; a late "cancelled" would be read as
  the new transfer's failure. A transfer the system cancelled is found missing at the next
  resume.
- **A transfer that ends without a file** (an error, a non-2xx status) fails the download as a
  transport failure. What its siblings had already delivered is staged first, so the retry
  fetches only what is missing; siblings still in flight are cancelled. ⚠ Unless the artifact
  the report is about is staged all the same: a report that is late, or about a transfer an
  earlier process settled, fails nothing.
- **Starts and cancels are ordered by when they were asked for** (`ModelTransferLedger`,
  beside the downloader in `:inference-service-proxy` since the whole-file review, the
  umbrella keeping the name Swift sees; rewritten after the second review). The session says what it holds later, on
  another queue (`getAllTasks`), so a start and a cancel are each two moments, and everything
  that goes wrong between them is order.
  - ⚠⚠ **The fault** (Codex, P2). The first cut numbered a start when its task was made, in the
    session's answer. A cancel asked for before that answer found nothing to cancel, and the
    answer then made and resumed the task: a cancelled model went on downloading. Asked for
    again at once, the download was suppressed by that stale task and ran on its request.
  - **Now:** a start is numbered when it is asked for. One a later cancel overtook is dropped
    at the session's answer, and again when its task exists (it is cancelled without running).
    A held ref is not started twice, whoever asks. A task a cancel was asked for no longer
    holds its ref. A cancel stops what was asked for before it, this process's own tasks at
    once and an earlier process's at the session's answer, and nothing asked for after it.
  - **Can a retry inherit the old task's cellular choice? Not within a process:** a task is
    never reused, each start builds its request from its own call, and the retry is admitted
    past the cancelled task. **Nor across processes:** a transfer whose cancel never reached
    the session (the process died first) is still there with its old request, and a download
    under the other choice does not match it, because the choice is in the ref (*The journal*).
  - ⚠⚠ **A report is checked when it is made and acted on later, so the two are made one
    thing: a reported task keeps its ref until its report is settled** (tenth review; the
    fourth finding of this kind after rounds 2, 5 and 7).
    - **The fault.** Whether a completion is the ref's to report was decided in the session's
      callback, and the ref freed there; Kotlin acted on the report later, queued behind its
      own lock. A step holding that lock (a resume, waiting for this very session to say what
      it holds) was told the ref was free, started a replacement, and the queued failure then
      ended the download the replacement belonged to.
    - **Chosen: the hold is kept until the downloader acknowledges** (`completed` /
      `settled`), not a generation carried into Kotlin and checked again there. A check
      repeated later still leaves the replacement started in between, to be cancelled and
      fetched again; with the hold kept, it is never started. And it is one rule in one
      place, the ledger, which already decides who speaks.
      - ⚠⚠ **Not enough alone, as the whole-file review showed: the report is asked again
        when it is acted on, too.** A hold can *end* while its report waits: a cancel ends
        it at once, and the download asked for next starts the same ref. The waiting report
        was then acted on as the new download's, a success as much as a failure (its file had
        gone with the cancel: "status 200 and delivered nothing"). So `completed` answers
        with the ledger's own report object, and the downloader asks `stands()` under its
        lock, which is the lock its cancels run under. The hold kept and the question asked
        again are the two halves; neither keys on what the report says.
    - `ledger.completed(task, ref, cancelled)` answers with a report (`ModelTransferReport`),
      or nothing when the completion is nobody's. A task that is reported stays its ref's
      holder, marked *unsettled*. `BackgroundModelDownloader.transferFinished` takes the
      report, asks `stands()` when it acts, and calls `settled()` **exactly once, whatever the
      settling did** (acted, found the report nobody's, could not read the journal, threw,
      met a teardown); the Swift passes the object through and never looks inside it.
    - **What makes `liveRefs` and the ledger agree: the answer is the ledger's, and it is
      who holds a ref.** Round 10 answered the session's list plus the unsettled holders.
      The list was still wrong the other way: it names a task whose cancel was asked for and
      has not reached it, so the download asked for after the cancel was told its ref was
      live, started nothing, and then the task went. `liveRefs` is now the held refs and
      nothing from the list (which is only used to learn of tasks of an earlier process). So
      a step is told what `admit` would allow: two lines of one rule.
    - **And a start is refused when its picture is out of date** (whole-file review). After
      a report is settled its ref is free, rightly. A start asked for *before* that, which
      the session answers only afterwards, was admitted: the artifact was staged and was
      fetched again. `admit` refuses a start asked for before a transfer of its ref was acted
      on while it still held the ref. ⚠ Only then: a transfer the system ended without a
      report, or one whose hold a cancel had ended, drops no start.
    - **The orders.** *The report is queued before a replacement could start:* the hold
      stands, none is started. *A replacement was started before the transfer completed:*
      then the old task was not the holder (a cancel, or it was never one), `completed`
      answers nothing, and nothing is reported. *The cancel and the replacement come after
      the report was made and before it is acted on:* the report no longer stands.
    - **All three reports.** *Completed* is the one that is deferred, and is covered as
      above. *Progress* is passed on in the same call that checks the holder, and has no
      later moment.
    - ⚠⚠ ***Delivered* has one after all** (round 15; this bullet said it had
      none). The file is moved inside the callback, on the session's queue, with no runtime
      and outside every lock of the downloader, because the temporary file dies when the
      callback returns. The holder was asked once, before the move. A cancel, a removal or a
      takeover landing in between cancelled the ref, deleted the destination, and the move
      then put the file back: up to 555 MB no record named, with no report to follow (the
      task had lost its hold) and no reclaimer but a later resume's sweep.
      - **A delivery is two questions around the move:** `beginDelivery` before it,
        `endDelivery` after it, and on *not kept* the mover deletes what it just placed.
      - **Which side does what, and why nobody waits.** The downloader does not wait for a
        delivery in progress and does not need to know of one. Ending a download, it cancels
        the ref (the hold ends in the ledger at once) and deletes the destination. Whichever
        of its delete and the move is second, the file goes: its own delete when the move was
        first, `endDelivery`'s answer when the move was second. The ledger's lock is held
        inside each question and never across the move, so the session's queue and the
        downloader's step lock never wait on each other.
      - **Nothing concludes "nothing delivered" during a delivery.** That conclusion is drawn
        from the completion's report, and the completion is delivered after
        `didFinishDownloadingTo` has returned, on the same serial queue. A resume in between
        sees the task still holding its ref, and starts nothing.
      - ⚠ Rests on the session delivering one file at a time (its delegate queue is serial):
        otherwise the task that replaces a cancelled one could place its file at the same
        path before the first one's discard. [D], not run.
      - **Who sweeps a delivered file no record names, in a build that offers the model:**
        only `resume`, at launch and at each foreground. So the rule above makes the window
        unreachable; it does not lean on the sweep.
    - ⚠ A report nobody takes (no runtime is installed in a background relaunch) is settled
      by the Swift at once: `onModelTransferCompleted` answers whether it was taken.
    - A cancel asked for while a report waits ends the hold all the same.
    - **Run together**: `BackgroundModelDownloadRacesTest` (JVM and simulator) drives the real
      downloader, its lock and its queued settlement over the real ledger and the real store.
      The session is `FakeTransport`: tasks, and the calls `ModelDownload.swift` makes on the
      ledger written out in Kotlin, once, for every case of the downloader's suite. ⚠ That
      the Swift makes those calls is still only compiled.
  - ⚠⚠ **One holder a ref, and every task known from first sight** (seventh review). Round
    5's rule had a second branch: a task this process did not start *spoke unless a cancel had
    been asked for*. So a task of an earlier process that had ended before the launch asked
    the session what it holds, its failure still queued, spoke over the replacement started
    in between, and Kotlin removed the replacement's record. There is no such branch now:
    - a task is given an identity the first time it is seen, when the session lists it
      (`observe`, and in the answers to a start and a cancel) or when it first reports. It
      takes the ref's hold then **if the ref is free and no cancel was asked for**; otherwise
      it is known and holds nothing, for good;
    - a task speaks, and keeps its ref from being started, **only while it is the holder**.
    - The relaunch, walked as ledger cases: the earlier task fails *before* the session is
      asked (it speaks, its ref is then free); it had ended *before* and reports *after* a
      replacement was admitted (it does not speak; nor would a file of its be taken); it was
      *listed* and fails later (it speaks: that is this download's failure); *two* earlier
      tasks for one ref (the first listed holds; the other never speaks, and does not inherit).
  - ⚠⚠ **Only the task that holds a ref now speaks for it** (`speaksFor`; fifth review). A
    ref outlives a task: cancelled and asked for again with the same choice, a download has
    the same ref and a new task, and the old task's last report can still be queued. Its
    **failure** was read as the new download's, which was then removed for it. So every
    report into Kotlin (delivered, progress, completed) is dropped unless its task is the
    holder (as restated above, one rule for every task).
    - **A stale task's delivered file is dropped too**, although it would be harmless: it has
      the ref's own digest, so it would be hashed and staged, or discarded. It is dropped
      because the download it belonged to was cancelled, and so that one rule covers every
      report. Not taken, the session deletes it.
    - `NSURLErrorCancelled` is still suppressed by itself: a transfer the *system* cancelled
      (a force-quit) belongs to an earlier process, which still speaks.
  - ⚠⚠ **The progress throttle is the ledger's, per task** (`reportsProgress`; ninth
    review). The Swift kept the last figure it had passed on **by ref**. A ref outlives a
    task: after a cancel far into a download and a retry with the same choice, the new task
    said nothing until it had passed the point the old one reached, and a 555 MB retry sat at
    zero. The figure now belongs to the task and is forgotten with it.
    - **What per-ref or per-task state is left in Swift:** none per ref. Per task, only
      `ownTasks` (the task objects, by task identifier), set when a task is made and cleared
      in `didCompleteWithError`. The session and the ledger are objects, not state.
    - **The Kotlin bar, for the same faults** [C, and pinned where marked]: after a cancel the
      entry is ended, and a retry's starts from what is staged, which a cancel has dropped
      (zero). After a takeover the entry is **restarted with the new record's total** (this
      round ✓: it kept the replaced record's, and would have run past its end); a transfer
      the two records share keeps its figure, rightly. A figure never exceeds its artifact's
      size. ⚠ It can go **backwards**, and should: a transfer that restarts has restarted.
  - The ledger is pure state and is run by `ModelTransferLedgerTest`, on the JVM and the simulator.
    `ModelDownload.swift` asks it what to do at each moment and does only that. ⚠ That the
    Swift asks at the right moments is not run: no session has run.
- **`liveRefs` may answer *could not tell*** (`null`; the session is given 5 s). Nothing is
  then started again or cancelled.

⚠⚠ **Two graphs of one process never interleave over the one journal** (round
13; the fifth finding of one family, after rounds 1, 2 and 6 and the commit-window note).
- **The finding.** A graph torn down *inside* its commit, after its last `alive()`, went on to
  the one act left ungated since round 1: removing the journal record once the store had
  confirmed. Its successor had journalled the same model meanwhile. The removal was by model,
  so it erased the successor's record; the successor's transfers then reported to no record,
  their files were deleted, and its caller waited for good.
- **The fact under all five.** The downloader's lock was per instance. A process has one
  journal, one store directory, one session and one ledger, and it can have two graphs over
  them at once. **Nothing serialised the two graphs' journal writes at all**: each read the
  journal at the start of a step and wrote it seconds later (after a hash, the transport's
  answer, a commit), and whatever the other had written in between was lost. Every journal
  write had that window, not only this one. Each earlier finding was answered with one more
  question asked of `alive()`, which can stop an act that has not begun and nothing else.
- **Done: one lock for the process** (the structural fix, not a sixth patch). The downloader is
  given its step lock (`steps`, no default); on iOS it is `PhotosModelDownloads.steps`, beside
  the transport, which is the process's too. Every step of every graph runs under it. The
  successor's first step waits until the retiring graph's step in flight has ended, and
  `alive()` only has to stop the *next* step.
  - It was contained: one constructor parameter, one object-level `Mutex`, one line of wiring.
  - **Not done: an identity (nonce) on the journal record** for a compare-and-remove. Under
    one lock the record a step removes is the record it read; a nonce would be a second guard
    for an interleaving that cannot happen, and it changes the journal's shape and the
    whole-record comparison in `begin`.
  - ⚠ `tornDown` still takes no lock, and `isDownloading`'s first look still only tries it.
- **What this closes:** the lost update on every journal write between two graphs; the
  cleanup after a commit; and, between graphs, the instant between an `alive()` and its act
  (round 1's residue): that act can still happen on a retired graph, but no other graph is in
  a step while it does.
- ⚠⚠ **Which two-graph windows remain:**
  1. ~~The store's index~~ **closed in round 14** (below).
  2. **How long the successor waits.** For the retiring step: a hash (seconds), the
     transport's answer (5 s at most), or the old graph's store-change wrapper. ⚠ That a
     retired readiness lets go of its wrapper promptly was not traced; if it does not, the
     successor's steps wait with it (its callers are still released by its own `tornDown`).
  3. **The wiring is one line, compiled, and no case runs two graphs through it on iOS.** The
     cases share the lock by hand (`DownloadProcess(steps = …)`).

⚠⚠ **A store never writes back, or decides from, an index it only remembers** (round 14; window 1 of the list above). `DefaultModelStore` memoises its index for the life of
the instance, on the ground that "nothing else writes it". On iOS a process can have two
stores over one index. The successor's memo predated the old graph's commit: it reported the
model absent, fetched 743 MB again, and its own commit wrote *its* view back, which with a
second model id drops the other's record.
- **Every index write is a read-modify-write of the index on disk.** `commitLocked` and
  `removeLocked` read it from storage before they compute what to write.
- **The store knows for itself when its memo is out of date** (after round 14; this replaced
  the refresh the downloader's steps asked for, which closed the gap for the steps and left
  every other reader in it). One counter for the process is moved by every index write of any
  store; a memo read at another count is not served, it is read again. So `InstalledModels`,
  `pathOf` and readiness on a successor graph see the old graph's commit at their next
  question, with no step in between. `pathOf`'s per-image path pays one volatile read and a
  comparison.
  - **What readiness did with a stale "absent"** [C, read, not run]: `AiReadiness` derives
    its state from one reading; with `installed` false the target is `NoModel`, and
    `leaveTo` publishes it and, if the state was `Ready`, cancels the pass and closes the
    sessions. **Nothing is persisted and nothing is cleared**: the fingerprint is applied (and
    vectors another archive made are cleared) only on *entering* `Ready`, and the verdict is
    written only there. So a stale `NoModel` was not sticky, within a process or across a
    crash: the next reconcile derives from a fresh reading. Its cost was a model not served,
    and an indexing pass cancelled, until then.
- **And the sweep cannot meet a commit in flight** (after round 14). The sweep runs on an
  instance's first read and deletes declared paths no record names. "Every declaration belongs
  to a dead process" was false with two stores: a successor's first read landing inside the
  old graph's commit, after its renames and before its index write, deleted the files just
  renamed, and the commit then wrote a record for files that were gone. Now **one lock for
  the process** (`DefaultModelStore`'s own, not the downloader's) is held by every commit and
  removal from the declaration to the index write, and by every read of the index from
  storage. The first read waits for the commit and finds its paths named.
  - Chosen over running the first read under the downloader's step lock: that lock is
    suspending and is held for the length of a hash, and a reader on the per-image path would
    wait behind it. This one is held for a commit's renames, is taken inside an instance's
    own lock and never the other way round, and nothing is awaited under it. The memoised
    path does not take it.
  - Chosen over sweeping only from the downloader: Android and desktop sweep from whichever
    read comes first, and that had to stay.
- **The memo stays**, for `pathOf`. A read from storage that fails forgets it and throws:
  once the disk has been asked and did not answer, memory is not served in its place. The
  read cases of §13.7 are the same code (absent, unreadable, not JSON, not this build's
  record); nothing is written after a failed one. The sweep still runs once per instance.
- ⚠ What a second store's first read still does, by design of the sweep: it deletes the
  **previous generation** of a model the other store of this process replaced. "The delete
  belongs to the next process" was written for one store; with two, the successor's first
  read is that delete. A session of the old graph that still has the old files open keeps
  them (an unlinked open file), and the old graph is being torn down.
- **Android and desktop:** one graph and one store. One more read of a small index per commit
  and per removal, one uncontended monitor, and a comparison on `pathOf`. Nothing else.

⚠⚠ **A read that failed is not a read that found nothing, and no ended transfer leaves its
caller waiting** (round 12). The journal read used by every step answered a
nullable list, `null` for *could not be read*, and one call site wrote `.orEmpty()`: at the
second read inside one report, a failed read became "the record is gone". The step returned,
the report was acknowledged and its hold released, and the caller waited on a download no
transfer ran for.
- **The accessor that made the choice silently is gone.** `readForAction()` answers a sealed
  `Journal` (`Read` or `Unreadable`), and the only way to the downloads is
  `orElse { … }`, whose argument cannot return a value: it returns, throws or refuses. Every
  call site says what an unreadable journal means *there*.
- **The invariant.** A journalled download always has one of: a transfer live or held for each
  artifact still missing; a step pending that will look again; or its callers told. Each
  early return of `settle`, `settleOne`, `settleReport`, `fail` and `resume` against it:

  | Return | Before | Now |
  |---|---|---|
  | `settleReport`: the journal cannot be read at all | silent; the hold released | callers told `ModelDownloadJournalUnreadableException` (by the model the ref is tracked for; no outcome, which is keyed by the record nobody read). The delivered file stays |
  | `settleReport`: cannot be read at the second read | ⚠⚠ taken for "gone" | callers told; outcome `Refused(JournalUnreadable)`; record, staging and the other transfers untouched |
  | `fail`: the journal cannot be read | silent | the same |
  | `fail`: the record cannot be removed | silent, by an earlier decision ("nothing is reported") | callers told the failure as it is, integrity kept; nothing cancelled or released; the record stays |
  | `settleOne`: nothing missing, not all staged (bytes that could not be read) | silent | callers told (a retryable failure); bytes and record stay |
  | `settleOne`: bytes unreadable while other transfers are live | waits | waits: their reports settle the download again |
  | `settleOne`: not now (no transport, no room) | told (round 9) | — |
  | `settleOne`: the transport could not say what is live | silent | **told** (round 16): `Refused(TransfersUnknown)`, `ModelTransfersUnknownException`. Nothing started (a transfer that may be running is not started twice), record and staging kept |
  | `resume`: the journal cannot be read | silent | **told** (round 16): every caller that had joined a record, `ModelDownloadJournalUnreadableException`. No outcome (it is keyed by a record nobody read) |
  | a report that no longer stands; a retired graph | silent | rightly: the first is another download's, the second's callers were told by `tornDown` |

- **Every return, on the tree of round 16, and the leg of the invariant it stands on**
  (*T* a transfer live or held for each missing artifact, *S* a step to come, *C* callers told):

  | Return | Leg |
  |---|---|
  | `begin`: refused before the journal (no transport, unreadable journal, no room, the other choice, the store, an ending asked for since) | C: this caller alone; nothing was journalled |
  | `begin` → `settle`, same bytes joined or a new record | whatever `settleOne` answers below |
  | `settleOne`: installed already; all staged → the commit | C: installed. A commit the store refuses: C (`failWaiting`), record kept |
  | `settleOne`: wrong length, wrong digest | C: failed; the record goes, or when it cannot, C all the same |
  | `settleOne`: `startMissing = false` | S: `settleReport` goes on in the same step |
  | `settleOne`: nothing missing, bytes unread | C (round 12) |
  | `settleOne`: no transport; no room | C (round 9), record kept |
  | `settleOne`: the transport could not say | C (round 16), record kept |
  | `settleOne`: every missing artifact is live or held | T |
  | `settleOne`: the missing ones started | T |
  | `settle`: an exception from `settleOne` (the store, the disk) | C (`failWaiting`), record kept |
  | `settleReport`: journal unreadable, at either read | C (round 12) |
  | `settleReport`: no record names the ref | no download: the file is deleted |
  | `settleReport`: nothing delivered, staged all the same; unread | T or C through the `settleOne` it ran; unread: nothing concluded, and the artifact's other transfers or the caller's told state stand |
  | `settleReport`: nothing delivered, not staged | C: failed |
  | a report that no longer stands | another download's: nothing of this one changes |
  | `resume`: journal unreadable | C (round 16) |
  | `resume`, `settleFinished`: a retired graph | C: `tornDown` told them |

  ⚠ *C* does not mean the download is over. For every *record kept* row the journal still
  names it, and the next `download` of the same entry joins it, or the next resume goes on.
- **What each such case looks like from outside** (5d's screen reads these four things):

  | Case | The caller of `download` | `outcomeFor(key)` | `isDownloading` | The progress stream |
  |---|---|---|---|---|
  | Installed | the record | `Installed` | false | ends (its entry is finished) |
  | Failed (transfer, wrong bytes); the record removed | the failure | `Failed`, `integrity` as it is | false | ends |
  | Failed, the record could not be removed | the failure | `Failed` | ⚠ **true** | ⚠ **stays open at its last figure**: the entry is the record's, and the record is there |
  | Cancelled; the model removed | cancelled | `Cancelled` | false | ends |
  | Not now: no transport, no room (a restart) | the typed refusal | `Refused(…)` | ⚠ true | ⚠ stays open at its last figure |
  | The transport could not say | `ModelTransfersUnknownException` | `Refused(TransfersUnknown)` | ⚠ true | ⚠ stays open, at what was staged |
  | Journal unreadable under a report or a failure | `ModelDownloadJournalUnreadableException` | `Refused(JournalUnreadable)`, or none when the record itself was what could not be read | ⚠ true | ⚠ stays open at its last figure |
  | Journal unreadable at a resume | the same | none | ⚠ true if it was tracked | ⚠ stays open |
  | Bytes that could not be read, nothing to wait for; a commit the store refused | a retryable failure | `Failed`, not integrity, or `Refused(StoreRefused)` | ⚠ true | ⚠ stays open |
  | Refused before the journal | the typed refusal | `Refused(…)` | false, unless another call's download runs | never had an entry of its own |
  | Torn down | cancelled | unchanged | false | ends |

  ⚠⚠ So the stream does **not** tell a refused-for-now download from a slow one: its entry
  stays, and its figure stops. `outcomeFor` is what says which. 5d must read both, or the
  stream needs a state of its own, which is a wire change and not this slice's.
- **The enumeration's five seconds** (`PhotosModelDownloads.LIVE_REFS_TIMEOUT_MS`) is Drive's
  value for the same call. `getAllTasks` crosses to `nsurlsessiond`; why it would take seconds
  in the foreground is not known and was not measured [I]. ⚠ A late answer is not lost on the
  ledger: the Swift hands the session's list to `ledger.liveRefs` inside the callback, before
  it calls back into Kotlin, so every task is known for the next ask even when Kotlin has
  stopped waiting.
- **No retry is scheduled.** Told, the caller asks again, and that asks the transport again
  (`aTransportThatDoesNotSayWhatItHoldsIsSaidAndNothingIsStartedOrEnded`). A timer on the
  downloader's scope would be a second way for a step to start, to be stopped by teardown,
  and a caller both told and still pending. One behaviour: said, kept, asked again.
- **Chosen: tell the caller, do not keep the report unsettled.** A report kept unsettled keeps
  its ref held, and nothing would retry it before the next foreground: the caller would wait
  exactly as before, and a download asked for again could not start its transfer. Told, the
  caller can ask again, and the record is there for the resume. It is `notNow`'s shape (round
  9): said, and nothing ended.
- **Every place a read result is narrowed, in this slice's Kotlin:**

  | Site | Unreadable vs absent | Verdict |
  |---|---|---|
  | `stillJournalled` (`.orEmpty()`) | collapsed | ⚠⚠ **fixed** (above); the function is gone |
  | `DefaultModelStore.beginStaging`: the marker | ⚠⚠ collapsed: a marker that was there and gave no bytes read as "another digest", and the staged bytes were **deleted** | **fixed**: it throws, nothing is deleted. ⚠ Shared with Android and desktop, where a download now fails and resumes later instead of restarting from zero |
  | `DefaultModelStore.stagedBytes`: the marker | collapsed to 0 | left: it only reads. The download is budgeted as if nothing were staged, and `beginStaging` then refuses |
  | `commitLocked`: the marker | collapsed to "not this commit's" | right: the commit is refused whole and nothing changes |
  | `ModelDownloadJournal.read` | distinct, with the stat after `null`; undecodable is its own answer | right |
  | `hash`, `hashOfStaged` (`?: return null`) | *inconclusive*, never missing or corrupt | right |
  | `reads.fileSize(delivered) == null`, `installedHolds`' stat | ⚠ a stat that fails reads as absent | left, and it is the floor: the stat is what tells unreadable from absent everywhere else. A failed stat of a delivered file fails the download as "delivered nothing" (retryable) |
  | `sweep`: `deliveredRefs() ?: return` | `null` is *could not list* | right: nothing is deleted |
  | `PhotosModelDownloads.deliveredRefs`, `discardDelivered` | `null` for a directory that could not be listed | right |
  | `PhotosModelDownloads.leftBehind` | a failed existence check reads as *nothing left* | left: no drain at that launch, asked again at the next |
  | `liveRefs() == null` | *could not tell*, never none | right (and see the table above) |
  | `reads.freeSpace() ?:` | unknown is a refusal | right |
  | the operation layer, the ledger | no read of the disk | — |

- **File protection.** Nothing in this path sets a protection class: `IosFileDatabase` writes
  with `NSDataWritingAtomic` and no protection option, the delivered file is moved by
  `NSFileManager`, and Photos declares no data-protection entitlement. So the journal, the
  delivered files and the staging have the platform default for a third-party app,
  *protected until first user authentication* [D]: unreadable only between a boot and the
  first unlock. A settle now runs only when the scene is active, which a locked phone cannot
  show, so it runs after that first unlock. ⚠ Not run on a phone; and the launch-time
  `resume` is tied to `recheck`, not to the scene phase, so a launch iOS makes before the
  first unlock (a background relaunch installs no runtime, so this needs another cause) would
  meet unreadable files, conclude nothing, and tell whoever waited.

⚠⚠ **What an enabled build left behind, met by a build that offers no model** (round 11). The session and its tasks belong to `nsurlsessiond` and outlive the app that started
them. An update can turn the feature off while a transfer runs: the build flag, or a static
gate. The app then never created the session (launch and `handleEventsForBackgroundURLSession`
were both behind `modelDownloadsOffered`), so its events were never drained and its tasks ran
on, up to 743 MB of them, for a build that installs nothing.
- **One predicate decides all three cases.** *Flag off*, *a static gate failing* and *a graph
  with no downloader bound* are the same thing: `PhotosOnDeviceAi.admitted` gates the session,
  the bridge, the owned stores and the downloader factory together. The gating is unchanged.
- **What becomes of each thing, in such a build:**

  | Left by the enabled build | Now | Reclaimed by |
  |---|---|---|
  | (a) live tasks in the session | **cancelled, all of them**, at launch and when iOS relaunches Photos for the session. `ModelTransferLedger(offered = false)` names every listed task (`notOffered`), admits no start, lets no task speak and reports nothing | — |
  | (b) delivered files in `inference/downloads` | **deleted** (`PhotosModelDownloads.discardDelivered`, no runtime). A file a task delivers while it is being cancelled is not taken: the system deletes it with the callback | — |
  | (c) the journal | ⚠ **left.** A later build that offers the model reads it, finds the transfers gone and fetches what is not staged | that build's resume; ⚠ nothing until then |
  | (d) staging in the store, up to 743.4 MB | ⚠ **left**, with the journal that names it, for the same build | the same; ⚠ nothing until then (`tech-debt.md`) |
  | (e) an installed model | ⚠ **left and not served**: no runtime is contributed, so nothing loads it. Its compiled towers stay too: the sweep that would remove them is the prepare step's, which such a build does not bind (§14.17) | a build that offers it again; nothing else |

- **Decided: sweep what Swift can reach without a runtime, leave the rest to come back to.**
  The journal and the staging are only readable through the stores a not-admitted graph does
  not bind, and they are what makes re-enabling cheap. ⚠ The cost is bytes with no reclaimer on
  a phone whose build never offers the model again.
- ⚠ **A phone that never had the feature creates no session.** A not-offering build attaches
  one at launch only when the downloads directory exists (`leftBehind`: the journal is written
  into it before any transfer starts). That directory is never removed, so such a phone
  attaches and drains at every launch from then on; with nothing in the session that is one
  `getAllTasks`.
- The decision is the ledger's and is run (`aBuildThatOffersNoModelCancelsEveryTaskAndLetsNoneSpeak`),
  the file sweep is run on the simulator
  (`aBuildThatOffersNoModelDeletesWhatWasDeliveredAndLeavesTheJournalForABuildThatDoes`). ⚠ The
  Swift that joins them (`ModelDownload.drain`, its two callers) is compiled, with the flag
  off and on, and has never run.

⚠⚠ **Nothing is verified or installed in the background**, in two halves that make one rule.

*A background relaunch installs no runtime.* `handleEventsForBackgroundURLSession` only
re-attaches the session. What it delivers is moved into place by a function that needs no graph
(`PhotosModelDownloads.acceptDelivered`), and waits there, unverified.
- Why: installing Photos' runtime opens the shared replica, and a process suspended while
  holding its lock is killed (`0xdead10cc`, §3.3). A background launch for a transfer is exactly
  a process that will be suspended again.
- So verification and the commit happen at the next **launch or return to the foreground**:
  `PhotosOnDeviceAi.recheck` calls `PhotosModelDownloads.resume` after writer 4.
- When Photos is alive, installed **and in the foreground** when a transfer ends, the
  completion reaches the installed graph and is settled at once, on the downloader's own scope.

*And a Photos that was only suspended settles nothing either* (whole-file review). "No runtime"
is true of a process iOS **started** for the session's events. A Photos that was suspended with
its runtime resident is **woken** for the same events, and the completion then reached the
installed graph: half a gigabyte hashed and a commit made on a few seconds of background time,
with no background-task assertion to finish in.
- **Decided: deferred to the foreground, not given an assertion.** The scene phase tells Kotlin
  whether Photos is in the foreground (`PhotosLocalRuntime.modelTransfersSettleNow`; `false`
  until first told). While it is not, `onModelTransferCompleted` answers *not taken*: the
  delivered file is moved into place, the ledger frees the ref, and the next `.active` runs
  `recheck` → `resume`, which settles from the journal and the disk exactly as after a relaunch.
  - Why not an assertion: one more thing that only runs on a phone, with an expiry to handle,
    to gain an install a few seconds before the user can see it.
  - ⚠ Cost: a download that finishes in the background shows *installed* when Photos is
    opened, not before. A failure that ends in the background is found as a missing transfer
    at the next foreground and started again, rather than reported as that failure.
- ⚠ **What it does not cover:** a settle that *began* in the foreground and is still hashing
  when Photos is sent to the background. It holds the downloader's own lock and touches Photos'
  private files only (the journal, the staging, the model store and its index are under
  `INFERENCE_FILE_DATABASE`, not the App Group; `PhotosOwnedInferenceStoresTest`), so a
  suspension freezes it and it goes on when Photos runs again. ⚠ It does **not** take the
  shared replica's lock itself; `storeChanging` (writer 3) cancels a pass and closes sessions
  first, and whether anything it waits for can hold that lock at suspension was **not** traced
  in this round (§14.16).
- ⚠ Compiled only, like the rest of the Swift: the scene-phase call was never run.
- **Plist keys: none added.** A background `URLSession` needs no `UIBackgroundModes` entry
  [D: Apple]; Drive declares `fetch` for its `BGAppRefreshTask`, not for its upload session.
  `photos-ios/project.yml` is unchanged. **`PrivacyInfo.xcprivacy`: not added** (5e's, §14.1):
  the free-space read is a required-reason API and now has a second caller.
- ⚠ Photos has an application delegate now, as only Drive had. It implements one method.
  `control-cross-app-open.md` §5i.7 says where a URL lands in an app with one; Photos has no URL
  receiver yet.

**The free-space check** (`CoreMlDiskBudget`, asked in step 5).
- `need = what is left to fetch + the prepare step + the margin`, compared with
  `FileDatabase.availableSpaceBytes()`.
- The prepare step is §3.4's measured 1 836.4 MB for the measured 743.4 MB download, **scaled by
  the entry's size** and rounded up, so a later export of another size is not budgeted with this
  one's figure. For the measured download it is exactly 1 836 400 000 bytes.
- So for the measured download: 2 579.8 MB + the margin. For the published entry
  (743 448 870 bytes): 2 579 969 593 bytes + the margin, **2 848 405 049 bytes**.
- **A first install and an update need the same**, as §3.4 decides: nothing of the old
  generation is credited. ⚠ What is *not* an update is not budgeted: an artifact the store
  holds installed under the very digest the entry names is neither fetched nor prepared again
  (its compiled model is keyed by that digest), so its bytes are in neither term. When every
  artifact is such, only the record is rewritten and no space is asked for at all.
- The margin is a placeholder, 256 MiB (§14.14).
- **Below the need: `ModelStorageInsufficientException(neededBytes, freeBytes)`**, and the
  outcome `Refused(InsufficientStorage(needed, free))`, which is what 5d's screen words.
- **Unknown free space is a refusal** (`ModelFreeSpaceUnknownException`), unlike the cache's
  own check, which proceeds. Before a ~743 MB fetch, not knowing is not enough.
- ⚠⚠ **On the wire both are `TRANSPORT_FAILED`.** `DownloadModelImplementation`'s catch-all
  maps them there with the exception's sentence. `ModelUnavailable.Reasons` has no entry for
  either, and adding one is a wire-vocabulary change that was **not made** (§14.14 item 1;
  `tech-debt.md`).

**Readiness's writer 3** (`ModelStoreChanges`).
- A shared seam, `Direct` on every platform but this graph: `changing { … }` runs a commit or a
  removal. Photos' binding is `AiReadiness.storeChanging`.
- Two callers: `BackgroundModelDownloader.commit`, and `DeleteModelImplementation`, which is
  shared code and takes the wrapper from `ModelDeliveryComponents`. ⚠ The removal is also
  handed to the downloader first, which ends a download of that model (*Two operations on one
  model*); the wrapper is entered inside that.
- The in-process `DefaultModelDownloader` does not use it: no graph that resolves it has a
  readiness.

**A retired graph** (§14.2, *Teardown*; rewritten after 5c-1's review).
- ⚠⚠ **The fault** (Codex, P2). The first cut asked `AiReadiness.isRetired()` where each step
  began, before each move and before the commit. A step waits in three places (the catalog
  fetch, the transport saying what it holds, the store-change wrapper) and blocks for seconds
  while it hashes. A teardown that landed there was not seen again by what followed: missing
  transfers were **started**, a corrupt file deleted, a failed download's record removed and its
  transfers cancelled, by a graph that was gone, beside a successor resuming the same journal.
- **The rule now: every act asks, immediately before it acts** (`alive()`), and nothing is
  asked on an act's behalf any earlier. The acts: a transfer started, a transfer cancelled, a
  journal write, a file deleted, a look at the store's staging (which writes a marker), a file
  moved into staging, the commit before the wrapper is entered and again inside it. A teardown
  ends the step there; `resume` and `settleFinished` swallow it, `download` and `cancel` tell
  their caller.
- ⚠⚠ **A miss in that sweep, and what replaced the sweep** (second review). One act
  was left ungated: dropping staged bytes whose digest is wrong, after hashing them, which is
  where a teardown is most likely to have landed. Listing call sites had missed it, so the
  structure now does the listing: `store`, `files` and the transport are **not properties** of
  the downloader. It reaches them only through two holders:
  - `Reads`: `installed`, `readStaged`, `fileSize`, `readRange`, `freeSpace`, `journal`. Nothing
    in it changes anything;
  - `Acts`: `beginStaging`, `abortStaging`, `adoptStaged`, `commitStaged`, `writeJournal`,
    `delete`, and `port()`, whose `start` and `cancel` are wrapped. **Each asks `alive()` as
    its first line.** The one ungated act has its reason in its name:
    `writeJournalOnceTheStoreHasCommitted`.

  A mutating call made past the two does not compile. `commit` also asks before it enters the
  wrapper, because on iOS entering it stops a model being served.
- ⚠⚠ **The third miss of this rule, and the hole in that structure** (sixth review).
  `Acts` gates calls on collaborators the downloader holds. A removal is not one: it is a
  block the `DeleteModel` operation hands in. The downloader asked, then ran the block, and
  the block waited for the wrapper (readiness's lock) before it touched the store. A teardown
  in that wait was not seen, and a retired graph removed the model from the store its
  successor had taken over.
  - **One way to run a block that changes the store: `Acts.changingTheStore`.** It waits for
    the wrapper, **then** asks `alive()`, then runs the block. The commit's block and the
    removal's go through it. The wrapper itself is no longer a property of the downloader
    either, so a store-changing block cannot be run past the helper.
  - So `withoutDownloadOf` now takes the wrapper and the **bare** removal, and runs the
    wrapper itself. The operation no longer wraps: `ModelDownloader`'s default runs the
    removal inside the wrapper it is handed, which is what Android and desktop did before.
  - ⚠ A removal that follows an ended download and is then refused leaves the model
    installed and its update gone: the record was removed before the wait, by a graph that
    was alive then. The caller is told the removal did not happen.
  - **Everything else the downloader runs that it does not own**, checked [C]:

    | What | Changes anything durable? | Gate |
    |---|---|---|
    | the commit's block | the store | `changingTheStore`, and `Acts.commitStaged` asks again |
    | the removal's block (`DeleteModel`) | the store | `changingTheStore` (this round) |
    | `catalogSource.load`, `retired`, `deliveredRefs`, `additionalDiskNeed` | no: reads and arithmetic | none needed |
    | `transport()` | hands out the port; its `start` and `cancel` are wrapped | `Acts.port()` |
    | `progress` (the holder), the waiters' entries, `lastReport` | memory of this graph | none; `tornDown` ends them |
    | `tornDown` | memory only | — |
    | the first journal look of `isDownloading` | reads, then tracks in memory; no wait in it | — |
    | `finish`'s journal write after a commit | the journal | ungated on purpose (above) |
    | `PhotosModelDownloads.acceptDelivered` (the file move in the session's callback) | a delivered file | ⚠ not a graph's at all: it runs with no runtime, by design, and what it leaves is unverified bytes the next graph hashes |
    | `DeleteModel` on a graph with no such downloader (Android, desktop) | the store | none, unchanged: nothing there is ever retired |
- The checks where `resume` and `settleFinished` begin remain, and are now only what spares a
  retired graph the work: removing either changes nothing a case can see.
- ⚠⚠ **Whoever waits on a retired graph is told, by the teardown itself**
  (`BackgroundModelDownloader.tornDown()`, called from `PhotosOnDeviceAi.retire`). A retired
  graph completes nothing: it acts on nothing, no report is routed to it, nobody resumes it,
  and the next graph has waiters of its own. Without this a caller of `download` waited for
  ever. `tornDown` is not a suspend function and takes no lock a step holds (a step may be
  hashing under the downloader's own); it fails the waiters with
  `ModelDownloadCancelledException`, ends the progress entries, and touches neither the journal
  nor a file. A step that meets the teardown itself calls it too.
  - ⚠⚠ **One entry per call, made before the call waits for anything** (fifth review). The
    first cut made the entry when `begin` returned, so a caller still waiting for the catalog
    had none, and the teardown told nobody. Now `download` registers first and its step runs
    beside it, on the downloader's scope; the caller waits on its entry and nothing else.
    Every place a caller can be held, and what releases it on a teardown:

    | Caller of | Held by | Released by |
    |---|---|---|
    | `download` | the catalog fetch | its entry (`tornDown`), at once; the step is then cancelled, so a catalog that answers later journals and starts nothing |
    | `download` | the downloader's lock, behind a step that is hashing or waiting | its entry, at once |
    | `download` | the transport saying what it holds; the store-change wrapper | its entry, at once; the step ends at its next act (`alive()`) or its cancellation |
    | `download` | a hash (blocking) | its entry, at once: the hash is the step's, not the caller's |
    | `download` | the transfers themselves | its entry, at once |
    | `cancel`, `withoutDownloadOf` | the lock | ⚠ **not at once.** They have no entry: they wait until the step that holds the lock ends (bounded: a hash, or the transport's 5 s), then meet `alive()` and throw. Neither can wait for ever |
    | `withoutDownloadOf` | the removal it runs (writer 3) | readiness's own lock; a retired readiness reconciles nothing |
    | the progress stream | an entry that is coming | `tornDown` ends the progress entries and forgets every waiter, so `isDownloading` is false and the stream ends (after its 3 s grace when it had seen no entry) |

  - A refusal is the caller's own: it completes that caller's entry, not the entries of
    others joined to a running download. A newer entry that takes over fails the entries of
    the download it replaced, not its own.
  - ⚠⚠ **And an ending answers the calls that had joined the record, and no other** (whole-file
    review). The entries were kept by model id, so whatever ended a download (installed,
    failed, refused for now, cancelled, removed, replaced) completed every call under that id,
    including one that was still fetching the catalog or waiting for the lock: *cancel, then
    download again* told the second call "cancelled" and lost its request. An entry is now
    marked *joined* by `begin`, when its record is the journal's, and an ending takes the
    joined entries only. A call that has not joined is answered by its own step.
  - ⚠⚠ **A caller that stops waiting stops nothing, at any moment** (whole-file review). The
    step used to be cancelled when its caller's wait ended. A caller that left between the
    journal write and the first start left a record no transfer ran for until the next
    foreground. The step is now never cancelled on the caller's account: a download whose
    caller left during the catalog fetch still begins. What stops a step is the graph going
    (`alive()`, which now also reads the downloader's own `gone`, set by `tornDown`).
    - ⚠ Not fixed, recorded: `HttpModelCatalogSource.load` wraps its fetch in `runCatching`,
      which swallows a `CancellationException` and answers *no catalog*. Nothing on this path
      cancels that fetch any more; on Android and desktop a cancelled download can report
      *catalog unavailable* instead of its cancellation (`tech-debt.md`).
- One act is not gated, on purpose: removing the journal record **after** the store confirmed a
  commit. The model is installed; the record is then wrong whoever is alive.
- ⚠⚠ **What stays open: the instant between one question and its one act.** `retire` is a flag
  set from the main thread and may take no lock (§14.2), so a check-then-act cannot be made
  atomic with it. Each act that can slip through is one the next graph absorbs:

  | The act that slips through | What the next graph finds | Cost |
  |---|---|---|
  | a transfer started | the transport refuses a ref it already holds (below), and reports it live | none |
  | a transfer cancelled, a delivered file deleted | the artifact missing | it is fetched again |
  | a journal write | the record, or its absence. ⚠ Racing the next graph's own write, one of the two is lost; what the lost one named is swept | a download to start again |
  | a file moved into staging | it staged, and hashes it again | none |
  | the commit | a model its own store, which read the index earlier, does not know of until the process is replaced | a download that fetches again and adopts the files that are there |

- ⚠⚠ **The transport is the one place that refuses a second start of a ref.** Two graphs of
  one process drive one session, and each can decide to start a ref from an answer of
  `liveRefs` that was true when it was given; no check inside a graph closes that.
  `ModelTransferPort.start` says so, and `ModelDownload.start` does it through the ledger above,
  which decides and records an admission in one step, so of two starts of one ref one sees the
  other even when the session's answer to it predates the first task.
- Completions are routed to the **installed** assembly only, so a retired graph hears none.

**What is reported.**
- `download` returns the record `commitStaged` returned, and nothing else.
- `outcomeFor(key)` answers only for the key the last outcome was about: the model id and the
  artifacts' digests. A failure of one catalog entry says nothing about another under the same id.
- A commit the store could not make (`ModelIndexUnreadableException`, an I/O error) is told to
  whoever waits, as the store's exception. The record stays, and the next settle tries again.
- ⚠ A relaunch has nobody waiting. A download that fails there leaves `outcomeFor` in that
  process's memory and nowhere else.
- A caller of `download` always gets an answer: the store's record, a refusal, a failure, a
  cancel, or, when its graph is torn down, `ModelDownloadCancelledException` from the teardown
  (below). ⚠ While the download simply runs, it waits, for as long as the transfers take.
- **`isDownloading` is this process's**, from memory: a call to `download` that has not got
  past the catalog yet, or a download it tracks. Not the journal at every poll: the progress
  stream asks twenty times a second. ⚠ The journal is read **once**, at the first question a
  process is asked, so a download that survived a relaunch is *running* before anything has
  resumed it (*Two operations on one model*).

**Threat model (HR 2).** Nothing moves and nobody new can read anything.
- The requests carry no header, token or identifier: plain GETs of public weights on the model
  CDN, the egress the catalog fetch already makes (IN-D10). On iOS they are made by
  `nsurlsessiond` for Photos' own session.
- The journal holds a catalog entry, digests and the cellular choice, in the container the
  stamp and the verdict already live in (A5 / C4e as amended by PR-3).
- A delivered file is unverified bytes in a private, backup-excluded directory, and nothing
  reads it but the hash. No amendment.

### 14.12 ⚠⚠ Found: the CDN answers a range with the whole text archive, sometimes

**What was seen** [M: 2026-10-11, `models.service.tenshu.app`, the published `coreml` entry]:

| When | Client | `Range: bytes=1000-1999` to the text archive (555 540 480 B) | Vision, vocabulary |
|---|---|---|---|
| The verification before this slice (reported, not re-run here) | — | the first request `200`, every later one `206` | — |
| about 20:55 local | `curl`, HTTP/2 | **`200`, `content-length: 555540480`**, `cf-cache-status: BYPASS`, `accept-ranges: bytes` | `206`, `content-range: bytes 1000-1999/…`, `MISS` |
| the same minute, `bytes=555540480-` (one past the end) | `curl`, HTTP/2 | **`200`, `content-length: 555540480`** (not `416`) | — |
| about 21:05 | JDK `HttpURLConnection`, HTTP/1.1 (`ModelCdnRangeProbeTest`) | `206`, `content-range: bytes 1000-1999/555540480`, `BYPASS` | `206`, `HIT` |

- All three artifacts carry an `ETag` and a `Last-Modified`.
- The text archive is never cached at the edge (`BYPASS` every time; it is over the edge's
  object limit [I]). Every download of it is served from the bucket. ⚠ Nothing to build; it
  bounds what a download costs and how fast it can be, and it is the artifact that misbehaves.
- ⚠⚠ **Why it answers `200` is not known**, nor how often. It is not *only the first request*.
- ⚠ The first two rows downloaded the archive, twice, into `/dev/null`: a `curl` that asks for
  1 000 bytes and is sent 555 MB receives them. The opt-in case reads headers only and drops
  the connection.

**What each downloader does with it.**

| | A resume answered `200` | Pinned by |
|---|---|---|
| `DefaultModelDownloader` (Android, desktop; in-process; sends `Range: bytes=<staged>-` itself) | [C] `fetchArtifact`: staging is aborted and begun again, the digest and the count start at zero, the body is written from offset 0. **Restart, never append** | `aRangeRequestAnsweredWithTwoHundredRestartsInsteadOfAppending` (the in-memory store, since IN-2b); `aResumeAnsweredTwoHundredRestartsTheArtifactFromZeroOverTheRealStore` (new: the real store, whose staging is a file) |
| `BackgroundModelDownloader` (iOS) | It sends **no `Range` and appends nothing**: one plain GET, one whole file back. Whatever the system did inside the transfer, the file is checked whole, size then sha256, before it is staged. A file that is the partial body followed by the whole one is discarded | `whateverTheTransportDeliveredOnlyTheCatalogsBytesAreInstalled` (*the whole body appended to a partial one*) |
| The background `URLSession` itself, resuming a transfer it interrupted | ⚠⚠ **[I], not run.** Apple documents that a download resumes only when the server supports byte ranges and sends a validator, and does not say in so many words what a task does when its conditional range request comes back `200`. That it starts the file again is how HTTP's `If-Range` is meant to work, and is not established here | nothing. §14.16's checklist |

- ⚠ **The cost on the paths that restart:** the text tower again from zero, 555 MB, each time
  the server ignores a resume. On iOS that is inside the system's transfer and invisible to
  the app, except as a bar that goes backwards [I].
- Android and desktop do not download this archive (`onnx` entries, each under the edge's limit).
- ⚠ **A delivered file of another length is a failure worth a retry, not an integrity failure**
  (whole-file review). It was reported as `INTEGRITY_FAILED`, whose meaning is *asking again
  fetches the same bytes*, although a body cut short, or a partial body with the whole one
  after it, is exactly what this section says a retry cures. Now only bytes of the **right
  length** with another digest are `ModelIntegrityException`; another length is a
  `ModelDownloadException` (`TRANSPORT_FAILED`), and the file is discarded either way.

### 14.13 Disk, step by step

Sizes are §3.4's, decimal MB. *On disk* is what this feature holds; free space falls by the
same amount at each step.

**A first install:**

| Step | On disk | Where | Slice |
|---|---|---|---|
| Before; refused below 2 579.8 MB + margin free | 0 | | 5c-1 |
| Transfers running | up to 743.4 | the system's own temporary files, outside the app's control | 5c-1 |
| Each delivered: **moved**, not copied | 743.4 | `inference/downloads/<ref>.delivered` | 5c-1 |
| Hashed | 743.4 | read only | 5c-1 |
| Each adopted into staging: a rename | 743.4 | `inference/models/<id>.<role>.model.part` | 5c-1 |
| Committed: a rename | **743.4** | `inference/models/<id>.<role>.<digest>.model` | 5c-1 |
| Prepare, vision | 1 298.4 | `inference/coreml/<tag>/` and the first-open space | 5c-2 |
| Prepare, text: **the peak** | **2 579.8** | the same | 5c-2 |

- ⚠ The 5c-1 rows never hold an artifact twice **because every hand-over is a rename**. On iOS
  the three directories are in one container, so `moveFile` renames [I: one volume; not
  measured on a phone]. Its fallback is a copy, which would hold the artifact twice for its
  length: +555.5 MB for the text tower.
- ⚠ Between a background delivery and the next foreground the 743.4 MB sit in
  `inference/downloads`, unverified.

**An update** (a newer catalog entry, the older generation installed and prepared):

| Step | On disk | Slice |
|---|---|---|
| Before; refused below the same 2 579.8 MB + margin | 2 579.8 (the old generation, first-open space counted as kept) | 5c-1 |
| New archives committed. The store declares the old archives garbage and does not delete them | **3 323.2** | 5c-1 |
| Old compiled directory deleted **before** the new one compiles | 3 323.2; 2 582.7 if the deletion returns its 740.5 [I] | 5c-2 |
| New prepare: **the peak** | **5 159.6**; 4 419.1 [I] | 5c-2 |
| The next process's sweep of the old archives | 4 416.2; 3 675.7 [I] | the store's |

- **At most one generation's compiled models, deleted before the next one compiles**: built in
  5c-2 (§14.17). The prepare step's first act is a sweep of every compiled directory the install
  record does not name, and it follows every commit (`anUpdateDeletesTheOldCompiledGenerationBeforeItCompilesTheNew`).
  ⚠ Between the commit and that sweep (the same foreground, or the next one when the commit was
  made as Photos left it) the old directories are there under tags no record names; nothing
  serves them.

- ⚠ **An update that keeps an artifact is budgeted from the whole entry's measured ratio**
  (whole-file review, noted, not changed). `additionalNeed` scales `MEASURED_PREPARE_BYTES` by
  the bytes to prepare over the whole download, and the towers do not prepare in proportion
  to their size: an update that replaces the vision tower alone is under-budgeted by about
  91 MB by that arithmetic. It is inside the 256 MiB margin, which is a placeholder
  (§14.14); the per-artifact figures are 5c-2's to measure, with the prepare step itself.

**What can be left behind by 5c-1:**

| After | Left | Until |
|---|---|---|
| A **failed** download (a transfer failed, or delivered the wrong bytes) | the artifacts already staged, up to 743.4 MB in `.part` files | the next attempt adopts them, or a later catalog entry replaces them role by role. ⚠ Nothing else removes them (`tech-debt.md`) |
| A **cancelled** download, or a model deleted under its update | nothing: the staging is dropped with the record (§14.11, *What becomes of a download's bytes*) | — |
| A download the user never returns to | what it has staged, and a journal record whose transfers the session may still finish | the user cancels (5d), deletes the model, or the download completes |
| A delivered file nothing names | the file | the next launch or foreground sweep |
| A directory that lost its backup exclusion | nothing: the purge takes `inference/downloads` with the rest of the tree | — |

### 14.14 Decisions taken in this slice, each with its alternative; deviations; placeholders

| # | Decision | Alternative |
|---|---|---|
| 1 | ⚠⚠ **No new `ModelUnavailable` reason.** The free-space refusal, the unknown free space, the missing transport and the unreadable journal are typed in Kotlin and as `ModelDownloadRefusal`, and reach the wire as `TRANSPORT_FAILED` with their own sentence. 5d's screen is in Photos' process and can read `outcomeFor` directly | **(a)** `INSUFFICIENT_STORAGE` (and `STORE_UNREADABLE`, which `tech-debt.md` already asks for) in `ModelUnavailable.Reasons`: a wire-vocabulary change, a sentence in every client's copy, and older clients read an unknown reason as a generic failure. **(b)** the bytes needed and free as fields of the error: a reshape (HR 9). ⚠ Left to the maintainer: `TRANSPORT_FAILED` is the *retryable* bucket, which is wrong for a full disk only in what the button promises |
| 2 | The cut into 5c-1 and 5c-2 (§14.11) | One PR. The prepare step would have doubled the review |
| 3 | A background relaunch installs no runtime, **and a resident Photos settles nothing while it is in the background**; delivered files wait for the next foreground | Installing the runtime in the background launch and committing there: a model ready before the user returns, and a process that holds the shared replica's lock when it is suspended |
| 4 | An undecodable journal is no journal, and the sweep runs against it (§14.11's table) | The index's rule: refuse JSON of another shape for the life of the process, and do not sweep. It protects a download another build began, and has no reset |
| 5 | Unknown free space refuses | Proceeding, as `CoreMlModelCache.requireSpace` does. The prepare step would then fail after the download on a full phone |
| 6 | An update needs what a first install needs | §3.4 leaves no choice until the deletion experiment |
| 7 | The prepare budget scales with the entry's size | The bare constant, exact only for one export |
| 8 | The first cellular choice stands; the other is refused | Cancelling the transfers and starting them again with the new flags inside `download`: the progress of every artifact lost as a side effect of asking twice |
| 9 | No resume data. A transfer that fails starts its artifact again from zero; the system's own retries inside a transfer are the only resume | `cancel(byProducingResumeData:)` and `downloadTask(withResumeData:)`, with the data journalled. It would matter for a failure the system reports rather than rides out; and it is the path §14.12's `200` would then be the app's to handle |
| 10 | A failed transfer fails the whole download; staged siblings are kept, siblings in flight are cancelled | Leaving the siblings running and re-enqueuing the failed one. A `403` or a `404` would then loop |
| 11 | `allowsConstrainedNetworkAccess` (Low Data Mode) is left at its default, allowed | Setting it false with the other two. D6 names two flags; a user in Low Data Mode on Wi-Fi who presses Download has asked for it. ⚠ Not decided by the maintainer |
| 12 | The session's `timeoutIntervalForResource` is left at the system's default, seven days [D: Apple] | A shorter bound. Nothing measured says which |
| 13 | `UIApplicationDelegateAdaptor` and `handleEventsForBackgroundURLSession`, Drive's precedent | SwiftUI's `.backgroundTask(.urlSession(id))` (iOS 16+), which needs no application delegate. Not the repo's precedent, and not something this slice could run either |
| 14 | `adoptStaged` on the shared `ModelStore`, with a default that refuses | Copying the delivered file through `writeStaged`, which holds 555 MB twice; or exposing the staging path |
| 15 | The journal carries no per-artifact state | A state per artifact. It would be a second record of what the disk already says, and could disagree with it |
| 16 | Progress crosses into Kotlin about once a MiB | Every `didWriteData` |
| 17 | `isDownloading` answers from this process's memory, after one look at the journal | The journal, read at every poll of the progress stream, and true in a graph that will never publish an entry |
| 22 | `DeleteModel` ends a download of that model first, and does not remove a model whose download it cannot end | Removing anyway: the model comes back when the download settles |
| 23 | `DeleteModel` of a model that is only downloading stays `NOT_INSTALLED`; it is not a cancel | Making it cancel. One operation with two meanings, and still no way to cancel without having had an install to delete |
| 18 | Staged bytes that cannot be read are *inconclusive*: left, and nothing fetched in their place | *Missing*: 555 MB fetched again because a read failed |
| 19 | Installed bytes are taken on the record's digest and a stat | Hashing them again: ~740 MB read to rewrite three small fields |
| 20 | An update does not fetch an artifact it shares with what is installed | Fetching every artifact of a new entry, as the in-process downloader does |
| 21 | The shared in-process downloader is left as it is (it re-fetches, and ends with the right record) | Giving it the same reuse. Shared code, Android and desktop, outside this slice |

**Deviations from §3–§5 and from §14.1's row, and why.**
1. **The slice is cut in two** (item 2).
2. **"On relaunch re-hash the delivered file from disk"** (§5): as built, *every* delivered file
   is hashed from disk, relaunch or not. There is no streamed digest on this path at all.
3. **The relaunch that installs is the next foreground launch**, not the background relaunch
   iOS makes for the session's events (item 3). §5's row says "a terminated-app relaunch";
   the test of that name runs a fresh process over the journal, which is what both are.
4. **The check's refusal is not a new wire reason** (item 1).
5. **`DeleteModel` runs inside the wrapper, and evicts nothing yet.** §3.2 has the transition to
   `NoModel` delete the compiled directory; that is the prepare step's sweep, which follows the removal (5c-2, §14.17).
6. **A staging method on the shared store** (`adoptStaged`), which §8 hoped would not be needed.
7. **No `PrivacyInfo.xcprivacy`** although the free-space read gained a caller: §14.1 puts the
   manifest in 5e.

**Placeholders, and what replaces each.**

| Constant | Value | Replaced by |
|---|---|---|
| ⚠⚠ `CoreMlDiskBudget.PLACEHOLDER_MARGIN_BYTES` | 256 MiB | The spread of the prepare step's free-space readings over more than one phone and OS build, plus what a phone must keep free for iOS to go on working through a prepare. Neither exists: one phone, one OS build, two readings a tower that agree within 1.3 MB (§3.4) |
| `CoreMlDiskBudget.MEASURED_DOWNLOAD_BYTES`, `MEASURED_PREPARE_BYTES` | 743.4 MB, 1 836.4 MB | Not placeholders: §3.4's measurement [M]. ⚠ They move with the artifact and are re-measured by the harness for each export |
| `PhotosModelDownloads.LIVE_REFS_TIMEOUT_MS` | 5 s | ⚠ A choice (Drive's value for the same question). A reading of how long `getAllTasks` takes on a phone with transfers in flight |
| `ModelDownload`'s progress step | 1 MiB | A choice |

### 14.15 Guards, each removed once and seen red

As §14.3: one mutation at a time, the affected classes run, the red cases read from result
files written by that run, the file restored and its sha256 compared.

[M: 65 on the JVM (`:inference-service-proxy:jvmTest`: `BackgroundModelDownloaderTest`,
`ModelDeliveryForATransportThatOutlivesTheProcessTest`, `ModelOperationsTest`, `ModelDownloaderTest`),
18 on the simulator (`:tenshu-sdk-ios:iosSimulatorArm64Test`: `PhotosModelDownloadTest`,
`PhotosOnDeviceAiTest`); all of them run again on the tree as it was first committed.] 62 and 18 went
red; **three survived** and are listed under the tables. Every file was restored.
⚠ After the two reviews the retirement rows were replaced, twice. As they stand they are
fourteen mutations run on the JVM on the tree of the second fix, all red, plus the two entry
checks, which survive as the table says; and nine on the simulator (the ledger's rules and the
teardown's call), under the simulator table. The other rows were **not** run again after
those changes.

**On the JVM:**

| Guard | Removed by | Red |
|---|---|---|
| ⭐ The download is journalled | no write | `aCancelWhoseRecordCouldNotBeRemovedCancelsNothing`, `aCommitTheStoreCouldNotMakeTellsWhoeverWaitsAndKeepsTheRecord` and 33 more |
| ⭐ …**before** any transfer is started | the transfers started first | `aJournalThatCannotBeWrittenStartsNoTransfer` |
| ⭐ A delivered file is the catalog's bytes, by sha256 from disk | no comparison | `aCorruptedFileIsDiscardedEvenWhenTheFailureCannotBeRecorded`, `aFileCorruptedWhileTheAppWasDeadIsDiscardedAtTheRelaunchAndNotCommitted` and 1 more |
| …and by size | neither check | `aCorruptedFileIsDiscardedEvenWhenTheFailureCannotBeRecorded`, `aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt` and 2 more |
| The size is checked before the bytes are read | no size check, the digest left | ⚠ **survived** (below) |
| ⭐ A file with the wrong digest is deleted where it is found | left for the failure path to delete | `aCorruptedFileIsDiscardedEvenWhenTheFailureCannotBeRecorded` |
| …and one of the wrong size | the same | `aCorruptedFileIsDiscardedEvenWhenTheFailureCannotBeRecorded` |
| ⭐ A delivered file that could not be read is not a corrupt one | read as corrupt | `aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt` |
| …and is not deleted | deleted and fetched again | `aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt` |
| ⭐ The free-space check | no comparison | `aRefusedDownloadIsNotRunning`, `anUpdateNeedsTheSameAsAFirstInstall` and 2 more |
| Unknown free space refuses | read as unlimited | `freeSpaceThatCannotBeReadIsARefusalNotAGuess` |
| The check comes before the journal | the record written first | `belowTheAdditionalNeedTheDownloadIsRefusedBeforeAnythingIsFetched` |
| What is staged is not budgeted for again | the whole download budgeted | `whatAnEarlierAttemptStagedIsNeitherFetchedNorBudgetedAgain` |
| The margin is added | 0 | `theMeasuredDownloadNeedsTheMeasuredTwoPointFiveEightGigabytesAndTheMargin` |
| ⭐ The prepare step is budgeted before the download | the download alone | `theMeasuredDownloadNeedsTheMeasuredTwoPointFiveEightGigabytesAndTheMargin` |
| The scaled budget rounds up | rounding down | `theBudgetScalesWithTheArtifactAndNeverRoundsDown` |
| ⭐ The cellular choice is the caller's | journalled as allowed | `aDownloadIsJournalledBeforeAnyTransferAndInstalledOnceEveryArtifactIsVerified`, `aDownloadStartedOnWifiWaitsOnCellularAndIsNotFailed` and 3 more |
| ⭐ `ModelDownloader.download` is Wi-Fi only | allowing cellular | `theDownloadEveryOtherCallerKnowsIsWifiOnly` |
| The request carries the journalled choice | always refusing | `aDownloadThatAllowsCellularProceedsThere`, `aRelaunchStartsALostTransferAgainWithTheChoiceThatWasJournalled` and 1 more |
| ⭐ …and refuses cellular by default | always allowing | `aDownloadStartedOnWifiWaitsOnCellularAndIsNotFailed`, `aSecondCallJoinsTheRunningDownloadAndAnotherCellularChoiceIsRefused` and 2 more |
| The first choice stands | joining whatever the choice | `aSecondCallJoinsTheRunningDownloadAndAnotherCellularChoiceIsRefused` |
| ⭐ An unreadable journal stops every step | read as empty | `anUnreadableJournalStartsNothingDeletesNothingAndIsReadAgain` |
| ⭐ `null` for a file that is there is unreadable | dropping the stat | `anUnreadableJournalStartsNothingDeletesNothingAndIsReadAgain` |
| A journal read that throws is unreadable | answering *absent* | `anUnreadableJournalStartsNothingDeletesNothingAndIsReadAgain` |
| A journal that is not this build's is no journal | refusing it | `aJournalThatIsNotThisBuildsRecordIsNoJournalAndIsSweptAndWrittenOver` |
| ⭐ *Could not tell* what is live is not *none* | read as none | `whenTheTransportCannotSayWhatItHoldsNothingIsStartedAgainOrCancelled` |
| ⭐ A transfer the transport holds is not started again | no check | `aNewerCatalogEntryReplacesTheDownloadThatWasRunning`, `aRelaunchMidTransferStartsNothingTwiceAndStartsAgainOnlyWhatTheTransportLost` and 2 more |
| ⭐ The sweep cancels only what no record names | cancelling every live transfer | `aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt`, `aFailureWhoseRecordCouldNotBeRemovedIsNotReported` and 4 more |
| ⭐ …and deletes only what no record names | deleting every delivered file | `aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt` |
| The sweep runs at a resume | never | `aJournalThatIsNotThisBuildsRecordIsNoJournalAndIsSweptAndWrittenOver`, `anUnreadableJournalStartsNothingDeletesNothingAndIsReadAgain` and 1 more |
| ⭐ The commit runs inside `ModelStoreChanges` | committing beside it | `aGraphRetiredWhileTheCommitWaitsForItsTurnInstallsNothing`, `theCommitRunsInsideTheStoreChangeWrapper` |
| ⭐ A retired graph begins no download | no check in `begin` | `aRetiredGraphNeitherStagesNorInstallsNorCleansUp` |
| ⭐ **No transfer is started after a teardown** | `Acts.port().start` not asking | `aGraphRetiredAfterItLookedAtItsStagingStartsNothing` |
| ⭐ …nor the store's staging looked at (it writes a marker) | `Acts.beginStaging` not asking | `aGraphRetiredWhileTheTransportIsAskedWhatItHoldsStartsNothing`, `aGraphRetiredWhileItBeginsADownloadJournalsAndStartsNothing` |
| ⭐ **No staged bytes dropped after a teardown under the hash** (round 2) | `Acts.abortStaging` not asking | `aGraphRetiredWhileItHashedStagedBytesDropsNothing` |
| ⭐ No journal write after a teardown | `Acts.writeJournal` not asking | `aGraphRetiredWhileItBeginsADownloadJournalsAndStartsNothing`, `aRetiredGraphCancelsNothing` |
| ⭐ No file deleted after a teardown | `Acts.delete` not asking | `aGraphRetiredUnderAStepDeletesAndCancelsNothing` |
| ⭐ No transfer cancelled after a teardown | `Acts.port().cancel` not asking | `aGraphRetiredUnderAStepDeletesAndCancelsNothing` |
| A teardown mid-settle stops the moves | `Acts.adoptStaged` not asking | `aGraphRetiredWhileItSettlesMovesNothingMore` |
| ⭐ …and the commit, before the wrapper is entered | no question in `commit` | `aGraphRetiredOnceEverythingIsStagedDoesNotCommit` |
| ⭐ …and inside it, last | `Acts.commitStaged` not asking | `aGraphRetiredWhileTheCommitWaitsForItsTurnInstallsNothing` |
| ⭐ **Whoever waits on a torn-down graph is told** (round 2) | `tornDown` completing nobody | `whoeverWaitsOnAGraphThatIsTornDownIsToldAtOnceAndTheDownloadIsLeftAlone`, `aGraphRetiredWhileTheCommitWaitsForItsTurnInstallsNothing` |
| …and its progress entries end | left tracked | `whoeverWaitsOnAGraphThatIsTornDownIsToldAtOnceAndTheDownloadIsLeftAlone` |
| A step that meets the teardown tells them too: `resume`; `settleFinished` | not calling `tornDown` there (two runs) | `aTeardownThatLandsUnderAResumeTellsWhoeverWaits`; `aGraphRetiredWhileTheCommitWaitsForItsTurnInstallsNothing` |
| The entry checks of `resume` and `settleFinished` | each removed | ⚠ **survive**, by design: every act behind them asks for itself |
| A commit whose record was not removed is not fetched again | not asking the store | `aCommitWhoseJournalWriteDidNotLandIsNotFetchedAgain` |
| ⭐ An outcome is keyed | answering for any key | `anOutcomeSaysNothingAboutAnotherDownload` |
| ⭐ A failure is reported only once its record is gone | reporting first | `aCorruptedFileIsDiscardedEvenWhenTheFailureCannotBeRecorded`, `aFailureWhoseRecordCouldNotBeRemovedIsNotReported` |
| ⭐ Nothing is cancelled or deleted before the record is gone | releasing first | `aCancelWhoseRecordCouldNotBeRemovedCancelsNothing`, `aFailureWhoseRecordCouldNotBeRemovedIsNotReported` |
| A name that is not a ref is never a path | no `isRef` | `aTransferNoRecordNamesIsDiscardedWhenItReports` |
| A delivery no record names is discarded | kept | `aTransferNoRecordNamesIsDiscardedWhenItReports` |
| A transfer that ends without a file fails the download | ignored | `aTransferThatEndsWithAnErrorStatusFailsAsTransportAndInstallsNothing`, `anOutcomeSaysNothingAboutAnotherDownload` and 1 more |
| An artifact a newer entry shares is neither cancelled nor started twice | released with the rest | `aNewerCatalogEntryReplacesTheDownloadThatWasRunning` |
| ⭐ All three or none | committing on any | `aCommitWhoseJournalWriteDidNotLandIsNotFetchedAgain`, `aDownloadIsJournalledBeforeAnyTransferAndInstalledOnceEveryArtifactIsVerified` and 20 more |
| ⭐ Bytes an earlier process staged are hashed again | trusted by length and marker | `bytesAnEarlierProcessStagedAreHashedAgainAndWrongOnesAreDropped` |
| Siblings that arrived whole are staged before a failure is concluded | stopping at the corrupt one | `aFileCorruptedWhileTheAppWasDeadIsDiscardedAtTheRelaunchAndNotCommitted` |
| ⭐ No transport is a refusal | a transport that does nothing | `withNoTransportTheDownloadIsRefusedAndNothingElseIsTried` |
| ⭐ Staged bytes that could not be read are not fetched again | read as missing | `stagedBytesThatCannotBeReadAreNotFetchedAgain` |
| A failure report about an artifact that is staged fails nothing | failing on every report without a file | `aLateFailureReportForAnArtifactThatIsStagedFailsNothing` |
| `isDownloading` is true from the call, before the catalog has answered | only once tracked | `aDownloadIsRunningFromTheMomentItIsAskedForAndOnlyInTheProcessThatWorksOnIt` |
| …and false again when the call is refused | never counted down | `aDownloadIsJournalledBeforeAnyTransferAndInstalledOnceEveryArtifactIsVerified`, `aDownloadIsRunningFromTheMomentItIsAskedForAndOnlyInTheProcessThatWorksOnIt` and 3 more |
| A resumed download is tracked: it has a bar and is *running* | not tracked | `aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt`, `aDownloadIsRunningFromTheMomentItIsAskedForAndOnlyInTheProcessThatWorksOnIt` and 1 more |
| The bar starts from what is held | starting at 0 | ⚠ **survived** (below) |
| Progress is published after a settle | not published | `aRelaunchMidTransferStartsNothingTwiceAndStartsAgainOnlyWhatTheTransportLost` |
| `adoptStaged` drops the old marker first | kept | ⚠ **survived** (below) |
| ⭐ …and what was staged, before the move | kept | `aMoveThatLeftNothingIsNotStagedUnderTheNewDigest` |
| …and writes the new marker | none written | `aFileCorruptedWhileTheAppWasDeadIsDiscardedAtTheRelaunchAndNotCommitted`, `adoptingMovesTheDeliveredFileIntoStagingAndTheNextAttemptResumesFromIt` and 4 more |
| …and refuses over an index it cannot read | not reading it | `aStoreWhoseIndexCannotBeReadAdoptsNothing` |
| ⭐ A contributed downloader is the graph's downloader | ignored | `aGraphThatContributesADownloaderGetsItAndNotTheInProcessFetch` |
| The wrapper reaches the operations | dropped by the components | `aGraphThatContributesADownloaderGetsItAndNotTheInProcessFetch` |
| ⭐ `DeleteModel` runs inside the wrapper | removing beside it | `deletingRunsInsideTheStoreChangeWrapper` |
| ⭐ The in-process downloader restarts on a `200` to its `Range` | appending | `aRangeRequestAnsweredWithTwoHundredRestartsInsteadOfAppending`, `aResumeAnsweredTwoHundredRestartsTheArtifactFromZeroOverTheRealStore` |

**On the JVM, the same bytes under a newer entry** (round 3; eleven mutations, all red, on the tree of that fix):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ The record becomes the newer entry when the bytes are the same | joining the old record | `aNewerEntryOverTheSameBytesMidDownloadKeepsTheTransfersAndInstallsTheNewerRecord`, `aNewerEntryOverBytesThatAreAllStagedFetchesNothingAndInstallsTheNewerRecord` |
| …on disk, not only in this step's memory | no journal write | the first of those |
| ⭐ Installed under other metadata is not *done* | comparing digests alone | `aNewerEntryOverBytesThatAreInstalledRewritesTheRecordAndFetchesNothing` |
| Each of version, label set, licence alone | each comparison removed (three runs) | `eachOfTheThreeFactsTheStoreKeepsIsEnoughToRewriteTheRecord`. ⚠ Survived first: the case moved all three at once |
| ⭐ Installed bytes are not fetched again | `installedHolds` not asked | `aNewerEntryOverBytesThatAreInstalledRewritesTheRecordAndFetchesNothing`, `anUpdateNeedsTheSameAsAFirstInstall` |
| A record whose file is gone is not the bytes | no stat | `anInstallRecordWhoseFileIsGoneIsNotTakenForTheBytes`. ⚠ Survived first: no case removed a file |
| A rewrite asks for no free space | budgeted like a download | `aNewerEntryOverBytesThatAreInstalledRewritesTheRecordAndFetchesNothing` |
| Installed bytes are not budgeted | the whole entry budgeted | the same, and `anUpdateNeedsTheSameAsAFirstInstall` |
| ⭐ The key holds what the store keeps | digests alone | `aNewerEntryOverTheSameBytesMidDownloadKeepsTheTransfersAndInstallsTheNewerRecord` and 13 more |

**On the JVM, two operations on one model** (round 4; on the tree of that fix, with the
twelve gate and teardown rows above run again and red):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ Nothing is changed before a download is admitted | the acting probe (`beginStaging`) in `begin` | `aNewerEntryThatIsRefusedTouchesNothingOfTheDownloadThatIsRunning` |
| ⭐ A removal ends the model's download first | not dropping the record | `deletingAModelEndsTheUpdateThatWasDownloadingSoNothingBringsItBack` |
| ⭐ …and does not run over a journal it could not read | read as empty | `aModelWhoseDownloadCannotBeEndedIsNotRemoved` |
| …nor on a graph that was torn down | not asking | the same |
| ⭐ `DeleteModel` hands the removal to the downloader | removing beside it | `deletingIsHandedToTheDownloaderWhichEndsADownloadOfThatModelFirst` |
| The factory gives the operation the downloader | not passed | the same |
| ⭐ A relaunched process says *running* before it has resumed | no look at the journal | `aDownloadIsRunningFromTheMomentItIsAskedForAndOnlyInTheProcessThatWorksOnIt` |
| The look happens once | repeated at every question | ⚠ **survives** (§14.16) |

**Round 17** (on the branch merged with master; the order of an ending and what follows it):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A download waits for the endings asked for before it | begins at once | `aCancelPreemptedBeforeItsStepEndsTheDownloadItWasAskedToEndAndNotTheNextOne`, `aRemovalPreemptedBeforeItsStepRemovesTheModelAndTheDownloadAskedForAfterItInstallsIt` |
| An ending that is over is not waited for | never taken off | `anEndingThatFailsOrWhoseCallerLeftHoldsUpNothing` and six more (for `cancel`; the same line in `withoutDownloadOf` was not mutated) |

Run again on this tree, both red: *a call asked for before an ending begins* (round 14) and
*an ending answers every waiter* (the whole-file review).
⚠ The preemption is reached through a seam in the downloader (`betweenAnEndingAndItsStep`,
nothing in the product): no fake can stand between two lines of a coroutine.

**Round 16** (the two returns that still left a caller waiting):

| Guard | Removed by | Red |
|---|---|---|
| A transport that could not say is said | silent | `aTransportThatDoesNotSayWhatItHoldsIsSaidAndNothingIsStartedOrEnded` |
| A resume that cannot read tells whoever had joined | silent | `aResumeThatCannotReadTheJournalTellsWhoeverHadJoinedADownload` |

**Round 15** (the first-read sweep, answered by the store's lock above the round 14
table's second half, and the delivered file):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A delivery is asked again after the move | kept whatever happened | `aCancelThatLandsBeforeTheMoveLeavesNoDeliveredFile`, `aDeliveryIsAskedForBeforeTheMoveAndAgainAfterIt` |
| A delivery is asked before the move | always let through | `aDeliveryIsAskedForBeforeTheMoveAndAgainAfterIt` |

**Round 14** (the call that has not journalled, and the index another store wrote):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A call asked for before an ending does not begin | begins | `aModelRemovedWhileItsUpdateWasStillFetchingTheCatalogStaysRemoved`, `aDownloadCancelledWhileItWasStillFetchingTheCatalogDoesNotBegin` |
| A cancel is counted | not counted | the second |
| A removal is counted | not counted | the first |
| ⭐ A step reads the index again | the memo | `aSuccessorGraphSeesWhatTheGraphBeforeItCommitted`, `aGraphTornDownDuringItsCommitDoesNotEraseTheRecordItsSuccessorWrites` |
| ⭐ A commit writes from the disk's index | from the memo | `twoStoresOverOneIndexNeverWriteBackWhatTheyRemembered`, and the second of the row above |
| A removal does | from the memo | `twoStoresOverOneIndexNeverWriteBackWhatTheyRemembered` |
| A failed re-read forgets the memo | falls back to it | `aReReadOfTheIndexThatFailsForgetsWhatWasRemembered` |

After round 14 (the store's own guards; local when written, pushed with round 15's answer):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A commit holds the process's lock to its index write | not held | `aSecondStoresFirstReadDuringACommitSweepsNothingOfThatCommit` (two threads, JVM) |
| ⭐ A read from storage holds it | not held | the same |
| ⭐ A memo is not served once any store has written | served | `aReaderOnASecondStoreSeesWhatTheFirstCommittedWithoutBeingTold` |
| A failed read forgets the memo | kept | `aReReadOfTheIndexThatFailsForgetsWhatWasRemembered` |

⚠ With the counter in, *a step reads the index again* **survived**: the store had made it
redundant. It was removed (`ModelStore.refresh` and the step's call), not left as a guard
nothing proves. *A removal writes back what it remembered* survives too and is equivalent now:
the read it is replaced by notices a stale memo itself.

**Round 13** (one lock for the process):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ Every graph's steps run under the process's lock | each graph its own (as it was) | `aGraphTornDownDuringItsCommitDoesNotEraseTheRecordItsSuccessorWrites`, `twoGraphsNeverInterleaveOverTheOneJournal` |

⚠ The first of the two cases **passed with a lock for each graph** when it was first written:
the successor was started from inside the commit on the test dispatcher, which queued it until
the old step had ended. It is started undispatched now, and is red without the fix.

The retirement tables of rounds 1, 2 and 6 and the gates were run again on this tree: every
mutation whose text still applies is red, but the commit's own question inside the wrapper,
which survives as before (the wrapper's helper asks first).

**Round 12** (a failed read is not an empty one, and no caller is left waiting):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ Unreadable at the second read tells the caller | silent | `aJournalThatCannotBeReadWhenItIsReadAgainIsNotARecordThatIsGone` |
| ⭐ An unreadable journal is not an empty one | read as empty | the same and five more |
| Unreadable at the report's first read tells the caller | silent | `aReportThatMeetsAnUnreadableJournalTellsWhoeverWaitsAndKeepsWhatWasDelivered` |
| A failure that cannot read the journal tells the caller | silent | `aFailureThatCannotReadTheJournalToEndItsRecordTellsWhoeverWaits` |
| A failure whose record stays tells the caller | silent (as it was) | `aFailureWhoseRecordCannotBeRemovedTellsWhoeverWaitsAndKeepsTheRecord` and two more |
| Unread bytes with nothing to wait for tell the caller | silent | `bytesThatCannotBeReadWithNothingLeftToWaitForAreSaidAndKept` |
| ⭐ A marker that cannot be read deletes nothing | read as another digest's | `aStagingMarkerThatCannotBeReadIsNotAMarkerForAnotherDigest` |

**Round 11** (a build that offers no model, met by what an enabled build left):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ Every listed task is cancelled when the model is not offered | none named | `aBuildThatOffersNoModelCancelsEveryTaskAndLetsNoneSpeak` |
| …and none when it is | all named | `aBuildThatOffersTheModelCancelsNothingOnThatAccount` |
| Nothing is held when it is not offered | holds as usual | the first |
| No start is admitted | admitted | the first |
| ⭐ (simulator) The delivered files are deleted | left | `aBuildThatOffersNoModelDeletesWhatWasDeliveredAndLeavesTheJournalForABuildThatDoes` |
| (simulator) A phone that never downloaded has nothing left behind | always *left behind* | the same |

**The whole-file review** (an independent read of every file at `52bf2f5`, answered
together with round 10). Six findings; all six were right.

| # | Found | What it was | Now |
|---|---|---|---|
| 1 | ⚠⚠ Stale staging renamed onto an installed, verified artifact | `commitLocked` judged a resident file *whole* against the length of whatever was in staging. Staging left by an update to another digest (failed, or taken over) had another length, so the resident file read as broken and the stale bytes were moved onto its path: the wrong bytes under the right sha256 in the index. The downloader left that staging there, because it marked an installed-held role without looking at its staging | **Store:** a resident file is judged against the record's size; what is not adopted is promoted only when its marker names the committed digest and the file is there; otherwise `ModelNotCommittableException`, before anything is declared or renamed. **Downloader:** a role the install record holds has its staging dropped when the settle marks it; a not-committable commit forgets everything it had verified. ⚠ **Android and desktop:** the in-process downloader begins every role for the committed digest and fetches every role, so its commits are unchanged; the one new behaviour is that a commit with nothing staged and nothing resident now throws instead of writing a record that names absent files |
| 2 | A start decided on a stale picture is admitted after the holder ended; live refs from the session's list | Round 10 kept a reported transfer as holder until it was settled. After the settle the ref was free, and a start asked for *before* it, answered by the session only then, was admitted: a staged artifact fetched again. And the list named a task whose cancel had not reached it | **The ledger is the one authority.** `admit` refuses a start asked for before a transfer of its ref was acted on while it still held the ref. `liveRefs` is the refs somebody holds. ⚠ Round 10 did **not** close this |
| 3 | An ending completes every waiter under the model id | above, *an ending answers the calls that had joined* | waiters carry `joined` |
| 4 | A caller that stops waiting can stop the download between the journal and the first start | above | the step is never cancelled for its caller |
| 5a | A stale **success** report does what round 10's failure did | T1 delivers; its report waits behind a cancel and the next download, which starts T2 under the same ref. Acted on, it found no file (the cancel took it) and failed the new download with "status 200 and delivered nothing". ⚠ Round 10 did **not** close this: a cancel ends the hold at once, so T2 was admitted | The report is the ledger's own object (`ModelTransferReport`), and the downloader asks `stands()` **when it acts**, under the lock its cancels run under. Nothing keys on the status or the error |
| 5b | Unreadable staging failed a download | In the nothing-delivered branch an artifact whose staging could not be read was not in `verified`, and the download was failed: on a failed read (decision 18) | `unread`: the refs whose bytes could not be read at the last look. Nothing is concluded |
| 6 | *Installed* answered without a stat | above, at `installedHolds` | the shortcut stats every file |

The ledger moved to `:inference-service-proxy` `commonMain` (`ModelTransferLedger`; the umbrella
keeps a subclass for the name Swift sees), so it and the downloader are tested **together**, on
the JVM and the simulator. `FakeTransport` is now that ledger over a session of tasks, driven
by the calls the Swift makes: a start is asked and answered separately, a transfer ends in the
session and is called back and reported separately, and reports enter through
`transferFinished`. The simulator-only race test and its second mirror of the Swift are gone.

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A resident file is judged by the record's size | by the staging's (as it was) | `theStoreNeverRenamesStagingOfAnotherDigestOntoAnInstalledArtifact` |
| ⭐ Only staging whose marker names the committed digest is promoted | marker not asked | `theStoreCommitsNothingWhenAnArtifactIsStagedForAnotherDigest` |
| …and whose file is there | not asked | `theStoreCommitsNothingWhenAnArtifactWasBegunAndNeverStaged` |
| ⭐ A role the install record holds drops its staging | left | `stagingLeftForAnotherDigestIsDroppedOnceTheInstalledArtifactIsWhatIsWanted` |
| …and is marked by the settle, not at admission | marked at admission | the same |
| A not-committable commit forgets what was verified | kept | `aCommitWhoseFilesAreGoneVouchesForNothingAndTheArtifactsAreFetchedAgain` |
| ⭐ A start asked for before its ref's transfer was acted on is dropped | admitted | `aStartAskedForBeforeATransferOfItsRefWasActedOnIsDropped`, `aStartDecidedBeforeTheTransferReportedIsNotMadeOnceItsBytesAreStaged` |
| …only by a transfer that still held the ref | by any | `aReportThatWaitedBehindACancelOfItsRefNoLongerStands` |
| ⭐ Live is who holds a ref | the list and the holds | `whatIsLiveIsWhoHoldsARefAndNotWhatTheSessionLists` |
| … | the list alone | the same, and `aFailureReportedBehindAResumeIsTheEndOfItsOwnDownloadAndStartsNoReplacement` |
| ⭐ A report is asked whether it stands when it is acted on | not asked | `aSuccessReportedBehindACancelAndTheNextDownloadIsNotTheNextDownloads` |
| …and answers from the hold | always *stands* | the same, and the ledger's |
| Unreadable staging concludes nothing | failed | `aReportOfNothingDeliveredFailsNoDownloadWhoseStagedBytesCouldNotBeRead` |
| ⭐ An ending answers the joined calls only | every call | `aCallThatAskedAfterACancelIsNotToldOfTheCancelAndItsDownloadBegins`, `aCallThatAskedBeforeAFailureIsNotToldOfTheFailureAndItsDownloadBegins` |
| ⭐ The step is not cancelled with its caller | cancelled (as it was) | `aCallerThatLeavesBetweenTheJournalAndTheFirstStartStopsNothing`, `aCallerThatLeavesWhileTheCatalogIsFetchedStopsNothing` |
| A graph that is gone acts on nothing, by its own flag | the injected flag alone | `aCallerIsReleasedByATeardownWhereverItsCallIsWaiting` |
| *Installed* needs every file | the record alone | `anInstallRecordWhoseFileIsGoneIsNotTakenForTheBytes` |
| Another length is not an integrity failure | integrity | `whateverTheTransportDeliveredOnlyTheCatalogsBytesAreInstalled` |

Eighteen mutations on the JVM (the table above; every one red) and two on the simulator: with
the foreground check removed
`theSessionSwiftInstallsIsWhatTheGraphDrivesAndWhatItReportsReachesTheInstalledRuntime` is red;
⚠ **the flag's `false` default survives**: every case sets it back, so only a fresh process
would see it.

**Every earlier table was run again on this tree** (rounds 0 to 10 and the gates; the ledger's
tables on the JVM now). Of 180 mutations, 84 were red, 87 no longer apply as text (the lines
they changed were rewritten in rounds since, or in this one: the waiters, `transferFinished`,
`liveRefs`, the commit), one no longer compiles, and eight survive:
- ⚠ **two that were red before this round, each now behind a second guard of this round's:**
  *the install record compared by a list of fields* (a role that moved now fails the per-role
  stat first, so the whole-record comparison is the only guard for a field no case changes),
  and *tracked on a graph that is gone* (a step on such a graph now stops at `alive()` before
  it tracks);
- the entry check of `download` on a retired graph, the commit's own question inside the
  wrapper, and the first look at the journal being repeated: every act behind them asks again;
- `adoptStaged` leaving the old marker: survives on this tree; whether it was red before was
  not established;
- one of the iOS graph (`teardown tells no waiter`) that the JVM does not compile, which is
  not a result. ⚠ The simulator tables that mutate `PhotosOnDeviceAi` and
  `PhotosModelDownloads` were **not** run again.

**Round 10** (on the tree of that fix, pushed together with the review round above):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A reported task keeps its ref until its report is settled | freed when it completes | `aFailureReportedBehindAResumeIsTheEndOfItsOwnDownloadAndStartsNoReplacement` (downloader and ledger together; it was a simulator-only case with its own mirror of the Swift when this round was written), `aReportedTaskKeepsItsRefUntilItsReportIsSettled` |
| ⭐ What Kotlin is told is live includes the unsettled | the session's list alone | the same two |
| A settled report frees its ref | not freed | `aReportedTaskKeepsItsRefUntilItsReportIsSettled` |
| A cancelled completion is not reported | reported | `aCompletionThatIsNobodysIsNotReportedAndHoldsNothing` |
| A cancel ends an unsettled hold | it outlives the cancel | `aCancelAskedForWhileAReportWaitsEndsTheHoldAllTheSame` |
| ⭐ (JVM) Every report is acknowledged, also when settling throws | only when it went through | `everyReportIsAcknowledgedOnceItHasBeenActedOnWhateverThatWas`. ⚠ Survived first: no case made the settling throw |
| …at all | never | the same |

One survives: `PhotosModelDownloads.transferFinished` answering *taken* with no downloader.
It cannot be reached: the runtime answers *not taken* first, for every graph without one.

**Round 9** (on the tree of that fix):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A restart asks whether there is room | not asked | `aLostTransferIsNotStartedAgainOntoADiskThatHasFilledUp` |
| …counting what is still in flight | only what it starts | the same |
| Admission asks, through the same function | not asked | `belowTheAdditionalNeedTheDownloadIsRefusedBeforeAnythingIsFetched` and four more |
| Unknown free space is no room, at both | read as room | `freeSpaceThatCannotBeReadIsARefusalNotAGuess`, and the first |
| ⭐ A refused restart is said | no outcome | the first, `aLostTransferWithNoTransportToStartItOnIsSaidAndNothingIsEnded` |
| No transport at a restart is said | silent | the second |
| The bar is restarted with a new record's total | kept | `theBarIsTheRecordsOwnAfterANewerEntryTakesOver` |
| ⭐ (simulator) A new task does not inherit its ref's last progress figure | inherited | `progressIsThrottledPerTaskSoARetryIsHeardFromItsFirstMebibyte` |
| A stale task's progress is not passed on | passed on | the same |
| Progress is passed on once a step | every tick; the figure not kept (two runs) | the same |

**Round 8** (on the tree of that fix):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ The journal is rewritten whenever the record differs, not when a key does | the key compared | `anArtifactTheCatalogMovedIsFetchedFromItsNewPlaceAfterARelaunchToo` |
| ⭐ The first look reads and tracks under the lock | no lock | `aRecordThatEndsWhileTheFirstQuestionReadsTheJournalIsNotLeftTracked`. ⚠ Survived first: no case ended a record inside the read |
| A record that replaced another leaves none of its refs tracked | no pruning | `aNewerCatalogEntryReplacesTheDownloadThatWasRunning` |
| Every step tracks what it read before it waits | `resume` not tracking | `theFirstQuestionNeverWaitsForAStepAndTracksNothingBesideIt`, `aRelaunchMidTransferStartsNothingTwiceAndStartsAgainOnlyWhatTheTransportLost` |
| The catalog is fetched outside the lock | inside it | `theCatalogIsFetchedWithoutHoldingUpEveryOtherStep` |
| Nothing is tracked on a graph that is gone | tracked all the same | `whoeverWaitsOnAGraphThatIsTornDownIsToldAtOnceAndTheDownloadIsLeftAlone`. ⚠ Survived first: no case had a step read the journal after the teardown |

⚠ Not mutated: that `isDownloading` does not *wait* for the lock. Making it wait cannot be
written in a function that does not suspend, short of spinning, which hangs the suite and
proves nothing; `theFirstQuestionNeverWaitsForAStep…` asserts the answer came at once.

**Round 7** (on the tree of that fix):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ `DeleteModel`'s failures are typed | its body run outside `typed` | `everyFailureHasTheSameTypedReasonWhicheverOperationMeetsIt` and the two store-refusal cases |
| `InstalledModels`' are | the read run outside it | the first of those |
| `DownloadModel`'s are, through the same function | the call run outside it | the same, `eachDownloadFailureKeepsItsOwnReason` and two more |
| A failure with no reason of its own is still typed | no last row | `eachDownloadFailureKeepsItsOwnReason`, and the first |
| ⭐ A dropped record's staging is dropped | kept | `aCancelledDownloadLeavesNothingStagedAndAFailedOneKeepsWhatArrived`, `deletingAModelLeavesNothingStagedOfTheUpdateThatWasDownloadingNotEvenUnderANewRole` |
| ⭐ …for a delete, every role of the update | kept as for a retry | the second |
| …for a cancel | kept as for a retry | the first |
| A role a newer entry does not have is dropped when it takes over | none dropped | `aNewerEntryThatTakesOverDropsTheStagingOfARoleItNoLongerHas` |
| A failure keeps what arrived | dropped | `whatAnEarlierAttemptStagedIsNeitherFetchedNorBudgetedAgain`, `aFileCorruptedWhileTheAppWasDeadIsDiscardedAtTheRelaunchAndNotCommitted` and two more |
| ⭐ (simulator) A task never seen before does not speak over a holder | *unknown speaks* | `anEarlierTaskThatEndedBeforeTheSessionWasAskedDoesNotSpeakOverItsReplacement` |
| A task takes a ref only when it is free | taking a held one | the same, `ofTwoEarlierTasksForOneRefOnlyTheFirstSeenSpeaks` |
| …and never after a cancel | taking it | `aTaskFirstSeenAfterACancelHoldsNothing` |
| ⭐ Only the holder speaks | any known task of the ref; a holder a cancel was asked for (two runs) | four cases; three cases |
| A listed task is known from the listing | not seen | `ofTwoEarlierTasksForOneRefOnlyTheFirstSeenSpeaks`. ⚠ Survived first: the case asked the two in the order they were listed |
| …and from the answer to a start | not seen | `aTaskOfAnEarlierProcessHoldsItsRefUntilACancelIsAskedFor` |
| An earlier process's task is not returned for an immediate cancel | returned | the same |

**Round 6** (on the tree of that fix):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A store-changing block is run only after asking **inside** the wrapper, after its wait | the helper not asking | `aGraphTornDownWhileTheRemovalWaitsForItsTurnRemovesNothing` (with a download to end first, and with none) |
| ⭐ The removal goes through that helper | the wrapper called directly | the same |
| …and inside the wrapper at all | the block run bare | the same, and `aModelWithNoDownloadIsSimplyRemoved` |
| The interface's default still wraps (Android, desktop) | the removal run bare | `deletingRunsInsideTheStoreChangeWrapper` |
| The operation hands the removal over | removing bare | `deletingIsHandedToTheDownloaderWhichEndsADownloadOfThatModelFirst`, `deletingRunsInsideTheStoreChangeWrapper` |

The commit run beside the helper survived (its own act asks as well). It can no longer be
written: the wrapper is not in the downloader's reach.

**Round 5** (on the tree of that fix):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ *Installed as this entry would install it* is one comparison of two records | a list of fields (digests and three scalars) | `theSameBytesUnderAnotherRoleAreInstalledUnderThatRole` |
| ⭐ A caller is released by a teardown while its step fetches the catalog | the caller waiting for its step | `aCallerIsReleasedByATeardownWhereverItsCallIsWaiting` |
| …and the step then works for nobody | not cancelled | the same. ⚠ Survived first: the case did not let the catalog answer afterwards |
| Nobody is registered on a graph that is gone | registered all the same | the same |
| A refusal is the caller's own | failing every waiter of the model | `aSecondCallJoinsTheRunningDownloadAndAnotherCellularChoiceIsRefused`, `aNewerEntryThatIsRefusedTouchesNothingOfTheDownloadThatIsRunning` |
| The caller that replaces a download is not told it was replaced | failing its entry with the others | `aNewerCatalogEntryReplacesTheDownloadThatWasRunning`, `aNewerEntryThatIsAdmittedTakesOverAndTheOlderStagingGoes` |
| ⭐ (simulator) Only the holder of a ref speaks for it | every task speaks | `onlyTheTaskThatHoldsARefNowSpeaksForIt`, `aTaskCancelledWhileItWasBeingMadeNeverSpeaks` |
| ⭐ …not a task a cancel was asked for | ignoring the cancel | `onlyTheTaskThatHoldsARefNowSpeaksForIt` |
| …not an earlier process's task after a cancel | still speaking | `aTaskOfAnEarlierProcessSpeaksForItsRefUntilACancelIsAskedFor` |
| …and for its own ref only | any ref | `onlyTheTaskThatHoldsARefNowSpeaksForIt` |

A fifth ledger mutation, dropping the check that the task is the recorded holder, survived: a
second task cannot exist beside the holder without a cancel. The clause was redundant and is removed.

**The order of starts and cancels** (`ModelTransferLedgerTest`; round 2; on the simulator
when these were run, and on the JVM too since the ledger moved):

| Guard | Removed by | Red |
|---|---|---|
| ⭐ A start a cancel overtook is dropped at the session's answer | admitted all the same | `aStartACancelOvertookIsDroppedWhenTheSessionAnswers` |
| ⭐ …and when its task exists | resumed all the same | `aCancelAskedForWhileTheTaskWasBeingMadeLeavesItNeverRun` |
| ⭐ A task a cancel was asked for no longer holds its ref | any listed task holds it | `aDownloadAskedForAgainAfterACancelGetsATaskOfItsOwnAndTheCancelDoesNotReachIt`, `aTaskOfAnEarlierProcessHoldsItsRefUntilACancelIsAskedFor` |
| ⭐ A cancel does not reach a start asked for after it | every task of the ref | `aDownloadAskedForAgainAfterACancelGetsATaskOfItsOwnAndTheCancelDoesNotReachIt` |
| This process's own tasks are cancelled at once | none returned | the same |
| ⭐ A held ref is not started a second time | no check of the ledger's own hold | `aRefThatIsHeldIsNotStartedASecondTimeWhoeverAsks` |
| The hold ends with its task | never released | the same |
| ⭐ The teardown tells the old graph's waiters (`PhotosOnDeviceAi.retire`) | not calling `tornDown` | `aGraphThatWasTornDownInstallsNothingAndTheNextOneDoes` |

A ninth, clearing the ledger's hold at a cancel, survived: the admission already ignores a hold
older than the last cancel. The line was redundant and is removed.

And on the JVM: ⭐ the cellular choice is part of the ref | a ref naming the artifact alone |
`aTransferStartedUnderTheOtherChoiceNeverCarriesADownload`.

**On the simulator, through Photos' assembled graph:**

| Guard | Removed by | Red |
|---|---|---|
| ⭐ Exactly `{"coreml"}` is contributed | `onnx` beside it | `onAnAdmittedPhoneExactlyTheCoreMlRuntimeIsContributedWithTheDownloaderThatMayCarryIt` |
| …and it is contributed | not bound | `onAnAdmittedPhoneExactlyTheCoreMlRuntimeIsContributedWithTheDownloaderThatMayCarryIt` |
| ⭐ The graph's wrapper is `AiReadiness.storeChanging` | running the change bare | `aCommitFromTheJournalRunsInsideReadinessWriterThree`, `deletingTheModelRunsInsideReadinessWriterThree` |
| ⭐ `ProxyModule` passes the contributed downloader | `null` | `aCommitFromTheJournalRunsInsideReadinessWriterThree`, `aFileCorruptedOnTheWayIsDiscardedAtTheRelaunchAndNothingIsInstalled` and 5 more |
| ⭐ …and the contributed wrapper | `Direct` | `deletingTheModelRunsInsideReadinessWriterThree`, `onAnAdmittedPhoneExactlyTheCoreMlRuntimeIsContributedWithTheDownloaderThatMayCarryIt` |
| ⭐ The launch check resumes the journal | not calling it | `aCommitFromTheJournalRunsInsideReadinessWriterThree`, `aFileCorruptedOnTheWayIsDiscardedAtTheRelaunchAndNothingIsInstalled` and 4 more |
| ⭐ The graph's downloader asks readiness whether it is retired | `{ false }` | `aGraphThatWasTornDownInstallsNothingAndTheNextOneDoes` |
| The session Swift installed is the graph's transport | the Koin lookup alone | `theSessionSwiftInstallsIsWhatTheGraphDrivesAndWhatItReportsReachesTheInstalledRuntime` |
| ⭐ The graph's downloader is given §3.4's budget | `{ _, _ -> 0 }` | `onAnAdmittedPhoneExactlyTheCoreMlRuntimeIsContributedWithTheDownloaderThatMayCarryIt` |
| Only a 2xx response is taken as a file | no status check | `onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup` |
| Only a ref-shaped name is | no `isRef` | `onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup` |
| ⭐ Nothing is delivered into a directory that is not excluded from backup | carrying on after the refusal | `onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup` |
| The exclusion is set before the move | not called | `onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup` |
| The delivered file is moved, not copied | `copyItem` | `onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup` |
| No downloads directory is *no delivered file*, not *unknown* | `null` | `onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup` |
| A completion reaches the installed runtime | dropped | `theSessionSwiftInstallsIsWhatTheGraphDrivesAndWhatItReportsReachesTheInstalledRuntime` |
| `installModelTransferBridge` installs it | not installing | `theSessionSwiftInstallsIsWhatTheGraphDrivesAndWhatItReportsReachesTheInstalledRuntime` |
| ⭐ None of it is bound with the flag off | always including the module | `aBuildWhoseInfoPlistDoesNotSayYesBindsNothing`, `belowIos17NothingIsBoundAndSearchIsNotBuilt` and 3 more |

⚠ **Three mutations survive, and each is said in §14.16 rather than papered over:** the size
check alone (the digest catches the same files), the bar's first snapshot (conflated before any
collector runs), and the old marker's removal in `adoptStaged` (what was staged goes first).

⚠ **What the first runs showed that the rows do not say.**
- **Nine survived first, and each found a missing case or a redundant line.** A wrong file left
  for the failure path to delete (no case made the failure unrecordable); the sweep deleting
  *named* files (no case had a named file still there at the sweep: now an unreadable one);
  four of the retired checks, one at a time (the checks were layered, so the cases now retire
  the graph at four different moments, and the one check no case could tell apart was removed);
  a late failure report failing a staged artifact; staged bytes that could not be read being
  fetched again (found by reading, then mutated); the sweep of a graph retired mid-settle.
- **The first whole run hung** and was killed: a case that asserts a refusal around `download`
  waits for transfers when the refusal is mutated away. Those cases now start the download on
  the background scope and assert that it ended, so a download wrongly let through is a named
  red case and not a timeout.
- ⚠ One simulator run was thrown away and repeated: the tree was disturbed under it by a stray
  `git stash` (restored at once, by content).

### 14.16 Still owed, and not proved

- **Smoke** (`scripts/android-smoke.sh`) and the full gate: owed, the orchestrator's before
  merge. `commonMain` of `:inference-service-proxy` and of `:control-shared-proxy` changed
  (`ProxyModule` passes two more optional bindings; `DeleteModel` runs inside a wrapper that is
  `Direct` off iOS). `:control:mergeLibDexDebug` ran here and passed.
- **5c-2** is built (§14.17): what it leaves owed is in §14.17.6.
- **Closed after Codex round 14:** a successor store's first read inside the other graph's
  commit, and a successor's readers answering from a stale memo (§14.11). ⚠ Left: the two-thread case runs on the JVM only.
- **Left open by Codex round 15:** the Swift around the two delivery questions is compiled
  only, and "one file at a time" is Apple's contract for a serial delegate queue, not run.
- **Left open by Codex round 13:** the windows still listed in §14.11 under *which two-graph
  windows remain* (the store's index was closed in round 14).
- **Left open by Codex round 12:** that the files are readable whenever a settle runs rests on
  the default protection class and the foreground rule, neither run on a phone. (The two
  returns that could still leave a caller waiting were closed in round 16.)
- **Left open by Codex round 16:** the progress stream cannot tell a download refused for now
  from a slow one (§14.11's table); and why `getAllTasks` would time out in the foreground.
- **Left open by Codex round 11** (the drain of a build that offers no model):
  - `ModelDownload.drain` and its two callers are compiled only. Not run anywhere: that a
    flag-off build's `getAllTasks` lists an enabled build's tasks, and that cancelling them
    ends the relaunches;
  - a task that completes between the launch and the session's answer delivers nothing and
    reports nothing, by the ledger; its file is the system's to delete [D];
  - the journal, the staging and an installed model have no reclaimer in such a build.
- **Left open by the whole-file review round** (2026-10-12):
  - ⚠ **Whether anything `storeChanging` waits for can hold the shared replica's lock when
    Photos is suspended** was not traced. The download path itself takes no such lock, and
    nothing is settled in the background any more; what is left is a settle that began in the
    foreground. The class is `0xdead10cc` (§3.3), which has killed Drive and Photos on the
    phone for other reasons.
  - **The foreground rule has never run.** `modelTransfersSettleNow` is driven by the scene
    phase in Swift; the Kotlin half is run on the simulator
    (`theSessionSwiftInstallsIsWhatTheGraphDrivesAndWhatItReportsReachesTheInstalledRuntime`),
    the Swift half is compiled. ⚠ If the first value is wrong, or never arrives, the symptom
    is quiet: a finished download that installs only when Photos is next opened *after* having
    been in the background.
  - **`FakeTransport` is one interleaving at a time.** Its gates put an order in by hand; no
    case searches for others, and nothing runs the downloader and the ledger on real threads
    any more (the simulator race test that did, with two orders, was replaced by the
    deterministic cases that cover the same two and six more).
  - **A report whose graph was torn down is settled without being asked whether it stands.**
    Deliberate (a retired graph acts on nothing), and it frees the ref: the next graph finds
    the delivered file on the disk.
  - **Maintainer decisions this round took, each reversible:** nothing settles in the
    background (rather than a background-task assertion); a delivered file of another length
    is `TRANSPORT_FAILED`, not `INTEGRITY_FAILED`; a caller that leaves before its download
    is journalled still gets its download (rather than: has asked for nothing);
    `HttpModelCatalogSource`'s swallowed cancellation is recorded, not fixed (`tech-debt.md`).
- ⚠⚠ **Nothing on a phone, and no real background `URLSession` anywhere.** `ModelDownload.swift`
  compiles and links (`ios-smoke.sh --build-only photos`, and once with the flag on); it has
  never run. Covered only by a fake transport, or by Apple's documented contract:
  - that a request with both flags false **waits** on cellular and resumes on Wi-Fi. The fake
    waits because it was written to; the request's flags are asserted, the system's behaviour
    is [D];
  - suspension, termination, the relaunch, `handleEventsForBackgroundURLSession`, the
    completion handler. A "relaunch" in a test is a new downloader, a new store and a new graph
    over the same disk;
  - `didFinishDownloadingTo`'s temporary file and its move, across two volumes if they are two;
  - that `taskDescription` survives a relaunch [D];
  - that a cancelled task reports `NSURLErrorCancelled` and nothing else;
  - `getAllTasks` within 5 s;
  - what the session does when its own resume is answered `200` (§14.12);
  - that a delivered file is readable when Photos is next in the foreground (data protection:
    Application Support's default class is readable after the first unlock [D]).
- **The on-phone checklist** (the maintainer's; each needs the App Group build, S0b a–d, the
  flag on, and until 5d a debug hook that calls `download`):
  1. A whole download on Wi-Fi: ~743 MB, three transfers, the bar, the commit; `Preparing`
     afterwards. Time it: the text tower comes from the bucket, never the edge (§14.12).
  2. Start on Wi-Fi, switch Wi-Fi off with cellular on: every transfer waits, none fails, no
     cellular data is used (Settings → Mobile Data → Photos). Wi-Fi back: they go on.
  3. The same with `allowsCellular = true`: it goes on over cellular.
  4. Leave Photos mid-download and lock the phone: the transfers finish; opening Photos
     installs the model.
  5. Leave Photos mid-download and let iOS terminate it (not a force-quit: memory pressure, or
     `kill` from Xcode): the same, through `handleEventsForBackgroundURLSession`.
  6. **Force-quit** Photos mid-download: iOS cancels the transfers [D]; opening Photos starts
     them again from zero.
  7. Reboot mid-download and open Photos after unlocking.
  8. A full disk: under ~2.85 GB free, `download` refuses before a byte moves, with the two
     figures.
  9. Low Data Mode on Wi-Fi: say what happened (item 11).
  10. The `200`, if it can be provoked: interrupt the text tower (flight mode for a minute)
      several times, and watch whether the bar restarts and whether the file still verifies.
  11. Free space before the download, after it, and after a cancelled one.
- **Not proved:**
  - the instant between a retirement question and its act, for every act (§14.11's table).
    Read from the code and argued, not reproduced: no case can land a teardown between two
    adjacent lines. The cases land it at every wait and after every earlier act;
  - two graphs of one process settling the same delivered file at once (the retiring one and
    its successor). Each derives from the disk and neither deletes on a failed read, so the
    loser re-derives; no case interleaves them;
  - **what of `ModelDownload.swift` is still unexercised**, exactly. Its decisions are
    `ModelTransferLedger`'s, which is run. What is compiled and never run is the Swift around
    them: that `didFinishDownloadingTo` asks `speaksFor` and `didWriteData` asks
    `reportsProgress` before they report; that `didCompleteWithError` asks `completed`, passes
    the report it is given to Kotlin, and settles it at once when Kotlin answers that nobody
    took it; that the scene phase reaches `modelTransfersSettleNow`, at the first appearance
    and at every change;
    that `liveTransferRefs` answers with `ledger.liveRefs` and not with the session's list; that `startAsked` is called
    before `getAllTasks`; that `admit` and `bind` are called
    in its answer, in that order, with the tasks filtered to running and suspended; that a
    refused `bind` cancels the task and does not resume it; that `cancelAsked`'s own tasks are
    cancelled before the session is asked; that `didWriteData` asks `reportsProgress` with
    the task's identifier and the total written; that `ended` is called from `didCompleteWithError`
    for every task, cancelled ones included; and that `getAllTasks` answers at all;
  - the size check alone, by mutation: a file of another size also has another digest, so
    removing it survives. It is there so that 555 MB of the wrong length are not read;
  - that the bar's **first** snapshot carries what is held. Starting it at zero survives: the
    settle that follows publishes the right figure before any collector of the conflated flow
    runs. The case asserts the figure after the settle;
  - that the old marker goes before the move in `adoptStaged`. Keeping it survives: what was
    staged is deleted before the move, so a marker with nothing behind it claims nothing;
  - what "free" means on a phone. The check reads `NSFileSystemFreeSize`; iOS can also purge
    caches to make room (`volumeAvailableCapacityForImportantUsage`) [D: Apple], which this does
    not count. The check can therefore refuse a download iOS would have made room for;
  - a journal that can be read and not written while a delivered file is corrupt: the file is
    discarded, the failure cannot be recorded, and each later resume fetches the artifact again;
  - `adoptStaged` when the move falls back to a copy and dies part-way. The marker is written
    last, so the next `beginStaging` reads nothing to resume; no case kills the move;
  - the two half-sweeps apart on the real directory listing (`deliveredRefs` answering `null`);
  - a journal written part-way. A file that does not decode is no journal;
  - `acceptDelivered`'s real backup exclusion failing. The case passes a stand-in for the failure;
  - progress, beyond the first snapshot after a relaunch;
  - the catalog through the graph: no graph case asks the CDN, so `download` itself is
    exercised over a fake catalog on the JVM and the simulator, and the graph cases begin at
    the journal;
  - a metadata-only rewrite **in Photos' graph**, with a `Ready` model: that the verdict stands
    and no vector is cleared is read from the two keys' inputs and from 5a's cases of
    `storeChanging`; the downloader's cases use a recording wrapper;
  - the in-process downloader on a metadata-only change. Read, not run;
  - the ⚠ᵃ cells of §14.11's matrix: fifteen states an operation is not exercised in;
  - that `remove` runs only after the download's record is gone, by mutation. The case reads
    the journal from inside the removal; the order was not swapped to see it fail;
  - the first journal look being *once*. Repeating it survives: tracking twice changes nothing;
- ⚠ **Found on the way, not fixed** (`tech-debt.md`): the CDN's `200` (§14.12); staged `.part`
  files have no sweep (§14.13); the refusals have no wire reason (§14.14 item 1).

### 14.17 What 5c-2 shipped (2026-10-12): the prepare step

Three pieces, all common code but the last:
- `ModelPreparer`: **which** towers are compiled, swept or discarded, and when;
- `CompiledCache`: **how** one tower is prepared (seven steps, the marker last), discarded
  (the marker first) and swept, over a port of primitives, `CompiledFiles`;
- `CoreMlModelCache` (iOS): the primitives and nothing else. ⚠ Since the whole-diff review
  (§14.17.7) the order and the cleanup are no longer in the iOS file, with a second copy in a
  fake: the rule the cases run is the rule that ships.

One call of `prepare`: the install record is read; everything it does not name is swept; each
tower that is not ready is compiled, opened once and marked; and readiness reads the markers
(writer 1). ⚠ It asks for no probe.

- **One sequence, for every trigger** (`PhotosOnDeviceAi.recheck`, serialised and coalesced):
  readiness's own check → the journal's resume → `ModelPreparer.check`: the prepare, then the
  probe **when the state says one is owed** → the warm-up after a pass. The triggers are a
  launch, the scene becoming active, and every step of the downloader that answered
  *installed* (asked for once that step has ended, and not waited for).
  A removal evicts in its own step (`evicting`, `evicted`), wherever Photos is.
- ⚠⚠ **The probe has one owner, and is owed by the state** (round 6). There were
  two: the preparer probed *when it had compiled something*, and the foreground's check
  otherwise, with a flag to keep them apart. An update that keeps both towers and changes only
  the vocabulary compiles nothing and moves the preflight's key: a verdict was awaited, the
  flag said no probe, and nothing asked until the user left and came back. Now: after the
  prepare, whatever it did, a model that readiness says awaits its verdict is probed, by
  `check` and by nothing else.

  | Readiness, when a trigger arrives | The prepare | The probe |
  |---|---|---|
  | `NoModel` | sweeps | none |
  | `Preparing`, a tower missing | compiles it (in the foreground) → `AwaitingVerdict` | owed, and made in the same check |
  | `Preparing`, both towers there (`Incomplete`: the vocabulary) | nothing | none: nothing is prepared |
  | `AwaitingVerdict` (a new key over the same towers; a probe that decided nothing) | nothing | **owed, and made** |
  | `Ready`, `Unsuitable` | nothing, and readiness is not touched | none |

- **An install that awaits its verdict, with Photos in the foreground and nothing to come:**
  the ways, plainly. In each the preflight's `lastReport` says which.

  | Why no verdict yet | What probes next |
  |---|---|
  | the backoff (a minute, doubling, an hour at most) after a slow, failed or transient probe | ⚠ **nothing, until the next trigger**: the next return to the foreground, or a commit. A user who stays in Photos is not probed again when the backoff ends |
  | this foreground has had its slow probe | by D12, only another foreground |
  | another probe was running (`Busy`) | that probe's own answer |
  | a tower would not load | the recompile, and its probe at once |

  **Decided by the maintainer, 2026-10-12: no timer; it stays as built.** A single delayed
  check at the backoff's end is the only thing that would close the first row. It was
  weighed and left out: 5b decided that a retry belongs to
  a later foreground (a phone that was hot or busy a minute ago is asked again when the user
  comes back, not behind their back while they scroll), a slow probe can only count on another
  foreground whatever a timer does, and a tower that would not load is answered at once by
  the recompile. ⚠ So the first row stands as it is. Decided, not open.
- **Under what:** the process's step lock, for its whole length. ⚠ **Not** readiness's lock:
  that is taken for the moment a tower that may be served is discarded (`compiledChanging`),
  to read the cache before a compile and after it, and to mark the cache as changing
  (`cacheChanging`). So `fingerprintChecked`, `awaitingProbe`, `recordProbe` and
  `unloadRetired` do not wait for a compile. Nothing on the main thread or the per-image path
  takes readiness's lock: `serves` is one volatile read.
- **Lock order:** the preparer's call lock → the step lock → readiness's lock → the store's
  process lock. A removal's eviction runs under the step lock and takes neither of the first two.
- **A call that changes nothing writes nothing.** What a call will do is decided before
  readiness is touched. It was not, at first: `compiledChanging` wrapped the whole call and
  left `Ready` unconditionally, so every return to the foreground un-served the model, closed
  both towers and cancelled a running pass, to compile nothing (review finding 2).
- **No probe beside a prepare, no prepare beside a probe.** `cacheChanging` marks the cache;
  while it is marked `awaitingProbe` lets no probe through. A probe claims its towers
  (`holding`) inside `awaitingProbe`, under readiness's lock, so there are two orders and no
  third: the probe began first and the preparer waits for it, or the mark came first and no
  probe begins. A probe's towers are therefore there for its whole length.
  - ⚠⚠ **That is not the same as its answer still being about what is installed when someone
    acts on it** (round 3; this bullet said no stamp was needed, and was wrong
    about the half that matters). An answer crosses waits: `recheck` hands it to the
    preparer, which waits for its own lock while a prepare of another generation runs. The
    answer then named *the last install the preparer had read*, the new one, and the old
    generation's failure discarded the new towers and used up their one recompile.
  - **The rule: a value that crosses a lock wait or a suspension carries the identity of
    what it is about, and the act re-checks it under the lock.**

    | The value | Carries | Re-checked | On a mismatch |
    |---|---|---|---|
    | a probe's answer, handed to `ModelPreparer.probed` | **the whole key it ran under** (`PreflightReport.key`, as it is; round 7), and the preparer's `generation` from **before** the probe began | under the preparer's call lock, and the install again under the step lock before a discard | dropped for this purpose: nothing compiled, nothing recorded; still returned to `recheck` as what that probe said |
    | the recompile allowance (`recompiled`) | the `PrepareKey` | under the call lock | a key that is not the install's is not looked up |
    | `Preflight.recompiled()` (past the backoff) | nothing of its own: it is called by the preparer inside the same call that compiled, with no wait in between that another prepare could take | — | — |
    | a probe's answer, recorded by `recordProbe` | the `PreflightKey` it probed | under readiness's lock, against the key that would run now | `Dropped`, and said to be |
    | …and for the **same** key compiled again | nothing more is needed: the probe keeps its claim (`holding`) until the answer **is recorded**, so no discard can come between the measurement and its recording. ⚠ It let go when its towers closed, before this round | — | — |
    | `lastReport` | the towers' digests. ⚠ An update that changes only the vocabulary or the metadata keeps the key; the prepare that follows its commit re-records under it | read by key (`outcomeFor`) | `null` |
    | `lastRefusal` | nothing: it is about the *last call*, not an install, and is cleared by the next call that reads one | — | — |
    | the `storeChanged` and `afterRemoval` triggers | nothing: each reads the record fresh under the step lock | — | — |
    | two `recheck`s' answers arriving in either order | each its own key and generation | as the first row | the stale one is dropped |
- ⚠⚠ **Every act that deletes or writes under `inference/coreml` runs inside that one
  exclusion** (round 2). The compile did; the sweep ran ahead of it, and after an
  update or a removal it deleted the towers of a probe still running on the generation that
  had just been replaced. The acts, all of them:

  | Act | Where | Inside the exclusion |
  |---|---|---|
  | the sweep of a prepare | `prepare` | yes, first |
  | a removal's eviction | `evicted`, in the removal's step | yes |
  | the discard before a recompile | `prepare` | yes, and inside `compiledChanging` (it touches served towers) |
  | a tower's seven steps, its staging deletes, its cleanup after a throw | `CompiledCache.prepare` | yes: reached only from inside |

  Structurally: the deleting and writing calls are members of `Acts.Changes`, and a `Changes`
  exists only inside `Acts.changing`, which marks the cache, **waits** (`beforeLoad`: a probe
  that had begun, an earlier graph's unload), and only then runs. A path that deletes cannot
  be written outside it.
  - **What is waited for, and the backend's own sessions.** A probe's pair is waited for
    here. The backend's sessions are on the *served* towers: an update's commit and a
    removal close them inside `storeChanging` before the store moves, and a recompile's
    discard closes them inside `compiledChanging`; the sweep never names a live digest.
  - **A removal now waits for a probe in flight**, inside the downloader's step: for one
    probe at most (the vocabulary, two towers opened, a handful of predictions: seconds [I,
    not measured under a removal]) and for an earlier graph's unload. No cycle, by the
    whole graph in §14.17.0 (this sentence first said so for this edge alone, and listed what
    a probe waits for without the unload it also waits for).
  - **What that probe concludes:** nothing. Its key moved under it, so `recordProbe` drops
    the answer, as before (`aPassForAKeyThatMovedUnderTheProbeIsDroppedAndNotReported`).
- ⚠⚠ **What `DeleteModel` returning means on iOS: the install record is gone, and so are the
  compiled towers it named** (round 4). One stated thing.
  - **The order is: the towers, then the record.** Inside the removal's step the preparer
    discards the record's own two digests (`evicting`: inside the exclusion, and inside
    readiness's writer, which stops serving them first), and the store removes the record
    only if they went. Then a sweep takes whatever else no record names (`evicted`).
  - **A tower that cannot be deleted fails the removal**, and nothing was removed: the model
    is still installed (not served, not compiled), `InstalledModels` still lists it, and
    deleting again tries again. It was the other way round: the record went first, the
    failed delete was recorded, and the removal was reported as done with ~740 MB left.
    - Chosen over *record first, then throw*: that reports a failure for a model the list no
      longer shows, a half state nobody can act on.
    - ⚠ Cost: after a failed delete the model is installed and unprepared. The next prepare
      **compiles it again** (it is installed), unless the user deletes it first.
    - ⚠ A process that dies between the towers and the record leaves the same: a model the
      user was deleting, installed and compiled again at the next foreground. That is what a
      crash in the middle of a delete costs.
  - **On the wire:** `CompiledCacheNotClearedException` has no reason of its own; `typed`
    answers `TRANSPORT_FAILED`, the retryable bucket, with its sentence. No new reason.
    `lastReport` says `Failed(cache, could not delete …)` for the install.
  - ⚠ **One layer down, unchanged:** the *archives* are not deleted by a removal at all. The
    store declares them and the next process's sweep deletes them
    (`DefaultModelStore.sweep`), ignoring whether each delete worked and then dropping the
    list. A failed archive delete is surfaced nowhere and is not tried again
    (`tech-debt.md`).
  - **The update path:** a commit is reported *installed* (it is). When the prepare that
    follows cannot sweep the old generation, it compiles nothing and says
    `Failed(cache, could not delete …)` for the new install, which stays `Preparing`; every
    later prepare meets the same directory first.
- ⚠⚠ **This graph is about one model; the store, the downloader and the cache are not**
  (round 10). The preparer, readiness, the preflight and the backend are about
  the pinned SigLIP pair, which is a legitimate scope while one Core ML model exists. The
  downloader and the store hold any number of models, and three of their hooks reached this
  graph with no model named: removing *any* model discarded SigLIP's compiled towers and
  stopped serving it. Each hook is scoped at the boundary now.

  | Component | Scope | What a second Core ML model meets |
  |---|---|---|
  | the removal's hooks (`beforeRemoval`, `afterRemoval`), the commit's (`storeChanged`) | **told which model** (this round) | nothing of SigLIP's is touched for another model's removal or commit |
  | readiness's writer 3 around a store change | **scoped** (`PinnedModelChanges`): only a change to the pinned model stops it being served | another model's commit or removal runs as it is. ⚠ A caller that names no model is treated as the pinned model's |
  | the sweep | **keeps every digest any install record names**, of any model; takes the rest | another model's compiled towers, in the same `inference/coreml`, are not orphans while it is installed. ⚠ Nothing *compiles* a second model: the preparer prepares the pinned one |
  | the prepare, the recompile allowance, the owed probe, `lastReport` | single-model: keyed by the pinned model's id and its digests | a second model is never prepared or probed. Keys carry the model id, so nothing is confused with another's |
  | readiness, `PreflightKey`, the verdict file, the backend | single-model | a second model needs its own readiness and verdict file; one verdict path today |
  | the disk budget (`CoreMlDiskBudget.additionalNeed`) | ⚠ **unscoped**: SigLIP's measured ratio for whatever entry the `coreml` runtime offers | another model's download is budgeted with SigLIP's prepare ratio (`tech-debt.md`) |
  | the flag-off drain (§14.11) | model-agnostic: every task of the session, every delivered file | the same for any number of models |
  | the catalog's runtime filter | `{"coreml"}`: every Core ML entry is offered | a second entry is offered and can be downloaded and installed; it then sits installed and unprepared |

- ⚠⚠ **Which identity each decision is bound to** (round 7). Rounds 3, 6 and 7
  each came from asking a question of a narrower identity than it needed; round 7's was a
  probe's key reduced to its two towers' digests on its way to the preparer, so an update that
  changes only the vocabulary looked like the same install, and the old towers' failure
  discarded the new install's towers and spent its recompile. The table, once:

  | The decision | Bound to | Why that, and what follows |
  |---|---|---|
  | *is this probe's answer about what is installed now?* | **the whole `PreflightKey` the probe ran under** (the towers' digests, the compute units, the OS build, the preprocessing revision, the vocabulary's digest, the app build, the preflight revision), compared **whole** with the key that would run now, **and** the compile generation from before the probe began | It is already the verdict's identity. Compared when the answer arrives, again under the step lock before a discard, and again before a recompile is remembered as made. ⚠ `probed` takes the report's key as it is: nothing converts it |
  | *has this install had its one recompile?* | the **towers' digests** (`PrepareKey`) | A recompile is about the compiled towers. ⚠ So an update that keeps both towers and changes the vocabulary **inherits a spent allowance**, rightly: the same bytes have been compiled twice in this process, and a third time cures nothing of a vocabulary |
  | *what is said of this install?* (`lastReport`, `outcomeFor`) | the towers' digests | What a prepare is about. ⚠ An update that keeps both towers keeps the key: what was said of the old install (`Incomplete`, `Failed`) is still what is said, from its commit until the check that commit asks for has re-recorded, seconds later. 5d should read it with readiness's state beside it |
  | *which compiled directories does the record name?* (the sweep, the eviction) | the towers' digests | They are the directories' names |
  | *do the stored vectors survive?* | the runtime fingerprint (PR-3) | Unchanged by this slice |
  | *is the model prepared?* | readiness's own reading (`coreMlModelFacts`), which the preparer asks with the same function | One predicate (review finding 8) |

- ⚠⚠ **Nothing is recorded as done before it happened** (round 8).
  - **The one recompile, and the probe owed after it, are two facts.** They were one set.
    The allowance is spent when the recompile completes; the probe that was to follow could
    then be refused (the scene left while the towers compiled), and nothing owed it any more:
    back in the foreground the ordinary probe met the failed probe's backoff, and the rebuilt
    towers waited it out, unverified. Now a **debt** stands from the recompile's end until a
    probe of those very towers has *run to an answer readiness recorded* (a verdict, or a
    counted miss), whoever asks for it: the same check, the next foreground's, a commit's. A
    check that finds it asks for its probe past the backoff. A probe that did not run
    (`Waiting`, `Busy`, nothing to probe) or was not recorded pays nothing.
    - Bound to the whole key and the compile generation (the identity table above): another
      install, or the same towers compiled again, inherit nothing; such a debt is dropped.
    - ⚠ Memory, like the allowance. A process that dies between the recompile and its probe
      starts with no debt and a fresh allowance: the rebuilt towers' first probe waits out
      the backoff the failed probe left on disk, once. Not persisted, by choice: the cost is
      one backoff.
  - **A probe the scene left under leaves no trace.** With no foreground nothing is probed
    (round 5). One that *began* in the foreground and ends with the scene gone, or in another
    foreground, is dropped before anything is recorded: no failure counted, no backoff's
    stamp, no slow probe spent in that foreground (`aProbeTheSceneLeftUnderLeavesNoTrace`).
    What it measured was measured on an app being throttled, and a count is durable.
  - **The others, on this tree:**

    | Recorded | Set |
    |---|---|
    | the recompile allowance (`recompiled`) | after the pass that discarded and compiled has returned `Prepared`, for an install that is still the one the probe was of |
    | the compile generation | ⚠ **before** the compile, on purpose: from that line the towers may not be what a probe saw, and an answer is dropped on the safe side |
    | *a step answered installed* (`storeChanged`) | in `finish`, after the store's commit returned (or the store was found to hold the install); said after the step lock is released |
    | *this foreground has had its slow probe* | after readiness has recorded a slow probe (`Decided` or `Counted`); not when a probe was asked for, refused, dropped or stopped |
    | `lastReport`, `lastRefusal` | after the pass, with what the disk and readiness answered |
    | the warm-up | not recorded at all: `warmText` opens a tower that is not open |

- ⚠⚠ **What is counted once per event is counted by the event, not by the call** (round 5).
  - **A foreground is the scene's.** D12 makes a phone `Unsuitable` after three slow probes
    on three *separate foregrounds*. The preflight counted its own `foregrounded()` calls,
    and two checks in one foreground (the launch's and a rehydration's) were two foregrounds.
    Now the scene phase is the source: `SceneForeground` moves a number on each
    change *to* active and at no other time, every probe is told which foreground it is in
    (`Preflight.foregrounded(foreground)`, `prepared`, `recompiled`), and one foreground has
    one slow probe counted (`SlowProbes`, the process's, so a graph that replaces another
    inside one foreground gets no second). N checks in one foreground are one, in any order.
    - With no foreground (`null`: a process the scene has not reported active, or Photos in
      the background) **nothing is probed**: there is nothing to count it in, and a probe
      opens both towers.
    - ⚠ The number is memory. A new process starts with none and its first foreground is a
      new one, which is right: the *count* is on disk, in the verdict file, and a new
      process's slow probe is a new foreground's.
  - **The check is one sequence at a time** (`CoalescedRuns`, one for a graph): the journal's
    resume, the prepare, the probe and the warm-up of one check end before another's begin. A
    request made while one runs waits and then causes **one** more run, for itself and every
    other request made during that run: what the running one had already passed is done
    again for them, and N requests are not N runs. Round 3's "two checks' answers out of
    order" cannot happen inside a graph any more; the identity check stays.
    - A teardown does not cancel a check that waits: the one in flight ends at its next
      question (`alive()`), and the one that waited then runs on a graph that starts nothing.
  - ⚠⚠ **Whether the scene is active and which foreground it is are one value**
    (`SceneForeground`; Codex round 6). They were two fields written one after the other, and
    a reader on another thread could take *active* from after the change and the number from
    before it: a slow probe counted in the foreground that had just ended, and a second to
    come in the one that had just begun. One immutable value, swapped whole; a reader takes
    one snapshot. The other pairs a reader combines, checked:

    | Pair | What makes it one |
    |---|---|
    | the scene's *active* and its number | one value (above) |
    | *torn down* and *in the foreground* (`aliveAndInTheForeground`) | not a pair: each is asked for itself, and *torn down* never goes back |
    | the preparer's last report and its generation | both read and written under its call lock |
    | `SlowProbes` | one value: the foreground that has had its probe. The count is readiness's, on disk, under its lock |
    | the store's records and *not an index* | one value since round 2 |
    | the downloader's *installed in this step* | read and written under the step lock |
  - **The same shape, elsewhere in 5b and 5c:**

    | Counted | By | Idempotent because |
    |---|---|---|
    | slow probes towards `Unsuitable` | the scene's foreground | above |
    | failed probes towards a failed install | each probe that ran and was recorded | a probe runs under its own lock (a second asks and is told `Busy`), a recorded failure starts the backoff, and the checks are now serialised. ⚠ The one probe allowed past the backoff is the one after a recompile, once per install and process |
    | the recompile allowance | the install (`PrepareKey`), in a set | a set |
    | the warm-up | — | `warmText` opens a tower that is not open, and nothing else |
    | the indexer's failure streak (`EMBED_FAILURE_STREAK_LIMIT`) | the indexer's passes | nothing in this slice starts or fails a pass [C: no caller here] |
- **Compiled only in the foreground** (the settle's rule, §14.11), asked before every step.

#### 14.17.0 Who waits for whom

⚠⚠ Written after a deadlock (round 9), and after three "no cycle" sentences in
this section that had each been argued for one edge. The review's fix for finding 5 made a
probe claim its towers (`holding`) *before* it waits for an unload; a teardown of the probe's
own graph in between published an unload that waits for that claim, and the probe joined it.
Each waited for the other; the teardown never ended, and no later graph of the process could
load a model. **Fixed structurally:** a wait for an unload says whose graph is asking
(`PendingUnloads.awaitFor`), and **a party of a graph that was torn down waits for no unload
at all**: it returns at once, and stops at its next step.

⚠⚠ That rule is the second one. The first (round 9) looked at *whose the pending unload is*
and skipped the asker's own. That is the head of a chain, and the argument below was true
for the head and false transitively (round 11): A's probe claims its towers, A
is torn down, its successor B is torn down too, and only then does A's probe reach the wait.
The head is B's unload; B's waits for A's; A's waits for A's probe. So the rule does not look
at the chain. It looks at the asker, and rests on one fact about order:

- **Every unload published after a graph was torn down chains behind that graph's own.** So
  once a graph is retired, whatever is pending waits for it, and its parties wait for nothing.
- **A party of a graph that is not retired may wait for the head.** The head was published
  before that graph's teardown, so its chain is strict predecessors: none of it is that
  graph's unload, and none of it waits for anything that graph holds. If the graph is torn
  down *while* the party is suspended there, its own unload queues behind the same head and
  then waits for the party to let go; the party comes back when the head ends, sees the
  retirement at its next step and lets go
  (`aTeardownThatLandsWhileAGraphWaitsForItsPredecessorEndsEverything`).
- ⚠⚠ Both lines are about order, so *retired?* and *what is pending?* are **one snapshot**:
  `awaitFor` reads both under one lock, and `retire` sets the retirement and publishes the
  unload inside the same lock. Read separately, a teardown could land between the two reads
  and a party that read *not retired* would then join its own graph's unload.

The whole graph, once. *A → B* reads "A, while it holds or has claimed the things in its row,
waits for B". An edge is safe when B ends without ever waiting for anything in A's row.

| Party | Holds or has claimed while it waits | Waits for | Why that ends |
|---|---|---|---|
| **a probe** | the preflight's own lock; its claim on its towers (`holding`), from inside `awaitingProbe` until its answer is recorded | readiness's lock (twice, briefly) | never held across a wait for a probe: a writer's change runs inside it, and the one writer that waits for `holding` does so *before* taking it |
| | | the pending unload, **only while this graph is not retired** | a chain of strict predecessors (published before this graph's teardown, read in one snapshot with it): each waits for the one before, its own graph's probe, readiness lock and tower locks. None of those is this graph's. Finite |
| | | Core ML (opening two towers, a handful of predictions) | blocking, finite; asks *torn down?* between steps |
| **this graph's unload** (after `retire`) | nothing | the unload before it; this graph's probe letting go; this graph's readiness lock; the backend's tower locks | a probe ends (above) and waits for none of these while it holds its claim, except readiness's lock, which the unload takes only *after* the probe has let go |
| **a prepare** | the preparer's call lock; the step lock; the mark *the cache is changing* | an earlier graph's unload; this graph's probe letting go | a probe never waits for the call lock, the step lock or the mark (it is refused, not queued, while the cache is marked) |
| | | readiness's lock (around a discard; to read the markers) | brief; nothing under it waits for the step lock |
| | | the cache's own lock; Core ML (a compile: seconds) | finite; stopped at the next step by a teardown or the scene leaving |
| **a commit** (a downloader's step) | the step lock | readiness's lock (`storeChanging`), then the store's process lock | innermost; nothing is awaited under the store's lock |
| **a removal** | the step lock | as a prepare's sweep: an earlier unload, this graph's probe letting go (one probe at most); then readiness's lock; then the store's | a probe never waits for the step lock |
| **the check** (`recheck`) | its own one-at-a-time lock | readiness's lock; the step lock (the resume, the prepare); the probe (asked, never queued: `Busy`) | each of those ends, above; a teardown takes none of them |
| **a search or an indexing call** (the backend) | nothing (the wait is before the tower's lock) | an earlier graph's unload; this graph's probe letting go; then the tower's lock | holds nothing while it waits, so it closes no cycle |
| **a download's caller** | nothing | its own answer | completed by a step, or by `tornDown`, which waits for nothing |
| **a download's step** | nothing yet | the endings asked for before it; then the step lock | an ending waits for the step lock only, and is taken off in a `finally` |
| **a probe's or a first open's load record** (`AiReadiness.attempting`, §14.5 item 0) | nothing between its two writes | readiness's lock, twice: to write the record before the load, and to clear it after | brief; the lock is not held across the load, and nothing under it waits for a load |
| **5d: a Models screen's download press** (`JoinedModelSource.download`) | nothing | the call's admission (`admitted`), **for at most `admitWait` (15 s)**; the call itself goes on in the graph's scope | ⚠⚠ The call waits for the step lock (behind a prepare or a probe: seconds) and then for a catalog fetch with no bound of its own; the press was unbounded. Now bounded: past it the press is answered `StillStarting`, and the **source** says *Starting…* on the row for as long as its call lives (`ModelSetup.Pressed.Starting`), then what the call answered (`Pressed.Refused`, until the next press; a refusal under the download's key is the row's `last` already). ⚠ A bound on the caller's wait, never on the claim |
| **the scene's change** (`AiReadiness.sceneChanged`) | nothing | nothing: one atomic write of its own file | — |
| **5d: a Models screen's removal** (`OnDeviceModelsRepository.delete`) | nothing | the removal, for at most `removalWait`; it runs in the repository's scope and takes the step lock as a removal does (above) | the removal ends (above); past the bound the row says *still removing* |
| **5d: a Models screen's check** (`checkAgain`) | nothing | the check, for at most `checkWait` | as *the check* above |
| **5d: search switched off** (`consentWithdrawn`) | nothing | the text tower's lock (`closeText`), in the repository's scope | waits only for a query that holds the tower; nothing that query waits for waits for the screen |
| **5d: the screen's reads** (the source's join, the consent half) | nothing | the store's lock (`readInstalled`), the preference store; on `Dispatchers.Default`, never the caller's thread | brief; a read holds nothing anyone above waits for |
| **`tornDown`, `retire`** | nothing | nothing | — |
| **5e** (the pass's slot, a backend open's record, the stop on leaving the foreground) | see §14.19.4 | | two new leaf locks; acyclic |

Acyclic, by the two columns: nothing that a probe waits for waits for the probe's lock or
claim (see below, transitively); nothing a prepare, a commit or a removal
waits for waits for the step lock or the call lock; and the three things that are held
*while waiting on a party* (the step lock, the call lock, the probe's claim) are waited for
only by parties that hold none of what their holder waits for.

**Each edge, transitively** (round 11: the table above was checked one hop deep). For every
*waits for X*: everything X can in turn wait for, and why none of it is held by the waiter.

| Edge | X can transitively wait for | None of it is the waiter's, because |
|---|---|---|
| a party (probe, prepare, removal, backend call) → the pending unload | every unload before it; for each of those graphs: its probe letting go, its readiness lock, its backend's tower locks; Core ML | the waiter's graph was not retired at the snapshot, so every graph in the chain is a strict predecessor. A predecessor's probe, readiness and towers are objects of another graph. The step lock and the call lock are the process's, and no unload takes them |
| an unload → the unload before it | the same, one graph further back | an unload holds nothing while it waits |
| an unload → its graph's probe letting go | what a probe waits for: readiness's lock (brief), Core ML, and the pending unload **only if not retired** | the probe's graph *is* retired by then, so the probe waits for no unload: the edge that closed both cycles is gone by rule, not by which unload happens to be the head |
| an unload → its graph's readiness lock, tower locks | whoever holds them: a writer's change (finite, waits for no unload under the lock); a backend call under a tower's lock (a prediction, finite) | taken only after the probe let go; nothing under either waits for an unload (the backend's wait is *before* the tower's lock) |
| a prepare or a removal → this graph's probe letting go | as the probe, above | a probe is refused, not queued, while the cache is marked; it never waits for the step lock or the call lock |
| a prepare, a commit, a removal → readiness's lock, the store's lock | a writer's change; a probe's two brief sections | nothing under either lock waits for the step lock, the call lock or an unload |
| the check → the step lock, the probe | a download's step, a prepare, a removal (each above) | none of them waits for the check's own lock |
| a download's step → the endings before it, the step lock | an ending: the step lock only. The step lock's holder: above | a step holds nothing yet while it waits |
| a download's caller → its answer | a step, or `tornDown` | holds nothing |

- ⚠ A retired graph's prepare or removal that holds the step lock also returns from the
  wait at once and stops at its next *torn down?* gate, so a successor's step is not held up
  behind an unload either.
- The three cases of the chain: both a graph and its successor torn down before the probe
  waits (`aProbeWhoseGraphAndItsSuccessorWereBothTornDownWaitsForNeither`, which ends with a
  third graph let through to load); a teardown landing under a suspended wait (above); four
  teardowns let go in the wrong order, unloading in theirs (`aChainOfTeardownsUnwindsInOrder`).
- ⚠ Not run: two threads racing the snapshot itself. It is one lock around two reads and one
  around two writes; the cases above run on one test dispatcher.
- **Every point of a probe at which its graph can be torn down** is a case
  (`aTeardownDuringAProbeEndsEverything`: before the probe, between the claim and the wait,
  while a tower loads, during a prediction, between the measurement and its recording), and
  each asserts that the probe, the unload and a successor's wait all **end**.
- ⚠ Argued, not run: the edges through the downloader's step lock under a teardown (they are
  the cases of §14.11), and everything on a phone.

#### 14.17.1 Invariants

- **I1** An install whose state is `Preparing` always has one of: a prepare running; a prepare
  to come (every launch, every foreground, every commit starts one); or an outcome that says
  why not: `lastReport` (keyed by the towers' digests) for the install, `lastRefusal` for a
  call that never read an install.
- **I2** A compiled directory exists ⇒ the install record names its digest, or the next sweep
  removes it, or a sweep said it could not (`Failed`).
- **I3** A marker exists ⇒ every step of that tower's prepare ran to its end in one piece, and
  the directory beside it was not touched since: a discard removes the marker **first**.
- **I4** Nothing is compiled, deleted or concluded after a read that failed; and *no record*
  read from bytes that were not an index is not a removed model (`ModelStore.holdsAnIndex`).
- **I5** A graph that was torn down starts no step, and nothing is **compiled** while Photos
  is not in the foreground. A removal's eviction is file deletes and runs with the removal.

Every return of `ModelPreparer`, against them:

| Return | Stands on |
|---|---|
| retired, before the lock | I5; a retired graph has no install to serve. `lastRefusal` |
| the index could not be read, or is not an index | I4: nothing swept, nothing compiled. `lastRefusal`; what was said of the install stays |
| the cache could not be listed | I4, and nothing is compiled beside it |
| a sweep left something | I2: said (`Failed`, role `cache`), nothing compiled beside it |
| no record | I2: everything swept; `NoModel` |
| a tower's marker could not be read | I4: the tower is left as it is |
| nothing to do | no readiness write; `Prepared(state)`, or `Incomplete(why)` when readiness does not read the install as prepared |
| not in the foreground, with a tower to compile | I5; I1 by *a prepare to come*; said for the install |
| a discard for a recompile did not delete | said (`Failed`), not remembered as made |
| a step threw, or was stopped | I3 (nothing of the tower is left, §14.17.3); said for that tower; towers before it keep their markers |
| the prepare returned and the marker is not there | `Failed`: what is said is what the disk holds |
| every tower marked | readiness's answer by **its** predicate: `Prepared`, or `Incomplete` |
| anything the cache or readiness threw | an outcome for the install, never out of the call |

#### 14.17.2 Operations × states

States: **P0** no model; **P1** installed, a tower not compiled, no prepare running; **P2** a
prepare in flight (it holds the step lock); **P3** every tower marked.

| Operation | P0 | P1 | P2 | P3 |
|---|---|---|---|---|
| `prepare` | sweeps | compiles what is missing, probes: `anInstalledModelIsCompiledTowerByTowerMarkedLastThenProbed` | a second call, this graph's or another's, waits and then finds the towers ready: `noStoreChangeAndNoOtherGraphRunsUnderACompile` | **changes nothing and writes nothing**: `aPrepareWithNothingToDoDoesNotTouchAModelThatIsBeingServed`, `sweepingWhatNoRecordNamesDoesNotTouch…` |
| a download's commit (an update) | n/a | P1 for the new record; the prepare that follows sweeps the old towers first: `anUpdateDeletesTheOldCompiledGenerationBeforeItCompilesTheNew` | waits for the step lock; then `storeChanged` starts another prepare. Unpinned as an interleaving: the same lock as the P2 case | as P1 |
| `DeleteModel` | — | the removal, and the eviction in the same step | waits for the step lock, then both | the towers are deleted **first** (not served any more), then the record, then a sweep, **before `DeleteModel` returns and wherever Photos is**; a tower that will not go fails the removal and nothing is removed: `aRemovalDeletesTheModelsTowersBeforeItsRecordAndFailsWhenTheyDoNotGo`, `aRemovalWhoseOwnPreparationThrowsRemovesNothing`, `aRemovalEvictsTheCompiledTowersInItsOwnStepWhereverPhotosIs` |
| `cancel`, a transfer's report, `resume` | — | independent | wait for the step lock (seconds) | independent |
| teardown | — | — | the tower in flight is not marked and nothing of it is left; marked towers stay: `aTeardownUnderACompile…` | nothing |
| a move to the background | — | nothing is compiled until the foreground | stopped at the next step, nothing left: `aStopAtAnyStepLeavesNothingOfTheTowerNotEvenCoreMlsOutput` | nothing |
| the process dies | — | — | a directory with no marker, the staging area, and Core ML's own temporary output [I: the system's to clear]. The next prepare removes the first two: `whatACrashLeftIsSweptOrCompiledAgainAndNeverTrusted` | the towers are there and named |
| a probe (this graph's) | — | none: nothing is prepared | **does not begin**; one that had begun is waited for: `noProbeBeginsBesideAPrepareAndAProbeThatHadBegunIsWaitedFor`, and on the real `Preflight`, `noProbeBeginsWhileTheCompiledCacheIsChanging…` | runs |
| a probe says a **tower** failed | — | — | — | compiled once more in this process and probed **at once**, past the backoff: `aTowerTheProbeCouldNotLoadIsCompiledOnceMoreAndProbedAtOnce`, `aFailedProbeMadeByTheForegroundCheckLeadsToTheRecompileToo`, and on the real `Preflight`, `theProbeAfterARecompileIsNotHeldBack…`. Not when the install changed under the probe |
| a probe failed **before any tower** (the vocabulary) | — | — | — | nothing is compiled: `aProbeThatFailedBeforeAnyTowerWasOpenedCompilesNothing` |
| a marker gone from under a `Ready` model | — | — | — | readiness reads the cache and leaves `Ready` before the first byte is written: `aTowerIsNeverWrittenWhereAModelIsStillBeingServedFrom` |

#### 14.17.3 One tower's prepare, and what a throw at each step leaves

The question (*torn down? in the background?*) is asked before each step, and can throw at
each. `CompiledCache.prepare` tracks Core ML's output from the moment it exists.

| Thrown as this step is reached | Exists at that moment | Left afterwards |
|---|---|---|
| `CHECK_SPACE` | nothing | nothing |
| `UNPACK` | nothing | nothing |
| `COMPILE` | the unpacked package | nothing |
| `MOVE` | the package; **Core ML's output, outside the cache** | nothing. ⚠ The output leaked here in the first cut: the stop came after it was assigned and before the block that deleted it (review finding 1) |
| `DELETE_PACKAGE` | the package; the compiled directory | nothing |
| `FIRST_OPEN` | the compiled directory | nothing |
| `MARK_READY` | the compiled directory, opened once | nothing |

**And when a primitive itself fails** (round 1). A primitive that did not do its
work throws; none answers a success flag. The deletes answered a `Boolean` at first, and the
one call that did not look at it (`DELETE_PACKAGE`) went on to open the tower and mark it
ready beside a package it had not deleted, and the sibling tower was compiled on top.

| The primitive that fails | At | Left | Reported |
|---|---|---|---|
| a delete of what an earlier prepare left (marker, directory, staging) | before `CHECK_SPACE` | what could not be deleted | `Failed(role, could not delete …)`; nothing is written beside it |
| the space check | `CHECK_SPACE` | nothing | `Failed(role, needs N bytes free …)` |
| the unpack | `UNPACK` | nothing | `Failed(role, …)` |
| the compile | `COMPILE` | nothing | `Failed(role, …)` |
| the move | `MOVE` | nothing (Core ML's output is deleted) | `Failed(role, …)` |
| ⭐ the delete of the package | `DELETE_PACKAGE` | the package, if it still cannot be deleted | `Failed(role, could not delete …)`; **no first open, no marker** |
| the first open | `FIRST_OPEN` | nothing | `Failed(role, …)` |
| the marker's write | `MARK_READY` | nothing | `Failed(role, …)` |
| a delete **in the cleanup** after any of the above | — | what could not be deleted | the first failure's words, *and* what was left |
| a delete in a discard (a recompile) or in a sweep | — | what could not be deleted; a marker that could not be removed leaves its directory untouched | `Failed(cache, …)`; nothing is compiled, and a recompile is not remembered as made |

In every row the towers after the one that failed are **not compiled** in that call.
- **Who reclaims what a failed cleanup left:** the staging area and a digest's directory are
  inside the cache, so the next call's sweep meets them first (the staging area is an entry
  like any other; a directory with no marker is cleared by that tower's next prepare). While
  they still cannot be deleted, every call ends there and says so.
- ⚠ **Core ML's own output** is outside the cache. If its delete fails, it is said once, in
  that outcome, and nothing of this app reclaims it afterwards: the path is not kept, and it is
  in the system's temporary directory [I: the system's to clear].

`aPackageThatCouldNotBeDeletedIsNotATowerThatIsReady`, `whatTheCleanupCouldNotDeleteIsSaidWithWhatWentWrong`,
`coreMlsOutputThatCouldNotBeDeletedIsSaid` and `whatAnEarlierPrepareLeftAndCannotBeDeletedIsNotCompiledOver`
pin the delete rows.

`aStopAtAnyStepLeavesNothing…` and `aStepThatThrowsAtAnyPointLeavesNothingOfTheTower` run
every row on the JVM; `aStopAtAnyOfTheSevenStepsLeavesNothingNotEvenCoreMlsOwnOutput` runs
every row against the real cache on the simulator.

#### 14.17.4 What the fake is, and what stands in for what

`FakeCompiledFiles` is the **primitives**: unpack, compile (its output outside the cache, as
Core ML's is), move, open, write a marker, delete (which can fail and leave things), list
(which can refuse), read a marker (which can be there and give no bytes). The cases run the
real `ModelPreparer`, the real `CompiledCache`, the real `AiReadiness` and the real
`DefaultModelStore`.
- ⚠ In the preparer's cases the probe is a function the case answers for. The preflight's own
  half (its backoff and the pass past it, what it holds and from when, that it does not begin
  beside a prepare, which failures are before any tower) runs on the real `Preflight` in
  `PreflightTest`. No case runs the real `Preflight` *and* the real preparer together.
- The real primitives run on the simulator (`CoreMlModelCacheTest`), with a recording
  compiler in place of Core ML.
- One case goes through Photos' real graph on the simulator: a commit leads to a prepare
  under the process's lock, through the real cache, which refuses that case's archives.
- ⚠ No case joins the preparer to a tower Core ML really compiles.

#### 14.17.5a Decisions, each with its alternative

| # | Taken | The alternative, and why not |
|---|---|---|
| 1 | The prepare holds the step lock for its whole length | Compiling outside it and re-checking afterwards: every check after a compile would be a check-then-act across seconds. Cost: a report, a cancel or a commit waits about 9 s [M, one phone] |
| 2 | The sweep is the delete-before-compile and the crash reclaim; a removal's eviction is the same sweep, made in the removal's step | One mechanism per trigger |
| 3 | Compiling only in the foreground; **deleting anywhere** | A removal's eviction waiting for the foreground: 1.8 GB stayed until Photos was opened again, and `DeleteModel` returned before the bytes were gone |
| 4 | A tower that will not load is recompiled once **per process**; a failure before any tower is not | A durable flag. The durable bound is the preflight's count |
| 5 | The probe after a recompile is not held back by the backoff, and **the count carries over** | Resetting the count for recompiled towers: a model that never loads would then be compiled at every launch for ever. Scheduling the probe for after the backoff: a timer that outlives nothing useful |
| 6 | No probe beside a prepare (`cacheChanging`), instead of a stamp on each probe's answer | A generation stamp checked when the answer is recorded: the towers would still be loaded twice at once, which is what `beforeLoad` exists to prevent |
| 7 | Nothing is compiled beside a cache that could not be listed, or beside something a sweep could not delete | Compiling anyway: the disk budget assumes one generation |
| 8 | **The check is one sequence at a time** (`CoalescedRuns`), and a foreground is **the scene's** (a number that moves when the scene becomes active). ⚠ This row said the check was left unserialised, and recorded as a known overlap that two checks each counted a foreground: a recorded defect that could make a phone `Unsuitable`, durably, from one foreground (Codex round 5) | Serialising alone: two checks in one foreground, one after the other, are still two calls. The number alone: the journal's resume, the prepare and the probe of two checks would still interleave |
| 9 | ⚠ **D14 is not decided here.** The archive is kept | Dropping it after a successful prepare takes 743.4 MB off the steady state (1 836.4 MB instead of 2 579.8, §3.4) and makes a recompile, and every OS update that invalidates a `.mlmodelc`, a 743 MB download instead of ~9 s. A small change here (one more act after the markers), not in the store or in `installedHolds` |

**What 5d reads** while a model is not `Ready`: readiness's state; `Preflight.lastReport`;
and from the preparer `lastReport` (the install's: `Failed(role, why)` carries *needs N bytes
free*, `Incomplete(why)` the missing vocabulary, `Unreadable`) and `lastRefusal` (the last
call never read an install: *not in the foreground*, *could not be read*). ⚠ Nothing in the
product reads either yet.

**An install that is `Incomplete`** (the vocabulary's file gone, or another size) is repaired
by a download of the model, which fetches what is missing (§14.11). ⚠ Nothing starts that
download by itself.

#### 14.17.5 Disk, re-derived

§14.13's tables stand, and the code now agrees with the update table's third row: the old
compiled generation goes before the new one compiles.
- **A first install** peaks at 2 579.8 MB during the text tower's prepare, as budgeted.
- **An update of both towers** peaks as §14.13 has it: 5 159.6 MB, or 4 419.1 if deleting the
  old compiled directories returns their 740.5 MB of `.mlmodelc` files [I]. The delete comes
  first, so the second figure is the one the code can reach; the old generation's first-open
  space (about 1 096 MB, the system's) is not this app's to return, and whether the system
  drops it with the directory was not measured.
- **The budget asked before a download** (`CoreMlDiskBudget.additionalNeed`): the bytes still
  to fetch, the larger of the measured prepare (scaled from the whole entry's ratio) and the
  cache's own per-tower check summed over the whole entry (`towerNeed`, 3 × the entry: 2 230.2 MB
  for the measured download, above the measured 1 836.4), and the margin. ⚠ Corrected in the
  hardening before 5e: it asked for the measured prepare alone, which is less than the cache's
  checks ask (below). ⚠ Still approximate per artifact: by §9.7's readings
  the vision tower's step takes 3.0 × its archive and the text tower's 2.3 ×, against the
  entry's 2.47 ×, so an update of the vision tower alone is asked for about 91 MB too little
  and one of the text tower alone about 96 MB too much. Inside the 256 MiB margin, which is
  a placeholder. Not changed: the function is given two sums, not the roles.
- ⚠⚠ The cache's own check before a tower (3 × the archive, one phone's reading) is **the
  same estimate** as the budget's, `CoreMlDiskBudget.towerNeed`. It was a second estimate, and
  this sentence said it refused a prepare the budget let through "only when the disk filled in
  between". That was wrong: with the vision tower first, a phone admitted with the least the
  budget allowed (2 104.8 MB free after the download) had 1 549.8 MB left at the text tower,
  whose check asks 1 665 MB — a band of about 115 MB passed the download and was refused at
  the text tower. With the budget at Σ 3 × archive every tower's check finds what it asks, in
  either order, while no earlier tower took more than its own 3 × (held by
  `aDownloadTheBudgetAdmitsPassesEveryTowersOwnCheckInEitherOrder`). Now it is true: only a
  disk that filled in between refuses one. Cost: about 394 MB more free space asked of a
  first download.

#### 14.17.5b Guards, each removed once and seen red

Thirty-seven, all red (34 on the JVM, 3 on the simulator); every file restored by hash. Each row is the mutation, named for what it makes the code do, and the first case that went red. They replace the 22 of the first cut, whose text the rework moved: every one of those guards is in this table in its new form.

| The mutation | Red |
|---|---|
| nothing is swept | `aCacheThatCannotBeListedIsNotCompiledBeside` and 7 more |
| compiled beside a cache that could not be listed | `aCacheThatCannotBeListedIsNotCompiledBeside` |
| a delete that failed is ignored | `aDeleteThatDidNotDeleteIsNotADelete` |
| the question is not asked at each step | `aStopAtAnyStepLeavesNothingOfTheTowerNotEvenCoreMlsOutput` and 1 more |
| the foreground is not asked | `aStopAtAnyStepLeavesNothingOfTheTowerNotEvenCoreMlsOutput` and 1 more |
| a retired graph is not asked | `aTeardownUnderACompileLeavesNoMarkerAndTheGraphWritesNothingMore` and 1 more |
| an unreadable marker is compiled over | `aMarkerThatCannotBeReadIsNotATowerToCompile` |
| an unreadable index is no record | `anIndexThatCannotBeReadOrIsNotAnIndexSweepsAndCompilesNothing` and 1 more |
| towers swept against bytes that were no index | `anIndexThatCannotBeReadOrIsNotAnIndexSweepsAndCompilesNothing` |
| a prepare with nothing to do unserves the model | `aPrepareWithNothingToDoDoesNotTouchAModelThatIsBeingServed` and 1 more |
| a prepare with nothing to do asks for a probe | `aFailedProbeMadeByTheForegroundCheckLeadsToTheRecompileToo` and 2 more |
| a model is recompiled every time | `aTowerTheProbeCouldNotLoadIsCompiledOnceMoreAndProbedAtOnce` |
| a vocabulary failure recompiles the towers | `aProbeThatFailedBeforeAnyTowerWasOpenedCompilesNothing` |
| a recompile discards whatever is installed now | `aRecompileIsNotMadeOfAModelThatWasReplacedUnderTheProbe` |
| a discard that failed is taken for made | `aRecompileThatCouldNotDeleteTheTowersIsNotReportedAsMade` |
| a returned prepare is taken for a marker | `aPrepareThatReturnsAndLeftNoMarkerIsNotReportedAsPrepared` |
| an incomplete install is said prepared | `anInstallReadinessDoesNotReadAsPreparedIsNotReportedAsPrepared` |
| no step lock | `noStoreChangeAndNoOtherGraphRunsUnderACompile` |
| nothing waited for before a load | `noProbeBeginsBesideAPrepareAndAProbeThatHadBegunIsWaitedFor` |
| what the cache throws leaves the call | `whateverTheCacheThrowsIsAnOutcomeForThisInstallAndNeverLeavesTheCall` |
| a refusal about no install erases the installs outcome | `noRoomIsSaidForThisInstallAndStaysSaidWhilePhotosIsAway` |
| an eviction waits for the foreground | `aRemovalEvictsTheCompiledTowersInItsOwnStepWhereverPhotosIs` |
| a tower is written under a served model | `aTowerIsNeverWrittenWhereAModelIsStillBeingServedFrom` |
| the compilers output is not cleaned | `aStepThatThrowsAtAnyPointLeavesNothingOfTheTower` and 1 more |
| the marker is not removed first | `aDiscardTakesTheMarkerFirstSoAHalfDeletedTowerIsNeverReady` |
| a sweep reports what it asked to delete | `aDeleteThatDidNotDeleteIsNotADelete` |
| a probe begins beside a prepare | `noProbeBeginsWhileTheCompiledCacheIsChangingAndAProbeHoldsFromTheMomentItIsLetThrough` |
| a probe holds only once a tower opens | `noProbeBeginsWhileTheCompiledCacheIsChangingAndAProbeHoldsFromTheMomentItIsLetThrough` |
| the probe after a recompile backs off | `theProbeAfterARecompileIsNotHeldBackByTheBackoffOfTheProbeThatFailed` |
| a vocabulary failure is not told apart | `aFailureBeforeAnyTowerWasOpenedSaysSo` |
| an installed step is not said | `aCommitIsSaidOnceItsStepHasEndedARemovalInItsOwnStepAndAFailureIsNot` and 1 more |
| only a commit made here is said | `aCommitTheNextStepFindsAlreadyInTheStoreIsSaidToo` |
| a removal evicts nothing in its step | `aCommitIsSaidOnceItsStepHasEndedARemovalInItsOwnStepAndAFailureIsNot` |
| bytes that are no index are an index | `anIndexThatCannotBeReadOrIsNotAnIndexSweepsAndCompilesNothing` |
| a marker that gave no bytes is absent | `aMarkerThatIsThereAndGivesNoBytesIsUnreadableNotAbsentAndIsNotCompiledOver` and 1 more |
| compiled path answers null for unreadable | `aMarkerThatIsThereAndGivesNoBytesIsUnreadableNotAbsentAndIsNotCompiledOver` and 1 more |
| the compilers output is not dropped | `aFailedMoveDeletesTheCompiledModelCoreMlLeftInItsTemporaryPlace` and 1 more |

⚠ Not mutated: that a failed delete on the device is reported (pinned on the JVM fake only).

After Codex round 11 (one more, red): a retired graph's party waiting for the head of the
chain.

After Codex round 10 (five more, all red): any model's removal discarding this model's towers;
every store change through this model's writer; the sweep keeping only this model's towers;
a commit that does not say which model; a removal hook told no model.

After Codex round 9 (three more, all red): a party that waits for its own graph's unload; an
unload that does not wait for the probe to let go; one that does not wait for the one before.

After Codex round 8 (five more, all red): the probe after a recompile not owed; paid by one
that did not run; given to another install; never paid; a probe the scene left under recorded.

After Codex round 7 (four more, all red): the key compared by its towers alone; an answer not
checked when it arrives; not checked again under the lock; a recompile that was not made
remembered as made.

After Codex round 6 (five more, all red): an install that awaits its verdict not probed; a
probe asked whatever the state; the scene published in two steps (a two-thread case on the
JVM); every announcement a new foreground; a request nobody waits for that does not run.

After Codex round 5 (eight more, all red; one on the simulator): a foreground with any number
of slow probes; a slow probe not noted against its foreground; a probe with no foreground to
count it in; every request of a check running; a request made while one runs dropped; two
sequences side by side; a launch among the requests lost (survived first, then pinned); every
announcement of the scene a new foreground.

After Codex round 4 (six more, all red; one on the simulator): a failed eviction only
recorded; a removal that does not ask before it removes; an eviction that waits for the
foreground; towers deleted under a model still served; a pass made by the prepare not taken
for one; the warm-up decided by the foreground's probe alone.
- **The warm-up after a pass** (the P3 of that round): `recheck` kept only the foreground
  probe's answer while the prepare step made a probe of its own after a compile, so a pass
  made there was not warmed after. ⚠ Superseded in round 6: a check has **one** probe
  (`ModelPreparer.check`), so there is one answer, and `PhotosOnDeviceAi.warmsAfter` reads
  it; the warm-up is one call behind the same consent gate. A foreground that compiles and
  then warms, in Photos' graph, is not run.

After Codex round 3 (four more): an answer acted on whatever install it is about; whatever
was compiled since; a compile that does not move the generation; a probe that lets go when
its towers close. All four red.

After Codex round 2 (four more, all red): a change that does not wait for a probe that had
begun; one that lets a probe begin beside it; a write that leaves the memo saying *no index*;
a write that does not move the process's count.

After Codex round 1 (six more, all red): a package that was not deleted passed over; what the
cleanup left not said; what an earlier prepare left compiled over; a sweep counting a failed
delete as removed; a failed discard taken for made; the cleanup stopping at the first thing
it could not delete.

#### 14.17.6 Still owed, and not proved

- **Nothing on a phone.** Not run anywhere: a real compile under the preparer; the real
  preflight after it; a suspension or a kill under a compile; the scene phase reaching
  `PhotosOnDeviceAi.foreground`.
- **The real `Preflight` and the real preparer together** (§14.17.4).
- **Core ML's temporary output after a kill mid-compile** is the system's to clear [I].
- **A delete that fails on the device**: the primitives answer from a re-stat, and that a
  failed `removeItemAtPath` is reported is pinned on the JVM fake only.
- **`CoreMlModelCache.preparedOnOsBuild`** still reads a marker with one answer for absent and
  unreadable. It decides nothing (the harness reads it).
- **A sweep that cannot list its directory** is pinned on the JVM fake only.
- **The check in Photos' graph**: `CoalescedRuns` and the preflight's count are each run on their own; two overlapping checks through the real `recheck`, in one foreground of a real scene, are not.
- **On-phone checklist, added to §14.16's** (same preconditions):
  7. After a download completes in the foreground: `Preparing` for about ten seconds, then
     `AwaitingVerdict`, then the preflight's answer. Time each tower.
  8. Send Photos to the background the moment a download completes; reopen it. The model is
     compiled then, and `inference/coreml` holds two directories and no `tmp`.
  9. Kill Photos during *Preparing* (Xcode stop). Reopen: the tower is compiled again; no
     directory without a `ready` file is left afterwards.
  10. Delete the model, also with Photos sent to the background at once: `inference/coreml`
      is empty when `DeleteModel` has returned, and the model is gone from the list. (A
      delete that fails leaves the model listed; that cannot be provoked by hand.)
  11. An update (a second catalog entry): free space before the new prepare, and at its peak.
  12. With a model `Ready`, leave and return to Photos ten times: the state does not move and
      a search in flight is answered.

#### 14.17.7 The whole-diff review of 5c-2 (at `834886fac`)

Ten findings; all ten were right.

| # | Found | Now |
|---|---|---|
| 1 | A stop at `MOVE` leaked Core ML's output | The step order and its cleanup are common code that tracks the output (§14.17.3); every step is a pinned throw point, on the JVM and against the real cache |
| 2 | Every `prepare` un-served a `Ready` model | A call decides before it touches readiness; a no-op writes nothing; a case reaches `Ready` |
| 3 | The probe after a recompile never ran (the backoff) | `Preflight.recompiled()` passes the backoff; the count carries over |
| 4 | A failure before any tower recompiled both | `PreflightFinding.Failed.beforeAnyTower`; only a tower's failure recompiles |
| 5 | A probe could overlap a sweep or a recompile | `cacheChanging`, and `holding` claimed under readiness's lock |
| 6 | A discard did not take the marker first; failed deletes read as done | Marker first; every delete answers from a re-stat; a failed one is an outcome |
| 7 | A commit found already in the store was not said | Said by every step that answers *installed*, decided under the lock; the count of commits is gone |
| 8 | `Prepared` for an install readiness does not read as prepared | `Incomplete(why)`, by readiness's own reading |
| 9 | A removal's eviction waited for the foreground and was asynchronous | Made in the removal's step |
| 10 | An unkeyed refusal erased the install's outcome | `lastReport` and `lastRefusal` are two things; what 5d reads is written down |

Also corrected: the store's *no records* for bytes that were not an index is no longer read
as *no model* (`holdsAnIndex`); the readiness lock is not held across a compile.

**Codex round 2**, the store's half: `holdsAnIndex` was answered from a flag kept beside the
memoised records, and a write replaced the records and left the flag. After a commit over
bytes that were not an index, the store answered a valid index and still said it held none,
and a removal's eviction refused to delete. The memo is now **one value** (`Index(records)` or
`NotAnIndex`), replaced whole by every read and every write. The other read cases, checked
the same way: a read that *failed* keeps nothing (the memo is forgotten); an index of
*another build's shape* is remembered for the process (`undecodable`), rightly, because
nothing can write over it: every write reads first and is refused. A write moves the
process's count, so a second store drops its own `NotAnIndex` at its next question
(`anIndexWrittenOverBytesThatWereNoIndexIsAnIndexAgain`).

### 14.18 Slice 5d (2026-10-13): the Models screen. Designed; 5d-1, the sources, 5d-2a, the shared view model, 5d-2b, the iOS repository, and 5d-3, the SwiftUI screens, built

§14.18.0–9 are the design pass, written before any code; §14.18.10 is what 5d-1 built,
§14.18.11 the plan the maintainer's decision on M3 calls for, §14.18.12 the design of 5d-2a,
§14.18.13 what it built, §14.18.14 the iOS repository (5d-2b), designed and as built, and §14.18.15 the SwiftUI screens (5d-3). No schema anywhere in it. Marks as above: [C] read
in the code, [D] decided in a doc, [I] inferred, [M] measured. Decisions that are the
maintainer's are numbered **M1–M12** in §14.18.9; all twelve were decided on 2026-10-13, and ⚠ **M3 was
overruled**: there is one shared view model, not a second one for iOS (§14.18.11).

#### 14.18.0 Where the screen lives, and what exists today

**Already decided, not invented here:** on iOS the Models and consent screen is **Photos'**, in
Photos' Settings (`photos-ios.md` §7 Q2, **settled by the maintainer on 2026-10-13**, and PIO-D10; §14.1's 5d row; §1.6). The alternative, a
Models screen in Control-on-iOS, needs a cross-process lock on the stores that does not exist.
`inference-service.md` §34's *Settings → Models is the only route to a model* holds per
platform: Control's on Android and desktop, Photos' on iOS.

| Platform | What is built | Where |
|---|---|---|
| Android, desktop | The whole screen: models, indexing, features as a device list (§38) | `ModelsScreen`, `ModelFeatureScreen` in `:control-shared` (Compose) over `ModelsViewModel` and `ClientModelsRepository` in `:control-shared-logic` [C] |
| Control-on-iOS | **No Models screen**, and none is planned: `SettingsView.swift` has no such row [C]; Control binds a store that never writes (PIO-D10, lock 4) | — |
| Photos-on-iOS | **No Settings screen at all.** `PhotosIosApp.swift:423` reserves *"Settings … behind a toolbar button on Library (PIO-5)"*, and PIO-5 is not built [C]. No iOS graph constructs `ModelsViewModel` (§11) | — |
| Mia-on-iOS | Three refusal lines that say *this device* and name Control (§1.8) | `AskMiaImplementation.kt` [C] |

So 5d builds the **first** Settings entry in Photos-on-iOS. It builds only the Models
destination and the toolbar button that leads to it; PIO-5's rows (sources, album picker,
skip-duplicates, play-videos) join that list later (M1).

**The existing view model does not fit iOS as it is.** Read against the last two slices [C]:

| What `ModelsViewModel` / `ClientModelsRepository` does | Why it is wrong on iOS |
|---|---|
| *Downloading* is the view model's own memory: `busyModelId`, set while its `download()` call is suspended | A download outlives the screen and the process (§14.11). A reopened screen shows a Download button over a running download |
| A model is `installed: Boolean`, from `InstalledModels` | Installed is not usable: `Preparing`, `AwaitingVerdict`, `Unsuitable`, and an `Incomplete` or failed prepare are all *installed* |
| A failed `InstalledModels` is `emptyList()` (`.getOrNull()…orEmpty()`) | An unreadable index shows *Download* (`tech-debt.md`, the store's read contract, item 2) |
| `download(modelId)` has no cellular choice; the op's implementation passes `false` (`BackgroundModelDownloader.kt:534`) | D6 offers *Use cellular data* per download |
| No cancel | §14.11 ⚠ᵇ |
| `RemovedSpacePending`: *"space is released when Control next starts"* | On iOS the compiled towers go inside the removal (§14.17.5a item 3); only the archives wait for the next process |
| `OfferedConsents` is one global list with `FACE_CLUSTERING` in it | No face backend exists on iOS: a switch that changes nothing |
| The repository takes Control's `SyncRepository` (device id, roster) | Photos' graph has none; the only iOS implementations are Control's surface and its fakes [C] |
| The sentences are Kotlin in `:control-shared`, and name Control | §11 |

#### 14.18.1 Scope

**In 5d**

- The Settings entry and the Models screen in SwiftUI, over a view model in commonMain.
- One model row whose state is read from the real sources (§14.18.2, §14.18.3): the catalog
  row, the download size and the on-disk estimate with the first-open allowance (§3.4), the
  bar, *Preparing*, *Checking*, why a phone is unsuitable, delete, cancel, update.
- The consent for *Search photos by description*, in §38's shape (M4), with the first-install
  question (§38.3).
- Photos' own wording for `noConsent` under the search bar, and Mia's three iOS lines (§1.8).
- `:inference-service` exported from the umbrella (§11).
- The observable seams the screen needs, all in Kotlin and none on the wire (§14.18.4).

**Stays for 5e** (unchanged from §14.1): `IosIndexingConditions`, *Index now*, the pass, the
coverage line, pause, thermal copy, `closeSessions()` on a memory warning, the privacy
manifest, D12 ⓪'s iOS 17 simulator load, and the flag.

**Not in either, recorded so it is not assumed:** PIO-5's settings rows; a second Core ML model
(`tech-debt.md` item 16: the screen shows the pinned pair's state only, and says nothing false
about another entry because it lists none, §14.18.3 row 3); any wire change (§14.18.4).

⚠ §14.1's 5d row says *"Index now refuses: conditions are still `Never`"*. A button that can
only refuse is a claim the screen cannot keep (`control-cross-app-open.md` §5g.2's rule, and
`OfferedConsents`' own). This design shows **no indexing section** until 5e (M5).

#### 14.18.2 The sources, and the one value the screen renders

Everything below is in Photos' process. *Observable* says whether a screen can follow it today.

| # | Source | Says | Observable today? | Survives a relaunch? |
|---|---|---|---|---|
| G | `AiStaticGates.decide` | admitted, or why not (flag, iOS 17, memory) | ⚠ **No.** The reason is printed and dropped (`PhotosOnDeviceAi.admitted`) | recomputed |
| R | `AiReadiness.state` | `NoModel` · `Preparing` · `AwaitingVerdict` · `Ready` · `Unsuitable` | yes, a `StateFlow` | the verdict file |
| C | `AvailableModels` (op) | the verified catalog for this runtime, or `NO_CATALOG` | a one-shot read | fetched again |
| I | `InstalledModels` (op) | the records, or a failure | a one-shot read; ⚠ its failure has no reason of its own | the index |
| D1 | `BackgroundModelDownloader.isDownloading` | a journal names this model | ⚠ a suspend poll | the journal |
| D2 | `…outcomeFor(key)` | `Installed` · `Refused(why)` · `Failed(integrity?)` · `Cancelled` | ⚠ **No**: a plain field, no flow; needs the entry's whole `ModelDownloadKey` | ⚠ **No**, in memory. A refusal-for-now is said again by the launch's `resume`; a failure is not |
| D3 | `ModelDownloadProgressHolder.progress` | bytes of the whole entry | yes | restarts at what is staged |
| D4 | the journal's cellular choice | Wi-Fi only, or cellular allowed | ⚠ **No** | the journal |
| P1 | `ModelPreparer.lastReport` | the install's: `Prepared` · `Incomplete(why)` · `Failed(role, why)` · `Unreadable` | yes | ⚠ no; the launch's check re-records it |
| P2 | `ModelPreparer.lastRefusal` | the last call read no install: `NotInForeground`, unreadable | yes | no |
| P3 | *a prepare is running now* | compiling | ⚠ **No**: `cacheBusy` is readiness's private mark | — |
| V | `Preflight.lastReport` | `Waiting(why)` · `Retry(finding, attempts)` · `NoProbe` · `Unreadable` · `InstallFailed` · `Unsuitable(finding)` · `Busy` | yes | ⚠ no; re-recorded by the launch's check, since `InstallFailed` and the counts come from the verdict file |
| K | *the first check of this process has ended* | P1, P2 and V mean something | ⚠ **No** | — |
| S | the two consent rows | realm, this device | yes (`consents()`) | synced / device row |
| N | the network path | Wi-Fi or not | Swift only (`NWPathMonitor`); nothing in Kotlin | — |

**One joined value.** The screen renders `ModelSetup`, a sealed plain-data type in
`:control-shared-logic`, produced by one pure function `modelSetupOf(G, R, C, I, D, P, V, K)`.
The function is the table of §14.18.3 and is tested as that table. The Swift renders the
value; it decides nothing (the doctrine of `photos-ios.md`: decisions in tested commonMain).

- **Where each piece lives.** `:control-shared-logic` cannot see `:inference-service-proxy`
  (§11), so the types the proxy speaks cannot be the screen's:
  - `:control-shared-logic`, `app.tenshu.control.inference`: `ModelSetup`; and
    `OnDeviceModelSource`, an interface in the pattern of `IndexCoverageSource` (PR-4):
    `setup: Flow<ModelSetup>`, `download(modelId, allowsCellular)`, `cancel(modelId)`,
    `checkAgain()`.
  - `:control-shared-proxy` commonMain: `modelSetupOf`, over the proxy's own types. It is
    reachable from the desktop tests, so the table runs on the JVM.
  - `:control-shared-proxy` iosMain: the implementation, bound in `photosOnDeviceAiModule`
    **under the interface** (PR-4's `ModelsHolderBindingTest` rule: a holder bound under its
    concrete type is silently absent).
  - `:inference-service-proxy`: the downloader's `outcomeFor` becomes a `StateFlow` of its
    last report; *a journal names this model* and its cellular choice become readable as one
    value; a *preparing now* flow beside `cacheChanging`; the gate's decision kept.
- **Delete stays the operation** (`DeleteModel`). It already ends a download first
  (`withoutDownloadOf`), evicts the towers and fails typed. Nothing in-process is needed.
- **Off iOS** `OnDeviceModelSource` is not bound. ⚠ Superseded by M3: the view model never
  sees the source; each platform's `ModelsRepository` does (§14.18.11).
- ⚠⚠ **Rule for the join: memory is never ahead of disk.** A source that is in memory only
  (D2, P1, P2, V) may *qualify* a state that R, I or D1 established from disk. It never
  establishes one. *Installed*, *Ready*, *Downloading* are only ever R, I and D1.

#### 14.18.3 The states

One table. *Cannot express* is about today's API: **W** = not on the wire (irrelevant to this
screen, §14.18.4); **K** = no Kotlin seam yet (5d adds it, §14.18.2); **✗** = nothing can say
it and 5d does not add it.

Sizes: *743 MB* is the catalog's download figure as the copy prints it, in decimal MB (the sum of the three artifacts, 743 448 870 bytes; the ~709 MB used elsewhere is the same download in MiB, corrected 2026-10-14); *about 2.6 GB* is §3.4's on-disk figure with
the first-open allowance counted as kept [M]. Both are computed, never literals in the copy.

| # | When (the sources) | The screen says | Offered | Cannot express |
|---|---|---|---|---|
| 1 | G: flag off | **no Settings → Models row** | — | — |
| 2 | G: refused (iOS below 17, or memory below the floor ⚠ a placeholder, 6.5 GiB) | *"Search by description isn't available on this iPhone."* and which of the two | nothing: no download, **no consent switch** (it would change nothing here) | **K**: the reason is dropped today. ⚠ A model a more permissive build left is not shown and cannot be deleted (`tech-debt.md` item 11) |
| 3 | K false, or C and I not read yet | *"Loading…"* | — | **K** |
| 4 | I failed | *"Couldn't read what's installed on this iPhone."* | **Try again** only. ⚠ No Download, no Delete | **W**: no reason for it (`TRANSPORT_FAILED`). In-process: the failure itself is enough |
| 5 | C failed, I empty, D1 false | *"The list of models couldn't be loaded."* | Try again | — (`NO_CATALOG` exists) |
| 6 | C lists nothing for this runtime, I empty | *"No model is available for this iPhone yet."* | — | — |
| 7 | `NoModel`, D1 false, no outcome | name · *743 MB download · about 2.6 GB on this iPhone* · licence | **Download** (Wi-Fi), with *Use cellular data* as a second, explicit choice (D6) | **W**: the op carries no cellular choice |
| 7a | as 7, D2 `Refused(InsufficientStorage(needed, free))` before any journal | as 7, and *"Needs N GB free. This iPhone has M GB."* | Download | **W**: `TRANSPORT_FAILED`, bytes in a sentence |
| 7b | as 7, D2 `Refused(FreeSpaceUnknown)` | *"Couldn't check the free space."* | Download | **W** |
| 7c | as 7, D2 `Failed(integrity = false)` | *"The download didn't finish."* | **Try again** | — |
| 7d | as 7, D2 `Failed(integrity = true)` | *"The download didn't match what was published. Trying again will fetch the same file."* | Download, not worded as a retry | — |
| 7e | as 7, D2 `Cancelled` | as 7 | as 7 | — |
| 8 | D1 true, bytes moving, no refusal | bar · *"X of 743 MB"* · *"You can leave Photos; the download continues."* | **Cancel** | **K** (D1 as a flow); **W**: no cancel op |
| 8a | as 8, D4 Wi-Fi only, N says no Wi-Fi | bar, still · *"Waiting for Wi-Fi."* | Cancel. ⚠ **No *use cellular now***: the first choice stands and the other is refused (§14.14 item 8), so it is *cancel and start again*, which drops what was fetched (M6) | **K** (D4); N is Swift's. ⚠ Shown only while the screen is open: nothing records it |
| 8b | D1 true, D2 `Refused(InsufficientStorage)` (a restart was refused; the record is kept) | bar, still · *"Paused. Needs N GB free; this iPhone has M GB."* | **Try again** (`checkAgain`), Cancel | **K**. ⚠⚠ The stream alone looks stalled (§14.11) |
| 8c | D1 true, D2 `Refused(FreeSpaceUnknown \| NoTransport \| TransfersUnknown \| StoreRefused)` | bar, still · *"Paused. Photos couldn't continue the download just now."* (one sentence each) | Try again, Cancel | **K** |
| 8d | D1 true, D2 `Refused(JournalUnreadable)`, or D1 could not be read | *"Couldn't read the download's record."* | Try again. ⚠ Cancel and Delete are **offered and may fail the same way** (both read the journal); the failure is then said | ✗ **No way out but a retry** while the journal stays unreadable (§14.18.6, I4) |
| 8e | D1 true, D2 `Failed`, the record could not be removed | *"The download didn't finish."* | Try again (a `download` joins the record), Cancel | **K** |
| 8f | D1 true, nothing in D2, bytes not moving, N has Wi-Fi | bar, still. ⚠ **Nothing is claimed**: slow and stuck are not told apart | Cancel | ✗ |
| 9 | `Preparing`, P3 true | *"Preparing… Keep Photos open. About ten seconds."* [M, one phone] | Delete (it waits for the step) | **K** (P3) |
| 9a | `Preparing`, P1 `Failed(role, why)` | *"Couldn't prepare the model."* and `why` | Try again, Delete | ✗ *needs N GB* is inside `why`, an English sentence: not typed, not localisable (M8) |
| 9b | `Preparing`, P1 `Incomplete(why)` | *"Part of the model is missing."* | **Download again** (it fetches only what is missing, §14.17.5a), Delete | — |
| 9c | `Preparing`, P1 or P2 unreadable | *"Couldn't read the model's files."* | Try again, Delete | — |
| 9d | `Preparing`, none of the above, K true | *"Preparing…"*. ⚠ Reached between a commit and its check, for a moment | Delete | — |
| 10 | `AwaitingVerdict`, a probe running or owed, V empty or `Busy` | *"Checking this iPhone…"* | Delete | — |
| 10a | `AwaitingVerdict`, V `Waiting(why)` or `Retry(Slow \| Transient)` | *"Checking paused. Photos will try again the next time you open it."* and, for heat, *"iPhone is warm."* | Delete. ⚠ **No *check now*** (decided 2026-10-12: no timer, as built) | ✗ `why` is a sentence; *warm* needs it typed (M8) |
| 10b | `AwaitingVerdict`, V `Retry(Failed)` | *"Checking…"* | Delete | — |
| 10c | `AwaitingVerdict`, V `InstallFailed` | *"The model didn't work on this iPhone after it was installed."* | **Delete**, and the sentence says *remove it and download it again*. ⚠⚠ **Not *Download again***, which `InstallFailed`'s own comment promises: a `download` of an entry that is installed whole fetches nothing and commits nothing (§14.11, S3 ✓³), so writer 3 never runs and the count that stops the probe is never cleared (§14.7 item 11). The removal is what clears it (M12) | — |
| 10d | `AwaitingVerdict`, V `NoProbe` | *"This version of Photos can't check this model. Update Photos."* | Delete | — |
| 10e | `AwaitingVerdict`, V `Unreadable` | *"Couldn't read the result of the check."* | Try again, Delete | — |
| 11 | `Ready`, catalog at the installed version | *"Ready"* · the on-disk figure | Delete | — |
| 11a | `Ready`, C failed | as 11. ⚠ No update is claimed with nothing to compare | Delete | — |
| 11b | `Ready`, catalog newer, D1 false | *"Update available · size"* | Update (as 7's two choices), Delete | — |
| 11c | `Ready`, D1 true (an update, S4) | *"Ready"* and the bar | Cancel (the update only), Delete (ends the update, then removes) | **K** |
| 12 | `Unsuitable` (three slow probes, or two loads Photos did not survive, §14.5) | *"This iPhone couldn't run the model well enough, so search by description stays off."* | **Delete** (*frees about 2.6 GB*). ⚠ No Download. An update is offered as 11b: other bytes get their own check (M9) | ✗ after a relaunch only the fact is known, not the timings |
| 13 | a removal in flight | *"Removing…"* | — | — |
| 13a | the removal failed | *"Couldn't remove the model. It is still installed."* | Delete again | **W**: `TRANSPORT_FAILED` |

**The feature row**, below the model, for every row from 3 on. It is S alone and never
changes what the model row says (IN-D10: downloaded and enabled are two things):

| S | Model | The row says | Offered |
|---|---|---|---|
| on here | `Ready` | *"Search photos by description: on for this iPhone"* | the feature screen |
| on here | anything else | the same, and *"It works once the model is ready and your photos are indexed."* (5e-2 round 2; not over an unsuitable model) | the feature screen |
| not on here | a first install has just landed | §38.3's question, once | Yes (realm, then device) · Not now (writes nothing) |
| not on here | otherwise | *"Off for this iPhone"* | the feature screen |
| the device id is not known | any | the switch is disabled, and says so | — (never written under a placeholder, §9.2) |

#### 14.18.4 The wire: a memo

**The finding.** Photos-on-iOS is route 1: its client and its proxy are one process and one
build. For this screen *nothing has to cross the operation boundary that does not already*.
The operations it calls (`AvailableModels`, `InstalledModels`, `DeleteModel`) exist. Everything
else it needs is device-local state of the same process, and the repo's precedent for exactly
that is an interface in `:control-shared-logic` implemented by the proxy (PR-4:
`IndexCoverageSource`, `ModelStoreAccessSource`, `IndexProgressSource`). So **the wire matters
only for parity**: Control on Android and desktop, and any client that is not the proxy's own
process.

**What a schema change costs here** (HR 9, and what the last slices learned):

- A new reason **string** in `ModelUnavailable.Reasons` is not a reshape: the field is a
  `string`, by design (*"a new one changes no shape"*, `ModelUnavailable.kt`) [C]. No bump, no
  migration, no baseline change. These errors are device-local and never synced, so no peer
  misreads one (`inference-service.md` §39.5's test). An older client reads an unknown reason
  as the retryable bucket.
- A new **field** on `ModelUnavailable` or `ModelDownloadProgress`, a new **parameter** on
  `DownloadModel` (optional included), or a new **operation** is a reshape: `service(…,
  version = 3)` with its `SchemaMigration`, the regenerated `SchemaShapeBaseline`, a permission
  entry and a caller for a new op, and **every client and the hub move in lockstep** (the hub
  is a schema peer and is deployed by hand). Pre-release that is cheap in code and not free in
  coordination.

| Candidate | Needed for 5d? | Without it | Cost | What Android and desktop get |
|---|---|---|---|---|
| **A cancel operation** | **No.** `OnDeviceModelSource.cancel` | — | new op: bump + migration + baseline + permission + caller + lockstep | Nothing today: `DefaultModelDownloader` has no cancel to call (`tech-debt.md` item 3). Worth an op only with a cancellable in-process downloader |
| **The cellular choice on `DownloadModel`** | **No.** `OnDeviceModelSource.download(id, allowsCellular)` | — | a param is a reshape: the full bump | Nothing: the in-process downloader has no such flag |
| **`STORE_UNREADABLE`** (reason string) | No: row 4 reads the failure as a failure | — | a string, plus a sentence in each client | ⭐ Fixes a live defect on **every** platform once Control's repository stops reading a failure as empty. That repository fix is in 5d (it is the shared file); the reason string is optional on top |
| **`INSUFFICIENT_STORAGE`** (reason string) | No: rows 7a and 8b read the typed refusal | — | a string and a sentence | Nothing yet: only the iOS downloader checks space |
| **needed / free bytes as fields** of the error | No | the sentence | a reshape | as above |
| **A state on the progress stream** for *refused for now* | **No.** D2 as a flow says it | — | a field on `ModelDownloadProgress`: a reshape | Nothing: an in-process download has no such state |
| **Prepare / readiness state** as an op | No: R, P and V are in-process flows | — | new composites and an op | Nothing: there is no prepare step off iOS |

**Recommendation (M2): 5d changes no schema.** Every candidate is either unneeded on iOS or
buys nothing on the platforms that would receive it. The two that are real on Android and
desktop are **not** wire problems first:

1. The shared repository's `failed read = nothing installed` is fixed in 5d, in Kotlin, for
   all platforms: `ModelsSnapshot` gains *installed could not be read*, and Control's screen
   gets its sentence. ⚠ That changes `ModelsUiState`, which owes its Swift (none exists yet)
   and Control's Compose screen (which does).
2. A cancel on Android and desktop needs a downloader that can be cancelled before it needs
   an operation.

**The alternative, and when to prefer it:** if a second client of these states is planned (a
Models screen in Control-on-iOS, or a remote proxy), every in-process seam above has to become
an operation eventually, and doing it now, once, in one bump, is cheaper than twice. Nothing
planned says so today (PIO-D10).

⚠ What the in-process route costs: `OnDeviceModelSource` is a second door to the downloader
beside the operation. It is the screen's only, it is not exported beyond the umbrella, and the
test plan pins that both doors meet one journal (§14.18.7).

#### 14.18.5 The screen's actions × states

States are §14.11's **S0–S4** (download) and §14.17.2's **P0–P3** (prepare); their tables say
what the downloader and the preparer do and which case pins it, and are **not restated**. This
table is what the *screen* does and shows. ✓ = the layer below is pinned there; ⚠ = new in 5d.

| Action | S0 nothing | S1 / S2 downloading | S3 installed (P1–P3, any readiness) | S4 installed + update |
|---|---|---|---|---|
| **Download** (Wi-Fi) | row 8 at once, from D1, not from the tap ⚠ | not offered. A second tap that raced the first joins (§14.11 ✓²): one download, one bar | not offered, except row 9b's *Download again* (an incomplete install: it fetches what is missing) and 11b's *Update* | not offered |
| **Download**, the other cellular choice | — | not offered (row 8a). If it arrives anyway it is refused and nothing is touched ✓²; the screen says so and the bar stays | — | as S1 |
| **Cancel** | not offered | the record, then the transfers, then the staging (§14.11 ✓); the row returns to 7 **when D1 says so**. A cancel that fails is said and the row stays 8 ⚠ | not offered | the update only; the installed model is untouched (§14.11: *as S1; installed untouched*, ⚠ᵃ **not pinned in that state**: 5d-1 pins it, since the screen is its first caller) |
| **Cancel, then Download** | — | two steps of one downloader, in order (its step lock ✓). The second starts from nothing: the staging went with the cancel | — | as S1 |
| **Delete** (asks first) | not offered. ⚠ `DeleteModel` here answers `NOT_INSTALLED` and stops nothing (⚠ᵇ): so the screen never sends it, and **Cancel is the only way to end a download of a model that is not installed** | not offered (as S0) | the towers, then the record (§14.17.2 ✓). In P2 it waits for the compile's step, and the row says *Removing…* for that long. A failure leaves row 13a and the model installed | the update is ended first, then the model (§14.11 ✓¹³). If the download cannot be ended, nothing is removed ✓¹⁴ and row 13a says so |
| **Try again** (`checkAgain`) | re-reads C and I | the launch's sequence, coalesced: journal resume, prepare, probe (§14.17.5a item 8 ✓). Never a second one beside a running one | the same | the same |
| **Leave the screen** | nothing | **nothing stops** (*a caller that stops waiting stops nothing*, §14.11 ✓). The view model's collectors die; the next open reads D1 and the bar from the holder ⚠ | a prepare or a probe goes on | nothing stops |
| **Photos to the background** | nothing | transfers continue in the system's session; nothing is settled until the foreground (§14.14 item 3 ✓) | a compile stops at its next step and is redone at the foreground ✓; a probe the scene left under records nothing ✓ | as S1 |
| **Return to the foreground** | — | `recheck` runs (it already does); the screen needs nothing but its flows ⚠ | the same | the same |
| **The graph is torn down** while the screen is open (sign-out, a reset) | the flows end | the waiter is told *cancelled*; D1 of the retired graph says nothing more | the retired graph neither writes nor serves ✓ | as S1 |
| | ⚠⚠ For every column: the view model holds a **retired** source. It must show *not connected* (a failure), never the last value and never *nothing installed*. The surface rebuilds it on the next install (`PhotosSurface.installRepository`'s pattern) ⚠ | | | |
| **Consent off** (this iPhone) | writes the device row | **the download goes on**: a model is not a consent (IN-D10). The feature row moves; the model row does not ⚠ | `Ready` stays `Ready`; search then answers `noConsent`, in Photos' words. A pass is 5e's | as S1 |
| **Consent on**, no model | the rows are written; the feature row says *"It works once the model is ready and your photos are indexed."* (5e-2 round 2; not over an unsuitable model) | the same | — | — |

#### 14.18.6 Invariants

- **I1. Nothing is shown as installed, preparing, ready or downloading that disk has not
  said.** *Installed* is I; *Ready* is R; *Downloading* is D1. No tap sets any of them: the
  row moves when its source does. (Today's `busyModelId` is the opposite, and is why it goes.)
- **I2. A read that failed is shown as that failure.** Never as an empty list, never as *not
  installed*, never as *no models published*. Rows 4, 5, 8d, 9c, 10e each have their sentence,
  and none of them offers an action that writes.
- **I3. Absent, unreadable and not-yet-read are three things** (rows 6, 4, 3). `null` is not
  *none* (`otherDeviceConsents`' rule).
- **I4. Every in-flight state has a way out the user can see**: Cancel for a download, Delete
  for an install, Try again for a refusal. ⚠ **One exception, stated:** an unreadable journal
  (row 8d) has only the retry, because cancel and delete read the same journal. Lifting it
  needs a reset, which nothing has (`tech-debt.md`, the read contract, item 1).
- **I5. A sentence promises only what the next press can do.** *Try again* is not offered for
  an integrity failure (7d); *Download* is not offered on an unsuitable phone (12) or over an
  unreadable index (4); no *check now* exists (10a).
- **I6. The model row and the feature row never write each other's state.** Delete touches no
  consent; a switch touches no model (IN-D10).
- **I7. Consent order is §38.2's**, unchanged: on writes realm then device; off writes device
  first; never under a placeholder id; every write ends in a re-read.
- **I8. No number is a literal.** The download size is the catalog's; the on-disk figure is
  `CoreMlDiskBudget`'s; *needed* and *free* come from the refusal.
- **I9. The view model takes no field below its `init`** (the K/N `Main.immediate` crash,
  §11), and nothing it launches can throw out of `viewModelScope`.
- **I10. No switch that changes nothing.** The offered capabilities are the platform's: on iOS
  *Search photos by description* alone.

#### 14.18.7 Test plan

**JVM (commonMain / desktopTest), where the decisions are**

- `modelSetupOf`: one case per row of §14.18.3, and the rule of §14.18.2 as cases of its own:
  for each in-memory source, a value that *would* establish a state does not when disk says
  otherwise (an `Installed` outcome with no record is not *Ready*; a `Refused` with no journal
  is row 7a, not 8b).
- The view model over a fake `OnDeviceModelSource` and a fake repository: every cell of
  §14.18.5 that is marked ⚠; the consent order (§38.4's cases run against the iOS capability
  list); a retired source reads *not connected*.
- The shared repository's read fix: a failed `InstalledModels` is a failure on **both**
  screens.
- The downloader's new flows against the existing fixture: `outcomeFor` and the flow agree at
  every step of §14.11's outside-view table.
- Mutations, one per guard, as every slice: at least I1 (a tap sets *downloading*), I2 (a
  failed read is empty), the join's rule, the retired source, the binding under the interface.

**What the fakes must be able to do.** ⚠ The fake is opt-in and never a default (AGENTS.md,
Control-on-iOS): the surface's default is *not connected*.

- Emit any `ModelSetup`, and fail any of its reads, **separately** from one another.
- Hold a download open across a view model's death and a new one's birth.
- Refuse a cancel and a delete, each with its typed failure.
- Retire under a collector.
- ⚠ It must not complete a download because `download` was called: the last slices' rule, *no
  test does what the product never does*. The fake moves only when the test moves it.

**Simulator (iosTest, the umbrella)**

- Photos' real graph resolves `OnDeviceModelSource` and builds the view model; both doors
  (the op's `DownloadModel` and the source's `download`) reach one journal.
- A teardown with a collector attached ends the flow and the next install's view model reads
  the new graph.

**Swift.** `scripts/ios-smoke.sh --build-only photos` for the compile. §14.1 promises *Swift
UI tests on a fixture backend*, and the target exists: `PhotosIosUITests` (XCUITest, eight
files, the `PhotosIos-UITests` scheme in `DebugHarness`, against `-photosFixture` and switches
such as `-photosSlowEdits`) [C]. So: a `-photosModels <state>` launch argument installs the
fake source in the state named, and `ModelsUITests` asserts, per state, the sentence and
**which buttons exist**; then the flows a tap starts (consent order as the fake recorded it,
delete asks first, cancel returns the row only when the fake moves). ⚠ Nothing runs that
scheme in a gate: it is run by hand on the simulator, as the other eight are (M10).

**Only on a phone** (added to §14.16's and §14.17.6's lists; the flag stays off, so a debug
build with `TENSHU_PHOTOS_ON_DEVICE_AI=YES`):

13. Download from the screen on Wi-Fi; leave Photos; return: the bar is where the system got
    to, then *Preparing*, *Checking*, *Ready*, with no tap.
14. Kill Photos mid-download; reopen the screen: row 8 without a tap.
15. Wi-Fi off during a Wi-Fi-only download: row 8a; Wi-Fi back: the bar moves again.
16. A phone with too little free space: row 7a's two figures against Settings → iPhone Storage.
17. Delete from *Ready*: the row goes, and the space returns (the towers at once).
18. The feature question after the first install; *Not now* writes nothing (read the rows).
19. VoiceOver reads each state's sentence and each button; Dynamic Type at the largest size.
20. Mia's three lines and the search bar's `noConsent` line name Photos' Settings.
21. (5d-3) The Settings button on the Library on iOS 26's bar, beside the density button, and
    with photographs under the bar; the path *Settings → Models* by hand.
22. (5d-3) `NWPathMonitor` on a phone: Wi-Fi off under a Wi-Fi-only download says *Waiting for
    Wi-Fi*, a hotspot and Low Data Mode do not; the sentence goes when Wi-Fi returns.
23. (5d-3) A sign-out or reset with the Models or the feature screen open: no screen stays up
    over the wall, and the next one is built over the new runtime.
24. (5d-3) Every sentence at the largest accessibility text size, and the device rows' switches
    under VoiceOver (name, status, value).

#### 14.18.8 The PR split

⚠ 5d-2 and 5d-3 below were written for a second view model (M3 as recommended). **§14.18.11
replaces them**: 5d-2 is two PRs, the first an Android and desktop change.

Three, each reviewable alone, each behind the flag. (5c was cut in two when built, and 5c-2
should have been.)

| PR | Builds | Tests | Touches Swift? |
|---|---|---|---|
| **5d-1** the sources | The observable seams of §14.18.2 in `:inference-service-proxy`; `ModelSetup`, `OnDeviceModelSource` and `modelSetupOf`; the iOS binding; the gate's reason kept. **No view model, no screen, no behaviour change** | the table; the flows against §14.11's fixture; the binding; mutations | no |
| **5d-2** the view model | The shared repository's read fix (both platforms; Control's Compose screen gets its sentence); the device-id and roster seam replacing `SyncRepository` in the repository; the platform's capability list; the iOS view model (M3) and its copy **as Kotlin values**; `:inference-service` exported; Photos' `noConsent` wording and Mia's three lines | §14.18.5; consent order; a retired source; `desktopTest` for Control's screen; mutations | no (the export changes headers; the smoke build runs) |
| **5d-3** the screen | The Settings button on Library, the Models screen and the feature screen in SwiftUI; the collector bridge; `NWPathMonitor` for row 8a; the `-photosModels` harness switch | the smoke build; `ModelsUITests` on the simulator; the phone list | yes |

5d-1 and 5d-2 are each the size of 5c-2's first half. 5d-3 is Swift and thin by construction.

#### 14.18.9 Decided by the maintainer (2026-10-13)

All twelve are decided. Eleven as recommended; **M3 was overruled**. The recommendation that
lost is kept in the row, because §14.18.0–8 above were written under it and say so where they
still do.

| # | Question | Decided |
|---|---|---|
| **M1** | 5d creates Photos-on-iOS's first Settings screen. Only the Models row, or PIO-5's rows too? | **Models only**, behind the toolbar button on Library; no button when the flag is off. PIO-5 adds its rows to the same list |
| **M2** | Any schema change in 5d? (§14.18.4) | **None.** Inference stays at `version = 2`. Nothing new on the wire: no cancel operation, no cellular parameter, no new reason string |
| **M3** | One view model for both platforms, or a second one for iOS? | ⚠⚠ **One. Overruled.** *Recommended and not taken:* a second `OnDeviceModelsViewModel`. *Decided:* the existing `ModelsViewModel` / `ModelsUiState` over the existing `ModelsRepository` interface are upgraded **for every platform** to carry the richer per-model state; iOS gets a second `ModelsRepository` beside `ClientModelsRepository`. An action a platform's repository cannot do is not offered. §14.18.11 is the plan |
| **M4** | The consent's shape on iOS | **§38's shape**: a feature row and a device list. *This iPhone* alone, with no *Turn off everywhere*, if Photos cannot read the roster. ⚠ What was verified is in §14.18.10: by the code it can; not run |
| **M5** | An indexing section in 5d, with an *Index now* that can only refuse? | **No.** It arrives whole in 5e. §14.1's 5d row is corrected |
| **M6** | *Use cellular data* after a Wi-Fi-only download has started | **No switch.** The row says to cancel and start again |
| **M7** | Low Data Mode | **As built** (allowed), and unsaid |
| **M8** | Typed reasons inside the process | **Typed.** Built in 5d-1 (§14.18.10) |
| **M9** | An update offered on an `Unsuitable` phone? | **Yes** |
| **M10** | Swift UI tests | **`ModelsUITests` in the existing `PhotosIosUITests` target**, run by hand; no gate is claimed |
| **M11** | The copy | The placeholders of §14.18.3 stand until the maintainer edits them; each sentence is one Kotlin value |
| **M12** | The way out of a failed install | **Delete**, worded *remove it and download it again* |

Open and **not** 5d's to decide: D14 (the archive after a compile); the memory floor (still a
placeholder); the flag.

#### 14.18.10 As built — 5d-1, the sources (2026-10-13)

Built on M2 and M8 as recommended, before the other ten decisions: no schema change, and the
two English sentences typed in-process. No view model, no Swift, no screen.

**What shipped**

| Source of §14.18.2 | Now |
|---|---|
| G, the gate's reason | `PhotosOnDeviceAi.decision(buildFlag)` returns `AiStaticGates.Decision`; `admitted` is that decision. An admitted graph's source carries `Admitted`; a graph that was not admitted has no source (`modelSource(koin)` is `null`) and the reason is the surface's to ask for |
| D1 and D4, *the journal names it* and its cellular choice | `BackgroundModelDownloader.journalView: Flow<ModelDownloadJournalView>` (and `journalNow()`): `NotLookedYet` · `Read(named)` · `Unreadable(namedBefore)` · `Retired`, each named download with its `ModelDownloadKey` and `allowsCellular` |
| D2, the last outcome | `lastOutcome: StateFlow<ModelDownloadOutcome?>`. `outcomeFor` reads the same value |
| P3, *preparing now* | `ModelPreparer.compiling`, around the compile only (first cut: `AiReadiness.cacheChanges`, which is true for every sweep; review finding 6). *A probe is running*: `Preflight.probing` |
| R, with the install it is about | `AiReadiness.standing: StateFlow<ReadinessStanding>`: `NotReadYet` · `Unreadable` · `For(key, state)`, the key being the whole `PreflightKey`; written in the same write as the state |
| K, *the first check has ended* | `ModelPreparer.checksEnded: StateFlow<Long>`, counted by `check` itself after it returned |
| a retired graph | `AiReadiness.retiredNow: StateFlow<Boolean>`: the value `isRetired` and `serving` read |
| N, the network path | `OnDeviceNetworkPath`, an interface and nothing behind it |
| M8 | `SpaceRefusal` and `CompiledSpaceInsufficientException` (the cache), `PrepareOutcome.Failed.space`; `PreflightOutcome.Waiting.cause`; `PreflightFinding.Transient.cause`. The sentences stay, for a log |

- `ModelsAvailability`, `ModelRow`, `ModelSetup`, `ModelActionResult` and
  `OnDeviceModelSource` (`:control-shared-logic`); `modelsOf(ModelOnDisk,
  ModelSaid)` and `JoinedModelSource` (`:control-shared-proxy` commonMain, so the JVM runs
  them); the binding in `photosOnDeviceAiModule`, **under the interface**.
- `InstallFailed`'s comment is corrected, and `cancel` in S4 is pinned
  (`cancellingAnUpdateEndsTheUpdateAndLeavesTheInstalledModelAsItWas`; §14.11's ⚠ᵃ on that
  cell is paid).

**How the rules are held**

- **Memory never chooses the case.** `modelSetupOf` takes two types. Every branch is taken on
  `ModelOnDisk`; `ModelSaid` is read only inside `absent`, `running` and `held`, whose return
  types are `Absent`, `Download.Running` and `Held`, and `held` switches the standing on
  readiness's state. `nothingAProcessRemembersMakesAModelInstalledReadyOrDownloading…` runs
  864 combinations of remembered values against a disk that says otherwise.
- **Identity.** A download's outcome is used only where its key is the journal's record's or
  the catalog entry's (`downloadKey()`, held equal to the downloader's by a case); a prepare
  report only for the installed record's `PrepareKey` (`ModelPreparer.keyFor`, now the one
  place it is built); a probe's only for its `PreflightKey`, read **with** the record in one
  read of the index. A bar counts only against its own record's total.
- **One source of truth each.** `retired`, the cache count, the outcome and the check count
  *are* the flows now; nothing mirrors them. The journal view is rebuilt inside `changing`,
  which is where `tracked`, `journalLookedAt`, `lastReadFailed` and `gone` are written.
  ⚠ That is **one function, not a type**: a later write to `tracked` under a bare
  `synchronized(ticks)` would compile. `an-ended-download-stays-named` and
  `a-torn-down-graph-keeps-its-view` are the mutations that catch two such writes; the others
  are caught by nothing but the comment.
- **The source keeps nothing about the model.** What is installed is asked of the store at
  every emission. The catalog's last answer is kept, as the answer to a fetch.

**Deviations from §14.18.2–3, and one change of behaviour**

1. ⚠⚠ **A behaviour change, in a slice that promised none:** `goesOn`. The last outcome was
   never withdrawn. A download refused for want of room went on once there was room and
   `outcomeFor` still said *refused*; nothing read it but the waiter it was made for. As a
   flow it would have shown *paused* over a moving bar. It is now withdrawn, for its own key
   only, at the two points a restart passes. No existing case asserted the stale value.
2. **K is not an input of the join, and that was half wrong** (review finding 3).
   True of P1, P2 and V: in a new process they are `null`. **Not true of R**: readiness's flow
   starts at `NoModel` before it has read anything and keeps it when a read fails, so a
   relaunch over a `Ready` model was shown as *Preparing*, for good when the read kept
   failing. Readiness now says whether its state is a reading (`stateRead`: not yet, read,
   unreadable), and the join takes that. `checksEnded` stays built and read by nothing.
3. **Rows 8d and 8e share `Download.Unreadable` / `DownloadSaid`**, and row 13 (a removal in
   flight) is the view model's own call, not a source.
4. `ModelSetup.Fetching` and `Held` are the names; `Update.Unknown` is new: with no catalog,
   or a journal not looked at, an update is neither claimed nor ruled out.
5. *Somebody is waiting on a download that is not journalled yet* (the catalog fetch, tens of
   seconds) is **not** in the journal view: it is not on disk. The caller of `download` knows
   it is waiting; the view model says *starting* from that, in 5d-2.

**⚠⚠ Found by the simulator, green on the JVM, and reproduced outside the downloader**
(Kotlin 2.4.10). The first cut marked a failed journal read with
`changing { lastReadFailed = journal is Journal.Unreadable }`, where `journal` is a `val`
assigned from a `when` **in the same function** and `changing` is an inline function whose
body is `synchronized(lock) { try { change() } finally { … } }`. On Kotlin/Native that test
answered **true for every value**, the plain class included: every read was said to have
failed (seven cases red on the simulator, green on the JVM).

- **Reproduced** in a case that knows nothing of the downloader
  (`IsOnADataObjectAfterAWhenTest`, `KotlinNativeTypeTestShapes.originalShape`): on the
  simulator it answers `[true, true, true, true, true]` for five values of which one is the
  object. On the JVM it is right.
- **Seven neighbouring shapes are right on both targets**, each a case there: the same test
  outside the lambda; with `==`; made before the lambda; under a bare `synchronized`; in an
  inline lambda with no `try`/`finally`; returned from the lambda; and with the value made by
  another function. So the fault needs **all three**: the `when` in the same function, `is`
  on the `data object`, and the assignment inside that inline `try`/`finally` lambda.
- ⚠ The cause in the compiler was **not** looked for. The rule that follows is narrow:
  *decide in the branch that makes the value, or test it before the lambda.* `==` is not the
  cure in general: `orElse` and the join's `is` checks on the same kinds of value are right.
- **Pinned per target:** `TheOriginalTypeTestShapeOnNativeTest` asserted the *wrong* answer on
  Native and failed the day the compiler was fixed; `…OnTheJvmTest` asserted the right one.
  ⭐ **Fixed in Kotlin 2.4.20** (2026-10-14): the canary went red, and the two became one
  commonTest, `TheOriginalTypeTestShapeTest`, asserting the right answer on both targets.
  Recorded in `tech-debt.md`. A grep of this track's main sources for a one-line
  `{ x = y is T }` lambda found no other.

- Also changed by typing the space refusal: `CoreMlModelCache.prepare` now throws
  `CompiledSpaceInsufficientException` for too little room, where it threw
  `CoreMlPrepareException` with a sentence (`tooLittleFreeSpaceIsRefusedBeforeAnythingIsUnpacked`
  asserts the two figures). ⚠ It has two callers: the preparer, and the latency harness
  (`inference-harness-ios/.../In3mHarness.kt:188`), which catches nothing by type and is
  unaffected. When the cleanup after such a refusal fails too, the cache rewraps it as *not
  cleared*; the figures are carried through that (`spaceShortOf`).

**The whole-diff review of 5d-1 (at `0e45cb8c0`): nine findings, all nine right**

| # | Found | Now |
|---|---|---|
| 1 | The journal view could name a download the journal no longer names, for the rest of the process: a read only ever **added** to what was tracked, and `untrack` sat behind a cleanup that can throw | `noteTheJournal` **replaces** what is tracked with what the read named, in one change, and ends the bar; `release` untracks first. ⚠ Found beside it: whoever waited on such a download was never told. They are now (`drop`, `noteTheJournal`) |
| 2 | Bytes that are no index were shown as *nothing installed*, with Download offered | `ModelStore.read()`: one sealed answer (`Records` · `NotAnIndex` · `Unreadable`). The source asks it. ⚠ The preparer and the removal still ask `installed()` and `holdsAnIndex()` in turn, correctly; they are not moved |
| 3 | Readiness had no *not read yet* and no *could not read* | `ReadinessStanding` (below, with Codex's finding): `Standing.NotReadYet` and `Standing.Unreadable`, each with *Try again* and Delete as its ways out |
| 4 | *The other cellular choice* was reported as an outcome keyed by the running record, and shown as why that download was not moving | Thrown to its caller and reported nowhere. `ModelDownloadRefusal.OtherChoiceRunning` is gone; the refusal is `ModelActionResult.Why.OtherCellularChoice`, the request's. ⚠ The other refusals made before the journal (`NoTransport`, `JournalUnreadable`, `StoreRefused`, no room) are keyed by the entry asked for; when that is the running record they are also true of it, and stay |
| 5 | A probe that is running was described by the report of the attempt before it; a report with no key was carried to the next install | `Preflight.probing` comes first (`Checking.How.Running`); a report with no key is about no install |
| 6 | *Preparing now* was claimed for every change of the cache: the sweep of every check, an eviction, a wait | `ModelPreparer.compiling`, set around the compile and nothing else |
| 7 | (a) The view was set inside the downloader's lock, so an immediate collector ran inside it and inside a teardown. (b) The store was read on the collector's thread at the rate of the bar | (a) Built under the lock, **published after it**, never backwards (a change number). (b) The join runs on `Dispatchers.Default` (`flowOn`); the bar is joined **after** the store's read |
| 8 | *Try again* waited for a catalog fetch, and a failed re-fetch erased a good catalog; two first collectors each fetched | The check runs beside the refresh; a failed refresh marks the catalog `STALE` and keeps it; *never read?* is asked under the lock |
| 9 | The binding hard-cast the downloader | `as?`; a graph with another downloader has no source (`modelSource` answers `null`) |
| Codex, early pass on the same commit | **A new install shown as `Ready` on the state of the one it replaced**: the store is read at the emission, readiness's state was captured before it, and the state did not say which install it was about. One emission, and one too many (I1) | **By identity, not by timing.** Readiness publishes `ReadinessStanding.For(key, state)`, the key set in the same write as the state; the join compares it **whole** with the `PreflightKey` read beside the record, and a state about another install is `Standing.NotReadYet` for this one. ⚠ The `PreflightKey` and not the record's identity: `Ready` is a statement about everything in that key (both digests, the vocabulary, the OS build, the app build, the revision). The same was then done for the two marks that had no identity: `Preflight.probing` is the key of the install being probed, `ModelPreparer.compiling` the `PrepareKey` being compiled |

**What an action's answer rests on** (review on `85bee103a`). The first cut of the
quick-returning `download` decided *accepted* by **racing two signals**: the journal view
changing, against the downloader's call ending. A teardown says `Retired` on the view
*before* it fails the call, so a download that died with its runtime, with no record, was
answered *accepted*; and a view that already named the model (another caller's download, or a
record of other bytes) was read as this call's.

**`Accepted` is now one fact, told by the owner:** `BackgroundModelDownloader.download` takes
`admitted`, which it calls when **this call** has a record in the journal (the one it wrote,
or the running one it joined), with that record's key and choice, after the record is on the
disk and tracked. The source waits for that or for the call's own end. **No journal view is
read to decide anything**, so there is no predicate over the view for a new case to fall
through.

| What happens | `download` answers | Where the rest goes |
|---|---|---|
| the downloader admits the call (it wrote the record) | `Accepted` | the row: `Fetching` |
| it admits the call by joining the running record of the same bytes and choice | `Accepted` | the row. ⚠ What the call says later (cannot go on now; failed; installed) is **dropped** by the source: it is `Download.held` and the store's to say, once |
| the call ends before it is admitted: no room · free space unknown · no transport · transfers unknown · journal unreadable · store refused · integrity · no catalog · unknown model | `Refused(Said(NeedsSpace · FreeSpaceUnknown · NoTransport · TransfersUnknown · JournalUnreadable · StoreRefused · Failed(integrity)))`, `Refused(NoCatalog)`, `Refused(UnknownModel)` | nowhere else. ⚠ The same refusal is also the downloader's `lastOutcome` for the entry asked for, which an `Absent` row shows as *last* |
| the other cellular choice | `Refused(OtherCellularChoice)` | nowhere: it is not an outcome (finding 4) |
| admitted and stopped in the same step (joined, then *not now*) | `Accepted` | the row's `held` |
| a record of **another** key is in the journal (a takeover is being decided), or another caller's | no answer yet: the view is not asked. Then `Accepted` if the downloader admits the newer entry, or the typed refusal if it does not, and the running download is untouched (§14.11 ✓⁹) | — |
| the journal view is `Unreadable` or `NotLookedYet` | not asked. The downloader reads the journal itself and admits or refuses | — |
| a teardown before the call is admitted | `Refused(NotConnected)`: the call is failed *torn down*, and the graph is retired | — |
| a teardown just after it was admitted, before the answer is given | `Refused(NotConnected)`: asked again at the end. The record is on the disk and is the next graph's | the next graph's row |
| installed already, nothing to fetch | `Accepted` (the call returns) | the row: `Held` |
| a failure nobody named | `Refused(Unnamed)` | — |

| What happens | `cancel` answers |
|---|---|
| a record is there | `Accepted`: the downloader read the journal and removed the record in one step |
| nothing is journalled; or the record went between the screen's look and the press | `Accepted`: none is there, which is what was asked for. ⚠ There is no look in the source first, so nothing can change between a look and the act |
| the journal cannot be read | `Refused(Said(JournalUnreadable))`, and nothing is cancelled (§14.11) |
| the cleanup throws after the record was removed | `Refused(Unnamed)`; the row is gone all the same (finding 1) |
| a teardown under it, or a graph that is gone | `Refused(NotConnected)` |

⚠ **Not pinned:** *admitted and the call ended, both ready, and the second clause chosen*.
`select` takes the first ready clause, so on one thread that branch is never reached; the
mutation that removes it survives.

⚠ Found by the case that pins what a call is told
(`aCallIsToldOnceItHasARecordInTheJournalAndNeverWhenItHasNone`): **a graph that was torn
down still joined a call to a running download of the same bytes.** That path writes nothing,
so nothing on it asked *torn down?*. Before this change the only effect was a waiter marked
*joined* on a graph that completes nothing; with `admitted` it would have told the call it was
accepted. `begin` asks now.

**Every joined value now says what it is about**, so no pair depends on which arrived first
(`aSourceThatIsStaleNeverSpeaksForAnInstallThatHasJustReplacedTheOneItWasAbout`, one assertion
per pair; `aRelaunchOverAReadyModelIsNeverShownAsPreparingAndAnUpdateIsNeverShownAsReady…` on
the source):

| Source | Carries | Compared with |
|---|---|---|
| the journal view | each record's `ModelDownloadKey` and cellular choice | — (it names) |
| the last outcome | `ModelDownloadKey`; a request's own refusal is never an outcome (finding 4) | the journal's record, or the catalog entry |
| the bar | model id and the record's total | the journal's record |
| readiness | `PreflightKey` | the key read beside the install record |
| a prepare report; *compiling* | `PrepareKey` | the install record's |
| a probe report; *probing* | `PreflightKey`; a report with none is about no install (finding 5) | the key read beside the install record |
| a call that read no install (`ModelPreparer.lastRefusal`) | ⚠ **Left out of this table when it was first written, and found by Codex**: it had no key at all, stood until the next keyed attempt ended, and described a model committed after it. It now carries the `PreflightKey` **readiness was about when the call was refused** (`PrepareRefusal.about`; `null` when readiness had read nothing) | the key read beside the install record; `null` matches nothing |
| the catalog | the entry itself | — |

**What is still unkeyed, and why each is about no install:**

| Value | Why it needs no key |
|---|---|
| a probe report with no key | It read no install. It is applied to **none** (finding 5): the join shows `Pending` |
| the catalog | It is what is *offered*, not what is installed; every use compares its entry (version, `downloadKey()`) with what it is set beside |
| the network path | A fact about the device now. It qualifies a download by nothing but *is there Wi-Fi* |
| the gate's decision | A fact about the build and the phone, fixed for the process. It is not joined: a refused gate has no source |
| a prepare refusal with `about == null` | Made before readiness had read anything: applied to none |

- **Why that stamp, and why it cannot go stale silently.** A refusal is about a moment, so it
  is stamped with what ends the moment *for an install*: the key readiness holds. Every commit
  and removal of the model goes through readiness's writer 3, which reconciles; a new install
  is a new key (other towers, another vocabulary), and the join already shows
  `Standing.NotReadYet` until readiness's key is the one read beside the record. So the
  refusal is consulted only when readiness is about this very install, and applied only if
  it was about the same one when the call was refused. A store change that keeps the key is
  the same install for everything a prepare reads. The store's write count was the
  alternative: it also moves for another model's commit, and needs a new member on the store.
- **The mirror case:** refused while no model was installed (readiness about *no install*, or
  having read nothing), then a first install commits: the key differs or is `null`, and the
  row says `Pending` (`aCallThatReadNoInstallSaysNothingOfAnInstallThatCameAfterIt`).
- ⚠ Stale in *time* it can still be (Photos is back in the foreground and the last refusal
  says it was not): every trigger makes a call, and the next call replaces it.

⚠ What I had written and was false, corrected in place: *"setting a `StateFlow`'s value runs
no collector"* (it does, for an immediate dispatcher); *"memory never ahead of disk, by
construction"* (the journal side **is** a memory, of the last read); *"the preparer is its
only caller"*.

- ⚠ Still as it was, and said: `lastOutcome` is set under the step lock, so an immediate
  collector **of that flow** runs under it. The join is its only collector and is on
  `Dispatchers.Default`. And between a probe letting go of its towers and its report being
  written, one emission can pair *not probing* with the report before.
- **Mutations after the review:** 20 in the inference module and 32 in the join and its
  source, the first round's 27 among them where the code they mutate still exists; then 16
  more for the identities. All red but one: *never read?* asked outside the lock **did not
  compile** as first written and was run again in a form that does.

**Found by the self-review, and not fixed**

- ~~**A moment of `Ready` for the wrong record.**~~ Found here, left as "readiness's shape,
  not this slice's", called sound by the review, and **then found again by Codex and fixed**:
  see the table above, last row. Leaving it was wrong by this track's own rule: a value that
  is joined with another carries what it is about.
- ⚠ `TenshuSyncRepository.devices` reads a failed roster read as an empty list
  (`?: emptyList()`): the shape this section forbids, in the file M4 would reuse.
- **Not pinned:** that a relaunch tracks what the journal names *before* it says *looked*
  (`noteTheJournal`'s order). A collector on the test dispatcher sees conflated values, so the
  intermediate value cannot be caught there.

**M4's open claim, answered: yes, by the code [C]; not run.** Control's `TenshuSyncRepository`
needs nothing but a `TenshuClient`: `devices` is `client.identityService.devices.flow()` and
`getDeviceId` is `client.syncService.getSyncStatus().deviceId`
(`control-shared-logic/.../data/TenshuSyncRepository.kt:73-76,121-122`). Photos' runtime has
that client (`PhotosLocalRuntime.kt:186`, `base.tenshuClient`) over the same
`buildProxyModule()`, which includes the sync service's modules (`ProxyModule.kt:172-173`),
and under route 1 it rides Control's one device identity (`FirstPartyLocalRuntime.kt:143-149`).
Nothing in a Photos graph has made either call in a test.

**Guards, each removed once and seen red** (fresh XML, files restored by hash)

| Removed | Red in |
|---|---|
| an ended download stays named (`untrack` outside `changing`) | `aDownloadIsNamedUntilItsRecordIsGoneAndNotAfter` and 1 more |
| a torn-down graph keeps its view | `aGraphThatIsTornDownSaysSoToWhoeverFollowsItAndNothingAfter` |
| a failed journal read is not said | `aJournalThatCannotBeReadIsSaidAndIsNeitherNoneNorNotLookedAt` |
| a refusal outlives what it described | `whatWasSaidOfADownloadIsWithdrawnOnceItGoesOn` |
| going on withdraws what was said of another | `whatWasSaidOfAnotherDownloadIsNotWithdrawnByThisOne` |
| the cellular choice is not the record's | `theCellularChoiceOfARunningDownloadIsTheOneItWasJournalledWith` |
| a cancel drops no staging | `cancellingAnUpdateEndsTheUpdateAndLeavesTheInstalledModelAsItWas` and 2 more |
| a check is counted before it ended | `aCheckIsCountedWhenItHasEndedAndNotBefore` |
| the space figures are dropped | `noRoomIsSaidForThisInstallAndStaysSaidWhilePhotosIsAway` |
| what was said of other bytes is this entry's | `whatWasSaidOfOtherBytesSaysNothingOfThisEntry` |
| what holds another record holds this one; a bar of another total; a cancel the journal contradicts | `aJournalledDownloadIsRunningWithItsChoiceItsBarAndWhatHoldsIt` (each) |
| a prepare report of other towers is this install's | `aPrepareIsDescribedByWhatWasSaidOfThisInstall` |
| a probe of another install is this install's | `aCheckIsDescribedByWhatWasSaidOfThisInstall` |
| an unreadable index is an empty one | `anIndexThatCouldNotBeReadIsThatAndNothingElseIsConcluded` and 2 more |
| an unreadable journal names nothing | `aJournalThatCouldNotBeReadIsNeitherADownloadNorNone` |
| a journal not looked at names nothing | `whatHasNotBeenReadYetIsLoadingAndNotNone` and 1 more |
| a remembered pass makes a model ready; a remembered install makes a model held | `nothingAProcessRemembersMakesAModelInstalledReadyOrDownloadingWhenTheDiskSaysItIsNot` (each) |
| no catalog means no update | `anUpdateIsOfferedOnlyAgainstACatalogThatWasReadAndFetchedOnlyWhenTheJournalSays` and 1 more |
| a retired graph's flow goes on; a retired graph's store is asked | `aRetiredGraphSaysNotConnectedAndItsFlowEnds` (each) |
| a store that throws is empty | `aStoreThatThrowsIsAnIndexThatCouldNotBeReadAndNotAnEmptyOne` |
| checking again does not ask the store again | `checkingAgainReadsTheCatalogAndTheStoreAgainAndRunsTheCheck`. ⚠ **Survived the first run**: the case changed the catalog in the same step, which caused the emission by itself |
| a fetch nobody waited for is recorded | `aCatalogFetchWhoseCollectorLeftRecordsNothingAndTheNextCollectorAsksAgain` |

⚠ Not mutated: the binding under the interface and the gate's decision (simulator cases only:
`theModelSourceIsBoundUnderItsInterfaceOnAnAdmittedPhoneAndNowhereElse`,
`theSourceShowsARelaunchedDownloadEndsItThroughTheSameJournalAndStopsWhenTheGraphIsRetired`);
the typed causes of `Waiting` and `Transient` at the four sites that set them.

⚠ Found by the self-review and fixed here: a catalog fetch whose collector had left was
recorded. The shared catalog source answers a cancelled fetch with *no catalog*
(`tech-debt.md`, the download entry, item 8), and that answer then stood for every later
collector, with nothing asking again.

**Still owed for 5d-1:** nothing on a phone; `OnDeviceNetworkPath` has no implementation;
no screen reads any of it yet.

#### 14.18.11 The shared view model, as decided (M3): the plan for 5d-2

⚠ 5d-2a is built (§14.18.13) and so is 5d-2b (§14.18.14); 5d-3, the SwiftUI screen, is not. It supersedes §14.18.2's *Where each piece lives* where that names a second
view model, §14.18.8's split, and the M3 row as first written.

**What 5d-1 already left open for it.** `ModelSetup` is in `:control-shared-logic` and names
nothing of iOS: no Core ML, no session, no scene. `modelSetupOf` is common code in
`:control-shared-proxy`, where a join over the proxy's types has to live;
`:control-shared-logic` needs only its result.

**The shapes, split in 5d-1 after the review** (they were one type; a Compose `when` was not
to be written against that):

- `ModelsAvailability`, the list's: `NotOffered(why)` · `NotConnected` · `Loading` ·
  `InstalledUnreadable` · `Listed(catalog, downloads, models)`.
  - `catalog`: `NOT_READ_YET` · `READ` · `STALE` (a refresh failed; the last good one is
    shown) · `UNAVAILABLE`. *Nothing is published* is `READ` with no row.
  - `downloads`: `KNOWN` · `UNREADABLE`. ⚠ A journal that could not be read is a fact about
    the list: with it, no model is shown as fetching **or** as absent, and no update is offered.
- `ModelRow(modelId, name, setup)` and `ModelSetup`, a model's: `Absent` · `Fetching` · `Held`.
- **Where a name and a size come from with no catalog.** `HeldModel.name` is the catalog's
  while the catalog lists the model, and otherwise the model's **id**: the install record
  carries no name, adding one is a reshape, and the id is what Control's screen has always
  shown there. `HeldModel.onDeviceBytes` comes from the record's own sizes (on iOS, the
  archives and §3.4's prepare figure scaled from them), so *frees about N GB* needs no
  catalog. A download with no catalog is a row too: its id, and the journalled total.
- **`NotOffered` has one producer: the repository**, from `PhotosOnDeviceAi.decision`. A
  graph that was not admitted has no source, so a source never says it.
- **Actions answer at once** (`ModelActionResult`: `Accepted` · `Refused(why)`, in types
  `:control-shared-logic` can see). `download` returns when the download is **journalled**
  or refused, not when it is installed (which can be days); its call goes on by itself.
  A download that was accepted and then cannot go on says so in its row
  (`Download.held`), **once**: it is not also a failed call.
- **How the last download's result is keyed off iOS.** `ClientModelsRepository` has no
  `ModelDownloadKey`. It keeps the typed result of its last `download` call with the
  **model id and the catalog version it asked for**, and shows it under an `Absent` row only
  while the catalog still lists that version. A `Failed(integrity)` of version 1 is not
  shown under version 2.

**The repository** (`ModelsRepository`, the one interface):

| Member | Today | Becomes |
|---|---|---|
| `models(): ModelsSnapshot`, a one-shot | re-fetched by the view model after its own calls | `models(): Flow<ModelsState>`: the availability and each `ManagedModel` with its `ModelSetup`. ⚠ *Downloading* is in what the repository emits, never in the view model |
| `ManagedModel.installed`, `.updateAvailable` | two booleans | `setup: ModelSetup` (`Held.update` is the second boolean, with *unknown* added) |
| `download(modelId)` | suspends until installed; its suspension **is** the busy state | `download(modelId, allowsCellular)`; the row moves when the flow does. The call still returns the typed outcome, for the message |
| — | — | `cancel(modelId)`, `checkAgain()` |
| — | — | `capabilities: ModelsCapabilities`, below |
| `OfferedConsents`, a global | faces included everywhere | `capabilities.features` |
| takes `SyncRepository` | Control's whole repository | a two-member seam: this device's id, the roster |

**`ModelsCapabilities`**, declared by each repository; **what is false is not drawn**:

| Flag | Android, desktop | iOS |
|---|---|---|
| `cancel` | false (the in-process downloader has none) | true |
| `cellularChoice` | false | true |
| `spaceReturns` | `AT_NEXT_START` (today's *RemovedSpacePending*) | `TOWERS_AT_ONCE` |
| `features` | search, faces | search |
| `indexing` (the section, *Index now*, pause, coverage) | true | **false until 5e** (M5) |
| `otherDevices` | true | true if the roster reads (M4) |

**What `ClientModelsRepository` (Android, desktop) reports for each field**

| `ModelSetup` | From |
|---|---|
| `InstalledUnreadable` | `installedModels()` failed. ⚠ Today `emptyList()` |
| `Listed(Unavailable)` | `availableModels()` failed (`NO_CATALOG` and the rest) |
| `Absent(model, last)` | in the catalog, not installed, not downloading. `last` from the typed result of **this process's** last `download` call (`Failed(integrity)`; the store refusals stay `storeReadOnly`); `onDeviceBytes` = the catalog's size |
| `Fetching(Running)` | a `download` call of this repository is in flight, **or** `downloadProgress(modelId)` answered with a tick when the screen opened (the proxy's stream ends at once when nothing is downloading). `allowsCellular = true` (nothing restricts it there), `held = null` always |
| `Download.Unreadable`, every `held`, `NotOffered`, `NotConnected` | never |
| `Held(Standing.Ready, update)` | installed. There is no prepare step and no check, so no other standing. `update` from the version comparison; `Unknown` with no catalog |

**The seven misfits of §14.18.0, on Android and desktop**

| Misfit | Android and desktop after 5d-2a |
|---|---|
| *Downloading* is the view model's memory | **Fixed.** A screen reopened over a running download shows it. ⚠ Within the process: an in-process download does not survive it |
| `installed: Boolean` | Same meaning, richer type: always `Ready` |
| A failed `InstalledModels` is *nothing installed* | **Fixed** (`tech-debt.md`, the store's read contract, item 2: the screen's half) |
| No cellular choice | Not offered (`cellularChoice = false`) |
| No cancel | Not offered (`cancel = false`); `tech-debt.md` item 7 stays, as an Android and desktop item |
| `RemovedSpacePending` | The same sentence, chosen by `spaceReturns` |
| `OfferedConsents` is global | From the repository; unchanged there |
| takes `SyncRepository` | Narrowed; Control passes its own |
| Sentences in `:control-shared` name Control | Unchanged there; Photos' are its own |

**What changes in the Compose `ModelsScreen`**

- The row is drawn from `ModelSetup` and not from two booleans and `busyModelId`:
  `modelStatusLine`, the button `when` (`ModelsScreen.kt:594`, `:683`) and the bar.
- A new full-width state for `InstalledUnreadable`, with *Try again* and no row buttons.
- Buttons and the indexing section behind `capabilities`.
- `ModelsUiState` loses `busyModelId` / `busyIsDownload` / `downloadedBytes` for downloads
  (a removal in flight stays the view model's own: it is one call, and its row says
  *Removing…*), and gains the availability and `capabilities`.
- ⚠ Every consumer of `ModelsUiState`, checked: `ModelsScreen.kt`, `ModelFeatureScreen.kt`,
  `App.kt` and the tests in `:control-shared` and `:control-shared-logic`. **No Swift reads it
  today** (no iOS graph builds the view model, §11), so the *UiState change owes Swift* trap
  has nothing to break until 5d-3 writes the first reader.

**What stays iOS-only, behind the repository:** readiness, the prepare step, the preflight,
the journal, the gate, the cellular choice, cancel, the network path; that is,
`OnDeviceModelSource` and everything under it. The iOS repository is a thin adapter: its
`models()` is the source's `setup` put into a one-row list; its consents are
`ClientModelsRepository`'s code over Photos' client, extracted so both use it.

**Cost**

- **Tests moved or rewritten:** the download half of `ModelsViewModelTest` (the busy slot is
  gone); `ModelsSnapshotTest` (the join returns the new type); `ModelsScreenTextTest`,
  `ModelsScreenLayoutTest`, `IndexNowButtonTest`; `ModelsRepositoryWiringTest`. The consent
  half, `ModelsLiveStreamsTest` and `ModelFeatureScreenTest` change only where the
  capability list is read.
- **House Rule 8:** it changes `:control`'s screen, so `scripts/android-smoke.sh`, and a look
  at the desktop app. ⚠ The smoke launches; it does not open Settings → Models, so the screen
  itself is checked by its Compose tests and by hand.
- **The risk the second view model avoided, now carried:** a regression on a shipped Android
  screen from an iOS feature behind a flag that is off. 5d-2a is therefore its own PR, with no
  iOS in it, so that it can be reviewed and smoked as an Android and desktop change.

**Do the review's problem classes exist in the shipped screen on Android and desktop?**
Asked by the maintainer; answered by reading master, not run. [C] throughout.

| Class | On Android and desktop today | 5d-2a |
|---|---|---|
| 1. A state shown that its source no longer says | **Yes.** *Downloading* is `busyModelId`, set while the view model's own `download` call is suspended (`ModelsViewModel.kt:448-482`). Leave the screen and come back while the download runs: the row offers **Download** again (the proxy joins, so nothing is fetched twice; the list is wrong). The bar is the view model's too | **Fixed**: read from the repository |
| 2a. A failed `InstalledModels` shown as empty | **Yes.** `.getOrNull()…orEmpty()` (`ModelsRepository.kt:383-384`): every installed model disappears and is offered for download | **Fixed** |
| 2b. Bytes that are no index, through the operation | **Yes.** `InstalledModelsImplementation` asks `store.installed()` (`ModelOperations.kt`), which answers them as no records: the op returns an empty list, **successfully**. Download is offered; the commit is then refused | **Fixed in the op** in 5d-2a: it asks `ModelStore.read()` and fails typed. No new reason string (M2): it is `TRANSPORT_FAILED`'s sentence, and the screen shows the failure |
| 2c. A catalog failure shown as empty | No: `catalogUnavailable` is kept apart (`ModelsRepository.kt:385-389`) | — |
| 2d. A failed roster read shown as *no other device* | **Yes.** `?: emptyList()` (`TenshuSyncRepository.kt:73-76`). *Turn off everywhere* then clears no peer and writes the realm no: §38.2's own hazard, behind `otherDevicesKnown`, which this defeats | **Fixed** in the two-member seam: a failed read is `null` (*not known*) |
| 3. *Installed* shown as usable with nothing confirming it | **Yes, by design of the platform.** There is no readiness there. `OnnxInferenceBackend.supports` is *located and not rejected* (`OnnxInferenceBackend.kt:131-132`); a session that will not open is found at the first image and reported per image. The row says installed throughout [I: the symptom] | **Left**, recorded in `tech-debt.md`: it needs a load check, which is a feature |
| 4. An old result under a new entry | **Partly.** A failure is a transient message, not a row state, so it is not re-attributed. But `busyModelId` is one slot for the whole list: a second model's row is inert while the first downloads | **Fixed** by per-row state; the keyed last result above |
| 5. A refresh that erases good data | **Yes.** `refresh()` replaces `models` with the snapshot (`ModelsViewModel.kt:198-211`): a catalog that fails on a later refresh turns every catalogued row into *installed only*, and drops what is not installed | **Fixed**: `STALE` |
| 6. IO on the main thread, or under a lock | Not found: the repository calls operations, which run in the proxy. Not measured | — |
| 7. An action that cannot do what its label says | **Yes.** ① A model with an update available shows only the download button: **there is no way to remove it** (`ModelsScreen.kt:594-611`, the `else` branch). ② Delete during a download is not offered (one busy slot). The removal's wording (*space at the next start*) is right there | ① **Fixed**: update and remove are two buttons. ② stays not offered (`cancel = false`) |
| 8. A switch shown before it was read, or a failed read shown as off | **Yes.** `readOnce` answers `pref.default` for a failed read (`PreferenceServiceExtensions.kt:143-148`: `as? TResult.Success`), and the switches are seeded *off* (`ModelsViewModel.kt:112`). A consent that is **on** and cannot be read shows as off; pressing it writes | **Left**, recorded: the cure is in `:preference-service-client` (a read that can say it failed), which is every screen's, not this slice's |

So 5d-2a owes cases, on the JVM, for: a download surviving the view model (1); a failed and a
not-an-index `InstalledModels` (2a, 2b); a failed roster (2d); two models with one downloading
(4); a refresh that fails after a good one (5); an updatable model that can be removed (7).

**The split, revised** (replaces §14.18.8's 5d-2 and 5d-3 rows; 5d-1 is as built)

| PR | Builds | Checks |
|---|---|---|
| **5d-2a** shared | (`ModelsAvailability` is already split, in 5d-1.) `ModelsRepository` as above; `InstalledModels` asking `ModelStore.read()`; `ClientModelsRepository` reporting it; `ModelsViewModel` / `ModelsUiState` upgraded; the Compose screen; the read fix | the JVM suites of `:control-shared-logic` and `:control-shared`; DEX; **Android smoke**; the desktop app by hand. No iOS code, but the simulator suites run: the shared module is exported |
| **5d-2b** iOS repository | the iOS `ModelsRepository` over `OnDeviceModelSource` and the three operations; the consent code shared; its binding in Photos' surface; `:inference-service` exported; the copy as Kotlin values; Photos' `noConsent` wording and Mia's three lines | JVM for the adapter over a fake source; the simulator suites; `ios-smoke.sh --build-only` (the export changes headers) |
| **5d-3** SwiftUI | as §14.18.8 | as §14.18.8 |


#### 14.18.12 Slice 5d-2a, designed (2026-10-13): the shared view model. Built: §14.18.13

The design the build followed, written first as for 5c-2. ⚠ Where §14.18.13 differs, it is right. §14.18.11 says *what*; this says how
the one view model behaves in every state the Android and desktop repository can be in, what
must not change on a shipped screen, and what each existing test becomes.

**Read for this, on master** [C]: the only caller of the `InstalledModels` operation in the
product is `ClientModelsRepository` (`ModelsRepository.kt:383`); Mia, Photos-Android and the
indexer do not call it. `ModelsSnapshotTest` is a unit test of the pure join: there are **no
image or text goldens** anywhere in the Models tests. `ModelsUiState` and `ManagedModel` are
read by `ModelsScreen.kt`, `ModelFeatureScreen.kt` and the six test files; by no Swift, and
by nothing on the web.

**The repository's states on Android and desktop** (what `ClientModelsRepository` can emit):

- **L** `Loading` · **U** `InstalledUnreadable` · **N** `NotConnected` (the proxy does not
  answer) · **C?** `Listed` with the catalog `READ` / `STALE` / `UNAVAILABLE`;
- a row: **A** `Absent` · **F** `Fetching` (this repository's call is in flight, or the
  progress stream answered at open) · **H** `Held(Ready)` · **H+** `Held(Ready,
  Update.Available)` · **H?** `Held(Ready, Update.Unknown)` (no catalog).
- ⚠ Never: `NotOffered`, `Downloads.UNREADABLE`, any `Download.held`, any standing but
  `Ready`. The repository builds rows through one function that takes no argument for them.

**Operations × states**

| Operation | L | U | N | A | F | H / H+ / H? |
|---|---|---|---|---|---|---|
| **open** | subscribes once; the row state comes from the repository | *Try again*; no row | *not connected*; *Try again* | row, Download | ⚠ **row shows the bar at once** (today: Download) | row, Remove; H+ also Update |
| **refresh** (`checkAgain`) | — | re-reads; leaves U only when the read works | re-reads | ⚠ a failed catalog keeps the rows: `STALE` (today: rows dropped) | bar untouched | as A |
| **download** | not offered | not offered | not offered | `Accepted`: the row moves to F **when the repository says so**. `Refused`: the typed message of today, the row stays A with `last` | not offered; a press that raced is the proxy's join | H+ only (Update): as A; H / H? not offered |
| **cancel** | — | — | — | — | not offered (`cancel = false`) | — |
| **delete** | — | not offered | not offered | — | not offered while fetching **that row**; ⚠ another row's is offered (today: one slot blocks all) | asks first, then *Removing…* on that row. ⚠ **H+ can be removed** (today: no button) |
| **leave, reopen** | — | — | — | — | ⚠ F again, from the repository (today: Download) | — |
| **the result of a download arrives after the user moved on** | — | — | — | the message is shown once; `last` is the row's, by model id and catalog version | — | a first install asks the feature question once (§38.3), as today |
| **the proxy restarts** | ⚠ nothing re-subscribes by itself: the list is read again at the next open or *Try again* (§14.18.13) | as L | N until the next read answers | an in-flight download died with the process: the row is A, and nothing says *failed* (it was not told) | → A, as left | H |
| **store read-only** (another Control) | — | — | — | notice up front; no buttons (as today) | — | notice; no buttons |

**Invariants** (the screen's I1–I10 of §14.18.6, restated for the shared class)

- **V1.** The view model holds no *downloading*. `busyModelId`, `busyIsDownload` and
  `downloadedBytes` go. A removal in flight is the one thing it holds, **per row** (a set of
  model ids), because a removal is one call and nothing publishes it.
- **V2.** A failed read is U or N, never an empty list; a failed **catalog** refresh never
  removes a row. ⚠ A failed re-read of *what is installed* does replace the list with U or N
  (§14.18.13): nothing is shown as installed on the strength of a read that just failed.
- **V3.** Every result carries what it is about: a download's result is `(modelId, catalog
  version asked for)`; a removal's is its model id; a result for a row that is gone is a
  message at most, never another row's state.
- **V4.** Nothing is offered that the repository's `capabilities` do not have, and nothing a
  press could not do (no Download over U; no Remove while that row is fetching).
- **V5.** Collectors on `viewModelScope` do no IO: the repository's flow is built off the
  main thread (`flowOn`), as `JoinedModelSource`'s is.
- **V6.** The consent half is **not touched** by this slice, except that its capability list
  and its roster come from the repository. Its 27 cases stay green unchanged.
- **V7.** No field below `init`.

**What must not change, and what does: every existing case**

*Keeps* = green and unedited. *Rewritten* = same assertion against the new shape, same name.
*Changes* = one of the audit's fixes (§14.18.11's table), named. Nothing is deleted without a
named successor.

| File | Cases | Becomes |
|---|---|---|
| `ModelsViewModelTest`, consents and other devices (`turnOffEverywhere…` ×2, `aDeviceThatCannotBeCleared…`, `anotherDeviceIsSwitchedOff…`, the 14 from `theSearchSwitchIsOfferedOff…` to `aRealmWriteThatFailsPutsBothBack`) | 18 | **Keeps** |
| …, the feature offer (`aFirstInstallOffers…`, `decliningTheOffer…`, `noOfferWhenThereIsNothingToAsk`) | 3 | **Rewritten**: the install is seen on the flow, not on the call's return |
| …, indexing and pause (`indexProgress…` to `anAlreadyPausedDevice…`, `indexNow…` ×4, `aThrowingStart…`, `consumingAMessage…`) | 12 | **Keeps** |
| …, `theListLoadsOnInit`, `anUnavailableCatalogReachesTheUiState` | 2 | **Rewritten** |
| …, `aLandedDownloadRefreshesSoTheRowBecomesInstalled` | 1 | **Rewritten**: no refresh is needed; the row follows the repository |
| …, `aSecondDownloadIsRefusedWhileOneIsInFlight` | 1 | **Changes** (class 4): a second **model** is *not* refused; a second press on the same row is. Successors: `twoModelsDownloadSideBySide`, `aSecondPressOnAFetchingRowDoesNothing` |
| …, `pushedProgressReachesTheUiState`, `deletingCarriesNoDownloadedBytes` | 2 | **Rewritten**: the bar is the row's `Download.writtenBytes` |
| …, `eachFailureOutcomeKeepsItsOwnMessage`, `aThrowingDownloadStillReleasesTheRow` | 2 | **Rewritten**: each refusal keeps its message; a throw is `Unnamed` and the row is A |
| …, removal (`deletingAsks…`, `dismissing…`, `aFailedRemoval…`, `aThrowingRemoval…`, `aRemovalSaysItsSpace…`, `anUpdateSaysThePreviousVersions…`) | 6 | **Keeps**, except that *busy* is asserted per row |
| …, the store's refusals (`aStoreOwnedByAnotherControl…`, `aDownloadRefusedByAnotherControl…`, `aStoreNotOwnedReason…`, `aLockThatCouldNotBeTaken…`, `aLockUnavailableRefusal…`, `theProxysRefusalKeepsItsKind…`) | 6 | **Keeps** (two rewritten where they call `download` and read its return) |
| `ModelsSnapshotTest` (the pure join) | 9 | **Rewritten** to return rows; `withNoCatalogNoUpdateIsClaimed` asserts `Update.Unknown` where it asserted `false` |
| `ModelsLiveStreamsTest` | 7 | **Keeps**; `aRealmOfOneDeviceListsNoOthers` gains a twin, `aRosterThatCouldNotBeReadIsNotKnownNotEmpty` (class 2d) |
| `IndexNowButtonTest` | 4 | **Keeps** |
| `ModelFeatureScreenTest` | 9 | **Keeps** |
| `ModelsScreenLayoutTest` | 9 | **Keeps**; `noOtherStateOffersTryAgain` **changes**: U and N offer it too |
| `ModelsScreenTextTest` | 16 | **Keeps**, except `theSupportingLineAlwaysCarriesTheSizeExceptWhileRemoving` and `theButtonsGoWhileARowIsBusy…`, **rewritten** over `ModelSetup` |
| `ModelsRepositoryWiringTest` | 1 | **Rewritten**: the provider names the narrowed seam too |

**New cases, one per audit row that is fixed** (each fails on master's behaviour): a download
survives the view model (1); a failed `InstalledModels` is U (2a); bytes that are no index are
U through the operation (2b); a roster that could not be read is not *no other device* (2d);
two models, one downloading (4); a refresh that fails after a good one keeps the rows (5); an
updatable model can be removed (7). And Compose cases that **open the screen** in L, U, N,
A, F, H, H+, H?, read-only: the smoke gate launches the apps and never opens this screen.

**The one behaviour change in the proxy.** `InstalledModels` asks `ModelStore.read()`.

- *Before:* with bytes that are no index, the operation succeeds with an empty list; Control
  shows every model as not installed, offers Download, and the download's commit is refused.
- *After:* it fails typed (`TRANSPORT_FAILED`, with its sentence; no new reason, M2); Control
  shows *Couldn't read what's installed* with *Try again*, and offers nothing that writes.
- No other caller exists (above).

**Left, recorded** (`tech-debt.md`, *Control's Models screen on Android and desktop*): a model
shown installed with nothing confirming it loads; a consent that cannot be read shown as off.
Neither is trivial or separable: the first needs a load check, the second a preference read
that can say it failed, in a module every screen uses.

#### 14.18.13 As built — 5d-2a, the shared view model (2026-10-13)

Android and desktop only: no iOS repository, no Swift, no schema. Inference stays at
`version = 2`. §14.18.12 is the design; this is what the code is, and where it differs.

**Where each piece is**

| Piece | Where |
|---|---|
| The operation's read | `InstalledModelsImplementation` asks `ModelStore.read()`. Bytes that are no index and an index that could not be read both fail `TRANSPORT_FAILED`, with their sentence. ⚠ A typed exception out of the store's read is no longer carried by its type from this operation: `read()` answers every failed read as *unreadable*. `DeleteModel` and `DownloadModel` are as they were |
| The repository's state | `ModelsState(availability, serves, storeReadOnly, removing)`. ⚠ **`ManagedModel` and `ModelsSnapshot` are gone**: the rows are `ModelsAvailability.Listed.models` and nothing copies them. `serves` is the catalog's capability ids by model id, which a row does not carry and the feature question needs |
| What a platform can do | `ModelsCapabilities`; `ControlModelsCapabilities` is Android's and desktop's |
| The devices seam | `ModelsDevices` (`deviceId()`, `roster`), `ClientModelsDevices` over a `TenshuClient`. ⚠ Control passes this and **not** its `SyncRepository`, whose `devices` still reads a failed read as empty for its other callers |
| What Android and desktop know | `ModelsLedger`, over five calls as plain data (`ModelCalls`), so it runs on the JVM with no client. `ClientModelsRepository` holds one for the process |
| The one function that makes a row | `modelsSnapshot(installed, available, stale, fetching, last)`. It has no parameter for a standing, for what holds a download, or for an unreadable journal, so it cannot make a row that has one (`noRowCarriesWhatThisPlatformCannotKnow`, 64 combinations) |
| What is said once | `newsBetween(before, now)`: a pure function of two emissions |

**What `download` answers on Android and desktop.** `DownloadModel` returns when the model is
installed, and nothing journals a download there. So *accepted* needs a definition, and it is
this: the ledger makes the call in its own scope, and answers the press when **the proxy's
progress stream has ticked for that model** (the download is under way) or when the call has
ended before that. `CompletableDeferred.complete` decides which of the two it was, so there is
no look followed by an act.

| The call | The press is answered | Recorded |
|---|---|---|
| ends before a tick: no catalog · unknown model · a store that refuses · a failure before a byte | `Refused`, typed | nowhere: its caller was told. ⚠ Except a store's refusal, which is `storeReadOnly` and stays |
| a tick is seen | `Accepted` | — |
| ends after it was answered, badly | (already answered) | the row's `last`, with the model id and **the catalog version at the press**; shown only under a catalog entry of that version, and withdrawn by the next press |
| ends after it was answered, installed | (already answered) | nothing: the store is read again |
| is running, and the row is pressed again | the first call's answer | one call per model |

- **The row is *fetching* from the press**, because the ledger's call is under way, and stays so
  until the call ends. The bar is the stream's. ⚠⚠ That is a value in memory establishing a
  state. It is allowed for one reason: nothing else starts a download on these platforms
  (`DownloadModel` has this one caller, and on Android and on desktop the proxy is in Control's
  process), so the call **is** the download; and it is removed in a `finally`, so it cannot
  outlive the call.
- **No stream is asked for a model nobody pressed.** The first cut probed every listed model at
  every read; with no other source of a download the probe could only find the ledger's own
  calls, and a probe whose stream never ended was then reused by a press. Each call has a
  watcher of its own, started with it, cancelled with it and never joined.
- **The press is answered when the download is under way, which is not at once**: the proxy
  reads the catalog and replays what is staged before the first tick (seconds to tens of
  seconds). `starting` is held that long; on these platforms the row is drawn fetching for all
  of it, so *Starting…* is seen for a frame at most.
- ⚠ **`ModelSetup.Update.Available` gained `last`** (defaulted, so 5d-1's join is unchanged): an
  update that failed leaves the model installed, and `Held` had nowhere to say so.
- ⚠ **An update being fetched is `Held(Ready, Update.Fetching)`**, not `Fetching`: the model is
  still installed. §14.18.12's row list did not name it.

**The view model**

- It holds, of its own: `starting` (a press not answered yet), its own removal press for the
  moment before the repository reports it, and the confirmation. Nothing else about a model:
  *removing* is the repository's (`ModelsState.removing`).
- `storeReadOnly` is the repository's. ⚠ The view model no longer latches it: a refusal by the
  store, of a download or of a removal, is latched in the ledger, and the screen is told once by
  the change in the state. So a screen reopened in the same process still has the notice.
- A message comes from two places and never both (true by construction since the third
  review: see *Told or recorded*): a **refusal** is the press's answer; what **ends afterwards**,
  or with nobody waiting, is read off two emissions (`newsBetween`). Nothing is said of the first
  emission, so a screen that opens on a failed row is not told again.
- The feature question (§38.3) is asked when a row that was not installed is seen installed,
  whoever started the download.
- A flow that ends leaves `NotConnected`; *Try again* subscribes again.
- `delete` asks the row again before the call: a confirmation answered after a download of that
  model has started removes nothing.

**The classification of §14.18.12, confirmed by reading every body.** The design's table was by
name. Where the two differ:

| Design said | Is |
|---|---|
| 104 cases | **106** (`ModelsViewModelTest` has 51, not 53: the groups *consents and other devices* hold 16, not 18) |
| The three feature-offer cases: rewritten | Two are unedited (the fake lands the install on the flow). `noOfferWhenThereIsNothingToAsk` has one fixture changed: its *an update* case was an installed model with no update, on which a press is now not sent at all |
| `theListLoadsOnInit`, `anUnavailableCatalogReachesTheUiState`, `aLandedDownloadRefreshes…`: rewritten | Unedited but for `busyModelId`: `loading`, `models` and `catalogUnavailable` are read off the availability |
| The removal and store cases: keep, busy per row | As said: every `assertNull(busyModelId)` is `assertEquals(emptySet(), busy)`, nothing else |
| `ModelsScreenLayoutTest`, `IndexNowButtonTest`, `ModelFeatureScreenTest`: keep | **Not one line changed**, and 14 of `ModelsScreenTextTest`'s 16: a test-only `ModelsUiState(loading = …)` and `ManagedModel` spell the old state |
| `noOtherStateOffersTryAgain` changes | Unedited: its four states still offer none. The states that now do have their own cases |
| `ModelsLiveStreamsTest`: 7 keep | 7 unedited, and the twin |

**Not as designed, and why**

1. *The proxy restarts: nothing says failed.* A call of the ledger's that was in flight **is**
   told (the call fails), so its row says *didn't finish*. A download the ledger did not start
   ends silently, as designed.
2. *Store read-only: no buttons.* They are drawn disabled, as before this slice.
3. `ModelsUiState.capabilities` defaults to Control's. A view model always replaces it; the
   default is what makes a state built by hand the shipped screen.
4. *Not connected* on Android and desktop is: `InstalledModels` gave no answer of the
   operation's own (it threw, or failed with nothing typed). A typed failure is *unreadable*.
5. An installed model's size is the **record's**, not the catalog's. They differ only when the
   versions do, and the record's is what is on the disk.

**After the whole-diff review of 5d-2a (at `3c59fcbff`)**

| # | Found | Now |
|---|---|---|
| 1 | The list was read once per process: reopening the screen read nothing, and a first read that hung held every later one | Every open of the Models screen asks for a read (`init`), the rows stay while it runs, requests made during a read share the next one, and each read is bounded (45 s) |
| 2 | A removal ran in the screen's scope: leaving mid-removal left a list that still showed the model | The removal and the read after it run in the ledger's scope; so does every read. `ModelsRepository.delete`'s contract says who owns the call |
| 3 | Cleanup was not on every ending: a call cancelled by somebody else left its row fetching for the process | `finally`. A cancellation under a live ledger is a download that did not finish |
| 4 | A press reused a watcher that might be dead, and a call's end joined one | Above: one watcher per call, never joined; a tick from a watcher that is no longer the call's writes nothing |
| 5 | The probe had no source | Dropped (above) |
| 6 | A read that threw escaped into a bare scope; a removal that happened was reported failed when the read after it threw | A read never throws (`answering`), the row mapping is inside the catch, the scope has a handler, and a removal answers what the removal answered |
| 7 | The feature screen's view model took model news nothing there shows or consumes | It is built with `forTheModelsScreen = false`: no news, no read. As on master, where it never produced any |
| 8 | A model that landed was shown absent until the catalog fetch returned | What is installed is read and published first, at every read, and before a landed call's row stops fetching. With no catalog yet the list is `NOT_READ_YET` with what is installed |

Also changed by it: the feature question and the indexing line ask for a model that is
**usable** (`Held` and `Ready`), not merely held; a store's refusal has a sentence where no
state says which it was; *Starting…* is said for any row a download was pressed on; the other
devices are neither collected nor counted where `capabilities.otherDevices` is false; a roster
that stops reading **keeps the devices it listed** and is *not known*; *Try again* subscribes
before it reads.

**After the second round on 5d-2a (at `0948fdc74`): a P1, and a second review**

| Found | Now |
|---|---|
| **Codex, P1.** The removal had moved to the ledger's scope and the knowledge that it runs had not: a screen reopened mid-removal had an empty `removing`, could start the update of that model, and the update could install again what was just removed | **A removal is the ledger's state**, as a download is (`ModelsState.removing`), shown as *Removing…* by every screen. One operation per model: a download of a model being removed is refused (`Why.Busy`), a removal under a download of it is refused (`ModelDeleteOutcome.Busy`), a second removal joins the first. Nothing is queued. ⚠ It qualifies the row and is beside the list, not in `ModelSetup`: whether the model is installed is still the store's |
| **Blocking.** Two downloads at once shared **one** progress stream in the real client: it keys a stream by the call site, before the model id is on the chain (`DownloadProgressRo`), and hands a second subscriber the first one's stream. A second row showed the first model's bytes and its press was answered on the first model's tick | ⚠⚠ **One download at a time on Android and desktop**, as on master: `ModelsCapabilities.oneDownloadAtATime`, a second model's press is refused in the ledger and its button is disabled. And a tick is used **only for the model it is about** (`ModelBytes.modelId`, the proxy's stamp). ⚠ The stream id was **not** changed: the id is a parameter of the operation it is derived from, so putting the model id into it changes the chain; that is a change to `:core-common`'s scheme and not this slice's |
| A landing's and a removal's re-read queued behind a catalog fetch | What is installed has its own lock |
| An ending before the first tick, with no screen waiting, was recorded nowhere | The ledger counts who is waiting for an answer; an answer nobody waits for told nobody, and is the row's `last` |
| The press's answer could reach the screen before the store's refusal was published | Published first; and the snackbar's text follows the state |
| The first open ran two reads | The ledger reads nothing by itself: a screen that opens asks |
| An installed row whose update was pressed said *Starting…* until the first tick | The repository's *downloading* is said first |
| A roster that stopped reading left its rows with live off-switches | Every write is gated on the roster being known, in the screen and in the view model |

**What bounds each call.** ⚠ Every bound is on the ledger's **wait**: an in-process operation
is not stopped by its caller giving up (it runs as a job of the session), so it goes on in the
proxy whatever the ledger does.

- The two reads: 45 s each. What a timed-out read answers later is discarded with its cancelled
  wait and has no way into the state.
- A removal: **the caller's wait** is 60 s, after which it is told *still removing*
  (`ModelDeleteOutcome.StillRemoving`), never *failed*. The claim is not bounded: the row says
  *Removing…* and refuses a download until the call has really ended. (The first cut bounded
  the call and released the row, which let a download start beside a removal still running and
  called a removal failed that then happened: third review, finding 1.)
- A download: nothing, by design.

**Told or recorded, never both** (third review, finding 2). Whoever presses is counted as
waiting in the same step that claims or joins the call, and the step that ends the call decides:
an answer somebody is counted for is theirs and recorded nowhere, one nobody waits for is the
row's. Nobody can join after that step. ⚠ One gap is left and is not pinned: a caller cancelled
in the instant between that step and its own resumption was counted as told.

**Recorded, not fixed** (`tech-debt.md`, the Models entry)

- **A ledger call can end while the proxy's download goes on.** A call cancelled by somebody
  else is a `TransportFailed` ending here and frees the slot, while `DefaultModelDownloader`'s
  job runs on its own scope: the row says *didn't finish* and the model may still land, and
  meanwhile a second download or a removal is accepted. *A call under way is a download under
  way* holds; the converse does not. Later: ask `isDownloading` before releasing the claim.
- **A download that never ends holds every Download button for the process.** Bounded in
  practice by the HTTP client's connect and socket timeouts; what is left is a server that
  trickles, or a blocked store thread. Later: a stall bound, or the cancel operation.

**Decided here, and the maintainer's to overrule**

- **A failed re-read of what is installed blanks the list** (U or N), where a failed catalog
  keeps it as `STALE`. Keeping the rows needs a value `ModelsAvailability` does not have
  (*installed, as last read*) and every writing action disabled under it. Not built.
- A download that is *held* says so in its row and raises no message.
- A failure sentence stays under its row for the process, whether or not a screen was open
  when it happened.

**Owed by 5d-2b** (what it paid is in §14.18.14; the rest of this list is still owed)

- ~~`ModelsUiState.capabilities` still defaults to Control's.~~ Paid: it has no default.
- `ClientModelCalls` and `ClientModelsDevices` run in no test. That a typed failure of
  `InstalledModels` arrives at a client as `ModelUnavailable` is shown only by
  `ModelOperationsTest` over the test proxy; if it did not in the app, an unreadable index would
  read *Can't reach…* and not *Couldn't read…*. `ModelsRepositoryWiringTest` matches source
  text; it is not a wiring test.
- Not pinned by a case: *Try again* subscribing before it reads; the collection of the other
  devices being skipped; a tick that arrives after its call's cleanup; a timed-out read of what
  is installed replacing a good list; that the store's refusal is published before the answer.
- ⚠ No case runs the **real** client: `ClientModelCalls` is private to `:control-shared-logic`
  and the client-through-proxy fixture (`ModelOperationsTest`'s) is in
  `:inference-service-proxy`'s tests, which this module cannot see. The shared stream is
  modelled by the double (`everySubscriptionGets`), from reading `DownloadProgressRo` and
  `DefaultInteractorProvider`, not from running them.
- A feature question for a download whose screen has gone is asked of whichever Models screen
  is open when it lands, and of none that opens afterwards.

**Unexercised:** nothing was looked at on a device or in the desktop app, and
`ClientModelCalls` / `ClientModelsDevices` have run nowhere. Hence, by hand, before or right
after the merge:

**The desktop script** (`./gradlew :control-desktop:run`, signed in, Settings → Models)

| # | Do | Expect |
|---|---|---|
| 1 | Open Models with nothing installed | The catalog's rows with their size and a Download button each; no *Couldn't read…* and no *Can't reach…* (either would mean the typed-failure mapping is wrong) |
| 2 | Press Download on one model | *Downloading… · N* with a spinner at once; within tens of seconds *X of Y* with a bar that moves; no Download or Remove on that row |
| 3 | While it runs, look at another model's row | Its Download is disabled and the row ends *Available when the other download has finished.* Its bytes do **not** move with the first model's |
| 4 | Still while it runs: Back, then open Models again | The same row, with the bar where it had got to. Never a Download button on it |
| 5 | Let it land | *Installed · N*, a Remove button, and once the question *Turn on "Search photos by description"…?*; the other row's Download is enabled again |
| 6 | Press Remove, confirm, and at once Back, then open Models again | Either *Removing…* with no buttons on that row, or the row already not installed. Never *Installed* with a live button |
| 7 | When it has gone | A snackbar *Removed. Its space is released the next time Control starts.* if the screen was open; the row offers Download |
| 8 | Network off, Back, open Models, *Try again* | The rows stay, with *Couldn't refresh the model list. Showing it as it was last read.* |

On Android the same steps, in Control → Settings → Models.

#### 14.18.14 Slice 5d-2b (2026-10-13): the iOS repository. Designed, and built as designed but where *As built* says

Photos-on-iOS's `ModelsRepository`: a thin adapter over `OnDeviceModelSource`, the
`DeleteModel` operation and the consent code Control uses. No Swift screen (5d-3), no schema,
the flag stays off.

**Where each piece is**

| Piece | Where |
|---|---|
| The repository | `OnDeviceModelsRepository`, `:control-shared-logic` commonMain, so the JVM runs it. It sees the source's interface, never the proxy |
| The consent half | `ModelsConsents` (the five members, moved up from `ModelsRepository`, which extends it) and `ClientModelsConsents` over a `TenshuClient` and `ModelsDevices`. Both repositories delegate to one. Not forked |
| What the catalog says a model serves | `OnDeviceModelSource.serves`, from the same catalog read the rows come from |
| The binding | `photosOnDeviceAiModule`, `single<ModelsRepository>` (one per graph, so two screens share one *removing*); `PhotosOnDeviceAi.modelsRepository(koin, decision)` answers it, or the `NotOffered` / `NotConnected` one for a graph that has none |
| The surface | `PhotosSurface.models()`; `PhotosLocalRuntime` installs a **provider**, resolved when the screen first opens and never on the launch path |
| The sentences | `PhotosModelsCopy` and `photosModelsScreen(state, device, onWifi)`, `:control-shared-logic` commonMain: the table of §14.18.3 as one function. Swift renders its result |

**Operations × the source's states.** *Passed on* = the source's answer, unchanged.

| Call | `NotOffered` (no source) | `NotConnected` (retired, or no source) | `Loading` | `InstalledUnreadable` | `Listed` |
|---|---|---|---|---|---|
| `models()` | `NotOffered(why)`, once, and **does not complete** (a view model reads a completed flow as *not connected*) | `NotConnected`, then completes | passed on | passed on | passed on, with `serves` and `removing` |
| `download` | `Refused(NotConnected)` | passed on (`Refused(NotConnected)`) | passed on: the downloader reads its own journal and catalog | passed on (the commit would be refused: `StoreRefused`) | passed on. `Absent` → `Accepted` when journalled; `Fetching`, same choice → joins; other choice → `Refused(OtherCellularChoice)`; `Held` + update → as `Absent`; `Held(Preparing(Incomplete))` → fetches what is missing; `Downloads.UNREADABLE` → `Refused(Said(JournalUnreadable))` |
| `cancel` | `Refused(NotConnected)` | passed on | passed on | passed on | passed on: `Accepted` also when nothing is journalled |
| `checkAgain` | returns | returns (the source's check finds a retired graph) | the source's `checkAgain`: the coalesced check beside a catalog refresh | the same | the same |
| `delete` | `NotConnected` (nothing was asked of anything; 5d-3) | `NotConnected` with no source; the operation's failure where there is one | the operation: `NOT_INSTALLED` is `Removed` | the operation fails typed: `Failed` | `Held`: the operation, which ends a download of the model first (the downloader's step). `Absent` / `Fetching`: the view model never sends it; the operation answers `NOT_INSTALLED` |

Rows of `Listed` need no column of their own: the repository does not look at a row before it
acts. Every action goes to the layer that owns the state, which reads it under its own lock.

**Settled here**

- **(a) *Removing* is the repository's own call in flight.** Nothing below publishes a removal:
  `withoutDownloadOf` runs it inside the downloader's step and says nothing until it has ended.
  So the repository holds `modelId → the call`, claimed in one `update {}` that tests and sets,
  released in a `finally`. A second press joins the first call. ⚠ It **qualifies** a row and
  establishes nothing: whether the model is installed is the source's.
  - ⚠ The mark is let go **after the source has said the model is gone**, when the removal
    answered *removed*: one shared collection of the source feeds every screen, and the call's
    coroutine waits on it (10 s at most) before it lets go. Otherwise a screen shows the model
    installed, with its buttons, between the removal's end and the store's next read.
  - It does not outlive the graph: `PhotosOnDeviceAi.retire` retires the repository, which
    cancels its scope, lets go of a removal in flight (its caller is answered *not connected*,
    never *removed* and, since 5d-3, never *failed*: the operation had not answered, and no
    ending is recorded) and ends every `models()` flow *not connected*
    whatever the source gets to say. The next graph has a repository of its own.
- **(b) One operation per model is the downloader's, and only its.** It orders endings and
  downloads by when they were asked (§14.11). The repository refuses nothing on the strength
  of its own mark: a download pressed during a removal is passed on and runs after it. The
  view model does not offer one on a row that is being removed.
- **(c) A teardown under an open screen:** the source says `NotConnected` and completes; so
  does `models()`; the view model shows *not connected*. *Try again* subscribes to the same,
  retired, repository and is told the same. ⚠ The view model **dies with its screen**
  (`PhotosSurface` builds one per call, as it does every other; SwiftUI owns it), and the next
  screen is built over the next install's repository. A deprovision also takes Photos to its
  not-connected wall, so no Models screen stays up over it.
- **(d) `checkAgain` is the source's, which is `PhotosOnDeviceAi.recheck`:** the one coalesced
  sequence. It runs in the repository's scope; the caller's wait is bounded (45 s), the check
  is not.
- **(e) `capabilities` are fixed for the process:** `otherDevices` is true wherever a client
  exists, because the roster is read through it. A roster read that fails is *not known* at
  that moment (`null`), which already takes *Turn off everywhere* away. A phone that is not
  offered a model has **no** feature and no other devices: a switch there changes nothing.
  `oneDownloadAtATime` is false: the journal keys a download by its entry and the bar by its
  model id, so two are told apart. (This graph lists one model, so it is not reached.)
- **(f) `download` answers what the source answered**, each `Why` with its sentence. Nothing
  stays suspended after it: what a download says later is its row's (`Download.held`,
  `Absent.last`).
- **Held downloads raise no message** (as 5d-2a decided): the row says why, with *Try again*.
- **The feature question** is asked when a model is seen to become usable **from a standing
  that was read**. `NotReadYet` and `Unreadable` are not *not usable*: a relaunch over a ready
  model passes through `NotReadYet`, and asked the question at every launch.

**Invariants**

- **R1.** Every row, and whether there is a list, is the source's. The repository adds
  `removing` (its own call) and `serves` (the catalog's) and changes nothing else.
- **R2.** `NotOffered` has one producer, this repository, from the gate's decision.
- **R3.** No lock is held across a call, and nothing is published under one: the state is two
  `MutableStateFlow`s, each changed by `update {}` with no side effect inside.
- **R4.** A removal is recorded as ended when it has ended; the bound is on its caller's wait.
- **R5.** No field below an `init` that starts work, in the repository or the view model.

**As built**

Everything in the table and under *Settled here* is what the code is, with these additions.

- **(a), as it turned out.** The removal's mark is in `OnDeviceModelsRepository.removals`; the
  one collection every screen reads is a `shareIn(WhileSubscribed(replayExpirationMillis = 0))`
  over the source, so nothing is collected with no screen open and a screen that opens later is
  never handed what an earlier one last saw. A removal that answered *removed* waits on that
  collection for a list that no longer holds the model. ⚠ If the source does not move within
  10 s it is asked to read again and that read is waited for (bounded, 45 s) before the mark
  goes. ⚠ **Not pinned** that a real removal moves the source inside the 10 s: the simulator
  case waits with `eventually`, which the cap and the re-read satisfy too.
- **A removal's answer travels with its ending** (`ModelsState.removalEndings`, set in the step
  that takes the model out of `removing`, in this repository and in `ModelsLedger`). A screen
  that watched a removal end says what the call answered. The first cut inferred it from
  *still held*, and on the cap's path called a removal that happened a failed one (review of
  5d-2b, finding 3). The ledger publishes the mark and the ending under an unreadable list too.
- **`OnDeviceModelSource.serves`** is new on 5d-1's interface: the catalog's capability ids,
  from `JoinedModelSource`'s one catalog read. ⚠ With no catalog it is empty, so a model that
  becomes usable while the catalog cannot be read raises no feature question.
- **`NotOffered` and no runtime.** `PhotosOnDeviceAi.modelsRepository(koin, …)` answers the
  graph's repository on an admitted phone, `OnDeviceModelsRepository.notOffered(why)` where the
  gates refused, and `notConnected()` with no graph. `PhotosLocalRuntime.modelsScreenInThisBuild()`
  is the build flag alone, for the Library button (M1).
- **The copy** is `PhotosModelsCopy` (each sentence a value) and `photosModelsScreen(state,
  device, onWifi)`, which returns `PhotosModelsScreen`: the body (hidden, a notice, or rows),
  each row's facts, status, detail, bar and **actions in order**, the feature row, the message,
  and the two questions. An action is a kind (what to call on the view model) and a label.
  ⚠ Row 8e's *Try again* is `retry()` (the check's resume), not a `download` that joins: the
  view model sends no download for a row that is fetching.
  ⚠ *Waiting for Wi-Fi* is said only when `onWifi == false`; nothing implements the monitor yet.
- **Photos' `noConsent` line** names Photos' Settings → Models where a backend is bound
  (`ModelsSwitchHome.THIS_APP`, passed by `PhotosSurface.search`). **Mia's three lines are not
  changed**: they are in `:mia-service-proxy`, which has no platform seam, and are wrong on iOS
  today whatever this slice does. Recorded in `tech-debt.md`.
- **`:inference-service` is exported** from the umbrella (`InferenceCapability`). No Obj-C name
  clashes (`scripts/check-objc-clashes.py --only-involving :inference-service`).

**⚠⚠ Found by the simulator, green on the JVM: the shared view model crashed on Kotlin/Native.**
`ModelsViewModel.download` and `.cancel` tested their answer in place: `answer is Refused`
(cancel), `(answer as? Refused)?.why` inside the `update {}` lambda (download), on the value
`runCatchingCancellable { … }.getOrDefault(…)` had just returned in the same coroutine. With an
answer of `Accepted` the process died with a **segmentation fault**, in both. 5d-2a's code, on
its first run on this target (no iOS graph had built the view model before).

- **Reproduced with no graph** (`ModelsViewModelOnNativeTest`: the view model over the iOS
  repository and a source the case moves). In the test's own function the same value tests
  right: `is Refused` false, `== Accepted` true, `as?` null.
- **The cure is the track's rule:** the answer is told apart in a function of its own
  (`whyRefused`, called by `refusalOf`), and nowhere in the coroutine that made the call.
- ⚠ The cause in the compiler was not looked for. It is the family of §14.18.10's finding (a
  type test on a `data object` of a sealed type, on a value made through inline `try` code in
  the same function), and it is in `tech-debt.md` beside it. `ModelsViewModel.delete` has the
  same shape over an **enum** and runs.

**What changed on Android and desktop** (shipped code; each has a case)

| Before | After |
|---|---|
| A removal that outlasted its caller's 60 s wait ended with nothing said | Its ending is said off the list: *Removed…* when the model has gone, *Couldn't remove…* when it is still installed. To every screen open then, once (`newsBetween`'s `removalsEnded`; a screen whose own call answers says it from the answer and not again) |
| *Available when the other download has finished.* was also said over a store that cannot be changed | Not said there (`waitsForADownload`) |
| The feature question was asked whenever a row went from not usable to usable | Asked when it was **known** not usable before, and not for an **install** this screen has already seen usable: an update is not asked about; a model removed and downloaded again is (the mark goes when the list shows the model not held). On Android and desktop the one observable change is the update, which was not asked about before either (both rows usable) |
| `ModelsUiState.capabilities` defaulted to Control's | No default; `anyModelInstalled` is `anyModelUsable` |
| `ModelsRepository` declared the five consent members | It inherits them from `ModelsConsents`; `ClientModelsRepository` delegates to `ClientModelsConsents`, the same code as before, moved |
| `ModelsViewModel.download` / `.cancel` told the answer apart in place | Through `refusalOf`. Same behaviour on the JVM |

**Guards, each removed once and seen red** (fresh XML; restored and `git diff --quiet`)

| Removed | Red in |
|---|---|
| the mark waits for the source | `aRemovalIsMarkedFromItsClaimUntilTheSourceHasSaidTheModelIsGone` and 2 more |
| `NotOffered`'s flow stays | `aPhoneThatIsOfferedNoModelIsToldSoByTheRepositoryAndItsFlowStays` |
| a source that ends says *not connected* | `aSourceThatIsRetiredOrEndsOrThrowsLeavesNotConnectedAndTheFlowEnds` |
| the question only from a known standing; only for a model not seen usable | `theFeatureQuestionIsAskedWhenAModelIsSeenToBecomeUsableAndNotWhenItsStandingWasOnlyJustRead` (each) |
| a removal's end is not said by the list as well. ⚠ **Survived the first run**: both sayings landed before the message was read and were folded. A case that reads it in between was added | `theEndOfARemovalIsSaidOnceWhicheverOfTheAnswerAndTheListComesFirst` |
| an ending the list showed first is remembered as seen | the same |
| the list says a removal's end at all; *still removing* is not an answer | `aRemovalThatOutlastsItsCallersWaitIsStillGivenItsClosingMessage` and 1 more (each) |
| the check's wait is bounded; the removal's wait is bounded | `checkingAgainIsTheSourcesCheck…`; `theBoundIsOnTheCallersWait…` |
| a second press joins the call | `aRemovalIsMarkedFromItsClaimUntilTheSourceHasSaidTheModelIsGone` |
| `removing` is in what is emitted | `theBoundIsOnTheCallersWait…` and 2 more |
| nothing is replayed to a screen that opens later | `everyScreenReadsOneCollectionOfTheSourceAndAScreenThatOpensLaterReadsItAfresh` |
| an input of the shared list that throws is said as *not connected* | `anyInputThatThrowsIsNotConnectedSaidAndTheFlowEnds` |
| an answered ending is remembered only while the list still shows the removal | `aRemovalTheListNeverShowedLeavesNothingThatSwallowsTheNextOnesEnding` |
| a throwing source is a refusal, not an escape | `aDownloadAndACancelAnswerWhatTheSourceAnswered…` |
| a failed install offers no download; *waiting for Wi-Fi* only for a Wi-Fi-only download | `PhotosModelsCopyTest` (each) |
| the waiting line is not said over a read-only store | `aRunningDownloadIsShownOnOpenAndHoldsOnlyItsOwnRow` |
| the repository is bound under its interface | three simulator cases of `PhotosModelDownloadTest` |
| the search bar's line is given its home | `theSearchBarsConsentLineNamesPhotosOwnSettingsWhereABackendIsBound` |
| the answer is told apart in a function of its own | a segmentation fault in `ModelsViewModelOnNativeTest` and `theViewModelFromTheSurface…` (seen before the cure, twice, in two shapes) |

**M4's claim, run on the simulator** (`photosOwnClientReadsTheDeviceIdAndTheRosterAndItsConsentIsTheOneTheProxyReads`):
Photos' client answers this device's id, and it is the id the proxy reads consent under: what
the repository writes is what `hasInferenceConsent` then reads, in both directions. The roster
read answers too. ⚠ In a graph that has never synced it answers an **empty list**, this device
not in it: a read that worked. What a paired phone's roster holds is a phone's to show.

⚠ **As built, 2026-11-05: its ~3-in-20 red was a product defect, now fixed.** The post-write read
joined a `realmSettings` stream another collector in the graph held open, and `TList.flow` replayed
that stream's log one emission per element, so the first was the pre-write snapshot: `realm=false`
over a stored yes. A joiner's catch-up is now one emission. The Models screen re-subscribes `consents()` after every press and could show the
same flick. No consent *decision* read it — the proxy's gate is `hasInferenceConsent`, a direct store
read. Fixed in `:core` (`tech-debt.md`, the `PhotosModelDownloadTest` entry; `core-client-resubscription.md` §4.2).

**After the whole-diff review of 5d-2b (at `e8e502c42`): seven findings, none blocking**

| # | Found | Now |
|---|---|---|
| 1 | `seenUsable` was by model id and never cleared: remove, download again, and the question was not asked | It is an install's: dropped when the list shows the model not held |
| 2 | The copy ignored `checkingAgain`: every *Try again* stayed offered for the 45 s a check can take | Withheld everywhere while checking; `PhotosModelsScreen.checkingAgain` says why. And the other transients, each where the copy honours it: `removing` and `starting` (the row: its sentence, no action), `storeReadOnly` and *one download at a time* (`takeable`: no download, no removal), a busy row (no action) |
| 3 | On the cap's path a removal that happened was announced as *couldn't remove* | The answer travels with the ending (above), and the re-read is awaited |
| 4 | *Download again* beside *Update* (one call twice); a held integrity failure worded as a retry | One download per row; `HELD_INTEGRITY_FAILED` with Cancel alone. And `everyRowThereIsSaysSomething…` walks every row there is (over 5,000 combinations of setup, mark, network and capabilities) |
| 5 | No view model of `:control-shared-logic` runs on Kotlin/Native but in `ModelsViewModelOnNativeTest` | **Recorded** (`tech-debt.md`), with the crash characterised and its nearest relatives listed |
| 6 | The weakened real-threads case could pass having run nothing | Every press must be answered with an answer that order can give, something must have run, nothing is left claimed. ⚠ The flake predates this slice: the ledger is unchanged |
| 7 | The repository's scope was never cancelled | Retired with the graph (above) |

⚠ For 5d-3: `ModelsSwitchHome.THIS_APP` says *in Photos, under Settings → Models*, a screen that does not exist until 5d-3 builds it. Reachable only with the flag on.
⚠ Not pinned: that `PhotosOnDeviceAi.retire` calls the repository's `retire` (the simulator case passes without it, because the source says *not connected* itself). Since 5d-3 the hold is `RetiredWithTheGraph`, latched and pinned by itself (§14.18.15); that `retire` reaches it is still not.

**Not pinned, or not done**

- Mia's three lines (above).
- The feature screen's device list has no values of its own yet: `PhotosModelsScreen.Feature`
  is the row on the Models screen. 5d-3 needs *This iPhone*, the other devices and *Turn off
  everywhere* as values, and a factory for the view model with `forTheModelsScreen = false`.
- That the device id is not known cannot be said by the feature row: `ModelsUiState` does not
  carry it. A write then fails and *Couldn't save that* is said.
- Two downloads at once on iOS (`oneDownloadAtATime = false`): this graph lists one model.
- `ClientModelCalls` still runs in no test. `ClientModelsDevices` and `ClientModelsConsents`
  now run on the simulator, over Photos' graph.

**Only on a phone:** everything of §14.18.7's list 13 to 20; and that `PhotosSurface.models()`
resolved on the main thread at the first open costs nothing a user sees (it constructs nothing
the launch's check has not already built, by reading; not measured).

#### 14.18.15 Slice 5d-3 (2026-10-13): the SwiftUI screens. Designed, and built as designed

Photos' first Settings entry: Library's toolbar button → **Settings** → **Models** → one
feature. Six follow-ups from the review of 5d-2b are folded in. No schema; the flag stays NO,
and with it off there is no button and no screen.

**The rule the design serves: Swift decides nothing and writes no sentence.** Every string,
whether a control is there, and their order are Kotlin values; a press is carried out by a
Kotlin function. Swift maps a value to a control.

| Kotlin value | SwiftUI control | A press calls |
|---|---|---|
| `PhotosModelsCopy.SETTINGS` / `MODELS` / `MODELS_ROW_DETAIL` | the gear's label, the two titles, Settings' one row | navigation only |
| `Body.Hidden` | nothing; the screen leaves | — |
| `Body.Notice(text, detail, tryAgain)` | a list section; a button where `tryAgain` is a label | `retry()` |
| `Body.Rows(rows, notice, tryAgain)` | the notice section, then a section per row | `retry()` |
| `Row.title / facts / status / detail` | one combined row (`models-row-<id>`) | — |
| `Row.progress` `Bar(fraction)` / `Busy` | `ProgressView(value:)` / an indeterminate one | — |
| `Row.actions`, in order | a button row each (`models-action-<KIND>-<id>`); `REMOVE` is drawn destructive (the one exhaustive `switch` over `Action.Kind`) | `photosModelsPress(viewModel, modelId, action)` |
| `checking` (new: the sentence for `checkingAgain`) | a spinner and the sentence | — |
| `message`, `messageShown` | a section at the top with `OK`; VoiceOver is told when it appears **and when another replaces it** | `photosModelsMessageSeen(viewModel, messageShown)`: clears that message and no other (`consumeMessage(expected)`) |
| `question` (`Asked(kind, question)`: Kotlin chose it, the removal before the offer) | **one** alert; *yes* drawn destructive for `REMOVAL` (the one exhaustive `switch` over `Asked.Kind`) | `photosModelsAnswer(viewModel, kind, yes)` |
| `feature` | a row that pushes the feature screen | navigation only |
| `PhotosFeatureScreen.thisDevice` / `others` (`Device(name, status, on, press)`) | a `Toggle` each, disabled where `press` is `null` | `photosFeaturePress(viewModel, capability, press)` |
| `othersNotice` | a line under the devices | — |
| `everywhere(label, press, question)` | a destructive button, then an alert | the same, after *Turn off* |

- **What Swift holds.** The collected `ModelsUiState` (replaced by every emission, and read
  again synchronously after each press: the view model's own change is synchronous and the
  stream delivers it a turn later); the monitor's `onWifi`; two navigation flags; and one
  presentation flag, *Turn off everywhere was pressed* (the view model has no field for that
  question; the question's text and whether there is one are the value's, and the flag falls
  when the button goes). The two questions of the Models screen are presented **by the state
  alone**: the alert's binding has an empty setter, because a dismissal written there as well
  would withdraw the question before *Remove* read it.
- **Messages are in the list, not an alert.** A second alert beside a question is dropped by
  SwiftUI silently. A message stays until `OK`, which consumes it in the view model; it is not
  said again on a return, a rotation or a new collection. Each screen has its own view model
  (the feature screen's is `forTheModelsScreen = false`), so neither swallows the other's.
- **Lifetime.** `ModelsModel` (a `@StateObject`) owns a `PhotosModelsBinding` (new: the view
  model, the install it was built over, `close()`). ⚠⚠ Nothing on iOS clears a Kotlin view
  model, so `deinit` closes it: its collectors would otherwise keep the repository's one
  collection of the source alive. Leaving the screen stops nothing the repository does.
- **Re-provision.** `PhotosSurface.modelsInstalls` counts every install and teardown; a model
  whose binding is of an earlier install closes it and builds another. So an open screen never
  keeps a *Try again* over a retired repository.
- **Threads.** Everything Swift calls is on the main actor and non-suspending (no suspend
  function is called from Swift, and none of the calls throws); emissions arrive on the main
  actor (`for await` in a `@MainActor` task; the view model's scope is `Main.immediate`). The
  path monitor answers on a utility queue and hops to the main actor.
- **The network.** `photosOnWifi(satisfied, usesWifi, usesWired)` in Kotlin, fed by
  `NWPathMonitor`: a path that carries traffic over Wi-Fi or a wire. Low Data Mode and
  *expensive* are not asked (M7; a hotspot joined over Wi-Fi is Wi-Fi to the transfer).
- **The feature screen** (M4) is `photosFeatureScreen(state, capability, device)`: *This
  iPhone*, the other devices (off only from here), *Turn off everywhere*. With a roster that
  cannot be read: this iPhone alone, no *Turn off everywhere*, and a sentence saying so; *not
  read yet* is a different sentence (`ModelsUiState.otherDevicesRead`, new). With no device id:
  the switch is disabled and says so (`ModelsConsents.thisDeviceKnown()`, new, →
  `ModelsUiState.thisDeviceKnown`); nothing is written under a placeholder.
- **The fixture.** `-photosModels <state>` (DEV_HARNESS, with `-photosFixture`; opt-in, nothing
  is installed without it) puts the **real** `OnDeviceModelsRepository` over a source, a
  removal and consents the harness moves (`PhotosModelsFixture`, 30 states). Its model is the
  published catalog entry's (`siglip-base-patch16-256-multilingual`, *SigLIP base
  (multilingual)*, 743 448 870 bytes over three artifacts, Apache-2.0), so the screenshots and
  the cases show *743 MB download · about 2.6 GB on this iPhone*, which is what ships. ⚠⚠ No call moves
  the fake: `models-probe-advance` does, and `models-probe-calls` lists what was asked.
  `-photosModelsOpen models|feature` opens a screen without a tap; `-photosModelsBare` hides the
  two harness buttons for a screenshot.
- `ModelsSwitchHome.THIS_APP` (*in Photos, under Settings → Models*) is now true: those are the
  two titles, from `PhotosModelsCopy`.

**The six follow-ups** (each with a case; the first four change shipped Android and desktop code)

| # | Before | After |
|---|---|---|
| 1 | A removal cut off by a teardown was answered and published as `Failed`: *"Couldn't remove the model. It is still installed."*, of a removal that may still land | `ModelDeleteOutcome.NotConnected` (new) → `ModelsMessage.NotConnected`; **no ending is recorded** (an earlier ending of the same model is dropped too). iOS only in effect: Control's ledger never produces it |
| 2 | A repository first built during or after the teardown was never retired | `RetiredWithTheGraph`: latched; whichever of *built* and *retire* is second retires it |
| 3 | The copy's walk was built from hand lists | `theWalksListsHoldAMemberOfEveryCaseTheirSealedTypesHave` holds each list against its sealed type's members (`permittedSubclasses`), and fails if a type records none |
| 4 | The view model kept the repository's removal marks after the list had ended | Cleared where the flow ends; a row is not left *removing* by a list that is gone |
| 5 | `newsBetween` dropped `removalsEnded` when the store turned read-only in the same emission | Computed above the early return |
| 6 | `removalEndings` sat between `waiting`'s KDoc and `waiting` | Moved |

**Also changed for every platform:** `ModelsViewModel` collects `thisDeviceKnown()` wherever a
switch is offered (on Android and desktop one more poll of the device id while it is unknown;
Control's screen does not read the two new fields).

**Guards, each removed once and seen red** (fresh results; restored and `git diff --quiet`)

| Removed | Red in |
|---|---|
| a cut-off removal records no ending | `aRemovalCutOffByAScopeThatIsNotTheRepositorysRecordsNoEndingAndIsAnsweredNotConnected` |
| a cut-off removal is *not connected*, not *failed* | the same, and `aRetiredRepositoryLetsGoOfItsRemoval…` |
| the latch | `aRepositoryFirstBuiltAfterTheTeardownIsRetiredAsItIsHandedOut` |
| the marks go with the list. ⚠ **Survived the first run**: the case ended its list by saying *not connected*, and that emission lets go of the removal by itself. It now ends without a last word | `aListThatEndsLeavesNoMarkThatSwallowsTheEndingOfARemovalTheNextListShows` |
| `removalsEnded` above the early return | `aRemovalsEndingIsNotDroppedByAnEmissionThatAlsoTurnsTheStoreReadOnly` |
| other devices only from a roster known now | `theOtherDevicesAreListedOnlyFromARosterThatIsKnownNow…` |
| no switch without a device id | `aDeviceWithNoIdHasNoSwitchAndSaysSoAndNothingElseIsOffered` |

**Swift, run by hand (M10; no gate runs it):** `ModelsUITests`, twelve cases on a simulator
booted for it: no button without the flag; 26 states, each its sentences and exactly its
actions in order; a download with each choice, and the row moving only when the source does;
both answers to the feature question; a cancel; a removal that asks first, is said once and is
not replayed; one that fails; *Try again* withheld while checking; a screen open across a
re-provision; the feature screen's three shapes.

**The independent review (2026-10-14), and what it changed**

- The fixture first showed an invented *SigLIP 2 Base* at *709 MB*: the MiB figure, printed by
  a copy that counts decimal MB. The real row says 743 MB. Now the catalog's entry (above),
  and its on-device figure is held to `CoreMlDiskBudget.prepareBytes` by a native case.
- `PhotosSurface.models()` read the repository **before** the install count: an install between
  the two reads left the later count over the replaced repository, so the screen never built
  again. The count is read first now (`aBindingBuiltAcrossAnInstallIsOfTheEarlierInstall…`).
- Nothing pinned that the view model collects `thisDeviceKnown()` and the roster's answer into
  the fields the feature screen reads; a JVM case drives both through the view model now.

**Not done, not pinned**

- ~~A removal question up when a feature question arrives~~ — **done before 5e**: the order is
  Kotlin's (`PhotosModelsScreen.question`), and `testARemovalAskedBeforeTheFeatureQuestionIs…`
  runs it over a fixture that advances by itself (`removalThenOffer`).
- **Mia's three iOS lines.** Not small: the wording is in `:mia-service-proxy` commonMain with
  no seam, and the cure is a parameter on its factory that Mia's iOS runtime sets. Owed before
  the flag flips (`tech-debt.md`).
- Nothing has run on a phone. §14.18.7's list, with 21 to 24 added.
- Not pinned in a test: that `deinit` closes the binding (read in the code; the native case
  pins that `close()` ends the collectors); the alert's behaviour when a teardown lands while
  it is up (the state it is presented from is replaced, so it should go; not run).
- `Body.Hidden` is unreachable by the button's gate; the screen leaves if it is ever shown.
- The feature question while the device id is not known is still asked; *Turn on* then says
  *Couldn't save that*.
- ⚠ Unverified still (carried from the review): whether a launch re-probes a ready model
  through `Checking`. If it does, the feature question returns at every launch while consent
  is off. A phone's to show.

**The hardening before 5e (2026-10-14)**, from 5d-3's review and the integrated review of the
track, each with a case that fails without it:
- *The feature screen across a teardown* closes instead of going blank: the pressed capability
  is kept (`pressedFeature`), so `ModelFeatureScreen` is built and dismisses itself when the list
  no longer offers it; an empty `navigationDestination` had left a blank screen pushed.
- *One question*, Kotlin's (above). *One-shot messages*: *OK* clears the message it was pressed
  on (`consumeMessage(expected)`); a replacement stays, and VoiceOver hears it (`onChange`).
  ⚠ Control's Compose screens still call `consumeMessage()`: their snackbar has the same window,
  and is not changed here.
- *Not read yet is not off* (all platforms): `ModelsUiState.consentsRead` is `false` until the
  first read of the consents lands; until then every consent setter refuses (Android and iOS),
  Photos' screens say *"Checking whether it's on…"* with no switch to press, and Compose draws
  its switches disabled. A press in that window wrote the opposite of a seed over what was stored.
- *The fixture's sub-rows* 7b, 8c (all four other reasons), 8e, 9c, 10b, 10d, 10e and 11a are
  states of `PhotosModelsFixture`, in `ModelsUITests`' table; `-photosModels` now requires
  `-photosFixture`, as `ModelsProbe.swift` said it did; the example device is *Alex's Mac*.
- ⚠ *A removal that a teardown cut off* (5d-3's review, item 4): `PhotosLocalRuntime`'s
  deprovision bumps `modelsInstalls` first, so the screen is rebuilt over the not-connected
  runtime and the old view model's `NotConnected` message for the removal is never shown; the
  screen says *"Photos can't reach its library right now."*, which means the same. Not changed.
- *Consents off the main thread on iOS*: `ClientModelsConsents(work = Dispatchers.Default)` from
  `OnDeviceModelsRepository.over`; Photos' client is in-process and `LocalTaskExecutor` switches
  no dispatcher, so every preference read and write ran on the main thread. Control's IPC client
  keeps the caller's (`ClientModelsConsentsTest`).
- *A download press waits at most 15 s* for its admission (`StillStarting` after it; the call
  goes on, §14.17.0). *Search switched off lets go of the warmed text tower* at once
  (`consentWithdrawn` → `CoreMlInferenceBackend.closeText`), and at the next check when it was
  switched off elsewhere. *A refused or failed update says why on its row*, and bytes that failed
  their check are not offered again. *A delete over bytes that are no index* fails instead of
  answering *not installed*, which both platforms showed as *"Removed"*. `anyModelUsable` is
  *not known* while a row's standing is not read or unreadable.
- *Round 2 (2026-10-14)*: a load the scene leaves under is transient, not a kill; one
  lock for the verdict's file per process; the compile bracketed; `Standing.StoppedLoading` with
  one *Try again*; an absent model whose key is answered offers no download; a press whose caller
  stopped waiting says *Starting…* while its call lives and then what it answered; the feature
  question is not asked of consents never read. All in §14.5 item 0 and §14.17.0.
- *The device id* the warm-up reads (`SyncManager.currentDeviceId`) and the one the screen reads
  (`getSyncStatus().deviceId`, which `GetSyncStatusImplementation` fills from the same property
  of the same `SyncManager` single) are one value, read at different moments; the screen caches
  its first non-blank one for the life of the repository. Nothing changed.

#### 14.18.16 Before the flag flips

⚠⚠ **A second flag sits beside this one (2026-11-10):** `TENSHU_PHOTOS_FACES` → Info.plist
`TenshuPhotosFaces`, read by `PhotosLocalRuntime.facesBuildFlag` ([`ios-face-grouping.md`](ios-face-grouping.md)
FD10, §15). It binds the face backend, `coreml+vision` and the face pair's own readiness and
custody into the graph this flag admits, and **only** there: alone it binds nothing, and it needs
this flag's static gates too (`PhotosOnDeviceAi.facesAdmitted`). So flipping
`TENSHU_PHOTOS_ON_DEVICE_AI` binds search alone, exactly as below; faces flip after it, at face
grouping's F8. Both are `NO` in `project.yml` (`PhotosBuildFlagsDeclaredTest`).

⚠ **The Models screens are two-model where faces are bound** (`ios-face-grouping.md` §16, F6): the source
is a `CombinedModelSource` of SigLIP's `JoinedModelSource` and the face model's; `PhotosModelsScreen`
carries `features` (one row per switch; `feature` is the first, search's); a feature screen's device may
carry a `question` asked before its press (face grouping's *off*); and `ModelsUiState.serves` says which
model serves which feature. With faces off every one of these is what it was.

The phone-only items below, with IN-3m-iOS and the lists of §14.16, §14.17.6, §14.18.7 and §14.19.9, run as one session: [`../how-to/pio7-phone-session.md`](../how-to/pio7-phone-session.md). ⭐ **What is left for the phone is the ordered list at the top of that file**, each line with its pass signal; the items below say which of them are closed, and where.

⭐ **Shown on the maintainer's iPhone 17e (iOS 26.6.1), 2026-11-09**, with the flag on in a
development build: the model downloaded over Wi-Fi and finished *Preparing*; the preflight passed
(`preflight.verdict` passed, attempts 0); consent was given; *Index now* indexed the 22-photo
library; "cat" finds all 5 cats (after the relevance-floor fix, `inference-service.md`); the idle
hint and the iOS 26 indexing copy are right (item 15). ⚠ **That is not H5 as written**: H5 asks for
≥ 200 photos and "dog". A 22-photo library and "cat" were shown, not ≥ 200 and not "dog".

Beyond 5e itself (5e-1 and 5e-2 built, §14.19, §14.19.11):
1. ⚠⚠ **A phone where loading the towers kills Photos** — built (§14.5 item 0), and only a phone
   shows that jetsam or Core ML kills at the moments the record brackets; with it, the memory
   threshold ① from a measurement (D10/D12), not the placeholder. **Owed, 📱** (phone list).
2. An iOS 17 simulator load test of both towers (D12 ⓪), ~~IN-3m-iOS~~ (**passed 2026-10-23**,
   `inference-service.md` §15.14), and H5 on the phone.
   - ⚠ **D12 ⓪: still owed, and not runnable on the build Mac (checked 2026-11-09).** Xcode 27.2
     offers no iOS 17 simulator runtime: `xcodebuild -downloadPlatform iOS -buildVersion 17.5`
     (also 17.0, 17.2, 17.4, and 18.6) answers *"is not available for download"*, while 26.5 is
     offered. The Mac holds iOS 26.5 and 27.2 only. The load needs an older Xcode beside this one,
     or a phone on iOS 17. The case to run there is `CoreMlRealArtifactTest` (both real towers,
     then the shipped probe through `CoreMlPreflightTowers`), with `-Dtenshu.inference.coremlExport`
     and `-Dtenshu.inference.tokenizer` (its KDoc), on an iOS 17 simulator device.
     ⚠ Read, not run: every Core ML symbol the iOS backend calls (`MLModel.compileModelAtURL`,
     `modelWithContentsOfURL:configuration:`, `predictionFromFeatures`, `MLModelConfiguration.computeUnits`,
     `MLComputeUnitsCPUAndNeuralEngine`, `MLMultiArray`, `MLDictionaryFeatureProvider`) is iOS 16 or
     older in the iOS 27.2 SDK's headers. That rules out a missing symbol, not an ML Program the
     iOS 17 compiler refuses, which is the question.
   - **H5: partly shown (2026-11-09, above), owed as written** (≥ 200 photos, "dog"; phone list).
3. ✅ **Closed (2026-11-09) by the written decision, for development builds; shipped builds are
   distinct already.** ⭐ **The maintainer's decision, 2026-11-09:** a distinct `CFBundleVersion`
   for archives only; development builds stay at `1`. Every archived build — TestFlight and the App Store, the only builds a
   user installs — carries its own, growing `CFBundleVersion`: `scripts/ios-archive.sh` passes
   `CURRENT_PROJECT_VERSION` = the UTC minute (`YYYYMMDDHHMM`) and checks the product's plist reads
   it (`ios-testflight.md`). So an app update is a new key: its preflight re-runs (seconds), and a
   key's counts — a fail of interrupted loads, a spent *Try again* (item 10) — start again.
   **Written down here: between two development builds** (Xcode, or `xcodebuild` as in
   `pio7-phone-session.md` B0), which read `ios-release/release.yml`'s constant `1`,
   **`PREFLIGHT_REVISION` alone stands between a code change and a stale pass.** ⚠ Not built: a
   per-build number for development builds. The archive script sets the number itself and
   asserts it on the product, so a build phase that rewrote the plist would fight it; and a
   development install is the maintainer's own phone, whose re-probe is one bump of
   `PREFLIGHT_REVISION` away. Every iOS update still empties the index until a 5e foreground pass
   (the fingerprint holds the OS build).
4. ~~Mia's three iOS lines~~ — **built in 5e-2** (§14.19.11). Still a phone's to show: that Mia's
   own process answers `NO_BACKEND` there, which is the line its user meets. **Owed, 📱.**
5. `ModelDownload.drain` and the Swift relaunch path, never run.
   **Simulator, closed as far as Kotlin reaches (2026-11-09):** the drain in the order
   `PhotosIosApp.init` and `ModelDownload.drain` run it — a build that offers nothing, finding an
   enabled build's leftovers, names every live task for cancelling, lets none speak, deletes what
   was delivered and keeps the journal, and the next enabled build resumes from that journal and
   fetches again exactly what the drain took, Wi-Fi only
   (`aFlagOffBuildDrainsWhatAnEnabledOneLeftAndTheNextEnabledBuildResumesFromTheJournal`). The
   relaunch's Kotlin half was already run there: a relaunch installs from the journal, a corrupted
   delivery is discarded, a lost transfer is started again, nothing settles in the background
   (`PhotosModelDownloadTest`). **Owed, 📱:** the Swift around it, with a real `nsurlsessiond` —
   that a flag-off build's `getAllTasks` lists an enabled build's tasks, that cancelling them ends
   the relaunches, and `handleEventsForBackgroundURLSession` with a terminated Photos. The
   simulator runs a background session's transfers but neither suspends nor relaunches for them
   (`ModelDownload.swift`'s header), and the photos project has no Swift unit-test target.
6. The first real downloads on a phone: the disk budget (§14.17.5) and an update's row (§14.11)
   as a user meets them; §14.18.7's phone list, items 21–24 included. ⭐ One Wi-Fi download through
   *Preparing* shown 2026-11-09. **Owed, 📱:** the interruptions, the disk figures, items 21–24.
   An update's row needs a second published catalog entry; none exists.
7. ✅ **Answered (2026-11-09): it re-probes nothing; it does pass through `AwaitingVerdict` for one
   publication.** A launch over a model that passed loads no tower and runs no probe while consent
   is off (Photos' own graph on the simulator,
   `aLaunchOverAPassWithConsentOffProbesNothingAndLoadsNoTower`; `aPassOutlivesTheProcessAndIsNotProbedAgain`).
   ⚠ **Found by that case:** readiness publishes `NoModel → AwaitingVerdict → Ready` at each process
   launch, because the fingerprint is applied before the model is served
   (`aPassOutlivesTheProcessAndIsEnteredInOrder`, which pinned the order but not what it publishes).
   The join draws `AwaitingVerdict` as `Checking` (`ModelSetupJoin`), and the view model asks the
   feature question when it sees `Checking → Ready` (`theFeatureQuestionIsAskedWhenAModelIsSeenToBecomeUsable…`,
   *"a screen opened while it was being checked"*). So a Models screen **collecting during the
   launch check** would flash *Checking* and, with consent off, ask the question again. ⚠ Read as
   not reachable, not changed: the check runs at the runtime's install, before any screen can be
   pushed; a screen opened afterwards reads `NotReadYet → Ready`, which asks nothing (the join's
   `aRelaunchOverAReadyModelIsNeverShownAsPreparing…`, the repository's relaunch case); a return to
   the foreground in the same process does not hop (`reconcile` stays at `Ready`); and a sign-out with
   the screen open rebuilds the screen over the new runtime (§14.18.7 item 23). If the phone (row 2 of
   the list) ever shows *Checking* at a launch, the fix is the join's — a `Ready` once seen for a key
   is not left for an `AwaitingVerdict` with no probe running — not readiness's order. A launch
   **does** re-probe after an app update, an OS update or a new model: a new key, by design (seconds).
8. The maintainer's calls in §14.5 item 0, settled 2026-10-14: the bound stays two; a load the
   scene left under is transient; the compile is bracketed; a fail of interrupted loads alone has
   one user-started way out. Still the phone's to show: that the scene's write lands before iOS
   suspends Photos. **Owed, 📱.**
9. ~~The backend's own tower opens after `Ready` are not bracketed~~ — **built in 5e-1**
   (§14.19.3): each tower's open is recorded in its own file, outside readiness's lock. Still the
   phone's to show, as for every record: that jetsam kills at the moment it brackets. **Owed, 📱.**
10. ✅ **Closed (2026-11-09): one *Try again* per key, counted on disk.** `PreflightPolicy.LOAD_AGAIN_PRESSES`
    = 1; `PreflightAttempts.loadedAgain` counts the presses and `AiReadiness.loadAgain` writes it
    before the load it allows. Past it, `stoppedByInterruptedLoads` is false: the fail of a phone
    whose own retry also killed Photos is D12's plain `Unsuitable` — *"This iPhone couldn't run the
    model well enough, so search by description stays off."*, and no download offered for that key (§14.5 item 0, *No download the key
    would answer at once*). ⚠ **Not per foreground**, as first recommended: every kill is a new
    process and a new foreground, so a per-foreground cap bounds nothing, and *remove, then
    download again* reaches no load for that key either. This is the round-2 call as written
    (*"`Standing.StoppedLoading` with one *Try again*"*, §14.18.15), made exact. What starts the
    count again is a new key: an app update (item 3), an OS update, a new model. A pass keeps the
    spent press. Not a `PREFLIGHT_REVISION` bump: no pass and no fail changes, only the way out.
    ⭐ **The maintainer's decision, 2026-11-09:** one *Try again* per key, kept on disk, with no
    reset on a foreground or a removal — accepted over the per-foreground recommendation it
    replaced. ⚠⚠ **A pass resets `interrupted`** (`PreflightAttempts.afterPass`; the kills of
    served tower opens are counted there too), and keeps `loadedAgain` spent. The bound counts kills
    with **no pass between them**: without the reset, two kills, *Try again* and a pass left
    `interrupted` at 1, and one kill weeks later (a jetsam at a peak, a crash, a battery that died)
    made a key that had passed `Unsuitable` for good (PR review, 2026-11-09). A load that kills
    Photos every time still cannot loop: a press needs a fail, and the fail after it needs two kills
    in a row. Tests: `aRetryThatAlsoKilledPhotosIsAPlainFailWithNoSecondTryAgain`,
    `aRetryThatPassesKeepsItsSpentPressForTheKey`, `aKillLongAfterARetryPassedIsCountedFromZero`
    (red with the reset deleted); the copy was already per state.
11. ✅ **Closed (2026-11-09).** `iosPreflightKeyOf` equals the installed key over the store a phone
    runs: `DefaultModelStore` with its index in `FileModelIndexStorage` over the real
    `IosFileDatabase`, artifacts arriving as the background download delivers them (`adoptStaged`,
    `commitStaged`), the key read back by a new store over the same disk
    (`theKeyOfAnEntryAsItWouldBeInstalledIsTheKeyThePhonesFileStoreReadsBack`, beside the
    `InMemoryModelStore` pin).
12. 5e-1's own phone list (§14.19.9): real thermal, power and battery transitions; the cancel's
    landing against the background window on a large library; memory over a full pass; that
    `applicationWillTerminate` arrives for a swipe-away of a running Photos. **Owed, 📱.**
13. ~~The rest after heat (`REST_AFTER_HEAT_MS`, 60 s) and the absent chunking, from PR-1's
    duty-cycle run (§4.5)~~ — **decided 2026-10-23: kept as built, no chunking** (§14.19.8).
14. ~~A kept `Pressed.Refused` is dropped only while the Models list is collected~~ — **fixed in
    5e-2** (§14.19.11): the call that kept the refusal watches the journal on the source's own scope
    until it names the model or the refusal is gone (`forgetRefusalOnceJournalled`). ⚠ Only the
    journal's half: a refusal kept over a row that became *held with no update* while no screen
    was open is still dropped by the next screen's first emission, not before it.
15. ✅ **Done (2026-11-09) — PR-7's copy (§15.7 Q2), a precondition of the flip** (PR-7 review
    round 1): the indexing section's running line *"Keep Photos open. Indexing stops when you
    leave."* was false on iOS 26 while a continued task carries the pass. **As built:** it follows
    the claim. `ContinuedIndexing.standings` → `ForegroundIndexSource.continued` (`LIVE` alone) →
    `ModelsUiState.indexContinued` → `PhotosModelsCopy.runningDetail`, which says *"You can leave
    Photos. Indexing goes on, and iOS shows how far it has got."* while a launched task carries the
    pass, and the old line otherwise: below iOS 26 (no scheduler, the claim stays `NONE`), a request
    refused, and a request submitted but not yet launched (a leave then still stops it, §15.2). The
    press's *"Indexing started. …"* message follows the same rule, drawn from the state.
    ⚠ *"Preparing… Keep Photos open. About ten seconds."* stays: the prepare step is never
    continued, and nothing is compiled in the background (`PhotosLocalRuntime.modelTransfersSettleNow`).
    Android and desktop do not draw this section's copy. Tests: `theRunningLineFollowsTheContinuedTask`,
    `continuedIsTrueOnlyWhileALaunchedTaskCarriesThePass`,
    `theStandingsFollowTheClaimAcceptedRefusedAndWithNoScheduler`; each guard removed once and seen red.

**After 5e-2** (§14.19.11): the indexing section is on (`capabilities.indexing = true` where the graph
binds a pass). The flag flip still owes the phone list, the App Store privacy label (the manifest
declares no collection, §14.19.11), and `Body.Hidden`'s gate checked against whatever replaces the
build flag.


#### 14.18.17 As built — the `theSourceShows…` "flake" was the real CDN (2026-11-05)

`PhotosModelDownloadTest`'s relaunch case expects a second press to be refused `NoCatalog`, on a
graph the case took to have no catalog. It had one: every graph built by `PhotosLocalRuntime.assemble`
binds `named("modelCatalogUrl")` to `https://models.service.tenshu.app`, and the published `coreml`
SigLIP entry (§12, live since 2026-10-11) verifies under the pinned key. So the press asked the
production CDN, and the case answered what the network did (measured on one iOS 26.5 simulator,
one test binary, the catalog URL varied by a diagnostic override):

| The catalog fetch | `download()` answered | The case |
|---|---|---|
| reaches the CDN (simulator booted) | `Accepted`: the real entry's digests differ from the hand-written journal, so it **replaced** the running download (§14.11) | red, 3 of 3 alone, 20 of 20 in the class |
| a server that accepts and never answers | `StillStarting`: no admission within `admitWait` (15 s, §14.18.10) | red, the message `iosTestCheck` showed |
| fails at once (connection refused, or `--standalone` on a simulator that is not booted) | `Refused(NoCatalog)` | green |

⚠⚠ **Not a product defect.** Each answer is the designed one for its catalog: a newer entry
replaces a journalled one, and a press whose fetch outlasts 15 s says *Starting…*. Nothing a user
sees changes. The defect was the fixture: the class header said "no case asks the catalog", and
three cases did (the source's first collection fetches it too).

**The fix (test-only):** every graph of the class is built with a blank
`modelCatalogPublicKeyHex`, which `HttpModelCatalogSource` answers with *no catalog* and **no
request**. ⚠ The key, not `modelCatalogUrl`: the URL is also the artifacts' base, and a blank one
broke `aTransferTheSessionLost…`'s restarted transfer (20 of 20, seen and reverted). One helper,
`noCatalogThen` (`PhotosTestCatalog.kt`), is what both classes that build admitted graphs pass as
their overrides: `PhotosModelDownloadTest` and `PhotosOnDeviceAiTest`, whose Models-screen case
collected the model source over the real CDN on every run too. The case now asserts
its premise on its own graph, and each class's `noGraphOfThisClassTrustsTheRealCatalog` pins the
binding, red whatever the network does when the override is removed.
⚠ The rule for any later iOS simulator case over an admitted graph: unpin the catalog yourself;
the default asks production.

### 14.19 Slice 5e (2026-10-14): the foreground pass. 5e-1, the conditions, the pass and its stop, built; 5e-2, the indexing section, built (§14.19.11)

Marks as above. The flag stays off. No schema'd type and no wire shape moves (HR 9): every
value below is in-process, and the records are files in Photos' private container. ⚠ **5e is two
PRs**: 5e-1 (this section's *built* parts, with the hardening's review carry-over) and 5e-2 (the indexing
section of the Models screen, §14.19.6, built: §14.19.11). With 5e-2 `OnDeviceModelsCapabilities.indexing`
is `true`; the build flag is still off, so nothing of it is reachable in a shipped build.

#### 14.19.1 The conditions, and where each is read

`ForegroundIndexingConditions` (commonMain) over `IosIndexingSignals` (iosMain), bound in
Photos' graph alone as `named("indexingConditions")`; every other route-1 graph keeps `Never`.

| Condition | iOS source | Effect |
|---|---|---|
| in the foreground | the scene phase as Photos' Swift reports it (`PhotosOnDeviceAi.inForeground`; `.inactive` is not a leave, §14.5 item 0) | `NOT_IN_FOREGROUND`: no pass begins, a running one stops at its next photo; leaving also **cancels** (§14.19.2) |
| heat | `ProcessInfo.thermalState` ≥ `.serious`; `thermalStateDidChangeNotification` | `TOO_WARM`: stops at the next photo; starts again by itself after a drop to `.fair` or below **and** `REST_AFTER_HEAT_MS` (60 s, ⭐ kept 2026-10-23 after IN-3m-iOS's sustained run, §14.19.8) |
| Low Power Mode | `isLowPowerModeEnabled`; `NSProcessInfoPowerStateDidChange` | `LOW_POWER_MODE`: stops; starts again when it is switched off |
| battery | `UIDevice.batteryLevel` < 20 % and not charging (`batteryState`), monitoring switched on once; the two battery notifications | `BATTERY_LOW`: stops; starts again on a charger or above 20 %. ⚠ A level the phone does not report (−1: a simulator, monitoring off) is **allowed**, as on Android: §34's *Index now keeps only battery-not-low* reads a refusal on a missing signal as one that never clears |
| memory | `UIApplicationDidReceiveMemoryWarning` | the pass is cancelled and **both towers closed** (`closeSessions`); nothing restarts it but a press |
| the scheduled bar | — | **no outside a processing wake**; inside one, no at `.serious`, in Low Power Mode or off the charger (PR-6, §16; `scheduledIndexingBlock`) |

Read at four places: the op (*Index now*), the runner **before the walk** (new, below), the
indexer per image (as on every platform), and the driver when the phone moves. ⚠ The reasons are
typed in the process (`IndexingBlock`, M8); the op's refusal stays the one string `notReady`, and
the driver reads the block again for the reason (no wire change).

#### 14.19.2 The pass's lifecycle

- **Start.** A press goes to `ForegroundIndexing.indexNow`, which calls **the operation**
  (`StartMediaIndexPass`) through Photos' in-process client: its four refusals (paused, no model,
  no consent, the manual bar) are checked once, there. The op starts the graph's
  `MediaIndexRunner` (single flight), whose pass then takes **the process's pass slot**
  (`PassExclusion`, new: two graphs of one process have two runners), asks the custody's gate
  (`beforePass`), asks **the bar once more before the walk** (new, on every platform: a pass that
  begins after the device stopped allowing it reads nothing of the library — on iOS that walk is a
  tree read of the shared replica in the background), walks, reconciles and indexes, reading the
  bar per image. Photos owns the stores (PIO-D10); the decoder is `IosImageDecoder` (the backend's).
- ⚠⚠ **Every ending is published.** The pass's coroutine starts `ATOMIC` and stops at once
  (`ensureActive`) when it was cancelled before dispatch: with the default start its body and
  `finally` never ran, the bar stood and the driver stayed *asked* for good.
- **Progress.** `IndexProgressHolder`, as on Android, and `MediaIndexRunner.standing` (new):
  `Running(pass)` or `Idle(lastEnding)`, **one value, each carrying the pass number it is about**
  (`Finished(result)`, `Stopped`, `Failed`). Moves to `Running` with the bar's publication and to
  `Idle` only after the bar is cleared.
- ⚠⚠ **Leaving the foreground** (§3.3): `PhotosOnDeviceAi.sceneChanged(…, false)` writes the
  scene's record, moves the scene (so the per-image bar already says no), then calls
  `ForegroundIndexing.leftForeground()`, which on the main thread only marks the ask
  `Stopped(LEFT_PHOTOS)` and opens `beginBackgroundTask`; on the driver's scope it then
  **cancels** the pass (`cancelRunningPass`, which joins it), **closes both towers**, and ends the
  window, once (`ATOMIC`, so a teardown in between still ends it). Also with no pass: the towers go
  (§14.1's *closeSessions on background with no pass*). The next press continues from the store.
  ⚠ The first search after a return then pays one text-tower open (24–88 ms, §2.2).
- **Resume.** A pass stopped by the phone (heat, Low Power Mode, battery) leaves the ask
  `Waiting(block)`; it starts again **by itself** while Photos stays in front, through the same
  op, once the block clears (heat: after the rest). Leaving the foreground or a memory warning ends
  the ask; a press resumes.
- **Endings** (every one): done or paused → `NotAsked`; deferred with a block the phone still
  says → `Waiting`; deferred with none (a block that cleared before the ending was read, or the
  stores' gate: the two look alike) → resumed **once** through the `Resuming` claim, then
  `NotAsked`, never waited out (§14.19.10); stopped (a model leaving `Ready`: readiness's
  `cancelPass`) or failed → `NotAsked`.
- **Teardown.** `PhotosOnDeviceAi.retire` cancels the driver's scope; readiness's unload cancels
  the pass and closes the towers as before. A successor graph's pass waits in the slot until the
  old one has let go.
- **Kill.** §14.19.3.

#### 14.19.3 ⚠⚠ The backend's own opens are recorded (closes §14.18.16 item 9)

**Decided: bracket them**, with D12 in mind. Unbracketed, a phone killed only by a tower open
after `Ready` loops: `warmText` opens the text tower at **every launch** with search consented,
and a press opens the vision tower. Now `AiReadiness.servingOpen` writes an
`AttemptBegun(kind = SERVE_OPEN, role, key = the served key, process, serial, foreground)` before
the open and clears it after, and every reading of readiness counts one a dead process left
exactly as a probe's (transient when the scene had left; else `interrupted`; two are
`Unsuitable`, with *Try again*, §14.5 item 0).

- ⚠⚠ **Not in the verdict's file, and never under readiness's lock.** The backend opens under its
  tower's lock, and a writer that stops serving holds readiness's lock and the verdict file's
  while it waits for that tower (`stopServing` → `closeSessions`). A record taken under either
  closes the cycle — the reason item 9 was left unbuilt. So each tower has its own file,
  `/inference/preflight.verdict.open.<role>`, with its own lock (`AttemptLedger.lockFor`), held for
  one small read or write and **waiting for nothing**: the innermost lock of the process.
- *Settled* inside the reading (`settledOpens`): the count written before it is believed, then
  the record cleared. ⚠⚠ **One load per dead process**, however many towers' records it left:
  both towers open at once is the peak of memory, so counting per file made one jetsam
  `Unsuitable` (§14.19.10, 1). ⚠ A clear that fails counts it again at the next read (a kill too many,
  never one too few); an unreadable record is an unreadable reading (nothing served, nothing
  loaded); this process's record and another key's are left for the tower's next open.
- *Cost:* two small writes per tower open (an open per foreground at most, more after a memory
  warning).

#### 14.19.4 What it adds to §14.17.0's graph

| Party | Holds while it waits | Waits for | Why that ends |
|---|---|---|---|
| **a pass** (the runner's coroutine) | nothing, at the slot | **the process's pass slot**: an earlier graph's pass | that pass is being cancelled by its graph's unload; a party of a retired graph waits for no unload (§14.17.0's rule), so it ends at its next photo. No caller waits on the slot: `start` has returned |
| **a backend open** | the tower's lock | **the tower's open-record lock**, twice, briefly | its holders read or write one file and wait for nothing |
| **a reading of readiness** | readiness's lock, the verdict file's | the open-record locks, briefly | as above. Order: readiness → verdict file → open record; tower → open record. Nothing that holds an open-record lock waits for any other lock |
| **leaving the foreground** | nothing (main thread) | — (it launches) | the launched stop waits for the pass's join and the towers' locks: a prediction (finite) |
| **a press / the driver's resume** | nothing | the op, then the runner's lock | as §34.3 |
| **a memory warning** | nothing | the pass's join, the towers' locks | as above |

Acyclic: the two new locks are leaves (the slot's holder waits for no slot and for no unload once
retired; the open-record lock's holder waits for nothing), and no new edge enters a lock that a
wait in §14.17.0 already holds.

#### 14.19.5 What each value carries

| Value | Carries | So that |
|---|---|---|
| `PassStanding.Running` / `Idle(PassEnding)` | the pass's number (per runner) | an ending is never taken for a later pass's |
| `ForegroundIndexingState.Asked(fromPass)` | the first pass number that answers this ask (the running one when a press joined it) | a late ending of an earlier pass does not end this ask |
| `ForegroundIndexingStanding.leftForegrounds` | how often Photos left the foreground | a press answered after Photos left asks for nothing, and stops what it started |
| a serve open's `AttemptBegun` | the served `PreflightKey`, process, serial, foreground | counted only against the install it loaded, never this process's own |
| `SceneRecords` | one record per process | §14.5 item 0, carry-over 1 |
| `IndexingBlock` | nothing: a reading of *now*, read again at each decision | — |

Every check-and-claim is one `update {}` with no side effect in the lambda (the press, the
resume's claim to `Resuming`, the endings); a resume that started a pass after Photos left
cancels it.

#### 14.19.6 Slice 5e-2, designed: the indexing section (built as designed but where §14.19.11 says)

`OnDeviceModelsCapabilities.indexing = true`, over the shared `ModelsViewModel` (M3), with
Android and desktop unchanged. `OnDeviceModelsRepository` gains the indexing calls over
`ForegroundIndexing` (*Index now* → `indexNow`, typed), the progress from the runner's holder,
the pause row (`IndexingPaused`, as Android), and a typed *why not* from `standing`. Copy as
Kotlin values beside `PhotosModelsCopy`; Swift renders what Kotlin chose. States, in order:
- no model, or one not `Ready` (its standing says which) — no button;
- consent off (which switch) — no button, the switch named;
- paused — *Resume*;
- running — *Indexing N of M*, with the bar;
- `Waiting(block)` — *Waiting: this iPhone is warm / Low Power Mode is on / the battery is low; it
  goes on by itself* (§3.3; *Waiting*, not *Paused*: the user's pause is its own state, 2026-10-15);
- `Stopped(LEFT_PHOTOS | LOW_MEMORY)` — *Stopped when you left Photos / iPhone ran low on
  memory*, with *Index now*;
- not asked — *Index now*, the coverage line as the first line (no *"not indexing"* line, 2026-10-15).
⚠⚠ **The coverage line after an iOS update** (§14.18.16 note): the fingerprint holds the OS build,
so an update clears every vector at the next check; the line must say *the index was cleared
after an iOS update* (from the custody's `FingerprintOutcome`, which is in memory and says it)
rather than a bare *0 of N*. ⚠ `LastIndexCoverage` is process-local and empty until a pass
finished in this process; on iOS the line also needs the store's own count, said as *can be
searched*, never *indexed*. Fixture states in `-photosModels`, `ModelsUITests`,
screenshots. Also 5e-2's: Mia's three iOS lines (§1.8; a seam on
`MiaServiceProxyImplementationFactory`), and Photos' `PrivacyInfo.xcprivacy` (in `project.yml`;
`tech-debt.md` *No iOS app ships a privacy manifest*).

#### 14.19.7 Guards, each removed once and seen red (JVM, fresh results)

| Removed | Red |
|---|---|
| the cancel on leaving the foreground | three `ForegroundIndexingTest` cases |
| the resume's claim (`Resuming`) | three: two changes started two passes |
| the press's `leftForegrounds` check | `aPressThatReturnsAfterPhotosLeftStopsWhatItStartedAndAsksForNothing` (after it was made to hold the pass's end back; it passed for the wrong reason first) |
| an ending's pass number | `anEarlierPassesEndingDoesNotEndTheAskThatFollowedIt` |
| the memory warning's close | `aMemoryWarningStopsThePassAndClosesTheTowersAndNothingResumesIt` |
| the heat rest | `aPressOnAWarmPhoneWaitsAndStartsOnceWhenItCoolsAfterTheRest` |
| the runner's slot | `twoRunnersSharingTheProcessesSlotNeverRunTwoPasses` |
| the bar before the walk | `aPassTheBarRefusesByTheTimeItBeginsWalksNothing` |
| a serve open's record | two `AiReadinessTest` cases |
| counting a dead process's open | `aTowerOpenedWhileReadyThatPhotosDoesNotSurviveIsCountedAndTheSecondStopsTheModel` |
| carry-over 1 (the foreign scene record kept) | `theNextProcessesSceneRecordDoesNotEraseTheEarlierOnesBeforeItIsRead` |
| carry-over 4 (`!retired` before a stop under the lock) | `aRetiredGraphStopsNothingUnderTheFileLockWhenTheStoreOrTheCacheChanges` (and `retiringLeavesReadyInOrder`) |
| carry-over 5 (the kept refusal dropped) | `aKeptRefusalIsDroppedOnceTheJournalNamesTheModel` |
| *5e-1 review:* a leave that names no foreground skipped (1) | `aSecondLeaveDoesNotEraseTheForegroundTheFirstOneLeft` |
| the scene records named by a tower's open record kept (2) | `anOpenFrozenWhenTheSceneLeftStaysTransientWhenTheNextProcessWritesItsSceneFirst` |
| the pass's `ATOMIC` start (3) | `aPassCancelledBeforeItWasDispatchedStillEndsAndClearsTheBar`, `theForegroundDriverReadsTheRealRunnersEndings` |
| the `ensureActive` at the top of an `ATOMIC` pass (3) | `theForegroundDriverReadsTheRealRunnersEndings`: without it a pass cancelled before dispatch ran to its end |
| the withdrawal's deletion on the bar's refusal (4) | `aPassTheBarRefusesBeforeTheWalkStillDeletesTheTemplatesOfAWithdrawnUser` |
| the rest clock restarted on a new block (6) | `aChangedBlockRestartsTheRestClock` |

On the simulator, through Photos' own graph (`PhotosOnDeviceAiTest`): *Index now*'s three
answers through the operation (refused, waiting for the heat, started over an empty library);
leaving the foreground closes the loaded tower inside a window that ends; every graph of the
process shares one slot; the backend's open leaves its record cleared. ⚠ The simulator run found
what the JVM's could not: the op's reason strings are `notReady`/`noBackend`, and the driver had
its own `NOT_READY` constant — self-consistent on the JVM, wrong against the real op.

#### 14.19.8 Deferred, and why

- ~~**Chunking**~~ (§3.3: a pass rests after N photos) — ⭐ **decided 2026-10-23: none; the heat stop
  stays as built** (stop at `.serious`, start again at `.fair` or below plus `REST_AFTER_HEAT_MS`,
  60 s). The duty-cycle run (`inference-service.md` §15.14.2) found `.serious` after ~4 700 photos on
  the charger and **no slowdown** at any thermal state, so a rest buys no speed; what the stop
  protects is the phone in the hand, and Apple's guidance is to cut work at `.serious`. Stopping at
  `.critical` was declined: it was measured only on a current phone. ⚠ **Owed, and it sets how long
  a pause lasts:** the time from `.serious` back to `.fair`. That cool-down, not the 60 s, is most
  of each pause, and it is not measured (the phone read 0 again within ~50 min; it was not checked
  sooner).
- **`BGProcessingTask`** (§1.4, §3.3): PR-6, built (§16). **`BGContinuedProcessingTask`**: PR-7, built (§15).
- **D12 ⓪'s load of both towers on an iOS 17 simulator runtime**: this Mac has iOS 26.5 and 27.2
  only, and Xcode 27.2 offers no iOS 17 (or 18) runtime for download (checked 2026-11-09,
  §14.18.16 item 2).
- ~~5e-2's list (§14.19.6)~~ — built (§14.19.11).

#### 14.19.9 Not proved, and only a phone can show

Real thermal, Low Power Mode and battery transitions and their notifications; the time a
`cancelRunningPass` takes to land mid-pass (a walk is not cancellable inside a store read; a
large library's walk length is not measured) against the background window; memory over a full
pass through the backend (§9.4's harness figure is not it); a suspension that catches a pass
anyway; per-photo timing in the product path; that `applicationWillTerminate` arrives for a
swipe-away of a running Photos; that a memory warning arrives before a jetsam.

- ⚠ **`UIDevice` off the main thread, under Main Thread Checker** (cloud review 6, plausible,
  not changed). `IosIndexingSignals.observe` *writes* `batteryMonitoringEnabled` from the Koin
  `single`, first resolved from `recheckOnce` on a `Dispatchers.Default` scope, and `read` reads
  `batteryLevel`/`batteryState` per photo on the pass's dispatcher. `UIDevice` is a UI-actor class in
  current SDKs; `IosBackupEnvironment` is precedent for reads only. One foreground pass from Xcode
  with Main Thread Checker on decides it (`how-to/pio7-phone-session.md` B6 step 1); a report naming
  `UIDevice` moves at least the one-time enable to `Dispatchers.Main`.

#### 14.19.10 5e-1's cloud review, after the merge (2026-10-14)

5e-1 merged before its cloud review finished; these are its follow-ups, each
re-read against master first. All six were real. No schema'd type, no wire shape (HR 9).
⚠ Written in the cloud: **not compiled or run** — the gate, `iosTestCheck` and the Photos smoke
build are owed before this is trusted.

| # | Finding | As built | Red without it |
|---|---|---|---|
| 1 🟠 | One dead process with **both** towers' opens on disk counted twice: `Unsuitable` after one jetsam, at the peak-memory moment | `settledOpens` collects every role file's dead-process records first, counts **one per process** (earliest `atEpochMs`; the latest foreground it opened in, so an open in a foreground the scene never left stays a kill), writes the count, then clears each record **only if it is still the one counted** (this process's own open may have written over it once the lock was let go) | `aProcessKilledOpeningBothTowersCountsOneInterruptedLoad` |
| 2 🟡 | A deferral whose short block had **cleared** by the time the ending was read fell to `NotAsked`, nothing to resume it | The reviewer's second option, **bounded**: `deferred && block == null` (no block implies the foreground) claims `Resuming` and starts again at once. ⚠ Once per ask (`resumedAsk`): the stores' gate also defers with no block and is indistinguishable here, and unbounded it is a pass per turn of the loop. The first option (the conditions remembering their last refusal) was not taken: the memory goes stale across passes and turns a stores'-gate deferral into a `Waiting` that every phone change retries | `aPassDeferredByABlockThatClearedBeforeItsEndingWasReadIsResumed`; `aDeferralNothingHereCanNameIsTriedOnceMoreAndThenNotWaitedOut` (the bound; it replaces `aDeferralNothingHereCanNameIsNotWaitedOut`) |
| 3 🟡 | `fromPass` from a `standing` read before the op: a pass ending during the op, and the op launching the next, gave the ask the earlier number | `passOfThisAsk(before)`, read once the start returned: the standing now wins when it names a later pass (a join keeps the joined pass, a fresh launch gets its own). The press and the resume both use it | `aPassThatEndsWhileAPressIsInTheOpDoesNotEndTheAskThePressMade` |
| 4 🟡 | An outgoing pass's unwinding clears the replacing pass's bar | ⚠ **Wider than reported:** `MediaIndexer.index`'s own `finally` clears the bar too, inside the slot, so guarding the runner alone left iOS broken. Both halves: the runner's `finish()` only while `standing` is still `Running(this pass)`, and the pass publishes `start(0)` again once `exclusion.around` is its own. The launch-time `start(0)` stays (§34.5), so **Android/desktop are unchanged**: their `start` joins the outgoing pass under the lock, so the guard always finds its own `Running`, and the second `start(0)` sets an equal value. Moving `start(0)` into `around` instead was not taken: off iOS it runs after `start` returns, which is §34.5's defect | `aPassWaitingInTheSlotForOneStillUnwindingKeepsItsBar` (the re-publish); `anOutgoingPassesFinallyDoesNotClearTheBarOfThePassThatReplacedIt` (the guard) |
| 5 ⚪ | The reconcile's KDoc said it runs before the conditions on purpose; the pre-walk bar check (race-only, no wrong refusal) skips it | KDoc amended to say so, and not to move it back. `stillOwed`'s KDoc no longer calls its inline `try` *a plain call* | — (docs) |
| 6 ⚪ | `UIDevice` off main | Not changed: owed to the phone (§14.19.9, B6) | — |

⚠ Remaining narrow window of 4: the guard is a check then a clear, not one step. An outgoing pass
that reads its own `Running` and is then descheduled while the next pass launches *and* takes the
slot can still clear it; the bar comes back at the next pass's total. Closing it needs the clear and
the standing in one step, which the bar-before-`Idle` order (§34.5) forbids.

#### 14.19.11 As built — 5e-2, the indexing section (2026-10-14)

Marks as above. The flag stays off (`TENSHU_PHOTOS_ON_DEVICE_AI = NO` in `project.yml`). No
schema'd type and no wire shape moves (HR 9): every new value is in-process.

**5e-1's review carry-overs, first**
- **N1.** `ForegroundIndexing` calls the start through `startOrFailed()`, a function of its own
  that answers a throw (an `Error` included) as `StartAnswer.Failed` and logs it. A throw had
  killed the change collector or the `later` job and left the ask `Resuming`, unlogged. ⚠ The try
  is in its own function and its value returned whole: the K/N third face is still open on 2.4.20.
- **N2.** `MediaIndexRunner` raises the liveness **after** `ensureActive()`, inside the try: a pass
  cancelled before dispatch no longer goes 0 → 1 → 0. ⚠ **Shared with Android and desktop**: there
  this was a foreground-service raise for a pass that never ran; now none.
- **N3.** `@OptIn(ExperimentalCoroutinesApi::class)` on the two functions that start `ATOMIC`.
- **§14.18.16 item 14, fixed** (it was cheap): the call that keeps a `Pressed.Refused` watches the
  journal on the source's scope until the journal names the model or the refusal is gone.

**The section** (`photosIndexing`, beside `photosModelsScreen`; Swift's `IndexingSection` draws it)
- States, first that holds, as §14.19.6 lists them: no model ready (*"You can index your photos
  once the model is ready."*: nothing on iOS starts a pass by itself, so the line promises none) → the switch not read, or off (named: off *for all your devices* or *for this
  iPhone*) → paused (*Resume*) → running (*Indexing N of M*, the bar, *Keep Photos open. Indexing
  stops when you leave.*, *Pause*) → waiting (*Waiting: …* warm, Low Power Mode, battery; each with
  when it goes on, *Pause*) → stopped (left Photos, low memory; *Index now*) → not asked (*Index
  now*; the coverage line, or *"Index your photos to search them by what is in them."*, is the
  first line). *Starting…* while a press or an ask has no pass on the bar yet (an ask: *Pause*; a
  press being answered: nothing).
- **The coverage line** wherever no pass runs: the **store's own count** (`EmbeddingStore.size()`,
  only once the custody's check made the stores usable) as *can be searched*, beside
  `LastIndexCoverage`'s reach (*N of M photos on this iPhone can be searched. K aren't on this
  iPhone.*). ⚠⚠ **After an iOS update** the line is *"The index was cleared after an iOS update.
  Index now to search your photos again."* (or *…N photos can be searched again so far.*), until a
  pass has finished in this process. The custody now says why it cleared
  (`InferenceStoreCustody.clearedByOsUpdate`: a held fingerprint whose OS build differs; a realm's
  clear or a new model's sets it back).
- **Wiring.** `OnDeviceIndexSource` (`:control-shared-logic`), adapted by `ForegroundIndexSource`
  (`:control-shared-proxy`) over the graph's `ForegroundIndexing`, runner, holder, coverage, custody
  and store; every input a provider, asked on `Dispatchers.Default`. The store is counted again
  when the pass starts or ends, when the custody's word or the coverage moves, and when readiness
  moves (the launch's check) — never per photo. The pause is the same `IndexingPaused` row as
  Android's, under the same id or placeholder (`ClientIndexingPause`).
- `OnDeviceModelsCapabilities.indexing = true`; ⚠ a repository given no `OnDeviceIndexSource`
  reports `false` whatever it was given (the section is not drawn where it could only refuse).
- *Index now* answers `IndexStartOutcome.Waiting` (new) when the phone holds it back: **no
  message**; the section says why for as long as it is true.

**What changed for Android and desktop.** Nothing a user can see. In shared code:
`ModelsRepository.foregroundIndex()` / `storedIndex()` are new, defaulted to one `null`, so Control's
view model collects two flows that say nothing; `ModelsUiState.foregroundIndex` / `storedIndex` stay
`null` there. `IndexStartOutcome.Waiting` is never produced there; `ModelsViewModel.indexNow` keeps
the message before when an outcome has none (only `Waiting`). Mia's factory takes
`photoSearch = THIS_DEVICE` there and says what it said. N2 above is the one behaviour change.

**Corrections to §14.19.6 as written**
- *The pause row "as Android"* includes Android's placeholder: a pause written before the device has
  an id lands under `local-device`, which the pass does not read. Kept, not fixed (the same on both).
  ⚠ The **read** now follows the id (round 1, below).
- *Position.* The section is drawn **under** the feature row it depends on (its *"Turn on … above"*
  names that row), not between the rows and the switch.
- *`PrivacyInfo.xcprivacy` "in `project.yml`"*: a privacy manifest is not a generated plist; it is
  a committed resource, `photos-ios/Resources/PrivacyInfo.xcprivacy`, which XcodeGen copies from
  `Resources/`. Declared from the **built binary** (`nm -u`, `strings`), not from a guess: file
  timestamps `C617.1`; disk space `E174.1` + `85F4.1` (the download's check, and the free space the
  screen says); system boot time `35F9.1` (`systemUptime`: the Library's clock, and Fit's, linked
  in); `UserDefaults` `CA92.1` (Timeline's controls, linked in). ⚠⚠ The umbrella is a static
  framework, so Photos carries every app's Kotlin and declares for it. Collection and tracking:
  none (HR 1); ⚠ the App Store privacy label is the maintainer's.
- *Mia's three lines* were separable: `MiaPhotoSearchHome` (required on the factory, like
  `MiaActionOffer`); `PHOTOS_APP` on iOS names Photos → Settings → Models for `NO_BACKEND`,
  `NO_CONSENT` and `NOT_INDEXED`. Audited in the three platform proxy modules **and** `:system-test-utils`' `SystemTestPlatformModule`, the fourth caller, which round 1 missed and which stopped `:system-test-utils` and `:hub:test` compiling (`MiaActionOfferWiringTest`).

**Decided by the maintainer (2026-10-15)**
- ✅ ***Pause* is hidden when nothing runs**: shown only while a pass runs (or an ask is starting)
  or waits to go on by itself (warm, Low Power Mode, battery), where pausing stops the automatic
  resume. Never idle, after an update, stopped, not ready or switched off; next to *Index now* it
  read as a second way to start. *Resume* whenever the user's pause is on
  (`pauseIsOfferedOnlyWhileAPassRunsOrWaitsToGoOnByItself`).
- ✅ ***Resume* only lifts the pause and starts no pass**: kept as built, *Index now* sits beside it.
- ✅ The copy: the phone's waits say *Waiting*, never *Paused* (that is the user's); no auto-indexing
  promise (*"…when you tap Index now…"* in the feature's description and the offer); the idle
  section has no *"Not indexing now."* line.

**Decisions the maintainer may overrule**
- The rest of the copy (M11 placeholders), and the coverage line's two measures in one sentence
  (the store's count over the last pass's reach).
- `IndexStartOutcome.Waiting` says no message; *Indexing started* stays a message on iOS.
- The *"cleared after an iOS update"* line lasts until a pass **finished** in this process; a pass
  stopped part-way keeps it, with the count so far.

**Guards, each removed once and seen red** (fresh results; restored, the file compared after)

| Removed | Red |
|---|---|
| N1: the start through `startOrFailed` on a resume | `aResumeWhoseStartThrowsEndsTheAskAndTheDriverStillFollowsThePhone` |
| N1: the same on a press | `aPressWhoseStartThrowsAnswersFailed` |
| N2: the liveness after `ensureActive` | `aPassCancelledBeforeItWasDispatchedNeverRaisesTheLiveness` |
| item 14: the journal watched after a kept refusal | `aKeptRefusalIsDroppedOnceTheJournalNamesTheModelWhileNoScreenIsOpen` |
| the OS build as the clear's reason | `aClearIsSaidToBeAnOsUpdatesOnlyWhenTheOsBuildIsWhatChanged`, `aNewModelsClearIsNoOsUpdate` |
| a realm's clear resets it | `aRealmsClearAfterAnOsUpdateIsNoLongerSaidToBeTheUpdates` |
| the update's word ends with a finished pass | `theStoreIsCountedWhenThePassOrReadinessMovesAndTheUpdatesWordEndsWithAFinishedPass` |
| readiness as a trigger of the count | the same |
| no indexing section without a source | `aRepositoryWithNoPassSaysNoIndexingSectionWhateverItWasGiven` |
| the repository's catch around *Index now*. ⚠ **Survived the first run**: the view model's own catch answers the same; the repository is now asked directly | the same |
| `Waiting` says no message | `aPressThatWaitsForThePhoneSaysNoMessage`, `theViewModelReadsThePassThroughTheRepositoryAndEveryPressReachesIt` |
| paused before running | `pausedOffersResumeAndARunningPassShowsItsBarAndPause` |
| no second *Index now* while a press is answered. ⚠ **Survived the first run**: the case pressed over *not asked*, whose own branch says *Starting…*; it now presses over a stopped pass too | `aStoppedOrUnaskedPassOffersIndexNow` |
| the update's line before the count | `theCoverageLineSaysWhatCanBeSearchedAndWhyItIsEmptyAfterAnUpdate` |
| not read is not off | `noModelReadyAndTheSwitchOffOfferNothingAndSayWhich` |
| Mia's iOS wording | `onIosEachOfTheThreeNamesPhotosAndNeitherThisDeviceNorControl` |
| `PHOTOS_APP` in the iOS proxy module | `Mia's photo refusals name Photos on iOS, and this device on Android and desktop` |
| *(simulator)* the graph's pass handed to the repository | `theModelsScreensIndexingSectionReadsPhotosOwnPassAndStore` |

**Review round 1 (5e-2)**
- ⚠⚠ `:system-test-utils`' `SystemTestPlatformModule` calls the Mia factory too and did not pass
  the new parameter: `:system-test-utils`, `:hub:test` and the schema baselines did not compile.
  It passes `THIS_DEVICE`, and the wiring audit covers it.
- A kept refusal's journal watcher outlived a retired graph (the journal's last word is `Retired`,
  and nothing cancels the source's scope): it ends on retirement now.
- ⚠ **The pause read follows the device id** (`pausedOver` over
  `ClientModelsConsents.deviceIdsOrPlaceholder()`): resolved once at the first collect, a screen
  opened before the id was known kept reading `local-device` after a press wrote under the real
  id, and offered no *Resume*. **Shared with Android and desktop's `ClientModelsRepository`**:
  a fix there too.
- *"Indexing starts when the model is ready."* promised a start nothing on iOS makes: now *"You
  can index your photos once the model is ready."*
- No `onStart { emit(null) }` before the stored-index inputs (it flashed the update's line and
  counted twice); the indexing providers build nothing on a retired graph; Mia's internal
  `photoSearch` default removed; `CoreMlReadinessTest`'s `sessions` list is compare-and-set (two
  coroutines appended to an `ArrayList`: the 1-of-2 flake).

| Removed (round 1) | Red |
|---|---|
| `THIS_DEVICE` in `SystemTestPlatformModule` (set to `PHOTOS_APP`) | `Mia's photo refusals name Photos on iOS, and this device on Android and desktop` |
| the watcher's end on retirement | `aKeptRefusalsWatcherEndsWhenItsGraphIsRetired` (a real dispatcher) |
| the pause read following the id (pinned to the placeholder) | `thePauseReadFollowsTheDeviceIdFromThePlaceholderToTheRealOne` |

**Review round 2 (5e-2)**
- *The pause read seeded per id*: `pausedOver` takes a one-shot `readOnceForDevice` beside the
  reactive read, as the consents do, and is tested through **both** call sites
  (`ClientIndexingPause`, `ClientModelsRepository.indexingPaused()`) over a `TestProxy`'s real
  preference service (`IndexingPauseRowTest`). ⚠⚠ **The seed's own mutation survived**: over the
  real proxy an empty device-setting relation still delivers an initial empty snapshot, so the
  reactive read answers *not paused* without it (`aDeviceWithNoRowAtAllReadsNotPaused` passes
  unseeded). The case it guards — a read that emits nothing while no row exists, as
  `readOnceForDevice`'s KDoc describes — did not reproduce here; the seed is kept as the consents'
  belt, and the claim is recorded as unverified rather than proved.
- *"It starts when the model is ready."* → *"It works once the model is ready and your photos are
  indexed."*, and not said over a model this phone cannot run (its own *unsuitable* sentence
  says it never will), on the Models screen's feature row and the feature screen's own row.
- `ForegroundIndexSource` over a retired graph: *not asked*, *Index now* fails, the count not known.
- After the merge of master (5e-1's cloud-review follow-ups, per-app frameworks P0–P2): `ModelsUITests` 17 of 17 green on a fresh iOS
  26.5 simulator; all ten apps compile (`ios-smoke --build-only`; Control needed its stale
  pre-merge DerivedData removed once, a build-cache artefact of the per-app framework split).

| Removed (round 2) | Red |
|---|---|
| the per-id seed of the pause read | ⚠ **survived** (above) |
| `ClientIndexingPause.paused()` following the id (resolved once) | `photosPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown`, `photosPauseUnderTheRealIdIsShown` |
| `ClientModelsRepository.indexingPaused()` following the id (resolved once) | `controlsPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown`, `controlsPauseUnderTheRealIdIsShown` |
| no detail over an unsuitable model | `theFeatureRowIsTheConsentsAloneAndIsNotDrawnWhereNothingIsOffered` |

**On the simulator** (`iosSimulatorArm64Test`, a simulator created for the run): the section over
Photos' **own** graph — the real driver, runner and store count through the shared view model; a
warm phone's press waits with no message, a cool one starts and the empty library's pass ends the
ask (`PhotosOnDeviceAiTest`); the fixture's section, each press by its kind, and a section that moves
only when the probe does (`ModelsViewModelOnNativeTest`). **Fixture states** (`-photosModels`):
`indexNotAsked` (advances to running, then done), `indexRunning`, `indexWarm`, `indexLowPower`,
`indexBatteryLow`, `indexLeftPhotos`, `indexLowMemory`, `indexPaused`, `indexAfterUpdate`,
`indexSwitchOff`, `indexPressWaits`. `ModelsUITests` gains three cases over them (eleven states, each
its sentences and exactly its buttons; *Index now* forwarded and the section moving only with the
pass; a press the phone holds back saying no message).

**Swift, run by hand** (M10): `ModelsUITests` on an iOS 26.5 simulator made for the run, 17 cases,
16 green; the red one was `testARemovalAskedBeforeTheFeatureQuestion…`'s own timing bound, whose
clock included a cold launch (15.8 s and 10.5 s against 10 s, at load 20–300). **Fixed in review
round 1**: the case's clock starts when the Models screen is up (5 s bound), and the fixture's
self-advance runs 8 s after the screen **first reads the list**, not after construction. Green on
a fresh 26.5 simulator, with the eleven indexing states. ⚠⚠ **On an iOS 18.5 simulator every case
that presses the harness's `step` button fails** (with this slice's section removed too, so not
the section; master was not run there): the tap is
synthesized on a hittable button and lands nowhere (a probe case printed the frame, the tap, and an
unmoved row); on 26.5 the same build moves. A harness defect, not the screen's; recorded here, not
fixed (the button is 13 pt tall over the tab bar's edge).

**Only a phone can show** (beyond §14.19.9): that the count read at the launch's check is the
store's after the clear, on a real update; that the section's waits follow real thermal, Low Power
Mode and battery notifications; that a large store's `size()` is cheap enough to read at each pass
start and end; Mia's `NO_BACKEND` line as Mia's own process meets it.

---

## 15. As built — PR-7: *Index now* continues (2026-10-31)

Marks as above. **Behind the build flag, which is off** (`TENSHU_PHOTOS_ON_DEVICE_AI = NO`): with it
off Swift installs nothing, nothing registers or submits, and every press is PR-5's foreground pass.
No schema'd type and no wire shape moves (HR 9): every new value is in-process. ⚠ Written and run on
the simulator; **nothing of it has run on a phone**, and the simulator has no continued-processing
scheduler (§15.6).

### 15.1 What it does

On iOS 26+, a press of *Index now* starts the pass (as before) and then asks for a
`BGContinuedProcessingTask` for it. Accepted, the task owns the pass: leaving Photos no longer
cancels it, the system shows its progress, and it ends with the pass. Refused, the pass is PR-5's
foreground pass, which a leave cancels.

| Piece | What it does | Where |
|---|---|---|
| The terminal outcome | `MediaIndexRunner.outcomeOf(pass)`: an awaitable `PassOutcome` per started pass — `COMPLETED`, `REFUSED` (a deferred or paused report: the stores' gate, the bar before the walk or mid-library, the user's pause), `CANCELLED`, `FAILED` — completed in the pass's `finally`, after the bar is cleared and the standing is `Idle`, on every path **a throw included**. The last 8 passes are kept. `IndexProgressHolder` is unchanged (progress only) | `MediaIndexRunner.kt` (`passOutcomeOf`) |
| The bridge | `ContinuedIndexing`: offers a started pass to the platform, holds it while a task is submitted or live, mirrors the bar into the task's progress, completes the task once from the outcome (`true` on `COMPLETED` alone), stops the pass on expiry, and tells its owner when it lets go | `ContinuedIndexing.kt` (commonMain) |
| The driver's half | `ForegroundIndexing` takes a `PassContinuation` (`None` everywhere but Photos): it offers the press's pass **after** the start answered *started*; `leftForeground()` returns at once while the continuation holds the pass; `continuationReleased()` does the leave's stop if Photos is not in front | `ForegroundIndexing.kt` |
| Photos' graph | `ContinuedIndexing` bound beside the driver, over the runner, the holder and the process's scheduler; the manual bar reads the foreground as `scene || continued.holdsPass()` | `PhotosOnDeviceAi.kt` (iosMain), `PhotosIndexNow` |
| The identifier | `PhotosIndexNowTask`: `app.tenshu.photos.ios.indexnow.*` permitted, `…indexnow.<uuid>` submitted | `PhotosIndexNowTask.kt` (commonMain), `photos-ios/project.yml` |
| The Swift-facing calls | `installIndexNowContinuation`, `onIndexNowTaskAnswered`, `onIndexNowTaskLaunched`, `onIndexNowTaskExpired`; a launch no installed graph asked for is completed at once with `false` | `PhotosLocalRuntime.kt`, `IndexNowContinuationBridge.kt` |
| Swift | `IndexNowContinuation`: `#available(iOS 26, *)`; registers a handler for the composed identifier, then submits with `strategy = .fail` (the completion-handler API on iOS 27+, the throwing one on 26); sets the expiration handler before handing the task over; maps done/total onto `task.progress`; `setTaskCompleted` only when Kotlin says. Installed from `PhotosIosApp.init` with the model download, behind `modelDownloadsOffered()` | `photos-ios/Sources/IndexNowContinuation.swift` |

### 15.2 The lifecycle

1. **Press.** `indexNow` → the op → the runner starts pass *n* (`start` returns at once). The press
   is answered *started*; then `offer(n)`: if a scheduler is installed (no decline since §15.4's Q1), the
   pass has not already ended, and no task is claimed, the claim is `Submitted` and Swift is asked to
   register and submit.
2. **Answer.** A refusal (`.fail`'s `ImmediateRunIneligible`, an unpermitted identifier, a
   registration that failed) drops the claim at once. ⚠ A request answered *accepted* whose handler
   does not run within `LAUNCH_GRACE_MS` (10 s, a placeholder [I]) is dropped too: nothing may keep a
   pass running in the background with no task under it.
3. **Launch.** The claim becomes `Live`; a coroutine (started `ATOMIC`, so a teardown still runs its
   `finally`) mirrors the bar into `task.progress` and awaits `outcomeOf(n)`.
4. **Leave.** `sceneChanged(false)` → `leftForeground()`, which asks the claim's standing:
   - **Live:** it returns — no cancel, no window, the towers stay open, the ask stays `Asked`. The
     pass's per-photo bar reads the claim as the foreground, so it goes on.
   - ⚠⚠ **Submitted, not yet launched** (round 1): a background window is opened and **held
     until the claim resolves** (`holdUntilResolved`) — ended at the launch, or, on a refusal, a
     lapsed grace or an expiry, after the release has done the leave in full. Without it a late
     answer left the pass reading the store with nothing keeping Photos running, the grace timer
     never ran under suspension, and that is the `0xdead10cc` case.
   - **None:** the PR-5 leave.
5. **End.** The outcome arrives → the owner reads the pass's ending **while the claim still
   holds** (`continuationSettled`, round 1: otherwise the standing's collector and the
   release raced, and a pass done — or stopped by heat — in the background could read *stopped
   when you left*) → the claim is released → `continuationReleased()`: Photos not in
   front ⇒ the leave's stop (`beginBackgroundTask` window, cancel, close the towers) → **then**
   `task.complete(outcome == COMPLETED)`. ⚠⚠ The order is the point: the window is open before the
   task lets the app be suspended, so a pass the driver resumed meanwhile (a cleared deferral) is
   stopped inside the window, never frozen mid-read of the shared replica.
6. **Expiry** (the system, or the user stopping it from the live activity): the claim ends in one
   retried transition, submitted or live (a launch racing the expiry is never
   lost), and is released. In the background that is the leave's stop, which cancels the pass at
   its next photo; ⚠⚠ **in front nothing is cancelled** — the pass goes on as an ordinary
   foreground pass (round 1, SHOULD-FIX 2). The task is completed with `false` **at once**.
   After a release in the background only an ask whose pass still ran becomes *stopped when you
   left*; an ending already read (done, *waiting* for the heat) stays. ⚠ The expiry reads a live
   task's ending first, as the `finally` would (round 2, LOW 3).
7. **Round 2, what a held claim forbids and what the foreground owes:**
   - ⚠⚠ **No resume while a task holds the pass.** A deferral whose block has cleared is resumed
     once only when no continuation is submitted or live: under one, the bar reads the task as
     the foreground, and a resume could start a second pass in the background just as the task
     lets go and the leave's window ends. Such an ask ends `NotAsked`.
     ⚠⚠ **This is not an edge case on iOS 26** (round 3, documented, not coded): every press
     there holds a continuation, `SUBMITTED` then `LIVE`, for the whole pass, **in front too**. So
     PR-5's single resume of a cleared deferral is lost for every iOS 26 press. Example: in front,
     heat touches `.serious` for a moment, the pass defers, the block has cleared by the time the
     ending is read, and the ask goes to `NotAsked`. The bar disappears and the rest of the library
     stays unindexed until the user presses again. Below iOS 26 (and on master) it resumes once.
     *Rejected alternative:* gate on "a task holds the pass **and** the scene is not active", with
     `inForeground` injected into the driver. It keeps the resume in front, but leaves a race: if
     the user leaves between the scene read and `startPass`, `resumeClaimed`'s `cancelPass` of the
     pass it started (`ForegroundIndexing.kt`, the `answer == Started && after != Asked` branch)
     runs outside any background window, which is round 2's leave race again. Whether a brief heat
     deferral in front happens on a phone at all is 📱 (§15.6 item 9); if it does, revisit this.
   - ⚠⚠ **Not in front is "not now", never a block.** A wait kept across a release is not
     rewritten in the background (its block and its clock stay, its re-check stays armed), and the
     scene becoming active reads it again (`ForegroundIndexing.enteredForeground`, from
     `PhotosOnDeviceAi.sceneChanged(now = true)`). ⚠ That call can now **construct** the driver
     on the main thread at activation, if nothing had resolved it yet (its construction opens no
     store: the runner's standing is resolved lazily, off main).
   - The launch grace's drop is in an `ATOMIC` timer's `finally`: a teardown while submitted still
     ends the windows a leave held.

⚠⚠ **No task is left uncompleted**: every outcome completes it; a launch of an identifier the bridge
did not submit (refused meanwhile, out of time, a replaced graph's) and a launch with no installed
graph are completed at once with `false`; a graph torn down under a live task completes it with
`false`. Each task is completed once.

### 15.3 Decisions taken in this PR, each with its alternative

1. **The press starts the pass, then offers it** — rather than the handler starting it. A press
   always starts work at once by construction, a refused request leaves exactly PR-5's pass, and a
   press the op refuses (consent off, no model, the bar) never shows a system progress UI for work
   that will not run. *Rejected:* submitting first and starting from the handler: a press the op
   then refuses would leave an accepted task to complete at once with `false`, and the gap between
   submission and handler would need its own foreground pass anyway.
2. **The identifier's base is `app.tenshu.photos.ios.indexnow.*`, not the row's
   `app.tenshu.photos.indexnow.*`.** Apple's header for `BGContinuedProcessingTaskRequest`
   (iPhoneOS 27.2 SDK) says the prefix "must at least contain the bundle ID of the submitting
   application"; Photos' bundle id is `app.tenshu.photos.ios`. `PhotosIndexNowTaskDeclaredTest`
   keeps the constant, the plist entry and the bundle id in step.
3. **The claim counts as the foreground for the pass's own bar** (`scene || holdsPass()`), rather
   than a new `IndexingBlock` or a second bar. Heat, Low Power Mode and the battery still stop a
   continued pass (it then ends `REFUSED` and the task completes with `false`); a pass stopped that
   way in the background is not resumed there (the op's bar refuses once the claim is gone).
4. **One task at a time, and only for a press.** A press that joins a running pass while a task is
   claimed asks for nothing; the driver's own resumes (after heat, a cleared deferral) never submit:
   a continued task is user-initiated by Apple's rule.
5. **A leave while the task holds the pass is not counted** (`leftForegrounds` stays): a press
   answered after the leave asks for the pass the task carries, rather than cancelling it.
6. **The progress during the walk is `0 of 0`** (the holder's placeholder until the indexer knows
   the total). Whether the system reads an indeterminate progress over a long walk as a stall is 📱
   (§15.6).
7. **Copy placeholders (M11):** the request's title *"Indexing your photos"* and subtitle *"So you can
   search them by what's in them"* (`PhotosIndexNowTask`). The section's running line said
   *"Keep Photos open. Indexing stops when you leave."*, which was wrong on iOS 26 when a task was
   accepted: Q2, ✅ done (§14.18.16 item 15).
8. **No `UIBackgroundModes` entry.** Nothing in the SDK's headers ties a continued-processing request
   to a background mode [I]; if the phone's submission answers `NotPermitted` for want of one, that
   is Q3.

### 15.4 ⚠⚠ The shared store: what PR-7 does with S0b-2's protection (Q1, ✅ resolved 2026-11-05)

A continued pass reads and writes the shared replica in the background, and since
`ios-shared-store-sqlite.md` B.22 (2026-11-05) the SQLite store is on in every iOS build.

- **History.** PR-7 shipped declining (`PhotosIndexNow.install(…, declinedWhen =
  { SharedReplicaEntries.isInstalled })`): the store's only background token was an entry's
  (`protectEntry`), a `beginBackgroundTask` assertion that iOS expires after seconds whatever task
  keeps the app running, and the admission checks read `backgroundTimeRemaining`, which is not the
  task's budget. With B.22 that decline became always true: every press was PR-5's foreground pass.
- ⭐ **Built (B.23): a task's token.** `SharedReplicaLifecycle.protectTask` — an explicit token whose
  assertion **is** the running task: nothing begun on the platform; while it is live, §4.2.1's
  admission reads the task's time (no deadline until its expiry) instead of
  `backgroundTimeRemaining`; expired from the task's `expirationHandler` as B.15.3's first row
  (interrupt, 2 s drain, latch); released when the pass ends. `PhotosLocalRuntime.onIndexNowTaskLaunched`
  takes it (`SharedReplicaEntries.protectTask` → `SharedReplicaTaskProtection`) before the claim
  goes live, and ⚠⚠ **the token ends before `setTaskCompleted`**, in both paths: the completion
  `ContinuedIndexing` makes is wrapped so the token is released (or, after the expiry handler,
  expired) first. The decline is gone.
- **PR-6** (§16) takes the same token in its `BGProcessingTask` handler, before the wake does anything.
- 📱 owed: B.23.5 (progress continues in the background; no `0xdead10cc` after an expiry; ⚠⚠ and, a hard gate, how long iOS lets the `expirationHandler` block: under a task the 2 s drain is the whole margin, B.23 (441)).

### 15.5 Guards, each removed once and seen red (JVM, fresh results; restored, the file compared after)

| Removed | Red |
|---|---|
| the outcome completed in the runner's `finally` | `everyPathEndsInItsTerminalOutcome`, `anExpiredContinuedTaskStopsThePassAtTheNextPhoto`, `aContinuedPassThatRunsToTheEndCompletesItsTaskWithSuccess` |
| a throw's outcome (`FAILED` read as `COMPLETED`) | `everyPathEndsInItsTerminalOutcome`, `theTerminalOutcomeOfEachEnding` |
| the leave returning while the task holds the pass | `withALiveContinuedTaskLeavingDoesNotCancelAndWithoutOneItStillDoes` and the four continued passes over the real runner |
| a refusal dropping the claim | `aRefusedSubmissionLetsGoAtOnceAndItsLateLaunchIsCompletedWithFailure`, `aRejectedSubmissionStartsTheForegroundPassAtOnceAndALeaveStillCancelsIt` |
| the expiry (made a no-op) | `anExpiryStopsThePassAndTheTaskCompletesWithFailureOnceItHasEnded`, `anExpiredContinuedTaskStopsThePassAtTheNextPhoto` |
| the expiry's release and cancel | the same two |
| a launch nobody asked for completed | `noTaskIsLeftUncompletedNorCompletedTwice`, `aRefusedSubmission…LateLaunch…`, `anAcceptedRequestWhoseHandlerNeverRunsLetsGoAfterTheGrace` |
| the progress mirror | `theTasksProgressFollowsTheHolderAndAFinishedPassCompletesItWithSuccess`, `anExpiredContinuedTaskStopsThePassAtTheNextPhoto` |
| success only on `COMPLETED` (made always `true`) | `aThrowingARefusedAndACancelledPassEachCompleteTheTaskWithFailure`, both expiry cases, `noTaskIsLeftUncompleted…` |
| released **before** completed (swapped) | `aContinuedPassThatEndsInTheBackgroundIsStoppedBeforeItsTaskCompletes`, `theTasksProgressFollows…` |
| the launch grace | `anAcceptedRequestWhoseHandlerNeverRunsLetsGoAfterTheGrace` |
| the offer after the start (moved before it) | `withALiveContinuedTaskLeavingDoesNotCancelAndWithoutOneItStillDoes` |
| the offer (removed) | six cases, the rejected submission's among them |
| `continuationReleased`'s stop in the background | `aContinuedPassThatEndsInTheBackgroundIsStoppedBeforeItsTaskCompletes`, `withALiveContinuedTask…` |
| an ended pass not offered | `aPassThatAlreadyEndedIsNotOfferedAndOneTaskAtATime` |
| *(simulator, Photos' own graph)* the bar counting the claim as the foreground | `aContinuedPassGoesOnThroughALeaveInPhotosOwnGraph` |
| *(simulator)* the driver given the graph's `ContinuedIndexing` | the same |
| *(simulator)* a launch with no runtime completed at once | `aContinuedTaskNoGraphAskedForIsCompletedAtOnceWithFailure` |
| ⚠ the once-only guard on `complete` (`OnceTask`) | **survived**: no path reaches a second completion (every completion is the launch's `finally` or the stranger's at once). Kept as a belt; recorded as unproved |

**Review round 1 (PR-7)** — removed once each, seen red (JVM, fresh results):

| Removed | Red |
|---|---|
| the claim's transition retried (one attempt only) | `anExpiryThatRacesTheLaunchIsNotLost` |
| the leave's held window while submitted (made a return) | `aLeaveWhileATaskIsOnlySubmittedHoldsAWindowUntilItIsLaunched`, `…IsDoneInFullWhenTheRequestIsRefused` |
| the held windows ended at the launch | `aWindowHeldWhileSubmittedEndsAtTheLaunchOrAfterTheRelease`, `aLeaveWhileATaskIsOnlySubmittedHoldsAWindowUntilItIsLaunched` |
| the held windows ended after a drop | `aWindowHeldWhileSubmittedEnds…`, `…IsDoneInFullWhenTheRequestIsRefused` |
| the release reading the foreground (stops in front too) | `anExpiryInFrontLeavesThePassRunningAndCompletesTheTaskOnceWithFailure` |
| the expiry completing the task at once | the same, `anExpiryReleasesThePassAndCompletesTheTaskWithFailureAtOnceAndOnce`, `anExpiryThatRacesTheLaunchIsNotLost` |
| the once-only guard (`OnceTask`) | ⭐ **now red** (an expiry completes, then the outcome would): `anExpiryReleasesThePass…AtOnceAndOnce`, `anExpiryInFront…` |
| the ending settled while the claim holds | `aContinuedPassEndingInTheBackgroundKeepsItsEndingInEitherOrder` |
| a wait kept after a release (overwritten) | the same (its heat cases) |

**Review round 2 (PR-7)** — removed once each, seen red (JVM, fresh results; restored, compared):

| Removed | Red |
|---|---|
| no resume while a continuation holds the pass | `aDeferralWithNoBlockEndingUnderAContinuedTaskStartsNoSecondPass` (both scopes queued) |
| *not in front* left as it is in `resumeIfClear` | `aWaitKeptAcrossTheReleaseSurvivesTheBackgroundAndResumesInFront` |
| `enteredForeground`'s re-check | the same |
| *(simulator)* `sceneChanged(now = true)` calling it | `theSceneBecomingActiveReadsAWaitingAskAgain` (⚠ survived first: the wait's own 1 ms re-check resumed it; the case now waits past it) |
| the release keeping a wait's re-check (`!(afterRelease && Waiting)`) | `aWaitKeptAcrossTheReleaseKeepsItsReCheck` (it survived round 1) |
| the expiry's settle | `anExpiryAfterThePassEndedKeepsItsEnding` (bridge and driver both queued), `anExpiryReleasesThePass…` |
| the grace's drop in `finally` | `aTeardownWhileSubmittedEndsTheHeldWindow` |
| the window ended when the claim resolved between the read and the hold | `aClaimThatResolvesAsTheLeaveHoldsItEndsTheWindowAndLeaves` |

Also new: `aRefusalAfterReturningToTheForegroundEndsTheHeldWindowAndStopsNothing`.

### 15.6 Not proved, and only a phone can show (📱, iOS 26)

1. Leave Photos mid-pass: the pass goes on, and the system shows its progress (title, subtitle,
   done of total).
2. A request refused (`.fail`; hard to provoke on purpose — Low Power Mode or a second app's task may
   do it): the press still started the pass at once, and leaving cancels it as in B6 item 5.
3. Expiry: stop the task from the system's live activity mid-pass. **Pass:** the pass stops at its
   next photo, the towers close, and the next press continues from the store.
4. A pass through a long library walk (progress `0 of 0`): not expired as stalled (decision 6).
   ⚠ PR-7's review round 1 asked for a heartbeat during the walk; **not built**: the walk is one suspend
   call (`MediaSource.walk`) with no count to report, and the holder's `StateFlow` drops an equal
   `0 of 0`, so a heartbeat would be invented progress. If the phone shows a stall expiry here, the
   fix is a walk that reports how many folders it has read.
5. Heat, Low Power Mode or a low battery during a continued pass in the background: it stops, the
   task ends, and nothing starts again until Photos is in front.
6. Whether `setTaskCompleted(success: false)` after a heat stop reads to the user as a failure.
7. Whether the submission needs a background mode (Q3), and what `ImmediateRunIneligible` looks like
   in practice.
8. A swipe-away from the app switcher with a task live.
9. In front on iOS 26, whether a brief heat deferral happens at all: heat touching `.serious`
   and clearing before the pass's ending is read. If it does, the pass ends `NotAsked` with photos
   left (§15.2 step 7), and the rejected alternative there is to be revisited.

### 15.7 Open questions

- **Q1** — ✅ **resolved 2026-11-05** (`ios-shared-store-sqlite.md` B.23, §15.4): the task's token
  is built and PR-7 takes it. Until then PR-7 declined, on every build once B.22 turned the store on.
- **Q2** — ✅ **resolved 2026-11-09** (§14.18.16 item 15). The indexing section's *"Keep Photos open.
  Indexing stops when you leave."* was wrong while a task is live. Built as recommended: the line
  follows the claim through `OnDeviceIndexSource.continued`, and says *"You can leave Photos.
  Indexing goes on, and iOS shows how far it has got."* only while a **launched** task carries the
  pass — submitted-not-launched and refused keep the old line, because a leave then still stops it.
  No Swift change: Swift draws `PhotosModelsScreen`'s strings.
- **Q3** — whether `UIBackgroundModes` needs an entry. ⭐ Recommendation: none until the phone says
  `NotPermitted`; then `processing`, which PR-6 adds anyway. ⭐ PR-6 has added it (§16), so PR-7's 📱
  pass now runs with it declared and cannot answer this question by itself.
- **Q4** — `LAUNCH_GRACE_MS` (10 s) is a placeholder. ⭐ Recommendation: keep until the phone shows how
  long a launch takes after `submit`; it only bounds a launch that never comes.

---

## 16. As built — PR-6: the overnight processing wake (2026-11-11)

Marks as above. **Behind the build flag, which is off** (`TENSHU_PHOTOS_ON_DEVICE_AI = NO`); faces ride
the same pass behind `TENSHU_PHOTOS_FACES`, as for every pass. With the flag off the handler is still
registered (it must be, at launch) but nothing is ever submitted, a wake that comes anyway is completed
at once, and a launch withdraws any request an enabled build left. No schema'd type and no wire shape
moves (HR 9): every new value is in-process. ⚠ Written and run on the simulator, which never launches a
`BGProcessingTask` and refuses every submission; **nothing of it has run on a phone** (§16.6).

### 16.1 What it does

When Photos leaves the foreground with something asked of it and photos left, it submits a
`BGProcessingTaskRequest` for `app.tenshu.photos.processing` (`requiresExternalPower = true`,
`requiresNetworkConnectivity = false`). When iOS grants it (charging and idle), Photos' runtime takes the
shared store's task token, re-arms the request, and runs the runner's **scheduled** pass, which the
scheduled bar allows only inside the wake. The task is completed once, from the pass's terminal outcome.

| Piece | What it does | Where |
|---|---|---|
| The scheduled bar | `ForegroundIndexingConditions.allowIndexing()` = inside a wake **and** `scheduledIndexingBlock` (not `.serious`, not Low Power Mode, not off the charger; an unknown charging state is the grant's word). ⚠ Outside a wake it is `false`, as before | `ForegroundIndexing.kt` (commonMain) |
| The bridge | `ProcessingWake`: arms on a leave (`leftForeground`), carries a launched wake (`launched`: prepare, ask, re-arm, pass, withdraw when current, release, complete once), stops it on `expired`, and answers `carriesPass()` for the bar and the leave | `ProcessingWake.kt` (commonMain) |
| What is asked | `PhotosOnDeviceAi.wakeWork`: not paused, and a half of `MEDIA_INDEX_CAPABILITIES` both permitted (two switches, absence is NO) and served; then `workLeft` over the runner's standing | `PhotosOnDeviceAi.kt` (iosMain) |
| The pass | `runWakePassOver` (via `PhotosOnDeviceAi.runWakePass`): a pass the **user** started running now is left alone (`REFUSED`, no join); otherwise `MediaIndexRunner.runToCompletion(manual = false)` → `wakeOutcomeOf`. The expiry's stop is `cancelRunningScheduledPass`, which never ends a manual pass. ⚠ **Never the `MediaIndexPass` op**, which on iOS is the old schedule row's retirement (§13.4 item 8) | `PhotosOnDeviceAi.kt` |
| The driver's half | `ForegroundIndexing`'s `wakeCarriesPass`: a leave inside a wake cancels nothing and closes nothing | `ForegroundIndexing.kt` |
| The runtime | `installProcessingWake`, `runProcessingWake(wakeId, task)`, `onProcessingWakeExpired(wakeId)`, `processingWakeIdentifier()`; the token; the journal | `PhotosLocalRuntime.kt`, `ProcessingWakeBridge.kt`, `ProcessingWakeJournal.kt` |
| The identifier | `PhotosProcessingWakeTask`: identifier and the two requirements, kept in step with `project.yml` by `PhotosProcessingWakeTaskDeclaredTest` | `PhotosProcessingWakeTask.kt` (control-shared-proxy commonMain) |
| Swift | `ProcessingWakeDriver`: registers in `PhotosIosApp.init` (every build), sets the `expirationHandler` first, installs the runtime on the main thread, hands the task to Kotlin off main; submits off main with Kotlin's requirements; withdraws | `photos-ios/Sources/ProcessingWakeDriver.swift` |
| The plist | `UIBackgroundModes: [processing]`, and `app.tenshu.photos.processing` beside PR-7's wildcard | `photos-ios/project.yml` |

### 16.2 The lifecycle

1. **Leave.** `sceneChanged(false)` → the driver's leave (as before) and `ProcessingWake.leftForeground()`:
   with a scheduler installed and no wake running, inside a background window and off the main thread,
   `wakeWork` is read; `LEFT` submits.
2. **Launch.** Swift's handler completes a wake at once in a build that offers no model (and
   withdraws the request), installing nothing. Otherwise it calls `onProcessingWakeBegan` (the wake's
   place, so an early expiry is kept and a late one leaves nothing), sets the `expirationHandler`, then
   on main installs the runtime if provisioned (a background launch builds no view, so nothing else
   would), then off main calls `runProcessingWake`. Kotlin completes at once with `false` if no admitted graph is installed, if the
   wake already expired, or if no token can be had. Otherwise it takes
   `SharedReplicaEntries.protectTask("photos-processing-wake")` and `begin()`s it (B.23.3), journals
   `launched`, and hands the wake to `ProcessingWake.launched` with `prepare` = the launch's store check
   (`Assembly.checking`: until it ends, no backend serves and nothing would be asked; journalled
   `checked ms=…`). ⚠⚠ The wake's entry holds its `ProcessingWake`, and the entry's `expired` is read
   **again after the launch**: an expiry between the token and the launch (the journal's write) found
   no wake current and ended the token, so without that read the wake ran past its own expiry, parked
   at the closed gate, and was never completed (fix round 1, HIGH).
3. **Ask, re-arm, pass.** `NOT_ASKED` completes with `true` and re-arms nothing. `NONE_LEFT` withdraws and
   completes with `true`. `LEFT` **submits first** (a grant is one-shot, and this wake may be expired or
   killed), then runs the pass.
4. **End.** The outcome is read; `COMPLETED` withdraws the request (the library is current). The wake
   lets go (`carriesPass()` false), `released()` is told — Photos not in front ⇒ the leave's stop: the
   pass cancelled (it has ended already) and **both models closed**, inside a background window — and
   then the task: the token ends (`finish`), **then** `setTaskCompleted(success)`.
5. **Expiry.** `onProcessingWakeExpired`: `hold.expire()`; `ProcessingWake.expired()` — the bar says no at
   once, the wake's job is cancelled, which cancels the runner's scheduled pass at its next photo; then
   `hold.endToken()` (B.15.3's interrupt and its 2 s drain) before the handler returns. The wake's own
   `finally` waits for the pass to stop, at most `EXPIRY_STOP_MS` (3 s), then completes with `false`; a
   backstop 1 s later completes it if the wake's coroutine is held where its cancel cannot reach (a thread
   parked at the store's closed gate). Each task is completed once (`OnceWakeTask`).

### 16.3 Decisions taken in this PR, each with its alternative

1. **No `deadline` parameter** (the row wrote `runProcessingWake(deadline)`). A `BGProcessingTask` states
   no remaining time; it runs until iOS calls the `expirationHandler`, and the store already reads a task
   as "no deadline" (B.23 (441)). The entry takes a wake id instead, which pairs an expiry that comes
   before the run with its run. *Rejected:* a fixed budget, a number nothing on the platform states.
2. **The identifier stays `app.tenshu.photos.processing`.** PR-7's bundle-id rule is the
   continued-processing request's own header; a processing task's identifier only has to be listed
   (Drive's is `app.tenshu.drive.refresh` under bundle `app.tenshu.drive.ios.smoke`) [D: the SDK's
   headers name no prefix rule for `BGProcessingTaskRequest`].
3. **No chunking; the heat stop is the backoff** (the row said "PR-5's thermal backoff and chunking"):
   chunking was decided against on 2026-10-23 (§14.19.8). Inside a wake, `.serious` makes the bar say no;
   the pass stops at its next photo (`REFUSED`) and the wake ends with `false`, its request still armed
   for the next window. *Rejected:* resting inside the wake and starting again — a wake is the OS's
   window, and waiting in it for the phone to cool spends it.
4. ⚠ **"Photos left" is unknown unless this runner's last pass was a library walk that ran to its end**
   (`workLeft`, `IndexPassResult.libraryWalk`; a PR-6b batch over arrived paths never counts). A completed
   pass withdraws the request it re-armed, and the next leave arms nothing. So **arrivals after a
   completed pass wait** for the next foreground's PR-6b batch, a press, or a pass that does not
   complete. A fresh process does not know, so it arms once. *Rejected:* re-arming whenever something is
   asked (a library walk every night for ever); counting unindexed photos at each leave (a whole library
   walk at every leave, on the main thread's way out).
5. **`true` on `COMPLETED`, and when nothing was asked or left; `false` otherwise** — a refusal (heat, the
   charger gone) included, as PR-7. Whether a `false` changes how often iOS grants the wake is 📱 (§16.6).
6. **A press during a wake supersedes it.** The runner's rule (a manual start replaces a scheduled pass)
   is unchanged: the wake's pass ends `CANCELLED`, the wake completes with `false`, and the press's pass
   is a foreground pass from then on. ~~PR-6b's trigger, which goes through the runner as a press does,
   inherits this.~~ PR-6b's batch is the runner's automatic kind, not a press: it yields to a wake, and
   a wake replaces it (§17.3 decision 2).
7. **Registered in every build, submitted behind the flag.** A `BGProcessingTask` handler must be
   registered before launch finishes, and a request an enabled build armed can still launch a disabled
   one; the disabled launch withdraws the request, and a wake that comes anyway is completed at once.
8. **Swift installs the runtime on the main thread** before Kotlin is called: `PhotosRootView.adopt()` is
   the other writer of the installed graph, and two installs racing are two graphs.
9. **The expiry waits for the stop, bounded** (3 s, then a 1 s backstop), and stops only a **scheduled**
   pass (`cancelRunningScheduledPass`): a wake that meets a pass the user started (iOS 26's continued
   *Index now*, under its own task) does not join it, and its expiry never ends it (fix round 1). PR-7 completes at once on an
   expiry; the row asked for the completion "after the cancel has stopped the pass". The bound is a
   placeholder [I] (§16.7 Q1).
10. **What is asked is the op's check minus the manual bar**: not paused, and a half both permitted and
    served (`permittedInference` ∩ `MEDIA_INDEX_CAPABILITIES`, the list now shared with
    `StartMediaIndexPass`). Per half (IN-D3): faces alone, switched on, is enough.
11. **A journal on disk** (`ProcessingWakeJournal`, `photos-private/processing-wakes.log`): stdout goes
    nowhere overnight, and the three nights need wakes, minutes and photos (§16.6).

12. ⚠ **The runtime is installed before the token** (fix round 1, item 3 — not fixable from here, and
    owed to the phone). `FirstPartyLocalRuntime.assembleBase` installs the SQLite binding and resolves
    the graph's `ObjectStore` on the main thread, so the store may be opened there before any task
    token exists — the token needs that binding (`SharedReplicaEntries.protectTask` is a pass-through
    without one). With the gate closed in the background, the main thread's admission takes an
    **implicit token** (`ReplicaProtection.awaitAdmission` → `protect()`, a `beginBackgroundTask`
    assertion), which a process iOS launched for a task should be granted [I]; only if it is refused
    does main sleep `MAIN_THREAD_RETRY_MS` and ask again. Drive's refresh wake installs the same way
    (`DriveLocalRuntime.runBackgroundRefresh`). *Rejected:* installing off main — `PhotosRootView.adopt()`
    installs on main too, and two installs racing are two graphs. 📱 §16.6 item 7 reads it: the
    journal's `launched` → `checked ms=…` gap, and no `[gate]` main-thread retry lines.
13. **The journal says what the submission got**, from Swift's own answer (`armed`, or `submit refused:
    …` — Background App Refresh off, the simulator), never from the request being asked for.

### 16.4 The shared store

The handler takes the task token exactly as PR-7's launch (B.23.3): before the wake does anything,
never an entry's `withProtection` (a `beginBackgroundTask` iOS ends within seconds), ended inside the
completion before `setTaskCompleted`, and expired from the `expirationHandler` with `endToken` as the
backstop before it returns. ⚠ The wake's completion after an expiry may come after the handler returned
(decision 9); the token has ended by then whichever order, because `endToken` returns only once it has.

### 16.5 Guards, each removed once and seen red

JVM (`ProcessingWakeTest`, fresh results with `--rerun`; restored from a copy, the file compared after):

| Removed | Red |
|---|---|
| the scheduled bar reading the wake (`inWake() &&`) | `noWakeMeansTheScheduledBarSaysNo`, `withNoWakeTheScheduledPassWalksNothing` |
| the charger in the scheduled bar (`charging == false`) | `noWakeMeansTheScheduledBarSaysNo` |
| the re-arm before the pass (moved after it) | `theWakeReArmsBeforeItWorksAndACompletedPassWithdrawsTheRequest` |
| no re-arm with nothing asked (a submit added there) | `aWakeWithNothingAskedRunsNoPassAndIsNotReArmed` |
| the leave's arm asking what is asked (`if (true)`) | `aLeaveArmsTheWakeOnlyWithSomethingAskedAndPhotosLeft`, `aWorkReadThatThrowsArmsNothing` |
| the once-only guard (`OnceWakeTask`) | `anExpiryRacingAFinishCompletesTheTaskOnce` and four expiry cases |
| the expiry waiting for the stop | `anExpiryCompletesTheTaskWithFailureOnlyOnceThePassHasStopped` |
| the backstop | `anExpiredWakeHeldWhereTheCancelCannotReachIsCompletedByTheBackstop` |
| the expiry's cancel | `anExpiryCompletesTheTaskWithFailureOnlyOnceThePassHasStopped`, `anExpiryMidPassStopsAtTheNextPhotoAndTheNextWakeResumesFromTheStore` |
| `carriesPass` reading the expiry | ⚠ survived first (Unconfined ended the wake before the read); the case now holds the pass past the cancel: `theWakeCarriesThePassWhileItRunsAndNotFromItsExpiry` |
| the driver's leave guard (`wakeCarriesPass`) | `aLeaveInsideAWakeCancelsNothingAndOutsideOneItStillDoes` |
| the withdrawal after a completed pass | `eachOutcomeCompletesTheTaskOnce`, `theWakeReArms…AndACompletedPassWithdrawsTheRequest` |
| success only on `COMPLETED` (always `true`) | `aFailingPassCompletesTheTaskOnceWithFailure`, `eachOutcomeCompletesTheTaskOnce` |
| `released()` told at the end | the three no-pass / completed cases |
| the catch that keeps a throw in the coroutine | ⚠ survived first (an inner catch duplicated it, now removed); the case now asserts the scope is not failed: `aFailingPassCompletesTheTaskOnceWithFailure` |
| an expiry before the job was known cancelling it once known | ⚠ survived first (the backstop hid it); new: `anExpiryBeforeTheJobIsKnownStillStopsTheWakeAtOnce` |

Desktop (`PhotosProcessingWakeTaskDeclaredTest`): the identifier out of `project.yml` → `theIdentifierIsPermittedBesideIndexNowsWildcard`; `processing` → `fetch` → `theProcessingBackgroundModeIsDeclared`; `REQUIRES_EXTERNAL_POWER = false` → `theRequestAsksForExternalPowerAndNoNetwork`.

Simulator (`PhotosOnDeviceAiTest`, Photos' own graph and the Swift-facing calls; one mutation per run, restored after):

| Removed | Red |
|---|---|
| the wake taking the store's token (`hold.begin()`) | `aWakeIndexesInPhotosOwnGraphUnderTheTasksTokenAndLetsGoAfter`, `anExpiredWakeExpiresTheTokenStopsThePassAndIsCompletedOnce`, `withNoConsentAWakeDoesNothingAndNothingIsArmed` |
| the expiry marking the token (`hold.expire()`) | `anExpiredWakeExpiresTheTokenStopsThePassAndIsCompletedOnce` |
| the scheduled bar wired to the wake in Photos' graph | `aWakeIndexes…`, `anExpiredWake…` |
| the arm on leaving the foreground (`sceneChanged`) | `aWakeIndexes…` |
| the consent read (`permittedInference`) | `withNoConsentAWakeDoesNothingAndNothingIsArmed` |
| the driver's leave told of the wake | `aWakeIndexes…` |
| ⚠ the entry's check for an expiry before the run (`expiredFirst`) | **survived**: the second read, after the token is registered (`expiredMeanwhile`), catches the same expiry. Kept as a belt; recorded as unproved |

**Fix round 1 (the independent review)** — removed once each, seen red (fresh results):

| Removed | Red |
|---|---|
| *(simulator)* the entry's `expired` read again after the launch | `anExpiryJustBeforeTheLaunchIsNotLost` |
| *(simulator)* an expiry finding the entry only (made `getOrPut` again) | `aWakeWithNoRuntimeOrAlreadyExpiredIsCompletedAtOnceWithFailure` (a place left behind), `anExpiryJustBeforeTheLaunchIsNotLost` |
| a pass the user started left alone by the wake (`runWakePassOver`'s check) | `aWakeLeavesAPassTheUserStartedAlone` |
| the expiry's stop for a scheduled pass only (`cancelRunningScheduledPass`'s `!manual`) | `anExpiryNeverStopsAPassTheUserStarted` |

### 16.6 Not proved, and only a phone can show (📱)

The steps are [`how-to/photos-processing-wake.md`](../how-to/photos-processing-wake.md).

1. The debugger's `_simulateLaunchForTaskWithIdentifier:` runs the wake: launched, re-armed, a pass,
   ended `COMPLETED`, no `0xdead10cc`. ⚠ A forced launch runs without a submitted request: it proves the
   handler, not the arming.
2. `_simulateExpirationForTaskWithIdentifier:` mid-pass: the pass stops at its next photo, the token's
   expiry interrupt is logged, the task completes with `false` within ~3 s, once; the next *Index now*
   continues from the store. ⚠ How long the `expirationHandler` may block is B.23.5 item 3's hard gate:
   Kotlin blocks it for the store's drain (up to ~2 s). Which thread iOS calls a `BGTask`'s
   `expirationHandler` on is not documented [I]; the store's end moves a release off the main thread
   (`SharedReplicaTaskProtection.finish`), an expiry is ended on whatever thread calls it.
3. **Three real nights**, from the journal:

   | Night | Wakes granted | Minutes per wake | Photos per wake | How each ended | Armed again |
   |---|---|---|---|---|---|
   | 1 | — | — | — | — | — |
   | 2 | — | — | — | — | — |
   | 3 | — | — | — | — | — |

4. Whether heat ends wakes on the charger overnight (`outcome=REFUSED`), and whether a `false` completion
   makes iOS grant fewer windows.
5. Faces in a wake (`TENSHU_PHOTOS_FACES=YES`): People refreshes after a night (the runner's
   `faceChanges` moves for a wake's pass like any pass).
6. App Review accepts the `processing` mode for "index photos on device while charging".
7. A cold background launch's install before the token (decision 12): the journal's `launched` →
   `checked ms=…` gap stays short, and the device log has no `[gate]` main-thread retries around it.

### 16.7 Open questions

- **Q1** — `EXPIRY_STOP_MS` (3 s) and its 1 s backstop are placeholders. ⭐ Recommendation: keep until
  §16.6 item 2 shows how long the stop takes after an expiry; lower it if iOS allows less.
- **Q2** — decision 4's gap: arrivals after a completed pass, with Photos never opened, wait. ⭐
  Recommendation: leave it to PR-6b; if the nights show phones that never open Photos, arm the wake from
  the library watch's revision moving (no walk). ⭐ PR-6b is built (§17): arrivals now wait only for Photos' next foreground.
- **Q3** — Background App Refresh off makes iOS refuse every wake, silently to the user. ⭐
  Recommendation: none until the nights show it matters; then a line in the indexing section.

---

## 17. As built — PR-6b: new arrivals while Photos is in front (2026-11-11)

Marks as above. **Behind the build flag, which is off** (`TENSHU_PHOTOS_ON_DEVICE_AI = NO`); faces ride the
batch behind `TENSHU_PHOTOS_FACES`, as for every pass. With the flag off nothing is bound
(`assertNothingIsBound` checks it) and the scene hooks reach no graph. No schema'd type and no wire shape
moves (HR 9): every new value is in-process, and the start operation is not touched (an optional parameter
there would be a reshape). The decision is `inference-service.md` IN-D6a; Android's half is its own change
in Control. ⚠ Written and run on the JVM and the simulator; **nothing of it has run on a phone** (§17.6).

### 17.1 What it does

When the library watch moves while Photos is in front — or Photos comes to the front with a look owed —
a short debounce later Photos' runtime reads **which photographs arrived** from the newest rows of the file
service's modification log into the device's **arrivals queue**, and runs **one capped batch over the
queued paths alone**: no tree walk, no whole-library reconcile. At most `NEW_ARRIVALS_BATCH_CAP` (50)
photos are processed; what the batch did not reach stays queued. Consent and the model are checked by the runner before anything is read; the bar
is *Index now*'s. Leaving the foreground stops it.

| Piece | What it does | Where |
|---|---|---|
| Which arrived | `ArrivedPhotoPaths.read`: the log's member hashes (no rows), then only the rows **past the persisted cursor** (`ArrivalsLogCursor`: the hashes of the last 32 rows read and the newest one's index, at `/inference/arrivals.cursor` beside the queue), at most `ARRIVALS_LOG_WINDOW` (500) of the newest; a `Created`, `Moved` or `Renamed` modification for an image outside the trash (`isArrivingImagePath`, Android's test). ⚠ Reading resumes past the **newest remembered row still there** — a move re-keys a file's history (old rows reaped from the middle, new ones appended) and a trim drops a file's oldest, so the very last row may be gone. ⚠⚠ None of its rows left, or no cursor (another realm, a long gap, a first look): the end becomes the cursor, **nothing is queued**, the look reports `missed`. The survivor further down than it was (a peer's rows merged ahead of it) or a burst past the window: the new rows are read and the look reports `missed`. `markEndIfUnmarked` (hashes only) sets a first cursor when no half may run. ⚠ Not Android's `NewArrivalsObserver`: on iOS the save ran in another app's process over the shared store, so Photos cannot observe it | `ArrivedPhotoPaths.kt` (control-shared-proxy commonMain) |
| The queue | `ArrivalQueue` (the common `ProxyModule`'s single, over the graph's inference `FileDatabase` — on iOS Photos' private store, so it outlives the process): de-duplicated, arrival order, at most 500 (oldest out first); `settle` drops done paths, keeps unreached ones, and drops an unavailable one (no bytes, a half that failed or was latched off, or no live row yet) after its third batch; `clear` with the realm's other derived stores (`wipeDerivedInferenceState` from `onLocalStoreWiped`, and `InferenceStoreCustody`'s realm change, relinquish and `eraseAtRest` of `INFERENCE_ARRIVALS_PATH`). ⚠ Flushing is the caller's: on iOS every change is flushed when made (the look, then the batch's settle), so a leave never loses one | `ArrivalQueue.kt` (commonMain, Android's half) |
| The batch | `MediaIndexRunner.runArrivalsBatch(context, queue)` → `runPathsBatch(context, paths, cap)`: `null` if any pass runs (never joined, never replacing); nothing read when no half may run (`MediaIndexer.anyHalfMayRun`: not paused, a half's two switches on and its backend serving — absence is NO) or the arrivals bar says no; ⚠⚠ **each refusal deletes what a consent withdrawal owes** (`withdrawalDeleted`, which also moves `faceChanges`); otherwise one pass over `MediaSource.mediaAt(paths)` (a partial list, so no reconcile) reporting each path's `PathOutcome` (done, or `UNAVAILABLE` — a missing path too, retried; none = not reached), with `libraryWalk = false`, so neither the Models screen's coverage nor `workLeft` reads it as the library. Automatic (`manual = false`): a press or a wake replaces it, and a wake's expiry (`cancelRunningScheduledPass`) spares it — on iOS it never runs inside a wake anyway, since the leave cancels it | `MediaIndexRunner.kt` (commonMain) |
| The cap | ⚠⚠ Counts photos **processed** — bytes or a preview in hand — never one already current, never one not on this device | `MediaIndexer.kt` |
| By path | `MediaSource.mediaAt`; Control's source answers the live images among the paths with one chunked point read (`fileLivePaths`). ⚠ Its default walks and filters — correct, and the cost a batch exists to avoid; every production source overrides it | `MediaIndexPassImplementation.kt`, `MediaLibrarySeams.kt` |
| The bar | `ForegroundIndexingConditions.allowArrivalIndexing()` = `arrivalIndexingAllowed`: the manual bar (in front, not `.serious`, not Low Power Mode, battery not low) **and a known battery** | `ForegroundIndexing.kt` |
| The trigger | `ForegroundArrivals`: one event queue; a debounce restarted by every change; a look only in front, and no pass when the queue is empty; a leave cancels the waiting and the running batch; a capped batch ends batching for that foreground; a busy runner is waited out once, on its `Idle` | `ForegroundArrivals.kt` (inference-service-proxy commonMain, iOS-only in use) |
| Photos' graph | `ArrivedPhotoPaths` (its cursor in the inference store, erased at rest with the queue), `ArrivalsMissed`, and `ForegroundArrivals` bound beside the driver on the indexing scope (cancelled with the graph); `lookForArrivals` (the log into the queue, flushed) and `runArrivalsBatch` over the graph's session, queue and runner, with a `[photos-arrivals]` line; `sceneChanged` tells it both edges; `workLeft` reads any batch (capped or not), and any `missed` look not yet followed by a walk, as photos left; `arrivalsMayRun` (`MediaIndexer.anyHalfMayRun`) asked before the look; the batch holds an `AppBackgroundWindow` | `PhotosOnDeviceAi.kt` (iosMain) |
| The watch | `PhotosLocalRuntime.install` hands the repository's `LibraryWatch` to it: `revision` (its first value dropped) as the cue, `watching` as whether it vouches, and the latest store check (`Assembly.checking`) awaited before a batch asks anything | `PhotosLocalRuntime.kt` |

No Swift change: the scene phase already reaches Kotlin (`modelTransfersSettleNow` → `sceneChanged`).

⚠ **Shared with Android's half of IN-D6a**, built at the same time over the same runner: the whole
commonMain batch API — `runPathsBatch`, `PathsBatch`/`PathOutcome`, `ArrivalQueue` and its
`runArrivalsBatch`, `MediaSource.mediaAt`, `PassBar.ARRIVALS`, `allowArrivalIndexing`, the cap counted
after the bytes, the refusals' withdrawal deletion — is that change's, carried here byte for byte from
its branch (both reviews asked for the same fix). This PR adds only the iOS pieces above it:
`ArrivedPhotoPaths`, `ForegroundArrivals`, `arrivalIndexingAllowed` and Photos' wiring. Whichever lands
second reconciles the two.

### 17.2 The lifecycle

1. **Install.** `follow`: a fresh process **owes one look** (it cannot know what arrived while it was not
   running), so the first foreground takes one — over the rows past the cursor the last process left,
   never the log's history. The first look on a device (no cursor) queues nothing and leaves what came
   before to the wake.
2. **A change.** Every move of the watch's revision owes a look and, in front, restarts the debounce
   (`ARRIVALS_DEBOUNCE_MS`, 3 s). Behind Photos it is remembered and nothing is armed.
3. **The front.** `enteredForeground`: a new foreground clears the last one's cap; a watch that is not
   watching vouches for nothing, so it owes a look; anything owed arms the debounce.
4. **The look and the batch.** Read again when the debounce fires: in front, owed, not capped, none
   running. A background window is opened for the batch's whole life (the look is a store read a leave
   cannot interrupt: the window ends only when the job does). The store check is awaited; ⚠⚠ **no half
   may run ⇒ no look** (the log's end is marked if unmarked — hashes only, no path) and the runner's
   refusal still runs, deleting what a withdrawal owes. Otherwise the log read, and what it found **queued and flushed at once**
   (a leave during the pass loses none of it). Queue empty ⇒ no pass. Otherwise the runner's batch over
   the queue. Its progress is the runner's holder — *Index now*'s bar on the Models screen.
5. **The end.** The queue is settled: done and gone paths leave; unreached stay; unavailable stay until
   their third batch. A cancelled batch settles nothing.
   Capped ⇒ no other batch this foreground. Deferred, paused or refused ⇒ everything stays queued, owed to
   the next change or foreground (no loop). Busy ⇒ tried once when the runner next reads `Idle` — ⚠ or at once, once, if it already reads `Idle`
   (the busy pass ended before the answer was handled; a second busy-from-`Idle` is a retired runner and
   waits for the next change). A throw ⇒
   logged and ⚠ owed again, not armed: the next change or foreground retries it, never a loop (Codex,
   2026-11-12: a vouching watch with no further change never asked again). Otherwise a change during the batch arms one more.
6. **The leave.** `leftForeground`: the debounce and any wait for the runner cancelled; a running batch
   cancelled (the runner cancels its pass, which ends at its next photo) and owed again.
   `ForegroundIndexing`'s leave does its usual stop besides (the pass cancelled, both models closed, in a
   background window). ⚠ It never asks for continued processing.
7. **People.** The batch is a runner pass like any: `MediaIndexRunner.faceChanges` moves in its `finally`
   when it wrote face records, and Collections re-reads People through `faceChangingPasses` (§17.5).

### 17.3 Decisions taken in this PR, each with its alternative

1. **⚠⚠ The batch indexes the arrived paths, never the library** (review of the Android half, 2026-11-11:
   a capped pass over the walk read the whole tree every batch, spent its 50 on the first backlog photos in
   tree order, and could leave the arrival unindexed for good). The watch says *that* something changed;
   the modification log says *which paths* — a local upload and a file synced in from another device are
   both a row there. *Rejected:* the watch's revision alone driving a walk-based pass (the bug above);
   diffing Collections' feed (a UI walk, and no baseline in a fresh process); `File.CreatedAt` as a
   high-water mark (it is seeded from the source's time, so an old photo uploaded today is not "new").
2. **⚠ The batch yields to a running wake; it does not replace it** (the order note said "replaced by
   it", from §16.3 decision 6's press rule). The batch is the runner's automatic kind, shared with
   Android, where a short job that joined or replaced an hours-long pass would end it at its time limit.
   Here, replacing a whole-library wake with a 50-photo batch would throw away the wake's night and end
   its task with `false`; yielding loses nothing, because the queue is kept and tried once the wake's pass
   ends. A wake that starts while a batch runs replaces the batch (the runner's rule). *Rejected:* a
   manual-kind batch (it would be joined by a press and hand the press a capped report).
3. **The cap counts photos processed**, and a photo whose bytes and preview are both absent is not one
   (Codex, on the Android half: it burned the budget every batch). It stays queued for
   `ARRIVAL_MAX_UNAVAILABLE` (3) batches — a synced-in photo whose bytes are still on their way — then is
   the wake's.
4. **⚠ An unknown battery is a no** (the row said "as for *Index now*", which allows it): nobody asked,
   so the shared `PassBar.ARRIVALS` rule holds. A phone reads its battery (`IosIndexingSignals` switches
   monitoring on); only the simulator does not — which is why every existing simulator case, whose
   phone reports none, runs no batch.
5. **A batch leaves photos for the wake.** The runner reads it `COMPLETED` (it returned); ⚠⚠
   `PhotosOnDeviceAi.workLeft` says *none left* only after a **library walk** that completed
   (`libraryWalk`), so after any batch — capped or not — the next leave arms PR-6's wake. A batch over
   its arrived paths says nothing about a backlog behind them; reading an uncapped one as "current" (the
   first build) left that backlog to a press. The cost is a wake that may find the library current.
6. **A fresh process owes one look**, and so does a foreground the watch cannot vouch for. The look is a
   bounded log read and runs no pass when nothing arrived. ⚠⚠ **It starts at a persisted cursor** (review
   of this PR, 2026-11-12): the first build re-offered the whole 500-row window at every cold start, so
   unindexed photos in it were queued ahead of the upload and the batch, taking the queue oldest first, 50
   at a time, reached the upload ~10 foregrounds later; re-offered paths could also push unreached ones out
   of a full queue. Where the cursor cannot say (none yet, its row moved, a burst past the window), nothing
   older is guessed at: the look reports `missed` and `workLeft` arms the wake until a walk started after
   it completes. *Rejected:* taking path batches newest first — it would change the shared queue's order,
   which Android's half settles by. *Rejected:* a length-only log accessor — the member hashes are read
   anyway to find the cursor, and no rows are deserialised but the new ones.
7. **Never chained, per foreground.** After a capped batch nothing more is batched until Photos comes to
   the front again; what the cap left stays queued for that next batch. *Rejected:* no batch until the
   wake has run — a user who opens Photos twice after a large sync would see nothing new indexed.
8. **No retry on the bar.** A batch the heat, Low Power Mode or the battery refused waits for the next
   change or foreground; the phone's own changes do not re-arm it. *Rejected:* following
   `PhotosIndexingSignals.changes` — unasked work that re-arms itself on every thermal tick.
9. **The debounce is 3 s and the log window 500 rows — placeholders** (§17.7 Q1); the queue's bounds are
   Android's half's.
10. **⚠⚠ Consent before the look** (review of this PR): with the flag on but consent never given, every
   foreground read the log and wrote up to 500 of the realm's paths into the queue file, which nothing
   cleared (no withdrawal had happened). `arrivalsMayRun` is asked first; with no half that may run the log
   is not read (only its end marked, so the first look after consent starts there).
11. **⚠⚠ A background window over the batch** (review of this PR): `Left` cancels the batch's job, but the
   look is a synchronous store read under the shared replica's lock that a cancellation cannot interrupt;
   a leave mid-look whose own window ended first was `0xdead10cc`. The batch opens its own window when it
   starts, in front, and ends it when its job ends.

### 17.4 The shared store

The batch runs only in front, under the same per-photo bar, so it reads the shared replica only while
Photos is active; a leave cancels it and `ForegroundIndexing`'s leave holds a background window until the
pass has stopped and both models are closed (§3.3). It takes no task token and asks for no task. The look
reads one window of one array.

### 17.5 Guards, each removed once and seen red

One mutation per run, fresh results (`--rerun` on the JVM and desktop), the file restored from a copy after.

JVM (`ForegroundArrivalsTest`: the trigger on fakes, and over the real runner and indexer):

| Removed | Red |
|---|---|
| ⚠⚠ the batch reading its paths (`mediaAt`), made the walk again | `anArrivalBehindABacklogIsIndexedAndNothingElseIsRead`, `aBatchIndexesTheCapAndIsNeverChained` |
| ⚠⚠ the cap spent after the bytes (moved back before them) | `unavailablePhotosDoNotSpendTheCapAndAreTriedABoundedNumberOfTimes` |
| the queue's limit for an unavailable photo | the same |
| the queue settling done paths | `anArrivalBehindABacklog…`, `aBatchIndexesTheCap…`, `unavailablePhotos…` |
| ⚠⚠ a refusal's withdrawal deletion (no half may run) | `aRefusedBatchStillDeletesWhatAWithdrawalOwes` |
| no pass when the queue is empty | `aLookThatFindsNoArrivalRunsNoPass` |
| the runner's consent-and-model check before anything is read (`anyHalfMayRun`) | `noConsentOrNoModelMeansNoPassAndNoWalk` |
| the runner's "a pass runs ⇒ `null`" | `aBatchAndIndexNowAreOnePassAtATime`, `aBatchYieldsToAWakeAndAWakeReplacesABatch` |
| the debounce (`delay(debounceMs)`) | `aBurstOfArrivalsIsOneBatch`, `noBatchStartsInTheBackground` |
| the foreground read when the debounce fires | `noBatchStartsInTheBackground` (the scene says "behind" before its leave is handled) |
| ⚠ the foreground read when arming | **survived**: the read at the fire catches the same case. Kept as a belt (no timer behind Photos); recorded as unproved. Both removed together: `noBatchStartsInTheBackground` |
| the leave cancelling the running batch | `leavingTheForegroundStopsTheBatchAtItsNextPhoto` |
| a capped batch ending batching (`capped = true`) | `aBatchIndexesTheCapAndIsNeverChained`, `aCappedBatchIsNeverChained` |
| the busy runner waited out (`RunnerIdle` never sent) | `aBusyRunnerIsWaitedOutAndTheBatchTriedOnce`, `aBatchAndIndexNowAreOnePassAtATime`, `aBatchYieldsToAWakeAndAWakeReplacesABatch` |
| a fresh process owing a look (`owed = false`) | fifteen cases, `aFreshProcessOwesOneBatchAndAQuietVouchingWatchNoneAfter` first |
| a watch that does not vouch owing one | `aFreshProcessOwesOneBatchAndAQuietVouchingWatchNoneAfter` |
| the unknown battery a no | `theArrivalsBarIsIndexNowsWithAnUnknownBatteryANo` |
| the runner's face-change count | `peopleIsToldToReReadAfterABatch` |

Desktop (`ArrivedPhotoPathsTest`, the real file service and Control's production media source):

| Removed | Red |
|---|---|
| the keys the last read saw (each arrival once) | `theLogSaysWhichPhotographsArrivedAndEachOnce`, `aBatchOverTheArrivalsIndexesTheUploadAloneWithNoWalk` |
| the arriving kinds (`Created` / `Moved` / `Renamed`) | `theLogSaysWhichPhotographsArrivedAndEachOnce` (an overwrite in place) |
| images only | the same (a `.txt` upload) |
| `mediaAt`'s live filter | `aBatchOverTheArrivalsIndexesTheUploadAloneWithNoWalk` (a path with no file) |

Simulator (`PhotosOnDeviceAiTest`, Photos' own graph over the shared SQLite store):

| Removed | Red |
|---|---|
| the graph's look at the log (nothing queued from it) | `anUploadIsIndexedInPhotosOwnGraphInFrontAndNeverBehind` |
| `install` following the watch (`followArrivals`) | the same |
| `sceneChanged(now = true)` telling the trigger | the same |
| `workLeft` asking for a library walk (`libraryWalk`) | `aCappedBatchLeavesPhotosForTheWake` (an uncapped batch read as a current library) |
| the graph binding ONE queue the custody clears | `theArrivalsQueueIsTheOneTheCustodyClears` |
| `workLeft` asking for a walk after a `missed` look | `aCappedBatchLeavesPhotosForTheWake` |
| `notAsked` marking the log's end before consent | `anUploadIsIndexedInPhotosOwnGraphInFrontAndNeverBehind` |
| ⚠ the trigger's foreground read in Photos' graph (`inForeground = { true }`) | **survived** in the simulator: the case now counts the trigger, not the standing, but the library watch emits nothing behind Photos, so nothing is armed there either way. The read itself is pinned by `noBatchStartsInTheBackground` (JVM) |

Review round (2026-11-12), each removed once and seen red:

| Removed | Red |
|---|---|
| the persisted cursor (a fresh reader offering the history) | `aColdStartQueuesOnlyWhatArrivedPastThePersistedCursor` |
| the newest surviving row (only the very last one) | `theLogSaysWhichPhotographsArrivedAndEachOnce` (a move) |
| `missed` on a burst / on rows merged ahead | `aBurstPastTheWindowOrAMovedCursorIsTheWakes` |
| `markEndIfUnmarked` | `aColdStartQueuesOnlyWhatArrivedPastThePersistedCursor` |
| an empty log's cursor ("from its start") | the same, and `anUploadIsIndexedInPhotosOwnGraphInFrontAndNeverBehind` (caught by it in this round) |
| consent before the look (`mayRun`) | `noHalfThatMayRunMeansTheLogIsNeverRead` |
| the batch's background window | `aLeaveMidLookHoldsTheWindowUntilTheLookEnds` |
| the busy-from-`Idle` retry; its once-only flag | `aBusyAnswerFromAnIdleRunnerIsRetriedOnceAndNeverLooped` (both halves; the loop case on bounded time, its scope cancelled, so a loop reads as a count and not a hang) |
| a throw owed again (`Ending.Failed -> owed = true`) | `aThrowingBatchIsRetriedAtTheNextForegroundAndNotAtOnce` |
| a leave during the look stopping before the pass (`ensureActive`) | `aLeaveMidLookHoldsTheWindowUntilTheLookEnds` |
| an empty log's cursor | `aColdStartQueuesOnlyWhatArrivedPastThePersistedCursor` |
| ⚠ recording `missed` into `ArrivalsMissed` (`lookForArrivals`) | **not pinned**: `lookForArrivals` is internal to `:control-shared-proxy`'s iOS graph and no simulator case can call it; `workLeft`'s use of it is |

`assertNothingIsBound` now also asserts no `ForegroundArrivals` in a graph the flag or a static gate refused.

### 17.6 Not proved, and only a phone can show (📱)

1. **The row's check:** upload one photo in Drive, open Photos: within seconds it is found by search and,
   with faces on, grouped in People — without a press. The device log shows `[photos-arrivals]` with
   `asked=1 … embedded=1`. ⚠ The simulator case uploads through Photos' own client; Drive's process
   writing the shared store is the phone's to show.
2. A photo synced in from another device while Photos is open: its `Created` row arrives with it and the
   batch takes it. A sync burst of more than 500 new rows between two looks leaves the oldest to the wake.
3. A leave mid-batch: no `0xdead10cc`, the pass stopped, and the next foreground continues from the queue.
4. The bar on a warm phone or in Low Power Mode: `deferred=true`, nothing read.
5. ⚠ The log array's order on the phone's SQLite store [I]: the reader takes the newest rows as the last
   ones; the simulator case holds for one upload, a busy realm is the phone's to show.

### 17.7 Open questions

- **Q1** — `ARRIVALS_DEBOUNCE_MS` (3 s), `NEW_ARRIVALS_BATCH_CAP` (50) and `ARRIVALS_LOG_WINDOW` (500)
  are placeholders. ⭐ Recommendation: keep until §17.6 items 1 and 2 are measured.
- **Q2** — the log reader's "seen" keys are in memory, so a fresh process re-offers its whole window: up
  to 500 paths queued, most of them `DONE` at a store read each. ⭐ Recommendation: none until the phone's
  first-foreground batch time shows it matters; then persist the last key beside the queue.
