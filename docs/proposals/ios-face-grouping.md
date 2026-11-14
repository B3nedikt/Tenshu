# Face grouping on iOS — People in Photos (PIO-8, IN-6 F5)

**Status:** v0.9, 2026-11-10. Design, plus the **F1a host spike: ⭐ GO** (§10), **the F1b phone spike:
⭐ GO** (§11), **F2 built and published** (§12), **F3 built** (§13), **F4 built** (§14), **F5 built,
bound behind `TENSHU_PHOTOS_FACES` = `NO`** (§15; A40 amended) and **F6 built, flag still off** (§16:
the face model's prepare, probe and download row, and the face switch whose *Turn off* asks first):
`VisionCoreMlFaceBackend` (Vision rev 3 → corners-mid → `alignFace` → `resampleFace` → AuraFace Core
ML), readiness generalised per model (FD7: `ReadinessSlot.FACES`), and a face preflight over one bundled
CC0 face (FD12, §14.2) with the field vector pinned to the host's. ✅ F2's entry is in the live catalog (published
2026-11-10, §12.5). Nothing on iOS runs faces yet: F5 bound the backend behind `TENSHU_PHOTOS_FACES`, which is off
and needs the AI flag too; F6 gave the face model its prepare, probe and Models-screen row, which a build
with both flags forced on now offers from the live catalog. **F7 built, flags still off** (§17): People in
SwiftUI — `groupingRunsHere` from the installed graph, the face-crop cover, the person header and naming,
the "With…" chip and the empty-state lines naming Photos' own Settings → Models. F8, the device pass and
the flag flip, is what remains. Recommends
**Apple Vision for detection and landmarks, plus the AuraFace checkpoint Android already ships,
converted to Core ML fp16**. F1a measured that route on the Mac and kept the threshold at 0.44 (§10);
F1b re-ran it on the iPhone 17e and 0.44 still holds, with a narrower margin (nearest different person
0.197, §11.4). ⚠ iOS Vision's points are **not** macOS Vision's (§11.3).

**Owner:** Benedikt

**Legend.** **[C]** = verified in code on master at `8d34b3d9a` (file:line). **[D]** = a claim another doc
in this repo makes, re-read but not re-measured. **[I]** = inferred here: not compiled, not run, not
measured. **[M]** = measured; only §10 (the F1a spike, on a Mac) and §11 (the F1b spike, on the iPhone) carry [M] figures.

Read with:
- [`photos-people-and-pets.md`](photos-people-and-pets.md). It owns IN-6. §3 covers the pipeline, §7a
  argues for "one detector everywhere" (this doc disagrees with it, §3.4), §11 has the iOS research,
  and §12 has the calibration.
- [`ios-on-device-inference.md`](ios-on-device-inference.md). It owns PIO-7 (AI-L). Read §2.2 for how
  the Core ML conversion was proved, §3.1 for the four locks and the realm stamp, §3.2 for readiness,
  and D12 for the device gate.
- [`photos-ios.md`](photos-ios.md). The PIO-8 row and Q5 ("Decide before PIO-8"). §8 records that
  People's header, naming and face crop wait on this.
- `docs/sync/threat-model.md` A40, including its iOS paragraph.

---

## 0. The answer in five lines

1. Everything **after** the embedder is already shared and already runs in Photos' iOS graph. That
   covers clustering, the template store, the index pass's face half, `faceGroups`, `nameFaceGroup`,
   consent, the People model and its naming view model. What is missing is a backend that claims
   `FACE_CLUSTERING`, plus the SwiftUI around it (§2).
2. **Templates never leave the device** (A40, IN-D5). So iOS vectors never have to be comparable with
   Android vectors. What has to transfer is the **calibration**: 0.44 was measured for one checkpoint
   on one pipeline (§3.1).
3. Therefore: **use the same checkpoint (AuraFace) on iOS, not the same detector.** Vision gives boxes
   and landmarks for zero model bytes on the Neural Engine. The cost is a landmark-definition
   difference, which §3.3 says must be measured, not assumed away. A host-side spike (PR-F1)
   measures it on the Mac.
4. §7a's "MediaPipe everywhere" was argued before D1. Its second ground ("once iOS has a runtime for
   the embedder, MediaPipe's detector is 2.7 MB on top") no longer holds: iOS's runtime is Core ML,
   and MediaPipe's detector is TFLite inside a MediaPipe graph (§3.4).
5. Two preconditions sat outside this track. ✅ The inference realm stamp's epoch half is built
   (2026-11-10, §2.2 item 8): the shared store's `meta.epoch` is in the stamp. AI-L's own flag flip
   is the one left (§2.2, §7).

---

## 1. What Android does today, as built

### 1.1 The models and their runtime

| Stage | Model | Artifact | Runtime | Size |
|---|---|---|---|---|
| Box detector | MediaPipe BlazeFace short-range, `face_detector.tflite`, **read from inside** the landmarker bundle | none of its own | MediaPipe Tasks (TFLite) | 229,746 B [D: `models.md` §5.9] |
| Landmarks | MediaPipe Face Landmarker (FaceMesh V2), 478 points reduced to 5 | `face_landmarker.task` | MediaPipe Tasks (TFLite) | 3,758,596 B for the bundle [D: `models.md` row `auraface-v1-int8`] |
| Embedder | AuraFace (`fal/AuraFace-v1` `glintr100.onnx`, IResNet-100 / ArcFace-class), static QDQ int8, 512-d | `auraface_int8.onnx` | ONNX Runtime | 66,033,423 B, sha256 `1b7b3c18…89a9a` [D: `photos-people-and-pets.md` §12.8b] |

- One catalog entry, `auraface-v1-int8`, with capability `faceClustering`, runtime `onnx+mediapipe`,
  and two artifacts with the roles `faceDetector` and `faceEmbedder`
  (`deploy/models/models.json:19-30`) [C].
- `FaceModel.RUNTIME = "onnx+mediapipe"`, with the rule *"a platform contributes this only when it
  can execute BOTH"* (`inference-service-proxy/.../onnx/FaceModel.kt:42-47`) [C].
- Android contributes `SUPPORTED_RUNTIMES + FaceModel.RUNTIME`
  (`control-shared-proxy/src/androidMain/.../PlatformProxyModule.kt:656`) [C]. Desktop contributes
  `SUPPORTED_RUNTIMES` alone, so desktop is offered no face entry [D: `ios-on-device-inference.md` §12.3].
- The pinned pair is `FaceModel.PinnedAuraFace`: ArcFace 112×112 template, SigLIP's normalisation
  profile, 512 dimensions, and the norm gate `minFaceNorm = null` (off)
  (`FaceModel.kt:53-85`) [C].
- The template **belongs to the embedder** (`FaceModel.kt:13-15`) [C]. That is what lets the detector
  change while the template stays fixed.

### 1.2 The pipeline

`MediaPipeFaceBackend` lives in `inference-service-proxy/src/androidMain/.../onnx/MediaPipeFaceBackend.kt` [C]:

- **Decode.** Bounded and EXIF-upright, at most 2048 px on the long edge (`:426-450`, `MAX_DECODE_EDGE`
  `:514`). An `orientation` argument overrides EXIF, because a preview has none (`:115-119`) [C].
- **Detect.** `FaceDetectionStrategy.TwoStage(tileGrid = 2)` is the default (`:77`):
  - BlazeFace runs over the whole image and over an overlapping 2×2 tile grid (`:200-243`);
  - boxes are de-duplicated keeping the **largest** (`selectFaceBoxes`, `:246`);
  - the Face Landmarker then runs on each box's crop with a 25 % margin (`:247-280`, `CROP_MARGIN`
    `:507`);
  - each mesh is reduced to 5 points by `FaceMesh.landmarks` (`:136-137`) [C].
  - The minimum detector confidence is 0.5 (`:71`), and at most 10 faces are kept per photo (`:504`) [C].
- **Align.** `alignFace` fits a 5-point **similarity** transform onto the template
  (`commonMain/.../FaceAlignment.kt:128`) [C].
- **Resample.** Nearest neighbour through `FaceCrop.unmap`, **in `androidMain`** (`:489-500`) [C].
  ⚠ The KDoc warns that a "better" resample moves every cosine and invalidates the calibration
  (`:49-53`) [C].
- **Embed.** ONNX Runtime on the media lane, under one mutex that also caches the decoded bitmap
  between `detectFaces` and `embedFace` (`:93-98`, `:142-180`) [C].
- **Stamp.** `provenance = model.provenance.copy(labelSetVersion = strategy.pipelineVersion)`
  (`:95`). `pipelineVersion` is `PIPELINE_REVISION * 10 + tileGrid`, currently 22, and a value is
  never reused (`:529-552`) [C].
- `supports(FACE_CLUSTERING)` is true only while both artifacts resolve and the embedder has not
  latched a wrong shape (`:110-112`) [C].

### 1.3 Clustering: what it is, and where it runs

- **What.** `clusterFaces` is greedy nearest-centroid clustering over unit vectors. Rows are visited
  sorted by path, so the result is deterministic. Centroids are re-normalised as they absorb faces,
  and clusters smaller than `minFaces` are dropped
  (`commonMain/.../FaceClustering.kt:131-155`) [C].
- **The operating point.** `FaceClustering.Measured` is cosine **0.44** with `minFaces = 2`
  (`:114`), and it was re-checked for AuraFace int8 (`:102-107`) [C].
  - ⚠⚠ *"the threshold describes THIS model's geometry and transfers to no other"* (`:97-100`) [C].
  - The asymmetry is the argument for 0.44: a split costs a tap, while a merge writes a stranger under
    a friend's name on every device (`:87-95`) [C].
- **Where.** In the proxy, at read time: `faceGroups` clusters on each call. It is wired once in the
  common graph,
  `single { FaceGroupingComponents(store = get(), clustering = FaceClustering.Measured, livePaths = get()) }`
  (`control-shared-proxy/src/commonMain/.../ProxyModule.kt:304`) [C]. So on iOS it is already in
  Photos' graph.
- **What crosses IPC.** Paths, an ephemeral group id, boxes and face counts. **Never a vector**
  (threat model A40) [D].

### 1.4 Stored device-locally versus synced

| Artifact | Where | Syncs | Evidence |
|---|---|---|---|
| Face template: box + 512-d vector per face, plus a "visited" row for photos with no face | `DefaultFaceTemplateStore`, `/inference/faces.log`, append-only with `LogStrikes` erasure | **never** | `ProxyModule.kt:280-287` [C]; A40 [D] |
| Store stamp (`ModelProvenance` incl. pipeline revision) | inside the log | never | `FaceModel.kt:33-38`, `MediaIndexer.kt:423-430` [C] |
| Clusters | nowhere: recomputed per `faceGroups` call | never | `FaceClustering.kt:14-21` (*"scaffolding, not a record"*) [C] |
| **Person name** | one `MediaLabel` per photo, `capability = faceClustering`, stamped `ModelProvenance.User` | **yes**, the ordinary sealed sync plane | `NameFaceGroupImplementation.kt:22-23,93` [C]; A40 [D] |
| Consent: realm switch + per-device switch | consent rows | the realm switch syncs; the device switch is per device | A40 *Withdrawal* [D] |
| Face→person map, rejections, pinned cover (F6c) | designed as `/inference/face-people.log` | never | **not built**: no `face-people` / `personFaces` symbol in the tree [C: grep] |

⚠ This answers the question `ios-on-device-inference.md` §1.8(c) left open ("Not verified here: which
face rows are synced"). **Only the `MediaLabel` names and the realm consent switch sync.** Every
template, cluster and box is device-local [C for the label write; D for the consent row].

### 1.5 How consent gates it

- `FACE_CLUSTERING` carries `offerableInOnboarding = false`
  (`inference-service/.../InferenceCapability.kt:57`) [C]. It is a Settings-only switch, off by
  default, and needs realm ∧ device (IN-D3) [D].
- **The index pass** re-reads face consent **per file**. An explicit withdrawal clears the store,
  while an absent row skips the file and deletes nothing (`MediaIndexer.kt:507-524`) [C]. The pass
  resolves its face backend as its own "third half" (`:420-422`) [C].
- **`faceGroups` runs its checks in this order**, and the order matters:
  1. the withdrawal deletes templates **first**, ahead of every refusal (`FaceGroupsImplementation.kt:72-81`);
  2. then the store gate (`:85`);
  3. then *backend before consent* (`:97`, `:107`);
  4. then templates from another model are cleared, never served (`:116-121`) [C].
- `nameFaceGroup` refuses without consent (`NameFaceGroupImplementation.kt:66`) [C].
- In Control's Models list, `OfferedConsents` includes `FACE_CLUSTERING`
  (`control-shared-logic/.../ModelsRepository.kt:287-288`). It is shown only where
  `faceClusteringRunsHere`, a **build fact**: Android true, desktop false
  (`control-shared/.../ModelsScreen.kt:435-447`, `BuildFeatures.android.kt:14`,
  `BuildFeatures.desktop.kt:9`) [C].

### 1.6 How the People UI reads it

- The Compose-free `:photos-shared-logic` joins `faceGroups` with face-capability labels in
  `peopleFrom`. A named person is keyed by **the name**, never by a cluster
  (`photos-shared-logic/.../model/People.kt:60-100`) [C].
- Naming runs through `CollectionsViewModel.nameGroup` → `repository.nameFaceGroup`, chunked by
  budget (`CollectionsViewModel.kt:1123-1150`, `TenshuPhotosRepository.kt:1479-1495`) [C].
- The cover crop is `FaceCover(path, region)`, the largest face (`model/FaceGroup.kt:49-56`) [C].
- `CollectionsLayout.of(state, groupingRunsHere)` decides whether People may explain an empty state
  (`CollectionsLayout.kt:133-149`) [C]. On Android `faceGroupingRunsHere = true`
  (`photos-shared-ui/src/androidMain/.../BuildFeatures.android.kt:3`) [C].

---

## 2. The iOS gap, precisely

### 2.1 What iOS already has and reuses unchanged

| Piece | On iOS today | Evidence |
|---|---|---|
| `clusterFaces`, `FaceClustering.Measured`, `alignFace`, `FaceCrop` | `commonMain`, compiled into the umbrella | [C] files above |
| The template store | **Photos' own**, in its private, backup-excluded container, behind the custody wrapper; every other route-1 graph binds `UnownedFaceTemplateStore` | `control-shared-proxy/src/iosMain/.../OwnedInferenceStores.kt:78-80,169-172`; `PlatformProxyModule.kt` iosMain `:588` [C] |
| Realm-stamp clears of templates (owner id), deprovision clear, erase-at-rest | built with PIO-7 PR-3 | `OwnedInferenceStores.kt:90-92,357` [C] |
| The index pass's face half | wired from the common `ProxyModule`, so Photos' foreground pass already carries it; it finds no backend | `ProxyModule.kt:399-408`; `MediaIndexer.kt:422` [C] |
| `faceGroups` behind the same store gate as `photosLike` | wired | `PlatformProxyModule.kt` iosMain `:1132-1138` [C] |
| `nameFaceGroup` → synced `MediaLabel` | shared op | [C] |
| People model, naming VM, layout rules | `:photos-shared-logic` (iOS-capable) | [C] §1.6 |
| People cards from names synced off another device | shown on iOS today, cover centred, no crop | `photos-ios/Sources/CollectionsScreen.swift:454-460`; `photos-ios.md` §8 [C] |
| Catalog role for an embedder-only platform | `FACE_EMBEDDER` is its own role, and the KDoc anticipates Apple: *"on Apple the detector comes free from Vision and only FACE_EMBEDDER is ours"* | `catalog/ModelCatalogData.kt:36-52` [C] |
| Core ML session, compile cache, `.cpuAndNeuralEngine`, image decoder, the readiness/preflight machinery, the download journal | built for SigLIP | `inference-service-proxy/src/iosMain/.../coreml/*` [C]; `ios-on-device-inference.md` §9–§14 [D] |

### 2.2 What is missing

1. **A face detector on iOS.** None exists. `CoreMlInferenceBackend` overrides `supports` and nothing
   face-shaped (`coreml/CoreMlInferenceBackend.kt:118`; no `detectFaces` / `embedFace`) [C]. The
   interface defaults answer `Failed` / `null` (`InferenceBackend.kt:183,207`) [C].
2. **A face embedder on Core ML.** There is no Core ML artifact of AuraFace and no export script for
   one [C: `deploy/models/models.json` has no such entry].
3. **The runtime contribution.** Photos binds `inferenceBackends = listOf(CoreMlInferenceBackend)` and
   `supportedModelRuntimes = COREML_RUNTIMES = {"coreml"}`
   (`PhotosOnDeviceAi.kt:340,398`; `CoreMlInferenceBackend.kt:32`) [C]. So the `onnx+mediapipe` entry
   is filtered out (`catalog/ModelCatalog.kt:157-159`) [C], which is correct.
4. **The capability flag.** `OnDeviceModelsCapabilities.features = [SEMANTIC_IMAGE_SEARCH]`, with
   *"Search alone: no face backend exists there"* (`OnDeviceModelsRepository.kt:61-71`) [C]. Photos'
   feature-screen copy says a capability whose *no deletes* (faces) is not offered
   (`PhotosModelsCopy.kt:891-893`) [C]. So withdrawal copy for faces is owed.
5. **`groupingRunsHere` on iOS** is the literal `false` in Swift
   (`CollectionsScreen.swift:38`, `CollectionGrids.swift:311`) [C]. On iOS it must become a
   **runtime** answer, because it depends on the device gate and readiness, not on the build.
6. **The People UI in SwiftUI.** Missing: the face-crop cover, the person header, naming, and the
   "Photos with…" chip (F6b). All four are listed as deferred in `photos-ios.md` §8 [D].
7. **Pixel access for the crop.** `resample` and the bounded decode are `androidMain`, over
   `android.graphics.Bitmap` (`MediaPipeFaceBackend.kt:426-500`) [C]. iOS needs an RGBA buffer and the
   **same** nearest-neighbour rule (§4, PR-F3).
8. ✅ **Done (2026-11-10)** — `ios-on-device-inference.md` §13.2 is the as-built: the stamp is the
   owner id **and** the store's `meta.epoch`, read per check through `ReplicaEpochSource` on the
   graph's own store; a changed epoch clears templates and vectors before anything serves, an
   unknown one is no realm (nothing usable, nothing cleared), and a stamp from before clears once.
   The original finding, kept for the record:
   **The realm stamp's epoch half.** `InferenceRealm(ownerId = owner, storeEpoch = null)`
   (`OwnedInferenceStores.kt:90-92`) [C]. A40's iOS paragraph: *"acceptable while no template exists
   on iOS, and a precondition for PIO-8"* [D]. ⚠⚠ **This is a hard dependency, not a nicety.** Templates
   are path-keyed, so after a same-owner reset a path can name someone else's photo.
   ⭐ **Nothing has to be built in the store for this.** The SQLite shared store already keeps and
   reads `meta.epoch` (`core-common/.../SharedReplicaStore.kt:28,59`, `ReplicaSchema.kt:56`) [C]. The
   owed work is to **plumb the existing `meta.epoch` into `currentRealm`** in `OwnedInferenceStores`,
   so that `storeEpoch` stops being `null`.
9. **An A40 amendment for route 1.** A40 says *"no vector crosses IPC, so an app process renders
   People while holding no biometric data at all"* [D]. On iOS route 1 the proxy runs **inside**
   Photos' process [D: `ios-first-party-route-1-migration.md`], so that sentence is false there. The
   boundary on iOS is the op surface, not a process (§3.6).

---

## 3. Options for detection and embedding on iOS

### 3.1 The cross-platform constraint, decided first

**Must iOS templates come from the same embedder as Android's?**

- **For correctness: no.** Nothing compares an iOS vector with an Android vector.
  - Templates are device-local and never synced (§1.4).
  - Clusters are recomputed per device per read (§1.3).
  - What crosses devices is a per-photo name, which needs no vector space (IN-D5;
    `photos-people-and-pets.md` §3.2: *"clusters may differ between devices before labelling. The
    labels do not."*) [D].
  - F6c's prototypes are computed per device from that device's own store [D: F6c.1].
- **For quality: yes, the same checkpoint.** The operating point (0.44, `minFaces = 2`) and the
  argument for where it sits were measured for AuraFace on one human-verified library (§1.3). A
  different checkpoint on iOS would need its own labelled calibration, and today exactly one exists.
  The maintainer's requirement, *"for our own models the results should be the same"*
  (`photos-people-and-pets.md` §3.2), is met by "same checkpoint, re-checked pipeline". Bit-identical
  vectors are not needed.
- **Numerics need not match.** Android runs int8 QDQ on ORT, while iOS would run fp16 on Core ML.
  Android's own fp32↔int8 per-face cosine is 0.994 mean [D: §12.8b]. SigLIP's fp16 Core ML matched
  fp32 at ≥ 0.9996 [D: `ios-on-device-inference.md` §2.2]. So iOS fp16 is probably **closer to fp32
  than Android is** [I].

⭐ **Decision proposed (FD2):** the same checkpoint on every platform, with its own pipeline stamp per
platform. The calibration is re-checked on iOS's pipeline before anything ships, and a per-platform
threshold is allowed only from that measurement. This is §3.3's rule ("one model family everywhere")
with its real reason stated: calibration, not wire comparability.

### 3.2 What A40 and the threat model require of any option

- **Device-local templates in Photos' own container.** This is already true of the store, whichever
  detector is chosen (§2.1).
- **On-device only.** Vision and Core ML both run in-process with no network [I: Apple documents
  on-device execution; not re-checked]. Any option that sent a face off the device is out.
- **The epoch half of the stamp** before the first template is written (§2.2 item 8). ✅ Built.
- **Consent before bytes.** This is inherited: the pass reads consent per file before the backend is
  called (§1.5).
- **A model or pipeline change re-indexes.** The stamp mechanism is inherited. The iOS pipeline needs
  its own never-reused `pipelineVersion` values (§4).

### 3.3 The options

| | (a) Convert Android's models to Core ML | (a′) MediaPipe Tasks iOS SDK + Core ML embedder | ⭐ (b) Vision `VNDetectFaceLandmarksRequest` + AuraFace on Core ML | (c) Apple-native face features |
|---|---|---|---|---|
| Detector | BlazeFace + FaceMesh V2 TFLite → Core ML, plus MediaPipe's graph logic (anchors, NMS, ROI rotation and scaling, crop-to-image projection) **re-implemented in Kotlin** | MediaPipe's own graph, a third-party XCFramework through cinterop | Vision, system framework, ANE, revision pinnable | Vision |
| Embedder | AuraFace → Core ML fp16 | same | same | ❌ **none public.** No faceprint API in any public Vision header; `VNGenerateImageFeaturePrintRequest` is a general-image descriptor [D: §7a, checked against SDK headers 2026-09-23] |
| Landmark semantics vs Android | the same **models**, but a re-implemented graph, so not the same pipeline: parity has to be **measured** anyway | the same graph, closest to Android [I] | different. Pupils ≈ MediaPipe's iris centres (same definition) [I]; mouth corners from `outerLips` ≈ vertices 61/291 [I]; **the nose tip is the open one** | n/a |
| Download | ~3.7 MB detector + embedder | embedder only; the SDK is in the binary | **embedder only** | none |
| D1 ("no third-party binary on iOS") | kept | ❌ **broken**: TFLite + MediaPipe in the app, beside Core ML | kept | kept |
| Work | largest: two conversions **plus** the post-processing port, with parity per stage | medium, but a cinterop and a vendored binary (the libsodium-style build script precedent) | smallest: one Vision call through K/N `platform.Vision`, one 5-point mapping in `commonMain`, one conversion | n/a |
| Who it helps besides iOS | ⭐ desktop F2d (MediaPipe → ONNX) could share the ported post-processing | nobody | nobody | — |
| Spike cost | high, and mostly on the Mac | medium | **low, entirely on the Mac**: macOS has the same Vision API [I] | — |

**Why (c) is out.**
- No public identity embedding exists on any Apple OS [D].
- PhotoKit exposes no people or faces API to third parties [I]. Even if it did, it would cover the
  system photo library, not Tenshu's realm files.
- Any Apple embedding would be a second, unversioned space with no Android twin and no calibration.

**Why (a′) is out.** It undoes D1's main win: no third-party runtime on iOS, everything on Core ML.
It also runs a second ML runtime beside Core ML in Photos' memory budget (D10, D12 are still open).
Keep it as the fallback of the fallback.

### 3.4 Recommendation: (b), with (a) as the measured fallback

⭐ **Vision for boxes and landmarks; AuraFace (the same checkpoint, sha `a7933ea5…25c60` at fal repo
commit `af6d057c` [D: `models.md` §5.4]) converted to Core ML fp16; alignment and resample shared in
`commonMain`.**

The reasoning, against §7a's three grounds for MediaPipe everywhere:

1. *"Desktop needs our own model whatever we do."* True, and unaffected. Desktop gets nothing from
   either iOS route except, under (a), a ported post-processing. That is a real but deferred benefit:
   F2d is not scheduled [D: `tech-debt.md` "People on desktop"].
2. *"iOS needs an inference runtime for the embedder regardless; once there, MediaPipe is 2.7 MB on
   top."* ⚠⚠ **This ground was written before D1 (2026-10-04) and no longer holds.** The runtime iOS
   got is Core ML, which cannot run a `.task` bundle. "2.7 MB on top" is now either (a′)'s
   third-party binary or (a)'s two conversions plus a port of MediaPipe's graph. Vision now saves
   **structure**, not just bytes.
3. *"A second landmark convention is not free."* True, and it is the whole risk of (b). But (a) does
   not escape it either: Android's calibration was measured on MediaPipe's **graph**
   (`MediaPipeFaceBackend.kt:49-53`) [C], and a re-implemented graph is a different pipeline that has
   to be re-measured. Both routes owe the same parity measurement, and (b)'s is cheaper to run.

Other points for (b):
- IN-D13's table already assigns Apple face detection to Vision (`inference-service.md` §7 C0) [D].
- The catalog role split was built for it (`ModelCatalogData.kt:36-44`) [C].
- Vision handles small and distant faces without a tile grid [I]. Android needed tiles only because
  BlazeFace is short-range [D: §12.14]. That recall claim is part of what the spike measures.

⚠ **What (b) costs, stated plainly:**
- **A per-platform pipeline stamp.** iOS and Android libraries cluster on different pipelines. IN-D5
  accepts that, but it is real.
- **A possible per-platform threshold.** If the spike moves the operating point, iOS gets its own
  `FaceClustering` value through Photos' `appOverrides`. The graph wires clustering once
  (`ProxyModule.kt:304`), and `appOverrides` is last-wins [D: `ios-on-device-inference.md` §3.1], so
  this needs no new seam [I].
- **The OS owns the detector.** A Vision revision can change between iOS releases. Pin the request's
  `revision` and stamp it into the pipeline version (FD9). Until a measurement shows outputs survive
  an OS update, follow SigLIP's precedent: the OS build is in the runtime fingerprint, so an OS
  update clears templates as well (FD9 prices this).

**When to fall back to (a).** PR-F1a's go/no-go fails on landmarks (§7) and a per-detector landmark
correction does not recover it. (a) then becomes its own plan, sized together with desktop F2d,
because the post-processing port is shared.

### 3.5 Landmarks: the one piece of new maths

Vision's `VNFaceLandmarks2D` gives point regions **normalised to the face's bounding box, with a
lower-left origin** [I: Apple docs, from memory]. Getting the y-flip wrong is a classic trap.
The mapping lives in `commonMain` as a pure function (`VisionFaceLandmarks.landmarks(...)`), fed by
recorded Vision fixtures, so the JVM gate tests it:

| ArcFace point | From Vision | Risk |
|---|---|---|
| left / right eye | `leftPupil` / `rightPupil` (one point each) | low: the same definition as MediaPipe's iris-ring centres [D: `models.md` §5.9] |
| mouth left / right | the extreme points of `outerLips` | low to medium [I] |
| nose tip | `nose` / `noseCrest` (the crest's lowest point, or the nose region's centroid) | ⚠ **the open one**: it is a definition, not a precision difference (`photos-people-and-pets.md` §3.3) |

⭐ **Measured in F1a (§10.3): use the eye CORNERS, not the pupils, and the nose midway between the
crest's lowest point and the nose region's lowest point.** That mapping needs no fitted constant.
The pupils sit ~0.05 IOD above FaceMesh's corner midpoints, and the crest's lowest point sits
0.07 IOD above its nose tip.

If the nose point is biased, the spike fits a **fixed correction** in inter-ocular units on public
ground truth. That is §3.3's "per-platform template calibrated against that detector's own landmark
definitions", applied to one point.

### 3.6 The threat-model consequences of (b)

These are owed in PR-F5 (HR 2):
- **A40, iOS paragraph.** Add that on iOS the detector is Apple Vision, in-process and on-device. Add
  that on route 1 the `faceGroups` boundary is an **operation**, not a process: Photos' process holds
  templates in memory while it serves People. This is Decision C's co-owner posture (A4) applied to
  A40.
- **The existing `meta.epoch` is plumbed into the stamp first** (§2.2 item 8), or A40's own precondition is breached. ✅ Done; A40 records it.
- **Data Protection class of `faces.log`** (FD8). Today it inherits Application Support's default
  [I]. State it in A40 either way.
- No change to HR 1, A1–A4 or the wire. **No schema reshape**: no new composite and no new op, so no
  HR 9 bump (§5 checks it with the baseline test).

---

## 4. The iOS pipeline, as proposed

```
bytes ─▶ IosImageDecoder (bounded 2048, upright) ─▶ RGBA buffer
         │
         ├─▶ VNDetectFaceLandmarksRequest (pinned revision, on the same CGImage) ─▶ boxes + regions
         │        └─▶ VisionFaceLandmarks.landmarks (commonMain) ─▶ DetectedFace(box, 5 points)
         │
         └─▶ alignFace (commonMain, unchanged) ─▶ resample (commonMain, nearest, FaceCrop.unmap)
                 └─▶ ImagePreprocessing (SIGLIP profile, as Android) ─▶ Core ML AuraFace fp16 ─▶ 512-d
```

- **One backend, not a separate detector seam.** `VisionCoreMlFaceBackend` (iosMain,
  `inference-service-proxy`) claims `FACE_CLUSTERING` only while the embedder is installed **and**
  its readiness is `Ready`. §11's worry, that a Vision-only backend wins the slot and cannot embed,
  goes away because one class owns both halves [I].
- **The same `FaceModel` template and profile.** A new `FaceModel` value (`AuraFaceCoreMl`) has its
  own `modelVersion`, and an iOS `pipelineVersion` drawn from a range Android never uses: ≥ 100
  (Android has used 0, 1–4, 11–14 and 21–24) (`MediaPipeFaceBackend.kt:529-552`) [C].
- **Same caps as Android**: confidence floor, 10 faces per photo, largest-box de-duplication. Vision's
  own confidence is not BlazeFace's, so the floor is re-chosen in the spike [I].
- **Bytes cached between detect and embed under one lock**, as on Android (`:93-98`) [C].
- **The resample moves to `commonMain`.** It becomes a function over an RGBA `IntArray` and Android's
  backend calls it. ⚠ It must keep Android's arithmetic bit-for-bit (pixel centres, coerce, nearest),
  or Android's calibration silently moves. The proof is a golden test run on Android's current
  output before the move (PR-F3).
- **Core ML session.** Reuse `CoreMlSession` / `CoreMlModelCache` (compile on device, `ready`
  marker, `.cpuAndNeuralEngine`), with the shape guard set to 512 and the finite / non-zero guard on
  every vector [D: D12 ②].

---

## 5. Licensing and model provenance

| Item | Question | Recommendation |
|---|---|---|
| **AuraFace, converted** | A Core ML fp16 file is a derivative of `glintr100.onnx`. Apache-2.0 §4 permits it with the licence text, NOTICE and a "files changed" statement. ⚠ This inherits the **open provenance** of AuraFace's training data (fal names no dataset; the repo's licence file names no grantor) [D: `models.md` §5.4]. The maintainer shipped route 3 on fal's public claim on 2026-09-24 [D: §10 Q1]. | **No new question, but an open one gets wider.** `models.md` §5.4 already defers a written confirmation from fal: (a) from-scratch training, (b) datasets and licence basis, (c) *"that Apache-2.0 extends to CDN redistribution and quantisation"* [D]. The Core ML conversion is **another derivative under that same deferred item**, so widen (c) to cover format conversion ("quantisation **and format conversion**"). Record it in the counsel packet's §4 as "same weights, a second format". |
| **Pinning the source** | §2.2 found SigLIP's ONNX had no recorded upstream revision. | The export script records repo, commit `af6d057c`, the source file sha `a7933ea5…`, and the pinned toolchain in a `source` block, as the SigLIP `coreml` entry does (`deploy/models/models.json:41-60`) [C]. |
| **Conversion route** | coremltools has no ONNX frontend [I: removed in coremltools 6]. The route is ONNX → PyTorch (`onnx2torch`) → `coremltools.convert`, and IResNet ops (Conv, BN, PReLU, Add, Gemm) are expected to convert [I]. | PR-F1 proves it. The self-check is per-face cosine against fp32 ORT on aligned crops. |
| **Apple Vision** | System framework under the Apple SDK licence. Nothing is redistributed, and its outputs are ours to use [I]. | None for counsel. Pin the revision for reproducibility, not for licence reasons. |
| **The preflight probe images** (§6) | A probe needs faces. A real person's face in the app bundle is biometric data and a licensing question. Android's quantiser used Vec2Face synthetic images [D: §12.8b], but Vec2Face's generator descends from WebFace4M, the encumbrance route 2 was dropped for [D: §10a]. | **Do not bundle Vec2Face output.** Use public-domain or CC0 faces with the licence recorded beside the file, or a self-consistency probe: one image under two fixed augmentations must rank above a second image (FD12). |
| **App Store** | Guideline 5.1.2(vi) restricts the use of facial mapping data [I: from memory, re-check]. The App Privacy label would say "not collected", since everything stays on device. | Check the guideline text before TestFlight. Photos' privacy manifest exists (5e-2) [D], so add nothing to it unless the guideline asks. |
| **MediaPipe** (fallback (a) only) | Apache-2.0 on the weights, with consented training data [D: `models.md` §5.9]. | Clean. Already hosted. |

---

## 6. Device gates and the spike

### 6.1 Targets

These are targets, not measurements [I]:
- **Embed** median ≤ 20 ms per face on the iPhone 17e under `.cpuAndNeuralEngine`. Android int8 on
  the CPU EP is 58–65 ms [D: §12.8b].
- **Vision** detect + landmarks ≤ 60 ms per image at a 2048 px edge.
- **Stop condition**, mirroring `ios-on-device-inference.md` §4.5: last-50 per-photo time (decode +
  detect + embed of every face) > 500 ms, or throttle > 2.
- **Footprint.** `phys_footprint` with SigLIP's two towers **and** the face embedder open together.
  This is D10's open question, made larger by this model.
- **Disk.** The download is ≈ 130 MB for fp16 (half of fp32's 260 MB [D]) [I]. The compiled copy
  roughly doubles it while it exists [D: §3.4 for SigLIP], and the free-space check uses the measured
  figure.

### 6.2 The gate: reuse D12's shape, with a key of its own

- **Static gate.** Faces are offered only where AI-L's `AiStaticGates.decide` admits the phone
  (iOS 17+, `physicalMemory` ≥ the threshold) **and** a separate face build flag is on.
  - The threshold is `PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES` = 6.5 GiB today
    (`AiStaticGates.kt:31,52`) [C], and is re-set from the combined footprint in §6.1.
  - ⚠ Photos' iOS 16 floor stays; faces, like AI-L, start at 17 [D: D12 ⓪].
- **Runtime preflight**, after the download and before the first pass. It is keyed on a **face
  preflight key**: the embedder digest, compute units, OS build, Vision revision, `PREFLIGHT_REVISION`
  and the app build. It runs on sessions opened outside the registry, as SigLIP's does [D: §14.2]:
  - **Vision probe:** a fixed image yields ≥ 1 face with 5 finite points inside its box;
  - **embedder probe:** a finite, non-zero 512-d vector, and the fixed pair ranking of FD12, with a
    recorded margin;
  - timing of both, `thermalState`, and `phys_footprint` with **all** inference sessions open.
- **Same verdict rules as D12**, reused rather than re-argued:
  - `Unsuitable` is persisted only for what the phone *is*;
  - a probe under `.serious`, memory pressure or a load error is retryable;
  - `AttemptBegun` counting applies, and two interrupted loads make the phone `Unsuitable`;
  - the verdict is a **live readiness state** consulted in `supports`, never a graph change
    [D: D12, §14.5 item 0].
- **Readiness per model.** `AiReadiness` today is SigLIP's. Generalise it to per-model keyed
  readiness rather than copying it (FD7), so search and faces are ready, unsuitable or deleted
  independently. IN-D10 says a user may want either one without the other [D].

### 6.3 Where the go/no-go runs

- ⭐ **On the Mac first (PR-F1a).** Vision exists on macOS, and the four checks below need no phone,
  no App Group build and no S0b.
- **On the phone second (PR-F1b).** The `inference-harness-ios` app needs no App Group either
  [D: §4.2], so it can host the face probe today.

---

## 7. PR plan

The table uses `ios-on-device-inference.md` §5's notation: 📱 = maintainer's iPhone, 🖥 = this Mac,
*smoke* = `scripts/android-smoke.sh`, *iOS lane* = `run-gate.sh`'s iOS compile, *iOS tests* =
`iosTestCheck`.

| # | What | Files / modules | Owes | Tests | User sees |
|---|---|---|---|---|---|
| **F0** | This plan | this doc; a pointer in `ios-on-device-inference.md` §1.8(c); `docs/README.md` | `check-pr-refs.py`, `check-doc-refs.py`, `check-proposal-status.py` | — | nothing |
| **F1a** ⭐ **Host spike** — ✅ **run 2026-11-09: GO** (§10) (throwaway, **never committed**; personal photos stay in the scratchpad) | (i) AuraFace fp32 ONNX → Core ML fp16 (and a weights-int8 variant), self-checked against ORT fp32. (ii) A Swift CLI on macOS running `VNDetectFaceLandmarksRequest` at a pinned revision over **the verified library** (the 400-photo camera roll behind §12.6, pulled from the OnePlus) and the §12.2 public ground-truth set. (iii) The same photos through MediaPipe Python. (iv) Vision-aligned crops embedded and clustered at 0.44 | 🖥 only | **Go/no-go**, each recorded: ① the 5-point error vs MediaPipe, normalised by inter-ocular distance, per point (nose separately); ② per-face cosine, Vision-aligned vs MediaPipe-aligned crop, same embedder; ③ the **verified 29-face cluster reproduces with no intruder** at 0.44, **and** the nearest different-person cosine similarity is **≤ threshold / 2**, i.e. ≤ 0.22 at 0.44. That is the 2× rule of `FaceCalibrationTest` (`theThresholdClearsTheNearestDifferentPersonByAMargin`), and Android's figure is 0.215 [D: §12.8c]. ⚠ The number is a similarity, so **higher is worse**: a value above 0.22 means two people sit close enough to merge; ④ recall against Android's 62 faces on that library [D: §12.14]. ⚠ **No-go** = ③ fails and no threshold recovers it once the rule is applied. An admissible threshold must hold the verified cluster whole (on Android it held at 0.44 and had fragmented by 0.52 [C: `FaceClustering.kt:102-104`]) **and** be ≥ 2 × the nearest different-person similarity measured on iOS's pipeline. With Android's figures that leaves roughly 0.43 up to the fragmentation point, with only 0.44 demonstrated, so the old "0.20–0.44" band is not the safe band. If a nose correction (§3.5) does not get iOS back inside that window ⇒ fallback (a), re-planned with F2d | none | nothing |
| **F1b** **Phone spike** (throwaway) — ✅ **run 2026-11-09: GO** (§11) | the F1a artifact side-loaded into `inference-harness-ios`; Vision on the phone | 📱 | embed and Vision median / p90; `phys_footprint` with SigLIP's towers open too; thermal over a sustained run; phone vectors vs Mac cosine ≥ 0.9999; phone Vision points vs macOS Vision points on the same files. Stop: §6.1. Results into this doc | none | nothing |
| **F2** Export + catalog entry — ✅ **built and published 2026-11-10** (§12, §12.5) | `scripts/inference/export-coreml-face-embedder.py` (manifest, `source` block, self-check); `deploy/models/models.json`: id `auraface-v1-int8` (FD4), runtime **`coreml+vision`** (FD4), one `faceEmbedder` artifact; `:model-signer` refuses a duplicate `(id, runtime)` (built in PR-2b [D]); `docs/models.md`. The maintainer signs and publishes | JVM gate (`:model-signer`, `:inference-service-proxy` jvmTest); no client code ⇒ no smoke. ⚠ Safe for shipped builds: none contributes `coreml+vision` | `FaceCatalogEntryTest`-style: Android/desktop/iOS-AI-L builds filter it out; an iOS build with the face runtime keeps exactly one entry | nothing |
| **F3** Shared maths to `commonMain` — ✅ **built 2026-11-10** (§13) | `resample` (RGBA `IntArray`, nearest, `FaceCrop.unmap`) out of `MediaPipeFaceBackend`; `VisionFaceLandmarks` (pure, fixtures recorded by F1a; ⭐ the mapping is §10.3's *corners-mid*, and the public-set fixtures are at `~/tenshu-models/face-f1a-2026-11-09/public-fixtures`); `FaceModel.AuraFaceCoreMl` + iOS `pipelineVersion` ≥ 100; no nose correction (F1a needed none, §10.3) | **smoke** + `:control:mergeLibDexDebug` (a commonMain change under Android's backend); iOS lane | ⭐ a **golden test of Android's resample, written and green BEFORE the move**, unchanged after it (`fix-verified-by-its-own-assumption`); mapping tests incl. the y-flip and rotated faces; each guard deleted once and seen red | nothing |
| **F4** The iOS backend — ✅ **built 2026-11-10, unbound** (§14) | `VisionCoreMlFaceBackend` (iosMain) over `platform.Vision` + `CoreMlSession`; face readiness + preflight (§6.2) via per-model `AiReadiness` (FD7); the probe assets (FD12) | iOS lane; iOS tests; `ios-smoke.sh --build-only photos` | fixture backend: claims nothing until `Ready`; `Unsuitable` / `AwaitingVerdict` per D12's rules; non-finite vector refused; the bytes cache is safe across detect → embed; a Vision error is `Failed`, never `Detected(empty)` (which is remembered) | nothing |
| **F5** Binding + threat model — ✅ **built 2026-11-10, flag off** (§15) | Photos `appOverrides`: add the backend to `inferenceBackends`; `supportedModelRuntimes += "coreml+vision"`; the face build flag (FD10); `OnDeviceModelsCapabilities.features` from the bound graph, not a constant; `FaceClustering` override only if F1a moved it (FD3), **with its own calibration test** (the 2× margin and the band, as `FaceCalibrationTest` does for `Measured`, which an override escapes); ⚠ **requires the `meta.epoch` stamp half landed** (✅ landed 2026-11-10); **A40 amended** (§3.6); ⚠⚠ the face readiness's `applyFingerprint` is a custody over `ReadinessSlot.FACES.fingerprintPath`, **never** SigLIP's (§14.4a) | iOS lane; iOS tests; smoke (if `commonMain` moves); `SchemaShapeBaselineEnforcementTest` green with no regeneration (proves no HR 9 bump) | Drive's, Control's and Calendar's graphs still resolve no face backend; Photos' resolves it only behind the flag and the gate; a stamp mismatch clears templates before `faceGroups` serves; withdrawal deletes before the gate (the existing `aWithdrawalDeletes…` cases re-run on the iOS graph) | nothing (flag off) |
| **F6** Consent + Models screen — ✅ **built 2026-11-10, flag off** (§16) | Photos' Models screen offers the faces download and the face switch; feature-screen copy for **"turning this off deletes this iPhone's face data; names stay"**; *Turn off* asks first | iOS lane; Swift; `PhotosIosUITests` | Swift UI tests on the fixture: consent order, the delete-on-off sentence, absent ≠ off | the switch (flag on) |
| **F7** People in SwiftUI — ✅ **built 2026-11-10, flags off** (§17) | `groupingRunsHere` from the surface (runtime, not literal); the face-crop cover (`FaceCover.region`); person header, naming via `CollectionsViewModel.nameGroup`; "Photos with…" chip; the empty-state lines | Swift; `ios-smoke.sh --build photos`; `check-objc-clashes.py` if exports change | `CollectionsUITests` extended: unnamed clusters, name → card titled, a name synced from Android shows its crop once iOS has faces; Kotlin already covers the rules (`CollectionsLayoutTest`, `PeopleCollectionsTest`) | ⭐ People on iOS (flag on) |
| **F8** 📱 **H6** device pass, then the flag flip | — | 📱 | the verified library on the iPhone (F1a's result reproduced in the product); a name typed on iOS appears on Android and the reverse; withdrawal deletes `faces.log`; a realm reset clears it with Photos not running; a Vision-revision or OS-build change re-indexes | ⭐ shipped |

**Order.**
- F1a → F1b → F2 (publish) → F3 → F4 → F5 → F6 → F7 → F8. F2 is built and published (2026-11-10); its publish was the maintainer's
  (`docs/how-to/models-pipeline.md` §6.2).
- F3 can start beside F1b once F1a passes.
- F5 also waited on the epoch being plumbed into the stamp (§2.2 item 8; ✅ done), and F8 waits on AI-L's flag flip
  (`ios-on-device-inference.md` §14.18.16), because faces ride the same foreground pass, download
  journal and readiness machinery.

---

## 8. Decisions for the maintainer

| # | Decision | Recommendation | Cost of the recommendation |
|---|---|---|---|
| **FD1** | Detector on iOS: Vision (b), converted MediaPipe (a), or the MediaPipe SDK (a′)? Settles `photos-ios.md` Q5 and §7a's open item | ⭐ **(b) Vision**: F1a passed (§10), so (a) is not needed; (a′) never while D1 stands | Two pipelines across platforms, with a stamp each; Apple owns the detector's revisions |
| **FD2** | Must iOS templates come from Android's embedder? | **The same checkpoint, not the same numerics or detector.** Vectors are never compared across devices; the calibration is what must transfer (§3.1) | One extra conversion; a calibration re-check per pipeline |
| **FD3** | One threshold or per-platform? | ⭐ **Keep 0.44: F1a's ③ held** (§10.4; nearest different person 0.176–0.189 on iOS's pipeline, ≤ 0.22). Had it failed: an iOS `FaceClustering` value through Photos' `appOverrides`, chosen by §10 Q2's rule (high end of the safe band) **and** the 2× margin rule. ⚠ `FaceCalibrationTest` pins only `FaceClustering.Measured`, so it would not see an iOS override: F5 must add a calibration test of its own for the iOS value, with iOS's measured nearest different-person figure | Two numbers to keep honest if they diverge; ⚠ never carry a number across without its measurement |
| **FD4** | The catalog entry's id and runtime | **Same id `auraface-v1-int8`, runtime `coreml+vision`.** The id follows PR-2b's rule (one id = one checkpoint across runtimes); renaming Android's would re-index every Android library post-release. ⚠⚠ The runtime **must not be bare `coreml`**: every Photos build with AI-L contributes `coreml` (`PhotosOnDeviceAi.kt:398`) [C], so it would offer a ~130 MB face download that nothing on it can use. That is `FaceModel.RUNTIME`'s rule (`FaceModel.kt:42-47`) applied to iOS | The `-int8` in the id is wrong for an fp16 artifact, kept as a recorded wart |
| **FD5** | fp16, or int8-weight palettisation? | **fp16** unless F1b's disk or footprint says otherwise; SigLIP's fp16 route is the proven one. ⭐ F1a: the weights-int8 variant is 65.6 MB and clusters identically on the Mac (§10.2), so it is a real option if F1b's footprint pushes. ⭐ **F1b: it does not push** — the pass peak is 132 MB with fp16 and 135 MB with int8, both with SigLIP's towers open, and int8 is only 0.8 ms a face faster (§11.2). fp16 stays | 130.6 MB download instead of 65.6 MB [M: §10.2] |
| **FD6** | Which iPhones? | **Only where AI-L is admitted** (the same static gate), plus the face preflight; no separate memory number until F1b measures one. ⭐ F1b measured one on the iPhone 17e: adding the face embedder to SigLIP's two towers costs ~1 MB after open and nothing visible at the pass peak (§11.2), so it gives no reason for a face-specific threshold. ⚠ The floor-class phone is still unmeasured | A phone that could run faces but not SigLIP gets neither |
| **FD7** | Readiness: copy `AiReadiness` or generalise it? | **Generalise to per-model keyed readiness** | Touches the 5a/5b code that is built and tested; a copy would be quicker and would drift |
| **FD8** | Data Protection class for `faces.log` | **Keep the container's default** (as for the vectors, A5's AD1 posture) and say so in A40; revisit if continued processing (PR-7) is shown to run while locked | Templates are readable after first unlock, as vectors are |
| **FD9** | Vision revision and OS updates | **Pin the revision and stamp it; keep the OS build in the fingerprint** until F8 shows an OS update leaves Vision's points unchanged | ⚠ Until then every iOS update re-indexes faces: a whole-library re-detection, recoverable, names untouched |
| **FD10** | Its own build flag? | **Yes**, `TENSHU_PHOTOS_FACES`, flipped after AI-L's | One more flag in `project.yml` |
| **FD11** | Scope: F6c on iOS? | **No.** iOS reaches Android's built state (F3b, F4, F6a, F6b); F6c lands in `commonMain` later and iOS then owes UI only | iOS People has no suggestions until F6c exists anywhere |
| **FD12** | Probe images | **CC0 / public-domain faces with the licence beside them, or the two-augmentation self-consistency probe**; never Vec2Face output | A small sourcing task in F4 |
| **FD13** | ⚖️ Counsel | **No new ask, but extend the open one**: the same weights as Android in another format. That conversion falls under `models.md` §5.4's already-deferred written confirmation from fal, whose clause (c) should be widened from "redistribution and quantisation" to include format conversion; add one line to the packet's §4. The maintainer is counsel here, so this is a recommendation to act on, not to wait for | — |

---

## 9. Inferred, not verified

- ~~That macOS Vision and iOS Vision give the same points at the same revision~~: **false**, §11.3. Same
  revision, same face count, same confidences, but the points and boxes differ on full-size photos
  (median 0.0016 inter-ocular distances, max 0.82 on one 13 px face). Only the effect on ③ is measured, and
  it holds.
- ~~That `onnx2torch` + coremltools convert IResNet-100 cleanly~~: measured in §10.2 (fp16 per-face
  cosine ≥ 0.99991 against ORT fp32).
- ~~The latency targets on the **phone** (§6.1)~~: measured in §11.2, and both are met by a wide margin.
- ~~That Vision's recall on distant faces matches or beats Android's tiled two-stage pipeline~~: §10.5. It finds 58 of the reference's 61 faces plus 28 more, and 4 of those 28 still owe a by-eye check.
- The App Store guideline's text (§5). (Vision's coordinate conventions were measured in §10.3.)
- That Photos' `appOverrides` can override `FaceGroupingComponents` without a new seam (§3.4).
- That one `VisionCoreMlFaceBackend` removes `photos-people-and-pets.md` §11's registry concern.

---

## 10. ⭐ As built: the F1a host spike (2026-11-09) — **GO**

Everything in this section is **[M]**, measured on a Mac (Apple M4 Pro, macOS 27.2 build 26B123). No
product code was written or committed. The spike code, the two converted artifacts and the public-set
fixtures are at `~/tenshu-models/face-f1a-2026-11-09` for F2/F3. The personal photographs, crops,
embeddings and library landmarks stayed in the session scratchpad and are not in that folder.

### 10.1 What ran, and the inputs

- **The verified library.** These are the 400 originals behind `photos-people-and-pets.md` §12.6. They
  were read with `adb pull` from the harness folder on the OnePlus CPH2581
  (`…/org.tenshu.inference.proxy.test/files/face_photos`, 1.9 GB), and the names match the device's
  `list.txt` 400/400.
  - ⚠ `~/tenshu-models/in3m-corpus-oneplus-2026-10-07` is **not** this library: only 90 of its 554
    files are among the 400.
  - The verified identity is the device's own listing `in6-clusters/cluster-00-n29.txt`, which is
    identical to `in6-parity/`.
- **The public set** (§12.2). This is Vec2Face `asset/perturbation.png`, sha256 `99b83a9b…1215`
  (11.8 MB, the only download). It was sliced into the harness's 88 tiles (8 identities × 11; column 0
  is the real reference photograph).
- **One decode for everyone.** A Python port of `decodeUpright` writes the bounded, upright bitmap
  once: power-of-two `inSampleSize` while the edge is over 2048, the same libjpeg DCT scaling, then
  EXIF. MediaPipe, Vision and the aligner all read those exact pixels. ⚠ So this measures the
  detector and the mapping, **not** `IosImageDecoder`. F1b and F3 owe that.
- **The reference** is `MediaPipeFaceBackend`'s `TwoStage(tileGrid = 2)`, ported line for line to
  MediaPipe Python **0.10.35** (Android's tasks version). It uses `face_detector.tflite` read from
  inside the same `face_landmarker.task` (sha256 `64184e22…`), with the same caps, margins, overlap
  tests and largest-box de-duplication. Embedding uses the shipped `auraface_int8.onnx`
  (`1b7b3c18…89a9a`).
  - ⭐ **It reproduces Android**: 61 faces over 58 photographs, which is §12.15's *"61 tiled
    detections"*. Clustered at 0.44 it gives 33 / 9 / 6 / 3 / 2 / 2.
  - The 33 hold **all 29 verified photographs plus 4**, which is §12.14's *"29 → 33, all four the same
    person"*.
  - That 33-face cluster is the ground truth below. A MediaPipe face outside it is taken as not that
    person, because §12.14 looked at every one by eye.
- **The iOS side.** A Swift CLI runs `VNDetectFaceLandmarksRequest` with **`revision =
  VNDetectFaceLandmarksRequestRevision3`**. That is the 76-point constellation; Vision logs its
  internal detector as `VNFaceDetectorRevision2`, and the supported revisions are [1, 2, 3].
  - Vision's points are mapped to five (§10.3), then go through a float32 port of `alignFace` and of
    the nearest-neighbour `resample` (pixel centres, `unmap`, `toInt()` truncation, `coerceIn`).
  - They are then preprocessed with `Profile.SIGLIP`, embedded with the Core ML artifact under
    `.cpuAndNeuralEngine`, and clustered by a port of `clusterFaces`.
  - A Vision face is matched to a MediaPipe face when their eye midpoints are within 0.5
    inter-ocular distances (IOD). It is then **V** (in the verified 33), **D** (MediaPipe saw it and
    it is someone else) or **U** (Vision only: unknown, never counted as either).

### 10.2 The conversion

- **Source.** `glintr100.onnx` fp32: sha256 `a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60`,
  260,694,151 B. It was pulled from the same phone's harness folder (`auraface_glintr100.onnx.keep`)
  and the sha was checked before converting; the script refuses any other file.
- **Route.** ONNX → `onnx2torch` 1.5.15 → `torch.jit.trace` → coremltools **9.0** `mlprogram`,
  `FLOAT16`, minimum target iOS 17. Toolchain: torch 2.7.0, numpy 2.2.6, onnx 1.23.2, Python 3.13.7 —
  `requirements-coreml-export.txt`'s set. The int8 QDQ model was **not** used as a source.
- **Weights-int8 variant.** `linear_quantize_weights`, symmetric, per channel, int8. Activations stay
  fp16.

| Artifact | `.mlpackage` tar | `weight.bin` sha256 | per-face cosine vs ORT fp32 |
|---|---|---|---|
| `auraface_fp16.mlpackage` | 130,638,848 B | `f762c464d5a7de1b4245bf123e7607c831c10a89561b5ef926078fbe147b58de` (130,364,480 B) | **mean 0.99998, min 0.99991** (n = 845) |
| `auraface_w8.mlpackage` | 65,644,032 B | `b85a3ad6c8a3c6c189d052c46f6ae86f6562faa91535a8a5276aadf703716048` (65,347,136 B) | mean 0.99983, min 0.99948 (n = 323) |
| (Android's `auraface_int8.onnx`, for scale) | 66,033,423 B | `1b7b3c18…89a9a` | mean 0.9919, min 0.9755 (n = 149) |

⚠ The tar's own sha is not reproducible, because tar records mtimes. `weight.bin` is the stable
identity; F2's export script must pin its own archive rule, as the SigLIP export does.
⭐ As §3.1 predicted, iOS fp16 is far closer to fp32 than Android's int8 is.

### 10.3 ① Landmarks: Vision vs MediaPipe, in MediaPipe inter-ocular units

Each error is `|Vision − FaceMesh|`, divided by the MediaPipe face's IOD, over the 58 library pairs
(median IOD 114 px, range 28–225). Each cell gives median / p90; the parentheses give the mean bias
along the eye axis and towards the chin.

| Mapping | left eye | right eye | **nose** | mouth left | mouth right |
|---|---|---|---|---|---|
| §3.5 as written: pupils, crest's lowest point | 0.066 / 0.086 (−.006, **−.054**) | 0.071 / 0.106 (+.022, **−.059**) | **0.134 / 0.223** (−.017, **−.068**) | 0.025 / 0.056 | 0.026 / 0.055 |
| ⭐ **corners-mid** (chosen) | **0.026 / 0.045** | **0.025 / 0.056** | **0.050 / 0.154** (+.004, +.005) | 0.025 / 0.056 | 0.026 / 0.055 |
| same, on the public set (88 pairs) | 0.018 / 0.027 | 0.020 / 0.030 | 0.059 / 0.119 | 0.017 / 0.035 | 0.018 / 0.031 |

- **The mapping that matches FaceMesh's definitions** (`FaceMesh.kt`):
  - **eye** = the midpoint of the `leftEye` / `rightEye` contour's two extreme points along the pupil
    axis. That is FaceMesh's corner midpoint, 33/133 and 362/263. Vision's pupils sit ~0.05 IOD
    higher, a definitional offset.
  - **nose** = the midpoint of `noseCrest`'s lowest point and `nose`'s lowest point. The crest bottom
    alone sits 0.068 IOD above FaceMesh's vertex 1. The nose region's centroid is worse (0.137); its
    lowest point alone (the subnasale) is 0.094.
  - **mouth** = `outerLips`' extreme points along the eye axis.
  - `leftPupil` and `leftEye` are the **image-left** eye, so the order needs no per-face sorting.
- ⭐ **No fitted correction is needed.** The mapping was chosen on the public set and holds on the
  library (held out) with ~0 nose bias.
  - For contrast: a fixed nose offset fitted on the public set, (−0.023, +0.063) IOD on the crest
    point, did **not** transfer. Its library median stayed 0.132, because the axis-direction bias
    flips sign between the sets.
- ⚠ **Vision's face `confidence` is 1.0 on all 86 library faces**, so it cannot be the detector floor
  §4 asks F4 to re-choose. `landmarks.confidence` ranges 0.72–0.93, and the verified person's minimum
  is 0.85. On this library a floor is unnecessary for ③. F4 should either take no floor or a
  `landmarks.confidence` floor, and **measure it before choosing**.

### 10.4 ② Two crops, same embedder; ③ the verified cluster

**②** compares, per matched face, the Vision-aligned crop with the MediaPipe-aligned crop, both
through Core ML fp16, over 58 faces:

| | mean | median | p10 | min |
|---|---|---|---|---|
| corners-mid | **0.923** | **0.939** | 0.890 | 0.341 |
| §3.5 as written | 0.897 | 0.913 | 0.858 | 0.333 |

The single outlier (0.34) is a **D** face, outside the verified person. Its detector score, 0.67,
lies inside the range §12.14 gave the cat. ⚠ That reading has not been checked by eye. No other face
falls below 0.80.

**③ The verified cluster at 0.44, on iOS's pipeline** (Vision → corners-mid → Core ML fp16):

| | |
|---|---|
| verified faces (V) in the cluster | **33 of 33**, including all 29 verified photographs |
| **D intruders** (faces MediaPipe saw that are someone else) | **0** |
| U members (Vision-only faces) | **4**, all the verified person by eye (see below) |
| nearest different person (verified cluster × every other cluster of ≥ 2, the harness's definition) | ⭐ **0.176** |
| the same against every non-member face, singletons included | 0.189 |
| reference (MediaPipe + ORT int8, the same definition) | 0.187 |
| stability | V = 33 and D = 0 from **0.20 through 0.52**; split at 0.60 (29 + 4) |

- ⭐ **0.176 ≤ 0.22**, so 0.44 clears the 2× margin rule (`FaceCalibrationTest`) on iOS's pipeline,
  with more room than Android's recorded 0.215.
- The admissible window is ≥ 0.352 by the margin and ≤ the stability edge. On this pipeline the edge
  is **beyond 0.52**: AuraFace fp32/int8 on MediaPipe had fragmented by then (§12.8c).
- ⇒ **0.44 stays. No per-platform threshold, no FD3 override.**
- The same result holds for every mapping tried and for both artifacts:

| Variant | V | D | nearest different person |
|---|---|---|---|
| pupils + crest (§3.5 as written) | 33 | 0 | 0.196 |
| pupils + mid | 33 | 0 | 0.173 |
| corners + crest | 33 | 0 | 0.175 |
| corners-mid on `auraface_w8` | 33 | 0 | 0.178 |

  So the result is not sensitive to the nose definition. The §3.5 nose correction and the fallback
  admissibility rule were therefore not needed.

⭐ **The 4 U members are verified by eye: all four are the verified person** (the maintainer,
2026-11-09). ③ therefore holds with them inside the cluster, and they count as a recall gain over
MediaPipe. What follows is what was known before that check.
- They come from 4 photographs where MediaPipe found **no** face, with small faces of 49–98 px.
- Their cosine to the verified members is mean 0.53–0.55 (max 0.68–0.70). For comparison, the
  verified person's own pairs have median 0.563.
- Their cosine to every known other person is ≤ 0.16.
- By every number they are the verified person seen further away, which is a recall gain. ⚠ But no
  one has looked at them, and §12.5's lesson is that a plausible aggregate is not a verification.
- The file names are recorded in the session scratchpad only, not here. Checking them is part of
  F1b's device pass, or a two-minute look by the maintainer.
- If any one of them is someone else, ③ fails, and this section must be re-read as NO-GO pending
  §3.5's correction.

### 10.5 ④ Recall against Android's faces, and the public set

- **Recall.** Vision finds **58 of the reference's 61** MediaPipe faces (95%), including **all 33 of
  the verified person**, plus **28 faces MediaPipe does not find** (box 39–131 px, mostly small or
  distant).
  - Android's 62 in §12.14 was measured before the largest-box change. Today's pipeline gives 61,
    which §12.15 also records.
  - 19 of the 28 extras fall into clusters of ≥ 2 at 0.44, so Vision would show more people. Their
    identities are unknown, and whether any are non-faces is unknown. ⚠ F1b/F8 owe the look.
  - The 3 faces Vision misses are not the verified person.
- **The public set (§12.2).** Both detectors find 88 of 88, and purity is **1.000** at every swept
  threshold on both pipelines.
  - At 0.44 corners-mid splits one item: id07's **real reference photograph** (column 0), whose mean
    cosine to its own variants is 0.51. It is the recoverable direction. MediaPipe's pipeline splits
    the same photograph from 0.52, and *pupils + mid* does not split it.
  - ⚠ The tiles are square 336 px, so §12.5's warning applies: this set cannot see an aspect-ratio
    defect. The library, with its 4:3 and 16:9 frames in both orientations, is what tests that.

### 10.6 Timings — ⚠ **Mac numbers** (M4 Pro), not the phone's

| Step | Median |
|---|---|
| Vision `VNDetectFaceLandmarksRequest` rev 3, per image at ≤ 2048 px edge | **4.0 ms** (p90 7.3) |
| Core ML fp16 embed per face, `.cpuAndNeuralEngine` | **2.7 ms** |
| Core ML weights-int8 embed per face | 2.4 ms |
| ORT fp32 / ORT int8 embed per face (CPU) | 30.7 / 20.3 ms |
| MediaPipe Python two-stage tiled, per image | 7 ms |
| Core ML load + compile of both packages | 3.5–4.0 s |

None of these is evidence for §6.1's iPhone targets. F1b measures them on the phone.

### 10.7 What this changes in the plan

- **FD1:** Vision (b), with no fallback to (a).
- **FD3:** 0.44 kept, with no override.
- **§3.5:** the mapping is corners-mid, with no correction.
- **§7:** the order is unchanged. F1b is next. Its checklist gains three items:
  - the by-eye check of the 28 Vision-only faces (§10.5); the 4 U members (§10.4) are already
    verified;
  - Vision's points on the phone vs these macOS points;
  - the confidence-floor question (§10.3).
- **F2 inherits:**
  - this conversion route and its toolchain;
  - the `weight.bin` digest as the identity;
  - the 130.6 MB fp16 size for the free-space check.

---

## 11. ⭐ As built: the F1b phone spike (2026-11-09) — **GO**

Everything in this section is **[M]**, and every figure is a ⚠ **phone number**: iPhone 17e (`iPhone18,5`),
iOS 26.6.1 (23G83), `physicalMemory` 8,081,145,856 B (7.53 GiB), on the charger at 100 %, `thermalState` 0
at every start, compute units `.cpuAndNeuralEngine` throughout. No product code was written or committed.
The spike code and the aggregate results are at `~/tenshu-models/face-f1b-2026-11-09`. The photographs,
crops, vectors and landmarks stayed in the session scratchpad, and **the photos were deleted from the
phone** when the run ended (§11.6).

### 11.1 What ran

- **The vehicle.** `inference-harness-ios`'s bundle id and signing, with an uncommitted spike build:
  Swift only, linking Core ML, ImageIO and Vision, and **not** the Kotlin `In3mHarnessKit`. So the
  footprint below is a process holding Vision and three Core ML models. It is not Photos, and not
  `CoreMlInferenceBackend`.
- **The inputs.** F1a's own upright PNGs (§10.1): the 400 photographs of the verified library and the
  88 public tiles. They were side-loaded into the harness container only, never the photo library, and
  the phone checked all 488 sha256s before the first row. ⚠ So decode is a PNG decode, **not**
  `IosImageDecoder`. That is still owed, by F3/F4.
- **The pipeline is F1a's, ported once to Swift.** Vision `VNDetectFaceLandmarksRequest` at
  `VNDetectFaceLandmarksRequestRevision3` → corners-mid (§10.3) → float32 `alignFace` → nearest
  `resample` → `Profile.SIGLIP` CHW → the F1a `.mlpackage`s, compiled on the phone.
  - ⭐ **The port was proved on the Mac first.** The same Swift file, built as a macOS CLI and run over
    the same PNGs, gives crops byte-identical to F1a's Python (174 of 174), vectors at cosine 1.0, Vision
    output identical to F1a's (all 76 points of all 86 faces), and ③ at 0.176 / 0.189 exactly as §10.4.
    So every phone-vs-Mac difference below is the phone's.
- **Rows**, one process each: `face-fp16` and `face-w8` (both sets, Vision + embed); `parity` (the
  Mac's five points re-cropped on the phone); `combined` (SigLIP's two towers **and** the face embedder
  open, one text query, then SigLIP vision on every photo plus the face pipeline, over 5 and over
  15 passes of the library); `cleanup`.

### 11.2 Timings, footprint, thermal — ⚠ phone numbers

| Step (phone) | median | p90 | §6.1 target | Mac (§10.6) |
|---|---|---|---|---|
| Vision rev 3, per image, library (≤ 2048 px edge) | **3.72 ms** | 5.80 | ≤ 60 ms ✅ | 4.0 / 7.3 |
| — same, a lazy `CGImage` as F1a timed it | 3.67 | 5.93 | | |
| — the public set's 336 px tiles | 3.39 | 4.05 | | |
| Embed per face, **fp16** (library / public) | **2.95** / 2.81 ms | 3.63 / 3.01 | ≤ 20 ms ✅ | 2.7 |
| Embed per face, **weights-int8** (library / public) | **2.19** / 2.04 ms | 2.90 / 2.16 | | 2.4 |
| align + resample per face | 0.06 ms | 0.12 | | |
| PNG decode per photo (⚠ not `IosImageDecoder`) | 26.5 ms | 29.3 | | |
| Per photo: decode + Vision + every face, fp16 / int8 | 32.4 / 33.6 ms | 37.6 / 37.6 | | |
| **Combined pass**, per photo: decode + SigLIP vision + Vision + faces (15 passes) | **48.0 ms** | 54.2 | | |
| — of which SigLIP vision predict / Vision / face embed | 7.0 / 4.5 / 3.1 ms | | | |

- **Open.** `compileModel` 109 ms (fp16) and 120 ms (int8). The first open after a compile took 1.88 s
  and 1.64 s; a later open of fp16 took 78 ms. SigLIP's towers took 2.5 s and 3.5 s to open for the
  first time after their compile.
- ⚠ **The first Vision request in a process is slow**: up to 104 ms, against 3–4 ms afterwards. F4's
  preflight should time Vision after one warm-up request [I: the cause is not measured].
- **Footprint** (`phys_footprint`; `os_proc_available_memory` for the process's remaining allowance):

| Point in the combined row, fp16 | footprint | peak |
|---|---|---|
| process start | 8.3 MB | — |
| + SigLIP vision tower open | 16.0 MB | 21.0 |
| + SigLIP text tower open | 15.9 MB | 21.0 |
| + face embedder open | **16.8 MB** | 21.0 |
| after one text query | 17.3 MB | 21.0 |
| over the pass: 5 passes / **15 passes** (6 000 photos) | 84–110 MB | 132.0 / **138.2 MB** |
| the same with the int8 embedder, 2 passes | 96–97 MB | 134.8 MB |
| face embedder alone, fp16 / int8 (face rows) | 21.1 / 11.6 MB after open | 128.5 / 123.0 MB |

  - **Headroom.** `os_proc_available_memory` read 3,368 MB at the start, so the process's allowance on this
    phone is ≈ 3.38 GB. At the pass peak it still read ≈ 3,270 MB. The worst peak, 138 MB, is **≈ 4 % of
    the allowance**.
  - ⚠ **D12 has no footprint threshold to measure against.** D12 ① is `physicalMemory` ≥
    `PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES` = 6.5 GiB. This phone's 7.53 GiB is 1.03 GiB above it. What
    D10/D12 ask for, the footprint of **Photos** through `CoreMlInferenceBackend` with every session
    open, is still owed: this is a Swift harness with no Kotlin runtime, reading PNGs.
  - What it does say: **the face embedder adds ~1 MB after open and nothing visible at the pass peak**
    beside SigLIP's two towers. The ~120 MB the pass adds is per-photo pixels (a 2048-edge RGBA bitmap,
    its RGB copy, the 256² SigLIP input), not the models. That compute-unit placement keeps the
    Neural Engine's weights out of `phys_footprint` is [I].
- **Thermal.** The 15-pass combined row ran **274 s, 6 000 photos and 1 290 faces with
  `thermalState` 0 at every sample** (every 25 photos). First-50 median 42.5 ms, last-50 43.6 ms:
  throttle **1.03**. §6.1's stop (last-50 > 500 ms, or throttle > 2) is nowhere near.
  - ⚠ This was on the charger at 100 %. IN-3m-iOS reached `.serious` after ~4 700 photos with SigLIP
    alone ([D: `ios-on-device-inference.md` §9.7], JPEG decode). Here it did not. The difference
    (input format, room, battery state) is not explained.

### 11.3 Parity with the Mac

**Same crop, two machines.** The Mac's own five points were sent to the phone and re-cropped there; the
crops were byte-identical to the Mac's, 174 of 174:

| Embedder | phone vs Mac cosine, mean | min | below 0.9999 |
|---|---|---|---|
| fp16 | 0.99997 (library) / 0.99998 (public) | **0.999897** | **1 of 174** |
| weights-int8 | 0.999999 | 0.999991 | 0 |
| for scale: phone fp16 `.cpuOnly` vs phone `.cpuAndNeuralEngine` | 0.99977 / 0.99986 | 0.99944 | — |

§7's "≥ 0.9999" holds for int8 everywhere, and for fp16 on 173 of 174 faces; the one exception is
0.999897. The compute units move a vector ~5× more than the machine does.

⚠⚠ **iOS Vision's points are not macOS Vision's.** Both machines ran the same PNG pixels at the same
revision. The phone is deterministic: two processes gave identical points for 86 of 86 faces. But it is
not the Mac:
- **Same faces.** 86 of 86 library faces match (box IoU ≥ 0.5), with none extra and none missing. Face
  `confidence` is 1.0 on every face on both machines, and `landmarks.confidence` differs by at most 0.006.
- **Different points.** Error divided by the Mac face's inter-ocular distance (IOD), library:

| | left eye | right eye | nose | mouth left | mouth right |
|---|---|---|---|---|---|
| median | 0.0017 | 0.0015 | 0.0015 | 0.0017 | 0.0017 |
| p90 | 0.010 | 0.015 | 0.011 | 0.011 | 0.012 |
| max | 0.056 | 0.094 | 0.112 | 0.053 | **0.818** |

  - The 0.818 is one face with a 13 px IOD.
  - Over all 76 points, the largest error a face has is median 0.004, p90 0.025, max 0.118 IOD.
  - **No face** is identical. Boxes differ by a median 0.028 IOD, max 0.32.
- **On the public set's 336 px tiles** the two are nearly the same: pooled five-point median 0.0001 IOD,
  max 0.022. So the difference grows with the image. That it comes from internal scaling or the compute
  path is [I].
- **Scale.** The phone-vs-Mac median is ~15× smaller than §10.3's Vision-vs-MediaPipe 0.026. The tails
  are not.
- **End to end** (each machine's own Vision, align and embed), cosine phone vs Mac: library median
  0.988, mean 0.979, min 0.856, with 7 faces below 0.95 and 3 below 0.90. Public median 0.9997.
- ⇒ A Vision output recorded on macOS is **not** a golden for iOS Vision. F3's mapping fixtures
  (Vision regions → five points) are pure and unaffected. A test that compares Vision's own output across
  platforms must not exist. FD9 (the OS build in the face key) is supported: Vision's output depends on
  the platform it runs on.

### 11.4 ③ re-checked on the phone's outputs

The phone's own Vision → corners-mid → embed, clustered at 0.44. Labels come from F1a through each face's
macOS twin (§10.1): **V** = verified, **D** = someone else, **U** = Vision-only; **U\*** = the 4 the
maintainer confirmed by eye.

| | phone fp16 | phone int8 | Mac fp16 (§10.4) |
|---|---|---|---|
| verified cluster | 37: **33 V** (all 29 photographs) + **4 U\*** | the same | the same |
| D intruders / unverified U | **0 / 0** | 0 / 0 | 0 / 0 |
| nearest different person (other clusters of ≥ 2; every non-member) | **0.197 / 0.197** | 0.199 / 0.199 | 0.176 / 0.189 |
| stability | whole from 0.20 through 0.52; split at 0.60 (29 V + 4 U\* stay together, 4 V leave) | the same | the same |
| public set at 0.44 | purity 1.000, 1 split | the same | the same |

- ⭐ **③ holds on the phone**: 0.197 ≤ 0.22. So 0.44 clears the 2× rule there too.
- ⚠ **The margin narrowed**: 0.023 below the 0.22 bar on the phone, against 0.044 on the Mac and 0.005
  for Android's 0.215. That §11.3's point drift is the cause is [I].
- ⇒ FD3 stands: **keep 0.44**. ⚠ Any iOS calibration figure (F5) carries the **phone's** 0.197, not the
  Mac's 0.176. A floor-class phone or another OS build could move it again [I]; F8 re-measures it.

### 11.5 What the phone did differently from the Mac

- **The points and the boxes** (§11.3). This is the one real difference.
- **Revisions.** These match the Mac. Landmarks supports [1, 2, 3], with default = current = 3, and
  revision 3 was used on every image. Rectangles supports [1, 2, 3]. The constellation is always
  76 points.
- **Confidences.** These match too. Face `confidence` is 1.0 on all 86 library faces, and
  `landmarks.confidence` runs 0.72–0.93. §10.3's floor question is unchanged; F4 decides it.
- **Timings** are within ~10 % of the M4 Pro's (§11.2). The first-request warm-up is the only surprise.
- **Disk.** Core ML leaves an E5 bundle cache under
  `Library/Caches/<bundle>/com.apple.e5rt.e5bundlecache/<OS build>/` that outlives the deleted models.
  Its size was not measured. It belongs to D14's *where is the first-open space kept* question.

### 11.6 Still owed, and housekeeping

- `IosImageDecoder` and HEIC on the face path (the input here was PNG): F3/F4.
- ⚠ The **by-eye check of the 28 Vision-only faces** (§10.5) is still the maintainer's. The spike looked
  at no image.
- Photos' own footprint through `CoreMlInferenceBackend` (D10, D12 ①), a floor-class phone, and a
  run on battery.
- **The phone.** The photos, vectors, outputs and models were deleted from the harness container by the
  spike's own cleanup row; `devicectl` cannot delete. The container listing afterwards holds no image
  and no vector file, only system caches. ⚠ The harness on the phone is now the F1b spike build:
  `run-in3m.sh build && run-in3m.sh install` before the next IN-3m-iOS run.
- `devicectl device copy to` **does** take a directory, which `ios-on-device-inference.md` §4.4 left as [I].

### 11.7 What this changes in the plan

- **FD5:** fp16 stays. Footprint and speed give int8 no case; only its 65 MB smaller download remains.
- **FD6:** no face-specific memory number.
- **FD3:** 0.44 stays, with the phone's 0.197 as the iOS figure.
- **§9:** the macOS = iOS Vision assumption is struck.
- **F3:** no cross-platform golden of Vision output.
- **F4:** the Vision probe checks finite points inside the box, never equality with recorded points;
  it times Vision after a warm-up request.
- **§7:** the order is unchanged. **F2 is next.**

---

## 12. ⭐ As built: F2, the export and the catalog entry (2026-11-10)

No client code changed, so no smoke and no iOS lane is owed. ✅ **Signed and published by the
maintainer on 2026-11-10** (§12.5).

### 12.1 What shipped

| Piece | Where |
|---|---|
| The export: pinned source, pinned toolchain, reproducible archive, self-check against fp32 ORT, export manifest | `scripts/inference/export-coreml-face-embedder.py`, `requirements-coreml-face-export.txt` (includes the SigLIP export's set) |
| Its reproducibility rules and refusals, with no model | `scripts/inference/test_export_coreml_face_embedder.py` (10 cases, stdlib only) |
| The entry: id `auraface-v1-int8`, `version` 1, runtime `coreml+vision`, one pinned `faceEmbedder`, `source` | `deploy/models/models.json` |
| The entry against the shipped runtime sets and the signer's rules | `FaceCatalogEntryTest` (`:inference-service-proxy` jvmTest) |
| The provenance, the derivative note, the runbook | `docs/models.md` §1, §1.1, §5.4; the counsel packet §4; `docs/how-to/models-pipeline.md` §2, §6.2; `deploy/models/README.md` |

**The artifact** [M]:

| | |
|---|---|
| Path | `auraface-v1-int8/1/coreml/auraface_fp16.mlpackage.tar` |
| sha256 | `d503f893c01153ae9fbc241973c797578b537d9dd4e6962d3326ec71a5c3b80c` |
| Size | 130 631 680 B |
| `weight.bin` | `f762c464…b58de`, 130 364 480 B: **byte-identical to F1a's** (§10.2), so F1a's and F1b's measurements are of these weights |
| Export manifest | `984e39d3d798f90ef74983a76cd1edf2de7272a5f79e579860ddd75d1fffd87f`, kept with the archive at `~/tenshu-models/coreml-face-export-984e39d3d798/` |
| Source | `fal/AuraFace-v1` at `af6d057c9b0ec4071d4c49c80e3539258798b609` (the full commit, resolved from the short `af6d057c`; it is the repo's head), `glintr100.onnx` sha256 `a7933ea5…25c60`, 260 694 151 B. The Hub's LFS record for that file at that commit names the same digest and size |
| Toolchain | coremltools 9.0, torch 2.7.0, numpy 2.2.6, onnx 1.23.2, onnx2torch 1.5.15, onnxruntime 1.31.1, pillow 13.0.0, Python 3.13.7, `pinned: true` |
| I/O | `input` Float32 `[1, 3, 112, 112]`, `embedding` Float32, 512 wide: F1a's names, which F1b's harness used |
| Self-check | the archive, unpacked and loaded under `CPU_AND_NE`, against the fp32 source on ORT's CPU provider: 3 fixed synthetic inputs, mean 0.999928 / min 0.999927; Vec2Face's 80 public synthetic faces, mean 0.999987 / min 0.999980. Floor 0.999 |

⚠ The tar is 7 168 B smaller than F1a's (§10.2's 130 638 848 B), and only `weight.bin` is shared with
it. F1a's tar was written by another tool with real times, and its `model.mlmodel` carries other
metadata (a spike description, no licence or author) in coremltools' own byte order.

### 12.2 ⭐ Reproducibility, and how it was got

F1a's tar was not reproducible, and neither are SigLIP's (`ios-on-device-inference.md` §9). The first
two runs of the script with zeroed tar times still gave **different** digests. The script's per-file
digests said which file moved: `model.mlmodel`, with `weight.bin` and the canonical `Manifest.json`
identical [M].
- The two `model.mlmodel` files parse to **the same message**. Their protobuf text forms are
  identical; only the bytes differ, because protobuf leaves map order unspecified.
- Re-serialising each with `SerializeToString(deterministic=True)` gave one digest for both [M].

So three things are fixed before the tar is written:
1. **the tar's times, owners, modes and order** — `write_tar`, shared with `export-coreml-towers.py`;
2. **`Manifest.json`'s item ids**, random UUIDs per save, replaced by a UUIDv5 of each item's path;
3. **`model.mlmodel`**, re-serialised deterministically. The script refuses a rewrite whose parse
   differs from what coremltools wrote.

**Evidence** [M]: two runs of the final script, in separate processes, on the pinned toolchain: both
archives `d503f893…c3b80c`, `cmp` identical, and both export manifests `984e39d3…fd87f`. The self-check
loads the canonical package through Core ML, so the rewritten ids and spec load.
⚠ Reproducible means **on that toolchain**. Another coremltools or torch traces another graph, and the
digest moves with it. A re-export on a new set is a new artifact at a new path (runbook §6).

### 12.3 The entry, and why each choice

- **Same id `auraface-v1-int8`, same `version` 1** (FD4). One id is one checkpoint across runtimes,
  as SigLIP's two entries are (`ios-on-device-inference.md` D13). Renaming Android's id would
  re-index every Android library post-release. ⚠ The `-int8` is wrong for an fp16 file and is kept
  as a recorded wart.
- **Runtime `coreml+vision`, never bare `coreml`.** Photos on iOS contributes `{"coreml"}` whenever
  AI-L is on (`PhotosOnDeviceAi.kt`). A bare-`coreml` face entry would offer that build a ~130 MB
  download nothing on it can run.
- **One artifact.** Vision is a system framework, so there is no detector file.
- **Pinned `sha256` and `byteSize`**, as the SigLIP Core ML entry is: the signer refuses other bytes
  under that path. Here the pin names a reproducible export, so a re-run of the script on the pinned
  toolchain re-creates exactly the pinned bytes.
- **`source`** is the export manifest's, field for field. `sourceBytes` stays out: the signer's
  manifest parse is strict and `ModelSource` has no such key.
- ⚖️ **The derivative note** (FD13): `models.md` §5.4's clause (c) now names format conversion, and
  the counsel packet's §4 has the "same weights, a second format" line. Nothing was re-decided. The
  model's own metadata carries the Apache-2.0 §4(b) changed-file notice.

### 12.4 Guards, each removed once and seen red

Each run was `:inference-service-proxy:jvmTest --tests '*catalog*' --rerun` (⚠ `--rerun` is needed:
`models.json` is not a declared input of the test task, so without it a manifest edit runs nothing
and the mutation reads as green), plus `:model-signer:test` for the signer's line. All three edited
files were hashed before and after, and all three were restored [M].

| Removed or changed | Went red |
|---|---|
| `models.json`: the iOS entry's runtime written as bare `coreml` | every `FaceCatalogEntryTest` case, and `CoreMlCatalogEntryTest.shippedBuildsSeeTheOnnxEntryAndOnlyPhotosOnIosSeesThisOne` (Photos would be offered the face archive) |
| `models.json`: the archive's sha256 pin deleted | `…theIosArchiveIsPinnedAndNamesItsSource` |
| `models.json`: the iOS entry's `version` written as 2 | `…theIosEntryIsTheSameCheckpointUnderItsOwnRuntime`, `…theSignerAcceptsTheManifestAndRefusesASecondIosFaceEntry`, `CoreMlCatalogEntryTest.theManifestPassesTheSignersOwnChecks` |
| `models.json`: `source.files` naming another digest | `…theIosArchiveIsPinnedAndNamesItsSource`, `…theSignerAccepts…`, `CoreMlCatalogEntryTest.theManifestPassesTheSignersOwnChecks` |
| The signer's duplicate `(id, runtime)` refusal | `…theSignerAcceptsTheManifestAndRefusesASecondIosFaceEntry`, `ModelCatalogBuilderTest.theSameIdTwiceUnderOneRuntimeIsRefused` |
| The client's runtime filter (`usableModels`) | `…shippedBuildsFilterTheIosEntryOutAndAFaceBuildKeepsExactlyOne`, `…itIsUsableOnAndroidAndFilteredOnDesktop` |
| Script: the UUIDv5 rewrite; the root check; the duplicate-path check; the source pin (four runs) | the Python cases for each: the two-digest cases, `…a_root_that_names_no_item…`, `…two_items_at_one_path…`, `…not_the_pinned_checkpoint…` |

⚠ Not guarded by a test: `canonical_spec` needs coremltools, so it is exercised by the export itself
(§12.2), not by the stdlib cases.

### 12.5 The publish, and what was owed

- ✅ **Published by the maintainer, 2026-11-10** [M, verified from outside the build]: the live
  `catalog.json` has sha256 `2d6be7d4…3085`, byte-identical to the signed build; `catalog.json.sig` is
  live; `auraface-v1-int8/1/coreml/auraface_fp16.mlpackage.tar` answers 206 and hashes to `d503f893…80c`
  (§12.1); Android's `auraface_int8.onnx` is unchanged (`1b7b3c18…`). So the face entry under runtime
  `coreml+vision` is in the live catalog.
- The block was `docs/how-to/models-pipeline.md` §6.2. It was rehearsed with a throwaway key [M]: the diff against the live catalog is the one added block
  `113a114,152`, and `DRY_RUN=1 scripts/models-publish.sh` printed eight artifacts, then the catalog,
  then its signature.
- **Publishing is safe before F5, and offers the entry to nobody.** No build contributes
  `coreml+vision` (`FaceCatalogEntryTest`): Android and desktop drop it, and so does Photos on iOS
  with AI-L on.
- F4's backend must read the archive by the names in §12.1 (`input`, `embedding`) and guard the
  512 width and finite, non-zero output, as the self-check does.
- F5 contributes the runtime. `FaceCatalogEntryTest` now reads `FaceModel.COREML_VISION_RUNTIME`
  (F3, §13), the constant F5 should contribute.
- The kept export was one copy on one disk until the publish; it is now on the CDN too. Unlike
  SigLIP's it can be re-made byte for byte, from `glintr100.onnx` and the pinned toolchain.


---

## 13. ⭐ As built: F3, the shared maths (2026-11-10)

No stored or wire shape changed: `FaceModel` is an in-process value, and `VisionFaceLandmarks` and the
resample are functions. So no HR 9 bump. Nothing is bound into any graph; that is F5.

### 13.1 What shipped

| Piece | Where |
|---|---|
| The resample, shared: nearest, pixel centres, `FaceCrop.unmap`, `toInt()`, border clamp. A reader overload (Android reads its `Bitmap` in place, so no 16 MB copy per face) and a packed-ARGB `IntArray` overload (iOS) | `resampleFace`, `FaceResample.kt` (commonMain); `MediaPipeFaceBackend.resample` now delegates |
| ⭐ The golden, recorded on Android's own resample **before** the move | `MediaPipeFaceResampleGoldenTest` (Android host, Robolectric): commit *"golden test of Android's face resample, before it moves"*, green there, then **byte-unchanged** and green after the move. `FaceResampleTest` (commonTest, every lane) holds the shared function to the same numbers, copied, never re-recorded |
| The Vision mapping: §10.3's *corners-mid*, no correction, y-flip inside | `VisionFaceLandmarks` (commonMain). Input is plain `pointsInImage` pixels (bottom-left origin) per region, so no Vision type reaches common code |
| Its fixtures: one face per public-set identity, the most tilted of each, plus F1a's own `vision_five` answer and the matched MediaPipe five | `VisionFaceLandmarksFixtures` (commonTest), generated from `~/tenshu-models/face-f1a-2026-11-09` |
| The iOS model and stamp | `FaceModel.AuraFaceCoreMl` (same id and version as Android, FD4), `FaceModel.COREML_VISION_RUNTIME`, `FaceModel.VISION_PIPELINE_VERSION = 100` |
| `FaceCatalogEntryTest` reads the constant instead of the literal, and pins the iOS entry to `AuraFaceCoreMl`'s id and version | jvmTest |

- ⚠ **`:inference-service-proxy` now has an Android host-test source set**, and the default hierarchy runs
  all of commonTest there too. That surfaced `List.removeFirst()` in `ModelPreparerTest`: a JDK 21 member
  that the Android runtime lacks. It now reads `removeAt(0)`.
- ⚠ **The tests check the mapping's maths, never Vision's output** (§11.3). The fixtures are macOS Vision's
  points used as *input*. The expected values are F1a's numpy reference on those points (within 5e-5 of
  the image), plus a semantic bound against MediaPipe: eyes and mouth < 0.08 IOD, nose < 0.2.
- Rotated faces: every fixture face rotated by 23°, 90°, 180°, −120° and 271° in Vision's frame maps to
  the upright answer rotated the same way. After `alignFace`, its five points land within 0.01 px of the
  upright face's template positions. The axes come from the face, so the mapping needs no per-face
  sorting.
- Still owed, unchanged: `IosImageDecoder` and HEIC on the face path (F4).

### 13.2 Guards, each removed once and seen red

Each change was run with `jvmTest` (and `testAndroidHostTest` where marked) with `--rerun`. The file was
hashed before and after, and every file was restored.

| Removed or changed | Went red |
|---|---|
| `resampleFace`: `toInt()` → `round().toInt()` (host too) | 4 of `MediaPipeFaceResampleGoldenTest` (only the degenerate case survives) and the same 4 of `FaceResampleTest`, on JVM and on the Android host |
| `resampleFace`: no pixel centre (`x` instead of `x + 0.5`) (host too) | the same 12 |
| `resampleFace`: the buffer-size check | `aBufferThatIsNotTheStatedImageIsRefused` |
| `VisionFaceLandmarks`: no y-flip | `theFixturesMapToF1asCornersMidReference`, `theFixturesLandOnMediaPipesPoints`, `visionsBottomLeftOriginIsFlippedToTopLeft`, `theNoseUsesTheChinEnds…`, `aRotatedFace…` |
| eyes = the pupils, not the corner midpoints | the reference, the MediaPipe bound, the flip case |
| nose = the crest's bottom alone | the reference, the MediaPipe bound, both flip cases |
| the chin axis reversed | the reference, the MediaPipe bound, both flip cases |
| the mouth found along the image's x axis, not the face's | `aRotatedFaceMapsToTheRotatedPointsAndAlignsTheSame` |
| no refusal of a degenerate axis | `regionsThatCannotCarryTheMappingAreRefused` |
| `VISION_PIPELINE_VERSION = 22` (host too) | `FaceModelTest.theIosPipelineStampIsOutsideAndroidsRange` (JVM and host), `FaceDetectionStrategyStampTest.noAndroidStrategyReachesTheIosRange` |
| `AuraFaceCoreMl.modelVersion = 2` | `FaceModelTest.theCoreMlModelIsTheSameCheckpointAsAndroids`, `FaceCatalogEntryTest.theIosEntryIsTheCoreMlFaceModel` |
| `COREML_VISION_RUNTIME = "coreml"` | 8 `FaceCatalogEntryTest` cases, and `FaceModelTest.theCoreMlModelIsTheSameCheckpointAsAndroids` |

---

## 14. ⭐ As built: F4, the iOS backend, its readiness and its preflight (2026-11-10)

**Unbound.** No graph contributes `coreml+vision`, binds the backend or the face readiness, or reads a
flag: that is F5. No stored or wire shape changed (the verdict file is device-local JSON, never a
schema's), so no HR 9 bump.

### 14.1 What shipped

| Piece | Where |
|---|---|
| The backend: decode → Vision rev 3 → `VisionFaceLandmarks` (on the **oriented** decode's size) → `alignFace` → `resampleFace` (packed ARGB) → `Profile.SIGLIP` → Core ML `input` `[1,3,112,112]` → `embedding` 512 | `VisionCoreMlFaceBackend` (iosMain, `coreml/`), `CoreMlTowerSpec.faceEmbedder` |
| Its stamp: `AuraFaceCoreMl.provenance` with `labelSetVersion = VISION_PIPELINE_VERSION` (100), the same on every vector | `VisionCoreMlFaceBackend.provenance` |
| Vision, pinned: `VNDetectFaceLandmarksRequestRevision3` set on every request; the revision Vision reports it ran is checked by the backend (`Failed` otherwise) and by the preflight judge; a BGRA `CVPixelBuffer` of the decoded pixels themselves, so Vision and the crop read one image (§14.4: why not a CGImage) | `SystemVisionFaceDetector`; `FaceModel.VISION_LANDMARKS_REVISION = 3`, pinned beside 100 by `FaceModelTest` (FD9) |
| The face decode: Android's power-of-two bound at 2048, upright through ImageIO's EXIF transform, or an explicit orientation (1–8) applied over the stored pixels; sRGB | `IosFaceImageDecoder`, `faceDecodeEdge`, `orient`; `IOS_FACE_PREPROCESSING_REVISION = 1` |
| Readiness per model (FD7) | `ReadinessSlot` (`SIGLIP`, `FACES`) on `AiReadiness`; `PreparedModel` keyed by role; `RuntimeFingerprint.detectorRevision` |
| The bundled CC0 face and the host reference | `scripts/inference/face-probe-photo.jpg` → `FaceProbePhoto` (iosMain); `FacePreflightHostReference_984e39d3d798` (commonMain); simulator-only stand-in for Vision's points `FaceProbePhotoMacVision` (iosTest) |
| The face key: embedder digest (in `visionArchiveSha256`), no text tower, no vocabulary, compute units, **OS build** (FD9), face decode revision, Vision revision, app build, `FACE_PREFLIGHT_REVISION` | `iosFacePreflightKey`, `iosFacePreflightKeyOf`, `iosFaceRuntimeFingerprint`; facts: `coreMlFaceModelFacts` |
| The preflight: `Preflight`'s protocol for any model, the model's half a `PreflightProber` | `Preflight.over(…)`, `PreflightProber`/`PreflightRun`; SigLIP's half `SiglipPreflightProber` (moved, not changed); the face half `FacePreflightProber`, `FacePreflightJudge`, `FacePreflightProbe`, `FaceProbeImages`; iOS models `CoreMlFacePreflightModels` |
| How F5 binds it | `faceBackendOver(readiness)`: paths and claims from the face readiness only, every open recorded through `servingOpen` |

**Decisions taken here** (each a new `VISION_PIPELINE_VERSION` if changed):
- **No confidence floor and no de-duplication of our own.** F1a and F1b ran neither; Vision returned
  confidence 1.0 on all 86 library faces and no doubled box (§11.3). §4's "same caps as Android" is
  kept for the cap alone: the **largest 10** faces.
- A face whose regions cannot carry the corners-mid mapping is left out (it cannot be aligned).
- `detectFaces` answers `Failed` when the backend does not serve, Vision throws, or readiness moved
  under the request; `Unreadable` only for a decode refusal; `Detected(empty)` only when Vision ran.

### 14.2 The probe (FD12): one licensed face, and a field that needs none

**The face** — approved by the maintainer for this purpose, CC0 only, fetched once from
`upload.wikimedia.org` through its Commons page:

| | |
|---|---|
| Commons page | https://commons.wikimedia.org/wiki/File:Selfie_image_of_Cecile_McKee.jpg |
| Author | `User:Cecilemckee` — the person pictured; source `{{own}}`, a selfie (2024-03-18) |
| Licence | **`{{self|cc-zero}}`** on the file page (CC0 1.0, `LicenseShortName` CC0) |
| Retrieved | 2026-11-10 |
| Original | 1969 × 1983 JPEG, 741 161 B, sha256 `4f91fff192d8d0a25309c75aba58ed7653842ef5ba0ffb6fe3f28a50fe34bcf6` (sha1 `cb99b607…f2f6`, matching Commons' own) |
| Committed | `scripts/inference/face-probe-photo.jpg`: 254 × 256 JPEG (`sips -Z 256`, quality 85, then `exiftool -all=`: no EXIF, no ICC), 19 933 B, sha256 `3c49a0b75ed61d862b2b7f3d44dab36f78f26fa94c3b1ba63b0b9f1c5e095772` |
| In the app | `FaceProbePhoto` (iosMain), the same bytes as base64 — a K/N binary carries no resources; `theBundledFaceIsTheCommittedFile` pins the sha |

An adult, frontal, evenly lit; released by the photographer, who is the subject. Not Vec2Face, not a
well-known person.

**Four rules** (`FacePreflightJudge`), on top of D12's verdict rules, unchanged:
- **Detector (§6.2):** Vision, at revision 3 as **reported by Vision** (`VisionDetection.revisionRun`),
  finds **≥ 1 face** in the bundled photo, each with a finite box and five finite points inside it
  (±25 % of the box). Timed after one warm-up request, as F1b asked.
- **Embedder, on the face:** the largest face, aligned and cropped as the backend crops one, sits
  closer to its own mirror image than to the chequer pattern by `faceMinMargin`.
- **Embedder, with no face:** the two-augmentation self-consistency rule on `FaceProbeImages` (a smooth
  field, it 12 levels brighter, it 3 px shifted; an unrelated chequer), by `minMargin`. Pixels pinned:
  sha256 `851dc40b79ebf2476a15f87509021ebf786703179217e4458e7955cb6e55d2a5`.
- ⭐ **"Correct", not only "not broken":** every timed vector of the field must sit at
  **cos ≥ 0.99 to the host's own vector** for it (`FacePreflightHostReference_984e39d3d798`, all 512
  values, sha256 `03fe882e…4b7039`). A numerics drift that keeps vectors smooth but turns them passes
  every ranking and norm, and still moves every cosine clustering compares with 0.44. Why 0.99: F1b
  measured the same crop phone vs Mac at ≥ 0.999897 (fp16) and the phone's `.cpuOnly` vs
  `.cpuAndNeuralEngine` at ≥ 0.99944 (§11.3); 0.99 is ~10× that spread. Only the Vision-free field is
  pinned: a face's crop follows Vision's points, which differ by platform (§11.3, end-to-end down to
  0.856), so a pinned face vector would fail honest phones.

**Host figures [M]** (iOS 26 simulator on the M4 Pro build Mac, the F2 archive `d503f893…`):

| | value | floor |
|---|---|---|
| field: cos brighter / shifted / other | 0.98186 / 0.99227 / 0.20292 → margin **0.7789** | 0.389 |
| field: raw norm | 8.763 | ×1.5 band |
| face (macOS Vision's points, see §14.4): cos mirror / other | 0.85539 / 0.00156 → margin **0.8538** | 0.427 |
| field vs the recorded host vector | 1.0 | 0.99 |

⚠ No phone has run the probe.

### 14.3 Guards, each removed once and seen red

25 guards. Mutations applied by a script, one at a time, the file hashed before and restored after each run
(`jvmTest` / `iosSimulatorArm64Test --rerun`).

| Removed or changed | Went red |
|---|---|
| `detectFaces`: no serving check before the decode | `aBackendThatIsNotReadyLooksAtNothingAndEmbedsNothing` (first run stayed green — the later check masked it; the case now also asserts nothing is decoded or run) |
| `detectFaces`: serving not re-asked after Vision | `aTeardownUnderTheDetectorRecordsNothing` |
| a Vision error answered `Detected(empty)` | `aVisionErrorIsFailedNeverDetectedEmpty` |
| `embedFace`: the finite / non-zero / width guard | `aNonFiniteOrZeroOrWrongWidthVectorIsRefused` |
| `embedFace`: readiness not re-asked after the prediction | `aTeardownUnderThePredictionReturnsNoVector` |
| `supports` without `ready()` | `itClaimsFacesOnlyWhileReadyAndWithAnEmbedder` |
| the bytes cache keyed on content, not identity | `equalBytesInAnotherArrayAreDecodedAfresh` |
| the bytes cache not keyed on the bytes | `aFaceIsNeverCroppedFromAnotherPhotographsPixels`, `equalBytesInAnotherArrayAreDecodedAfresh` |
| the rejected-archive latch | `anArchiveThatIsNotTheEmbedderIsLatchedForItsPathAlone` |
| Vision's box y-flip | `visionsBottomLeftBoxIsFlipped` |
| EXIF 6 mapped as 1 | `everyExifOrientationIsAppliedAsDisplayed` |
| the largest-10 cap | `aFaceWithoutLandmarksIsLeftOutAndTheLargestTenAreKept` |
| `ReadinessSlot.FACES` on SigLIP's verdict path | `aFaceFailureLeavesSearchReady…`, `aSearchFailureLeavesFacesReady…`, `deletingTheFaceModelLeavesSearchServing`, `everySlotHasItsOwnFiles…`, `threeSlowFaceProbes…AndSearchIsUntouched` |
| settled opens reading SigLIP's roles, not the slot's | `aFaceFailureLeavesSearchReadyAndItsFileUntouched` |
| face judge: the Vision revision check | `aDetectorErrorOrAnotherRevisionIsAFailureNeverUnsuitable` |
| face judge: a reported face's five points | `aDetectedFaceMustCarryFivePointsInsideItsBox` |
| face judge: the unusable-vector check | `aNonFiniteVectorIsAFailureNeverAPass` |
| face judge: the ranking margin | `anEmbedderThatDoesNotRankTheProbeImagesFails` |
| the warm-up counted in the detector's timing | `theDetectorAndTheEmbedderAreTimedAfterAWarmUp` |
| face judge: ≥ 1 face in the bundled photo | `aDetectorThatFindsNoFaceInTheBundledFaceFails` |
| face judge: the field vs the host's recorded vector (cos ≥ 0.99) | `aFieldVectorTurnedAwayFromTheHostsFailsThoughItStillRanks` |
| face judge: the face vs its mirror margin | `theFaceAndItsMirrorAreEmbeddedAndMustRankAboveThePattern` |
| backend: the revision Vision reports it ran | `aVisionThatRanAnotherRevisionIsFailed` |
| `faceBackendOver`: refuses a readiness that is not the face slot | `theFaceBackendRefusesSearchsReadiness` |
| `FacePreflightProbe.isFor`: the no-text-tower check | `theShippedFaceProbeIsTheF2ExportsAndIsMeasured` (first run green — a vocabulary also refused the key; the case now tests each half alone) |

**SigLIP, unchanged:** `AiReadinessTest` (40), `PreflightTest` (112), `CoreMlReadinessTest` (22),
`PhotosOnDeviceAiTest` (40), `PhotosModelDownloadTest` (19), `PreflightJudgeTest`,
`PreflightRevisionPinTest` green with **no line of those files changed**: the old constructor,
`PreparedModel(vision, text, vocabulary)`, `.copy(vocabularyPath = …)` and the verdict paths all
survive as they were.

### 14.4 On the simulator, real Vision and the real archive [M]

⚠ The build Mac's simulator, not a phone.
- ⚠⚠ **Vision via a `CGImage` finds no face on the simulator** — the bundled face, 0 faces through every
  CGImage route tried (a bitmap context in four pixel layouts, ImageIO's own image, with and without
  `usesCPUOnly`), 1 face through a `CVPixelBuffer` and through encoded data. So
  `SystemVisionFaceDetector` hands Vision a BGRA pixel buffer of the decoded pixels — the camera's own
  input type, one copy. (F1a/F1b used a CGImage on macOS and on the phone, where it works.)
- ⚠⚠ **The simulator's Vision landmarks are not the face's.** Its box matches macOS Vision's
  (IoU > 0.8, asserted), but its points are wrong and differ run to run (one run: both pupils within
  6 px on a 140 px face, at the face's centre; macOS: 62 px apart). Same with `usesCPUOnly`. So on
  the simulator the five-points rule is not asserted on live Vision; it is asserted on **macOS Vision's
  recorded answer** for the same image (`FaceProbePhotoMacVision`, by F1a's `vision_detect.swift`), and
  the face host margin was measured on that. ⚠ The five-points rule on live iOS Vision is the phone's
  to pass (F8).
- Vision **runs on the simulator at revision 3**: first request ~350–415 ms, then ~51–67 ms (the phone
  measured 3–4 ms after a warm-up, §11.2).
- A 1600×1200 EXIF-6 JPEG through `IosFaceImageDecoder` → Vision: `Detected(empty)`.
- The whole face preflight on the real F2 archive, the real decode of the bundled face and macOS
  Vision's points, on a `ReadinessSlot.FACES` readiness: **Passed**, `Ready`. Embedder open 24 ms,
  embed ~29 ms median (warm-up 28 ms), footprint 68 → 69 MB.
- Run: `./gradlew :inference-service-proxy:iosSimulatorArm64Test -Dtenshu.inference.coremlFaceExport=<export dir>`
  (`VisionCoreMlFaceRealTest`; the archive cases skip without it).

### 14.4a Parity with Android, and two traps for F5

- ⚠ **Which faces are kept differs from Android in crowds.** iOS keeps the 10 largest Vision faces
  by box area, with no confidence floor of its own. Android asks MediaPipe for `setNumFaces(10)` with
  `minConfidence` 0.5 and de-duplicates by largest box. In a photo with more than 10 faces, or faint
  ones, the two keep different faces. F1a/F1b measured no photo where it mattered (≤ 10 faces, all
  confidence 1.0). Changing it is a new `VISION_PIPELINE_VERSION`.
- ⚠⚠ **F5: never SigLIP's custody for the face readiness.** `applyFingerprint` is a lambda; a face
  readiness handed `InferenceStoreCustody.applyFingerprint` over `/inference/runtime.fingerprint` would
  write the face key's fingerprint there and clear every search vector. `ReadinessSlot` now carries
  `fingerprintPath` (`/inference/faces.runtime.fingerprint` for faces), and `faceBackendOver` refuses
  any readiness that is not `ReadinessSlot.FACES` (`theFaceBackendRefusesSearchsReadiness`). The
  custody bound to the face slot over its `fingerprintPath` is F5's to build.
- The decode cache holds one photograph, up to ~12–16 MB ARGB, until the next photograph or
  `closeSessions`: the price of decoding once for all of a photograph's faces.

### 14.5 Still owed

- ⚠ The five-points rule on **live iOS Vision** (the simulator's landmarks are wrong, §14.4), and a
  recall/parity run of the backend over F1a's public images: only their landmark JSON is on disk.
- The phone: the probe's margin, Vision's first-request cost and the embed time under the Neural Engine.
- HEIC on the face path (the decode is ImageIO's, as SigLIP's; not exercised here).
- F5: the binding, the flag, `coreml+vision`, A40, the face custody's `applyFingerprint` (the face key's
  runtime half carries the OS build and Vision revision, so an OS update clears templates, FD9).

---

## 15. ⭐ As built: F5, the binding, the flag and A40 (2026-11-10)

**Bound, and off.** Both flags are `NO` in `photos-ios/project.yml`; no shipped build binds anything new.
No stored or wire shape changed (the face fingerprint is device-local JSON beside SigLIP's), so no HR 9
bump; `SchemaShapeBaselineEnforcementTest` is green with no regeneration.

### 15.1 What shipped

| Piece | Where |
|---|---|
| The flag (FD10): `TENSHU_PHOTOS_FACES` → Info.plist `TenshuPhotosFaces`, only the exact `YES` | `photos-ios/project.yml`; `PhotosLocalRuntime.FACES_INFO_KEY`, `facesBuildFlag`; `OnDeviceAiInputs.facesBuildFlag` (default off) |
| The decision: face flag **and** the AI flag **and** both static gates, once at assembly | `PhotosOnDeviceAi.facesAdmitted`; `PhotosLocalRuntime.assemble` (`Assembly.faces`); `photosOnDeviceAiModule(root, faces)` — the module itself is only included for an admitted phone |
| The binding: `inferenceBackends = [CoreMlInferenceBackend, VisionCoreMlFaceBackend]`; `supportedModelRuntimes = {coreml, coreml+vision}` | `PhotosOnDeviceAi.kt` |
| The face readiness, `named(FACE_READINESS)`, `ReadinessSlot.FACES` | `coreMlFaceReadiness(…)` (iosMain): facts `coreMlFaceModelFacts`, key `iosFacePreflightKey`, `storesUsable` = realm custody ∧ face custody |
| ⚠⚠ The face custody: its own file `/inference/faces.runtime.fingerprint`, clears **templates only**, refuses SigLIP's file and SigLIP's key | `FaceTemplateCustody` (commonMain) |
| Model changes routed by model: a face commit or removal stands faces down, never search, and the reverse | `ByModelChanges` (replaces `PinnedModelChanges` when faces are bound) |
| The face readiness in every lifecycle path SigLIP's is in: writer 4 at each check, `sceneChanged`, retired with the graph (inside the same unload chain), the embedder closed on leaving the foreground | `PhotosOnDeviceAi.recheckOnce`, `sceneChanged`, `retire`; `ForegroundIndexing.closeSessions` |
| Capabilities from the graph: the Models screen's `features` are one per **bound** backend kind | `PhotosOnDeviceAi.features(backends)` → `OnDeviceModelsRepository.over(features = …)` (the constant no longer decides) |
| Threshold (FD3): no override; Photos' graph uses the common `FaceClustering.Measured` (0.44) | `ProxyModule`; pinned by `theFaceGraphClustersAtTheMeasuredOperatingPoint` |
| A40 amended (*Faces on iOS route 1*), A5's amendment points at it | `docs/sync/threat-model.md` |

**Custody separation is enforced four times:** (1) `faceBackendOver` refuses a readiness that is not the
face slot's (F4); (2) `coreMlFaceReadiness` takes a `FaceTemplateCustody` **by type**, so SigLIP's
`InferenceStoreCustody::applyFingerprint` (same lambda shape) cannot be passed; (3) the custody refuses
SigLIP's file in its constructor; (4) it refuses a SigLIP-shaped key (a text archive, or no detector
revision) and is then unusable, so nothing is served.

### 15.2 Not bound by F5, and why

✅ The first two items below were closed by F6 (§16).

- ⚠ **The face model's prepare and probe.** `ModelPreparer` and the bound `Preflight` are SigLIP's; with the
  flag on, a face model that was installed would sit in `Preparing` (nothing compiles it) and the face
  backend would claim nothing. F4's `Preflight.over(FacePreflightProber…)` and a second `ModelPreparer`
  (towers `[faceEmbedder]`, id `auraface-v1-int8`) are F6's, with the download. ✅ The preparer's sweep
  already keeps every installed record's digests, of any model, so a second model's compiled tower is not
  swept as an orphan.
- ⚠ **The Models screen's face rows.** `JoinedModelSource` is SigLIP's model alone, so nothing offers the
  face download yet; the face *consent* switch does appear with the flag on (it follows the bound
  backend), with search's copy. ⚠⚠ **So the face flag must not flip before F6**: the switch's *Turn off*
  does not yet say that it deletes this iPhone's face data, nor ask first.
- Calendar's graph is not asserted directly: `CalendarLocalRuntime.assemble` aborts the headless test
  host (`IosNotificationPresenter`), the reason `PhotosOwnedInferenceStoresTest` already gives; Mia, Drive
  and Control are, and now also assert no face backend, readiness or custody.

### 15.3 Guards, each removed once and seen red

Applied by a script, one at a time, the file restored after each run (`--rerun`, fresh XML).

| Removed or changed | Went red |
|---|---|
| `facesAdmitted` without the AI flag and gates | `eachStaticGateKeepsTheFaceBackendOutWithBothFlagsOn` |
| the runtime binding faces on the AI answer alone (face flag ignored) | `withTheFaceFlagOffSearchIsBoundAndFacesAreNot`, `theModelsScreenOffersTheFaceSwitchOnlyBesideAFaceBackend` |
| `coreml+vision` contributed without the face backend | `withTheFaceFlagOffSearchIsBoundAndFacesAreNot` |
| the Models screen's `features` a constant (search + faces) | `theModelsScreenOffersTheFaceSwitchOnlyBesideAFaceBackend` |
| ⚠⚠ the face readiness bound over SigLIP's `InferenceStoreCustody` (a raw `AiReadiness`, not `coreMlFaceReadiness`) | `theFaceModelBecomingReadyClearsOnlyTemplatesAndNeverTouchesSearch`, `aSearchKeyHandedToTheFaceCustodyIsRefusedAndNothingIsServed`, `aWithdrawalDeletesTheTemplatesWhileTheFaceCustodyHasFailed` |
| model changes pinned to SigLIP (`PinnedModelChanges`) | `aChangeToEachModelStandsOnlyThatModelDown` |
| `ByModelChanges`: SigLIP's branch falling through to `else -> change()` | `aChangeToEachModelStandsOnlyThatModelDown` |
| the face readiness not retired with the graph | `aTornDownGraphClaimsNoFaceAndItsFaceReadinessIsRetired` |
| `FaceTemplateCustody`: SigLIP-shaped key accepted | `searchsKeyIsRefusedAndTheTemplatesAreUnusableUntilAFaceKeyComes` |
| `FaceTemplateCustody`: SigLIP's file accepted | `itKeepsItsFingerprintInTheFaceSlotsFileAndNeverInSearchs` |
| `FaceTemplateCustody`: the file written before the clear | `aClearThatThrowsLeavesTheOldFileAndNothingUsable` |
| `faceBackendOver`: any slot accepted (F4's guard, re-run) | `theFaceBackendRefusesSearchsReadiness` |
| `TENSHU_PHOTOS_FACES: "YES"` in `project.yml` | `facesAreDeclaredAndOff` |

⚠ Not pinned on its own: `realmUsable` inside the face readiness's `storesUsable`. Every route to a
template (`faceGroups`, the pass) already stands behind the realm gate first, so removing it changes
nothing observable; it stays as defence in depth.

### 15.4 Tests

`PhotosFaceBindingTest` (iosTest, 18): both flags × both static gates; production's Info.plist; the
Models screen's features; `FaceClustering.Measured`; the face `Ready` clears templates of an unknown
making and leaves the vectors and SigLIP's fingerprint file byte-for-byte; search's key refused; model
changes per model; owner change and epoch change clear templates before `faceGroups` serves; withdrawal
deletes through a gate with no realm and through a failed face custody (the iOS twin of
`aWithdrawalDeletesTheTemplatesThroughAGateAFingerprintFailureClosed`); absence refuses and deletes
nothing; teardown retires the face readiness. `FaceTemplateCustodyTest` (commonTest, 6).
`PhotosBuildFlagsDeclaredTest` (desktopTest, 2): both plist keys fed from their settings, both `NO`.

---

## 16. ⭐ As built: F6, the face model's prepare, probe and Models row, and the face switch (2026-11-10)

**Built, and off.** Both flags stay `NO` in `photos-ios/project.yml`; nothing new is reached by a shipped
build. No stored or wire shape changed (the face preparer's markers are the compiled cache's own, the
verdict is the face slot's device-local file), so no HR 9 bump. ✅ The face entry is in the live catalog
(published 2026-11-10, §12.5), so a build with both flags forced on offers its download; shipped builds
contribute no `coreml+vision` and are offered nothing. ⚠ No suite here reads the live catalog: every
graph runs with none (`noCatalogThen`), so nothing in them changed with the publish.

### 16.1 What shipped

| Piece | Where |
|---|---|
| The face prepare step: `ModelPreparer` over `ReadinessSlot.FACES`, towers `[faceEmbedder]`, id `auraface-v1-int8`; the same compiled cache, step lock and sweep (every installed record's digests are live, of either model) | `photosOnDeviceAiModule`, `named(PhotosOnDeviceAi.FACE_PREPARER)`; `CoreMlCompiledModels` compiles and first-opens `faceEmbedder` with `CoreMlTowerSpec.faceEmbedder` (it refused any role but SigLIP's two); `coreMlFaceNotPrepared` |
| The face probe: `Preflight.over(faceReadiness, FacePreflightProber(CoreMlFacePreflightModels(…)))`, its own process-wide slow-probe record (`faceSlowProbes`), never beside search's probe | `named(PhotosOnDeviceAi.FACE_PREFLIGHT)` |
| One check for both: SigLIP's prepare → probe → warm-up, **then** the face model's prepare → probe, in the one coalesced sequence a launch, a foreground and a commit run | `PhotosOnDeviceAi.recheckOnce` → `faceCheck`; SigLIP's tail moved, unchanged, into `searchAfterTheCheck` |
| The download and removal: a face commit asks for the same check; a removal evicts the face model's compiled tower before its record goes (each preparer's `evicting` returns at once for another model's id) | `storeChanged`, `beforeRemoval`, `afterRemoval` of the `BackgroundModelDownloader` binding |
| The Models list: a `JoinedModelSource` over the face slot (its readiness, preparer, probe; the catalog entry by id **and** runtime `coreml+vision`), beside SigLIP's, under one `CombinedModelSource` | `PhotosOnDeviceAi.faceSource`; `CombinedModelSource` (`:control-shared-proxy` commonMain) |
| The switch's withdrawal, wired: a no that lands lets the embedder go and calls `faceGroups`, whose withdrawal-first deletion runs now rather than at the next People or pass | `JoinedModelSource.facesWithdrawn` ← `OnDeviceModelsRepository.withdrawn` |
| The copy (below), one feature row per switch, a question on a device's switch, per-model removal and *unsuitable* sentences, and *Index now* only where a switch that is on has its **own** model usable | `PhotosModelsCopy`, `photosModelsScreen` (`features`), `photosFeatureScreen` (`Device.question`); `ModelsUiState.serves` |
| SwiftUI: a row per feature; a device's question presented from the state, the switch unmoved until *Turn off* | `photos-ios/Sources/ModelsScreen.swift` |
| The fixture's two face states, `facesAbsent` and `facesOn` | `PhotosModelsFixture` |

**The copy.** ⚠⚠ Android's words wherever Android has them (`:control-shared`'s `consentHeadline`,
`featureDescription`, `turnOffText`, `turnOffEverywhereLabel`), pinned as literals by
`PhotosFacesCopyTest.theFaceCopyIsAndroidsWhereAndroidHasIt`:

| Where | Sentence |
|---|---|
| The feature | **Group photos by people** |
| What it does (the feature screen) | Find faces in your photos and group them so you can name people. Face data is made on each device you allow and never leaves it. Only the names you type sync to your devices. Turning this off deletes the face data and keeps the names. |
| While the switch is on (Models row, this device's row) | **Turning this off deletes this iPhone's face data; names stay.** |
| *Turn off* here asks | Turn off "Group photos by people" on this iPhone? / This iPhone's face data is deleted. The names you typed are kept, and other devices are not changed. / *Turn off* · *Keep on* |
| … another device | Turn off "Group photos by people" on ‹name›? / Its face data is deleted. The names you typed are kept, and other devices are not changed. |
| … everywhere | *Turn off everywhere and delete face data* → Turn off "Group photos by people" everywhere? / Face data is deleted on every device. The names you typed are kept. |
| The offer once the model is ready | Turn on "Group photos by people" for this iPhone? / The model is ready. Photos looks for faces in your library when you tap Index now. Face data is made on this iPhone and never leaves it; only the names you type sync. Turning this off deletes the face data and keeps the names. |
| Removing the face model | This frees about ‹N› on this iPhone. Grouping photos by people stops working here until a model is downloaded again. The switch for it is not changed, and face data already made stays until you turn it off. |
| A phone that cannot run it | This iPhone couldn't run the model well enough, so grouping photos by people stays off. |

⚠ *Removing the model deletes no face data* is true as built: the templates are the template store's,
and only an explicit no (or a new face key's custody) clears them. The sentence says so rather than
implying otherwise.

**Consent and withdrawal.**
- **On** is the realm's yes, then this device's (`ModelsViewModel.setRealmConsent`), as search's; it asks
  nothing. **Off** asks first everywhere face grouping's no deletes: this device, another device, every
  device. Search's screen is unchanged: only *everywhere* asks.
- ⚠⚠ **Absent ≠ off.** The screen shows a row that was never written as off (the consent read cannot tell
  them apart), promises no deletion over it and asks nothing. Only an explicit `false` deletes: the
  deletion is `FaceGroupsImplementation`'s `inferenceConsentWithdrawn`, so the screen's hook, run with no
  row written, deletes nothing (`anAbsentFaceRowDeletesNothingWhenTheScreenSaysAWithdrawal`).
- ⚠⚠ **Withdrawal deletes before the gate, and now.** A no that lands calls the operation People reads;
  its withdrawal-first deletion runs ahead of every gate (F5), so the templates go while the screen is
  still open. Names are `MediaLabel` rows and are never touched.

### 16.2 Decisions taken here

- **The face model's prepare and probe run after search's, in the same check**, never beside: the two
  preparers share the step lock and the compiled cache; the one coalesced check runs the two probes in
  turn, every face load (prepare, probe, backend) awaits search's probe, and search's backend (a query,
  the text tower's warm-up) awaits a face probe that holds the embedder — only one the graph has built
  (`FacePreflightBuilt`), so neither the wait nor a teardown constructs the face pair. The face embedder
  is not warmed after a check: it opens with the first face.
- ⚠⚠ ***Index now* follows the operation's rule**, `StartMediaIndexPassImplementation`'s: a pass needs a
  feature that is switched on **and** has a backend, i.e. its own model usable. Search's model ready
  with only face grouping on (face model absent or unsuitable), or the reverse, is a press refused
  `NO_CONSENT` every time: no button, and the line names the switched-on feature's model ("You can index
  your photos once the model for "Group photos by people" is ready." / "This iPhone can't run the model
  for "…", so it can't index for it."). With both switches off it names both. With one feature none of
  this can fire, and search's section is what it was.
- **One list, two sources** (`CombinedModelSource`): the list is no better than its worst part — any
  source *not connected* ends it, an unreadable store or *loading* stands for the whole list, the journal
  is unreadable if either says so, the catalog its worst answer. A call goes to its model's source; a
  model neither has is refused as *unknown*.
- ⚠ **What a row is about is the catalog's `serves`** (`ModelsUiState.serves`, new and defaulted
  empty); before the catalog is read (a cold, offline launch) it is the graph's own map of the models it
  prepares, `ModelsCapabilities.modelFeatures` (`PhotosOnDeviceAi.modelFeatures`, empty with faces off so
  search's capabilities are exactly what they were); with neither, a row is search's. Android's and
  desktop's states carry both and draw nothing from them.
- ⚠ **The on-device estimate is SigLIP's scaled** (`CoreMlDiskBudget.prepareBytes`): about 453 MB for the
  131 MB archive. The face tower's compile has not been measured; the figure is a ceiling, not a reading.
- ⚠ **Each source fetches the catalog** (two signed fetches per Models screen where faces are bound). Not
  coalesced: they are small, and a shared fetch would add a lifetime to get wrong.

### 16.3 Guards, each removed once and seen red

Applied by a script, one at a time, each file hashed before and restored after (`--rerun`, fresh XML;
Swift through `xcodebuild test` on the fixture).

| Removed or changed | Went red |
|---|---|
| `recheckOnce` without `faceCheck` | 5 of `PhotosFaceModelTest` (`theFaceModelIsPreparedAndProbedToReady…`, `aReadyFaceModelIsLeftAsItIs…`, `theModelsListShowsTheFaceModel…`, both *deletes … now* cases) |
| `CoreMlCompiledModels`: the `faceEmbedder` role (refused as "not a tower") | the same 5 |
| the face probe bound over SigLIP's readiness (`get()`, not the face slot) | the same 5 |
| the face preparer over SigLIP's readiness | the same 5 |
| the Models source not combined (search's alone) | `theModelsListShowsTheFaceModel…`, both *deletes … now* cases |
| `facesWithdrawn` calling nothing | `turningFacesOffOnTheModelsScreenDeletesTheTemplatesNowAndKeepsTheNames`, `theRealmsNoDeletesTheTemplatesNowAndKeepsTheNames` |
| `JoinedModelSource.consentWithdrawn` without its face branch | the same 2 |
| ⚠⚠ `facesWithdrawn` clearing the templates itself (no explicit-no test) | `anAbsentFaceRowDeletesNothingWhenTheScreenSaysAWithdrawal` |
| `CombinedModelSource`: a call to the first source, whatever the model | `aCallGoesToTheSourceOfItsModelAndAnUnknownModelIsRefused` |
| … *not connected* not standing for the list | `theListIsNoBetterThanItsWorstPart`, `theListEndsNotConnectedWhenEitherSourceDoes` |
| … *installed unreadable* not standing for the list; the catalog the first source's | `theListIsNoBetterThanItsWorstPart` (each) |
| copy: no question on this device's *off* | `turningFacesOffHereAsksFirstNamingWhatIsDeleted` |
| copy: no question on another device's *off* | `turningFacesOffForAnotherDeviceOrEverywhereAsksFirstToo` |
| copy: the delete sentence dropped from the face row | `theFaceRowSaysWhatTurningItOffDeletesWhileItIsOn`, `whileTheFaceModelIsNotReady…` |
| ⚠⚠ copy: the delete sentence said whether or not the switch is on (absent ≠ off) | `theModelsScreenOffersAFaceSwitchOfItsOwnBesideSearchs` |
| copy: usable asked of any model, not the feature's | `whileTheFaceModelIsNotReadyItsRowSaysItWorksOnceReady` |
| copy: every model read as search's (`capabilityOf`) | 4 of `PhotosFacesCopyTest` (removal text, *unsuitable*, the face row, the face screen) |
| copy: *Index now* gated on search's switch alone | `theIndexingSectionStartsWithEitherSwitchOn` |
| `ModelsViewModel` not carrying `serves` | `ModelsViewModelTest.whatEachModelServesIsTheScreensToRead` |
| *Index now* offered with a switch on and no model of **its own** usable | `faceGroupingOnWithoutItsModel…`, `faceGroupingOnWithAModelThisPhoneCannotRunSaysSo`, `searchOnWithoutItsModel…` |
| that line not saying *can't run* for an unsuitable model | `faceGroupingOnWithAModelThisPhoneCannotRunSaysSo` |
| both switches off naming search's alone | `theIndexingSectionStartsWithEitherSwitchOn` |
| `capabilityOf` without the graph's `modelFeatures` before the catalog | `beforeTheCatalogIsReadTheGraphSaysWhichModelIsFaceGroupings` |
| the graph not passing `modelFeatures` | `PhotosFaceModelTest.theModelsScreenKnowsWhichModelIsWhoseWithNoCatalog` |
| `retire` resolving the face probe (`getOrNull`) instead of the built one | `PhotosFaceModelTest.aTeardownBuildsNoFaceProbe` |
| Swift: a device's switch pressed without its question | `ModelsUITests.testTurningFacesOffAsksFirstAndSaysExactlyWhatIsDeleted`, `…ForAnotherDeviceOrEverywhereAsksFirstToo` |

⚠ **Not seen red:** the removal's `beforeRemoval` without the face preparer's `evicting` stayed green —
the post-removal sweep (SigLIP's preparer's `evicted`, which keeps only live digests) takes the face tower
anyway. What `evicting` adds is the order (the tower before the record, so a delete that fails fails the
removal), which no case here makes fail. Not pinned either: a face commit through the journal asking for
the check (`storeChanged`); the suites install with `ModelStore.add`. Nor search's backend waiting for a
face probe in flight: no case holds a face probe open under a search load.

**SigLIP, unchanged:** every case of `tenshu-sdk-ios-photos`'s iOS suite (`PhotosOnDeviceAiTest`,
`PhotosModelDownloadTest`, `PhotosWarmUpAfterAPassTest`, `PhotosFaceBindingTest`, …), `PhotosModelsCopyTest`,
`PhotosFeatureScreenTest`, `ModelsViewModelTest` and every search case of `ModelsUITests` green with no
line of their assertions changed. `PhotosFaceModelTest.removingTheFaceModelTakesItsArchiveAndTowerAndNothingOfSearchs`
holds SigLIP's record, archives, towers and `Ready` across the face model's removal, and
`theFaceModelIsPreparedAndProbedToReadyByTheCheckEveryLaunchRuns` that search's readiness and verdict file
saw nothing of the face check.

### 16.4 Tests

`PhotosFaceModelTest` (iosTest, 10; also: a teardown builds no face probe, and the graph's model map with no catalog): installed → compiled and first opened → probed → `Ready` through the
check; a second check changes nothing; no face preparer or probe without the flag; the Models list's face
row from the face slot; the removal; *Turn off* here and the realm's no each delete the templates now and
keep the name; an absent row deletes nothing. `PhotosFacesCopyTest` (desktopTest, 18; with *Index now*'s two scenarios, their mirror, *can't run*, and the cold catalog), `CombinedModelSourceTest`
(commonTest, 6), `ModelsViewModelTest.whatEachModelServesIsTheScreensToRead`. `ModelsUITests` (4 new, on the
fixture): the download row and *Preparing* and the offer, then realm-then-device; *Turn off* asks and names
what goes, *Keep on* writes nothing; another device and *everywhere* ask too, in the two-switch order; a
switch never written asks nothing, writes nothing on open, turns on realm first.

### 16.5 Still owed

- ✅ ~~The publish~~ (§12.5, done 2026-11-10). ⚠ A real download, compile and probe of the published
  archive through this binding has not run on any device: F8's.
- ✅ ~~F7 (People in SwiftUI)~~ (§17), then F8 on the phone: the real compile's time and size, the probe on live Vision.
- Not pinned by a test: the face source's catalog lookup by runtime (`coreml+vision`, not Android's
  `onnx+mediapipe` entry of the same id) — every graph in the suites runs with no catalog.

---

## 17. ⭐ As built: F7, People in SwiftUI (2026-11-10)

**Built, and off.** Both flags stay `NO` in `photos-ios/project.yml`; a shipped build binds no face
backend, so `groupingRunsHere` answers `false` exactly where the Swift literal did, and People draws
what it drew before F7: cards only for names synced from another device, round and centred as before,
no line, and a person's page the plain grid — ⚠⚠ no header, no naming (it could never save: the face
switch cannot exist with the flag off) and no "With…" (`PhotosSurface.personPage`, the card's
`groupingRunsHere`). No stored or wire
shape changed (HR 9 untouched): the new shared types are UI values, never stored or sent.

### 17.1 What shipped

| Piece | Where |
|---|---|
| `groupingRunsHere` from the installed graph, asked on every layout: `true` only where the assembly bound the face backend (F5's `facesAdmitted`), the face preflight has not found the phone unsuitable **and** the face readiness is not retired — so a graph the shared store's host stopped (B.16) answers `false` until the rebuild installs its own | `PhotosFaceGrouping.over(faceReadiness)` from `PhotosLocalRuntime.faceGrouping(assembly)` → `PhotosSurface.installFaceGrouping` at `install`, cleared by `installNotConnected`; `PhotosSurface.groupingRunsHere()` |
| The tab redraws when that answer moves (an install, a teardown, a verdict): the layout reads it live, but SwiftUI redraws only on a publish | `PhotosSurface.faceGroupingChanges()` (the face readiness's `state` and `retiredNow`) → `CollectionsModel.faceGrouping` |
| ⚠⚠ A person's page is a person's only where this phone groups faces; the card's F7 look likewise | `PhotosSurface.personPage(state, smartId, page)`; `PersonCardView(groupingRunsHere:)` |
| The tab and *View all* lay out through the surface, never a literal | `PhotosSurface.collectionsLayout(state)`, `PhotosSurface.sectionFailure(section, state)`; `CollectionsScreen.swift`, `CollectionGrids.swift` |
| People's lines name **Photos'** Settings → Models where this app runs its own proxy (the search bar's `ModelsSwitchHome` rule); Android's lines unchanged by default | `peopleStatusLine(…, modelsSwitch)`, `CollectionsLayout.of(…, modelsSwitch)` |
| The face crop, shared: the square Compose cut, moved out of `FaceAvatar` unchanged (Compose delegates; `FaceAvatarGeometryTest` green unchanged) | `faceCropSquare`, `faceCropRequestPx` (`:photos-shared-logic` `model/FaceCrop.kt`); `PhotosSurface.facePreview` / `facePreviewPx`; Swift `FaceCoverView` |
| The person card: a circle cut to the face; with no face, the whole photograph in a rounded card (Compose's `PersonCard` fallback); an unnamed person's title line blank | `PersonCardView` |
| The person page: header (face, name or *Add a name*, count), the naming action in the toolbar, the name prompt, the refusal alert, the "With…" chip and picker, *No photos of both yet* | `SmartGridScreen`, `PersonHeaderView`, `CompanionPicker` (`CollectionGrids.swift`) |
| Who is a person, whether naming is offered and whose verdict a dialog is — moved out of Compose's `App.kt` unchanged, both shells read it | `CollectionsUiState.personPage(smartId, page)` → `PersonPage` |
| Every sentence of the person page, Compose's words, both shells read them | `PersonPageCopy` (`:photos-shared-logic`); `AlbumDetailScreen` now reads it |
| The "With…" selection through aliases | `PersonHeader.chosenCompanion(withId)` |
| The fixture's People: `-photosPeople noModel|noConsent|notIndexed|none|faces`; two names synced from another device in every state; naming writes face-capability `MediaLabel` rows | `PhotosScreenshotFixture` |

**What each state shows on iOS** (both flags on):

| Proxy's answer | People section |
|---|---|
| `NO_BACKEND`, no face model installed | "To see people here, download the face model in Photos, under Settings → Models." |
| `NO_BACKEND`, the face model installed and being prepared or probed | "People appear once the face model is ready on this device." (`PhotosFaceGrouping.modelPending`) |
| `NO_CONSENT` | "To see people here, turn on "Group photos by people" for this device in Photos, under Settings → Models." |
| `NOT_INDEXED` | "People appear after Photos looks through your photos on this device. Tap Index now, under Settings → Models." |
| `Available`, nobody | "No people found yet." — ⚠ only with no synced names: a name is a person, and the line goes quiet |
| `Available`, clusters | the cards, named first in *View all*; no line |
| Failed read | "Couldn't load people." / "Couldn't refresh people." with *Try Again* |
| Anything else (`UNCALIBRATED`, a newer reason) | "People isn't available on this device yet." |
| Flags off, or the face preflight found the phone unsuitable, or the graph torn down | nothing: no line, no failure, no placeholder — only cards for synced names, drawn as before F7, opening a plain grid |

⚠ A refusal is said even beside synced names (`peopleStatusLine`'s rule): a name typed on Android shows
here from its labels alone while this phone groups nobody.

### 17.2 Decisions taken here

- ⚠⚠ **An unsuitable phone groups no faces** for People's purposes. The face backend is bound, but its
  preflight said this phone cannot run the model; People's `NO_BACKEND` line would then send the user to
  download a model that will never run, the lie search's `onNoBackend` avoids. The Models screen says
  why (F6's *unsuitable* sentence).
- ⚠ **The lines are iOS's own where iOS differs**, not Android's: Control-on-iOS has no Settings → Models
  (the switch and the download are Photos'), the face model is one artifact (Vision detects), and a pass
  starts with *Index now*, not Android's idle-and-charging schedule.
- **Naming follows Android exactly**: additive, never "Rename"; *Add a name* on an unnamed person,
  *Add another person* on a named one, *Finish naming* (with the interrupted name) after a partial save;
  no naming while narrowed by "With…". ⚠ **There is no rename and no clearing a name on either
  platform** — the data layer cannot rename (the name is part of the `MediaLabel` key); person-wide
  rename, merge and removal are F6c's (`photos-people-and-pets.md` §6a F6c.4), out of scope by FD11.
- ⚠ **Orientation: the region and the rung are both upright, by construction.** `FaceRegion` is a
  fraction of the upright frame — the index pass hands the backend no orientation
  (`MediaIndexer`, `orientation = null`), so the original's own EXIF applies — and a rung is minted
  upright (`ExifUpright`; Android's `AndroidGetPreviewImplementation`, iOS likewise). The cut is
  therefore taken of the decoded preview as it is; `TileImageLoader`'s EXIF transform is a no-op on a
  rung. ⚠ The residual is a **legacy** rung Android minted raw before `ExifUpright`: cut wrongly on
  iOS exactly as Android's own `FaceAvatar` cuts it today.
- ⚠⚠ **A person's header with no face draws the first photograph's `previewPath`** — an edited
  photograph's rendition, never its original (ED-3: a crop or Redact hides what the original shows),
  and a rendition not yet readable is a placeholder. `SmartAlbum.coverPath` and Compose's header
  already did.

### 17.3 Names synced from Android — exactly what the code does

- A name is a `MediaLabel` row with the face capability; it syncs. Clusters (`faceGroups`) are this
  device's alone and never sync. People is `peopleFrom(faceGroups, faceLabels)`, unchanged.
- **Before this phone has faces** (flags off, no model, no consent, not indexed): the name is a card
  from its labels alone — exactly its labelled photographs — with the whole first photograph as its
  cover (no face this phone can place).
- **Once this phone has clusters**: a cluster **every** photograph of which carries the name is folded
  into that person (no new card), lends its id as an alias and its largest face as the cover. A cluster
  that is only partly labelled stays an unnamed card of its own; a labelled photograph with two faces
  never supplies the cover. Nothing matches faces across devices: the only thing that crosses is the
  name on photographs.
  ⚠ *Amended 2026-11-11 (F6c-s, `photos-people-and-pets.md` §6a [as built](photos-people-and-pets.md#f6c-s--name-suggestions-as-built-2026-11-11)):*
  a partly-labelled cluster that the face grouping itself attributes to the name — by its
  single-face photographs — is now **folded on screen only** (confident, nothing written) or
  **asked** *"Is this Mia?"* (not confident). iOS shows both on the person page wherever
  `groupingRunsHere()`; the answers are the shared view model's. The FD11 row's *"iOS People has no
  suggestions until F6c"* is closed for this slice; F6c-2/-3's prototype suggestions remain out of
  scope by FD11.
- ⚠ Hence a name whose photographs no local cluster entirely covers keeps the whole photograph even
  with faces on — Leo in the fixture.

### 17.4 Guards, each removed once and seen red

Kotlin by a script, one at a time, each file hashed before and restored after (fresh results dirs);
Swift through `xcodebuild test` on `face-f7-builder`, the named cases only.

| Removed or changed | Went red |
|---|---|
| `PhotosSurface.groupingRunsHere()` the literal `false` | `PhotosPeopleGroupingTest.theTabAsksTheInstalledGraph…` |
| Swift: the tab laid out with `groupingRunsHere: false` (the pre-F7 literal) | `CollectionsUITests.testEachEmptyStateSaysWhyAndWhereToGo` |
| `faceGrouping` ignoring `Assembly.faces` (an answer for every graph) | `aGraphWithNoFaceBackendGroupsNoFaces`, `theTabAsksTheInstalledGraph…` |
| `PhotosFaceGrouping` ignoring the face preflight's *unsuitable* | `aPhoneTheFacePreflightFoundUnsuitableGroupsNoFaces` |
| `PhotosFaceGrouping` ignoring a retired readiness (a torn-down graph) | `aTornDownGraphGroupsNoFaces` |
| `modelPending` never passed to the layout | `aPhoneTheFacePreflightFoundUnsuitableGroupsNoFaces` (the pending line) |
| the pending line's branch removed (`peopleStatusLine`) | `CollectionsLayoutTest.peoplesLineNamesThisAppsModelsScreen…` |
| `changes` not following the readiness's state | `aPhoneTheFacePreflightFoundUnsuitableGroupsNoFaces` (no redraw on the verdict) |
| ⚠⚠ `PhotosSurface.personPage` not gated on `groupingRunsHere` | `PhotosPeopleGroupingTest.aPersonsPageIsAPlainGridWhereThisPhoneGroupsNoFaces` |
| ⚠⚠ Swift: the page from the ungated `personPage` (naming offered with the flags off) | `CollectionsUITests.testWithNoFaceBackendASyncedNameIsThePreF7CardAndPage` |
| ⚠⚠ Swift: the card drawn as F7's with the flags off | `CollectionsUITests.testWithNoFaceBackendASyncedNameIsThePreF7CardAndPage` |
| ⚠⚠ Swift: the header fallback drawing `Photo.path` (the original) | `CollectionsUITests.testAPersonHeaderWithNoFaceDrawsAnEditedPhotographsRendition` |
| `installNotConnected` keeping the graph's answer | `theTabAsksTheInstalledGraph…` |
| `peopleStatusLine` without its `THIS_APP` branch (Control's lines on iOS) | `CollectionsLayoutTest.peoplesLineNamesThisAppsModelsScreen…` |
| `faceCropSquare` ignoring the region (always the centred square) | `FaceCropTest` (2), `FaceAvatarGeometryTest.theCropIsTheFaceGrownByAMarginAndSquare` |
| ⚠⚠ Swift: the cut taken with no region (the photograph centred, as before F7) | `CollectionsUITests.testANameSyncedFromAndroidShowsItsFaceOnceThisPhoneHasFaces` |
| `personPage`: a snapshot card nameable | `PersonPageTest.aSnapshotCardIsAPersonButCannotBeNamedYet` |
| `personPage`: verdicts matched without the aliases | `PersonPageTest.aVerdictBelongsToThePersonItWasFor…`, `…anUnfinishedNamingIsTheNameThatWasBeingWritten…` |
| `chosenCompanion` without the aliases | `PersonPageTest.aCompanionIsFoundThroughTheirAliases` |
| Swift: *Save* calling nothing | `CollectionsUITests.testUnnamedClustersAreCards_andNamingOneTitlesIt` |
| Swift: naming offered while narrowed by "With…" | `CollectionsUITests.testANameSyncedFromAndroidShowsItsFaceOnceThisPhoneHasFaces` |

⚠ The first alias guard stayed green at first: the test recorded the verdict under the primary id,
which the page matches anyway. It went red only once the case recorded it under the cluster id that a
naming then folds into an alias — the case the aliases exist for.

⚠ **Not pinned on its own:** the card's own face branch (`PersonCardView` drawing `FaceCoverView`): the
card's accessibility value is read from `coverFace`, the same condition, so deleting the branch keeps it
"face". The header draws the same `FaceCoverView`, and that one is pinned. Nor `install` calling
`installFaceGrouping`: the tests call `faceGrouping(assembly)` and the surface directly,
since `install` hands the graph to the shared store's host.

### 17.5 Tests

`PhotosPeopleGroupingTest` (iosTest, 6): no face backend → no answer (each flag off, production's
plist); bound → `true`; awaiting its verdict → the pending line, then the face preflight's *unsuitable*
→ `false`, a change the tab redraws for; a torn-down graph → `false`; a person's page is a plain grid
where this phone groups no faces; the surface asks the installed graph, names Photos and *Index now*,
and forgets it on `installNotConnected`. `PersonPageTest` (commonTest, 6),
`FaceCropTest` (commonTest, 5), `CollectionsLayoutTest.peoplesLineNamesThisAppsModelsScreen…`.
`CollectionsUITests` (6 new, on the fixture): with no face backend People says nothing; with no face
backend and names synced from another device (`syncedOnly`), the pre-F7 card and a plain page — no
naming, no "With…"; a person header with no face draws an edited first photograph's rendition; each refusal's
line, with Mia from her labels and no face; unnamed clusters → name one → the page and the card are
titled, *Add another person*, never *Rename*; Mia's face once this phone has clusters, cut off-centre
(upper right), Leo's whole photograph, and "With…" narrowing with no naming while narrowed. Compose:
`CollectionsScreensTest`, `PeopleSectionTest`, `FaceAvatarGeometryTest` and the rest of
`:photos-shared-ui` green with no line of their assertions changed.

### 17.6 Owed to F8 (the device pass)

- With both flags on, on the phone: the cards and header cut from **real** rungs of real faces,
  including a portrait photograph whose rung Android minted (upright since `ExifUpright`; a legacy raw
  rung is the known residual, the same on Android).
- A name typed on iOS appears on Android and the reverse; a name typed on Android folds a fully-labelled
  iOS cluster into the person on iOS and lends it its face.
- Each empty-state line reached for real: before the download, consent off, before *Index now*, after.
- An unsuitable verdict silences People (the floor-class phone, if one is at hand).
- **F6c-s name suggestions** on the phone: the maintainer's own case — a cluster holding the two
  photographs named "Benedikt" plus ones only this phone indexed folds into Benedikt with *"N photos
  matched by face on this device"*; *Not Benedikt* unfolds it and it stays unfolded across a relaunch
  (the rejection is in `faces.log.rejections`, Photos' container); *Confirm* names them and the
  Android phone then folds them by label. Only the fixture (`-photosPeople suggest`) has run.
- ~~People refreshes after a pass that writes clusters but no new label~~ — **fixed 2026-11-11**
  (`photos-ios.md` §8.22.3, as-built *People after an index pass*): the tab follows the index runner's
  pass endings, not the library watch. ⚠ Still owed on the phone: a pass with the tab shown redraws when
  it ends, and a return after one walks (`Collections ->` timings, not `walk=skipped (clean)`). The pass
  line's new `faces=` field says whether the face half ran at all.
